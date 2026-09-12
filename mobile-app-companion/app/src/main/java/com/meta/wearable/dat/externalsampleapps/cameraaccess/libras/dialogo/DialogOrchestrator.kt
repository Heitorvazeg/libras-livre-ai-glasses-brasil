/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// DialogOrchestrator - Dono do DialogState e de todas as transições
//
// Ver docs/orquestracao-dialogo-audio-plano.md §5, §6.5. Coordena WakeWordDetector,
// LandmarkPipeline (reconhecimento de sinal), Speaker (TTS), AudioSessionManager (A2DP<->HFP) e
// SttEngine (transcrição) — nenhum componente decide roteamento de áudio ou o que uma wake word
// significa por conta própria, tudo passa por aqui ("só existe um dono do áudio por vez", §5).
//
// A costura sinal->frase continua sendo responsabilidade DESTA classe, mas ela agora DELEGA a
// resolução: palavrasReconhecidas acumula uma glosa por boundary do SignBoundaryDetector (via
// onSignRecognized) e endSignSession() entrega a lista ao GlossContextualizer
// (docs/contextualizacao-glosa-seq2seq-plano.md §3). O joinToString(" ") que existia aqui era
// um placeholder explícito; ele sobrevive como PassthroughGlossContextualizer, último degrau
// do fallback.
//
// "Libras Livre, iniciar"/"encerrar" também ligam/desligam a câmera+stream dos óculos (não só a
// sessão lógica de captura), via os callbacks ensureCameraActive/deactivateCamera injetados pelo
// CameraViewModel (que é quem sabe startSession/startStreaming/stopStreaming) — mesma lógica de
// "nenhum componente decide sozinho", aplicada agora também ao hardware da câmera.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.AudioSessionManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.Speaker
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.SttEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWordDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.GlossContextualizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LandmarkPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DialogOrchestrator(
    private val scope: CoroutineScope,
    private val landmarkPipeline: LandmarkPipeline,
    private val speaker: Speaker,
    private val audioSessionManager: AudioSessionManager,
    private val sttEngine: SttEngine,
    // Glossário -> frase em PT-BR. Cadeia montada em criarGlossContextualizer():
    // modelo .tflite sob guarda -> template -> passthrough (§3.3).
    private val contextualizer: GlossContextualizer,
    // Liga a câmera/stream dos óculos sob demanda (①→②) e espera até estar pronta pra capturar,
    // ou false se não conseguiu (sessão/stream não subiu a tempo — ver CameraViewModel). Injetado
    // porque só o CameraViewModel sabe operar o DeviceSession/Stream do DAT (ver header).
    private val ensureCameraActive: suspend () -> Boolean,
    // Desliga o stream (câmera+display) — chamado assim que uma sessão de sinais fecha (②→③),
    // já que a captura de vídeo não é mais necessária dali em diante no ciclo.
    private val deactivateCamera: () -> Unit,
    // Handoff pro pipeline texto->glosa->avatar (docs/vlibras-webview-plano.md) — ainda não
    // implementado nesta branch, por isso é só um callback injetado (ver §6.5 do plano).
    private val onAvatarText: (String) -> Unit,
) {

  companion object {
    private const val TAG = "Libras:DialogOrchestrator"

    // Estados em que a wake word deve estar ouvindo (docs/orquestracao-dialogo-audio-plano.md
    // §5): "O detector de wake word fica ativo em ①②④⑤ [...] só pausa em ③⑥⑦".
    private val WAKE_WORD_ACTIVE_STATES =
        setOf(
            DialogState.AGUARDANDO_SINAL,
            DialogState.CAPTURANDO_SINAIS,
            DialogState.AGUARDANDO_RESPOSTA,
            DialogState.ESCUTANDO_ATENDENTE,
        )

    // Corta "Libras Livre, encerrar" (com variações comuns de pontuação/caixa) do final da
    // transcrição — a frase pode vazar pro texto reconhecido pelo STT (plano §4 item 3, §6.4).
    private val TRAILING_ENCERRAR_PATTERN =
        Regex("""\s*libras\s+livre,?\s+encerrar[.!?]?\s*$""", RegexOption.IGNORE_CASE)

    // Timeout de inatividade nas duas sessões ATIVAS (② capturando sinais, ⑤ escutando
    // atendente) — se ninguém sinalizar/falar por 1 minuto, encerra sozinho, como se "Libras
    // Livre, encerrar" tivesse sido ouvido (§7 Fase 7). NÃO se aplica aos estados de espera
    // (①④) — lá só a wake word real ou o botão de fallback disparam a transição.
    private const val IDLE_TIMEOUT_MS = 60_000L
  }

  private val _state = MutableStateFlow(DialogState.AGUARDANDO_SINAL)
  val state: StateFlow<DialogState> = _state.asStateFlow()

  private var wakeWordDetector: WakeWordDetector? = null

  // Timer de inatividade das sessões ativas (② e ⑤) — um só campo porque as duas são mutuamente
  // exclusivas no state machine (nunca as duas ativas ao mesmo tempo). Ver IDLE_TIMEOUT_MS.
  private var idleTimeoutJob: Job? = null

  // Glosas reconhecidas na sessão em curso — uma por boundary (ver
  // docs/sign-boundary-detector-plano.md §5.3). Ao "encerrar", a lista vai inteira para o
  // [contextualizer], que decide como ela vira frase — esta classe não sabe (nem deve saber) se
  // a resolução veio do modelo, do template ou do passthrough.
  private val palavrasReconhecidas = mutableListOf<String>()

  /** Liga a fonte de wake words (hoje, [SpeechRecognizerWakeWordDetector]) — chamar uma vez, na
   * criação. */
  fun attachWakeWordDetector(detector: WakeWordDetector) {
    wakeWordDetector = detector
    if (_state.value in WAKE_WORD_ACTIVE_STATES) detector.start()
  }

  /**
   * Tenta (re)ligar o detector real depois que RECORD_AUDIO é concedido em tempo de execução (ver
   * CameraViewModel.enableWakeWordListening) — sem isso, [SpeechRecognizerWakeWordDetector.start]
   * silenciosamente não faz nada até a próxima chamada de [attachWakeWordDetector]/[setState], que
   * pode nunca vir se o estado atual já é um dos ativos. No-op se o estado atual não é um dos que
   * espera wake word.
   */
  fun resumeWakeWordDetectorIfActive() {
    if (_state.value in WAKE_WORD_ACTIVE_STATES) wakeWordDetector?.start()
  }

  /** Chamado pelo [WakeWordDetector] ativo (motor real ou botão) quando uma frase é ouvida. */
  fun onWakeWord(word: WakeWord) {
    when (_state.value) {
      DialogState.AGUARDANDO_SINAL -> if (word == WakeWord.INICIAR) beginSignSession()
      DialogState.CAPTURANDO_SINAIS -> if (word == WakeWord.ENCERRAR) endSignSession()
      DialogState.AGUARDANDO_RESPOSTA -> if (word == WakeWord.INICIAR) beginListening()
      DialogState.ESCUTANDO_ATENDENTE -> if (word == WakeWord.ENCERRAR) endListening()
      DialogState.FALANDO,
      DialogState.TRANSCREVENDO,
      DialogState.GERANDO_AVATAR ->
          Log.w(TAG, "Wake word '$word' ignorada em ${_state.value} (deveria estar pausada)")
    }
  }

  /**
   * Chamado pelo LandmarkPipeline a cada sinal reconhecido dentro da sessão em curso — um por
   * boundary do SignBoundaryDetector, não mais uma vez por sessão inteira (§5.3). Só acumula;
   * quem decide quando falar é [endSignSession].
   */
  fun onSignRecognized(text: String) {
    if (text.isNotBlank()) palavrasReconhecidas.add(text)
    // Conta como atividade — reinicia o timeout de 1 min de inatividade (§7 Fase 7).
    resetIdleTimeout(DialogState.CAPTURANDO_SINAIS) { endSignSession() }
  }

  /**
   * Chamado pelo LandmarkPipeline quando um segmento não é reconhecido. Um sinal perdido não
   * aborta a sessão inteira — só não entra na frase final; a sessão segue capturando o
   * próximo sinal normalmente (LandmarkPipeline já reinicia o buffer sozinho por boundary). Não
   * mexe na câmera: ela continua ligada o tempo todo dentro de uma sessão de sinais, só
   * [endSignSession] a desliga.
   */
  fun onSignRecognitionFailed() {
    Log.w(TAG, "Um segmento da sessão não foi reconhecido — seguindo o resto da sessão")
    // Um gesto foi tentado (só não reconhecido) — ainda conta como atividade pro timeout de 1 min
    // (§7 Fase 7): a pessoa está sinalizando, só não com sucesso.
    resetIdleTimeout(DialogState.CAPTURANDO_SINAIS) { endSignSession() }
  }

  // Evita que uma segunda "Libras Livre, iniciar" (a wake word continua ativa em ①) dispare uma
  // segunda chamada de ensureCameraActive() enquanto a primeira ainda está subindo a câmera.
  private var startingSignSession = false

  private fun beginSignSession() {
    if (startingSignSession) return
    startingSignSession = true
    scope.launch {
      try {
        if (!ensureCameraActive()) {
          Log.w(TAG, "Câmera/stream não ficou pronta a tempo — 'Libras Livre, iniciar' ignorado")
          return@launch
        }
        palavrasReconhecidas.clear()
        setState(DialogState.CAPTURANDO_SINAIS)
        resetIdleTimeout(DialogState.CAPTURANDO_SINAIS) { endSignSession() }
        landmarkPipeline.startSession()
      } finally {
        startingSignSession = false
      }
    }
  }

  private fun endSignSession() {
    // Pode ser chamado pela wake word real, pelo botão de fallback, ou pelo próprio timeout de
    // inatividade (§7 Fase 7) — cancela o timer nos três casos (idempotente se já disparou).
    cancelIdleTimeout()
    // Pausa a wake word já aqui, no instante em que "encerrar" foi ouvido — mesmo que ainda
    // falte esperar a classificação de um sinal em aberto (abaixo).
    setState(DialogState.FALANDO)
    scope.launch {
      // Suspende até LandmarkPipeline terminar: força classificar um segmento em aberto, se
      // houver, e espera qualquer classificação já em voo — só depois disso a lista de
      // palavras está completa (§5.3).
      landmarkPipeline.endSession()
      // A câmera não é mais necessária dali em diante no ciclo (fala, escuta e transcrição são só
      // áudio) — desliga só agora, depois que endSession() processou o que faltava (ela pode
      // depender dos últimos frames capturados); a próxima "iniciar" (beginSignSession) religa
      // sob demanda.
      deactivateCamera()
      val glosas = palavrasReconhecidas.toList()
      palavrasReconhecidas.clear()
      if (glosas.isNotEmpty()) {
        val resultado = contextualizer.contextualize(glosas)
        Log.i(TAG, "glosas=$glosas -> \"${resultado.texto}\" (${resultado.origem})")
        if (resultado.texto.isNotBlank()) speaker.speakAndAwait(resultado.texto)
      }
      setState(DialogState.AGUARDANDO_RESPOSTA)
    }
  }

  private fun beginListening() {
    setState(DialogState.ESCUTANDO_ATENDENTE)
    scope.launch {
      val device = audioSessionManager.acquireListening()
      if (device == null) {
        Log.w(TAG, "Sem dispositivo SCO disponível — volta pra AGUARDANDO_RESPOSTA sem escutar")
        setState(DialogState.AGUARDANDO_RESPOSTA)
        return@launch
      }
      sttEngine.start(
          onResult = { text -> onAttendantTranscribed(text) },
          onError = { onAttendantTranscriptionFailed() },
      )
      // Sem VAD/resultado parcial disponível ainda (SttEngine só dispara onResult/onError uma vez,
      // no fim — ver docs/orquestracao-dialogo-audio-plano.md §6.4), então este timer é fixo desde
      // o início da escuta, não reinicia por atividade de fala como o de ② faz por gesto — §7
      // Fase 7 registra essa diferença como limitação conhecida.
      resetIdleTimeout(DialogState.ESCUTANDO_ATENDENTE) { endListening() }
    }
  }

  private fun endListening() {
    // Pode ser chamado pela wake word real, pelo botão de fallback, ou pelo timeout de
    // inatividade acima — cancela o timer nos três casos (idempotente se já disparou).
    cancelIdleTimeout()
    setState(DialogState.TRANSCREVENDO)
    // Corta a captura agora — como se o atendente tivesse parado de falar neste instante. O
    // resultado chega de forma assíncrona via o onResult/onError já configurado em
    // beginListening().
    sttEngine.stop()
  }

  private fun onAttendantTranscribed(rawText: String) {
    if (_state.value != DialogState.TRANSCREVENDO) return
    val text = rawText.replace(TRAILING_ENCERRAR_PATTERN, "").trim()
    scope.launch {
      audioSessionManager.releaseListening()
      setState(DialogState.GERANDO_AVATAR)
      onAvatarText(text)
      setState(DialogState.AGUARDANDO_SINAL)
    }
  }

  private fun onAttendantTranscriptionFailed() {
    if (_state.value != DialogState.TRANSCREVENDO) return
    audioSessionManager.releaseListening()
    // Permite tentar de novo com "Libras Livre, iniciar" sem reabrir a sessão de sinais inteira.
    setState(DialogState.AGUARDANDO_RESPOSTA)
  }

  // (Re)inicia o timer de inatividade — cancela qualquer um pendente antes (cobre tanto "resetar
  // o relógio por atividade nova" quanto "trocar de estado ativo"). onTimeout só dispara se o
  // estado ainda for o mesmo de quando o timer foi armado — evita disparo tardio depois de uma
  // transição legítima (wake word real, botão) já ter mudado de estado.
  private fun resetIdleTimeout(whileInState: DialogState, onTimeout: () -> Unit) {
    idleTimeoutJob?.cancel()
    idleTimeoutJob =
        scope.launch {
          delay(IDLE_TIMEOUT_MS)
          if (_state.value == whileInState) onTimeout()
        }
  }

  private fun cancelIdleTimeout() {
    idleTimeoutJob?.cancel()
    idleTimeoutJob = null
  }

  // Único ponto que muda o estado E decide se a wake word deve estar ouvindo — "nenhum
  // componente decide roteamento por conta própria" (plano §5).
  private fun setState(newState: DialogState) {
    _state.value = newState
    if (newState in WAKE_WORD_ACTIVE_STATES) {
      wakeWordDetector?.start()
    } else {
      wakeWordDetector?.pause()
    }
  }
}

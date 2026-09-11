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
// A costura sinal->frase (como uma sequência de sinais reconhecidos vira uma frase falável) é
// responsabilidade DESTA classe: palavrasReconhecidas acumula uma palavra por boundary do
// SignBoundaryDetector (via onSignRecognized), e endSignSession() junta com espaço ao
// "encerrar" — placeholder explícito no lugar da tabela combinacoesConhecidas real (ver
// docs/sign-boundary-detector-plano.md §5.3).
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LandmarkPipeline
import kotlinx.coroutines.CoroutineScope
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
  }

  private val _state = MutableStateFlow(DialogState.AGUARDANDO_SINAL)
  val state: StateFlow<DialogState> = _state.asStateFlow()

  private var wakeWordDetector: WakeWordDetector? = null

  // Palavras reconhecidas na sessão de sinais em curso — uma por boundary (ver
  // docs/sign-boundary-detector-plano.md §5.3). Junta com espaço ao "encerrar": placeholder
  // explícito no lugar da tabela combinacoesConhecidas real, que não existe implementada em
  // lugar nenhum do repo ainda (mobile-app-companion/README.md, item de checklist em aberto).
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
        landmarkPipeline.startSession()
      } finally {
        startingSignSession = false
      }
    }
  }

  private fun endSignSession() {
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
      val frase = palavrasReconhecidas.joinToString(" ")
      palavrasReconhecidas.clear()
      if (frase.isNotBlank()) {
        speaker.speakAndAwait(frase)
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
    }
  }

  private fun endListening() {
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

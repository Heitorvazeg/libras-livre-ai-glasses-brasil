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
//
// [NOVO — docs/confirmacao-e-modo-economia-plano.md] Dois pontos do feedback da banca de
// 2026-09-15, os dois reaproveitando peças que já existiam em vez de construir caminho novo:
//  - ②.5 CONFIRMANDO_RECONHECIMENTO: mostra pro SURDO (via playAvatar, o mesmo do ⑦) a frase que
//    o sistema entendeu antes de falar pro atendente, com uma janela curta pra correção por
//    sinal novo (ver endSignSession/confirmarReconhecimento e o branch de
//    onSignRecognized/onSignRecognitionFailed nesse estado).
//  - onBatteryLow(): reage aos eventos de bateria baixa/crítica do DAT (BATTERY_LOW/
//    BATTERY_CRITICAL, disparados pelo CameraViewModel a partir de session.errors/
//    stream.errorStream) desligando a câmera e pulando ②/②.5/③ pro resto do atendimento — só o
//    sentido atendente→surdo (fala/avatar) continua.

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
    // Handoff pro pipeline texto->glosa->avatar (docs/vlibras-webview-plano.md §6, Fase 5).
    // Suspende até a animação terminar (ou desistir), para que ⑦ só volte a ① quando a pessoa
    // surda de fato tiver visto a resposta. Devolve false quando o avatar não estava disponível
    // — quem chama decide o fallback.
    private val playAvatar: suspend (String) -> Boolean,
    // Pré-carrega o avatar no INÍCIO do atendimento, não no ⑦: o Unity leva 6-9 s para ficar
    // pronto (medido, §0.7 do plano), e esse tempo cabe escondido atrás de ②③④⑤⑥. Criar não é
    // mostrar.
    private val prepareAvatar: () -> Unit,
    // Fim do atendimento: devolve os ~300 MB do processo do renderer.
    private val releaseAvatar: () -> Unit,
    // Último recurso quando o avatar não subiu: falar a resposta e mostrar a legenda, em vez de
    // travar em ⑦ (§6, Fase 5 do plano).
    private val onAvatarUnavailable: (String) -> Unit,
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

    // [NOVO] Janela de correção em ②.5 CONFIRMANDO_RECONHECIMENTO, depois que o avatar termina
    // de mostrar a frase reconhecida pro surdo — docs/confirmacao-e-modo-economia-plano.md §1.3.
    // Bem mais curta que IDLE_TIMEOUT_MS de propósito: aqui não é uma sessão de sinalização
    // inteira, é só uma pausa de "tem certeza?". Palpite, não medido — calibrar com pessoas
    // surdas reais (ver plano, §1.6).
    private const val CONFIRMATION_WINDOW_MS = 6_000L

    // [NOVO] Anunciada pro atendente quando onBatteryLow() liga o modo economia — ele é quem
    // ouve (está de óculos), e é ele quem vai precisar explicar pra pessoa surda que o
    // reconhecimento de sinais parou de funcionar por ora.
    private const val MENSAGEM_MODO_ECONOMIA =
        "Bateria dos óculos baixa. A câmera foi desligada. A conversa continua só por voz."
  }

  private val _state = MutableStateFlow(DialogState.AGUARDANDO_SINAL)
  val state: StateFlow<DialogState> = _state.asStateFlow()

  // [NOVO] Modo economia de bateria (docs/confirmacao-e-modo-economia-plano.md §2) — ligado uma
  // vez por onBatteryLow() e nunca desligado sozinho (o DAT não expõe um evento de "bateria
  // recuperada", só os dois limiares de baixa/crítica). Exposto pra UI mostrar um aviso
  // persistente, já que a mudança de modo não é óbvia olhando só o DialogState.
  private val _economiaBateria = MutableStateFlow(false)
  val economiaBateria: StateFlow<Boolean> = _economiaBateria.asStateFlow()

  private var wakeWordDetector: WakeWordDetector? = null

  // Timer de inatividade/janela de confirmação (②, ②.5 e ⑤) — um só campo porque os três são
  // mutuamente exclusivos no state machine (nunca dois ativos ao mesmo tempo). Ver
  // IDLE_TIMEOUT_MS/CONFIRMATION_WINDOW_MS.
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
      DialogState.AGUARDANDO_SINAL ->
          if (word == WakeWord.INICIAR) {
            // [NOVO] Modo economia: pula ②/②.5/③ inteiros — sem câmera não há sinal pra
            // capturar, então "iniciar" aqui passa a significar a mesma coisa que em ④ (escutar
            // o atendente), não abrir uma sessão de sinais que nunca vai coletar nada.
            if (_economiaBateria.value) beginListening() else beginSignSession()
          }
      DialogState.CAPTURANDO_SINAIS -> if (word == WakeWord.ENCERRAR) endSignSession()
      DialogState.AGUARDANDO_RESPOSTA -> if (word == WakeWord.INICIAR) beginListening()
      DialogState.ESCUTANDO_ATENDENTE -> if (word == WakeWord.ENCERRAR) endListening()
      DialogState.CONFIRMANDO_RECONHECIMENTO,
      DialogState.FALANDO,
      DialogState.TRANSCREVENDO,
      DialogState.GERANDO_AVATAR ->
          Log.w(TAG, "Wake word '$word' ignorada em ${_state.value} (deveria estar pausada)")
    }
  }

  /**
   * Chamado pelo LandmarkPipeline a cada sinal reconhecido dentro da sessão em curso — um por
   * boundary do SignBoundaryDetector, não mais uma vez por sessão inteira (§5.3). Em
   * CAPTURANDO_SINAIS só acumula; quem decide quando falar é [endSignSession]. Em
   * CONFIRMANDO_RECONHECIMENTO (②.5, docs/confirmacao-e-modo-economia-plano.md §1.3) um sinal
   * novo significa "não é isso" — descarta a frase mostrada e reabre a captura com este sinal já
   * como o primeiro da tentativa seguinte.
   */
  fun onSignRecognized(text: String) {
    if (_state.value == DialogState.CONFIRMANDO_RECONHECIMENTO) {
      Log.i(TAG, "Correção durante a confirmação — sinal novo vira início da próxima tentativa")
      palavrasReconhecidas.clear()
      if (text.isNotBlank()) palavrasReconhecidas.add(text)
      setState(DialogState.CAPTURANDO_SINAIS)
      resetIdleTimeout(DialogState.CAPTURANDO_SINAIS) { endSignSession() }
      return
    }
    if (text.isNotBlank()) palavrasReconhecidas.add(text)
    // Conta como atividade — reinicia o timeout de 1 min de inatividade (§7 Fase 7).
    resetIdleTimeout(DialogState.CAPTURANDO_SINAIS) { endSignSession() }
  }

  /**
   * Chamado pelo LandmarkPipeline quando um segmento não é reconhecido. Em CAPTURANDO_SINAIS um
   * sinal perdido não aborta a sessão inteira — só não entra na frase final; a sessão segue
   * capturando o próximo sinal normalmente (LandmarkPipeline já reinicia o buffer sozinho por
   * boundary). Em CONFIRMANDO_RECONHECIMENTO conta como pedido de correção do mesmo jeito que
   * [onSignRecognized] — a pessoa tentou sinalizar de novo, só não deu pra classificar; reabre
   * CAPTURANDO_SINAIS do zero (sem um texto pra usar como primeiro sinal). Nenhum dos dois casos
   * mexe na câmera: ela continua ligada o tempo todo dentro de uma sessão de sinais OU da
   * confirmação que a sucede, só [endSignSession]/[confirmarReconhecimento] a desligam.
   */
  fun onSignRecognitionFailed() {
    if (_state.value == DialogState.CONFIRMANDO_RECONHECIMENTO) {
      Log.i(TAG, "Tentativa de correção não reconhecida — reabre CAPTURANDO_SINAIS do zero")
      palavrasReconhecidas.clear()
      setState(DialogState.CAPTURANDO_SINAIS)
      resetIdleTimeout(DialogState.CAPTURANDO_SINAIS) { endSignSession() }
      return
    }
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
    // Guarda defensiva: onWakeWord já desvia pra beginListening() em modo economia, mas
    // beginSignSession() não devia depender só disso pra nunca ligar a câmera nesse modo.
    if (_economiaBateria.value) {
      Log.i(TAG, "Modo economia ativo — ignorando 'iniciar' de sinalização (só voz)")
      return
    }
    startingSignSession = true
    scope.launch {
      try {
        if (!ensureCameraActive()) {
          Log.w(TAG, "Câmera/stream não ficou pronta a tempo — 'Libras Livre, iniciar' ignorado")
          return@launch
        }
        palavrasReconhecidas.clear()
        // Começa a carregar o Unity agora, invisível: até chegarmos ao ⑦ terão passado ②③④⑤⑥,
        // tempo de sobra para os 6-9 s de carga (§4.2 do plano).
        prepareAvatar()
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
    // [NOVO] Pausa a wake word já aqui, mas em CONFIRMANDO_RECONHECIMENTO agora, não em FALANDO —
    // ainda falta mostrar a frase pro SURDO confirmar antes de falar pro atendente (§1 do plano
    // de confirmação). A câmera continua ligada: a sessão de confirmação a reabre logo abaixo.
    setState(DialogState.CONFIRMANDO_RECONHECIMENTO)
    scope.launch {
      // Suspende até LandmarkPipeline terminar: força classificar um segmento em aberto, se
      // houver, e espera qualquer classificação já em voo — só depois disso a lista de
      // palavras está completa (§5.3).
      landmarkPipeline.endSession()
      val glosas = palavrasReconhecidas.toList()
      palavrasReconhecidas.clear()
      if (glosas.isEmpty()) {
        // Nada reconhecido — não há o que confirmar nem falar. Desliga a câmera e segue como
        // antes.
        deactivateCamera()
        setState(DialogState.AGUARDANDO_RESPOSTA)
        return@launch
      }
      val resultado = contextualizer.contextualize(glosas)
      Log.i(TAG, "glosas=$glosas -> \"${resultado.texto}\" (${resultado.origem})")
      if (resultado.texto.isBlank()) {
        deactivateCamera()
        setState(DialogState.AGUARDANDO_RESPOSTA)
        return@launch
      }
      // Mostra pro SURDO o que foi entendido, reaproveitando o mesmo avatar do ⑦ — ANTES de
      // falar pro atendente (docs/confirmacao-e-modo-economia-plano.md §1.2). playAvatar() já
      // cai pra legenda sozinho (onAvatarUnavailable) se o avatar não subir; aqui isso não muda
      // o fluxo, só o caminho degradado é visual.
      if (!playAvatar(resultado.texto)) onAvatarUnavailable(resultado.texto)
      // playAvatar() suspende (até 45 s, AVATAR_TIMEOUT_MS do CameraViewModel) — onBatteryLow()
      // pode ter mudado o estado por baixo enquanto isso. Sem este guard, reabriríamos a captura
      // (landmarkPipeline.startSession()) depois que o modo economia já desligou a câmera.
      if (_state.value != DialogState.CONFIRMANDO_RECONHECIMENTO) return@launch
      // Reabre a captura para a janela de correção (§1.3) — DEPOIS que o avatar já terminou de
      // animar, não durante: limita o tempo em que MediaPipe e o Unity do avatar coexistem
      // (vlibras-webview-plano.md §4.2) à janela de confirmação, não à animação inteira.
      landmarkPipeline.startSession()
      resetIdleTimeout(DialogState.CONFIRMANDO_RECONHECIMENTO) {
        confirmarReconhecimento(resultado.texto)
      }
    }
  }

  /**
   * Fim da janela de confirmação (§1.3) sem nenhuma correção: a pessoa surda não sinalizou de
   * novo, então a frase mostrada está confirmada e segue pro atendente. Chamado só pelo timeout
   * de [CONFIRMATION_WINDOW_MS] — uma correção real nunca chega aqui, porque
   * [onSignRecognized]/[onSignRecognitionFailed] já tiram o estado de CONFIRMANDO_RECONHECIMENTO
   * antes que este timeout dispare (ver [resetIdleTimeout]: só age se o estado não mudou).
   */
  private fun confirmarReconhecimento(texto: String) {
    scope.launch {
      // Fecha a sessão de confirmação (idle — nenhum segmento em aberto, closeSession() é barato
      // aqui) antes de desligar a câmera, mesmo padrão do fim de ②.
      landmarkPipeline.endSession()
      deactivateCamera()
      speaker.speakAndAwait(texto)
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
      // Suspende até o avatar terminar de sinalizar. Se ele não estava disponível (sem WebGL,
      // assets ausentes, renderer morto, sem rede e sem cache), a resposta ainda chega à pessoa
      // surda pelo caminho degradado — nunca ficamos presos em ⑦.
      if (!playAvatar(text)) onAvatarUnavailable(text)
      // O atendimento terminou este turno; o avatar fica carregado para o próximo, e só é
      // destruído quando o atendimento inteiro encerra por inatividade (§4.2).
      setState(DialogState.AGUARDANDO_SINAL)
      resetIdleTimeout(DialogState.AGUARDANDO_SINAL) { encerrarAtendimento() }
    }
  }

  private fun onAttendantTranscriptionFailed() {
    if (_state.value != DialogState.TRANSCREVENDO) return
    audioSessionManager.releaseListening()
    // Permite tentar de novo com "Libras Livre, iniciar" sem reabrir a sessão de sinais inteira.
    setState(DialogState.AGUARDANDO_RESPOSTA)
  }

  /**
   * [NOVO] Chamado pelo CameraViewModel quando o DAT reporta `DeviceSessionError.BATTERY_CRITICAL`
   * (sessão) ou `StreamError.BATTERY_LOW` (stream) — docs/confirmacao-e-modo-economia-plano.md
   * §2. Liga o modo economia de vez pro resto do atendimento (o SDK não expõe um evento de
   * "bateria recuperada" pra desligar sozinho): desliga a câmera agora se ela estava em uso, e a
   * partir daqui "Libras Livre, iniciar" em ① passa a abrir escuta do atendente (④) em vez de
   * sessão de sinais (②) — ver o branch de AGUARDANDO_SINAL em [onWakeWord]. Idempotente: um
   * segundo evento de bateria não repete o anúncio nem reprocessa nada.
   */
  fun onBatteryLow() {
    if (_economiaBateria.value) return
    _economiaBateria.value = true
    Log.w(TAG, "Bateria baixa/crítica nos óculos — modo economia ligado (câmera desligada dali em diante)")
    val comCameraAberta =
        _state.value == DialogState.CAPTURANDO_SINAIS ||
            _state.value == DialogState.CONFIRMANDO_RECONHECIMENTO
    if (comCameraAberta) {
      // Bateria crítica é urgente — não tenta preservar o que já foi capturado nesta sessão,
      // só encerra. resetIdleTimeout ainda não disparou; cancela pra não competir com esta
      // transição.
      cancelIdleTimeout()
      palavrasReconhecidas.clear()
      setState(DialogState.AGUARDANDO_RESPOSTA)
    }
    scope.launch {
      if (comCameraAberta) {
        landmarkPipeline.endSession()
        deactivateCamera()
      }
      speaker.speakAndAwait(MENSAGEM_MODO_ECONOMIA)
    }
  }

  /**
   * Fim do ATENDIMENTO (não do turno): ninguém interagiu por [IDLE_TIMEOUT_MS] depois que o
   * avatar respondeu. É aqui que os ~300 MB do renderer voltam para o sistema — destruir por
   * turno faria a próxima resposta esperar de novo os 6-9 s de carga do Unity (§4.2).
   */
  private fun encerrarAtendimento() {
    Log.i(TAG, "Atendimento ocioso — liberando o avatar")
    releaseAvatar()
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

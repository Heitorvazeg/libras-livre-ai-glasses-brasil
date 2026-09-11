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

  // Glosas reconhecidas na sessão em curso — uma por boundary (ver
  // docs/sign-boundary-detector-plano.md §5.3). Ao "encerrar", a lista vai inteira para o
  // [contextualizer], que decide como ela vira frase — esta classe não sabe (nem deve saber) se
  // a resolução veio do modelo, do template ou do passthrough.
  private val palavrasReconhecidas = mutableListOf<String>()

  /** Liga a fonte de wake words (hoje, [ManualWakeWordDetector]) — chamar uma vez, na criação. */
  fun attachWakeWordDetector(detector: WakeWordDetector) {
    wakeWordDetector = detector
    if (_state.value in WAKE_WORD_ACTIVE_STATES) detector.start()
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
   * próximo sinal normalmente (LandmarkPipeline já reinicia o buffer sozinho por boundary).
   */
  fun onSignRecognitionFailed() {
    Log.w(TAG, "Um segmento da sessão não foi reconhecido — seguindo o resto da sessão")
  }

  private fun beginSignSession() {
    palavrasReconhecidas.clear()
    setState(DialogState.CAPTURANDO_SINAIS)
    landmarkPipeline.startSession()
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

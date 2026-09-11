/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// TtsEngine - Motor de síntese de voz, trocável
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4 item 10, §6.6, §8 item 4. Interface nova —
// Speaker.kt (fachada usada pelo DialogOrchestrator) passava a chamar android.speech.tts.TextToSpeech
// direto, sem nenhuma interface (diferente do padrão já usado por WakeWordDetector/SttEngine). Essa
// assimetria some aqui: Speaker vira uma casca fina que delega pra um TtsEngine, permitindo trocar o
// motor sem tocar no DialogOrchestrator (que só conhece Speaker.speakAndAwait).
//
// AndroidTextToSpeechEngine (neste arquivo) é a implementação-base, usando a API nativa do Android —
// era o que Speaker.kt fazia direto antes desta interface existir. PiperSherpaOnnxTtsEngine.kt é o
// motor real (local, Piper via sherpa-onnx) — ver header daquele arquivo pro motivo da escolha.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

interface TtsEngine {
  /** Fala [text] e suspende até terminar (erro incluso — nunca lança). Chamável da main thread. */
  suspend fun speakAndAwait(text: String)

  /** Interrompe a fala em andamento, se houver. */
  fun stop()

  /** Libera recursos — chamado só no teardown do dono (ex.: onCleared do ViewModel). */
  fun shutdown()
}

/**
 * Implementação-base usando `android.speech.tts.TextToSpeech` — motor do sistema, não local. Ver
 * header sobre por que não é mais o motor padrão (fica como fallback atrás da mesma interface).
 */
class AndroidTextToSpeechEngine(context: Context) : TtsEngine {

  companion object {
    private const val TAG = "Libras:AndroidTtsEngine"
  }

  @Volatile private var ready = false

  // O OnInitListener dispara de forma assíncrona, depois que o construtor retorna — então `tts`
  // já está atribuído quando o callback acessa setLanguage().
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          val result = tts.setLanguage(Locale("pt", "BR"))
          ready =
              result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
          if (!ready) Log.w(TAG, "pt-BR indisponível no TTS do aparelho (result=$result)")
        } else {
          Log.e(TAG, "Falha ao inicializar TextToSpeech (status=$status)")
        }
      }

  override suspend fun speakAndAwait(text: String) {
    if (!ready) {
      Log.w(TAG, "TTS ainda não está pronto — ignorando \"$text\"")
      return
    }
    val utteranceId = "libras-${System.nanoTime()}"
    suspendCancellableCoroutine<Unit> { cont ->
      tts.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
              if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
              if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }

            override fun onError(id: String?, errorCode: Int) {
              if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
          }
      )
      cont.invokeOnCancellation { tts.stop() }
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
  }

  override fun stop() {
    runCatching { tts.stop() }
  }

  override fun shutdown() {
    runCatching {
      tts.stop()
      tts.shutdown()
    }
  }
}

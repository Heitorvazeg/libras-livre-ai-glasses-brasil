/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AndroidTextToSpeechEngine - TtsEngine usando android.speech.tts.TextToSpeech
//
// Motor do sistema: é a voz de RESERVA da TtsEmCadeia (docs/prontidao-demo/05-audio.md §5.5), criada
// só quando o Piper falha. Segue o roteamento padrão do Android e não respeita o seletor de saída
// de voz (limitação aceita no 5.1). Avisa o fim no onDone, que já é o fim real da reprodução (5.3).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidTextToSpeechEngine(context: Context) : TtsEngine {

  companion object {
    private const val TAG = "Libras:AndroidTtsEngine"
    // A inicialização é assíncrona: como reserva, o motor é criado no momento em que precisa falar.
    private const val ESPERA_PRONTO_MS = 3_000L
  }

  private val pronto = CompletableDeferred<Boolean>()

  // O OnInitListener dispara de forma assíncrona, depois que o construtor retorna — então `tts`
  // já está atribuído quando o callback acessa setLanguage().
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          val result = tts.setLanguage(Locale("pt", "BR"))
          val ok = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
          if (!ok) Log.w(TAG, "pt-BR indisponível no TTS do aparelho (result=$result)")
          pronto.complete(ok)
        } else {
          Log.e(TAG, "Falha ao inicializar TextToSpeech (status=$status)")
          pronto.complete(false)
        }
      }

  override suspend fun aquecer(frases: List<String>): Boolean =
      withTimeoutOrNull(ESPERA_PRONTO_MS) { pronto.await() } ?: false

  override suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit): Boolean {
    if (withTimeoutOrNull(ESPERA_PRONTO_MS) { pronto.await() } != true) {
      Log.w(TAG, "TTS do Android indisponível — não falou \"$text\"")
      return false
    }
    val utteranceId = "libras-${System.nanoTime()}"
    return suspendCancellableCoroutine { cont ->
      tts.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
              if (id == utteranceId) runCatching(onInicioAudio)
            }

            override fun onDone(id: String?) {
              if (id == utteranceId && cont.isActive) cont.resume(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
              if (id == utteranceId && cont.isActive) cont.resume(false)
            }

            override fun onError(id: String?, errorCode: Int) {
              if (id == utteranceId && cont.isActive) cont.resume(false)
            }
          }
      )
      cont.invokeOnCancellation { tts.stop() }
      if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS && cont.isActive) {
        cont.resume(false)
      }
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

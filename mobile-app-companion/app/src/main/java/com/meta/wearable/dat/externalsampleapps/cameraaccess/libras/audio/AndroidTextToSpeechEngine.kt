/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AndroidTextToSpeechEngine - impl-base de TtsEngine usando android.speech.tts.TextToSpeech
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4 item 10, §6.6. Motor do sistema (não local) —
// fica como fallback atrás da mesma interface; o motor padrão é o PiperSherpaOnnxTtsEngine (local).
// Era o que o Speaker chamava direto antes da interface TtsEngine existir.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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

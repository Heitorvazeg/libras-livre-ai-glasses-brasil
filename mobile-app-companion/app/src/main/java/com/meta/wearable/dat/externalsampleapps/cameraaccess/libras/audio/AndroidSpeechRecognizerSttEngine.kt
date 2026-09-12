/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AndroidSpeechRecognizerSttEngine - impl-base de SttEngine usando a API nativa do Android
//
// Ver docs/orquestracao-dialogo-audio-plano.md §6.4, §8 item 2. Implementação mínima (sem
// dependência nova) pra destravar o DialogOrchestrator sem escolher o motor real (Vosk).
// android.speech.SpeechRecognizer gerencia a própria captura de mic internamente, seguindo o
// roteamento de áudio corrente do sistema — não expõe uma API de "alimentar PCM manualmente", por
// isso, ao contrário do VoskSttEngine, esta impl não usa PcmMicCapture. O AudioSessionManager já
// deve ter trocado pra HFP (acquireListening) antes de start() — é essa troca que faz a captura vir
// do mic dos óculos em vez do mic do celular.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class AndroidSpeechRecognizerSttEngine(context: Context) : SttEngine {
  private val context: Context = context.applicationContext
  private var recognizer: SpeechRecognizer? = null

  companion object {
    private const val TAG = "Libras:SttEngine"
  }

  override fun start(onResult: (String) -> Unit, onError: (Throwable) -> Unit) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      onError(IllegalStateException("SpeechRecognizer indisponível neste aparelho"))
      return
    }

    val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    recognizer = speechRecognizer
    speechRecognizer.setRecognitionListener(
        object : RecognitionListener {
          override fun onResults(results: Bundle) {
            val text =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
              onError(IllegalStateException("Transcrição vazia"))
            } else {
              onResult(text)
            }
          }

          override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer error=$error")
            onError(RuntimeException("SpeechRecognizer error=$error"))
          }

          override fun onReadyForSpeech(params: Bundle?) {}

          override fun onBeginningOfSpeech() {}

          override fun onRmsChanged(rmsdB: Float) {}

          override fun onBufferReceived(buffer: ByteArray?) {}

          override fun onEndOfSpeech() {}

          override fun onPartialResults(partialResults: Bundle?) {}

          override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    )

    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        }
    speechRecognizer.startListening(intent)
  }

  override fun stop() {
    recognizer?.stopListening()
    recognizer?.destroy()
    recognizer = null
  }
}

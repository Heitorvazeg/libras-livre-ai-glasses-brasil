/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// SttEngine - Transcrição da resposta do atendente
//
// Ver docs/orquestracao-dialogo-audio-plano.md §6.4, §8 item 2. Interface trocável: o motor de
// verdade (on-device/offline, ex. Vosk) não foi escolhido ainda — AndroidSpeechRecognizerSttEngine
// é a implementação-base mínima, usando a API nativa do Android (sem dependência nova), pra
// destravar o DialogOrchestrator sem essa decisão. android.speech.SpeechRecognizer já gerencia a
// própria captura de mic internamente seguindo o roteamento de áudio corrente do sistema — ele
// não expõe uma API de "alimentar PCM manualmente", por isso não existe aqui uma classe de
// captura crua separada (diferente do que um motor tipo Vosk exigiria). O AudioSessionManager já
// deve ter trocado pra HFP (acquireListening) antes de start() — é essa troca que faz a captura
// vir do mic dos óculos em vez do mic do celular.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

interface SttEngine {
  /**
   * Começa a escutar. [onResult] é chamado no máximo uma vez com a transcrição; [onError] no
   * máximo uma vez se a captura falhar ou não render nada. Deve ser chamado a partir da main
   * thread (SpeechRecognizer exige um Looper).
   */
  fun start(onResult: (String) -> Unit, onError: (Throwable) -> Unit)

  /**
   * Encerra a escuta agora — o áudio capturado até aqui é finalizado como se o atendente tivesse
   * parado de falar neste instante (dispara [onResult]/[onError] de forma assíncrona, não aqui).
   * É assim que a wake word "encerrar" corta a captura, em vez de depender de detecção de
   * silêncio.
   */
  fun stop()
}

/** Implementação mínima usando a API nativa do Android — ver header sobre por que não Vosk/Porcupine ainda. */
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

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// SpeechRecognizerWakeWordDetector - motor real de wake word, mic do celular
//
// Ver docs/orquestracao-dialogo-audio-plano.md §8 item 1. Entre as duas rotas discutidas lá
// (Porcupine vs. modelo TFLite próprio), esta é uma terceira via adotada para destravar a
// funcionalidade sem depender de conta/licença externa (Porcupine) nem de um dataset de áudio
// próprio (TFLite): reaproveita android.speech.SpeechRecognizer — a mesma API já usada em
// SttEngine.kt — em modo de escuta contínua (reinicia sozinho a cada resultado/erro), comparando
// cada transcrição contra as duas frases fixas por regex. Zero dependência nova, mas não é
// keyword-spotting de verdade: historicamente depende de rede em muitos aparelhos (o objetivo do
// projeto é on-device/offline — ver PoC/api/README.md) e gasta mais bateria/CPU que um motor
// dedicado. Fica atrás da mesma interface WakeWordDetector para ser trocada por Porcupine/TFLite
// depois sem tocar no DialogOrchestrator.
//
// Concorrência não validada em hardware real (docs §4 item 3, §7 Fase 0): este detector precisa
// continuar rodando (mic do celular) durante DialogState.ESCUTANDO_ATENDENTE, ao mesmo tempo que
// o SttEngine também usa um SpeechRecognizer (mic dos óculos via HFP). Dois reconhecedores do
// sistema ativos ao mesmo tempo, no mesmo processo, é um cenário sem garantia documentada — pode
// funcionar (mics diferentes) ou um dos dois pode ser suspenso pelo serviço de reconhecimento do
// aparelho. Só teste em hardware real confirma; os botões de fallback continuam servindo de
// contorno se isso falhar.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

class SpeechRecognizerWakeWordDetector(
    context: Context,
    private val onWakeWord: (WakeWord) -> Unit,
) : WakeWordDetector {

  private val context: Context = context.applicationContext

  // SpeechRecognizer exige criação/uso a partir de uma thread com Looper — a main serve e mantém
  // este detector coerente com o resto do pacote (DialogOrchestrator roda em viewModelScope,
  // Dispatchers.Main).
  private val handler = Handler(Looper.getMainLooper())

  companion object {
    private const val TAG = "Libras:WakeWordDetector"

    // Intervalo entre o fim de uma escuta e o início da próxima — evita um loop apertado de
    // criar/destruir SpeechRecognizer que alguns serviços de reconhecimento (OEM) não toleram bem.
    private const val RESTART_DELAY_MS = 300L

    private val INICIAR_PATTERN = Regex("""libras\s+livre,?\s+iniciar""", RegexOption.IGNORE_CASE)
    private val ENCERRAR_PATTERN = Regex("""libras\s+livre,?\s+encerrar""", RegexOption.IGNORE_CASE)
  }

  @Volatile private var active = false
  private var recognizer: SpeechRecognizer? = null

  // Garante no máximo um WakeWord por utterance (parcial + final podem ambos casar o mesmo
  // trecho) — resetado a cada novo ciclo de escuta em restart().
  private var firedThisUtterance = false

  override fun start() {
    if (active) return
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "RECORD_AUDIO não concedido — wake word real não inicia (botões seguem valendo)")
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      Log.w(TAG, "SpeechRecognizer indisponível neste aparelho — wake word real não inicia")
      return
    }
    active = true
    listenOnce()
  }

  override fun pause() {
    active = false
    recognizer?.let { runCatching { it.stopListening() } }
  }

  override fun stop() {
    active = false
    recognizer?.let { runCatching { it.destroy() } }
    recognizer = null
  }

  private fun listenOnce() {
    if (!active) return
    firedThisUtterance = false

    val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    recognizer = speechRecognizer
    speechRecognizer.setRecognitionListener(
        object : RecognitionListener {
          override fun onResults(results: Bundle) {
            handleTranscript(
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            )
            restart(speechRecognizer)
          }

          override fun onError(error: Int) {
            // ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT são o caso comum (silêncio ambiente) — não é
            // falha, só reinicia o ciclo.
            if (
                error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
              Log.d(TAG, "Wake word listener error=$error")
            }
            restart(speechRecognizer)
          }

          override fun onReadyForSpeech(params: Bundle?) {}

          override fun onBeginningOfSpeech() {}

          override fun onRmsChanged(rmsdB: Float) {}

          override fun onBufferReceived(buffer: ByteArray?) {}

          override fun onEndOfSpeech() {}

          // Reage já no parcial pra reduzir a latência entre a frase falada e a ação — não espera
          // o SpeechRecognizer decidir que a pessoa parou de falar.
          override fun onPartialResults(partialResults: Bundle?) {
            handleTranscript(
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
            )
          }

          override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    )

    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    speechRecognizer.startListening(intent)
  }

  private fun handleTranscript(text: String?) {
    if (firedThisUtterance || text.isNullOrBlank()) return
    val word =
        when {
          INICIAR_PATTERN.containsMatchIn(text) -> WakeWord.INICIAR
          ENCERRAR_PATTERN.containsMatchIn(text) -> WakeWord.ENCERRAR
          else -> null
        } ?: return
    firedThisUtterance = true
    onWakeWord(word)
  }

  private fun restart(finished: SpeechRecognizer) {
    finished.destroy()
    if (recognizer === finished) recognizer = null
    if (!active) return
    handler.postDelayed({ if (active) listenOnce() }, RESTART_DELAY_MS)
  }
}

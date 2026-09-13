/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// OpenWakeWordDetector - motor real de wake word, local/offline (.onnx)
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4 item 9, §6.3, §7 Fase 3, §8 item 1. Segunda
// implementação de WakeWordDetector — SpeechRecognizerWakeWordDetector (mesma interface) continua
// no código como motor de destravamento/fallback (§4 item 7); trocar qual o CameraViewModel
// instancia é a única mudança necessária pra ativar este motor.
//
// Usa com.rementia.openwakeword.lib.WakeWordEngine (vendorizado em com/rementia/openwakeword/lib/
// — biblioteca não publicada em nenhum repositório Maven/JitPack, ver header de WakeWordEngine.kt),
// que por baixo roda mel-spectrogram.onnx + embedding_model.onnx (modelos oficiais do openWakeWord,
// fixos, Apache 2.0) + um classificador .onnx por frase — CONTRAI a suposição original do plano de
// ".tflite": esta biblioteca de integração Android usa ONNX Runtime, não TFLite (confirmado direto
// no código-fonte dela). O pipeline de treino do openWakeWord em si exporta nativamente em ONNX
// (TFLite é que exige conversão) — não muda o §7 Fase 3, só a extensão do arquivo final.
//
// Pré-requisito pra este motor funcionar (nenhum dos três existe neste repo ainda):
//  - app/src/main/assets/melspectrogram.onnx e embedding_model.onnx — modelos fixos do openWakeWord,
//    baixar prontos (não precisam de treino).
//  - app/src/main/assets/wakeword/libras_livre_iniciar.onnx e libras_livre_encerrar.onnx — os dois
//    classificadores custom, treinados via notebook automatic_model_training.ipynb do openWakeWord
//    com voz sintética pt-BR (ver §7 Fase 3) — este é o trabalho real pendente, não a integração
//    Android em si.
//
// Gerencia o próprio mic (AudioRecorder vendorizado, MediaRecorder.AudioSource.MIC, 16kHz mono) —
// não aceita PCM externo, então não reaproveita PcmMicCapture.kt (a captura de PCM configurável
// usada pelo STT). Mesmo mic do celular usado por SpeechRecognizerWakeWordDetector (§4 item 1).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenWakeWordDetector(
    context: Context,
    private val onWakeWord: (WakeWord) -> Unit,
) : WakeWordDetector {

  private val context: Context = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    private const val TAG = "Libras:OpenWakeWord"

    // Nomes usados tanto no WakeWordModel (aparecem em WakeWordDetection.model.name) quanto no
    // mapeamento de volta pro enum WakeWord abaixo.
    private const val NAME_INICIAR = "Libras Livre, iniciar"
    private const val NAME_ENCERRAR = "Libras Livre, encerrar"

    // Classificadores custom — ver header pro pipeline de treino pendente (§7 Fase 3).
    private const val MODEL_INICIAR = "wakeword/libras_livre_iniciar.onnx"
    private const val MODEL_ENCERRAR = "wakeword/libras_livre_encerrar.onnx"

    // Limiares independentes, calibrados na curva de wake-word-model/resultados/*/relatorio.md
    // (split sintético + validação genérica, sem ambiente real ainda — Fase 3, §7). Os dois
    // classificadores respondem diferente ao limiar — WakeWordModel aceita um valor por modelo,
    // então cada um usa o ponto que dá mais recall sem aumentar o falso-positivo genérico em
    // relação ao próximo limiar acima. Reavaliar depois de medir em hardware real —
    // "Threshold Guidelines" em WakeWordModel.kt vendorizado.
    private const val THRESHOLD_INICIAR = 0.3f
    private const val THRESHOLD_ENCERRAR = 0.4f
  }

  @Volatile private var active = false
  private var engine: WakeWordEngine? = null
  private var detectionJob: Job? = null

  override fun start() {
    if (active) return
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "RECORD_AUDIO não concedido — openWakeWord não inicia (botões seguem valendo)")
      return
    }

    val realEngine =
        engine
            ?: runCatching { buildEngine() }
                .onFailure { e ->
                  Log.e(TAG, "Falha ao carregar os modelos do openWakeWord (assets ausentes?)", e)
                }
                .getOrNull()
                ?.also { engine = it }
            ?: return

    active = true
    detectionJob?.cancel()
    detectionJob =
        scope.launch {
          realEngine.detections.collect { detection ->
            val word =
                when (detection.model.name) {
                  NAME_INICIAR -> WakeWord.INICIAR
                  NAME_ENCERRAR -> WakeWord.ENCERRAR
                  else -> null
                }
            if (word != null) onWakeWord(word)
          }
        }
    realEngine.start()
  }

  override fun pause() {
    active = false
    detectionJob?.cancel()
    detectionJob = null
    runCatching { engine?.stop() }
  }

  override fun stop() {
    pause()
    runCatching { engine?.release() }
    engine = null
  }

  private fun buildEngine(): WakeWordEngine {
    val models =
        listOf(
            WakeWordModel(name = NAME_INICIAR, modelPath = MODEL_INICIAR, threshold = THRESHOLD_INICIAR),
            WakeWordModel(
                name = NAME_ENCERRAR, modelPath = MODEL_ENCERRAR, threshold = THRESHOLD_ENCERRAR),
        )
    // ALL, não SINGLE_BEST: "iniciar" e "encerrar" são gatilhos distintos por estado (ver
    // DialogOrchestrator.onWakeWord), não variações do mesmo comando — mesmo raciocínio do
    // exemplo "Smart Home Controller" em DetectionMode.kt vendorizado.
    return WakeWordEngine(context = context, models = models, detectionMode = DetectionMode.ALL)
  }
}

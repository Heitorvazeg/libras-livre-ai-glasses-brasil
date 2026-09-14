/*
 * Os classificadores pt-BR de wake word carregam no ONNX Runtime do Android?
 *
 * Existe porque eles NÃO carregavam, e nada acusava: os pesos ficam num arquivo separado
 * (libras_livre_*.onnx.data) e o runner lia o .onnx como bytes, sem caminho de onde o ORT
 * resolver o .data. O OpenWakeWordDetector engolia a exceção num log e o motor simplesmente não
 * subia. Construir o WakeWordEngine é o teste certo: ele cria as sessões ONNX no construtor.
 *
 * Instrumentado (exige emulador/aparelho): o AssetManager e o onnxruntime-android não existem
 * na JVM dos testes de unidade.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeWordModelosCarregamTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  // Mesmos caminhos de OpenWakeWordDetector (lá são privados).
  private val classificadores =
      listOf("wakeword/libras_livre_iniciar.onnx", "wakeword/libras_livre_encerrar.onnx")

  @Test
  fun cadaClassificadorCarregaSozinho() {
    // Um por vez: se só um estiver quebrado, a falha diz qual.
    for (caminho in classificadores) {
      val engine =
          runCatching {
                WakeWordEngine(
                    context = context,
                    models = listOf(WakeWordModel(name = caminho, modelPath = caminho, threshold = 0.5f)),
                    detectionMode = DetectionMode.ALL,
                )
              }
              .getOrElse { e -> throw AssertionError("$caminho não carregou: ${e.cause ?: e}", e) }
      engine.release()
    }
  }

  @Test
  fun osDoisCarregamJuntosComoNoDetector() {
    val engine =
        WakeWordEngine(
            context = context,
            models = classificadores.map { WakeWordModel(name = it, modelPath = it, threshold = 0.5f) },
            detectionMode = DetectionMode.ALL,
        )
    engine.release()
  }
}

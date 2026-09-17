/*
 * Vendorizado de github.com/Re-MENTIA/openwakeword-android-kt (main, 2026-09-11), Apache License
 * 2.0 — ver WakeWordEngine.kt (pacote pai) pro contexto de por que está vendorizado em vez de
 * dependência Gradle. `modelPath` é o classificador custom por frase (WakeWordModel.modelPath) —
 * os dois `.onnx` treinados pras frases "Libras Livre, iniciar/encerrar"
 * (docs/orquestracao-dialogo-audio-plano.md §4 item 9, §7 Fase 3).
 *
 * ÚNICA DIVERGÊNCIA DO ORIGINAL: [createSession] com pesos externos. Os classificadores treinados
 * em wake-word-model/ saem com os pesos num arquivo separado (`<modelo>.onnx.data`), e o original
 * só abria o `.onnx` como bytes — sem caminho, o ONNX Runtime não tem de onde resolver o `.data`
 * e falha ("filesystem error ... libras_livre_iniciar.onnx.data"). Quando o `.data` existe nos
 * assets, os dois são copiados para [cacheDir] e a sessão abre pelo caminho. Coberto por
 * WakeWordModelosCarregamTest (androidTest).
 */
package com.rementia.openwakeword.lib.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer

/**
 * Handles ONNX model loading and inference for wake word detection.
 *
 * @param cacheDir onde copiar o modelo quando ele tem pesos externos (ver header).
 */
internal class OnnxModelRunner(
    private val assetManager: AssetManager,
    private val modelPath: String,
    private val cacheDir: File,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession()

    companion object {
        private const val BATCH_SIZE = 1
        private const val EXTERNAL_DATA_SUFFIX = ".data"
    }

    private fun createSession(): OrtSession {
        return try {
            val externalData = modelPath + EXTERNAL_DATA_SUFFIX
            if (assetExists(externalData)) {
                env.createSession(copyToDisk(listOf(modelPath, externalData)).absolutePath)
            } else {
                assetManager.open(modelPath).use { inputStream ->
                    val modelBytes = inputStream.readBytes()
                    env.createSession(modelBytes)
                }
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to load model: $modelPath", e)
        }
    }

    private fun assetExists(path: String): Boolean {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return assetManager.list(dir)?.contains(name) == true
    }

    /**
     * Copia os assets para o mesmo diretório em [cacheDir], com os mesmos nomes — o `.onnx`
     * guarda a localização do `.data` relativa a si mesmo. Recopia sempre (são ~300 KB, uma vez
     * por engine), para uma atualização do APK nunca abrir pesos antigos. Cada arquivo é escrito
     * num temporário e renomeado: uma cópia interrompida não deixa um arquivo pela metade.
     *
     * @return o arquivo do primeiro asset (o `.onnx`).
     */
    private fun copyToDisk(assets: List<String>): File {
        val destinos = assets.map { asset ->
            val destino = File(cacheDir, "onnx/$asset")
            destino.parentFile?.mkdirs()
            val temporario = File(destino.parentFile, destino.name + ".tmp")
            assetManager.open(asset).use { input ->
                temporario.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temporario.renameTo(destino)) {
                temporario.delete()
                throw IOException("Não consegui mover $temporario para $destino")
            }
            destino
        }
        return destinos.first()
    }

    /**
     * Run inference on the wake word detection model.
     *
     * @param inputArray 3D float array of shape [1, features, embeddings]
     * @return Prediction score between 0.0 and 1.0
     */
    fun predictWakeWord(inputArray: Array<Array<FloatArray>>): Float {
        var inputTensor: OnnxTensor? = null

        return try {
            inputTensor = OnnxTensor.createTensor(env, inputArray)

            session.run(mapOf(session.inputNames.first() to inputTensor)).use { outputs ->
                val result = outputs[0].value as Array<FloatArray>
                result[0][0]
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to run inference", e)
        } finally {
            inputTensor?.close()
        }
    }

    override fun close() {
        session.close()
    }
}

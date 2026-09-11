/*
 * Vendorizado de github.com/Re-MENTIA/openwakeword-android-kt (main, 2026-09-11), Apache License
 * 2.0 — ver WakeWordEngine.kt (pacote pai) pro contexto de por que está vendorizado em vez de
 * dependência Gradle. Conteúdo idêntico ao original, salvo este comentário. Espera o asset fixo
 * `embedding_model.onnx` (modelo de embedding de fala do openWakeWord, Apache 2.0, congelado —
 * ver docs/orquestracao-dialogo-audio-plano.md §4 item 9) na raiz de app/src/main/assets/.
 */
package com.rementia.openwakeword.lib.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager

/**
 * Handles embedding generation from mel-spectrograms using ONNX model.
 */
internal class EmbeddingModel(
    private val assetManager: AssetManager
) : AutoCloseable {

    companion object {
        private const val EMBEDDING_MODEL = "embedding_model.onnx"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * Generate embeddings from mel-spectrogram windows.
     *
     * @param input 4D array of shape [batch, height, width, channels]
     * @return 2D array of embeddings
     */
    fun generateEmbeddings(input: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        var session: OrtSession? = null
        var inputTensor: OnnxTensor? = null

        return try {
            assetManager.open(EMBEDDING_MODEL).use { inputStream ->
                val modelBytes = inputStream.readBytes()
                session = env.createSession(modelBytes)
            }

            inputTensor = OnnxTensor.createTensor(env, input)

            session!!.run(mapOf("input_1" to inputTensor)).use { results ->
                val rawOutput = results[0].value as Array<Array<Array<FloatArray>>>

                // Reshape from (41, 1, 1, 96) to (41, 96)
                Array(rawOutput.size) { i ->
                    rawOutput[i][0][0].copyOf()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate embeddings", e)
        } finally {
            inputTensor?.close()
            session?.close()
        }
    }

    override fun close() {
        // env is managed globally by OrtEnvironment
    }
}

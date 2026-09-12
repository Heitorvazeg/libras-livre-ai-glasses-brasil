/*
 * Vendorizado de github.com/Re-MENTIA/openwakeword-android-kt (main, 2026-09-11), Apache License
 * 2.0 — ver docs/orquestracao-dialogo-audio-plano.md §4 item 9. Biblioteca não publicada em nenhum
 * repositório Maven/JitPack (README manda ./gradlew publishToMavenLocal), por isso o código-fonte
 * entra direto no projeto em vez de uma dependência Gradle. Conteúdo idêntico ao original, salvo
 * este comentário — consumido por OpenWakeWordDetector.kt (pacote
 * .../libras/audio), que é quem escolhe os modelos .onnx (mel-spectrogram/embedding oficiais do
 * openWakeWord + os dois classificadores custom treinados pras frases "Libras Livre,
 * iniciar/encerrar").
 */
package com.rementia.openwakeword.lib

import android.content.Context
import android.content.res.AssetManager
import com.rementia.openwakeword.lib.audio.AudioProcessor
import com.rementia.openwakeword.lib.audio.AudioRecorder
import com.rementia.openwakeword.lib.ml.OnnxModelRunner
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.rementia.openwakeword.lib.model.DetectionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Main entry point for wake word detection using ONNX Runtime.
 *
 * This class manages multiple wake word models and emits detection events through a Kotlin Flow.
 * It provides real-time audio processing with configurable detection modes and cooldown periods.
 *
 * @property context Android context for accessing resources and assets
 * @property models List of wake word models to detect. At least one model is required.
 * @property detectionMode Mode for handling multiple simultaneous detections. See [DetectionMode]
 * @property detectionCooldownMs Cooldown period in milliseconds to prevent duplicate detections. Set to 0 to disable cooldown.
 * @property scope CoroutineScope for background operations. Defaults to Dispatchers.Default for optimal performance.
 *
 * @constructor Creates a new wake word detection engine
 * @throws IllegalArgumentException if models list is empty
 */
class WakeWordEngine(
    private val context: Context,
    private val models: List<WakeWordModel>,
    private val detectionMode: DetectionMode = DetectionMode.SINGLE_BEST,
    private val detectionCooldownMs: Long = 2000L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val assetManager: AssetManager = context.assets
    private val audioRecorder = AudioRecorder(context)
    private val modelProcessors = mutableMapOf<WakeWordModel, ModelProcessor>()
    private val detectionCooldowns = mutableMapOf<String, Long>()

    private val _detections = MutableSharedFlow<WakeWordDetection>()

    /**
     * Flow of wake word detection events. Hot/shared — multiple collectors get the same events.
     */
    val detections: Flow<WakeWordDetection> = _detections.asSharedFlow()

    private var recordingJob: Job? = null

    init {
        require(models.isNotEmpty()) { "At least one wake word model must be provided" }
        initializeModels()
    }

    private fun initializeModels() {
        models.forEach { model ->
            val processor = ModelProcessor(assetManager, model)
            modelProcessors[model] = processor
        }
    }

    /**
     * Starts wake word detection. Manages its own [AudioRecorder] (MediaRecorder.AudioSource.MIC,
     * 16kHz mono) internally — não aceita PCM externo.
     *
     * @throws IllegalStateException if RECORD_AUDIO permission is not granted
     */
    fun start() {
        require(audioRecorder.hasRecordPermission()) {
            "RECORD_AUDIO permission is required for wake word detection"
        }

        recordingJob?.cancel()
        recordingJob = scope.launch {
            audioRecorder.startRecording()
                .collect { audioBuffer ->
                    // Process all models in parallel and collect results
                    val detectionResults = models.mapIndexed { index, model ->
                        async {
                            try {
                                val processor = modelProcessors[model]!!
                                val score = processor.process(audioBuffer)
                                if (score > model.threshold) {
                                    DetectionResult(
                                        model = model,
                                        score = score,
                                        difference = score - model.threshold,
                                        index = index
                                    )
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()

                    // Process results based on detection mode
                    when (detectionMode) {
                        DetectionMode.SINGLE_BEST -> {
                            // Select the best detection based on score-threshold difference
                            detectionResults.maxByOrNull { result ->
                                // Primary: difference, Secondary: inverse index (lower index = higher priority)
                                result.difference * 1000 - result.index * 0.001
                            }?.let { result ->
                                emitDetection(result.model, result.score)
                            }
                        }
                        DetectionMode.ALL -> {
                            // Emit all detections that passed threshold
                            detectionResults.forEach { result ->
                                emitDetection(result.model, result.score)
                            }
                        }
                    }
                }
        }
    }

    private suspend fun emitDetection(model: WakeWordModel, score: Float) {
        val now = System.currentTimeMillis()
        val lastDetection = detectionCooldowns[model.name]

        if (lastDetection == null || detectionCooldownMs == 0L || now - lastDetection >= detectionCooldownMs) {
            _detections.emit(
                WakeWordDetection(
                    model = model,
                    score = score
                )
            )
            detectionCooldowns[model.name] = now
        }
    }

    /** Stops audio recording and cancels ongoing detection processing. Restartable via [start]. */
    fun stop() {
        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * Releases all resources (ONNX Runtime sessions, audio processing buffers). Engine cannot be
     * reused after this call.
     */
    fun release() {
        stop()
        modelProcessors.values.forEach { it.close() }
        modelProcessors.clear()
    }

    private data class DetectionResult(
        val model: WakeWordModel,
        val score: Float,
        val difference: Float,
        val index: Int
    )

    private inner class ModelProcessor(
        assetManager: AssetManager,
        private val model: WakeWordModel
    ) : AutoCloseable {

        private val modelRunner = OnnxModelRunner(assetManager, model.modelPath)
        private val audioProcessor = AudioProcessor(assetManager, modelRunner)

        fun process(audioBuffer: FloatArray): Float {
            return audioProcessor.predictWakeWord(audioBuffer)
        }

        override fun close() {
            audioProcessor.close()
            modelRunner.close()
        }
    }
}

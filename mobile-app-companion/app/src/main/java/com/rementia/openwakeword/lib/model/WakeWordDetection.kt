/*
 * Vendorizado de github.com/Re-MENTIA/openwakeword-android-kt (main, 2026-09-11), Apache License
 * 2.0 — ver WakeWordEngine.kt (pacote pai) pro contexto de por que está vendorizado em vez de
 * dependência Gradle. Conteúdo idêntico ao original, salvo este comentário.
 */
package com.rementia.openwakeword.lib.model

/**
 * Represents a wake word detection event, emitted through [WakeWordEngine.detections].
 *
 * @property model The [WakeWordModel] that triggered this detection.
 * @property score The confidence score of the detection, ranging from 0.0 to 1.0.
 * @property timestamp System timestamp (ms since epoch) when the detection occurred.
 */
data class WakeWordDetection(
    val model: WakeWordModel,
    val score: Float,
    val timestamp: Long = System.currentTimeMillis()
)

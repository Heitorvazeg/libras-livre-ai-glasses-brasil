/*
 * Vendorizado de github.com/Re-MENTIA/openwakeword-android-kt (main, 2026-09-11), Apache License
 * 2.0 — ver WakeWordEngine.kt (pacote pai) pro contexto de por que está vendorizado em vez de
 * dependência Gradle. Conteúdo idêntico ao original, salvo este comentário.
 */
package com.rementia.openwakeword.lib.model

/**
 * Configuration for a wake word model.
 *
 * @property name Human-readable name for the wake word. Used in detection events and logging.
 * @property modelPath Path to the ONNX classifier file relative to the assets directory.
 * @property threshold Detection threshold between 0.0 and 1.0. Default is 0.5f.
 */
data class WakeWordModel(
    val name: String,
    val modelPath: String,
    val threshold: Float = 0.5f
)

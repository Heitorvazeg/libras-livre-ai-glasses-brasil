/*
 * Vendorizado de github.com/Re-MENTIA/openwakeword-android-kt (main, 2026-09-11), Apache License
 * 2.0 — ver WakeWordEngine.kt (pacote pai) pro contexto de por que está vendorizado em vez de
 * dependência Gradle. Conteúdo idêntico ao original, salvo este comentário.
 */
package com.rementia.openwakeword.lib.model

/**
 * Detection mode for handling multiple wake word models.
 *
 * This enum determines how the [WakeWordEngine] processes and emits detections
 * when multiple models detect wake words simultaneously in the same audio frame.
 */
enum class DetectionMode {
    /**
     * Only emits the detection with the highest confidence relative to its threshold. If
     * multiple detections have the same difference, the model that was registered first (lower
     * index) takes precedence.
     */
    SINGLE_BEST,

    /**
     * Emits all detections that exceed their respective thresholds. Multiple wake words can be
     * detected and processed simultaneously.
     */
    ALL
}

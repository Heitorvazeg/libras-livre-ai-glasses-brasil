/*
 * Libras Livre — tipo compartilhado entre LandmarkExtractor (produtor) e LandmarkNormalizer
 * (consumidor). Antes vivia em LandmarkApi.kt (removido — a classificação deixou de ser via
 * API, ver libras/SignClassifier.kt), mas é um tipo de dado independente de rede.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

/** Landmarks crus de um frame, nas coordenadas normalizadas do MediaPipe (0..1). */
data class FrameLandmarks(
    val pose: List<FloatArray>, // 33 pontos [x, y, z, visibility]
    val leftHand: List<FloatArray>?, // 21 pontos [x, y, z] ou null se não detectada
    val rightHand: List<FloatArray>?, // idem
)

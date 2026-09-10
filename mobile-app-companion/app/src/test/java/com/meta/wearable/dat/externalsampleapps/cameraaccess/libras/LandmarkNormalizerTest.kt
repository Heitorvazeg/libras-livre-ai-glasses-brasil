/*
 * Verifica a fórmula de LandmarkNormalizer contra valores calculados à mão a partir
 * de computer-vision-model/PoC/src/extract.py:frame_normalizado (ver §2.4 de
 * docs/extracao-landmarks-plano.md) — origem = meio dos ombros, escala = distância
 * entre eles, cada ponto = (pixel - origem) / escala.
 *
 * Não é uma comparação cross-language executando o extract.py de verdade (este
 * ambiente não tem numpy/mediapipe instalados) — é uma verificação de que a fórmula
 * e o mapeamento de índices (pose_indices) estão implementados corretamente. Rodar
 * o extract.py num frame real e comparar byte a byte com este resultado é o próximo
 * passo de validação recomendado antes de confiar nisto em produção.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LandmarkNormalizerTest {

  private fun pose33(overrides: Map<Int, FloatArray>): List<FloatArray> =
      List(33) { i -> overrides[i] ?: floatArrayOf(0f, 0f, 0f, 1f) }

  // ombro_esq(11)=(0.4,0.5) -> pixel(40,100); ombro_dir(12)=(0.6,0.5) -> pixel(60,100),
  // largura=100, altura=200 -> origem=(50,100), escala=sqrt(20^2+0^2)=20.
  private val ombros =
      mapOf(
          11 to floatArrayOf(0.4f, 0.5f, 0f, 1f),
          12 to floatArrayOf(0.6f, 0.5f, 0f, 1f),
          0 to floatArrayOf(0.5f, 0.4f, 0f, 1f), // nariz -> pixel(50,80) -> norm(0,-1)
          24 to floatArrayOf(0.7f, 0.9f, 0f, 1f), // quadril_dir -> pixel(70,180) -> norm(1,4)
      )

  @Test
  fun normalizaPoseComOrigemNoMeioDosOmbrosEEscalaEntreEles() {
    val frame = FrameLandmarks(pose = pose33(ombros), leftHand = null, rightHand = null)
    val out = LandmarkNormalizer.normalize(frame, width = 100, height = 200)
    assertNotNull(out)
    out!!

    // POSE_SUBSET[0] = 0 (nariz) -> índice 0 na saída.
    assertEquals(0f, out[0][0], 1e-4f)
    assertEquals(-1f, out[0][1], 1e-4f)

    // POSE_SUBSET[14] = 24 (quadril_dir) -> índice 14, último ponto do bloco de pose.
    assertEquals(1f, out[14][0], 1e-4f)
    assertEquals(4f, out[14][1], 1e-4f)
  }

  @Test
  fun maoAusenteViraOVetorZeroNosDoisBlocos() {
    val frame = FrameLandmarks(pose = pose33(ombros), leftHand = null, rightHand = null)
    val out = LandmarkNormalizer.normalize(frame, width = 100, height = 200)!!

    for (i in LandmarkNormalizer.OFFSET_MAO_ESQ until LandmarkNormalizer.OFFSET_MAO_ESQ + LandmarkNormalizer.N_MAO) {
      assertEquals(0f, out[i][0], 0f)
      assertEquals(0f, out[i][1], 0f)
    }
    for (i in LandmarkNormalizer.OFFSET_MAO_DIR until LandmarkNormalizer.OFFSET_MAO_DIR + LandmarkNormalizer.N_MAO) {
      assertEquals(0f, out[i][0], 0f)
      assertEquals(0f, out[i][1], 0f)
    }
  }

  @Test
  fun maoPresenteUsaAMesmaOrigemEEscalaDaPose() {
    // pixel(30,120) com origem(50,100)/escala20 -> norm(-1,1)
    val leftHand = List(21) { floatArrayOf(0.3f, 0.6f, 0f) }
    val frame = FrameLandmarks(pose = pose33(ombros), leftHand = leftHand, rightHand = null)
    val out = LandmarkNormalizer.normalize(frame, width = 100, height = 200)!!

    val p0 = out[LandmarkNormalizer.OFFSET_MAO_ESQ]
    assertEquals(-1f, p0[0], 1e-4f)
    assertEquals(1f, p0[1], 1e-4f)
  }

  @Test
  fun ombroPoucoVisivelDescartaOFrame() {
    val comOmbroFraco = ombros + (11 to floatArrayOf(0.4f, 0.5f, 0f, 0.2f)) // visibility < 0.5
    val frame = FrameLandmarks(pose = pose33(comOmbroFraco), leftHand = null, rightHand = null)
    assertNull(LandmarkNormalizer.normalize(frame, width = 100, height = 200))
  }

  @Test
  fun escalaDegeneradaDescartaOFrame() {
    val ombrosColados =
        mapOf(
            11 to floatArrayOf(0.5f, 0.5f, 0f, 1f),
            12 to floatArrayOf(0.5f, 0.5f, 0f, 1f),
        )
    val frame = FrameLandmarks(pose = pose33(ombrosColados), leftHand = null, rightHand = null)
    assertNull(LandmarkNormalizer.normalize(frame, width = 100, height = 200))
  }
}

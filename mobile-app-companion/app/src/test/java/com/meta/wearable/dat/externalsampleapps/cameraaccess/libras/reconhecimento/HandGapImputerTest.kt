/*
 * Porta o fixture de computer-vision-model/treino/selftest.py::teste_imputacao_maos —
 * mesma sequência sintética, mesmas asserções, adaptado pro consumo frame-a-frame
 * (offer()) em vez de um array pronto. Ver HandGapImputer.kt.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGapImputerTest {

  companion object {
    private const val N_FRAMES = 30
  }

  private fun frameSintetico(f: Int, maoEsqAusente: Boolean): Array<FloatArray> {
    val frame = Array(LandmarkNormalizer.N_PONTOS) { FloatArray(LandmarkNormalizer.N_CANAIS) }
    for (i in 0 until LandmarkNormalizer.N_POSE) {
      frame[i][0] = 0.5f
      frame[i][1] = 0.5f
    }
    if (!maoEsqAusente) {
      val valor = 1.0f + f * 0.01f
      for (i in 0 until LandmarkNormalizer.N_MAO) {
        val p = frame[LandmarkNormalizer.OFFSET_MAO_ESQ + i]
        p[0] = valor
        p[1] = valor
      }
    }
    // Mão direita nunca aparece neste fixture — fica [0,0] (default do Array(...){ FloatArray }).
    return frame
  }

  @Test
  fun lacunaCurtaEhPreenchidaLacunaLongaFicaComoAusencia() {
    val imputer = HandGapImputer(lacunaMaxima = 5)
    for (f in 0 until N_FRAMES) {
      val ausente = f in 10..12 || f in 20..29 // lacuna curta (3) e lacuna longa (10, até o fim)
      imputer.offer(frameSintetico(f, maoEsqAusente = ausente))
    }
    val seq = imputer.snapshot()

    for (f in 10..12) {
      val p = seq[f][LandmarkNormalizer.OFFSET_MAO_ESQ]
      assertFalse("frame $f deveria ter sido preenchido", p[0] == 0f && p[1] == 0f)
    }
    for (f in 20..29) {
      val p = seq[f][LandmarkNormalizer.OFFSET_MAO_ESQ]
      assertEquals("lacuna longa não pode ser inventada", 0f, p[0], 0f)
      assertEquals("lacuna longa não pode ser inventada", 0f, p[1], 0f)
    }

    // O preenchimento tem que ficar entre os vizinhos (frames 9 e 13) e ser monotônico.
    val antes = 1.0f + 9 * 0.01f
    val depois = 1.0f + 13 * 0.01f
    val v10 = seq[10][LandmarkNormalizer.OFFSET_MAO_ESQ][0]
    val v12 = seq[12][LandmarkNormalizer.OFFSET_MAO_ESQ][0]
    assertTrue("interpolação abaixo do vizinho anterior", antes < v10)
    assertTrue("interpolação não monotônica", v10 <= v12)
    assertTrue("interpolação acima do vizinho seguinte", v12 < depois)

    // A pose nunca é tocada pela imputação de mão.
    for (f in 0 until N_FRAMES) {
      for (i in 0 until LandmarkNormalizer.N_POSE) {
        assertEquals(0.5f, seq[f][i][0], 1e-6f)
        assertEquals(0.5f, seq[f][i][1], 1e-6f)
      }
    }
  }

  @Test
  fun sessaoSemNenhumaDeteccaoNaoQuebraNemInventaDado() {
    val imputer = HandGapImputer()
    repeat(10) { f -> imputer.offer(frameSintetico(f, maoEsqAusente = true)) }
    for (frame in imputer.snapshot()) {
      val p = frame[LandmarkNormalizer.OFFSET_MAO_ESQ]
      assertEquals(0f, p[0], 0f)
      assertEquals(0f, p[1], 0f)
    }
  }

  @Test
  fun resetLimpaOBufferEOEstadoDePresenca() {
    val imputer = HandGapImputer(lacunaMaxima = 5)
    imputer.offer(frameSintetico(0, maoEsqAusente = false))
    imputer.reset()
    assertTrue(imputer.snapshot().isEmpty())

    // Depois do reset, uma lacuna "curta" relativa à sessão anterior não deveria vazar —
    // não há detecção anterior nesta nova sessão, então nada é interpolado.
    imputer.offer(frameSintetico(0, maoEsqAusente = true))
    val p = imputer.snapshot()[0][LandmarkNormalizer.OFFSET_MAO_ESQ]
    assertEquals(0f, p[0], 0f)
  }
}

/*
 * Recorte do segmento com margem de repouso (docs/prontidao-demo/01-segmentacao.md §1.1).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentadorTest {

  private fun frame(x: Float): Array<FloatArray> {
    val f = Array(LandmarkNormalizer.N_PONTOS) { FloatArray(LandmarkNormalizer.N_CANAIS) }
    for (i in 0 until LandmarkNormalizer.N_POSE) f[i][1] = 1f
    for (i in 0 until LandmarkNormalizer.N_MAO) {
      f[LandmarkNormalizer.OFFSET_MAO_ESQ + i][0] = x
      f[LandmarkNormalizer.OFFSET_MAO_ESQ + i][1] = 2f
    }
    return f
  }

  @Test
  fun `seis segundos parado e um de sinal viram um segmento do movimento com margens`() {
    val parametros = ParametrosSegmentacao()
    val segmentos = mutableListOf<Pair<List<FrameComTempo>, LimitesSegmento>>()
    val segmentador = Segmentador(parametros, onSegmento = { f, l -> segmentos.add(f to l) })

    // 6 s parado, 1 s andando a 2 ombros/s, 1,5 s parado; 24 fps.
    for (n in 0..(24 * 85 / 10)) {
      val ts = n * 1000L / 24
      segmentador.onFrame(frame(2f * (ts.coerceIn(6000, 7000) - 6000) / 1000f), ts)
    }

    val (frames, limites) = segmentos.single()
    val duracao = frames.last().tsMs - frames.first().tsMs
    // Antes, o segmento levava os 6 s de repouso junto (~7,7 s).
    assertTrue("segmento de $duracao ms", duracao in 1000..1800)
    assertTrue("começou em ${frames.first().tsMs}", frames.first().tsMs >= limites.inicioMs - parametros.preRollMs)
    assertTrue(frames.first().tsMs >= 6000 - parametros.preRollMs)
    assertTrue("terminou em ${frames.last().tsMs}", frames.last().tsMs <= limites.fimDoMovimentoMs + parametros.posRollMs)
    assertTrue(frames.zipWithNext().all { (a, b) -> a.tsMs < b.tsMs })
  }

  @Test
  fun `os frames entregues sao copias`() {
    var entregue: List<FrameComTempo>? = null
    val segmentador = Segmentador(onSegmento = { f, _ -> entregue = f })
    val originais = mutableListOf<Array<FloatArray>>()
    for (n in 0..(24 * 3)) {
      val ts = n * 1000L / 24
      val f = frame(2f * (ts.coerceIn(1000, 1800) - 1000) / 1000f)
      originais.add(f)
      segmentador.onFrame(f, ts)
    }
    val primeiro = entregue!!.first()
    primeiro.pontos[LandmarkNormalizer.OFFSET_MAO_ESQ][0] = 99f
    assertTrue(originais.none { it[LandmarkNormalizer.OFFSET_MAO_ESQ][0] == 99f })
  }

  @Test
  fun `espasmo descartado nao entrega segmento`() {
    val segmentos = mutableListOf<List<FrameComTempo>>()
    val descartados = mutableListOf<LimitesSegmento>()
    val segmentador = Segmentador(onSegmento = { f, _ -> segmentos.add(f) }, onDescartado = { descartados.add(it) })
    for (n in 0..(24 * 3)) {
      val ts = n * 1000L / 24
      segmentador.onFrame(frame(if (ts in 1000 until 1084) 0.3f else 0f), ts)
    }
    assertTrue(segmentos.isEmpty())
    assertEquals(1, descartados.size)
  }
}

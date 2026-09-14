/*
 * Detector de fronteiras de sinal (docs/prontidao-demo/01-segmentacao.md §1.2–1.6), com os
 * parâmetros estimados do §1.8. Sequências sintéticas: não é calibração, é conferir que a máquina
 * de estados faz o que o plano decidiu.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignBoundaryDetectorTest {

  private class Resultado(val detector: SignBoundaryDetector) {
    val fronteiras = mutableListOf<Pair<Long, LimitesSegmento>>()
    val descartados = mutableListOf<LimitesSegmento>()
    var entradaMs: Long? = null
  }

  /**
   * Frame com a pose parada e as mãos em [xEsq]/[xDir] (null = ausente). Os 21 pontos de cada mão
   * andam juntos; y fixo diferente de zero para nunca parecer ausência.
   */
  private fun frame(xEsq: Float?, xDir: Float? = null, ruido: ((Int) -> Float)? = null): Array<FloatArray> {
    val f = Array(LandmarkNormalizer.N_PONTOS) { FloatArray(LandmarkNormalizer.N_CANAIS) }
    for (i in 0 until LandmarkNormalizer.N_POSE) {
      f[i][0] = 0.1f * i - 0.7f
      f[i][1] = 1f
    }
    fun mao(offset: Int, x: Float) {
      for (i in 0 until LandmarkNormalizer.N_MAO) {
        f[offset + i][0] = x + 0.01f * i + (ruido?.invoke(i) ?: 0f)
        f[offset + i][1] = 2f + (ruido?.invoke(i + 100) ?: 0f)
      }
    }
    xEsq?.let { mao(LandmarkNormalizer.OFFSET_MAO_ESQ, it) }
    xDir?.let { mao(LandmarkNormalizer.OFFSET_MAO_DIR, it) }
    return f
  }

  /** Roda [duracaoMs] a [fps], com a mão esquerda em [x] (null = ausente) em cada instante. */
  private fun rodar(
      fps: Int,
      duracaoMs: Long,
      parametros: ParametrosSegmentacao = ParametrosSegmentacao(),
      x: (Long) -> Float?,
  ): Resultado {
    lateinit var r: Resultado
    val detector =
        SignBoundaryDetector(
            parametros = parametros,
            onBoundary = { r.fronteiras.add(ultimoTs to it) },
            onDescartado = { r.descartados.add(it) },
        )
    r = Resultado(detector)
    var n = 0
    while (true) {
      val ts = n * 1000L / fps
      if (ts > duracaoMs) break
      ultimoTs = ts
      detector.onFrame(frame(x(ts)), ts)
      if (r.entradaMs == null && detector.estadoAtual == EstadoSinalizacao.SINALIZANDO) r.entradaMs = ts
      n++
    }
    return r
  }

  private var ultimoTs = 0L

  /** Parada até 1 s, anda a 2 ombros/s até 2 s, parada depois. */
  private fun sinalDeUmSegundo(ts: Long): Float = 2f * (ts.coerceIn(1000, 2000) - 1000) / 1000f

  @Test
  fun `o mesmo movimento a 24 e a 12 fps produz as mesmas transicoes`() {
    val a24 = rodar(24, 4000) { sinalDeUmSegundo(it) }
    val a12 = rodar(12, 4000) { sinalDeUmSegundo(it) }
    val umFrameA12 = 1000L / 12 + 1

    assertEquals(1, a24.fronteiras.size)
    assertEquals(1, a12.fronteiras.size)
    assertTrue("entrada ${a24.entradaMs} x ${a12.entradaMs}", abs(a24.entradaMs!! - a12.entradaMs!!) <= umFrameA12)
    val (emitida24, lim24) = a24.fronteiras.single()
    val (emitida12, lim12) = a12.fronteiras.single()
    assertTrue("fim ${lim24.fimDoMovimentoMs} x ${lim12.fimDoMovimentoMs}", abs(lim24.fimDoMovimentoMs - lim12.fimDoMovimentoMs) <= umFrameA12)
    assertTrue("emissão $emitida24 x $emitida12", abs(emitida24 - emitida12) <= umFrameA12)
    assertEquals(MotivoFechamento.PAUSA, lim24.motivo)
  }

  @Test
  fun `maos paradas com tremor nunca entram em sinalizando`() {
    val aleatorio = Random(42)
    var entrou = false
    val detector = SignBoundaryDetector(onBoundary = { entrou = true })
    for (n in 0 until 24 * 20) {
      val ts = n * 1000L / 24
      // σ = 0,014 ombro por coordenada, por frame, independente em cada ponto.
      detector.onFrame(frame(0.3f, -0.3f, ruido = { (aleatorio.nextGaussian() * 0.014).toFloat() }), ts)
      if (detector.estadoAtual == EstadoSinalizacao.SINALIZANDO) entrou = true
    }
    assertTrue("o tremor foi lido como sinal", !entrou)
  }

  @Test
  fun `sinal de uma mao so mede o mesmo com a outra ausente ou parada`() {
    val comOutraAusente = SignBoundaryDetector(onBoundary = {})
    val comOutraParada = SignBoundaryDetector(onBoundary = {})
    for (n in 0 until 24 * 3) {
      val ts = n * 1000L / 24
      val x = sinalDeUmSegundo(ts)
      comOutraAusente.onFrame(frame(x, null), ts)
      comOutraParada.onFrame(frame(x, -0.5f), ts)
      assertEquals("t=$ts", comOutraAusente.ultimaMedicao?.final, comOutraParada.ultimaMedicao?.final)
    }
  }

  @Test
  fun `movimento lento entre os dois limiares depois de comecar nao fecha o sinal`() {
    // 300 ms rápido (2 ombros/s) para entrar, depois 2 s a 0,55 ombro/s: abaixo da entrada (0,7) e
    // acima da saída (0,4).
    val r =
        rodar(24, 2300) { ts ->
          if (ts < 300) 2f * ts / 1000f else 0.6f + 0.55f * (ts - 300) / 1000f
        }
    assertTrue(r.entradaMs != null)
    assertTrue("fechou: ${r.fronteiras}", r.fronteiras.isEmpty())
    assertEquals(EstadoSinalizacao.SINALIZANDO, r.detector.estadoAtual)
  }

  @Test
  fun `movimento lento vindo do repouso nao entra`() {
    val r = rodar(24, 3000) { ts -> 0.55f * ts / 1000f }
    assertNull(r.entradaMs)
  }

  @Test
  fun `espasmos de 1 a 4 frames nao disparam fronteira`() {
    for (frames in 1..4) {
      // Salto de 0,3 ombro que dura `frames` frames e volta.
      val inicio = 1000L
      val fim = inicio + frames * 1000L / 24
      val r = rodar(24, 3000) { ts -> if (ts in inicio until fim) 0.3f else 0f }
      assertTrue("espasmo de $frames frame(s) virou sinal: ${r.fronteiras}", r.fronteiras.isEmpty())
    }
  }

  @Test
  fun `sinal de 300 ms dispara`() {
    val r = rodar(24, 3000) { ts -> 2f * (ts.coerceIn(1000, 1300) - 1000) / 1000f }
    assertEquals(1, r.fronteiras.size)
    assertTrue(r.fronteiras.single().second.duracaoMovimentoMs >= 250)
  }

  @Test
  fun `ausencia das maos de 600 ms no meio do sinal nao fecha`() {
    // Anda de 1 a 3 s, com as mãos sumidas entre 1,6 e 2,2 s.
    val r =
        rodar(24, 3200) { ts ->
          if (ts in 1600 until 2200) null else 2f * (ts.coerceIn(1000, 3000) - 1000) / 1000f
        }
    assertTrue("fechou durante a oclusão: ${r.fronteiras}", r.fronteiras.none { it.first < 3000 })
  }

  @Test
  fun `ausencia das maos de 1000 ms fecha por oclusao`() {
    val r =
        rodar(24, 3500) { ts ->
          if (ts in 1600 until 2600) null else 2f * (ts.coerceIn(1000, 3500) - 1000) / 1000f
        }
    val primeira = r.fronteiras.first()
    assertEquals(MotivoFechamento.OCLUSAO, primeira.second.motivo)
    assertTrue("fechou em ${primeira.first}", primeira.first in 2400..2650)
  }

  @Test
  fun `a pausa fecha o sinal e a duracao maxima fecha o que nao para`() {
    val continuo = rodar(24, 5000) { ts -> 2f * ts.coerceAtLeast(500) / 1000f }
    assertEquals(MotivoFechamento.DURACAO_MAXIMA, continuo.fronteiras.first().second.motivo)
  }

  @Test
  fun `fechamento forcado aplica a duracao minima`() {
    val espasmo = rodar(24, 1100) { ts -> if (ts in 1000 until 1042) 0.3f else 0f }
    assertEquals(EstadoSinalizacao.SINALIZANDO, espasmo.detector.estadoAtual)
    assertTrue(!espasmo.detector.forcarFechamento())

    val sinal = rodar(24, 1600) { ts -> 2f * (ts.coerceIn(1000, 1600) - 1000) / 1000f }
    assertTrue(sinal.detector.forcarFechamento())
    assertEquals(MotivoFechamento.FIM_DA_SESSAO, sinal.fronteiras.single().second.motivo)
  }
}

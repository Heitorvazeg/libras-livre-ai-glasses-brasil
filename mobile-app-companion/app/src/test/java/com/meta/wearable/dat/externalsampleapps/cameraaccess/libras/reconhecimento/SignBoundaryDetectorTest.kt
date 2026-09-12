/*
 * Testes sintéticos do estado SINALIZANDO/PARADO (docs/sign-boundary-detector-plano.md §4) —
 * não é validação empírica (isso é Fase 0/1, bloqueada neste ambiente, ver §0/§7 do plano);
 * é conferir que a máquina de estados está implementada conforme o §4.2/§4.3, com
 * parâmetros controlados (não os defaults não calibrados).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignBoundaryDetectorTest {

  // Frame com todos os pontos em [0,0] (pose "parada" na origem, as duas mãos ausentes).
  private fun frameVazio(): Array<FloatArray> =
      Array(LandmarkNormalizer.N_PONTOS) { FloatArray(LandmarkNormalizer.N_CANAIS) }

  // Mão esquerda presente em x=valor, y=1 (y fixo != 0 pra nunca colidir com o sentinela de
  // ausência [0,0], mesmo quando valor=0) — direita e pose ficam na origem (paradas).
  private fun comMaoEsq(valor: Float): Array<FloatArray> {
    val frame = frameVazio()
    for (i in 0 until LandmarkNormalizer.N_MAO) {
      frame[LandmarkNormalizer.OFFSET_MAO_ESQ + i][0] = valor
      frame[LandmarkNormalizer.OFFSET_MAO_ESQ + i][1] = 1f
    }
    return frame
  }

  @Test
  fun pausaSustentadaAposMovimentoDisparaBoundary() {
    var boundaries = 0
    val detector =
        SignBoundaryDetector(
            limiarVelocidade = 0.5f,
            janelaSustentacaoMs = 200,
            tetoOclusaoMs = 100_000,
            duracaoMinimaMs = 0,
            duracaoMaximaMs = 100_000,
            onBoundary = { boundaries++ },
        )
    var t = 0L
    for (passo in 0..10) {
      detector.onFrame(comMaoEsq(passo.toFloat()), t)
      t += 50
    }
    assertEquals("ainda sinalizando, nenhuma pausa longa o bastante", 0, boundaries)

    val parado = comMaoEsq(10f)
    detector.onFrame(parado, t) // t = 550
    t += 250 // passa da janela de sustentação (200ms)
    detector.onFrame(parado, t)
    assertEquals(1, boundaries)
  }

  @Test
  fun movimentoContinuoNaoDisparaBoundaryPrecoce() {
    var boundaries = 0
    val detector =
        SignBoundaryDetector(
            limiarVelocidade = 0.5f,
            janelaSustentacaoMs = 200,
            tetoOclusaoMs = 100_000,
            duracaoMinimaMs = 0,
            duracaoMaximaMs = 100_000,
            onBoundary = { boundaries++ },
        )
    var t = 0L
    for (passo in 0..40) {
      detector.onFrame(comMaoEsq(passo.toFloat()), t)
      t += 50
    }
    assertEquals(0, boundaries)
  }

  @Test
  fun oclusaoTotalSustentadaDisparaBoundary() {
    var boundaries = 0
    val detector =
        SignBoundaryDetector(
            limiarVelocidade = 0.5f,
            janelaSustentacaoMs = 100_000,
            tetoOclusaoMs = 300,
            duracaoMinimaMs = 0,
            duracaoMaximaMs = 100_000,
            onBoundary = { boundaries++ },
        )
    var t = 0L
    detector.onFrame(comMaoEsq(0f), t)
    t += 50
    detector.onFrame(comMaoEsq(5f), t) // cruza o limiar -> SINALIZANDO
    t += 50
    // As duas mãos somem (frameVazio: os dois blocos batem no sentinela de ausência).
    detector.onFrame(frameVazio(), t)
    t += 350 // > tetoOclusaoMs
    detector.onFrame(frameVazio(), t)
    assertEquals(1, boundaries)
  }

  @Test
  fun oclusaoDeUmaMaoSoNaoDisparaOclusaoTotal() {
    var boundaries = 0
    val detector =
        SignBoundaryDetector(
            limiarVelocidade = 0.5f,
            janelaSustentacaoMs = 100_000,
            tetoOclusaoMs = 100,
            duracaoMinimaMs = 0,
            duracaoMaximaMs = 100_000,
            onBoundary = { boundaries++ },
        )
    var t = 0L
    detector.onFrame(comMaoEsq(0f), t)
    t += 50
    detector.onFrame(comMaoEsq(5f), t) // SINALIZANDO, mão esquerda presente
    t += 50
    // Só a mão esquerda continua se movendo — nunca fica "ambas ausentes" — mesmo além do
    // tetoOclusaoMs (a mão direita já estava ausente o tempo todo neste fixture, o que não
    // conta como oclusão TOTAL sozinho).
    for (passo in 6..20) {
      detector.onFrame(comMaoEsq(passo.toFloat()), t)
      t += 50
    }
    assertEquals(0, boundaries)
  }

  @Test
  fun segmentoMaisCurtoQueDuracaoMinimaNaoDisparaBoundary() {
    var boundaries = 0
    val detector =
        SignBoundaryDetector(
            limiarVelocidade = 0.5f,
            janelaSustentacaoMs = 100,
            tetoOclusaoMs = 100_000,
            duracaoMinimaMs = 5_000,
            duracaoMaximaMs = 100_000,
            onBoundary = { boundaries++ },
        )
    var t = 0L
    detector.onFrame(comMaoEsq(0f), t)
    t += 50
    detector.onFrame(comMaoEsq(5f), t) // SINALIZANDO
    t += 50
    val parado = comMaoEsq(5f)
    detector.onFrame(parado, t)
    t += 200 // passa a janela de sustentação, mas o segmento inteiro é curto demais
    detector.onFrame(parado, t)
    assertEquals(0, boundaries)
  }

  @Test
  fun duracaoMaximaForcaBoundaryMesmoSemPausaDetectada() {
    var boundaries = 0
    val detector =
        SignBoundaryDetector(
            limiarVelocidade = 0.5f,
            janelaSustentacaoMs = 100_000,
            tetoOclusaoMs = 100_000,
            duracaoMinimaMs = 0,
            duracaoMaximaMs = 500,
            onBoundary = { boundaries++ },
        )
    var t = 0L
    // Sinalização contínua, sem pausa nenhuma, por mais de 500ms.
    for (passo in 0..20) {
      detector.onFrame(comMaoEsq(passo.toFloat()), t)
      t += 50
    }
    assertEquals("o teto de segurança deveria ter forçado ao menos um boundary", 1, boundaries)
  }

  @Test
  fun forcarFechamentoSoDisparaSeEstiverSinalizando() {
    var boundaries = 0
    val detector = SignBoundaryDetector(onBoundary = { boundaries++ })

    assertFalse("nunca viu frame nenhum, não tem segmento aberto", detector.forcarFechamento())
    assertEquals(0, boundaries)

    var t = 0L
    detector.onFrame(comMaoEsq(0f), t)
    t += 50
    detector.onFrame(comMaoEsq(5f), t) // cruza o limiar default -> SINALIZANDO

    assertTrue(detector.forcarFechamento())
    assertEquals(1, boundaries)
    assertFalse("já fechou, não tem mais segmento aberto", detector.forcarFechamento())
    assertEquals(1, boundaries)
  }
}

/*
 * Conversão YUV_420_888 -> ARGB antes do MediaPipe (ver o header de YuvParaArgb).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvParaArgbTest {

  private fun rgb(p: Int) = Triple((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)

  private fun perto(esperado: Triple<Int, Int, Int>, obtido: Triple<Int, Int, Int>, tol: Int = 3) =
      abs(esperado.first - obtido.first) <= tol &&
          abs(esperado.second - obtido.second) <= tol &&
          abs(esperado.third - obtido.third) <= tol

  /** Um frame 2x2 de uma cor só, em planos separados (passo de pixel 1). */
  private fun corUnica(yv: Int, uv: Int, vv: Int): Int {
    val saida = IntArray(4)
    YuvParaArgb.converter(
        largura = 2, altura = 2,
        y = ByteArray(4) { yv.toByte() }, passoLinhaY = 2,
        u = byteArrayOf(uv.toByte()), v = byteArrayOf(vv.toByte()),
        passoLinhaUv = 1, passoPixelUv = 1,
        saida = saida,
    )
    assertTrue(saida.all { it == saida[0] })
    return saida[0]
  }

  @Test
  fun `cores de referencia BT601 de faixa limitada`() {
    assertTrue(perto(Triple(0, 0, 0), rgb(corUnica(16, 128, 128))))
    assertTrue(perto(Triple(255, 255, 255), rgb(corUnica(235, 128, 128))))
    assertTrue("vermelho: ${rgb(corUnica(81, 90, 240))}", perto(Triple(255, 0, 0), rgb(corUnica(81, 90, 240))))
    assertTrue("verde: ${rgb(corUnica(145, 54, 34))}", perto(Triple(0, 255, 0), rgb(corUnica(145, 54, 34))))
    assertTrue("azul: ${rgb(corUnica(41, 240, 110))}", perto(Triple(0, 0, 255), rgb(corUnica(41, 240, 110))))
  }

  @Test
  fun `alfa opaco em todo pixel`() {
    assertEquals(0xff, (corUnica(100, 128, 128) ushr 24))
  }

  @Test
  fun `respeita o passo de linha e o passo de pixel dos planos intercalados`() {
    // 4x2, luma com 2 bytes de preenchimento por linha (lixo 99) e croma intercalado com passo 2
    // (como o NV12 que o decodificador costuma entregar pelos planos do Image).
    val largura = 4
    val altura = 2
    val y = byteArrayOf(16, 16, (-21).toByte(), (-21).toByte(), 99, 99, 16, 16, (-21).toByte(), (-21).toByte(), 99, 99)
    // Duas amostras de croma por linha de croma: [U0 V0 U1 V1], com U1 = V1 = lixo nas posições ímpares.
    val u = byteArrayOf(128.toByte(), 7, 128.toByte(), 7)
    val v = byteArrayOf(128.toByte(), 7, 128.toByte(), 7)
    val saida = IntArray(largura * altura)
    YuvParaArgb.converter(largura, altura, y, 6, u, v, 4, 2, saida)
    // Colunas 0-1 pretas, 2-3 brancas (235 = -21 em byte), nas duas linhas; o lixo não entra.
    for (linha in 0 until altura) {
      assertTrue(perto(Triple(0, 0, 0), rgb(saida[linha * largura + 0])))
      assertTrue(perto(Triple(0, 0, 0), rgb(saida[linha * largura + 1])))
      assertTrue(perto(Triple(255, 255, 255), rgb(saida[linha * largura + 2])))
      assertTrue(perto(Triple(255, 255, 255), rgb(saida[linha * largura + 3])))
    }
  }
}

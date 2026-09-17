/*
 * Reamostragem pelo tempo (docs/prontidao-demo/02-classificador.md §2.2). Os valores esperados foram
 * gerados com numpy (`np.interp(np.linspace(t0, tN, alvo), ts, v)`), o mesmo cálculo do
 * `gcn.para_sequencia` do treino, só que com os timestamps no lugar dos índices.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import org.junit.Assert.assertEquals
import org.junit.Test

class ReamostragemTemporalTest {

  private fun segmento(valores: DoubleArray): List<Array<FloatArray>> =
      valores.map { v -> Array(2) { p -> floatArrayOf(v.toFloat(), -v.toFloat() * (p + 1), 7f) } }

  private fun conferir(esperado: List<Double>, saida: Array<Array<FloatArray>>) {
    assertEquals(esperado.size, saida.size)
    for ((k, v) in esperado.withIndex()) {
      assertEquals("x no instante $k", v.toFloat(), saida[k][0][0], 1e-5f)
      assertEquals("y do ponto 1 no instante $k", -2f * v.toFloat(), saida[k][1][1], 1e-5f)
      assertEquals("canal constante", 7f, saida[k][1][2], 0f)
    }
  }

  @Test
  fun `timestamps uniformes batem com o np interp`() {
    val saida =
        ReamostragemTemporal.reamostrar(
            segmento(doubleArrayOf(0.0, 1.0, 4.0, 9.0, 16.0)), longArrayOf(0, 40, 80, 120, 160), alvo = 8)
    conferir(
        listOf(0.0, 0.5714285714285715, 1.4285714285714286, 3.142857142857143, 5.428571428571429,
            8.285714285714286, 12.0, 16.0),
        saida)
  }

  @Test
  fun `com frames descartados segue o tempo e nao o indice`() {
    // Frames em 0, 40, 200 e 240 ms: o celular descartou 80..160. Pelo índice, o meio do segmento
    // cairia entre 40 e 200 ms na metade do caminho; pelo tempo, a interpolação é entre eles.
    val saida =
        ReamostragemTemporal.reamostrar(
            segmento(doubleArrayOf(0.0, 1.0, 2.0, 6.0)), longArrayOf(0, 40, 200, 240), alvo = 7)
    conferir(listOf(0.0, 1.0, 1.25, 1.5, 1.75, 2.0, 6.0), saida)
  }

  @Test
  fun `frame unico e repetido e a entrada nao muda`() {
    val entrada = segmento(doubleArrayOf(3.0))
    val saida = ReamostragemTemporal.reamostrar(entrada, longArrayOf(500), alvo = 4)
    conferir(listOf(3.0, 3.0, 3.0, 3.0), saida)
    saida[0][0][0] = 99f
    assertEquals(3f, entrada[0][0][0], 0f)
  }
}

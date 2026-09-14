/*
 * Modos do placeholder do classificador (docs/prontidao-demo/02-classificador.md §2.6).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderSignClassifierTest {

  private val segmento = SegmentoSinal(listOf(Array(57) { FloatArray(3) }), longArrayOf(0))

  @Test
  fun `modo roteiro devolve as 4 sequencias do roteiro em ordem e recomeca`() {
    val placeholder = PlaceholderSignClassifier(modo = { ModoPlaceholder.ROTEIRO })
    val glosas = List(10) { placeholder.classify(segmento).glosa }
    assertEquals(
        listOf("filho", "vacina", "vontade", "cinco", "filho", "medo", "banheiro", "vontade", "filho", "vacina"),
        glosas)
  }

  @Test
  fun `confianca segue o modo, lido a cada classificacao`() {
    var modo = ModoPlaceholder.ALTA
    val placeholder = PlaceholderSignClassifier(modo = { modo })
    assertEquals(PlaceholderSignClassifier.CONFIANCA_ALTA, placeholder.classify(segmento).confianca)
    modo = ModoPlaceholder.BAIXA
    val baixa = placeholder.classify(segmento)
    assertEquals(PlaceholderSignClassifier.CONFIANCA_BAIXA, baixa.confianca)
    assertTrue(baixa.confianca < 0.6f) // abaixo do limiar inicial do fluxo "repita" (2.8)
  }

  @Test
  fun `modo aleatorio fica entre 0 e 1`() {
    val placeholder = PlaceholderSignClassifier(modo = { ModoPlaceholder.ALEATORIA }, aleatorio = Random(7))
    repeat(50) {
      val c = placeholder.classify(segmento)
      assertTrue(c.confianca in 0f..1f)
      assertTrue(c.margem in 0f..c.confianca)
    }
  }
}

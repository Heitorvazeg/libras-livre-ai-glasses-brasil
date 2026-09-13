/*
 * Lado de cada mão pelo pulso da pose (docs/prontidao-demo/02-classificador.md §2.4).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.AtribuicaoMaos.Lados
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.AtribuicaoMaos.Ponto
import org.junit.Assert.assertEquals
import org.junit.Test

class AtribuicaoMaosTest {

  private val pulsoEsq = Ponto(100f, 300f)
  private val pulsoDir = Ponto(300f, 300f)

  @Test
  fun `maos com rotulo trocado vao para o pulso mais proximo`() {
    // A mão 0 (que o HandLandmarker chamaria de esquerda) está no pulso direito, e vice-versa.
    val lados = AtribuicaoMaos.atribuir(listOf(Ponto(295f, 290f), Ponto(105f, 310f)), pulsoEsq, pulsoDir)
    assertEquals(Lados(esquerda = 1, direita = 0), lados)
  }

  @Test
  fun `duas maos perto do mesmo pulso - fica a mais proxima e a outra vai para o livre`() {
    val lados =
        AtribuicaoMaos.atribuir(listOf(Ponto(130f, 300f), Ponto(110f, 300f)), pulsoEsq, Ponto(900f, 300f))
    assertEquals(Lados(esquerda = 1, direita = 0), lados)
  }

  @Test
  fun `uma mao so`() {
    assertEquals(Lados(esquerda = null, direita = 0), AtribuicaoMaos.atribuir(listOf(Ponto(280f, 320f)), pulsoEsq, pulsoDir))
    assertEquals(Lados(esquerda = 0, direita = null), AtribuicaoMaos.atribuir(listOf(Ponto(90f, 280f)), pulsoEsq, pulsoDir))
  }

  @Test
  fun `nenhuma mao`() {
    assertEquals(Lados(null, null), AtribuicaoMaos.atribuir(emptyList(), pulsoEsq, pulsoDir))
  }
}

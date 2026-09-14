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
  fun `mao do atendente longe dos pulsos e descartada`() {
    // Ombros a 200 px: raio de 100 px. A mão do atendente está na base do quadro, a ~400 px.
    val punhos = listOf(Ponto(110f, 305f), Ponto(200f, 700f), Ponto(290f, 295f))
    assertEquals(listOf(0, 2), AtribuicaoMaos.filtrarPorPulso(punhos, pulsoEsq, pulsoDir, larguraOmbros = 200f))
  }

  @Test
  fun `duas pessoas - fica a de ombros mais afastados`() {
    val aoFundo = Ponto(500f, 100f) to Ponto(560f, 100f) // 60 px
    val pertoDaCamera = Ponto(100f, 200f) to Ponto(300f, 200f) // 200 px
    assertEquals(1, AtribuicaoMaos.escolherPose(listOf(aoFundo, pertoDaCamera)))
    assertEquals(null, AtribuicaoMaos.escolherPose(emptyList()))
  }

  @Test
  fun `mao da pessoa surda com rotulo trocado passa pelo filtro e vai para o pulso certo`() {
    // Punho 0 junto do pulso direito, punho 1 junto do esquerdo, mais a mão do atendente ao longe.
    val punhos = listOf(Ponto(300f, 310f), Ponto(95f, 290f), Ponto(900f, 900f))
    val mantidas = AtribuicaoMaos.filtrarPorPulso(punhos, pulsoEsq, pulsoDir, larguraOmbros = 200f)
    assertEquals(listOf(0, 1), mantidas)
    assertEquals(Lados(esquerda = 1, direita = 0), AtribuicaoMaos.atribuir(mantidas.map { punhos[it] }, pulsoEsq, pulsoDir).let {
      Lados(it.esquerda?.let(mantidas::get), it.direita?.let(mantidas::get))
    })
  }

  @Test
  fun `nenhuma mao`() {
    assertEquals(Lados(null, null), AtribuicaoMaos.atribuir(emptyList(), pulsoEsq, pulsoDir))
  }
}

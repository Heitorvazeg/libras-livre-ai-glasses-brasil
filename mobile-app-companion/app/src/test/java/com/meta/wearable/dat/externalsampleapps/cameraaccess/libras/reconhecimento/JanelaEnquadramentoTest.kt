/*
 * "Tronco fora do quadro" (docs/prontidao-demo/03-captura-e-landmarks.md §3.5).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import org.junit.Assert.assertEquals
import org.junit.Test

class JanelaEnquadramentoTest {

  /** Frames a 24 fps de [inicioMs] a [fimMs], com o resultado de cada instante. */
  private fun JanelaEnquadramento.rodar(inicioMs: Long, fimMs: Long, resultado: (Long) -> ResultadoFrame): Enquadramento {
    var ultimo = Enquadramento.OK
    var ts = inicioMs
    while (ts <= fimMs) {
      ultimo = registrar(ts, resultado(ts))
      ts += 42
    }
    return ultimo
  }

  @Test
  fun `ombros cortados por mais de 1 s avisam tronco fora do quadro`() {
    val janela = JanelaEnquadramento()
    // Meio segundo de descarte ainda não avisa.
    assertEquals(Enquadramento.OK, janela.rodar(0, 500) { ResultadoFrame.SEM_OMBROS })
    assertEquals(Enquadramento.TRONCO_FORA, janela.rodar(542, 1_600) { ResultadoFrame.SEM_OMBROS })
  }

  @Test
  fun `sem pose avisa ninguem no quadro`() {
    val janela = JanelaEnquadramento()
    assertEquals(Enquadramento.NINGUEM, janela.rodar(0, 1_600) { ResultadoFrame.SEM_POSE })
  }

  @Test
  fun `descarte de ate metade dos frames nao avisa`() {
    val janela = JanelaEnquadramento()
    var i = 0
    val r = janela.rodar(0, 3_000) { if (i++ % 2 == 0) ResultadoFrame.SEM_OMBROS else ResultadoFrame.NORMALIZADO }
    assertEquals(Enquadramento.OK, r)
  }

  @Test
  fun `voltar ao quadro limpa o aviso e as contagens seguem a janela`() {
    val janela = JanelaEnquadramento()
    assertEquals(Enquadramento.TRONCO_FORA, janela.rodar(0, 1_600) { ResultadoFrame.SEM_OMBROS })
    assertEquals(Enquadramento.OK, janela.rodar(1_642, 3_000) { ResultadoFrame.NORMALIZADO })
    assertEquals(0, janela.descartados)
    janela.reiniciar()
    assertEquals(0, janela.processados)
  }
}

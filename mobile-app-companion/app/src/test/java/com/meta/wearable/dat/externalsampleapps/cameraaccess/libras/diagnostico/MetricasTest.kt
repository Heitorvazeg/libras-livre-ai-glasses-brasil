/*
 * Coletor de métricas (docs/prontidao-demo/03 §3.8 e 06 §6.5).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricasTest {

  @Test
  fun `fps e percentual sem pose saem dos contadores da janela`() {
    val m = Metricas()
    m.amostrar(1_000, LeituraSistema(), filaCheia = 0) // abre a janela
    repeat(48) { m.frameRecebido() }
    repeat(40) { m.frameDecodificado() }
    repeat(20) { m.frameProcessado(comPose = it % 4 != 0) }
    val a = m.amostrar(3_000, LeituraSistema(bateriaPct = 80), filaCheia = 2)
    assertEquals(24f, a.fpsRecebido, 1e-3f)
    assertEquals(20f, a.fpsDecodificado, 1e-3f)
    assertEquals(10f, a.fpsProcessado, 1e-3f)
    assertEquals(25f, a.pctSemPose, 1e-3f)
    assertEquals(2, a.filaCheia)
    assertEquals(80, a.sistema.bateriaPct)

    // A janela seguinte só conta o que veio depois.
    repeat(12) { m.frameRecebido() }
    assertEquals(12f, m.amostrar(4_000, LeituraSistema(), 2).fpsRecebido, 1e-3f)
  }

  @Test
  fun `primeira amostra nao inventa fps`() {
    val m = Metricas()
    repeat(10) { m.frameRecebido() }
    assertEquals(0f, m.amostrar(500, LeituraSistema(), 0).fpsRecebido, 0f)
  }

  @Test
  fun `etapas pertencem ao turno e um novo iniciar limpa a tabela`() {
    val marcas = mutableListOf<MarcaEtapa>()
    val m = Metricas(onMarca = { marcas += it })
    assertEquals(1, m.novoTurno(agoraMs = 10_000))
    m.marcarDesdeOInicio(Etapa.INICIAR_PODE_SINALIZAR, agoraMs = 12_300)
    m.marcar(Etapa.CONTEXTUALIZACAO, 42, "origem=TEMPLATE")
    m.marcar(Etapa.CLASSIFICACAO, 8, "glosa=filho")

    assertEquals(
        listOf(Etapa.INICIAR_PODE_SINALIZAR, Etapa.CLASSIFICACAO, Etapa.CONTEXTUALIZACAO),
        m.etapasDoTurnoAtual().map { it.etapa })
    assertEquals(2_300L, m.etapasDoTurnoAtual().first().ms)
    assertEquals(3, marcas.size)

    assertEquals(2, m.novoTurno(agoraMs = 20_000))
    assertTrue(m.etapasDoTurnoAtual().isEmpty())
  }

  @Test
  fun `linha de log tem o formato do script de agregacao`() {
    assertEquals(
        "turno=3 etapa=contextualizacao ms=42 origem=TEMPLATE",
        Metricas.linhaLog(MarcaEtapa(3, Etapa.CONTEXTUALIZACAO, 42, "origem=TEMPLATE")))
    assertEquals("turno=1 etapa=classificacao ms=8", Metricas.linhaLog(MarcaEtapa(1, Etapa.CLASSIFICACAO, 8, null)))
  }
}

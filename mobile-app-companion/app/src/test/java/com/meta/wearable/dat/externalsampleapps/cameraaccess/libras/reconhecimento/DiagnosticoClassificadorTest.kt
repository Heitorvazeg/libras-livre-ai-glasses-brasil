package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import org.junit.Assert.*
import org.junit.Test

class DiagnosticoClassificadorTest {
  @Test fun `simulado nao alega reconhecer`() {
    val d = DiagnosticoClassificador(ModoClassificador.SIMULADO)
    assertTrue(d.titulo.contains("não reconhece sinais reais"))
    assertFalse(d.detalhes(0.6f).contains("modelo_sha256"))
  }

  @Test fun `experimental mostra identidade completa e configuracao atual`() {
    val id = IdentidadeClassificador("baseline-v1", "ab".repeat(32), "cd".repeat(32), "ef".repeat(32), "ausente_nao_calibrado")
    val d = DiagnosticoClassificador(ModoClassificador.REAL_EXPERIMENTAL, id)
    assertTrue(d.titulo.contains("não aprovado"))
    assertTrue(d.resumo(0.7f).contains("Sem calibração · T=1 · limiar manual: 0.7"))
    assertTrue(d.resumo(0.7f).contains(id.modeloSha256.take(12)))
    for (hash in listOf(id.modeloSha256, id.sidecarSha256, id.checkpointSha256)) assertTrue(d.detalhes(0.7f).contains(hash))
    assertTrue(d.detalhes(0.8f).contains("limiar_manual=0.8"))
  }

  @Test fun `recusado preserva motivo mesmo sem erro transitorio`() {
    val d = DiagnosticoClassificador(ModoClassificador.RECUSADO, motivo = "Modelo adulterado")
    assertTrue(d.titulo.contains("bloqueado"))
    assertEquals("Modelo adulterado", d.resumo(0.6f))
    assertTrue(d.detalhes(0.6f).contains("Modelo adulterado"))
  }
}
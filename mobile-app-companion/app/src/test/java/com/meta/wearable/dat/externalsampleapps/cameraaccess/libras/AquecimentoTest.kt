/*
 * Aquecimento com diagnóstico (docs/prontidao-demo/06-latencia.md §6.4).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AquecimentoTest {

  @Test
  fun `etapas rodam em sequencia, com tempo, e uma falha vira X com motivo sem parar as outras`() = runBlocking {
    var agora = 0L
    val ordem = mutableListOf<String>()
    val etapas =
        listOf(
            EtapaAquecimento("MediaPipe", bloqueiaIniciar = true) { ordem += "MediaPipe"; agora += 120 },
            EtapaAquecimento("Classificador", bloqueiaIniciar = true) {
              ordem += "Classificador"
              agora += 30
              error("sinal_classifier.json ausente")
            },
            EtapaAquecimento("Voz", bloqueiaIniciar = true) { ordem += "Voz"; agora += 800 },
        )
    val resultados = Aquecimento(etapas, relogioMs = { agora }, onMudanca = { _, _ -> }).executar()

    assertEquals(listOf("MediaPipe", "Classificador", "Voz"), ordem)
    assertEquals(listOf(StatusEtapa.OK, StatusEtapa.FALHOU, StatusEtapa.OK), resultados.map { it.status })
    assertEquals(listOf(120L, 30L, 800L), resultados.map { it.ms })
    assertEquals("sinal_classifier.json ausente", resultados[1].motivo)
  }

  @Test
  fun `iniciar libera quando as etapas que bloqueiam terminam, com o avatar ainda carregando`() = runBlocking {
    val publicacoes = mutableListOf<Pair<List<ResultadoEtapa>, Boolean>>()
    val etapas =
        listOf(
            EtapaAquecimento("MediaPipe", bloqueiaIniciar = true) {},
            EtapaAquecimento("Voz", bloqueiaIniciar = true) { error("Piper não carregou") },
            EtapaAquecimento("Avatar", bloqueiaIniciar = false) {},
        )
    Aquecimento(etapas, relogioMs = { 0L }, onMudanca = { r, pronto -> publicacoes += r to pronto }).executar()

    // No começo nada está pronto.
    assertFalse(publicacoes.first().second)
    // Quando o avatar começa a executar, as duas etapas que bloqueiam já terminaram (uma com ✗).
    val avatarExecutando = publicacoes.first { it.first[2].status == StatusEtapa.EXECUTANDO }
    assertTrue(avatarExecutando.second)
    assertTrue(publicacoes.last().second)
  }
}

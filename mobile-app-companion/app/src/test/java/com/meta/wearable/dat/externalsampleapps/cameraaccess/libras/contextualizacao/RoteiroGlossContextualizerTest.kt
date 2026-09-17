/*
 * Libras Livre — a rede de segurança do roteiro do hackathon (Contextualizadores.kt).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class DelegateFalso(private val texto: String, private val origem: Contextualizacao.Origem) :
    GlossContextualizer {
  var chamadas = 0
    private set

  override suspend fun contextualize(glosas: List<String>): Contextualizacao {
    chamadas++
    return Contextualizacao(texto, origem)
  }

  override fun close() {}
}

class RoteiroGlossContextualizerTest {

  @Test
  fun `casos do roteiro travam o texto, mesmo se o delegate diria outra coisa`() = runBlocking {
    val delegate = DelegateFalso("o banheiro é a vontade", Contextualizacao.Origem.MODELO)
    val roteiro = RoteiroGlossContextualizer(delegate)

    val resultado = roteiro.contextualize(listOf("banheiro", "vontade"))

    assertEquals("Quero ir ao banheiro.", resultado.texto)
    assertEquals(Contextualizacao.Origem.TEMPLATE, resultado.origem)
    assertEquals("não deveria nem chamar o delegate para um caso travado", 0, delegate.chamadas)
  }

  @Test
  fun `ordem de captura diferente ainda bate o mesmo caso`() = runBlocking {
    val delegate = DelegateFalso("nao deveria aparecer", Contextualizacao.Origem.MODELO)
    val roteiro = RoteiroGlossContextualizer(delegate)

    val resultado = roteiro.contextualize(listOf("vontade", "vacina", "filho"))

    assertEquals("O meu filho quer a vacina.", resultado.texto)
  }

  @Test
  fun `todos os quatro casos do roteiro estao cobertos`() = runBlocking {
    val delegate = DelegateFalso("x", Contextualizacao.Origem.MODELO)
    val roteiro = RoteiroGlossContextualizer(delegate)

    assertEquals("O meu filho quer a vacina.",
        roteiro.contextualize(listOf("filho", "vacina", "vontade")).texto)
    assertEquals("Cinco.", roteiro.contextualize(listOf("cinco")).texto)
    assertEquals("O meu filho está com medo.",
        roteiro.contextualize(listOf("filho", "medo")).texto)
    assertEquals("Quero ir ao banheiro.",
        roteiro.contextualize(listOf("banheiro", "vontade")).texto)
  }

  @Test
  fun `combinacao fora do roteiro passa direto pro delegate`() = runBlocking {
    val delegate = DelegateFalso("o banco fica na esquina", Contextualizacao.Origem.MODELO)
    val roteiro = RoteiroGlossContextualizer(delegate)

    val resultado = roteiro.contextualize(listOf("banco", "esquina"))

    assertEquals("o banco fica na esquina", resultado.texto)
    assertEquals(Contextualizacao.Origem.MODELO, resultado.origem)
    assertEquals(1, delegate.chamadas)
  }
}

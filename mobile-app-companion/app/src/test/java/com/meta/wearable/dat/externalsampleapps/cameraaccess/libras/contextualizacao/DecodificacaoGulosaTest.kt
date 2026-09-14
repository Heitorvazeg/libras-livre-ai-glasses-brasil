/*
 * O laço de decodificação do modelo de contextualização (docs/prontidao-demo/06-latencia.md
 * §6.1 e §6.2): para no fim de frase e deixa o teto da guarda interromper entre os passos.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodificacaoGulosaTest {

  private val inicio = 0
  private val fim = 1

  @Test
  fun `para no fim de frase sem rodar os passos que sobram`() {
    // Regressão do defeito C: o laço antigo continuava até os 23 passos depois do EOS.
    var chamadas = 0
    val tokens =
        DecodificacaoGulosa.decodificar(inicio = inicio, maxPassos = 23, fim = fim) {
          chamadas++
          if (chamadas == 5) fim else 100 + chamadas
        }
    assertEquals(5, chamadas)
    assertEquals(listOf(101, 102, 103, 104), tokens)
  }

  @Test
  fun `sem fim de frase para no teto de passos`() {
    var chamadas = 0
    val tokens =
        DecodificacaoGulosa.decodificar(inicio = inicio, maxPassos = 23, fim = fim) {
          chamadas++
          7
        }
    assertEquals(23, chamadas)
    assertEquals(23, tokens.size)
  }

  @Test
  fun `cada passo recebe a sequencia ate ali, a partir do token inicial`() {
    val vistas = mutableListOf<List<Int>>()
    DecodificacaoGulosa.decodificar(inicio = inicio, maxPassos = 23, fim = fim) { seq ->
      vistas.add(seq.toList())
      if (vistas.size == 3) fim else 40 + vistas.size
    }
    assertEquals(listOf(listOf(0), listOf(0, 41), listOf(0, 41, 42)), vistas)
  }

  @Test
  fun `teto da guarda interrompe a geracao entre os passos e cai no template`() = runBlocking {
    var passos = 0
    // Mesma forma do TfliteGlossContextualizer: geração bloqueante dentro de withContext, com a
    // verificação de cancelamento entre os passos.
    val lento =
        object : GlossContextualizer {
          override suspend fun contextualize(glosas: List<String>) =
              withContext(Dispatchers.Default) {
                val contexto = coroutineContext
                DecodificacaoGulosa.decodificar(
                    inicio = inicio,
                    maxPassos = 23,
                    fim = fim,
                    verificarCancelamento = { contexto.ensureActive() },
                ) {
                  passos++
                  Thread.sleep(100)
                  7
                }
                Contextualizacao("nunca chega", Contextualizacao.Origem.MODELO)
              }

          override fun close() {}
        }
    val template =
        object : GlossContextualizer {
          override suspend fun contextualize(glosas: List<String>) =
              Contextualizacao("O meu filho.", Contextualizacao.Origem.TEMPLATE)

          override fun close() {}
        }
    val lexico =
        LexicoGlosas.parse("""{"glosas":{"filho":{"classe":"substantivo","formas":["filho"]}}}""")
    val cadeia =
        GuardedGlossContextualizer(primario = lento, fallback = template, lexico = lexico, timeoutMs = 250)

    val inicioNs = System.nanoTime()
    val resultado = cadeia.contextualize(listOf("filho"))
    val ms = (System.nanoTime() - inicioNs) / 1_000_000

    assertEquals(Contextualizacao.Origem.TEMPLATE, resultado.origem)
    // Sem a verificação entre os passos, a cadeia esperaria os 23 passos (~2,3 s).
    assertTrue("a cadeia levou ${ms} ms", ms < 400)
    assertTrue("rodou $passos passos", passos <= 4)
  }
}

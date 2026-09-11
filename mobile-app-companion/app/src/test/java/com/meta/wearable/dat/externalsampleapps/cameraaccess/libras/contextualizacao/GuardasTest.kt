/*
 * Testes das guardas de §8.1. Os casos NÃO são inventados: cada um é uma saída real medida no
 * pipeline Python durante o desenvolvimento do modelo — foi assim que as duas descobertas
 * (degeneração e invenção) apareceram.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LEXICO_JSON = """
{"glosas":{
  "não":{"classe":"marcador","negacao":true,"formas":["não","nao","nenhum","sem","nada"]},
  "eu":{"classe":"pronome","formas":["eu","meu","minha","estou","quero","preciso","tenho","volto"]},
  "filho":{"classe":"substantivo","formas":["filho","filha","criança","menino"]},
  "banheiro":{"classe":"substantivo","formas":["banheiro","sanitário"]},
  "onde":{"classe":"interrogativo","formas":["onde","aonde","fica"]},
  "você":{"classe":"pronome","formas":["você","voce","senhor","senhora"]},
  "ruim":{"classe":"adjetivo","formas":["ruim","mal","péssimo"]},
  "america":{"classe":"substantivo","formas":["américa","america"]},
  "voltar":{"classe":"verbo","formas":["voltar","volto","volta","retorno"]}
}}
"""

class GuardasTest {

  private val lexico = LexicoGlosas.parse(LEXICO_JSON)

  @Test
  fun `normalizacao remove acento e pontuacao`() {
    assertEquals("onde fica o banheiro", lexico.normalizar("Onde fica o banheiro?"))
    assertEquals("nao esta bem", lexico.normalizar("Não está bem!"))
  }

  @Test
  fun `cobertura aceita qualquer forma do lexico`() {
    assertTrue(lexico.cobre("o meu filho está mal", "filho"))
    assertTrue(lexico.cobre("a minha filha está mal", "filho")) // outra forma
    assertTrue(lexico.cobre("eu estou com dor", "eu")) // "estou" carrega o sujeito
    assertTrue(!lexico.cobre("o banco fica ali", "filho"))
  }

  @Test
  fun `degeneracao pega o lixo real que o modelo v1 produzia`() {
    // Saídas medidas de verdade, com decodificação restrita (§6.3).
    assertNotNull(Guardas.degenerado("ondeJ dele dele dele dele dele você", 2))
    assertNotNull(Guardas.degenerado("a a a a a a a a a a", 2))
    assertNotNull(Guardas.degenerado("o Só 35 disso dele dele eu medo, 70 gu tu, com barulho", 3))
    assertNotNull(Guardas.degenerado("", 1))
  }

  @Test
  fun `degeneracao NAO acusa saida boa — falso positivo vira fallback desnecessario`() {
    // Regressão: a primeira versão marcava qualquer maiúscula e acusava 100% das saídas do
    // template, que capitaliza a inicial.
    assertNull(Guardas.degenerado("Eu preciso do documento.", 3))
    assertNull(Guardas.degenerado("Onde fica o banheiro?", 2))
    assertNull(Guardas.degenerado("eu quero a vacina", 3))
    assertNull(Guardas.degenerado("Bom dia", 2))
  }

  @Test
  fun `invencao pega conteudo que ninguem sinalizou`() {
    // Casos reais do modelo v2/v3.
    assertTrue(Guardas.inventadas("eu volto amanhã na américa",
        listOf("eu", "voltar", "america"), lexico).contains("amanha"))
    // Verbo leve NÃO é invenção: "fica" não afirma nada que "onde" já não afirme.
    assertTrue(Guardas.inventadas("o banheiro fica ali", listOf("banheiro", "onde"), lexico).isEmpty())
  }

  @Test
  fun `guarda barra negacao perdida e cai no fallback`() = runBlocking {
    // O caso que reprovou o modelo v1 no portão inegociável: [filho, não, ruim] virou
    // "o meu filho está mal" — inversão de sentido em contexto de saúde (§8.1).
    val mentiroso = object : GlossContextualizer {
      override suspend fun contextualize(glosas: List<String>) =
          Contextualizacao("o meu filho está mal", Contextualizacao.Origem.MODELO)
      override fun close() {}
    }
    var motivo: String? = null
    val guardado = GuardedGlossContextualizer(
        primario = mentiroso,
        fallback = TemplateGlossContextualizer(),
        lexico = lexico,
        onRejeicao = { motivo = it })
    val r = guardado.contextualize(listOf("filho", "não", "ruim"))
    assertEquals(Contextualizacao.Origem.TEMPLATE, r.origem)
    // Quem barra é a COBERTURA, não a regra dedicada: `não` é ela mesma uma glosa, então
    // perder a negação já é perder cobertura. A regra específica em rejeitar() fica como
    // redundância defensiva — ela só dispararia se `não` saísse do léxico, e é barata demais
    // para remover dado o que está em jogo (§8.1).
    assertTrue("esperava rejeição por cobertura da glosa 'não', veio: $motivo",
        motivo!!.startsWith("cobertura") && motivo!!.contains("não"))
  }

  @Test
  fun `guarda barra omissao`() = runBlocking {
    val omisso = object : GlossContextualizer {
      override suspend fun contextualize(glosas: List<String>) =
          Contextualizacao("eu volto", Contextualizacao.Origem.MODELO)
      override fun close() {}
    }
    var motivo: String? = null
    val guardado = GuardedGlossContextualizer(
        omisso, TemplateGlossContextualizer(), lexico, onRejeicao = { motivo = it })
    val r = guardado.contextualize(listOf("eu", "voltar", "america"))
    assertEquals(Contextualizacao.Origem.TEMPLATE, r.origem)
    assertTrue(motivo!!.startsWith("cobertura"))
  }

  @Test
  fun `guarda aceita saida boa e preserva a origem MODELO`() = runBlocking {
    val bom = object : GlossContextualizer {
      override suspend fun contextualize(glosas: List<String>) =
          Contextualizacao("eu volto para a américa", Contextualizacao.Origem.MODELO)
      override fun close() {}
    }
    val guardado = GuardedGlossContextualizer(bom, TemplateGlossContextualizer(), lexico)
    val r = guardado.contextualize(listOf("eu", "voltar", "america"))
    assertEquals(Contextualizacao.Origem.MODELO, r.origem)
    assertEquals("eu volto para a américa", r.texto)
  }

  @Test
  fun `excecao no primario nao derruba a sessao`() = runBlocking {
    val quebrado = object : GlossContextualizer {
      override suspend fun contextualize(glosas: List<String>): Contextualizacao =
          throw IllegalStateException("interpreter morreu")
      override fun close() {}
    }
    val guardado = GuardedGlossContextualizer(quebrado, TemplateGlossContextualizer(), lexico)
    val r = guardado.contextualize(listOf("onde", "banheiro"))
    assertEquals(Contextualizacao.Origem.TEMPLATE, r.origem)
    assertEquals("Onde fica o banheiro?", r.texto)
  }
}

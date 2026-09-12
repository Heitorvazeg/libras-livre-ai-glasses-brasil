/*
 * Teste de PARIDADE com contextualization-model/modelo/template.py, que é a fonte de verdade.
 *
 * Os valores esperados foram GERADOS pela implementação Python, não escritos à mão. É o mesmo
 * cuidado que LandmarkNormalizerTest tem com extract.py: uma divergência aqui não quebra com
 * erro — o usuário só passa a ouvir uma frase diferente da que foi validada.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateGlossContextualizerTest {

  private val casos =
      listOf(
        listOf("eu", "não", "querer", "vacina") to "Eu não quero a vacina.",
        listOf("onde", "banheiro") to "Onde fica o banheiro?",
        listOf("eu", "dor", "ruim") to "Eu estou com dor ruim.",
        listOf("quanto", "esperar") to "Quanto tempo estou esperando?",
        listOf("oi", "manhã") to "Bom dia",
        listOf("filho", "precisar", "vacina") to "O meu filho precisa da vacina.",
        listOf("eu", "precisar", "ajuda") to "Eu preciso de ajuda.",
        listOf("obrigado", "ajuda") to "Obrigado pela ajuda",
        listOf("banheiro", "onde", "por-favor") to "Onde fica o banheiro, por favor.",
        listOf("filho", "dor") to "O meu filho está com dor.",
        listOf("eu", "não", "conhecer", "banco") to "Eu não conheço o banco.",
        listOf("você", "querer", "vacina") to "Você quer a vacina.",
        listOf("banco", "esquina") to "O banco a esquina.",
        listOf("oi") to "Olá",
        listOf("não") to "Não.",
        listOf("eu", "voltar", "manhã") to "Eu volto de manhã.",
        listOf("número", "cinco") to "A senha cinco.",
        listOf("filho", "medo", "barulho") to "O meu filho está com medo barulho.",
        listOf("eu", "aluno") to "Eu aluno.",
        listOf("sim", "obrigado") to "Obrigado, sim",
      )

  @Test
  fun `bate com a implementacao Python em todos os casos`() {
    for ((glosas, esperado) in casos) {
      assertEquals("glosas=$glosas", esperado, TemplateGlossContextualizer.montar(glosas))
    }
  }

  @Test
  fun `negacao nunca some da saida`() {
    // A regra dura do §8.1: o template existe também para ser o fallback seguro, então ele
    // JAMAIS pode perder a negação — é a única métrica do §10 com alvo 1,000 inegociável.
    for ((glosas, _) in casos.filter { "não" in it.first }) {
      assert(TemplateGlossContextualizer.montar(glosas).contains("não", ignoreCase = true)) {
        "perdeu a negação em $glosas"
      }
    }
  }

  @Test
  fun `glosa desconhecida nao derruba, cai no glossario cru`() {
    val saida = TemplateGlossContextualizer.montar(listOf("xxxx", "yyyy"))
    assertEquals("xxxx yyyy", saida)
  }

  @Test
  fun `origem e TEMPLATE`() = runBlocking {
    val r = TemplateGlossContextualizer().contextualize(listOf("oi"))
    assertEquals(Contextualizacao.Origem.TEMPLATE, r.origem)
  }
}

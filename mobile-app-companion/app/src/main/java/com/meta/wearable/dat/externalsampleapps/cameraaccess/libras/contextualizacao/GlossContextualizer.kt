/*
 * Libras Livre — contextualização glosa -> português (Fase 0 de
 * docs/contextualizacao-glosa-seq2seq-plano.md §3).
 *
 * Substitui o placeholder que vivia em DialogOrchestrator (`palavrasReconhecidas.joinToString(" ")`)
 * e aposenta o item `combinacoesConhecidas` do checklist em mobile-app-companion/README.md.
 *
 * Interface trocável, mesmo padrão de SignClassifier/WakeWordDetector/SttEngine: o fio
 * boundary -> classificar -> acumular -> CONTEXTUALIZAR -> falar fecha sem depender do
 * `.tflite` existir.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

/**
 * Resultado da contextualização.
 *
 * [origem] não é enfeite: é o que permite medir em campo quantas sessões usaram o modelo e
 * quantas caíram no fallback — a "taxa de fallback" do §10, que aqui é a métrica mais
 * informativa que existe sobre se o `.tflite` está se pagando.
 */
data class Contextualizacao(
    val texto: String,
    val origem: Origem,
) {
  enum class Origem {
    /** Saída do `.tflite`, aprovada por todas as guardas. */
    MODELO,
    /** Tabela de regras — fallback quando o modelo é rejeitado, ou quando não há modelo. */
    TEMPLATE,
    /** Glossário cru, juntado com espaço. Último recurso. */
    PASSTHROUGH,
  }
}

interface GlossContextualizer {
  /**
   * Transforma o glossário da sessão numa frase falável em PT-BR.
   *
   * **Não lança.** O usuário já sinalizou a sessão inteira; uma falha aqui não pode comer o
   * resultado. Implementações tratam erro internamente e degradam (ver
   * [GuardedGlossContextualizer]). `suspend` porque a implementação real faz inferência.
   */
  suspend fun contextualize(glosas: List<String>): Contextualizacao

  fun close()
}

/**
 * Fase 0 — exatamente o que `DialogOrchestrator` fazia antes desta branch. Existe para que a
 * introdução da interface seja um refactor de comportamento idêntico, e como último degrau do
 * fallback: se tudo mais falhar, o glossário cru ainda é falado. Robótico, mas nunca mente.
 */
class PassthroughGlossContextualizer : GlossContextualizer {
  override suspend fun contextualize(glosas: List<String>) =
      Contextualizacao(glosas.joinToString(" "), Contextualizacao.Origem.PASSTHROUGH)

  override fun close() {}
}

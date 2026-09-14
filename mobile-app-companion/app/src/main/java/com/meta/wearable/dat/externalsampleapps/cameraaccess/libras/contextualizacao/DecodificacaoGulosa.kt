/*
 * Libras Livre — laço de decodificação gulosa do modelo de contextualização (6.1, 6.2).
 *
 * Separado do TfliteGlossContextualizer para ser testável na JVM, sem LiteRT. Dois defeitos que
 * este laço existe para não repetir:
 *
 *   - FIM DE FRASE: o laço antigo usava `repeat` com `return@repeat` no EOS, que só pula para a
 *     próxima iteração. O modelo rodava sempre os 23 passos, mesmo com a frase pronta no 3º.
 *   - TETO QUE NÃO INTERROMPE: o `withTimeout` da guarda só cancela em ponto de suspensão, e a
 *     geração é bloqueante. Sem [verificarCancelamento] entre os passos, o app esperava a geração
 *     inteira e só DEPOIS caía no template. Com ela, o atraso depois do teto é de um passo.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

object DecodificacaoGulosa {

  /**
   * @param inicio token que abre a sequência do decoder (PAD, na convenção do T5).
   * @param maxPassos teto de passos; a sequência final tem no máximo `maxPassos + 1` tokens.
   * @param fim token de fim de frase; não entra no resultado.
   * @param verificarCancelamento chamado antes de cada passo; deve lançar para interromper.
   * @param passo recebe a sequência até aqui (com [inicio]) e devolve o próximo token.
   * @return os tokens gerados, sem [inicio] e sem [fim].
   */
  fun decodificar(
      inicio: Int,
      maxPassos: Int,
      fim: Int,
      verificarCancelamento: () -> Unit = {},
      passo: (sequencia: List<Int>) -> Int,
  ): List<Int> {
    val sequencia = mutableListOf(inicio)
    for (i in 0 until maxPassos) {
      verificarCancelamento()
      val proximo = passo(sequencia)
      if (proximo == fim) break
      sequencia.add(proximo)
    }
    return sequencia.drop(1)
  }
}

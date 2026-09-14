/*
 * Libras Livre — tetos de tempo do estado ⑦ (docs/prontidao-demo/09-avatar.md §9.1, defeito D).
 *
 * Antes, o ⑦ podia segurar a conversa por até ~105 s (tradução 30 s + 30 s, animação 45 s fixos),
 * com os botões desabilitados. Agora cada espera tem um teto proporcional ao que ela faz, e
 * estourar qualquer um deixa a legenda na tela e devolve a conversa ao ①.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

/**
 * @param traducaoMs teto da tradução texto -> glosa, conexão e leitura somadas.
 * @param animacaoBaseMs parte fixa do teto da animação (entrada e saída do avatar).
 * @param animacaoPorSinalMs quanto cada sinal da glosa acrescenta ao teto da animação.
 * @param folgaMs o que o prazo total do ⑦ dá além dos dois tetos somados.
 */
data class TetosAvatar(
    val traducaoMs: Long = 5_000L,
    val animacaoBaseMs: Long = 3_000L,
    val animacaoPorSinalMs: Long = 1_500L,
    val folgaMs: Long = 2_000L,
) {
  /** Sinais da glosa: `VOCÊ PRECISAR MARCAR&REGISTRAR` são 3 (o `&` desambigua, não soma). */
  fun sinais(glosa: String): Int = glosa.trim().split(ESPACOS).count { it.isNotBlank() }

  fun animacaoMs(glosa: String): Long = animacaoBaseMs + animacaoPorSinalMs * sinais(glosa)

  /** Prazo do ⑦ inteiro, contado do início da tradução. */
  fun totalMs(glosa: String): Long = traducaoMs + animacaoMs(glosa) + folgaMs

  private companion object {
    val ESPACOS = Regex("\\s+")
  }
}

/** Como o ⑦ terminou. Tudo que não é [ANIMOU] ou [PULADO] deixa só a legenda. */
enum class DesfechoAvatar {
  ANIMOU,
  /** O operador tocou "Pular". */
  PULADO,
  /** O avatar estava em FALHOU, ou falhou durante a espera. */
  AVATAR_INDISPONIVEL,
  /** Sem rede e sem cache, ou o teto da tradução estourou. */
  SEM_GLOSA,
  TETO_ANIMACAO,
  TETO_TOTAL,
}

/*
 * Libras Livre — as guardas de §8.1, em três checagens independentes.
 *
 * Porte de contextualization-model/modelo/{fluencia,invencao}.py.
 *
 * POR QUE TRÊS, e não só cobertura: a guarda original do plano (§3.2) verificava apenas que
 * toda glosa APARECE na saída. Duas descobertas do pipeline Python mostraram que isso é
 * necessário e longe de suficiente:
 *
 *   1. DEGENERAÇÃO — com decodificação restrita, o modelo v1 produzia lixo em 54% das
 *      combinações não vistas, e o lixo PASSAVA na cobertura, porque a própria restrição
 *      forçava as glosas a aparecerem: `[onde, você]` virava "ondeJ dele dele dele dele você".
 *      Cobre `onde` e `você`. Seria falado.
 *   2. INVENÇÃO — o modelo acrescenta conteúdo que ninguém sinalizou: `[eu, filho]` virou
 *      "eu tenho um filho que estuda aqui". Medido em 11,9% das sessões no v3, contra 4,8% do
 *      template. Em atendimento isso é pior que soar robótico: a pessoa não disse aquilo.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

object Guardas {

  /** Palavras funcionais + verbos leves: carregam relação, não conteúdo. */
  private val FUNCIONAIS =
      ("""a o as os um uma uns umas ao aos do da dos das no na nos nas num numa pelo pela
      de em por para pra com sem sobre entre ate desde apos e ou mas que se como quando
      e sao era foi ser estar esta estou estao este esse essa isso aquilo aquele aquela
      me te se lhe nos vos meu minha meus minhas seu sua seus suas dele dela
      eu tu ele ela eles elas voce senhor senhora
      muito mais menos ja agora aqui ali la entao tambem so apenas bem mal sim nao
      tenho tem temos ter tinha vou vai vamos ir pode posso podem poderia
      mesmo mesma proprio propria todo toda todos todas outro outra
      fica ficar ficam sente sentir sentindo vem vir indo passa passar
      dia dias hora horas vez vezes coisa parte lugar""")
          .split(Regex("\\s+"))
          .filter { it.isNotBlank() }
          .toSet()

  /** Maiúscula no MEIO de palavra, dígito, ou caractere fora do alfabeto PT. */
  private val SUSPEITO =
      Regex("(?<=[a-záàâãéêíóôõúüç])[A-Z]|\\d|[^\\p{L}\\p{N}\\s,.?!-]")

  /** Mesma palavra 3x seguidas, ou bigrama repetido. */
  fun temRepeticao(texto: String): Boolean {
    val p = texto.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    for (i in 0..p.size - 3) if (p[i] == p[i + 1] && p[i + 1] == p[i + 2]) return true
    val bigramas = (0 until p.size - 1).map { p[it] to p[it + 1] }
    return bigramas.size != bigramas.toSet().size
  }

  /**
   * Saída colapsada? Conservador de propósito: na dúvida NÃO acusa — um falso positivo aqui
   * vira fallback desnecessário. A primeira versão marcava qualquer maiúscula e acusava 100%
   * das saídas do template, que capitaliza a inicial.
   */
  fun degenerado(texto: String, nGlosas: Int): String? {
    val p = texto.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
      p.isEmpty() -> "vazio"
      SUSPEITO.containsMatchIn(texto) -> "caractere suspeito"
      temRepeticao(texto) -> "repetição"
      p.size > maxOf(8, nGlosas * 5) -> "comprimento"
      else -> null
    }
  }

  /**
   * Palavras de conteúdo que nenhuma glosa do enunciado explica (§8.1). Conservador: a lista
   * de funcionais é generosa, então SUBESTIMA. Número alto é confiável; número baixo não prova
   * ausência.
   */
  fun inventadas(texto: String, glosas: List<String>, lexico: LexicoGlosas): List<String> {
    val permitidas = lexico.palavrasPermitidas(glosas)
    return lexico.normalizar(texto).split(" ").filter { p ->
      p.length > 2 &&
          p !in FUNCIONAIS &&
          p !in permitidas &&
          permitidas.none { it.length >= 4 && it.take(4) == p.take(4) }
    }
  }
}

/**
 * Envolve um contextualizador e só deixa passar o que satisfaz TODAS as guardas; senão cai no
 * [fallback]. Nunca lança, nunca fica sem resposta.
 *
 * A assimetria que justifica ser conservador: falar o glossário cru ("banheiro onde") soa
 * robótico mas não mente. Falar uma frase fluente e errada mente com confiança.
 *
 * MEDIDO no v2 (validação sintética): a guarda aceita o modelo em 94,7% das sessões. Nessas,
 * o F1 do modelo (0,837) ainda fica abaixo do template (0,884) — por isso o modelo entra como
 * primário mas o template segue sendo o piso, e a [Contextualizacao.origem] existe para medir
 * essa proporção em campo.
 */
class GuardedGlossContextualizer(
    private val primario: GlossContextualizer,
    private val fallback: GlossContextualizer,
    private val lexico: LexicoGlosas,
    private val timeoutMs: Long = 1_500L,
    private val onRejeicao: (motivo: String) -> Unit = {},
) : GlossContextualizer {

  override suspend fun contextualize(glosas: List<String>): Contextualizacao {
    val candidato =
        runCatching { kotlinx.coroutines.withTimeout(timeoutMs) { primario.contextualize(glosas) } }
            .onFailure { onRejeicao("exceção/timeout: ${it.message}") }
            .getOrNull()

    if (candidato != null) {
      val motivo = rejeitar(candidato.texto, glosas)
      if (motivo == null) return candidato
      onRejeicao(motivo)
    }
    return fallback.contextualize(glosas)
  }

  /** `null` se a saída passa; senão o motivo da rejeição. */
  fun rejeitar(texto: String, glosas: List<String>): String? {
    val ausentes = glosas.filterNot { lexico.cobre(texto, it) }
    if (ausentes.isNotEmpty()) return "cobertura: faltou $ausentes"
    // Regra dura: negação sinalizada que some da saída inverte o sentido. NA PRÁTICA a
    // cobertura acima já barra isso, porque `não` é ela mesma uma glosa — esta linha é
    // redundância defensiva, para o caso de o léxico mudar. Barata demais para remover dado
    // que o alvo do §10 é 1,000 inegociável.
    if (glosas.any { it in lexico.negacoes } && !lexico.temNegacao(texto)) return "negação perdida"
    Guardas.degenerado(texto, glosas.size)?.let { return "fluência ($it)" }
    val inventadas = Guardas.inventadas(texto, glosas, lexico)
    if (inventadas.isNotEmpty()) return "invenção: $inventadas"
    return null
  }

  override fun close() {
    primario.close()
    fallback.close()
  }
}

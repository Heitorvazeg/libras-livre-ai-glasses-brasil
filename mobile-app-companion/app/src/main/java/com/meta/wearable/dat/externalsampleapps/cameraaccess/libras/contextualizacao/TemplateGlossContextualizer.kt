/*
 * Libras Livre — baseline por regras (§4 do plano). Porte de
 * contextualization-model/modelo/template.py, que é a fonte de verdade.
 *
 * Existe por dois motivos, e o primeiro pesa mais: é o PISO. Ele é composicional por
 * construção — monta qualquer combinação das 41 glosas com a mesma qualidade média, porque é
 * regra e não estatística. Nas 120 combinações nunca vistas do conjunto de teste ele degenerou
 * em 0,0%, contra 5,0% do modelo v2. Também é o único que nunca inventa conteúdo (§8.1).
 *
 * Segundo motivo: é o fallback permanente da GuardedGlossContextualizer.
 *
 * MEDIDO (validação sintética, 42 sequências, corpus v3): F1 0,871 contra 0,823 do modelo;
 * conteúdo inventado 0,048 contra 0,119. O modelo não bateu este baseline em nenhuma das três
 * rodadas de treino — por isso ele entra como primário só sob guarda.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

class TemplateGlossContextualizer : GlossContextualizer {

  override suspend fun contextualize(glosas: List<String>) =
      Contextualizacao(montar(glosas), Contextualizacao.Origem.TEMPLATE)

  override fun close() {}

  companion object {
    // Conjugação por pessoa: (1a singular, 3a singular).
    private val VERBOS =
        mapOf(
            "querer" to ("quero" to "quer"),
            "vontade" to ("quero" to "quer"),
            "precisar" to ("preciso" to "precisa"),
            "conhecer" to ("conheço" to "conhece"),
            "esperar" to ("estou esperando" to "está esperando"),
            "voltar" to ("volto" to "volta"),
            "aproveitar" to ("vou aproveitar" to "vai aproveitar"),
            "acontecer" to ("aconteceu" to "aconteceu"),
        )
    private val ESTADOS =
        mapOf(
            "dor" to ("estou com dor" to "está com dor"),
            "medo" to ("estou com medo" to "está com medo"),
            "ruim" to ("estou mal" to "está mal"),
        )
    private val SUJEITOS =
        mapOf("eu" to ("eu" to 0), "você" to ("você" to 1),
              "filho" to ("o meu filho" to 1), "aluno" to ("o aluno" to 1))
    private val OBJETOS =
        mapOf(
            "banheiro" to "o banheiro", "documento" to "o documento", "nome" to "o nome",
            "número" to "a senha", "vacina" to "a vacina", "banco" to "o banco",
            "esquina" to "a esquina", "espelho" to "o espelho", "bala" to "uma bala",
            "maçã" to "uma maçã", "sapo" to "um sapo", "america" to "a américa",
            "barulho" to "barulho", "ajuda" to "ajuda", "cinco" to "cinco",
            "amarelo" to "amarelo", "filho" to "o meu filho", "aluno" to "aluno",
        )
    private val TEMPO = mapOf("manhã" to "de manhã", "noite" to "à noite")
    private val INTERROGATIVOS =
        mapOf("onde" to "Onde fica", "quanto" to "Quanto tempo", "quando" to "Quando")
    // Verbos que regem preposição — sem isto sai "preciso a vacina".
    private val REGENCIA = mapOf("precisar" to "de")
    private val CONTRACAO =
        mapOf("de o" to "do", "de a" to "da", "de um" to "de um", "de uma" to "de uma")

    private fun reger(prep: String, objeto: String): String {
      val cabeca = objeto.substringBefore(' ')
      val resto = objeto.substringAfter(' ', "")
      val junto = CONTRACAO["$prep $cabeca"]
      return if (junto != null) "$junto $resto".trim() else "$prep $objeto"
    }

    fun montar(glosas: List<String>): String {
      var g = glosas.toMutableList()
      val negado = g.remove("não")

      var prefixo = ""
      if (g.remove("oi")) {
        prefixo =
            when {
              g.remove("manhã") -> "bom dia"
              g.remove("noite") -> "boa noite"
              else -> "olá"
            }
      }
      var sufixo = ""
      if (g.remove("por-favor")) sufixo = "por favor"
      if (g.remove("obrigado")) {
        val agradecimento = if (g.remove("ajuda")) "obrigado pela ajuda" else "obrigado"
        prefixo = if (prefixo.isEmpty()) agradecimento else "$prefixo, $agradecimento"
      }
      if (g.remove("sim")) prefixo = if (prefixo.isEmpty()) "sim" else "$prefixo, sim"

      val interrog = g.firstOrNull { it in INTERROGATIVOS }
      if (interrog != null) g.remove(interrog)

      var sujeito = ""
      var pessoa = 0
      for (cand in listOf("eu", "você", "filho", "aluno")) {
        val temNucleo = g.any { it in VERBOS || it in ESTADOS }
        if (cand in g && (cand == "eu" || cand == "você" || temNucleo)) {
          SUJEITOS[cand]?.let { (s, p) -> sujeito = s; pessoa = p }
          g.remove(cand)
          break
        }
      }

      val tempo = g.filter { it in TEMPO }.mapNotNull { TEMPO[it] }
      g = g.filterNot { it in TEMPO }.toMutableList()

      var nucleo = ""
      val verbo = g.firstOrNull { it in VERBOS }
      val estado = g.firstOrNull { it in ESTADOS }
      if (verbo != null) {
        g.remove(verbo)
        nucleo = VERBOS.getValue(verbo).let { if (pessoa == 0) it.first else it.second }
      } else if (estado != null) {
        g.remove(estado)
        nucleo = ESTADOS.getValue(estado).let { if (pessoa == 0) it.first else it.second }
      }

      var objetos = g.mapNotNull { OBJETOS[it] }.toMutableList()
      if ("ruim" in g && estado != "ruim") objetos.add("ruim")
      if (verbo != null && REGENCIA.containsKey(verbo) && objetos.isNotEmpty()) {
        objetos = (listOf(reger(REGENCIA.getValue(verbo), objetos[0])) + objetos.drop(1))
            .toMutableList()
      }

      val corpo =
          (listOf(sujeito) + (if (negado) listOf("não") else emptyList()) + listOf(nucleo) +
                  objetos + tempo)
              .filter { it.isNotBlank() }
              .joinToString(" ")

      var frase =
          when {
            interrog != null -> "${INTERROGATIVOS[interrog]} $corpo?".replace("  ", " ")
            corpo.isNotEmpty() -> "$corpo."
            else -> ""
          }
      var todo = listOf(prefixo, frase).filter { it.isNotBlank() }.joinToString(", ")
      if (sufixo.isNotEmpty()) {
        todo =
            if (todo.isEmpty()) sufixo.replaceFirstChar { it.uppercase() } + "."
            else todo.trimEnd('.', '?', '!') + ", $sufixo."
      }
      todo = todo.trim()
      return if (todo.isEmpty()) glosas.joinToString(" ")
      else todo.replaceFirstChar { it.uppercase() }
    }
  }
}

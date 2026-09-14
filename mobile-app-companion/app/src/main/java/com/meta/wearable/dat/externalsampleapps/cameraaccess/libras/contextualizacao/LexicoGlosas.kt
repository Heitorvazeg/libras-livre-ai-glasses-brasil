/*
 * Libras Livre — o léxico glosa -> formas aceitáveis em PT (§5.4 do plano).
 *
 * Um artefato, três usos: alimenta o TemplateGlossContextualizer, a cobertura da
 * GuardedGlossContextualizer e a detecção de invenção. É gerado em
 * contextualization-model/lexico/lexico-glosas.json e copiado para assets/.
 *
 * ATENÇÃO à completude: um léxico incompleto NÃO é problema estético. A guarda passa a
 * rejeitar saídas CORRETAS do modelo, o que infla a taxa de fallback e faz um modelo bom
 * parecer inútil. Foi medido no pipeline Python: faltavam `volte`, `espere`, `assusta`,
 * `estuda` e as formas de 1a pessoa (`conheço`, `sei`) que em PT carregam o sujeito na
 * morfologia do verbo.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import android.content.Context
import java.text.Normalizer
import org.json.JSONObject

class LexicoGlosas
private constructor(
    /** glosa -> formas de superfície aceitáveis, já normalizadas. */
    private val formas: Map<String, List<String>>,
    /** glosa -> classe gramatical (pronome, verbo, interrogativo...). */
    val classes: Map<String, String>,
    /** Glosas que invertem polaridade — hoje só `não`. Regra dura do §8.1. */
    val negacoes: Set<String>,
) {

  val glosas: Set<String>
    get() = formas.keys

  /** Normalização única do app: minúsculas, sem acento, só alfanumérico e espaço. */
  fun normalizar(texto: String): String {
    val semAcento =
        Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(MARCAS, "")
    return semAcento.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .trim()
        .replace(ESPACOS, " ")
  }

  /** A glosa aparece no texto em alguma forma aceitável? */
  fun cobre(texto: String, glosa: String): Boolean {
    val alvo = normalizar(texto)
    return formas[glosa]?.any { alvo.contains(it) } ?: false
  }

  /** O texto carrega alguma marca de negação? */
  fun temNegacao(texto: String): Boolean {
    val alvo = normalizar(texto)
    return negacoes.any { n -> formas[n]?.any { alvo.contains(it) } == true }
  }

  /** Todas as formas de todas as [glosas] — base para detectar conteúdo inventado. */
  fun palavrasPermitidas(glosas: List<String>): Set<String> =
      glosas.flatMap { g -> formas[g].orEmpty().flatMap { it.split(" ") } }.toSet()

  companion object {
    private val MARCAS = "\\p{Mn}+".toRegex()
    private val ESPACOS = "\\s+".toRegex()

    /** Carrega de `assets/lexico-glosas.json`. Lança se ausente — sem léxico não há guarda. */
    fun fromAssets(context: Context, nome: String = "lexico-glosas.json"): LexicoGlosas {
      val bruto = context.assets.open(nome).bufferedReader().use { it.readText() }
      return parse(bruto)
    }

    fun parse(json: String): LexicoGlosas {
      val raiz = JSONObject(json).getJSONObject("glosas")
      val formas = mutableMapOf<String, List<String>>()
      val classes = mutableMapOf<String, String>()
      val negacoes = mutableSetOf<String>()
      // Instância temporária só para reusar normalizar() na hora de montar as formas.
      val temp = LexicoGlosas(emptyMap(), emptyMap(), emptySet())
      for (glosa in raiz.keys()) {
        val info = raiz.getJSONObject(glosa)
        val arr = info.getJSONArray("formas")
        formas[glosa] = (0 until arr.length()).map { temp.normalizar(arr.getString(it)) }
        classes[glosa] = info.optString("classe", "")
        if (info.optBoolean("negacao", false)) negacoes.add(glosa)
      }
      return LexicoGlosas(formas, classes, negacoes)
    }
  }
}

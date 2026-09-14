/*
 * Libras Livre — o que fazer com uma frase sinalizada (docs/prontidao-demo/02-classificador.md §2.5, §2.8).
 *
 * A decisão é por FRASE, não por sinal: o atendente ouve um aviso quando o app não entendeu, e
 * depois de três frases rejeitadas seguidas vem "tente outro meio de comunicação". O critério é a
 * MENOR confiança da frase, não a média — um sinal duvidoso numa frase de três já muda o sentido.
 *
 * | Situação                                                         | Decisão          | Contador |
 * |------------------------------------------------------------------|------------------|----------|
 * | todas as glosas conhecidas com confiança ≥ limiar                 | Falar(glosas)    | zera     |
 * | alguma < limiar, falha de classificação ou todas fora do léxico  | PedirRepeticao   | +1       |
 * | 3ª rejeição seguida                                              | Desistir         | zera     |
 * | sessão sem segmentos, encerrada por timeout                       | Ignorar          | não muda |
 * | sessão sem segmentos, encerrada manualmente                       | PedirRepeticao   | +1       |
 *
 * Glosa fora do léxico da contextualização (2.5) não é falada: numa sessão mista, só as conhecidas
 * vão para a frase. Classe Kotlin pura.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.Classificacao

enum class MotivoEncerramento {
  /** Botão "Encerrar agora" ou wake word "encerrar". */
  MANUAL,
  /** Teto da captura sem nenhum segmento (4.3). */
  TIMEOUT,
  /** Pausa longa depois de pelo menos um sinal (4.1). */
  SILENCIO,
}

/** O que a sessão de captura produziu. [falhas] conta segmentos que o classificador não conseguiu classificar. */
data class ResultadoSessao(
    val classificacoes: List<Classificacao>,
    val falhas: Int,
    val motivo: MotivoEncerramento,
)

sealed interface DecisaoFrase {
  data class Falar(val glosas: List<String>) : DecisaoFrase

  data object PedirRepeticao : DecisaoFrase

  data object Desistir : DecisaoFrase

  data object Ignorar : DecisaoFrase
}

class AvaliadorDeFrase(
    /** Chaves do léxico da contextualização; null (léxico ausente) aceita todas as glosas. */
    private val glosasConhecidas: Set<String>?,
    private val limiar: () -> Float = { LIMIAR_PADRAO },
    private val maxRejeicoes: Int = 3,
) {

  companion object {
    /** Limiar inicial do plano (2.8); configurável nas configurações de demo na onda 4. */
    const val LIMIAR_PADRAO = 0.60f
  }

  var rejeicoesSeguidas = 0
    private set

  fun foraDoLexico(glosa: String): Boolean = glosasConhecidas != null && glosa !in glosasConhecidas

  fun abaixoDoLimiar(c: Classificacao): Boolean = c.confianca < limiar()

  fun avaliar(resultado: ResultadoSessao): DecisaoFrase {
    val segmentos = resultado.classificacoes.size + resultado.falhas
    if (segmentos == 0) {
      return if (resultado.motivo == MotivoEncerramento.TIMEOUT) DecisaoFrase.Ignorar else rejeitar()
    }
    if (resultado.falhas > 0 || resultado.classificacoes.any(::abaixoDoLimiar)) return rejeitar()
    val conhecidas = resultado.classificacoes.map { it.glosa }.filterNot(::foraDoLexico)
    if (conhecidas.isEmpty()) return rejeitar()
    rejeicoesSeguidas = 0
    return DecisaoFrase.Falar(conhecidas)
  }

  /** O atendimento terminou (inatividade ou "Cancelar atendimento"): a próxima pessoa começa do zero. */
  fun zerar() {
    rejeicoesSeguidas = 0
  }

  private fun rejeitar(): DecisaoFrase {
    rejeicoesSeguidas++
    if (rejeicoesSeguidas >= maxRejeicoes) {
      rejeicoesSeguidas = 0
      return DecisaoFrase.Desistir
    }
    return DecisaoFrase.PedirRepeticao
  }
}

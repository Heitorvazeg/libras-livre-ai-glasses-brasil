/*
 * Libras Livre — o painel de conversa como estado (docs/prontidao-demo/10-tela.md §10.1, defeito E).
 *
 * Antes, o resultado do pipeline só aparecia num banner que "capturando" escondia e que sumia
 * junto com o stream: a banca nunca via o que o app entendeu nem o que falou. Aqui cada turno
 * guarda os sinais, a decisão sobre a frase (2.8), a frase falada, a resposta e como o ⑦ terminou,
 * e um redutor puro aplica os eventos do DialogOrchestrator. A tela só desenha.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.DesfechoAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.Contextualizacao
import kotlin.math.roundToInt

/**
 * Um sinal classificado. [confianca] fica nula enquanto o classificador não devolve uma;
 * [abaixoDoLimiar] e [foraDoLexico] marcam o que o avaliador da frase (2.8, 2.5) não aceitou.
 */
data class SinalNaConversa(
    val glosa: String,
    val confianca: Float? = null,
    val abaixoDoLimiar: Boolean = false,
    val foraDoLexico: Boolean = false,
)

enum class DesfechoTurno {
  AVATAR,
  LEGENDA,
  PULADO,
}

/** A decisão sobre a frase do turno (2.8), como o painel a mostra. */
enum class DecisaoNaConversa {
  FALADA,
  REPITA,
  DESISTIU,
  IGNORADA,
}

data class TurnoConversa(
    val numero: Int,
    val sinais: List<SinalNaConversa> = emptyList(),
    val decisao: DecisaoNaConversa? = null,
    val falado: String? = null,
    val origemFalado: Contextualizacao.Origem? = null,
    val resposta: String? = null,
    val desfecho: DesfechoTurno? = null,
) {
  /** Um turno recém-aberto, ou sem nada reconhecido e sem aviso, não tem o que mostrar. */
  val temConteudo: Boolean
    get() =
        sinais.isNotEmpty() || falado != null || resposta != null ||
            decisao == DecisaoNaConversa.REPITA || decisao == DecisaoNaConversa.DESISTIU

  /** `FILHO 92% · VACINA 88%`; sem confiança, só a glosa. */
  fun textoDosSinais(): String = sinais.joinToString(" · ") { textoDoSinal(it) }

  companion object {
    fun textoDoSinal(s: SinalNaConversa): String {
      val glosa = s.glosa.uppercase()
      return s.confianca?.let { "$glosa ${(it * 100).roundToInt()}%" } ?: glosa
    }
  }
}

data class Conversa(val turnos: List<TurnoConversa> = emptyList()) {
  val atual: TurnoConversa?
    get() = turnos.lastOrNull()

  /** Os turnos anteriores que o painel mostra menores, do mais antigo ao mais recente. */
  val anteriores: List<TurnoConversa>
    get() = turnos.dropLast(1)
}

sealed interface EventoConversa {
  /** Um "iniciar" (ou um "repita") abriu a captura: começa um turno novo. */
  data object TurnoIniciado : EventoConversa

  data class SinalClassificado(val sinal: SinalNaConversa) : EventoConversa

  data class DecisaoTomada(val decisao: DecisaoNaConversa) : EventoConversa

  data class FraseFalada(val texto: String, val origem: Contextualizacao.Origem) : EventoConversa

  data class RespostaTranscrita(val texto: String) : EventoConversa

  data class AvatarTerminou(val desfecho: DesfechoAvatar) : EventoConversa
}

object Conversas {

  /** O turno atual e os 2 anteriores. */
  const val MAX_TURNOS = 3

  fun reduzir(conversa: Conversa, evento: EventoConversa): Conversa =
      when (evento) {
        EventoConversa.TurnoIniciado -> novoTurno(conversa)
        is EventoConversa.SinalClassificado -> {
          // Um sinal sem turno aberto (não deveria acontecer) abre um, em vez de sumir.
          val base = if (conversa.atual == null) novoTurno(conversa) else conversa
          atualizarAtual(base) { it.copy(sinais = it.sinais + evento.sinal) }
        }
        is EventoConversa.DecisaoTomada -> atualizarAtual(conversa) { it.copy(decisao = evento.decisao) }
        is EventoConversa.FraseFalada ->
            atualizarAtual(conversa) { it.copy(falado = evento.texto, origemFalado = evento.origem) }
        is EventoConversa.RespostaTranscrita ->
            atualizarAtual(conversa) { it.copy(resposta = evento.texto) }
        is EventoConversa.AvatarTerminou ->
            atualizarAtual(conversa) { it.copy(desfecho = desfechoDoTurno(evento.desfecho)) }
      }

  fun desfechoDoTurno(desfecho: DesfechoAvatar): DesfechoTurno =
      when (desfecho) {
        DesfechoAvatar.ANIMOU -> DesfechoTurno.AVATAR
        DesfechoAvatar.PULADO -> DesfechoTurno.PULADO
        DesfechoAvatar.AVATAR_INDISPONIVEL,
        DesfechoAvatar.SEM_GLOSA,
        DesfechoAvatar.TETO_ANIMACAO,
        DesfechoAvatar.TETO_TOTAL -> DesfechoTurno.LEGENDA
      }

  fun decisaoNaConversa(decisao: DecisaoFrase): DecisaoNaConversa =
      when (decisao) {
        is DecisaoFrase.Falar -> DecisaoNaConversa.FALADA
        DecisaoFrase.PedirRepeticao -> DecisaoNaConversa.REPITA
        DecisaoFrase.Desistir -> DecisaoNaConversa.DESISTIU
        DecisaoFrase.Ignorar -> DecisaoNaConversa.IGNORADA
      }

  private fun novoTurno(conversa: Conversa): Conversa {
    val numero = (conversa.atual?.numero ?: 0) + 1
    return Conversa((conversa.turnos + TurnoConversa(numero)).takeLast(MAX_TURNOS))
  }

  private fun atualizarAtual(conversa: Conversa, mudar: (TurnoConversa) -> TurnoConversa): Conversa {
    val atual = conversa.atual ?: return conversa
    return Conversa(conversa.turnos.dropLast(1) + mudar(atual))
  }
}

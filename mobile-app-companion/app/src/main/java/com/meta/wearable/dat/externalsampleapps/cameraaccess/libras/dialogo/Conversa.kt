/*
 * Libras Livre — o painel de conversa como estado (docs/prontidao-demo/10-tela.md §10.1, defeito E).
 *
 * Antes, o resultado do pipeline só aparecia num banner que "capturando" escondia e que sumia
 * junto com o stream: a banca nunca via o que o app entendeu nem o que falou. Aqui cada turno
 * guarda os sinais, a frase falada, a resposta e como o ⑦ terminou, e um redutor puro aplica os
 * eventos do DialogOrchestrator. A tela só desenha.
 *
 * Campos que dependem de itens posteriores entram com eles: a decisão do avaliador (2.8, onda 3)
 * e as marcas de glosa descartada (2.5) ou abaixo do limiar (2.8).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.DesfechoAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.Contextualizacao
import kotlin.math.roundToInt

/** Um sinal classificado. [confianca] fica nula até o classificador devolver uma (2.6). */
data class SinalNaConversa(val glosa: String, val confianca: Float? = null)

enum class DesfechoTurno {
  AVATAR,
  LEGENDA,
  PULADO,
}

data class TurnoConversa(
    val numero: Int,
    val sinais: List<SinalNaConversa> = emptyList(),
    val falado: String? = null,
    val origemFalado: Contextualizacao.Origem? = null,
    val resposta: String? = null,
    val desfecho: DesfechoTurno? = null,
) {
  /** Um turno recém-aberto, ou sem nenhum sinal reconhecido, não tem o que mostrar. */
  val temConteudo: Boolean
    get() = sinais.isNotEmpty() || falado != null || resposta != null

  /** `FILHO 92% · VACINA 88%`; sem confiança, só a glosa. */
  fun textoDosSinais(): String =
      sinais.joinToString(" · ") { s ->
        val glosa = s.glosa.uppercase()
        s.confianca?.let { "$glosa ${(it * 100).roundToInt()}%" } ?: glosa
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
  /** Um "iniciar" abriu a captura: começa um turno novo. */
  data object TurnoIniciado : EventoConversa

  data class SinalClassificado(val sinal: SinalNaConversa) : EventoConversa

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

  private fun novoTurno(conversa: Conversa): Conversa {
    val numero = (conversa.atual?.numero ?: 0) + 1
    return Conversa((conversa.turnos + TurnoConversa(numero)).takeLast(MAX_TURNOS))
  }

  private fun atualizarAtual(conversa: Conversa, mudar: (TurnoConversa) -> TurnoConversa): Conversa {
    val atual = conversa.atual ?: return conversa
    return Conversa(conversa.turnos.dropLast(1) + mudar(atual))
  }
}

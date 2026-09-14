/*
 * O painel de conversa como estado (docs/prontidao-demo/10-tela.md §10.1).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.DesfechoAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.Contextualizacao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversaTest {

  private fun aplicar(vararg eventos: EventoConversa, inicial: Conversa = Conversa()): Conversa =
      eventos.fold(inicial, Conversas::reduzir)

  private fun volta(frase: String, resposta: String, desfecho: DesfechoAvatar = DesfechoAvatar.ANIMOU) =
      arrayOf(
          EventoConversa.TurnoIniciado,
          EventoConversa.SinalClassificado(SinalNaConversa("filho")),
          EventoConversa.FraseFalada(frase, Contextualizacao.Origem.TEMPLATE),
          EventoConversa.RespostaTranscrita(resposta),
          EventoConversa.AvatarTerminou(desfecho),
      )

  @Test
  fun `uma volta completa monta o turno do painel`() {
    val conversa =
        aplicar(
            EventoConversa.TurnoIniciado,
            EventoConversa.SinalClassificado(SinalNaConversa("filho")),
            EventoConversa.SinalClassificado(SinalNaConversa("vacina")),
            EventoConversa.SinalClassificado(SinalNaConversa("vontade")),
            EventoConversa.FraseFalada("O meu filho quer a vacina.", Contextualizacao.Origem.TEMPLATE),
            EventoConversa.RespostaTranscrita("qual a idade dele"),
            EventoConversa.AvatarTerminou(DesfechoAvatar.ANIMOU),
        )

    val turno = conversa.atual!!
    assertEquals(1, turno.numero)
    assertEquals("FILHO · VACINA · VONTADE", turno.textoDosSinais())
    assertEquals("O meu filho quer a vacina.", turno.falado)
    assertEquals(Contextualizacao.Origem.TEMPLATE, turno.origemFalado)
    assertEquals("qual a idade dele", turno.resposta)
    assertEquals(DesfechoTurno.AVATAR, turno.desfecho)
    assertTrue(conversa.anteriores.isEmpty())
  }

  @Test
  fun `o painel continua com o resultado depois do turno, ate o proximo iniciar`() {
    // Defeito E: o banner sumia quando o stream desligava. O estado não depende do stream, e só
    // um novo "iniciar" tira o turno de destaque.
    val depoisDaVolta = aplicar(*volta("Cinco.", "tudo bem pode aguardar"))
    assertEquals("Cinco.", depoisDaVolta.atual!!.falado)

    val proximo = aplicar(EventoConversa.TurnoIniciado, inicial = depoisDaVolta)
    assertEquals(2, proximo.atual!!.numero)
    assertTrue(proximo.atual!!.sinais.isEmpty())
    assertNull(proximo.atual!!.falado)
    assertEquals(listOf("Cinco."), proximo.anteriores.map { it.falado })
  }

  @Test
  fun `guarda o turno atual e so os dois anteriores`() {
    val conversa =
        aplicar(
            *volta("Um.", "a"),
            *volta("Dois.", "b"),
            *volta("Três.", "c"),
            *volta("Quatro.", "d"),
        )
    assertEquals(listOf(2, 3, 4), conversa.turnos.map { it.numero })
    assertEquals(listOf("Dois.", "Três."), conversa.anteriores.map { it.falado })
    assertEquals("Quatro.", conversa.atual!!.falado)
  }

  @Test
  fun `desfecho do 7 vira avatar, pulado ou legenda`() {
    assertEquals(DesfechoTurno.AVATAR, Conversas.desfechoDoTurno(DesfechoAvatar.ANIMOU))
    assertEquals(DesfechoTurno.PULADO, Conversas.desfechoDoTurno(DesfechoAvatar.PULADO))
    for (d in listOf(
        DesfechoAvatar.AVATAR_INDISPONIVEL,
        DesfechoAvatar.SEM_GLOSA,
        DesfechoAvatar.TETO_ANIMACAO,
        DesfechoAvatar.TETO_TOTAL,
    )) {
      assertEquals("desfecho $d", DesfechoTurno.LEGENDA, Conversas.desfechoDoTurno(d))
    }
  }

  @Test
  fun `sessao sem sinais nem frase fica com o turno vazio`() {
    val conversa = aplicar(EventoConversa.TurnoIniciado)
    assertEquals("", conversa.atual!!.textoDosSinais())
    assertNull(conversa.atual!!.falado)
    // O painel não desenha uma caixa vazia para ele.
    assertFalse(conversa.atual!!.temConteudo)
    assertTrue(aplicar(EventoConversa.SinalClassificado(SinalNaConversa("medo")), inicial = conversa).atual!!.temConteudo)
  }

  @Test
  fun `sinal sem turno aberto abre um, e eventos que nao abrem turno sao ignorados`() {
    assertTrue(aplicar(EventoConversa.RespostaTranscrita("perdida")).turnos.isEmpty())
    val conversa = aplicar(EventoConversa.SinalClassificado(SinalNaConversa("medo")))
    assertEquals("MEDO", conversa.atual!!.textoDosSinais())
  }

  @Test
  fun `turno de repeticao guarda o pedido e a nova captura abre outro turno`() {
    val conversa =
        aplicar(
            EventoConversa.TurnoIniciado,
            EventoConversa.SinalClassificado(SinalNaConversa("filho", 0.3f, abaixoDoLimiar = true)),
            EventoConversa.DecisaoTomada(DecisaoNaConversa.REPITA),
            EventoConversa.TurnoIniciado,
            EventoConversa.SinalClassificado(SinalNaConversa("filho", 0.9f)),
            EventoConversa.DecisaoTomada(DecisaoNaConversa.FALADA),
            EventoConversa.FraseFalada("O meu filho.", Contextualizacao.Origem.TEMPLATE),
        )
    val repetido = conversa.anteriores.single()
    assertEquals(DecisaoNaConversa.REPITA, repetido.decisao)
    assertTrue(repetido.sinais.single().abaixoDoLimiar)
    assertNull(repetido.falado)
    assertEquals(DecisaoNaConversa.FALADA, conversa.atual!!.decisao)
    assertEquals("O meu filho.", conversa.atual!!.falado)
  }

  @Test
  fun `pedido de repeticao sem nenhum sinal ainda aparece no painel`() {
    val conversa = aplicar(EventoConversa.TurnoIniciado, EventoConversa.DecisaoTomada(DecisaoNaConversa.REPITA))
    assertTrue(conversa.atual!!.temConteudo)
    val ignorada = aplicar(EventoConversa.TurnoIniciado, EventoConversa.DecisaoTomada(DecisaoNaConversa.IGNORADA))
    assertFalse(ignorada.atual!!.temConteudo)
  }

  @Test
  fun `glosa fora do lexico fica marcada como descartada`() {
    val conversa =
        aplicar(
            EventoConversa.TurnoIniciado,
            EventoConversa.SinalClassificado(SinalNaConversa("maca", 0.95f, foraDoLexico = true)),
            EventoConversa.SinalClassificado(SinalNaConversa("medo", 0.9f)),
        )
    val sinais = conversa.atual!!.sinais
    assertEquals(listOf(true, false), sinais.map { it.foraDoLexico })
    assertEquals("MACA 95% · MEDO 90%", conversa.atual!!.textoDosSinais())
    assertEquals(DecisaoNaConversa.DESISTIU, Conversas.decisaoNaConversa(DecisaoFrase.Desistir))
  }

  @Test
  fun `confianca aparece em porcentagem quando existe`() {
    val turno =
        TurnoConversa(
            numero = 1,
            sinais = listOf(SinalNaConversa("filho", 0.92f), SinalNaConversa("vacina", null)),
        )
    assertEquals("FILHO 92% · VACINA", turno.textoDosSinais())
  }
}

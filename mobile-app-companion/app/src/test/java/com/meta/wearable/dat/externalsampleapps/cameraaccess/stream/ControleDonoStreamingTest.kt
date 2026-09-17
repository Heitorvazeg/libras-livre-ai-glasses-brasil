package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.*
import org.junit.Test

class ControleDonoStreamingTest {
  @Test
  fun `teardown A depois de start B nao envia stop global`() {
    val controle = ControleDonoStreaming()
    val enviados = mutableListOf<ControleDonoStreaming.Comando>()
    controle.iniciar("A") { enviados += it }
    controle.iniciar("B") { enviados += it }
    controle.parar("A") { fail("Dono antigo nao pode enviar STOP") }
    assertEquals("B", controle.receber(enviados.last()).donoAtivo)
    assertEquals("B", controle.receber(enviados.first()).donoAtivo)
    controle.parar("B") { enviados += it }
    assertNull(controle.receber(enviados.last()).donoAtivo)
  }

  @Test
  fun `stop A ja enfileirado e start A tardio preservam B em qualquer ordem`() {
    val controle = ControleDonoStreaming()
    val enviados = mutableListOf<ControleDonoStreaming.Comando>()
    controle.iniciar("A") { enviados += it }
    controle.parar("A") { enviados += it }
    controle.iniciar("B") { enviados += it }
    // B entregue primeiro; depois STOP e START de A. Nenhum entrega autoridade ao intent.
    for (comando in listOf(enviados[2], enviados[1], enviados[0], enviados[2])) {
      val decisao = controle.receber(comando)
      assertEquals("B", decisao.donoAtivo)
      assertEquals(comando != enviados[2], decisao.intentDesatualizado)
    }
  }

  @Test
  fun `stop antes da entrega do start impede ressurreicao inclusive mesmo dono`() {
    val controle = ControleDonoStreaming()
    val enviados = mutableListOf<ControleDonoStreaming.Comando>()
    controle.iniciar("VM") { enviados += it }
    controle.parar("VM") { enviados += it }
    assertNull(controle.receber(enviados[1]).donoAtivo)
    assertNull(controle.receber(enviados[0]).donoAtivo)
    controle.iniciar("VM") { enviados += it }
    assertTrue(enviados[2].revisao > enviados[1].revisao)
    assertEquals("VM", controle.receber(enviados[1]).donoAtivo)
    controle.parar("VM") { enviados += it }
    assertNull(controle.receber(enviados[2]).donoAtivo)
    controle.parar("VM") { fail("STOP duplicado nao deve criar servico") }
  }

  @Test
  fun `refresh de audio pertence ao VM mesmo sem stream`() {
    val controle = ControleDonoStreaming()
    var comando: ControleDonoStreaming.Comando? = null
    controle.iniciar("audio") { comando = it }
    assertEquals("audio", controle.receber(comando).donoAtivo)
    controle.parar("outro") { fail("Limpeza sem stream de outro VM nao deve parar audio") }
    assertEquals("audio", controle.receber(null).donoAtivo)
    controle.parar("audio") { comando = it }
    assertNull(controle.receber(comando).donoAtivo)
  }

  @Test
  fun `processo novo nao adota intents antigos nem restart nulo`() {
    val controle = ControleDonoStreaming()
    assertNull(controle.receber(null).donoAtivo)
    assertNull(controle.receber(ControleDonoStreaming.Comando(8, "antigo", true)).donoAtivo)
    assertNull(controle.receber(ControleDonoStreaming.Comando(9, "antigo", false)).donoAtivo)
    controle.parar("nunca iniciou") { fail("Nao criar FGS so para parar") }
  }

  @Test
  fun `envio rejeitado preserva dono anterior sem reutilizar revisao`() {
    val controle = ControleDonoStreaming()
    val enviados = mutableListOf<ControleDonoStreaming.Comando>()
    controle.iniciar("A") { enviados += it }
    val erro = IllegalStateException("startForegroundService rejeitado")
    try {
      controle.iniciar("B") { throw erro }
      fail("Deveria propagar rejeicao")
    } catch (e: IllegalStateException) {
      assertSame(erro, e)
    }
    assertEquals("A", controle.receber(enviados.last()).donoAtivo)
    try {
      controle.parar("A") { throw erro }
      fail("Deveria propagar rejeicao")
    } catch (e: IllegalStateException) {
      assertSame(erro, e)
    }
    assertEquals("A", controle.receber(null).donoAtivo)
    controle.parar("A") { enviados += it }
    assertEquals(4L, enviados.last().revisao)
    assertNull(controle.receber(enviados.first()).donoAtivo)
  }
}
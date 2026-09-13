/*
 * Configurações de demo, parte mínima da onda 2 (docs/prontidao-demo/10-tela.md §10.6).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoPlaceholder
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfiguracoesDemoTest {

  private class Memoria : ConfiguracoesDemo.Armazenamento {
    val mapa = mutableMapOf<String, String>()

    override fun ler(chave: String) = mapa[chave]

    override fun gravar(chave: String, valor: String) {
      mapa[chave] = valor
    }
  }

  @Test
  fun `padroes do plano sem nada salvo`() {
    val valores = ConfiguracoesDemo(Memoria()).valores.value
    assertEquals(false, valores.gravadorSessao)
    assertEquals(false, valores.painelMetricas)
    assertEquals(ModoPlaceholder.ROTEIRO, valores.modoPlaceholder)
  }

  @Test
  fun `valor salvo sobrevive a uma nova abertura`() {
    val memoria = Memoria()
    ConfiguracoesDemo(memoria).atualizar { it.copy(gravadorSessao = true, modoPlaceholder = ModoPlaceholder.BAIXA) }
    val reaberta = ConfiguracoesDemo(memoria).valores.value
    assertEquals(true, reaberta.gravadorSessao)
    assertEquals(ModoPlaceholder.BAIXA, reaberta.modoPlaceholder)
  }

  @Test
  fun `voltar ao padrao restaura e persiste os padroes`() {
    val memoria = Memoria()
    val configuracoes = ConfiguracoesDemo(memoria)
    configuracoes.atualizar { ValoresDemo(gravadorSessao = true, painelMetricas = true, modoPlaceholder = ModoPlaceholder.ALTA) }
    configuracoes.voltarAoPadrao()
    assertEquals(ValoresDemo(), configuracoes.valores.value)
    assertEquals(ValoresDemo(), ConfiguracoesDemo(memoria).valores.value)
  }

  @Test
  fun `valor ilegivel cai no padrao`() {
    val memoria = Memoria().apply {
      mapa["gravador_sessao"] = "talvez"
      mapa["modo_placeholder"] = "MODO_QUE_NAO_EXISTE"
    }
    assertEquals(ValoresDemo(), ConfiguracoesDemo(memoria).valores.value)
  }
}

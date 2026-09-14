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
  fun `padroes decididos no plano para os seletores da onda 4`() {
    val v = ValoresDemo()
    assertEquals(0.7f, v.segmentacao.limiarEntrada)
    assertEquals(500L, v.segmentacao.pausaMs)
    assertEquals(30_000L, v.tetoCapturaMs)
    assertEquals(20_000L, v.tetoEscutaMs)
    assertEquals(MotorWakeWord.SPEECH_RECOGNIZER, v.motorWakeWord)
    assertEquals(true, v.comandoDeVoz)
    assertEquals(SaidaVoz.OCULOS, v.saidaVoz)
    assertEquals(MicrofoneResposta.CELULAR, v.microfoneResposta)
    assertEquals(300L, v.folgaAposFalaMs)
    assertEquals(0.60f, v.limiarConfianca)
    assertEquals(1.5f, v.fatorLimiarMemoria)
    assertEquals(5_000L, v.tetoTraducaoMs)
    assertEquals(3_000L, v.tetoAnimacaoBaseMs)
    assertEquals(1_500L, v.tetoAnimacaoPorSinalMs)
  }

  @Test
  fun `parametros de segmentacao editados persistem e combinacao invalida nao grava`() {
    val memoria = Memoria()
    val configuracoes = ConfiguracoesDemo(memoria)
    configuracoes.atualizar { it.copy(segmentacao = it.segmentacao.copy(limiarEntrada = 0.9f, pausaMs = 650)) }
    val reaberta = ConfiguracoesDemo(memoria).valores.value.segmentacao
    assertEquals(0.9f, reaberta.limiarEntrada)
    assertEquals(650L, reaberta.pausaMs)

    // Saída acima da entrada: o construtor recusa e nada muda.
    val antes = configuracoes.valores.value
    runCatching { configuracoes.atualizar { it.copy(segmentacao = it.segmentacao.copy(limiarSaida = 2f)) } }
    assertEquals(antes, configuracoes.valores.value)
    assertEquals(antes, ConfiguracoesDemo(memoria).valores.value)
  }

  @Test
  fun `segmentacao gravada invalida cai no padrao inteiro`() {
    val memoria = Memoria().apply {
      mapa["seg_limiar_entrada"] = "0.3"
      mapa["seg_limiar_saida"] = "0.5"
    }
    assertEquals(ValoresDemo().segmentacao, ConfiguracoesDemo(memoria).valores.value.segmentacao)
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

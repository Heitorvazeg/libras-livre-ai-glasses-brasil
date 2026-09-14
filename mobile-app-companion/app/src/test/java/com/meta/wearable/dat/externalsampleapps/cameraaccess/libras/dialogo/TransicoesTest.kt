/*
 * Regras de transição do diálogo (docs/prontidao-demo/04-turnos-wake-word-e-botoes.md).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransicoesTest {

  @Test
  fun `wake word ouve em 1, 2 e 4 e fica pausada em 3, 5, 6 e 7`() {
    val esperado =
        mapOf(
            DialogState.AGUARDANDO_SINAL to true,
            DialogState.CAPTURANDO_SINAIS to true,
            DialogState.FALANDO to false,
            DialogState.AGUARDANDO_RESPOSTA to true,
            DialogState.ESCUTANDO_ATENDENTE to false,
            DialogState.TRANSCREVENDO to false,
            DialogState.GERANDO_AVATAR to false,
        )
    // Garante que um estado novo no enum não passe sem decisão.
    assertEquals(DialogState.entries.toSet(), esperado.keys)
    for ((estado, ativa) in esperado) {
      assertEquals("wake word em $estado", ativa, Transicoes.wakeWordAtiva(estado))
    }
  }

  @Test
  fun `wake word nao disputa o microfone com o Vosk no 5`() {
    // 4.4: regressão. Até a onda 1, ESCUTANDO_ATENDENTE estava na lista.
    assertFalse(Transicoes.wakeWordAtiva(DialogState.ESCUTANDO_ATENDENTE))
  }

  @Test
  fun `pausa de 2,4 s nao encerra a captura e 2,6 s encerra`() {
    assertFalse(Transicoes.encerrarCapturaPorSilencio(segmentos = 2, paradoHaMs = 2_400))
    assertTrue(Transicoes.encerrarCapturaPorSilencio(segmentos = 2, paradoHaMs = 2_600))
  }

  @Test
  fun `pausa sem nenhum segmento nao encerra`() {
    assertFalse(Transicoes.encerrarCapturaPorSilencio(segmentos = 0, paradoHaMs = 10_000))
  }

  @Test
  fun `fim de fala com texto vazio vai ao 4 e com texto vai ao avatar`() {
    assertEquals(DialogState.AGUARDANDO_RESPOSTA, Transicoes.estadoAposTranscricao("   "))
    assertEquals(DialogState.GERANDO_AVATAR, Transicoes.estadoAposTranscricao("qual a idade dele"))
  }

  @Test
  fun `pedir repeticao volta ao 2 e falar abre a escuta pulando o 4`() {
    assertEquals(DialogState.CAPTURANDO_SINAIS, Transicoes.estadoAposDecisao(DecisaoFrase.PedirRepeticao))
    assertEquals(DialogState.ESCUTANDO_ATENDENTE, Transicoes.estadoAposDecisao(DecisaoFrase.Falar(listOf("filho"))))
    assertEquals(DialogState.AGUARDANDO_SINAL, Transicoes.estadoAposDecisao(DecisaoFrase.Desistir))
    assertEquals(DialogState.AGUARDANDO_SINAL, Transicoes.estadoAposDecisao(DecisaoFrase.Ignorar))
  }

  @Test
  fun `rotulo e acao do botao principal em cada estado`() {
    val esperado =
        mapOf(
            DialogState.AGUARDANDO_SINAL to BotaoPrincipal(RotuloBotao.INICIAR, AcaoBotao.INICIAR),
            DialogState.CAPTURANDO_SINAIS to BotaoPrincipal(RotuloBotao.ENCERRAR_AGORA, AcaoBotao.ENCERRAR_CAPTURA),
            DialogState.FALANDO to BotaoPrincipal(RotuloBotao.FALANDO, null),
            DialogState.AGUARDANDO_RESPOSTA to BotaoPrincipal(RotuloBotao.OUVIR_RESPOSTA, AcaoBotao.OUVIR),
            DialogState.ESCUTANDO_ATENDENTE to BotaoPrincipal(RotuloBotao.ENCERRAR_AGORA, AcaoBotao.ENCERRAR_ESCUTA),
            DialogState.TRANSCREVENDO to BotaoPrincipal(RotuloBotao.TRANSCREVENDO, null),
            DialogState.GERANDO_AVATAR to BotaoPrincipal(RotuloBotao.PULAR, AcaoBotao.PULAR),
        )
    assertEquals(DialogState.entries.toSet(), esperado.keys)
    for ((estado, botao) in esperado) {
      assertEquals("botão em $estado", botao, Transicoes.botaoPrincipal(estado, oculosDisponiveis = true))
    }
    assertFalse(Transicoes.botaoPrincipal(DialogState.FALANDO, true).habilitado)
  }

  @Test
  fun `sem oculos o botao do 1 fica desabilitado com o motivo`() {
    val botao = Transicoes.botaoPrincipal(DialogState.AGUARDANDO_SINAL, oculosDisponiveis = false)
    assertEquals(RotuloBotao.CONECTE_OS_OCULOS, botao.rotulo)
    assertFalse(botao.habilitado)
    // Fora do ①, o botão não depende dos óculos (a escuta e o avatar não usam a câmera).
    assertEquals(AcaoBotao.OUVIR, Transicoes.botaoPrincipal(DialogState.AGUARDANDO_RESPOSTA, false).acao)
  }
}

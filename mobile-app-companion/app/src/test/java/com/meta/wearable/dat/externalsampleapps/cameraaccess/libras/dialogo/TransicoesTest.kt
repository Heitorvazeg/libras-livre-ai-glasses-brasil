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
  fun `wake word ouve em 1, 2 e 4 e fica pausada em 1,5, 2,5, 3, 3,5, 5, 6 e 7`() {
    val esperado =
        mapOf(
            DialogState.AGUARDANDO_SINAL to true,
            // [NOVO] docs/consentimento-por-atendimento-plano.md §1 — mesmo padrão de ②.5/③⑥⑦:
            // as wake words não decidem consentimento por ninguém.
            DialogState.PEDINDO_CONSENTIMENTO to false,
            DialogState.CAPTURANDO_SINAIS to true,
            // [NOVO] docs/confirmacao-e-modo-economia-plano.md §1.4 — mesmo padrão de ③⑥⑦.
            DialogState.CONFIRMANDO_RECONHECIMENTO to false,
            DialogState.FALANDO to false,
            // ③.5 — mesmo padrão de ①.5/②.5: quem reabre a captura é o toque do operador, não uma
            // palavra ouvida enquanto o avatar ainda está explicando o pedido de repetição.
            DialogState.PEDINDO_REPETICAO to false,
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
  fun `pedir repeticao passa pelo avatar antes do 2 e falar vai pra confirmacao do surdo antes do 5`() {
    // [MUDOU] docs/confirmacao-e-modo-economia-plano.md §1.4: Falar não pula mais direto pra
    // ESCUTANDO_ATENDENTE — passa por ②.5 CONFIRMANDO_RECONHECIMENTO primeiro.
    // [MUDOU] "repita" também não volta mais direto pro ②: o pedido é apresentado a quem sinalizou
    // em ③.5 e a captura só reabre no "Capturar de novo".
    assertEquals(DialogState.PEDINDO_REPETICAO, Transicoes.estadoAposDecisao(DecisaoFrase.PedirRepeticao))
    assertEquals(
        DialogState.CONFIRMANDO_RECONHECIMENTO, Transicoes.estadoAposDecisao(DecisaoFrase.Falar(listOf("filho"))))
    assertEquals(DialogState.AGUARDANDO_SINAL, Transicoes.estadoAposDecisao(DecisaoFrase.Desistir))
    assertEquals(DialogState.AGUARDANDO_SINAL, Transicoes.estadoAposDecisao(DecisaoFrase.Ignorar))
  }

  @Test
  fun `rotulo e acao do botao principal em cada estado`() {
    val esperado =
        mapOf(
            DialogState.AGUARDANDO_SINAL to BotaoPrincipal(RotuloBotao.INICIAR, AcaoBotao.INICIAR),
            // [NOVO] docs/consentimento-por-atendimento-plano.md §2.2 — ação null de propósito:
            // "Aceitar"/"Recusar" não passam por AcaoBotao, a tela desenha os dois à parte.
            DialogState.PEDINDO_CONSENTIMENTO to BotaoPrincipal(RotuloBotao.CONSENTIMENTO_PENDENTE, null),
            DialogState.CAPTURANDO_SINAIS to BotaoPrincipal(RotuloBotao.ENCERRAR_AGORA, AcaoBotao.ENCERRAR_CAPTURA),
            // [NOVO] docs/confirmacao-e-modo-economia-plano.md §1.3 — "Corrigir" é um botão
            // pequeno à parte, fora do modelo de botão principal (ver AcaoBotao.CONFIRMAR).
            DialogState.CONFIRMANDO_RECONHECIMENTO to BotaoPrincipal(RotuloBotao.CONFIRMAR, AcaoBotao.CONFIRMAR),
            DialogState.FALANDO to BotaoPrincipal(RotuloBotao.FALANDO, null),
            // ③.5: reabre a captura sem passar por ①.5 — ação própria, não INICIAR.
            DialogState.PEDINDO_REPETICAO to BotaoPrincipal(RotuloBotao.REPETIR, AcaoBotao.REPETIR),
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
  fun `com o comando de voz desligado nenhum estado ativa a wake word`() {
    // 4.6: os botões continuam valendo (a regra do botão não depende da voz).
    for (estado in DialogState.entries) {
      assertFalse("wake word em $estado", Transicoes.wakeWordAtiva(estado, habilitada = false))
    }
    assertEquals(AcaoBotao.INICIAR, Transicoes.botaoPrincipal(DialogState.AGUARDANDO_SINAL, true).acao)
  }

  @Test
  fun `antes do aquecimento terminar o iniciar fica desabilitado`() {
    val botao = Transicoes.botaoPrincipal(DialogState.AGUARDANDO_SINAL, oculosDisponiveis = true, aquecido = false)
    assertEquals(RotuloBotao.PREPARANDO, botao.rotulo)
    assertFalse(botao.habilitado)
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

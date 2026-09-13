/*
 * Regras de transição do diálogo (docs/prontidao-demo/04-turnos-wake-word-e-botoes.md).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

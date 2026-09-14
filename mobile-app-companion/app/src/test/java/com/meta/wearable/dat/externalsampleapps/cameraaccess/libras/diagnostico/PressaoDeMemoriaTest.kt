/*
 * Reação à falta de memória (docs/prontidao-demo/08-memoria.md §8.1).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.AvatarState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.PressaoDeMemoria.AcaoAvatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressaoDeMemoriaTest {

  private val mb = 1024L * 1024L

  @Test
  fun `memoria baixa abaixo de 1,5 vez o limiar do sistema ou com lowMemory`() {
    assertTrue(PressaoDeMemoria.memoriaBaixa(disponivelBytes = 290 * mb, limiarSistemaBytes = 200 * mb, lowMemory = false, fator = 1.5f))
    assertFalse(PressaoDeMemoria.memoriaBaixa(disponivelBytes = 310 * mb, limiarSistemaBytes = 200 * mb, lowMemory = false, fator = 1.5f))
    assertTrue(PressaoDeMemoria.memoriaBaixa(disponivelBytes = 4_000 * mb, limiarSistemaBytes = 200 * mb, lowMemory = true, fator = 1.5f))
  }

  @Test
  fun `nao libera o avatar no meio da animacao, libera depois`() {
    assertEquals(AcaoAvatar.LIBERAR_DEPOIS_DA_ANIMACAO, PressaoDeMemoria.decidir(true, AvatarState.ANIMANDO, false, false).avatar)
    assertEquals(AcaoAvatar.LIBERAR_AGORA, PressaoDeMemoria.decidir(true, AvatarState.PRONTO, false, false).avatar)
    assertEquals(AcaoAvatar.NENHUMA, PressaoDeMemoria.decidir(true, AvatarState.OCIOSO, false, false).avatar)
  }

  @Test
  fun `libera a voz de reserva so se estiver ociosa`() {
    assertTrue(PressaoDeMemoria.decidir(true, AvatarState.OCIOSO, vozReservaCriada = true, vozReservaEmUso = false).liberarVozReserva)
    assertFalse(PressaoDeMemoria.decidir(true, AvatarState.OCIOSO, vozReservaCriada = true, vozReservaEmUso = true).liberarVozReserva)
    assertFalse(PressaoDeMemoria.decidir(true, AvatarState.OCIOSO, vozReservaCriada = false, vozReservaEmUso = false).liberarVozReserva)
  }

  @Test
  fun `sem memoria baixa nada e liberado`() {
    val d = PressaoDeMemoria.decidir(false, AvatarState.PRONTO, vozReservaCriada = true, vozReservaEmUso = false)
    assertEquals(AcaoAvatar.NENHUMA, d.avatar)
    assertFalse(d.liberarVozReserva)
  }
}

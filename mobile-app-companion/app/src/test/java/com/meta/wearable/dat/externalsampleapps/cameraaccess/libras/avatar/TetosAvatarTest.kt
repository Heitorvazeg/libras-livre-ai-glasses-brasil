/*
 * Tetos do estado ⑦ (docs/prontidao-demo/09-avatar.md §9.1).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TetosAvatarTest {

  private val tetos = TetosAvatar()

  @Test
  fun `conta um sinal por palavra da glosa, com o e comercial dentro do sinal`() {
    assertEquals(3, tetos.sinais("VOCÊ PRECISAR MARCAR&REGISTRAR"))
    assertEquals(1, tetos.sinais("CINCO"))
    assertEquals(2, tetos.sinais("  IDADE   QUAL \n"))
    assertEquals(0, tetos.sinais("   "))
  }

  @Test
  fun `teto da animacao e 3 s mais 1,5 s por sinal`() {
    assertEquals(3_000L + 1_500L, tetos.animacaoMs("CINCO"))
    assertEquals(3_000L + 3 * 1_500L, tetos.animacaoMs("VOCÊ PRECISAR MARCAR&REGISTRAR"))
  }

  @Test
  fun `prazo total e traducao mais animacao mais 2 s`() {
    // 5 s + (3 s + 2 × 1,5 s) + 2 s
    assertEquals(13_000L, tetos.totalMs("IDADE QUAL"))
  }

  @Test
  fun `sem rede o 7 termina em no maximo cerca de 7 s`() {
    // Sem glosa não há animação: o ⑦ custa o teto da tradução mais a folga.
    assertTrue(tetos.traducaoMs + tetos.folgaMs <= 7_000L)
  }

  @Test
  fun `os tetos sao configuraveis`() {
    val curtos = TetosAvatar(traducaoMs = 1_000L, animacaoBaseMs = 500L, animacaoPorSinalMs = 100L, folgaMs = 0L)
    assertEquals(1_000L + 500L + 200L, curtos.totalMs("A B"))
  }
}

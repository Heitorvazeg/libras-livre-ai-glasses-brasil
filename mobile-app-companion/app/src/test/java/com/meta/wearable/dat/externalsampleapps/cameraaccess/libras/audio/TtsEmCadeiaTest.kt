/*
 * Voz em cadeia (docs/prontidao-demo/05-audio.md §5.5, 08-memoria.md §8.3).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEmCadeiaTest {

  private class Falso(var funciona: Boolean) : TtsEngine {
    val faladas = mutableListOf<String>()
    var aquecimentos = 0
    var desligado = false

    override suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit): Boolean {
      if (!funciona) return false
      onInicioAudio()
      faladas += text
      return true
    }

    override suspend fun aquecer(frases: List<String>): Boolean {
      aquecimentos++
      return funciona
    }

    override fun stop() {}

    override fun shutdown() {
      desligado = true
    }
  }

  @Test
  fun `com o Piper funcionando a reserva nunca e criada`() = runBlocking {
    var criadas = 0
    val cadeia = TtsEmCadeia(Falso(true), criarReserva = { criadas++; Falso(true) })
    assertTrue(cadeia.aquecer(listOf("olá")))
    assertTrue(cadeia.speakAndAwait("O meu filho quer a vacina."))
    assertEquals(0, criadas)
    assertFalse(cadeia.reservaCriada)
  }

  @Test
  fun `motor que falha usa a reserva e avisa uma vez so`() = runBlocking {
    val reserva = Falso(true)
    var avisos = 0
    val cadeia = TtsEmCadeia(Falso(false), criarReserva = { reserva }, onReserva = { avisos++ })
    assertTrue(cadeia.speakAndAwait("primeira"))
    assertTrue(cadeia.speakAndAwait("segunda"))
    assertEquals(listOf("primeira", "segunda"), reserva.faladas)
    assertEquals(1, avisos)
    assertTrue(cadeia.emReserva)
  }

  @Test
  fun `falha no aquecimento ja troca para a reserva e a aquece`() = runBlocking {
    val principal = Falso(false)
    val reserva = Falso(true)
    var avisos = 0
    val cadeia = TtsEmCadeia(principal, criarReserva = { reserva }, onReserva = { avisos++ })
    assertFalse(cadeia.aquecer(listOf("aviso")))
    assertEquals(1, reserva.aquecimentos)
    // Mesmo que o principal "volte", a cadeia fica na reserva até reiniciar.
    principal.funciona = true
    cadeia.speakAndAwait("depois")
    assertEquals(listOf("depois"), reserva.faladas)
    assertEquals(1, avisos)
  }

  @Test
  fun `reserva em uso nao e liberada por memoria`() = runBlocking {
    val reserva = Falso(true)
    val cadeia = TtsEmCadeia(Falso(false), criarReserva = { reserva })
    cadeia.speakAndAwait("x")
    assertFalse(cadeia.liberarReservaOciosa())
    assertFalse(reserva.desligado)
  }
}

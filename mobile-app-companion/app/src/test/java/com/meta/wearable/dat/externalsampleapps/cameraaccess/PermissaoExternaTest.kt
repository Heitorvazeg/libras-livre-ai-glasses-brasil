package com.meta.wearable.dat.externalsampleapps.cameraaccess

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissaoExternaTest {
  @Test
  fun `cancelar A nao permite que resultado externo A retome B`() = runTest {
    val ponte = PermissaoExterna<String, String>()
    val lancados = mutableListOf<String>()
    ponte.associar(Any()) { lancados += it }
    val a = async { ponte.solicitar("A") }
    runCurrent()
    a.cancelAndJoin()
    val b = async { ponte.solicitar("B") }
    runCurrent()
    assertEquals(listOf("A"), lancados)
    ponte.receber("resultado A")
    runCurrent()
    assertEquals(listOf("A", "B"), lancados)
    assertFalse(b.isCompleted)
    ponte.receber("resultado B")
    assertEquals("resultado B", b.await())
  }

  @Test
  fun `cancelar na fila nao libera nem altera pedido externo em voo`() = runTest {
    val ponte = PermissaoExterna<String, Boolean>()
    val lancados = mutableListOf<String>()
    ponte.associar(Any()) { lancados += it }
    val a = async { ponte.solicitar("A") }
    runCurrent()
    val b = async { ponte.solicitar("B") }
    runCurrent()
    b.cancelAndJoin()
    ponte.receber(true)
    assertTrue(a.await())
    assertEquals(listOf("A"), lancados)
    ponte.associar(Any()) { ponte.receber(false) }
    val c = async { ponte.solicitar("C") }
    assertFalse(c.await())
  }

  @Test
  fun `cancelar depois do callback antes de retomar nao contamina proximo pedido`() = runTest {
    val ponte = PermissaoExterna<Unit, String>()
    ponte.associar(Any()) {}
    val a = async { ponte.solicitar(Unit) }
    runCurrent()
    ponte.receber("A")
    a.cancelAndJoin()
    val b = async { ponte.solicitar(Unit) }
    runCurrent()
    assertFalse(b.isCompleted)
    ponte.receber("B")
    assertEquals("B", b.await())
  }

  @Test
  fun `falha sincrona de launch libera slot e resultado sem dono e descartado`() = runTest {
    val ponte = PermissaoExterna<Unit, Boolean>()
    ponte.receber(true)
    val erro = IllegalStateException("launcher indisponivel")
    ponte.associar(Any()) { throw erro }
    try {
      ponte.solicitar(Unit)
      fail("Deveria propagar a falha")
    } catch (e: IllegalStateException) {
      assertSame(erro, e)
    }
    // Callback síncrono também é suportado: limpar antes de acordar o próximo dono.
    ponte.associar(Any()) { ponte.receber(false) }
    assertFalse(ponte.solicitar(Unit))
  }

  @Test
  fun `launchers compartilham exclusao mas nao o destino do resultado`() = runTest {
    val exclusao = Mutex()
    val camera = PermissaoExterna<Unit, String>(exclusao)
    val audio = PermissaoExterna<Unit, Boolean>(exclusao)
    var audioLancado = false
    camera.associar(Any()) {}
    audio.associar(Any()) { audioLancado = true }
    val a = async { camera.solicitar(Unit) }
    runCurrent()
    a.cancelAndJoin()
    val b = async { audio.solicitar(Unit) }
    runCurrent()
    assertFalse(audioLancado)
    camera.receber("antigo")
    runCurrent()
    assertTrue(audioLancado)
    camera.receber("sem dono")
    assertFalse(b.isCompleted)
    audio.receber(true)
    assertTrue(b.await())
  }

  @Test
  fun `rotacao troca launcher enquanto B espera mutex e resultado A nao resolve B`() = runTest {
    val ponte = PermissaoExterna<String, String>()
    val activityVelha = Any()
    val activityNova = Any()
    val lancados = mutableListOf<String>()
    var removido = false
    ponte.associar(activityVelha) {
      check(!removido) { "Launcher da Activity destruida" }
      lancados += "velha:$it"
    }
    val a = async { ponte.solicitar("A") }
    runCurrent()
    a.cancelAndJoin()
    val b = async { ponte.solicitar("B") }
    runCurrent()
    ponte.dissociar(activityVelha)
    removido = true
    ponte.associar(activityNova) { lancados += "nova:$it" }
    // onDestroy antigo não desfaz onStart da nova Activity.
    ponte.dissociar(activityVelha)
    ponte.receber("resultado A")
    runCurrent()
    assertEquals(listOf("velha:A", "nova:B"), lancados)
    assertFalse(b.isCompleted)
    ponte.receber("resultado B")
    assertEquals("resultado B", b.await())
  }

  @Test
  fun `resultado A sem Activity ativa nao lanca B ate nova associacao`() = runTest {
    val ponte = PermissaoExterna<String, String>()
    val dona = Any()
    val lancados = mutableListOf<String>()
    ponte.associar(dona) { lancados += "velha:$it" }
    val a = async { ponte.solicitar("A") }
    runCurrent()
    val b = async { ponte.solicitar("B") }
    runCurrent()
    ponte.dissociar(dona)
    ponte.receber("resultado A")
    runCurrent()
    assertEquals("resultado A", a.await())
    assertEquals(listOf("velha:A"), lancados)
    assertFalse(b.isCompleted)
    ponte.associar(Any()) { lancados += "nova:$it" }
    runCurrent()
    assertEquals(listOf("velha:A", "nova:B"), lancados)
    ponte.receber("resultado B")
    assertEquals("resultado B", b.await())
  }

  @Test
  fun `Activity que acordou a espera pode parar antes de B retomar`() = runTest {
    val ponte = PermissaoExterna<Unit, Boolean>()
    val b = async { ponte.solicitar(Unit) }
    runCurrent()
    val transitoria = Any()
    ponte.associar(transitoria) { fail("Activity ja parou") }
    ponte.dissociar(transitoria)
    runCurrent()
    assertFalse(b.isCompleted)
    ponte.associar(Any()) { ponte.receber(true) }
    assertTrue(b.await())
  }

  @Test
  fun `cancelar espera sem Activity libera mutex mas nao inventa pedido externo`() = runTest {
    val exclusao = Mutex()
    val camera = PermissaoExterna<Unit, String>(exclusao)
    val audio = PermissaoExterna<Unit, Boolean>(exclusao)
    val a = async { camera.solicitar(Unit) }
    runCurrent()
    a.cancelAndJoin()
    camera.associar(Any()) { fail("Pedido cancelado nao pode lancar") }
    camera.receber("sem dono")
    audio.associar(Any()) { audio.receber(true) }
    assertTrue(audio.solicitar(Unit))
  }
}
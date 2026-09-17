package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConclusaoCallbacksCodecTest {
  // Thread real (como o HandlerThread); falhas nela voltam ao teste, não se perdem no executor.
  private class Fila(acao: () -> Unit) {
    private val falha = AtomicReference<Throwable?>()
    val thread = Thread {
      try {
        acao()
      } catch (e: Throwable) {
        falha.set(e)
      }
    }.apply { isDaemon = true }

    fun conferir() {
      assertFalse("Drenagem retornou antes do término da thread", thread.isAlive)
      falha.get()?.let { throw AssertionError("Falha na thread de callback", it) }
    }
  }

  private fun CountDownLatch.esperar() {
    // Timeout é apenas watchdog de teste, nunca critério de conclusão da produção.
    check(await(10, TimeUnit.SECONDS)) { "Teste não liberou o latch" }
  }

  @Test
  fun `falhas de input output e onError capturadas antes de stop sao publicadas antes da barreira`() = runTest {
    for (etapa in listOf("entrada", "saida", "codec")) {
      val owner = CodecCallbackOwner<Any>()
      val codec = Any()
      owner.prepare(codec)
      owner.enableCallbacks(codec)
      val conclusao = ConclusaoCallbacksCodec()
      val capturada = CompletableDeferred<Unit>()
      val invalidado = CompletableDeferred<Unit>()
      val publicar = CountDownLatch(1)
      val publicacoes = AtomicInteger()
      val erro = IllegalStateException(etapa)
      val fila = Fila {
        val failure = owner.use(codec) { erro }
        capturada.complete(Unit)
        publicar.esperar() // Janela real: saiu de owner.use, ainda não chamou reportFailures.
        assertSame(erro, failure)
        assertNull(owner.current())
        publicacoes.incrementAndGet() // Não suprimir só porque o codec foi invalidado.
      }
      conclusao.registrar(fila.thread)
      fila.thread.start()
      try {
        capturada.await()
        var recursosFechados = false
        val drenagem = launch {
          conclusao.pararEDrenar {
            owner.stop()
            invalidado.complete(Unit) // Simula stop + quit, que não interrompe callback em voo.
          }
          recursosFechados = true
        }
        runCurrent()
        invalidado.await()
        runCurrent()
        assertFalse(drenagem.isCompleted)
        assertFalse(recursosFechados)
        assertEquals(0, publicacoes.get())
        var mainLivre = false
        launch { mainLivre = true }
        runCurrent()
        assertTrue(mainLivre)
        publicar.countDown()
        drenagem.join()
        fila.conferir()
        assertEquals(1, publicacoes.get())
        assertTrue(recursosFechados)
        // Reter a referência permite drenar novamente mesmo depois de stop limpar o codec.
        conclusao.pararEDrenar { assertNull(owner.stop()) }
        assertEquals(1, publicacoes.get())
      } finally {
        publicar.countDown()
      }
    }
  }

  @Test
  fun `onFailure chama stop durante teardown nativo sem disputar lifecycle nem esperar a si mesmo`() = runTest {
    val conclusao = ConclusaoCallbacksCodec()
    val lifecycle = Any()
    val entrou = CompletableDeferred<Unit>()
    val stopNativo = CountDownLatch(1)
    val callbackRetornou = CountDownLatch(1)
    val workerPublicando = CompletableDeferred<Unit>()
    val liberarPublicacaoWorker = CountDownLatch(1)
    val erroPublicado = AtomicInteger()
    val fila = Fila {
      entrou.complete(Unit)
      stopNativo.esperar()
      // Simula onFailure chamando HevcDecoder.stop. Lifecycle está ocupado por stop/release
      // nativo em OUTRA thread, que só progride quando este callback retorna.
      conclusao.parar {
        synchronized(lifecycle) { Unit }
        workerPublicando.complete(Unit)
        liberarPublicacaoWorker.esperar() // reportFailures da parada delegada também conta.
        erroPublicado.incrementAndGet()
      }
      callbackRetornou.countDown()
    }
    conclusao.registrar(fila.thread)
    fila.thread.start()
    try {
      entrou.await()
      var fechado = false
      val drenagem = launch {
        conclusao.pararEDrenar {
          synchronized(lifecycle) {
            stopNativo.countDown()
            callbackRetornou.esperar()
          }
        }
        fechado = true
      }
      runCurrent()
      workerPublicando.await()
      runCurrent()
      assertFalse(fechado)
      assertFalse(drenagem.isCompleted)
      liberarPublicacaoWorker.countDown()
      drenagem.join()
      fila.conferir()
      assertEquals(1, erroPublicado.get())
      assertTrue(fechado)
    } finally {
      stopNativo.countDown()
      callbackRetornou.countDown()
      liberarPublicacaoWorker.countDown()
    }
  }

  @Test
  fun `cancelar dono nao encurta drenagem nem libera recursos antes do finally do callback`() = runTest {
    val conclusao = ConclusaoCallbacksCodec()
    val entrou = CompletableDeferred<Unit>()
    val parou = CompletableDeferred<Unit>()
    val terminar = CountDownLatch(1)
    val finallyExecutado = AtomicInteger()
    val fila = Fila {
      try {
        entrou.complete(Unit)
        terminar.esperar()
      } finally {
        finallyExecutado.incrementAndGet()
      }
    }
    conclusao.registrar(fila.thread)
    fila.thread.start()
    try {
      entrou.await()
      val drenagem = launch { conclusao.pararEDrenar { parou.complete(Unit) } }
      runCurrent()
      parou.await()
      drenagem.cancel()
      runCurrent()
      assertFalse(drenagem.isCompleted)
      assertEquals(0, finallyExecutado.get())
      terminar.countDown()
      drenagem.join()
      fila.conferir()
      assertEquals(1, finallyExecutado.get())
      conclusao.pararEDrenar {} // Final owner pode aguardar novamente.
    } finally {
      terminar.countDown()
    }
  }

  @Test
  fun `stop antes da ativacao e thread que nao iniciou ainda permitem drenagem idempotente`() = runTest {
    for (registrar in listOf(false, true)) {
      val conclusao = ConclusaoCallbacksCodec()
      if (registrar) conclusao.registrar(Thread { fail("Não deve iniciar a fila") })
      val owner = CodecCallbackOwner<Any>()
      conclusao.parar { assertNull(owner.stop()) }
      repeat(2) {
        conclusao.pararEDrenar { assertNull(owner.stop()) }
      }
      assertFalse(owner.canPrepare())
    }
  }

  @Test
  fun `falha na parada delegada e propagada depois de drenar a thread`() = runTest {
    val conclusao = ConclusaoCallbacksCodec()
    val solicitouStop = CompletableDeferred<Unit>()
    val erro = IllegalStateException("observer de encerramento falhou")
    val fila = Fila {
      conclusao.parar { throw erro }
      solicitouStop.complete(Unit)
    }
    conclusao.registrar(fila.thread)
    fila.thread.start()
    solicitouStop.await()
    try {
      conclusao.pararEDrenar {}
      fail("Falha da parada delegada não pode ser suprimida")
    } catch (e: IllegalStateException) {
      // Recuperação de stacktrace das coroutines pode copiar a exceção ao cruzar await.
      assertEquals(erro.message, e.message)
    }
    fila.conferir()
  }

  @Test
  fun `drenagem direta da propria fila falha explicitamente em vez de fazer self join`() = runTest {
    val conclusao = ConclusaoCallbacksCodec()
    val rejeicoes = AtomicInteger()
    val fila = Fila {
      // Apenas no teste: reproduz caller incorreto que tenta bloquear o próprio callback.
      runBlocking {
        try {
          conclusao.pararEDrenar { fail("Não pode entrar na parada via self join") }
          fail("Deveria rejeitar drenagem da própria fila")
        } catch (e: IllegalStateException) {
          assertEquals("Callback não pode aguardar a própria conclusão", e.message)
          rejeicoes.incrementAndGet()
        }
      }
    }
    conclusao.registrar(fila.thread)
    fila.thread.start()
    conclusao.pararEDrenar {}
    fila.conferir()
    assertEquals(1, rejeicoes.get())
  }
}
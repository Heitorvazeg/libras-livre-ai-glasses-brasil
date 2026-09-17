package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CodecCallbackOwnerTest {
  @Test
  fun `somente identidade corrente e running permitem entrada saida e erro`() {
    data class FakeCodec(val id: Int)
    val owner = CodecCallbackOwner<FakeCodec>()
    val codec = FakeCodec(1)
    val outro = FakeCodec(1) // equals nao confere ownership.
    assertTrue(owner.canPrepare())
    assertTrue(owner.prepare(codec))
    assertFalse(owner.prepare(outro))
    assertNull(owner.use(codec) { "input antes de start" })
    assertFalse(owner.enableCallbacks(outro))
    assertTrue(owner.enableCallbacks(codec))
    for (callback in listOf("entrada", "saida", "erro", "formato")) {
      assertNull(owner.use(outro) { fail("Callback de outra identidade: $callback") })
      assertEquals(callback, owner.use(codec) { callback })
    }
    assertSame(codec, owner.stop())
    assertNull(owner.current())
    for (callback in listOf("entrada", "saida", "erro", "formato")) {
      assertNull(owner.use(codec) { fail("Callback obsoleto: $callback") })
    }
  }

  @Test
  fun `stop durante espera descarta callback sem obter nem devolver input buffer`() {
    val owner = CodecCallbackOwner<Any>()
    val codec = Any()
    owner.prepare(codec)
    owner.enableCallbacks(codec)
    val esperandoFrame = CountDownLatch(1)
    val concluirEspera = CountDownLatch(1)
    val acessos = AtomicInteger()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val callback = executor.submit(Callable {
        assertEquals(true, owner.use(codec) { true }) // Antes do poll.
        esperandoFrame.countDown()
        assertTrue(concluirEspera.await(5, TimeUnit.SECONDS)) // Poll bloqueado, sem lock.
        owner.use(codec) {
          acessos.incrementAndGet() // getInputBuffer/copia/queue/fallback so depois da guarda.
        }
      })
      assertTrue(esperandoFrame.await(5, TimeUnit.SECONDS))
      // Stop deve concluir ANTES de liberar a espera, nao aguardar o timeout do poll.
      assertSame(codec, executor.submit(Callable { owner.stop() }).get(5, TimeUnit.SECONDS))
      concluirEspera.countDown()
      assertNull(callback.get(5, TimeUnit.SECONDS))
      assertEquals(0, acessos.get())
    } finally {
      concluirEspera.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `stop espera acesso atomico terminar antes de entregar codec para release`() {
    val owner = CodecCallbackOwner<Any>()
    val codec = Any()
    owner.prepare(codec)
    owner.enableCallbacks(codec)
    val acessandoBuffer = CountDownLatch(1)
    val terminarAcesso = CountDownLatch(1)
    val pararSolicitado = CountDownLatch(1)
    val ordem = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val callback = executor.submit(Callable {
        owner.use(codec) {
          ordem.add("get")
          acessandoBuffer.countDown()
          assertTrue(terminarAcesso.await(5, TimeUnit.SECONDS)) // Simula operacao atomica em curso.
          ordem.add("queue")
        }
      })
      assertTrue(acessandoBuffer.await(5, TimeUnit.SECONDS))
      val stop = executor.submit(Callable {
        pararSolicitado.countDown()
        assertSame(codec, owner.stop())
        ordem.add("release")
      })
      assertTrue(pararSolicitado.await(5, TimeUnit.SECONDS))
      assertFalse(stop.isDone)
      terminarAcesso.countDown()
      callback.get(5, TimeUnit.SECONDS)
      stop.get(5, TimeUnit.SECONDS)
      assertEquals(listOf("get", "queue", "release"), ordem.toList())
    } finally {
      terminarAcesso.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `liberacao fora da guarda pode aguardar callback obsoleto sem deadlock`() {
    val owner = CodecCallbackOwner<Any>()
    val codec = Any()
    owner.prepare(codec)
    owner.enableCallbacks(codec)
    val executor = Executors.newSingleThreadExecutor()
    try {
      val retired = owner.stop()
      assertSame(codec, retired)
      // Simula stop/release nativo esperando o callback terminar: o lock ja esta livre.
      val callback = executor.submit(Callable<Boolean> {
        val resultado = owner.use(codec) {
          fail("Nao deve liberar output nem reportar erro do codec antigo")
          true
        }
        resultado == null
      })
      assertTrue(callback.get(5, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `stop e terminal inclusive antes de start e entrega ownership uma unica vez`() {
    for (preparar in listOf(false, true)) {
      val owner = CodecCallbackOwner<Any>()
      val codec = Any()
      if (preparar) owner.prepare(codec)
      assertSame(if (preparar) codec else null, owner.stop())
      assertNull(owner.stop())
      assertFalse(owner.canPrepare())
      assertFalse(owner.prepare(Any()))
      assertFalse(owner.enableCallbacks(codec))
      assertNull(owner.current())
    }
  }
}
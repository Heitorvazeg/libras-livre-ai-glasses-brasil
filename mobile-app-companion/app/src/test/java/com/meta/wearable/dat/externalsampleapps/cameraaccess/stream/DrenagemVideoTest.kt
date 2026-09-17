package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrenagemVideoTest {
  @Test
  fun `frame sincrono cancelado termina antes de limpar e abrir sucessor`() = runTest {
    val entrou = CompletableDeferred<Unit>()
    val terminou = CompletableDeferred<Unit>()
    val soltarFrame = CountDownLatch(1)
    var limpo = false
    var sucessorAberto = false
    val produtor = backgroundScope.launch(Dispatchers.Default) {
      entrou.complete(Unit)
      // Simula writeCompressedFrame/feedCompressedFrame síncronos: cancel não interrompe.
      check(soltarFrame.await(10, TimeUnit.SECONDS)) { "Teste nao liberou o frame" }
      terminou.complete(Unit)
    }
    try {
      entrou.await()
      val teardown = launch {
        drenarVideoAntesDeLimpar(produtor) {
          assertTrue(terminou.isCompleted)
          limpo = true
        }
        sucessorAberto = true
      }
      runCurrent()
      assertTrue(produtor.isCancelled)
      assertFalse(produtor.isCompleted)
      assertFalse(limpo)
      assertFalse(sucessorAberto)
      // A main/scheduler segue livre para callbacks enquanto o frame ainda possui consumidores.
      var callbackAtendido = false
      launch { callbackAtendido = true }
      runCurrent()
      assertTrue(callbackAtendido)
      soltarFrame.countDown()
      teardown.join()
      assertTrue(limpo)
      assertTrue(sucessorAberto)
    } finally {
      soltarFrame.countDown()
      produtor.join()
    }
  }

  @Test
  fun `sucessor tambem espera finalizacao suspensa do recorder`() = runTest {
    val liberarRecorder = CompletableDeferred<Unit>()
    var sucessorAberto = false
    val teardown = launch {
      drenarVideoAntesDeLimpar(null) { liberarRecorder.await() }
      sucessorAberto = true
    }
    runCurrent()
    assertFalse(sucessorAberto)
    liberarRecorder.complete(Unit)
    teardown.join()
    assertTrue(sucessorAberto)
  }

  @Test
  fun `falha antes do primeiro frame ainda permite limpeza`() = runTest {
    var limpezas = 0
    drenarVideoAntesDeLimpar(null) { limpezas++ }
    assertEquals(1, limpezas)
  }
}
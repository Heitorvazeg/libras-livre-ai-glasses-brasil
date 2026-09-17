package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DonoLeitorLandmarksTest {
  private class Fila {
    private val acoes = ArrayDeque<() -> Unit>()
    fun postar(acao: () -> Unit) { acoes.addLast(acao) }
    fun drenar() { while (acoes.isNotEmpty()) acoes.removeFirst().invoke() }
  }

  @Test
  fun `stop reentrante fecha image antes do reader e extractor e rejeita callback atrasado`() {
    val fila = Fila()
    val dono = DonoLeitorLandmarks(fila::postar)
    val ordem = mutableListOf<String>()
    var imageValida = true
    val fecharReader = {
      assertFalse("reader.close invalidaria Image em uso", imageValida)
      ordem.add("reader.close")
      Unit
    }
    fila.postar {
      dono.executar {
        try {
          ordem.add("extract")
          dono.invalidar()
          dono.fecharDepoisDosCallbacks(fecharReader)
          dono.fecharDepoisDosCallbacks(fecharReader) // Idempotente.
          fila.postar { ordem.add("extractor.close") } // Barreira final de dispose.
          assertTrue("stop fechou Image durante extração", imageValida)
          assertEquals(listOf("extract"), ordem)
        } finally {
          imageValida = false
          ordem.add("image.close")
        }
      }
    }
    fila.postar { dono.executar { fail("Callback obsoleto tentou adquirir Image") } }
    fila.drenar()
    assertEquals(listOf("extract", "image.close", "reader.close", "extractor.close"), ordem)
  }

  @Test
  fun `falha real continua visivel e finally ainda precede fechamento do reader`() {
    val fila = Fila()
    val dono = DonoLeitorLandmarks(fila::postar)
    val falha = IllegalStateException("falha real da extracao")
    var imageFechada = false
    var readerFechado = false
    val resultado = runCatching {
      dono.executar {
        try {
          dono.fecharDepoisDosCallbacks {
            assertTrue(imageFechada)
            readerFechado = true
          }
          throw falha
        } finally {
          imageFechada = true
        }
      }
    }
    assertSame(falha, resultado.exceptionOrNull())
    assertFalse(readerFechado)
    fila.drenar()
    assertTrue(readerFechado)
  }

  @Test
  fun `resultado em voo nao atravessa stop nem outra captura no mesmo reader`() {
    val filaFrames = Fila()
    val filaPublicacao = Fila()
    val antigo = DonoLeitorLandmarks(filaFrames::postar)
    val sessaoAntiga = Any()
    var sessaoAtual: Any? = sessaoAntiga
    var publicacoes = 0
    assertTrue(antigo.podePublicar(sessaoAntiga, sessaoAtual))
    filaPublicacao.postar {
      if (antigo.podePublicar(sessaoAntiga, sessaoAtual)) publicacoes++
    }
    sessaoAtual = null // endSession antes de extrair/publicar o último frame.
    filaPublicacao.drenar()
    assertEquals(0, publicacoes)
    sessaoAtual = Any() // startSession no mesmo stream.
    assertFalse(antigo.podePublicar(sessaoAntiga, sessaoAtual))
    antigo.invalidar()
    val novo = DonoLeitorLandmarks(filaFrames::postar)
    assertFalse(antigo.podePublicar(sessaoAtual!!, sessaoAtual))
    assertTrue(novo.podePublicar(sessaoAtual!!, sessaoAtual))
  }

  @Test
  fun `stop nao espera callback que precisa do lock do caller e sucessor usa mesma fila`() {
    val frames = Executors.newSingleThreadExecutor()
    val lifecycle = Executors.newSingleThreadExecutor()
    val entrou = CountDownLatch(1)
    val retomar = CountDownLatch(1)
    val lockDoCaller = Any()
    val ordem = mutableListOf<String>() // Somente a fila serial escreve.
    val antigo = DonoLeitorLandmarks { frames.execute { it() } }
    try {
      val callback = frames.submit(Callable {
        antigo.executar {
          try {
            ordem.add("extract.antigo")
            entrou.countDown()
            assertTrue(retomar.await(5, TimeUnit.SECONDS))
            synchronized(lockDoCaller) { ordem.add("onEvento") }
          } finally {
            ordem.add("image.close")
          }
        }
      })
      assertTrue(entrou.await(5, TimeUnit.SECONDS))
      lifecycle.submit(Callable {
        synchronized(lockDoCaller) {
          antigo.invalidar()
          antigo.fecharDepoisDosCallbacks { ordem.add("reader.close") }
          // Fecha sem precisar obter o lock que o callback usará em onState/onEvento.
          val novo = DonoLeitorLandmarks { frames.execute { it() } }
          frames.execute { novo.executar { ordem.add("extract.novo") } }
        }
      }).get(5, TimeUnit.SECONDS)
      retomar.countDown()
      callback.get(5, TimeUnit.SECONDS)
      frames.submit(Callable { ordem.toList() }).get(5, TimeUnit.SECONDS).let {
        assertEquals(listOf("extract.antigo", "onEvento", "image.close", "reader.close", "extract.novo"), it)
      }
    } finally {
      retomar.countDown()
      lifecycle.shutdownNow()
      frames.shutdownNow()
    }
  }
}
/*
 * Testes do tradutor PT -> glosa, contra um servidor HTTP local (sem tocar a rede publica).
 *
 * O contrato exercitado aqui e o MEDIDO em 2026-09-12 (§0.1 do plano): o corpo da resposta e
 * TEXTO PURO, nao JSON. Esse e o erro mais facil de cometer ao reimplementar isto.
 *
 * O servidor e um ServerSocket cru de proposito: com.sun.net.httpserver nao esta no classpath
 * de unit test do Android, e adicionar dependencia de teste por causa de poucos casos nao paga.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Servidor de uma requisicao por vez, suficiente para os casos aqui. [antesDeResponder] roda depois
 * de ler a requisicao e antes de responder: e como um teste segura uma traducao "em voo".
 */
private class FakeServer(val status: Int, val corpo: String, val antesDeResponder: () -> Unit = {}) {
  private val socket = ServerSocket(0)
  val porta: Int get() = socket.localPort
  @Volatile var chamadas = 0
  @Volatile var ultimoCorpo = ""

  init {
    thread(isDaemon = true) {
      while (!socket.isClosed) {
        try {
          socket.accept().use { c ->
            val entrada = c.getInputStream().bufferedReader()
            var tamanho = 0
            while (true) {
              val linha = entrada.readLine() ?: break
              if (linha.isEmpty()) break
              if (linha.startsWith("Content-Length:", true)) {
                tamanho = linha.substringAfter(":").trim().toInt()
              }
            }
            if (tamanho > 0) {
              val buf = CharArray(tamanho)
              entrada.read(buf, 0, tamanho)
              ultimoCorpo = String(buf)
            }
            chamadas++
            antesDeResponder()
            val bytes = corpo.toByteArray()
            val cabecalho = "HTTP/1.1 " + status + " X\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + bytes.size + "\r\n" +
                "Connection: close\r\n\r\n"
            c.getOutputStream().apply {
              write(cabecalho.toByteArray())
              write(bytes)
              flush()
            }
          }
        } catch (_: Exception) {
          // socket fechado no fim do teste
        }
      }
    }
  }

  val url: String get() = "http://127.0.0.1:" + porta + "/translate"

  fun fechar() = socket.close()
}

class VLibrasGlosaTranslatorTest {

  private fun cacheVazio() = GlosaCache()

  private inline fun <T> comServidor(
      status: Int,
      corpo: String,
      noinline antesDeResponder: () -> Unit = {},
      bloco: (FakeServer) -> T,
  ): T {
    val s = FakeServer(status, corpo, antesDeResponder)
    try {
      return bloco(s)
    } finally {
      s.fechar()
    }
  }

  @Test
  fun `resposta em texto puro vira glosa`() = runBlocking {
    comServidor(200, "VOCE PRECISAR MARCAR&REGISTRAR RECEPCAO") { s ->
      val t = VLibrasGlosaTranslator(cacheVazio(), s.url)
      assertEquals("VOCE PRECISAR MARCAR&REGISTRAR RECEPCAO", t.traduzir("voce precisa marcar"))
    }
  }

  @Test
  fun `manda o texto no campo text, como o app oficial`() = runBlocking {
    comServidor(200, "OI") { s ->
      VLibrasGlosaTranslator(cacheVazio(), s.url).traduzir("oi")
      assertTrue("corpo enviado foi: " + s.ultimoCorpo, s.ultimoCorpo.contains("text"))
      assertTrue(s.ultimoCorpo.contains("oi"))
    }
  }

  @Test
  fun `erro HTTP devolve null em vez de lancar`() = runBlocking {
    comServidor(500, "boom") { s ->
      assertNull(VLibrasGlosaTranslator(cacheVazio(), s.url).traduzir("qualquer coisa"))
    }
  }

  @Test
  fun `servidor inalcancavel devolve null — o setimo estado cai no fallback`() = runBlocking {
    val t = VLibrasGlosaTranslator(cacheVazio(), "http://127.0.0.1:1/translate")
    assertNull(t.traduzir("sem rede"))
  }

  @Test
  fun `servidor que aceita e nao responde estoura o teto e devolve null a tempo`() = runBlocking {
    // 9.1: antes eram 30 s de conexão + 30 s de leitura presos no ⑦.
    val mudo = ServerSocket(0)
    val aceitos = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
    thread(isDaemon = true) { runCatching { while (true) aceitos.add(mudo.accept()) } }
    try {
      val t = VLibrasGlosaTranslator(cacheVazio(), "http://127.0.0.1:" + mudo.localPort + "/translate", tetoMs = 300)
      val inicio = System.nanoTime()
      assertNull(t.traduzir("ninguem responde"))
      val ms = (System.nanoTime() - inicio) / 1_000_000
      assertTrue("levou $ms ms", ms < 1_000)
    } finally {
      mudo.close()
      aceitos.forEach { runCatching { it.close() } }
    }
  }

  @Test
  fun `segunda chamada vem do cache, sem tocar a rede`() = runBlocking {
    comServidor(200, "BANCO&DINHEIRO ESQUINA") { s ->
      val t = VLibrasGlosaTranslator(cacheVazio(), s.url)
      t.traduzir("o banco fica na esquina")
      // Caixa e espacos diferentes: o atendente repete a frase o dia inteiro sem repetir a
      // digitacao. Normalizar e o que faz o cache valer alguma coisa na pratica.
      assertEquals("BANCO&DINHEIRO ESQUINA", t.traduzir("  O BANCO fica na esquina  "))
      assertEquals("deveria ter batido no servidor uma vez so", 1, s.chamadas)
    }
  }

  @Test
  fun `texto vazio nao vira requisicao`() = runBlocking {
    comServidor(200, "X") { s ->
      assertNull(VLibrasGlosaTranslator(cacheVazio(), s.url).traduzir("   "))
      assertEquals(0, s.chamadas)
    }
  }

  @Test
  fun `resposta vazia nao entra no cache`() = runBlocking {
    val cache = cacheVazio()
    comServidor(200, "   ") { s ->
      assertNull(VLibrasGlosaTranslator(cache, s.url).traduzir("nada"))
      assertEquals(0, cache.tamanho())
    }
  }

  // --- Cache por atendimento: vale dentro dele, some no fim dele ---

  @Test
  fun `no mesmo atendimento a frase repetida nao vai a rede`() = runBlocking {
    comServidor(200, "ONDE BANHEIRO") { s ->
      val t = VLibrasGlosaTranslator(cacheVazio(), s.url)
      assertEquals("ONDE BANHEIRO", t.traduzir("onde fica o banheiro"))
      assertEquals("ONDE BANHEIRO", t.traduzir("onde fica o banheiro"))
      assertEquals(1, s.chamadas)
    }
  }

  @Test
  fun `depois do fim do atendimento a mesma frase vai a rede de novo`() = runBlocking {
    val cache = cacheVazio()
    comServidor(200, "ONDE BANHEIRO") { s ->
      val t = VLibrasGlosaTranslator(cache, s.url)
      t.traduzir("onde fica o banheiro")
      cache.limpar() // fim do atendimento (PoliticaCamera.revogarConsentimento)
      assertEquals(0, cache.tamanho())
      assertEquals("ONDE BANHEIRO", t.traduzir("onde fica o banheiro"))
      assertEquals("o atendimento novo comeca sem cache", 2, s.chamadas)
    }
  }

  @Test
  fun `resposta que chega depois do fim do atendimento nao entra no cache seguinte`() = runBlocking {
    val cache = cacheVazio()
    val chegou = CountDownLatch(1)
    val libera = CountDownLatch(1)
    comServidor(200, "SUA CONSULTA AMANHA", antesDeResponder = {
      chegou.countDown()
      libera.await(5, TimeUnit.SECONDS)
    }) { s ->
      val t = VLibrasGlosaTranslator(cache, s.url)
      val emVoo = async(Dispatchers.Default) { t.traduzir("sua consulta e amanha") }
      assertTrue("a requisicao nao chegou ao servidor", chegou.await(5, TimeUnit.SECONDS))
      // O atendimento acaba com a traducao pendente; so depois a resposta chega.
      cache.limpar()
      libera.countDown()
      // Devolver a glosa a quem pediu e permitido (quem descarta o turno e o orquestrador)...
      assertEquals("SUA CONSULTA AMANHA", emVoo.await())
      // ...mas o cache do atendimento seguinte continua vazio.
      assertEquals(0, cache.tamanho())
      assertNull(cache.obter("sua consulta e amanha"))
    }
  }
}

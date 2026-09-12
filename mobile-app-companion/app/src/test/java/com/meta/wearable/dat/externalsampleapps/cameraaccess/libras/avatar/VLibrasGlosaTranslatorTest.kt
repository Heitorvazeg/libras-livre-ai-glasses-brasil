/*
 * Testes do tradutor PT -> glosa, contra um servidor HTTP local (sem tocar a rede publica).
 *
 * O contrato exercitado aqui e o MEDIDO em 2026-09-12 (§0.1 do plano): o corpo da resposta e
 * TEXTO PURO, nao JSON. Esse e o erro mais facil de cometer ao reimplementar isto.
 *
 * O servidor e um ServerSocket cru de proposito: com.sun.net.httpserver nao esta no classpath
 * de unit test do Android, e adicionar dependencia de teste por causa de sete casos nao paga.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Servidor de uma requisicao por vez, suficiente para os casos aqui. */
private class FakeServer(val status: Int, val corpo: String) {
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

  private fun cacheVazio() = GlosaCache(File.createTempFile("glosa", ".tsv").apply { delete() })

  private inline fun <T> comServidor(status: Int, corpo: String, bloco: (FakeServer) -> T): T {
    val s = FakeServer(status, corpo)
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
}

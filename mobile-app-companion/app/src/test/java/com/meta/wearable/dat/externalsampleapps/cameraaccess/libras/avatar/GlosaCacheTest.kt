/*
 * Testes do cache de glosa (docs/vlibras-webview-plano.md §6, Fase 1).
 * JVM puro — sem emulador, sem rede.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlosaCacheTest {

  private fun tmp(): File = File.createTempFile("glosa", ".tsv").apply { delete() }

  @Test
  fun `guarda e recupera`() {
    val c = GlosaCache(tmp())
    c.guardar("bom dia", "BOM_DIA")
    assertEquals("BOM_DIA", c.obter("bom dia"))
    assertNull(c.obter("boa noite"))
  }

  @Test
  fun `persiste entre instancias — o disco e o que torna o modo sem rede util`() {
    val f = tmp()
    GlosaCache(f).guardar("onde fica o banheiro", "ONDE BANHEIRO")
    assertEquals("ONDE BANHEIRO", GlosaCache(f).obter("onde fica o banheiro"))
  }

  @Test
  fun `glosa com acento e E comercial sobrevive ao round-trip`() {
    val f = tmp()
    GlosaCache(f).apply {
      guardar("a", "VOCE PRECISAR MARCAR&REGISTRAR")
      guardar("b", "RECEPCAO")
    }
    val lido = GlosaCache(f)
    assertEquals("VOCE PRECISAR MARCAR&REGISTRAR", lido.obter("a"))
    assertEquals("RECEPCAO", lido.obter("b"))
  }

  @Test
  fun `descarta as entradas mais antigas ao estourar o limite`() {
    val c = GlosaCache(tmp(), maxEntradas = 2)
    c.guardar("um", "UM")
    c.guardar("dois", "DOIS")
    c.guardar("tres", "TRES")
    assertEquals(2, c.tamanho())
    assertNull("a mais antiga deveria ter saido", c.obter("um"))
    assertEquals("TRES", c.obter("tres"))
  }

  @Test
  fun `glosa multilinha nao corrompe o arquivo — o formato e uma linha por par`() {
    val f = tmp()
    GlosaCache(f).apply {
      guardar("a", "BOM\nDIA\tTUDO BEM")
      guardar("b", "RECEPCAO")
    }
    val lido = GlosaCache(f)
    assertEquals("BOM DIA TUDO BEM", lido.obter("a"))
    // Sem a higienizacao, a cauda da primeira glosa viraria uma linha orfa e comeria a segunda.
    assertEquals("RECEPCAO", lido.obter("b"))
  }

  @Test
  fun `arquivo corrompido nao derruba — comeca vazio`() {
    val f = tmp().apply { writeText("lixo sem separador\nmais lixo") }
    assertNull(GlosaCache(f).obter("qualquer"))
  }
}

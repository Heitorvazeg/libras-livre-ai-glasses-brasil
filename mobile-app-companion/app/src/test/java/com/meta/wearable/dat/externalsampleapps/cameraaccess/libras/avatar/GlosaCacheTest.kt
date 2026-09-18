/*
 * Testes do cache de glosa (docs/vlibras-webview-plano.md §6, Fase 1): só memória, por atendimento.
 * JVM puro — sem emulador, sem rede.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlosaCacheTest {

  private fun GlosaCache.guardar(chave: String, glosa: String) = guardar(chave, glosa, epoca())

  @Test
  fun `guarda e recupera`() {
    val c = GlosaCache()
    c.guardar("bom dia", "BOM_DIA")
    assertEquals("BOM_DIA", c.obter("bom dia"))
    assertNull(c.obter("boa noite"))
  }

  @Test
  fun `glosa com acento e E comercial volta intacta`() {
    val c = GlosaCache()
    c.guardar("a", "VOCÊ PRECISAR MARCAR&REGISTRAR")
    assertEquals("VOCÊ PRECISAR MARCAR&REGISTRAR", c.obter("a"))
  }

  @Test
  fun `glosa multilinha sai numa linha so`() {
    val c = GlosaCache()
    c.guardar("a", "BOM\nDIA\tTUDO BEM\r\n")
    assertEquals("BOM DIA TUDO BEM", c.obter("a"))
  }

  @Test
  fun `descarta as entradas mais antigas ao estourar o limite`() {
    val c = GlosaCache(maxEntradas = 2)
    c.guardar("um", "UM")
    c.guardar("dois", "DOIS")
    c.guardar("tres", "TRES")
    assertEquals(2, c.tamanho())
    assertNull("a mais antiga deveria ter saido", c.obter("um"))
    assertEquals("TRES", c.obter("tres"))
  }

  @Test
  fun `limite padrao e de 500 entradas`() {
    val c = GlosaCache()
    repeat(501) { c.guardar("frase $it", "G$it") }
    assertEquals(500, c.tamanho())
    assertNull(c.obter("frase 0"))
    assertEquals("G500", c.obter("frase 500"))
  }

  @Test
  fun `limpar deixa o cache vazio — fim do atendimento`() {
    val c = GlosaCache()
    c.guardar("bom dia", "BOM_DIA")
    c.guardar("onde fica o banheiro", "ONDE BANHEIRO")
    c.limpar()
    assertEquals(0, c.tamanho())
    assertNull(c.obter("bom dia"))
    assertNull(c.obter("onde fica o banheiro"))
  }

  @Test
  fun `guardar com epoca de antes do limpar e recusado`() {
    val c = GlosaCache()
    val epocaDoAtendimentoAnterior = c.epoca()
    c.limpar()
    assertFalse(c.guardar("frase de quem ja foi embora", "FRASE", epocaDoAtendimentoAnterior))
    assertEquals(0, c.tamanho())
    // A época nova continua valendo.
    assertTrue(c.guardar("frase nova", "NOVA", c.epoca()))
    assertEquals("NOVA", c.obter("frase nova"))
  }

  @Test
  fun `nenhuma operacao cria arquivo`() {
    // O cache antigo escrevia em vlibras/glosa-cache.tsv. Nada deste tipo pode sequer apontar
    // para disco: nenhum campo nem parâmetro de construtor do tipo File/Path.
    val tipos = GlosaCache::class.java.declaredFields.map { it.type } +
        GlosaCache::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }
    assertTrue(tipos.none { File::class.java.isAssignableFrom(it) || java.nio.file.Path::class.java.isAssignableFrom(it) })

    // E, exercitado de ponta a ponta (guardar, estourar o LRU, obter, limpar), o diretório de
    // trabalho continua igual.
    val cwd = File("").absoluteFile
    val antes = cwd.list()!!.toSet()
    val c = GlosaCache(maxEntradas = 2)
    c.guardar("um", "UM")
    c.guardar("dois", "DOIS")
    c.guardar("tres", "TRES")
    c.obter("dois")
    c.tamanho()
    c.limpar()
    c.guardar("quatro", "QUATRO")
    assertEquals(antes, cwd.list()!!.toSet())
    assertFalse(File(cwd, "vlibras").exists())
  }
}

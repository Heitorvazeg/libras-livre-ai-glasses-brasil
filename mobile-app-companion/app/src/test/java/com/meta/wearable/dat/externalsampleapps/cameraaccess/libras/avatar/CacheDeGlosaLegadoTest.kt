/*
 * Migração: o TSV que as versões antigas do GlosaCache gravavam em filesDir some na inicialização.
 * JVM puro — um diretório temporário faz o papel de filesDir.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheDeGlosaLegadoTest {

  private val filesDir: File = Files.createTempDirectory("filesDir").toFile()

  @After fun limpar() {
    filesDir.deleteRecursively()
  }

  private fun tsvLegado() =
      File(filesDir, "vlibras/glosa-cache.tsv").apply {
        parentFile!!.mkdirs()
        writeText("onde fica o banheiro\tONDE BANHEIRO\nsua consulta e amanha\tCONSULTA AMANHA")
      }

  @Test
  fun `apaga o TSV legado e a pasta que ficou vazia`() {
    val tsv = tsvLegado()
    apagarCacheDeGlosaLegado(filesDir)
    assertFalse(tsv.exists())
    assertFalse(File(filesDir, "vlibras").exists())
  }

  @Test
  fun `preserva a pasta se houver outra coisa nela`() {
    val tsv = tsvLegado()
    val outro = File(filesDir, "vlibras/outro.bin").apply { writeText("x") }
    apagarCacheDeGlosaLegado(filesDir)
    assertFalse(tsv.exists())
    assertTrue(outro.exists())
  }

  @Test
  fun `sem arquivo legado nao faz nada e nao falha`() {
    apagarCacheDeGlosaLegado(filesDir)
    apagarCacheDeGlosaLegado(filesDir)
    assertTrue(filesDir.exists())
    assertFalse(File(filesDir, "vlibras").exists())
  }
}

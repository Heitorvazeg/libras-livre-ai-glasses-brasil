/*
 * Cópia de assets à prova de interrupção (docs/prontidao-demo/05-audio.md §5.6).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopiaDeAssetsTest {

  private val arvore =
      mapOf(
          "modelo" to arrayOf("am", "conf", "final.mdl"),
          "modelo/am" to arrayOf("a.bin", "b.bin"),
          "modelo/conf" to arrayOf("mfcc.conf"),
      )
  private val conteudo =
      mapOf(
          "modelo/am/a.bin" to "A",
          "modelo/am/b.bin" to "B",
          "modelo/conf/mfcc.conf" to "C",
          "modelo/final.mdl" to "M",
      )

  private fun listar(caminho: String): Array<String>? = arvore[caminho]

  @Test
  fun `copia interrompida seguida de nova chamada termina com todos os arquivos e o marcador`() {
    val raiz = Files.createTempDirectory("assets").toFile()
    var aberturas = 0
    val quebraNoTerceiro = { caminho: String ->
      if (++aberturas == 3) throw IOException("disco cheio")
      conteudo.getValue(caminho).byteInputStream()
    }
    runCatching { CopiaDeAssets.garantir("modelo", raiz, ::listar, quebraNoTerceiro) }
    val pasta = File(raiz, "modelo")
    assertFalse("sem marcador depois da falha", File(pasta, CopiaDeAssets.MARCADOR).exists())

    CopiaDeAssets.garantir("modelo", raiz, ::listar) { conteudo.getValue(it).byteInputStream() }
    assertTrue(File(pasta, CopiaDeAssets.MARCADOR).isFile)
    for ((caminho, texto) in conteudo) assertEquals(texto, File(raiz, caminho).readText())
  }

  @Test
  fun `pasta antiga sem marcador e apagada e copiada de novo`() {
    val raiz = Files.createTempDirectory("assets").toFile()
    val lixo = File(raiz, "modelo/am/restante-de-copia-antiga.bin").apply { parentFile.mkdirs(); writeText("x") }
    CopiaDeAssets.garantir("modelo", raiz, ::listar) { conteudo.getValue(it).byteInputStream() }
    assertFalse(lixo.exists())
    assertTrue(File(raiz, "modelo/final.mdl").isFile)
  }

  @Test
  fun `com marcador nada e copiado de novo`() {
    val raiz = Files.createTempDirectory("assets").toFile()
    CopiaDeAssets.garantir("modelo", raiz, ::listar) { conteudo.getValue(it).byteInputStream() }
    var aberturas = 0
    CopiaDeAssets.garantir("modelo", raiz, ::listar) { aberturas++; conteudo.getValue(it).byteInputStream() }
    assertEquals(0, aberturas)
  }
}

/*
 * Guarda executável de docs/contextualizacao-implementacao.md §11: o .tflite e as duas tabelas
 * que ele exige (glosa_ids.json, destokenizar.json) são versionados juntos em assets/, e o
 * carimbo modelo_contextualizacao.proveniencia.json amarra os três por sha256.
 *
 * Por que isso precisa ser um teste, e não uma convenção: se o modelo for reexportado e as
 * tabelas não (ou o contrário), nada falha em runtime — o modelo produz ids, a tabela traduz
 * errado e sai português plausível e incorreto. Aqui, trocar um arquivo sem atualizar o
 * carimbo quebra o build de testes.
 *
 * Roda na JVM: o diretório de trabalho dos testes de unidade é o módulo app/, então os assets
 * são lidos direto de src/main/assets/.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeloContextualizacaoProvenienciaTest {

  private val assets = File("src/main/assets")
  private val carimbo by lazy {
    JSONObject(File(assets, "modelo_contextualizacao.proveniencia.json").readText())
  }

  @Test
  fun `o modelo versionado existe e bate com o carimbo`() {
    val modelo = File(assets, carimbo.getString("modelo"))
    assertTrue(
        "${modelo.path} ausente — o modelo de contextualização é versionado no git, " +
            "não baixado (ver mobile-app-companion/README.md §2.3)",
        modelo.isFile,
    )
    assertEquals("tamanho do .tflite", carimbo.getLong("tflite_bytes"), modelo.length())
    assertEquals(
        "sha256 do .tflite diverge do carimbo — reexportou o modelo sem atualizar " +
            "modelo_contextualizacao.proveniencia.json?",
        carimbo.getString("tflite_sha256"),
        sha256(modelo),
    )
  }

  @Test
  fun `as tabelas de contrato batem com o carimbo`() {
    assertEquals(
        "glosa_ids.json diverge do modelo carimbado",
        carimbo.getString("glosa_ids_sha256"),
        sha256(File(assets, "glosa_ids.json")),
    )
    assertEquals(
        "destokenizar.json diverge do modelo carimbado",
        carimbo.getString("destokenizar_sha256"),
        sha256(File(assets, "destokenizar.json")),
    )
  }

  @Test
  fun `as constantes do Kotlin batem com o contrato do export`() {
    val contrato = carimbo.getJSONObject("contrato")
    assertEquals(contrato.getInt("S_ENC"), TfliteGlossContextualizer.S_ENC)
    assertEquals(contrato.getInt("T_DEC"), TfliteGlossContextualizer.T_DEC)
    assertEquals(contrato.getInt("D_MODEL"), TfliteGlossContextualizer.D_MODEL)
  }

  private fun sha256(arquivo: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    arquivo.inputStream().use { entrada ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val lidos = entrada.read(buffer)
        if (lidos < 0) break
        digest.update(buffer, 0, lidos)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

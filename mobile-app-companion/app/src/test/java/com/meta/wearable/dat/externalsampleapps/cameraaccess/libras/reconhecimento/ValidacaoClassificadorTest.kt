/*
 * Contrato do classificador de sinais (docs/prontidao-demo/02-classificador.md §2.6).
 *
 * O sidecar abaixo tem a forma exata que o `exportar.py` grava (conferido contra o export
 * `--smoke --arquitetura gcn`); os casos adulteram um campo por vez.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

class ValidacaoClassificadorTest {

  private val poseApp = listOf(0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24)
  private val nomes =
      listOf("nariz", "olho_esq", "olho_dir", "orelha_esq", "orelha_dir", "boca_esq", "boca_dir", "ombro_esq",
          "ombro_dir", "cotovelo_esq", "cotovelo_dir", "pulso_esq", "pulso_dir", "quadril_esq", "quadril_dir")

  private fun sidecarJson(
      sha: String = "ab".repeat(32),
      shape: List<Int> = listOf(1, 96, 57, 3),
      pose: List<Int>? = poseApp,
      rotulos: Int = 20,
      calibracao: String? = null,
  ): String {
    val poseJson = pose?.let { p -> p.mapIndexed { i, idx -> """{"nome":"${nomes.getOrElse(i) { "p$i" }}","indice_mediapipe_pose":$idx}""" }.joinToString(",", "[", "]") } ?: "null"
    return """
      {"schema":1,"modelo":"sinal_classifier.tflite","sha256":"$sha",
       "rotulos":${List(rotulos) { "\"classe%02d\"".format(it) }.joinToString(",", "[", "]")},
       "modo":"landmarks",
       "contrato_entrada":{"shape":${shape.joinToString(",", "[", "]")},"dtype":"float32",
         "layout_landmarks":{"pontos":57,"dimensoes":3,"coordenadas":["x","y","z"],"pose_ordenada":$poseJson},
         "frames_fixos":${shape.getOrElse(1) { 96 }},"imputacao_embutida":true}
       ${calibracao?.let { ",\"calibracao\":$it" } ?: ""}}
    """.trimIndent()
  }

  private val modeloBom = InterfaceModelo("ab".repeat(32), listOf(1, 96, 57, 3), "float32", 20)

  private fun motivos(json: String, modelo: InterfaceModelo = modeloBom) =
      ValidacaoClassificador.motivosDeRecusa(SidecarClassificador.ler(json), modelo)

  @Test
  fun `sidecar do export smoke e aceito`() {
    val sidecar = SidecarClassificador.ler(sidecarJson())
    assertEquals(96, sidecar.frames)
    assertEquals(3, sidecar.dimensoes)
    assertEquals(1f, sidecar.temperatura)
    assertTrue(sidecar.imputacaoEmbutida)
    assertEquals(emptyList<String>(), ValidacaoClassificador.motivosDeRecusa(sidecar, modeloBom))
  }

  @Test
  fun `sha256 adulterado recusa`() {
    assertTrue(motivos(sidecarJson(sha = "cd".repeat(32))).single().contains("sha256"))
  }

  @Test
  fun `ordem da pose diferente do app recusa`() {
    val trocada = poseApp.toMutableList().apply { this[11] = 16; this[12] = 15 }
    assertTrue(motivos(sidecarJson(pose = trocada)).single().contains("ordem da pose"))
    assertTrue(motivos(sidecarJson(pose = null)).single().contains("sem a ordem da pose"))
  }

  @Test
  fun `numero de rotulos diferente da saida recusa`() {
    assertTrue(motivos(sidecarJson(rotulos = 19)).single().contains("rótulos"))
  }

  @Test
  fun `shape e dimensoes fora do que o app entrega recusam`() {
    val quatroCoords = motivos(sidecarJson(shape = listOf(1, 96, 57, 4)), modeloBom.copy(shapeEntrada = listOf(1, 96, 57, 4)))
    assertTrue(quatroCoords.any { it.contains("coordenadas") })
    val outrosPontos = motivos(sidecarJson(shape = listOf(1, 96, 49, 2)), modeloBom.copy(shapeEntrada = listOf(1, 96, 49, 2)))
    assertTrue(outrosPontos.any { it.contains("pontos") })
    assertTrue(motivos(sidecarJson(), modeloBom.copy(shapeEntrada = listOf(1, 64, 57, 3))).any { it.contains("diverge do modelo") })
  }

  @Test
  fun `temperatura vem da calibracao do sidecar`() {
    assertEquals(1.8f, SidecarClassificador.ler(sidecarJson(calibracao = """{"temperatura":1.8}""")).temperatura, 1e-6f)
  }

  @Test
  fun `softmax com temperatura e margem entre os dois primeiros`() {
    val p = Probabilidades.softmax(floatArrayOf(2f, 1f, 0f))
    assertEquals(1f, p.sum(), 1e-5f)
    assertEquals(0.66524f, p[0], 1e-4f)
    val top = Probabilidades.top2(p)
    assertEquals(0, top.indice)
    assertEquals(0.66524f - 0.24473f, top.margem, 1e-4f)
    // Temperatura > 1 achata a distribuição.
    assertTrue(Probabilidades.softmax(floatArrayOf(2f, 1f, 0f), temperatura = 2f)[0] < p[0])
    // Estável com logits grandes.
    assertEquals(1f, Probabilidades.softmax(floatArrayOf(1000f, 0f))[0], 1e-6f)
  }

  @Test
  fun `rotulos vazios duplicados e frame count incompativel recusam`() {
    for (rotulos in listOf(listOf("", "b"), listOf("a", "a"), listOf("a"))) {
      val json = JSONObject(sidecarJson()).put("rotulos", org.json.JSONArray(rotulos)).toString()
      assertTrue(motivos(json).any { it.contains("rótulos") })
    }
    assertTrue(motivos(sidecarJson(shape = listOf(1, 64, 57, 3)),
        modeloBom.copy(shapeEntrada = listOf(1, 64, 57, 3))).any { it.contains("contrato do app") })
  }

  @Test
  fun `calibracao presente exige temperatura valida`() {
    for (bloco in listOf("null", "{}", "[]")) {
      assertThrows(org.json.JSONException::class.java) { SidecarClassificador.ler(sidecarJson(calibracao = bloco)) }
    }
    for (valor in listOf("0", "-1", "1e100")) {
      assertTrue(motivos(sidecarJson(calibracao = """{"temperatura":$valor}""")).any { it.contains("temperatura") })
    }
  }

  @Test
  fun `probabilidades rejeitam dados nao finitos e invalidos`() {
    for (t in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
      assertThrows(IllegalArgumentException::class.java) { Probabilidades.softmax(floatArrayOf(1f, 2f), t) }
    }
    for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
      assertThrows(IllegalArgumentException::class.java) { Probabilidades.softmax(floatArrayOf(1f, v)) }
      assertThrows(IllegalArgumentException::class.java) { Probabilidades.top2(floatArrayOf(1f, v)) }
    }
    assertThrows(IllegalArgumentException::class.java) { Probabilidades.softmax(floatArrayOf()) }
    assertThrows(IllegalArgumentException::class.java) { Probabilidades.top2(floatArrayOf(-0.1f, 1.1f)) }
    val extremos = Probabilidades.softmax(floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE), Float.MIN_VALUE)
    assertEquals(1f, extremos.sum(), 0f)
  }

  /**
   * Todo rótulo do modelo real tem de existir no léxico da contextualização, ou estar na lista
   * explícita dos que não são falados (2.5). Só roda quando o modelo estiver nos assets.
   */
  @Test
  fun `rotulos do modelo nos assets existem no lexico ou estao na lista de nao falados`() {
    val sidecar = File("src/main/assets/sinal_classifier.json")
    assumeTrue("sinal_classifier.json ainda não está nos assets", sidecar.isFile)
    val naoFalados = setOf("maca") // MINDS `maca`; no léxico é `maçã` (2.5)
    val lexico = JSONObject(File("src/main/assets/lexico-glosas.json").readText()).getJSONObject("glosas").keys().asSequence().toSet()
    val foraDoLexico = SidecarClassificador.ler(sidecar.readText()).rotulos.filter { it !in lexico && it !in naoFalados }
    assertEquals("rótulos fora do léxico e fora da lista de não falados", emptyList<String>(), foraDoLexico)
  }
}

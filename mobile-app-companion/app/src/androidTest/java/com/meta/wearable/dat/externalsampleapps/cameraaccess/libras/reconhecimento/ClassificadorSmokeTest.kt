/*
 * O classificador .tflite pelo contrato do sidecar (docs/prontidao-demo/02-classificador.md §2.6 e §2.7).
 *
 * Usa o export `--smoke --arquitetura gcn` (pesos aleatórios, mesmo contrato de entrada da
 * configuração de entrega), gerado junto com o fixture de paridade por
 * `scripts/fixture_paridade_classificador.py` e guardado nos assets de TESTE, não nos do app. Quando
 * o checkpoint real chegar, o mesmo script com ele regenera o fixture e estes testes voltam a valer.
 *
 * Instrumentado: o LiteRT não roda na JVM.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassificadorSmokeTest {

  private val assets = InstrumentationRegistry.getInstrumentation().context.assets
  private val nome = "smoke_sinal_classifier"

  private val sidecar by lazy { assets.open("$nome.json").bufferedReader().use { it.readText() } }
  private val modelo by lazy { assets.open("$nome.tflite").use { it.readBytes() } }
  private val fixture by lazy { JSONObject(assets.open("paridade_classificador.json").bufferedReader().use { it.readText() }) }

  private fun frames3d(a: JSONArray): List<Array<FloatArray>> =
      List(a.length()) { t -> a.getJSONArray(t).let { f -> Array(f.length()) { p -> f.getJSONArray(p).let { v -> FloatArray(v.length()) { c -> v.getDouble(c).toFloat() } } } } }

  private fun pontos(a: JSONArray): List<FloatArray> =
      List(a.length()) { i -> a.getJSONArray(i).let { p -> FloatArray(p.length()) { c -> p.getDouble(c).toFloat() } } }

  @Test
  fun modeloSmokeCarregaPeloSidecarEClassifica() {
    val classificador = TfliteSignClassifier(sidecar, modelo)
    try {
      val seq = fixture.getJSONArray("sequencias").getJSONObject(0)
      val frames = frames3d(seq.getJSONArray("imputados_reamostrados"))
      val c = classificador.classify(SegmentoSinal(frames, LongArray(frames.size) { it.toLong() }))
      assertTrue(c.glosa in classificador.sidecar.rotulos)
      assertTrue(c.confianca in 0f..1f)
      assertTrue(c.margem in 0f..c.confianca)
    } finally {
      classificador.close()
    }
  }

  @Test
  fun sidecarAdulteradoERecusado() {
    val adulterado = JSONObject(sidecar).put("sha256", "0".repeat(64)).toString()
    try {
      TfliteSignClassifier(adulterado, modelo).close()
      fail("um sidecar com sha256 errado foi aceito")
    } catch (e: ModeloRecusado) {
      assertTrue(e.motivos.any { it.contains("sha256") })
    }
    val poseTrocada =
        JSONObject(sidecar).apply {
          val pose = getJSONObject("contrato_entrada").getJSONObject("layout_landmarks").getJSONArray("pose_ordenada")
          val esq = pose.getJSONObject(11)
          pose.put(11, pose.getJSONObject(12))
          pose.put(12, esq)
        }.toString()
    try {
      TfliteSignClassifier(poseTrocada, modelo).close()
      fail("um sidecar com a ordem da pose trocada foi aceito")
    } catch (e: ModeloRecusado) {
      assertTrue(e.motivos.any { it.contains("ordem da pose") })
    }
  }

  @Test
  fun tfliteReproduzOsLogitsDoPytorchSobreAEntradaDoApp() {
    val classificador = TfliteSignClassifier(sidecar, modelo)
    try {
      val sequencias = fixture.getJSONArray("sequencias")
      for (s in 0 until sequencias.length()) {
        val seq = sequencias.getJSONObject(s)
        val frames = frames3d(seq.getJSONArray("imputados_reamostrados"))
        // Já estão nos frames do contrato: timestamps uniformes tornam a reamostragem a identidade.
        val obtido = classificador.logits(SegmentoSinal(frames, LongArray(frames.size) { it.toLong() }))
        val esperado = seq.getJSONArray("logits_app_pytorch")
        var pior = 0f
        for (i in 0 until esperado.length()) pior = maxOf(pior, kotlin.math.abs(esperado.getDouble(i).toFloat() - obtido[i]))
        assertTrue("sequência $s: maior diferença de logit $pior", pior <= 2e-3f)
      }
    } finally {
      classificador.close()
    }
  }

  /**
   * Dos landmarks crus até os logits, pelo código do app: normalização, imputação, reamostragem pelo
   * tempo e .tflite. Tem de bater com os logits do PyTorch sobre o mesmo caminho feito em Python.
   *
   * O top-1 contra o caminho do TREINO só é conferido quando a margem do treino é folgada: com o
   * `--smoke`, o modelo de pesos aleatórios responde sempre a mesma classe, com margem ~0,01 — um
   * top-1 igual ali não provaria nada. Com o checkpoint real, a checagem passa a valer.
   */
  @Test
  fun caminhoInteiroDoAppReproduzOPytorchEOTop1DoTreino() {
    val classificador = TfliteSignClassifier(sidecar, modelo)
    val margemMinima = fixture.getDouble("margem_minima_top1")
    try {
      val sequencias = fixture.getJSONArray("sequencias")
      for (s in 0 until sequencias.length()) {
        val seq = sequencias.getJSONObject(s)
        val ts = seq.getJSONArray("ts_ms").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val pose = seq.getJSONArray("pose")
        val esq = seq.getJSONArray("mao_esq")
        val dir = seq.getJSONArray("mao_dir")
        val imputador = HandGapImputer()
        for (t in ts.indices) {
          val frame =
              FrameLandmarks(
                  pontos(pose.getJSONArray(t)),
                  if (esq.isNull(t)) null else pontos(esq.getJSONArray(t)),
                  if (dir.isNull(t)) null else pontos(dir.getJSONArray(t)),
              )
          imputador.offer(LandmarkNormalizer.normalize(frame, seq.getInt("largura"), seq.getInt("altura"))!!)
        }
        val logits = classificador.logits(SegmentoSinal(imputador.snapshot(), ts))
        val esperado = seq.getJSONArray("logits_app_pytorch")
        var pior = 0f
        for (i in 0 until esperado.length()) pior = maxOf(pior, kotlin.math.abs(esperado.getDouble(i).toFloat() - logits[i]))
        assertTrue("sequência $s: caminho do app diverge do Python em $pior logit", pior <= 2e-3f)

        val top1 = logits.indices.maxBy { logits[it] }
        if (seq.getDouble("margem_treino") >= margemMinima) {
          assertEquals("sequência $s: top-1 do app × do treino", seq.getInt("top1_treino"), top1)
        } else {
          android.util.Log.i("ClassificadorSmokeTest",
              "sequência $s: margem do treino ${seq.getDouble("margem_treino")} < $margemMinima, top-1 não conferido (modelo sem treino)")
        }
      }
    } finally {
      classificador.close()
    }
  }
}

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Modelo do APK PRINCIPAL. Fixtures só fornecem entradas/referências, nunca os pesos carregados. */
@RunWith(AndroidJUnit4::class)
class ClassificadorPrivadoAppTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val assets = instrumentation.targetContext.assets

  private fun carregar(): CarregamentoClassificador {
    assumeTrue("Teste exige build privado explícito", BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO)
    val r = FabricaClassificadorApp.carregar(assets) { error("Privado não pode simular") }
    assertEquals(r.motivo, ModoClassificador.REAL_EXPERIMENTAL, r.modo)
    return r
  }

  private fun pontos(a: JSONArray): List<FloatArray> = List(a.length()) { i ->
    a.getJSONArray(i).let { p -> FloatArray(p.length()) { p.getDouble(it).toFloat() } }
  }

  @Test fun pacoteDoAlvoTemIdentidadeFixadaELabelsConhecidas() {
    val r = carregar()
    try {
      val id = assets.open(CarregadorClassificador.IDENTIDADE).use { it.readBytes() }
      assertEquals(BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256, CarregadorClassificador.sha256(id))
      val lexico = JSONObject(assets.open("lexico-glosas.json").bufferedReader().use { it.readText() })
          .getJSONObject("glosas").keys().asSequence().toSet()
      val real = r.classificador as TfliteSignClassifier
      assertEquals(20, real.sidecar.rotulos.size)
      assertEquals(emptyList<String>(), real.sidecar.rotulos.filter { it !in lexico && it != "maca" })
      real.aquecer()
    } finally { r.classificador.close() }
  }

  @Test fun caminhoDeLandmarksNoAlvoReproduzReferenciaPrivada() {
    val r = carregar()
    try {
      val fixture = JSONObject(instrumentation.context.assets.open("paridade_classificador.json")
          .bufferedReader().use { it.readText() })
      // Vincula a referência ao export efetivamente empacotado no alvo.
      val nome = fixture.getString("modelo").removeSuffix(".tflite")
      val referencia = JSONObject(instrumentation.context.assets.open("$nome.json").bufferedReader().use { it.readText() })
      assertEquals(r.identidade!!.modeloSha256, referencia.getString("sha256"))
      assertEquals(r.identidade.checkpointSha256, referencia.getJSONObject("origem").getString("sha256"))
      val sequencias = fixture.getJSONArray("sequencias")
      assertTrue(sequencias.length() > 0)
      for (s in 0 until sequencias.length()) {
        val seq = sequencias.getJSONObject(s)
        val ts = seq.getJSONArray("ts_ms").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val pose = seq.getJSONArray("pose")
        val esq = seq.getJSONArray("mao_esq")
        val dir = seq.getJSONArray("mao_dir")
        val imputador = HandGapImputer()
        for (t in ts.indices) {
          val frame = FrameLandmarks(pontos(pose.getJSONArray(t)),
              if (esq.isNull(t)) null else pontos(esq.getJSONArray(t)),
              if (dir.isNull(t)) null else pontos(dir.getJSONArray(t)))
          imputador.offer(LandmarkNormalizer.normalize(frame, seq.getInt("largura"), seq.getInt("altura"))!!)
        }
        val logits = (r.classificador as TfliteSignClassifier).logits(SegmentoSinal(imputador.snapshot(), ts))
        val esperado = seq.getJSONArray("logits_app_pytorch")
        assertEquals(esperado.length(), logits.size)
        for (i in logits.indices) assertEquals("seq=$s classe=$i", esperado.getDouble(i).toFloat(), logits[i], 2e-3f)
      }
    } finally { r.classificador.close() }
  }

  @Test fun inferenciasConcorrentesEEncerramentoSaoSerializados() {
    val r = carregar()
    val real = r.classificador as TfliteSignClassifier
    val segmento = SegmentoSinal(List(2) { Array(57) { FloatArray(3) } }, longArrayOf(0, 1))
    val pool = Executors.newFixedThreadPool(2)
    try {
      val esperado = real.logits(segmento)
      val futures = pool.invokeAll(List(4) { Callable { real.logits(segmento) } }, 30, TimeUnit.SECONDS)
      futures.forEach { assertArrayEquals(esperado, it.get(), 1e-6f) }
      val emVoo = pool.submit(Callable {
        try { real.logits(segmento); "executou" }
        catch (e: IllegalStateException) { assertEquals("Classificador de sinais já fechado", e.message); "fechado" }
      })
      real.close()
      assertTrue(emVoo.get(30, TimeUnit.SECONDS) in listOf("executou", "fechado"))
      real.close()
      assertThrows(IllegalStateException::class.java) { real.logits(segmento) }
    } finally { pool.shutdownNow(); real.close() }
  }
}
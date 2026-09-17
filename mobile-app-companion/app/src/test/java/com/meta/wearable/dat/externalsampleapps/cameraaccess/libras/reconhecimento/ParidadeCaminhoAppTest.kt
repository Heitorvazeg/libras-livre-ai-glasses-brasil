/*
 * Paridade do caminho do app com o código do treino (docs/prontidao-demo/02-classificador.md §2.7).
 *
 * O fixture `androidTest/assets/paridade_classificador.json` sai de
 * `scripts/fixture_paridade_classificador.py`, que passa landmarks sintéticos (timestamps
 * irregulares, frames descartados, lacunas de mão) pelo código Python de verdade:
 * `PoC/src/extract.py:frame_normalizado`, `treino/dados.py:imputar_maos` e `np.interp` pelo tempo.
 * Aqui o LandmarkNormalizer, o HandGapImputer e a ReamostragemTemporal têm de reproduzir cada etapa.
 * A parte do .tflite fica no teste instrumentado ClassificadorSmokeTest.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParidadeCaminhoAppTest {

  private val fixture =
    File(
      System.getProperty(
        "librasLivre.paridadeClassificadorJson",
        "src/androidTest/assets/paridade_classificador.json",
      )
    )

  private fun pontos(a: JSONArray): List<FloatArray> =
      List(a.length()) { i -> a.getJSONArray(i).let { p -> FloatArray(p.length()) { c -> p.getDouble(c).toFloat() } } }

  private fun frames3d(a: JSONArray): List<Array<FloatArray>> =
      List(a.length()) { t -> a.getJSONArray(t).let { f -> Array(f.length()) { p -> f.getJSONArray(p).let { v -> FloatArray(v.length()) { c -> v.getDouble(c).toFloat() } } } } }

  private fun maoOuNull(a: JSONArray, t: Int): List<FloatArray>? = if (a.isNull(t)) null else pontos(a.getJSONArray(t))

  private fun comparar(rotulo: String, esperado: List<Array<FloatArray>>, obtido: List<Array<FloatArray>>, tol: Float) {
    assertEquals("$rotulo: frames", esperado.size, obtido.size)
    var pior = 0f
    for (t in esperado.indices) for (p in esperado[t].indices) for (c in esperado[t][p].indices) {
      pior = maxOf(pior, kotlin.math.abs(esperado[t][p][c] - obtido[t][p][c]))
    }
    assertTrue("$rotulo: maior diferença $pior > $tol", pior <= tol)
  }

  @Test
  fun `normalizacao, imputacao e reamostragem pelo tempo reproduzem o Python`() {
    assertTrue("${fixture.path} ausente — rode scripts/fixture_paridade_classificador.py", fixture.isFile)
    val raiz = JSONObject(fixture.readText())
    val frames = raiz.getInt("frames")
    val sequencias = raiz.getJSONArray("sequencias")
    assertTrue(sequencias.length() > 0)

    for (s in 0 until sequencias.length()) {
      val seq = sequencias.getJSONObject(s)
      val largura = seq.getInt("largura")
      val altura = seq.getInt("altura")
      val ts = seq.getJSONArray("ts_ms").let { a -> LongArray(a.length()) { a.getLong(it) } }
      val pose = seq.getJSONArray("pose")
      val maoEsq = seq.getJSONArray("mao_esq")
      val maoDir = seq.getJSONArray("mao_dir")

      // 1 -> 2: normalização.
      val normalizados =
          List(ts.size) { t ->
            LandmarkNormalizer.normalize(FrameLandmarks(pontos(pose.getJSONArray(t)), maoOuNull(maoEsq, t), maoOuNull(maoDir, t)), largura, altura)
                ?: error("sequência $s, frame $t: normalizador descartou um frame que o Python aceitou")
          }
      comparar("sequência $s, normalizados", frames3d(seq.getJSONArray("normalizados")), normalizados, 2e-4f)

      // 2 -> 3: imputação na linha do tempo real e reamostragem pelo tempo para o contrato.
      val imputador = HandGapImputer(lacunaMaxima = raiz.getInt("lacuna_maxima"))
      normalizados.forEach { imputador.offer(it) }
      val reamostrados = ReamostragemTemporal.reamostrar(imputador.snapshot(), ts, frames).toList()
      comparar("sequência $s, imputados e reamostrados", frames3d(seq.getJSONArray("imputados_reamostrados")), reamostrados, 2e-4f)
    }
  }
}

/*
 * Libras Livre — cliente HTTP da API de validação (PoC/api/server.py).
 *
 * Envia landmarks CRUS (as coordenadas 0..1 como saem do MediaPipe) + a resolução
 * do frame; TODA a normalização acontece no servidor, chamando o mesmo
 * extract.frame_normalizado que gerou os .npy de referência. Isso elimina o maior
 * risco do caminho por API — a normalização no app divergir da que a PoC mediu.
 *
 * Transporte propositalmente sem dependência nova: HttpURLConnection + org.json,
 * ambos já no Android. A carga é pequena (poucos KB: só pontos, nunca vídeo).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Landmarks crus de um frame, nas coordenadas normalizadas do MediaPipe (0..1). */
data class FrameLandmarks(
    val pose: List<FloatArray>, // 33 pontos [x, y, z, visibility]
    val leftHand: List<FloatArray>?, // 21 pontos [x, y, z] ou null se não detectada
    val rightHand: List<FloatArray>?, // idem
)

/** Resposta de /classify (o subconjunto que o app usa). */
data class ClassifyResult(
    val sinal: String,
    val distancia: Double,
    val clipeVizinho: String,
    val framesUsados: Int,
    val framesDescartados: Int,
    val topk: List<Pair<String, Double>>,
)

/** Erro de negócio da API (payload inválido, sem referências, etc.) — já com mensagem legível. */
class LandmarkApiException(message: String, val statusCode: Int? = null) : Exception(message)

class LandmarkApi(private val baseUrl: String) {

  companion object {
    private const val TAG = "Libras:LandmarkApi"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000 // DTW 1-NN contra centenas de referências leva segundos
  }

  /**
   * POST /classify — bloqueante; chame de um dispatcher de IO.
   *
   * @param width largura do frame em pixels (o servidor multiplica x·W para voltar a pixel)
   * @param height altura do frame em pixels
   * @param frames a sequência do sinal já segmentado pelo app
   */
  fun classify(width: Int, height: Int, frames: List<FrameLandmarks>): ClassifyResult {
    val body = buildPayload(width, height, frames).toString()
    val url = URL("${baseUrl.trimEnd('/')}/classify")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
    }
    try {
      conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      val text =
          (if (code in 200..299) conn.inputStream else conn.errorStream)
              ?.bufferedReader()
              ?.use(BufferedReader::readText)
              .orEmpty()
      if (code !in 200..299) {
        throw LandmarkApiException(mensagemDeErro(code, text), code)
      }
      return parseResult(JSONObject(text))
    } catch (e: LandmarkApiException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Falha ao chamar $url: ${e.message}", e)
      throw LandmarkApiException(
          "Não consegui falar com a API em $baseUrl — confira se o servidor está no ar e o host " +
              "(emulador usa 10.0.2.2). Detalhe: ${e.message}")
    } finally {
      conn.disconnect()
    }
  }

  /** GET /health — devolve o nº de clipes de referência, ou lança se não alcançar o servidor. */
  fun health(): Int {
    val url = URL("${baseUrl.trimEnd('/')}/health")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = CONNECT_TIMEOUT_MS
    }
    try {
      val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
      return JSONObject(text).optInt("clipes_referencia", 0)
    } finally {
      conn.disconnect()
    }
  }

  private fun buildPayload(width: Int, height: Int, frames: List<FrameLandmarks>): JSONObject {
    val framesJson = JSONArray()
    for (fr in frames) {
      framesJson.put(
          JSONObject().apply {
            put("pose", pontosParaJson(fr.pose))
            put("left_hand", if (fr.leftHand != null) pontosParaJson(fr.leftHand) else JSONObject.NULL)
            put("right_hand", if (fr.rightHand != null) pontosParaJson(fr.rightHand) else JSONObject.NULL)
          })
    }
    return JSONObject().apply {
      put("width", width)
      put("height", height)
      put("frames", framesJson)
    }
  }

  private fun pontosParaJson(pontos: List<FloatArray>): JSONArray {
    val arr = JSONArray()
    for (p in pontos) {
      val ponto = JSONArray()
      for (v in p) ponto.put(v.toDouble())
      arr.put(ponto)
    }
    return arr
  }

  private fun parseResult(json: JSONObject): ClassifyResult {
    val topk = mutableListOf<Pair<String, Double>>()
    json.optJSONArray("topk")?.let { arr ->
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        topk.add(o.getString("sinal") to o.getDouble("distancia"))
      }
    }
    return ClassifyResult(
        sinal = json.getString("sinal"),
        distancia = json.getDouble("distancia"),
        clipeVizinho = json.optString("clipe_vizinho"),
        framesUsados = json.optInt("frames_usados"),
        framesDescartados = json.optInt("frames_descartados"),
        topk = topk,
    )
  }

  private fun mensagemDeErro(code: Int, corpo: String): String {
    // FastAPI põe a explicação em {"detail": "..."} (ou uma lista, no 422 de validação).
    val detalhe =
        runCatching {
          val obj = JSONObject(corpo)
          when (val d = obj.opt("detail")) {
            is String -> d
            is JSONArray -> if (d.length() > 0) d.getJSONObject(0).optString("msg", corpo) else corpo
            else -> corpo
          }
        }.getOrDefault(corpo)
    return when (code) {
      503 -> "API sem clipes de referência — rode datasets/ingest.py + src/extract.py e reinicie."
      422 -> "Nenhum sinal reconhecível no clipe: $detalhe"
      else -> "API respondeu $code: $detalhe"
    }
  }
}

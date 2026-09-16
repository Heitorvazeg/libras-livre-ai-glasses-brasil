/*
 * Libras Livre — contrato do classificador de sinais (docs/prontidao-demo/02-classificador.md §2.6).
 *
 * O `computer-vision-model/treino/exportar.py` grava, ao lado do `.tflite`, um sidecar `.json` com o
 * sha256 do modelo, os rótulos na ordem da saída e o contrato de entrada (shape, dtype, ordem da
 * pose, frames). Aqui ele é lido e conferido contra o modelo carregado e contra o que o app entrega.
 * Qualquer divergência RECUSA o modelo com o motivo: um modelo errado nunca roda em silêncio.
 *
 * Puro (org.json), para testar na JVM.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import kotlin.math.exp
import org.json.JSONObject

class SidecarClassificador(
    val sha256: String,
    val rotulos: List<String>,
    /** `[1, frames, pontos, dimensões]`. */
    val shape: List<Int>,
    val dtype: String,
    /** Índices do MediaPipe Pose na ordem do vetor; null se o export não declarou. */
    val poseOrdenada: List<Int>?,
    val framesFixos: Int,
    val imputacaoEmbutida: Boolean,
    /** `calibracao.temperatura` (M7 do documento do modelo); 1,0 quando ausente. */
    val temperatura: Float,
) {
  val frames: Int
    get() = shape.getOrElse(1) { 0 }

  val pontos: Int
    get() = shape.getOrElse(2) { 0 }

  val dimensoes: Int
    get() = shape.getOrElse(3) { 0 }

  companion object {
    fun ler(json: String): SidecarClassificador {
      val raiz = JSONObject(json)
      val contrato = raiz.getJSONObject("contrato_entrada")
      val shape = contrato.getJSONArray("shape").let { a -> List(a.length()) { a.getInt(it) } }
      val pose =
          contrato.optJSONObject("layout_landmarks")?.optJSONArray("pose_ordenada")?.let { a ->
            List(a.length()) { a.getJSONObject(it).getInt("indice_mediapipe_pose") }
          }
      return SidecarClassificador(
          sha256 = raiz.getString("sha256"),
          rotulos = raiz.getJSONArray("rotulos").let { a -> List(a.length()) { a.getString(it) } },
          shape = shape,
          dtype = contrato.getString("dtype"),
          poseOrdenada = pose,
          framesFixos = contrato.optInt("frames_fixos", shape.getOrElse(1) { 0 }),
          imputacaoEmbutida = contrato.optBoolean("imputacao_embutida", false),
          temperatura = if (raiz.has("calibracao")) raiz.getJSONObject("calibracao").getDouble("temperatura").toFloat() else 1f,
      )
    }
  }
}

/** O que o Interpreter informa do `.tflite` carregado. */
data class InterfaceModelo(
    val sha256: String,
    val shapeEntrada: List<Int>,
    val dtypeEntrada: String,
    val tamanhoSaida: Int,
)

object ValidacaoClassificador {

  /** Lista vazia = modelo aceito. Cada item é um motivo legível, para a faixa de estado e o log. */
  fun motivosDeRecusa(sidecar: SidecarClassificador, modelo: InterfaceModelo): List<String> {
    val motivos = mutableListOf<String>()
    if (!sidecar.sha256.matches(Regex("[a-fA-F0-9]{64}"))) motivos += "sha256 inválido"
    if (sidecar.rotulos.size < 2 || sidecar.rotulos.any { it.isBlank() } ||
      sidecar.rotulos.distinct().size != sidecar.rotulos.size) motivos += "rótulos vazios ou duplicados"
    if (!sidecar.sha256.equals(modelo.sha256, ignoreCase = true)) {
      motivos += "sha256 do .tflite não bate com o sidecar"
    }
    if (sidecar.shape != modelo.shapeEntrada) {
      motivos += "shape do sidecar ${sidecar.shape} diverge do modelo ${modelo.shapeEntrada}"
    }
    if (sidecar.shape.size != 4 || sidecar.shape[0] != 1) {
      motivos += "shape de entrada ${sidecar.shape} não é [1, frames, pontos, dimensões]"
    } else {
      if (sidecar.pontos != LandmarkNormalizer.N_PONTOS) {
        motivos += "modelo espera ${sidecar.pontos} pontos; o app entrega ${LandmarkNormalizer.N_PONTOS}"
      }
      if (sidecar.dimensoes !in 2..LandmarkNormalizer.N_CANAIS) {
        motivos += "modelo espera ${sidecar.dimensoes} coordenadas; o app entrega até ${LandmarkNormalizer.N_CANAIS}"
      }
      if (sidecar.framesFixos != sidecar.frames) {
        motivos += "frames_fixos=${sidecar.framesFixos} diverge do shape (${sidecar.frames})"
      }
      if (sidecar.frames != 96) motivos += "modelo exige ${sidecar.frames} frames; contrato do app é 96"
    }
    if (!sidecar.dtype.equals("float32", ignoreCase = true) || !modelo.dtypeEntrada.equals("float32", ignoreCase = true)) {
      motivos += "dtype de entrada ${sidecar.dtype}/${modelo.dtypeEntrada}; o app entrega float32"
    }
    when (sidecar.poseOrdenada) {
      null -> motivos += "sidecar sem a ordem da pose (layout_landmarks.pose_ordenada)"
      LandmarkNormalizer.ORDEM_POSE -> Unit
      else -> motivos += "ordem da pose do sidecar ${sidecar.poseOrdenada} diverge do app ${LandmarkNormalizer.ORDEM_POSE}"
    }
    if (sidecar.rotulos.size != modelo.tamanhoSaida) {
      motivos += "${sidecar.rotulos.size} rótulos para uma saída de ${modelo.tamanhoSaida}"
    }
    if (!sidecar.temperatura.isFinite() || sidecar.temperatura <= 0f) motivos += "temperatura ${sidecar.temperatura} inválida"
    return motivos
  }
}

/** Softmax com temperatura (2.8) e as duas maiores probabilidades. */
object Probabilidades {

  data class Top2(val indice: Int, val confianca: Float, val margem: Float)

  fun softmax(logits: FloatArray, temperatura: Float = 1f): FloatArray {
    require(temperatura.isFinite() && temperatura > 0f) { "temperatura inválida" }
    require(logits.isNotEmpty() && logits.all { it.isFinite() }) { "logits vazios ou não finitos" }
    val escalados = DoubleArray(logits.size) { logits[it].toDouble() / temperatura }
    val maximo = escalados.maxOrNull() ?: return FloatArray(0)
    val exps = DoubleArray(escalados.size) { exp(escalados[it] - maximo) } // estável numericamente
    val soma = exps.sum()
    return FloatArray(exps.size) { (exps[it] / soma).toFloat() }
  }

  fun top2(probabilidades: FloatArray): Top2 {
    require(probabilidades.isNotEmpty()) { "sem classes" }
    require(probabilidades.all { it.isFinite() && it in 0f..1f }) { "probabilidades inválidas" }
    var primeiro = 0
    for (i in probabilidades.indices) if (probabilidades[i] > probabilidades[primeiro]) primeiro = i
    val segundo = probabilidades.indices.filter { it != primeiro }.maxOfOrNull { probabilidades[it] } ?: 0f
    return Top2(primeiro, probabilidades[primeiro], probabilidades[primeiro] - segundo)
  }
}

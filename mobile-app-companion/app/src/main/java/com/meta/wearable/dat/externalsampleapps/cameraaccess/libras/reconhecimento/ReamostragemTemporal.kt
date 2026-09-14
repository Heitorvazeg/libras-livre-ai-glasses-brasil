/*
 * Libras Livre — reamostragem do segmento PELO TEMPO (docs/prontidao-demo/02-classificador.md §2.2).
 *
 * O .tflite do classificador tem número fixo de frames (sidecar: contrato_entrada.frames_fixos,
 * 96 no export padrão) e reamostra internamente para 64. O app entrega exatamente esses N,
 * gerados em instantes igualmente espaçados entre o primeiro e o último timestamp do segmento, com
 * interpolação linear de cada coordenada: o equivalente, pelo tempo, ao `np.interp` do
 * `gcn.para_sequencia`. Assim um segmento com frames descartados pelo celular continua sendo o
 * vídeo uniforme que o treino viu.
 *
 * Roda DEPOIS da imputação de mãos (2.3), que precisa da linha do tempo real.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

object ReamostragemTemporal {

  /**
   * @param frames segmento com pelo menos um frame, todos com o mesmo número de pontos e canais.
   * @param tsMs timestamp de cada frame, não decrescente.
   * @return [alvo] frames novos (o segmento de entrada não é alterado).
   */
  fun reamostrar(frames: List<Array<FloatArray>>, tsMs: LongArray, alvo: Int): Array<Array<FloatArray>> {
    require(frames.isNotEmpty()) { "segmento vazio" }
    require(frames.size == tsMs.size) { "${frames.size} frames e ${tsMs.size} timestamps" }
    require(alvo >= 1) { "alvo precisa ser positivo" }
    val pontos = frames[0].size
    val canais = frames[0][0].size
    val inicio = tsMs.first().toDouble()
    val fim = tsMs.last().toDouble()

    var j = 0
    return Array(alvo) { k ->
      val t = if (alvo == 1) inicio else inicio + (fim - inicio) * k / (alvo - 1)
      // Avança até o intervalo [tsMs[j], tsMs[j + 1]] que contém t (np.interp: fora da faixa, o valor da borda).
      while (j < tsMs.size - 2 && tsMs[j + 1] <= t) j++
      val a = frames[j]
      val b = frames[minOf(j + 1, frames.size - 1)]
      val t0 = tsMs[j].toDouble()
      val t1 = tsMs[minOf(j + 1, tsMs.size - 1)].toDouble()
      val peso = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0).toFloat() else 0f
      Array(pontos) { p -> FloatArray(canais) { c -> a[p][c] + (b[p][c] - a[p][c]) * peso } }
    }
  }
}

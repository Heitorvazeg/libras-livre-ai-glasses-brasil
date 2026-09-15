/*
 * Libras Livre — "tronco fora do quadro" (docs/prontidao-demo/03-captura-e-landmarks.md §3.5).
 *
 * A normalização descarta o frame sem os dois ombros visíveis, e a pessoa não sabe: sinaliza para um
 * app que não está vendo nada. Numa janela deslizante de 1 s, conta os frames processados e os
 * descartados; mais de 50% descartados por mais de 1 s vira aviso — "tronco fora do quadro" se a
 * maioria dos descartes teve pose sem ombros, "ninguém no quadro" se não teve pose. Classe pura.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

enum class Enquadramento {
  OK,
  TRONCO_FORA,
  NINGUEM,
}

/** O que aconteceu com um frame que passou pelo MediaPipe. */
enum class ResultadoFrame {
  NORMALIZADO,
  SEM_OMBROS,
  SEM_POSE,
}

class JanelaEnquadramento(
    private val janelaMs: Long = 1_000L,
    private val fracaoMaxima: Float = 0.5f,
    private val sustentacaoMs: Long = 1_000L,
) {
  private val frames = ArrayDeque<Pair<Long, ResultadoFrame>>()
  private var acimaDesdeMs: Long? = null

  var processados = 0
    private set

  var descartados = 0
    private set

  fun registrar(tsMs: Long, resultado: ResultadoFrame): Enquadramento {
    frames.addLast(tsMs to resultado)
    while (frames.isNotEmpty() && tsMs - frames.first().first > janelaMs) frames.removeFirst()
    processados = frames.size
    val semOmbros = frames.count { it.second == ResultadoFrame.SEM_OMBROS }
    val semPose = frames.count { it.second == ResultadoFrame.SEM_POSE }
    descartados = semOmbros + semPose

    if (descartados <= processados * fracaoMaxima) {
      acimaDesdeMs = null
      return Enquadramento.OK
    }
    val desde = acimaDesdeMs ?: tsMs.also { acimaDesdeMs = it }
    if (tsMs - desde < sustentacaoMs) return Enquadramento.OK
    return if (semOmbros >= semPose) Enquadramento.TRONCO_FORA else Enquadramento.NINGUEM
  }

  fun reiniciar() {
    frames.clear()
    acimaDesdeMs = null
    processados = 0
    descartados = 0
  }
}

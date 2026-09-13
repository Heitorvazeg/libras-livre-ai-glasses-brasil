/*
 * Libras Livre — lado de cada mão pelo pulso da pose (docs/prontidao-demo/02-classificador.md §2.4).
 *
 * O treino usou o MediaPipe Holistic, que liga cada mão ao braço da pose. O app usa o HandLandmarker
 * separado, cujo rótulo de handedness pode vir trocado (câmera em primeira pessoa, mão cruzada).
 * Aqui cada mão vai para o pulso da pose mais próximo do seu punho (ponto 0), em coordenadas de
 * imagem. O rótulo do HandLandmarker não decide mais nada.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

object AtribuicaoMaos {

  /** Um ponto em pixels. */
  data class Ponto(val x: Float, val y: Float)

  /** Índice (na lista de punhos) da mão escolhida para cada lado; null = sem mão. */
  data class Lados(val esquerda: Int?, val direita: Int?)

  /**
   * Casa mãos e pulsos pela menor distância, gulosamente: o par mais próximo primeiro. Assim, se
   * duas mãos disputam o mesmo pulso, fica a mais próxima e a outra vai para o pulso livre.
   */
  fun atribuir(punhos: List<Ponto>, pulsoEsq: Ponto, pulsoDir: Ponto): Lados {
    val pares =
        punhos.indices
            .flatMap { i -> listOf(Triple(i, true, dist2(punhos[i], pulsoEsq)), Triple(i, false, dist2(punhos[i], pulsoDir))) }
            .sortedBy { it.third }
    var esquerda: Int? = null
    var direita: Int? = null
    val usadas = mutableSetOf<Int>()
    for ((mao, ehEsquerda, _) in pares) {
      if (mao in usadas) continue
      if (ehEsquerda && esquerda == null) {
        esquerda = mao
        usadas += mao
      } else if (!ehEsquerda && direita == null) {
        direita = mao
        usadas += mao
      }
      if (esquerda != null && direita != null) break
    }
    return Lados(esquerda, direita)
  }

  private fun dist2(a: Ponto, b: Ponto): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
  }
}

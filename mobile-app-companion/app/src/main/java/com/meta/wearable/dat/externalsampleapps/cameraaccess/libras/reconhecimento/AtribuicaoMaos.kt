/*
 * Libras Livre — quem é a pessoa surda e de que lado está cada mão
 * (docs/prontidao-demo/02-classificador.md §2.4 e 03-captura-e-landmarks.md §3.6).
 *
 * O treino usou o MediaPipe Holistic, que liga cada mão ao braço da pose. O app usa detectores
 * separados, e a câmera dos óculos vê mais gente: o atendente (cujas mãos aparecem na parte de baixo
 * do quadro) e quem passa ao fundo. Três regras, todas em coordenadas de imagem:
 *   - pose: das pessoas detectadas, fica a de ombros mais afastados (a mais próxima da câmera);
 *   - filtro: só ficam as mãos cujo punho está a menos de `raioPulso` larguras de ombro de um pulso
 *     dessa pose;
 *   - lado: cada mão restante vai para o pulso mais próximo; o rótulo de handedness do HandLandmarker
 *     não decide nada.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import kotlin.math.sqrt

object AtribuicaoMaos {

  /** Um ponto em pixels. */
  data class Ponto(val x: Float, val y: Float)

  /** Índice (na lista de punhos) da mão escolhida para cada lado; null = sem mão. */
  data class Lados(val esquerda: Int?, val direita: Int?)

  /** Raio inicial do filtro, em larguras de ombro (3.6). */
  const val RAIO_PULSO = 0.5f

  /** A pose da pessoa mais próxima: a de maior distância entre ombros. Null sem poses. */
  fun escolherPose(ombros: List<Pair<Ponto, Ponto>>): Int? =
      ombros.indices.maxByOrNull { distancia(ombros[it].first, ombros[it].second) }

  /** Os índices das mãos cujo punho está perto de um dos pulsos da pose escolhida. */
  fun filtrarPorPulso(
      punhos: List<Ponto>,
      pulsoEsq: Ponto,
      pulsoDir: Ponto,
      larguraOmbros: Float,
      raio: Float = RAIO_PULSO,
  ): List<Int> {
    val limite = raio * larguraOmbros
    return punhos.indices.filter { minOf(distancia(punhos[it], pulsoEsq), distancia(punhos[it], pulsoDir)) < limite }
  }

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

  fun distancia(a: Ponto, b: Ponto): Float = sqrt(dist2(a, b))

  private fun dist2(a: Ponto, b: Ponto): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
  }
}

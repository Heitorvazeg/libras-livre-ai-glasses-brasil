/*
 * Libras Livre — normalização de landmarks on-device (Fase 1 de
 * docs/extracao-landmarks-plano.md).
 *
 * Replica, byte a byte (dentro da tolerância numérica de float32), o que
 * `computer-vision-model/PoC/src/extract.py:frame_normalizado` faz em Python — é a
 * fonte de verdade, não este arquivo (ver docs/extracao-landmarks-plano.md §2.4).
 * Um descompasso aqui não quebra com erro: o classificador recebe uma distribuição
 * diferente da que aprendeu e erra silenciosamente.
 *
 * DUAS CORREÇÕES em relação ao rascunho original do plano (`docs/extracao-landmarks-plano.md`
 * §3 item 2, §4): usa só 2 canais (x, y) — o modelo em treino usa `canais_ent=2`
 * (`treino/gcn.py`) e `arr[:, :, :2]` (`treino/dados.py`), não 3. z fica de fora
 * inteiramente, inclusive no cálculo de origem/escala (que já usava só x,y no
 * Python, então não muda nada matematicamente — só deixa de existir código morto
 * pra ler um z que nunca seria usado).
 *
 * Algoritmo (extract.py §5.2):
 *   1. Converte as coordenadas normalizadas do MediaPipe (0..1) pra pixel: x·W, y·H.
 *   2. Origem = ponto médio entre os ombros (landmarks 11 e 12 do MediaPipe Pose).
 *   3. Escala = distância euclidiana entre os ombros, em pixels (x,y).
 *   4. Cada ponto vira (ponto_px − origem) / escala.
 *   5. Frame descartado (retorna null) se um dos ombros tiver visibility abaixo de
 *      MIN_VISIBILIDADE, ou se a escala for degenerada (ombros colados).
 *   6. Mão ausente vira o vetor zero — equivalente a "a mão está na origem", a
 *      mesma convenção que extract.py usa (bloco de zeros, sem normalizar).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import kotlin.math.sqrt

object LandmarkNormalizer {

  // PoC/config.yaml:pose_indices — 15 pontos, na ordem de inserção do dict (preservada
  // pelo YAML/Python, ver computer-vision-model/PoC/src/config.py:pose_subset). Cada valor é o
  // índice do ponto dentro dos 33 do MediaPipe Pose: nariz, olho_esq, olho_dir, orelha_esq,
  // orelha_dir, boca_esq, boca_dir, ombro_esq, ombro_dir, cotovelo_esq, cotovelo_dir,
  // pulso_esq, pulso_dir, quadril_esq, quadril_dir.
  private val POSE_SUBSET = intArrayOf(0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24)

  // ombro_esq / ombro_dir — índices dentro dos 33 pontos do MediaPipe Pose (não do subset
  // acima), usados só pra origem/escala (normalizacao.ref_a/ref_b no config.yaml).
  private const val REF_OMBRO_ESQ = 11
  private const val REF_OMBRO_DIR = 12

  private const val MIN_VISIBILIDADE = 0.5f
  private const val ESCALA_MINIMA = 1e-6f

  const val N_POSE = 15
  const val N_MAO = 21
  const val N_PONTOS = N_POSE + 2 * N_MAO // 57
  const val N_CANAIS = 2 // x, y — sem z (ver header)

  /** Offset, dentro do vetor de 57 pontos, de onde cada bloco começa. */
  const val OFFSET_MAO_ESQ = N_POSE
  const val OFFSET_MAO_DIR = N_POSE + N_MAO

  /**
   * Normaliza um frame. Devolve `null` quando o frame não é normalizável — mesmos dois
   * critérios do Python: ombro pouco visível, ou ombros degenerados (escala ~0).
   *
   * @param width largura do frame em pixels (as coordenadas do MediaPipe vêm 0..1).
   * @param height altura do frame em pixels.
   * @return `Array(57) { FloatArray(2) }` — pose(15) + mão_esq(21) + mão_dir(21), cada
   *   ponto `[x, y]` já normalizado. O chamador fica dono do array devolvido (ver
   *   [HandGapImputer], que o guarda e eventualmente o modifica in-place).
   */
  fun normalize(frame: FrameLandmarks, width: Int, height: Int): Array<FloatArray>? {
    val pose = frame.pose
    if (pose.size <= maxOf(REF_OMBRO_ESQ, REF_OMBRO_DIR)) return null

    val ombroEsq = pose[REF_OMBRO_ESQ]
    val ombroDir = pose[REF_OMBRO_DIR]
    // FrameLandmarks.pose: [x, y, z, visibility] — índice 3 é a visibility (LandmarkExtractor.kt).
    if (minOf(ombroEsq[3], ombroDir[3]) < MIN_VISIBILIDADE) return null

    val w = width.toFloat()
    val h = height.toFloat()
    val ax = ombroEsq[0] * w
    val ay = ombroEsq[1] * h
    val bx = ombroDir[0] * w
    val by = ombroDir[1] * h
    val origemX = (ax + bx) / 2f
    val origemY = (ay + by) / 2f
    val dx = ax - bx
    val dy = ay - by
    val escala = sqrt(dx * dx + dy * dy)
    if (escala < ESCALA_MINIMA) return null

    val pontos = Array(N_PONTOS) { FloatArray(N_CANAIS) }
    for (i in POSE_SUBSET.indices) {
      normalizarPonto(pontos[i], pose[POSE_SUBSET[i]], w, h, origemX, origemY, escala)
    }
    preencherMao(pontos, OFFSET_MAO_ESQ, frame.leftHand, w, h, origemX, origemY, escala)
    preencherMao(pontos, OFFSET_MAO_DIR, frame.rightHand, w, h, origemX, origemY, escala)
    return pontos
  }

  private fun preencherMao(
      destino: Array<FloatArray>,
      offset: Int,
      mao: List<FloatArray>?,
      w: Float,
      h: Float,
      origemX: Float,
      origemY: Float,
      escala: Float,
  ) {
    // Mão ausente: os pontos já nascem [0f, 0f] em Array(N_PONTOS) { FloatArray(N_CANAIS) } —
    // "ausência = origem", mesma convenção de extract.py (bloco de zeros, ver header).
    mao ?: return
    for (i in mao.indices) {
      normalizarPonto(destino[offset + i], mao[i], w, h, origemX, origemY, escala)
    }
  }

  private fun normalizarPonto(
      destino: FloatArray,
      lm: FloatArray, // [x, y, ...] normalizado (0..1) do MediaPipe
      w: Float,
      h: Float,
      origemX: Float,
      origemY: Float,
      escala: Float,
  ) {
    destino[0] = (lm[0] * w - origemX) / escala
    destino[1] = (lm[1] * h - origemY) / escala
  }
}

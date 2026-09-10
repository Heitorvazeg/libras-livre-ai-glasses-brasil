/*
 * Libras Livre — imputação de lacunas de mão, versão ONLINE (Fase 2 de
 * docs/extracao-landmarks-plano.md §2.5, §6).
 *
 * Portagem causal de `computer-vision-model/treino/dados.py:imputar_maos`. O
 * original roda OFFLINE: conhece o clipe inteiro de uma vez, então pode "olhar
 * pra frente" pra saber que a mão vai reaparecer e interpolar o vão. No app, os
 * frames chegam um de cada vez, ao vivo (ver docs/orquestracao-dialogo-audio-plano.md
 * — mesma distinção causal/não-causal já registrada em
 * docs/sign-boundary-detector-plano.md §6) — não dá pra "espiar" um frame futuro
 * porque ele ainda não existe.
 *
 * Adaptação: em vez de processar o clipe pronto, [offer] bufferiza os frames e,
 * quando uma mão REAPARECE de verdade, preenche retroativamente (no buffer já
 * acumulado) os frames que ficaram entre a última aparição e essa. O resultado é
 * idêntico ao offline pro mesmo clipe, porque a regra é a mesma — interpolar
 * linearmente só entre duas detecções reais, com lacuna <= [lacunaMaxima] — só
 * muda QUANDO ela pode ser aplicada. Ver `treino/dados.py:imputar_maos` (o
 * `lacuna_maxima=5` de lá é o mesmo valor usado no treino de verdade — não é
 * sobrescrito em `treinar.py`) e `treino/selftest.py:teste_imputacao_maos`, cujo
 * fixture está portado em `HandGapImputerTest`.
 *
 * ORDEM IMPORTA: isto processa os frames no framerate nativo, ANTES da
 * reamostragem pra 64 frames (`TemporalResampler`, Fase 3) — reamostrar antes
 * mudaria o que "lacuna de 5 frames" significa.
 *
 * Só toca nos blocos de mão — pose nunca é modificada (mesma garantia do Python,
 * conferida em `teste_imputacao_maos`).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

class HandGapImputer(private val lacunaMaxima: Int = 5) {

  // Buffer da sessão de captura em curso (cresce enquanto a sessão estiver aberta —
  // ver DialogOrchestrator, estado CAPTURANDO_SINAIS).
  private val frames = mutableListOf<Array<FloatArray>>()

  // Índice do último frame em que cada mão apareceu; -1 = nunca apareceu ainda nesta sessão.
  private var ultimoPresenteEsq = -1
  private var ultimoPresenteDir = -1

  /**
   * Adiciona um frame já normalizado ([LandmarkNormalizer.normalize]) ao buffer da sessão,
   * fechando retroativamente qualquer lacuna curta de mão que essa chegada complete. O
   * imputador passa a ser dono do array — não guarde nem reuse a referência depois de
   * chamar isto, porque uma chamada futura pode sobrescrever os blocos de mão dele.
   */
  fun offer(frame: Array<FloatArray>) {
    require(frame.size == LandmarkNormalizer.N_PONTOS) {
      "esperava ${LandmarkNormalizer.N_PONTOS} pontos, veio ${frame.size}"
    }
    val indiceAtual = frames.size
    frames.add(frame)
    ultimoPresenteEsq = processarMao(indiceAtual, LandmarkNormalizer.OFFSET_MAO_ESQ, ultimoPresenteEsq)
    ultimoPresenteDir = processarMao(indiceAtual, LandmarkNormalizer.OFFSET_MAO_DIR, ultimoPresenteDir)
  }

  /** A sessão acumulada até agora, com as lacunas curtas já fechadas. */
  fun snapshot(): List<Array<FloatArray>> = frames

  /** Descarta o buffer — chamar ao abrir uma nova sessão de captura. */
  fun reset() {
    frames.clear()
    ultimoPresenteEsq = -1
    ultimoPresenteDir = -1
  }

  private fun processarMao(indiceAtual: Int, offset: Int, ultimoPresente: Int): Int {
    if (maoAusente(frames[indiceAtual], offset)) return ultimoPresente // segue ausente, nada a fechar ainda

    if (ultimoPresente >= 0) {
      val vao = indiceAtual - ultimoPresente - 1
      if (vao in 1..lacunaMaxima) {
        interpolar(ultimoPresente, indiceAtual, offset, vao)
      }
      // vao > lacunaMaxima: lacuna longa, fica zerada (ausência real) — já é o valor
      // default, nada a fazer. vao == 0: mão presente no frame anterior, nada a fechar.
    }
    return indiceAtual
  }

  private fun maoAusente(frame: Array<FloatArray>, offset: Int): Boolean {
    for (i in 0 until LandmarkNormalizer.N_MAO) {
      val p = frame[offset + i]
      if (p[0] != 0f || p[1] != 0f) return false
    }
    return true
  }

  // Equivalente a `saida[ini+1:fim] = seq[ini]*(1-pesos) + seq[fim]*pesos`, com
  // `pesos = np.linspace(0, 1, vao + 2)[1:-1]` (treino/dados.py:imputar_maos) — pra
  // passo=1..vao, peso = passo / (vao + 1).
  private fun interpolar(ini: Int, fim: Int, offset: Int, vao: Int) {
    val de = frames[ini]
    val ate = frames[fim]
    for (passo in 1..vao) {
      val peso = passo.toFloat() / (vao + 1).toFloat()
      val alvo = frames[ini + passo]
      for (i in 0 until LandmarkNormalizer.N_MAO) {
        val a = de[offset + i]
        val b = ate[offset + i]
        val destino = alvo[offset + i]
        destino[0] = a[0] * (1f - peso) + b[0] * peso
        destino[1] = a[1] * (1f - peso) + b[1] * peso
      }
    }
  }
}

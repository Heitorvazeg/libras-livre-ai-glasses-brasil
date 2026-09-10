/*
 * Libras Livre — detecção automática de início/fim de sinalização (Fase 2 de
 * docs/sign-boundary-detector-plano.md §4).
 *
 * Decide, quadro a quadro, quando uma pessoa está SINALIZANDO ou PARADA — é o que delimita
 * cada sinal individual DENTRO de uma sessão (a sessão inteira é aberta/fechada pela wake
 * word, via DialogOrchestrator; ver docs/orquestracao-dialogo-audio-plano.md).
 *
 * Consome a saída JÁ NORMALIZADA de LandmarkNormalizer (57 pontos x 2 canais, em unidades de
 * "distância entre ombros") — não recalcula origem/escala por conta própria (§4.1 revisado
 * de docs/sign-boundary-detector-plano.md).
 *
 * ONLINE/CAUSAL: só olha o passado (frame anterior), nunca o futuro — ver §6 do plano.
 *
 * PARÂMETROS NÃO CALIBRADOS: as Fases 0/1 do plano (calibração com dado real) estão
 * bloqueadas no ambiente onde isto foi implementado (sem device/emulador, sem o dataset da
 * PoC no checkout — ver §0/§7 do plano). Os defaults abaixo são os "pontos de partida
 * sugeridos" do §4.4, não valores medidos — calibrar é trabalho futuro, não deste commit.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import kotlin.math.sqrt

enum class EstadoSinalizacao {
  SINALIZANDO,
  PARADO,
}

class SignBoundaryDetector(
    // NÃO CALIBRADO (ver header) — soma das distâncias euclidianas (mãos+braços, em unidades
    // de distância-entre-ombros) abaixo disso é considerado "parado".
    private val limiarVelocidade: Float = 0.5f,
    // NÃO CALIBRADO — peso de cada grupo na combinação do deslocamento (§4.1). Começa 1:1.
    private val pesoMao: Float = 1f,
    private val pesoBraco: Float = 1f,
    // NÃO CALIBRADO — quanto tempo sustentado abaixo do limiar até considerar fim (§4.2).
    private val janelaSustentacaoMs: Long = 700,
    // NÃO CALIBRADO — tolerância pra oclusão total (as duas mãos ausentes) antes de forçar
    // fim mesmo sem ter medido "parado" — maior que janelaSustentacaoMs de propósito (§4.3).
    private val tetoOclusaoMs: Long = 1200,
    // NÃO CALIBRADO — segmento mais curto que isso não dispara boundary (ruído/falso início).
    private val duracaoMinimaMs: Long = 300,
    // NÃO CALIBRADO — teto de segurança: força fim mesmo sem detectar pausa (§4.4).
    private val duracaoMaximaMs: Long = 8_000,
    // Disparado (síncrono, na mesma chamada de onFrame/forcarFechamento) a cada transição
    // SINALIZANDO -> PARADO com duração >= duracaoMinimaMs — é o "boundary" que dispara a
    // classificação do segmento acumulado desde o boundary anterior (§5).
    private val onBoundary: () -> Unit,
) {

  companion object {
    // Índices dentro do vetor de 57 pontos de LandmarkNormalizer — cotovelo_esq/pulso_esq e
    // cotovelo_dir/pulso_dir (ver docs/sign-boundary-detector-plano.md §4.1 revisado).
    private val BRACO_ESQ = intArrayOf(9, 11)
    private val BRACO_DIR = intArrayOf(10, 12)
  }

  private var estado = EstadoSinalizacao.PARADO
  private var frameAnterior: Array<FloatArray>? = null

  // Timestamp do último instante com deslocamento >= limiarVelocidade, enquanto SINALIZANDO
  // — é contra isso que janelaSustentacaoMs é medida (§4.2).
  private var tsUltimoMovimento = 0L
  // Timestamp de quando as DUAS mãos ficaram ausentes ao mesmo tempo, ou null se não estão
  // (nesse instante) — oclusão de uma mão só não conta (§4.3).
  private var tsInicioOclusaoTotal: Long? = null
  private var tsInicioSegmento = 0L

  /** O estado atual — exposto só pra inspeção/teste, quem decide o que fazer é [onBoundary]. */
  val estadoAtual: EstadoSinalizacao
    get() = estado

  /**
   * Processa um frame já normalizado. [timestampMs] precisa ser monotônico crescente (mesmo
   * relógio usado pra extrair os landmarks — ver LandmarkPipeline.nextTimestampMs()).
   */
  fun onFrame(frame: Array<FloatArray>, timestampMs: Long) {
    val anterior = frameAnterior
    frameAnterior = frame

    val maoEsqAusente = maoAusente(frame, LandmarkNormalizer.OFFSET_MAO_ESQ)
    val maoDirAusente = maoAusente(frame, LandmarkNormalizer.OFFSET_MAO_DIR)
    val ambasAusentes = maoEsqAusente && maoDirAusente

    tsInicioOclusaoTotal = if (ambasAusentes) (tsInicioOclusaoTotal ?: timestampMs) else null

    // Sem frame anterior não dá pra medir deslocamento nenhum — só a bookkeeping de oclusão
    // acima já foi feita, o resto espera o próximo frame.
    if (anterior == null) return

    val deslocamento = if (ambasAusentes) 0f else calcularDeslocamento(anterior, frame)

    when (estado) {
      EstadoSinalizacao.PARADO -> {
        if (!ambasAusentes && deslocamento >= limiarVelocidade) {
          estado = EstadoSinalizacao.SINALIZANDO
          tsInicioSegmento = timestampMs
          tsUltimoMovimento = timestampMs
        }
      }
      EstadoSinalizacao.SINALIZANDO -> {
        if (!ambasAusentes && deslocamento >= limiarVelocidade) {
          tsUltimoMovimento = timestampMs
        }
        val oclusaoLongaDemais =
            tsInicioOclusaoTotal != null && timestampMs - tsInicioOclusaoTotal!! >= tetoOclusaoMs
        val pausaSustentada = timestampMs - tsUltimoMovimento >= janelaSustentacaoMs
        val duracaoExcedida = timestampMs - tsInicioSegmento >= duracaoMaximaMs

        if (oclusaoLongaDemais || pausaSustentada || duracaoExcedida) {
          fecharSegmento(timestampMs)
        }
      }
    }
  }

  /**
   * Força o fim do segmento em aberto, se houver — sem esperar sustentação nem checar
   * duracaoMinimaMs (o objetivo é não perder o último sinal ao fechar a sessão, ver
   * docs/sign-boundary-detector-plano.md §5.3). Devolve true se havia um segmento SINALIZANDO
   * (e portanto [onBoundary] foi chamado). Chamar só ao encerrar a sessão inteira.
   */
  fun forcarFechamento(): Boolean {
    if (estado != EstadoSinalizacao.SINALIZANDO) return false
    estado = EstadoSinalizacao.PARADO
    onBoundary()
    return true
  }

  private fun fecharSegmento(timestampMs: Long) {
    val duracaoSegmento = timestampMs - tsInicioSegmento
    estado = EstadoSinalizacao.PARADO
    if (duracaoSegmento >= duracaoMinimaMs) {
      onBoundary()
    }
    // Segmento curto demais: descarta silenciosamente (ruído/falso início, §4.4) — quem
    // acumula os frames (LandmarkPipeline/HandGapImputer) começa um buffer novo de qualquer
    // forma no próximo segmento.
  }

  private fun calcularDeslocamento(anterior: Array<FloatArray>, atual: Array<FloatArray>): Float {
    val dMaoEsq = deslocamentoMao(anterior, atual, LandmarkNormalizer.OFFSET_MAO_ESQ)
    val dMaoDir = deslocamentoMao(anterior, atual, LandmarkNormalizer.OFFSET_MAO_DIR)
    val dBracoEsq = deslocamentoPontos(anterior, atual, BRACO_ESQ)
    val dBracoDir = deslocamentoPontos(anterior, atual, BRACO_DIR)
    return pesoMao * (dMaoEsq + dMaoDir) + pesoBraco * (dBracoEsq + dBracoDir)
  }

  // Soma das distâncias euclidianas ponto a ponto da mão — pula o grupo inteiro (devolve 0,
  // não conta como "parado") se a mão estiver ausente em QUALQUER um dos dois frames: ausência
  // não é deslocamento zero (§4.1) — é a mesma convenção de LandmarkNormalizer/HandGapImputer
  // (bloco de N_MAO pontos somando zero = ausente).
  private fun deslocamentoMao(anterior: Array<FloatArray>, atual: Array<FloatArray>, offset: Int): Float {
    if (maoAusente(anterior, offset) || maoAusente(atual, offset)) return 0f
    var soma = 0f
    for (i in 0 until LandmarkNormalizer.N_MAO) {
      soma += distancia(anterior[offset + i], atual[offset + i])
    }
    return soma
  }

  // Pontos de pose (braço) — LandmarkNormalizer só devolve um frame não-nulo quando a pose
  // inteira foi detectada, então não existe "braço ausente" separado a checar aqui.
  private fun deslocamentoPontos(anterior: Array<FloatArray>, atual: Array<FloatArray>, indices: IntArray): Float {
    var soma = 0f
    for (i in indices) soma += distancia(anterior[i], atual[i])
    return soma
  }

  private fun distancia(a: FloatArray, b: FloatArray): Float {
    val dx = a[0] - b[0]
    val dy = a[1] - b[1]
    return sqrt(dx * dx + dy * dy)
  }

  private fun maoAusente(frame: Array<FloatArray>, offset: Int): Boolean {
    for (i in 0 until LandmarkNormalizer.N_MAO) {
      val p = frame[offset + i]
      if (p[0] != 0f || p[1] != 0f) return false
    }
    return true
  }
}

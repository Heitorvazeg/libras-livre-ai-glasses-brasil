/*
 * Libras Livre — detecção de início e fim de cada sinal (docs/prontidao-demo/01-segmentacao.md).
 *
 * Decide, frame a frame, se a pessoa está SINALIZANDO ou PARADA. Consome a saída já normalizada do
 * LandmarkNormalizer (57 pontos, em larguras de ombro) e só olha o passado (causal).
 *
 * O que mudou em relação à primeira versão, e por quê (mapa de riscos §3.1):
 *   - 1.2: velocidade em ombros/s, comparando com o frame mais recente que tenha pelo menos
 *     `janelaVelocidadeMs` de idade, dividida pelo intervalo real. O limiar antigo era deslocamento
 *     entre dois frames, e mudava de sentido quando o celular processava menos fps;
 *   - 1.3: média da velocidade dos 21 pontos por mão (só com a mão presente nos dois frames) e
 *     MÁXIMO entre mão esquerda, mão direita e pulsos. A soma de 42 distâncias acumulava tremor e
 *     mudava de escala com o número de mãos visíveis;
 *   - 1.4: média móvel exponencial e dois limiares (entrada e saída);
 *   - 1.5: a duração mínima é medida no MOVIMENTO (último movimento − início), não incluindo a
 *     pausa; abaixo dela o segmento é descartado sem classificar;
 *   - 1.6: com as duas mãos ausentes, o relógio da pausa para; o sinal só fecha por oclusão depois
 *     de `tetoOclusaoMs`.
 *
 * Parâmetros em ParametrosSegmentacao, ESTIMADOS e não calibrados (1.8).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import kotlin.math.sqrt

enum class EstadoSinalizacao {
  SINALIZANDO,
  PARADO,
}

enum class MotivoFechamento {
  PAUSA,
  OCLUSAO,
  DURACAO_MAXIMA,
  FIM_DA_SESSAO,
}

/**
 * Onde o movimento de um sinal começou e terminou, no relógio dos frames.
 *
 * [inicioMs] e [fimDoMovimentoMs] seguem a velocidade SUAVIZADA (estado e pausa) e delimitam o
 * recorte (1.1), com folga. [duracaoMovimentoMs], que decide a duração mínima (1.5), é medida na
 * velocidade BRUTA: do primeiro frame da sequência que levou à entrada até o último frame em
 * movimento, menos o que a janela de velocidade acrescenta (idade da referência − um frame). A
 * velocidade compara com um frame de ~110 ms atrás, então um deslocamento continua "visível" por
 * essa janela depois de acabar, e a média móvel ainda estica o fim: sem essas correções, um espasmo
 * de 4 frames mediria ~290 ms e passaria da duração mínima.
 */
data class LimitesSegmento(
    val inicioMs: Long,
    val fimDoMovimentoMs: Long,
    val duracaoMovimentoMs: Long,
    val motivo: MotivoFechamento,
)

/**
 * Velocidades do último frame, em ombros/s. Nulo = sem medida (mão ausente em um dos dois frames,
 * ou ainda sem frame de referência). Vai para o gravador de sessão (1.9).
 */
data class MedicaoVelocidade(
    val maoEsq: Float?,
    val maoDir: Float?,
    val pulsos: Float?,
    val final: Float,
    val suavizada: Float,
)

class SignBoundaryDetector(
    private val parametros: ParametrosSegmentacao = ParametrosSegmentacao(),
    // Síncronos, na mesma chamada de onFrame/forcarFechamento.
    private val onBoundary: (LimitesSegmento) -> Unit,
    private val onDescartado: (LimitesSegmento) -> Unit = {},
) {

  companion object {
    // Índices dentro do vetor de 57 pontos (POSE_SUBSET do LandmarkNormalizer): pulso_esq, pulso_dir.
    private const val PULSO_ESQ = 11
    private const val PULSO_DIR = 12
  }

  private class Amostra(val tsMs: Long, val pontos: Array<FloatArray>)

  // Frames recentes, do mais antigo ao mais novo; guarda só o necessário para achar a referência.
  private val recentes = ArrayDeque<Amostra>()

  private var estado = EstadoSinalizacao.PARADO
  private var suavizada = 0f
  private var temSuavizada = false
  private var tsAnterior: Long? = null

  private var tsInicio = 0L
  private var tsUltimoMovimento = 0L
  // Duração mínima pela velocidade bruta (ver LimitesSegmento): início da sequência de frames
  // brutos em movimento ainda PARADO, e o fim ajustado do último frame bruto em movimento.
  private var inicioSequenciaBrutaMs: Long? = null
  private var inicioBrutoMs = 0L
  private var fimBrutoAjustadoMs = 0L
  private var pausaAcumuladaMs = 0L
  private var oclusaoAcumuladaMs = 0L

  val estadoAtual: EstadoSinalizacao
    get() = estado

  /** A medição do último frame processado, ou null antes de haver referência. */
  var ultimaMedicao: MedicaoVelocidade? = null
    private set

  /** Processa um frame normalizado. [timestampMs] precisa ser estritamente crescente. */
  fun onFrame(frame: Array<FloatArray>, timestampMs: Long) {
    val dtMs = tsAnterior?.let { timestampMs - it } ?: 0L
    tsAnterior = timestampMs

    val referencia = referenciaPara(timestampMs)
    recentes.addLast(Amostra(timestampMs, frame))
    descartarAntigos(timestampMs)

    val ambasAusentes =
        maoAusente(frame, LandmarkNormalizer.OFFSET_MAO_ESQ) &&
            maoAusente(frame, LandmarkNormalizer.OFFSET_MAO_DIR)

    val medicao = referencia?.let { medir(it, frame, timestampMs) }
    ultimaMedicao = medicao
    if (referencia == null || medicao == null) return
    val v = medicao.suavizada
    val brutoEmMovimento = medicao.final >= parametros.limiarSaida
    // O que a janela acrescenta ao fim de um movimento: a referência tem esta idade, e o movimento
    // real terminou no máximo um frame antes do frame atual.
    val fimBrutoDesteFrame = timestampMs - maxOf(0L, (timestampMs - referencia.tsMs) - dtMs)

    when (estado) {
      EstadoSinalizacao.PARADO -> {
        inicioSequenciaBrutaMs = if (brutoEmMovimento) inicioSequenciaBrutaMs ?: timestampMs else null
        if (v >= parametros.limiarEntrada) {
          estado = EstadoSinalizacao.SINALIZANDO
          tsInicio = timestampMs
          tsUltimoMovimento = timestampMs
          inicioBrutoMs = inicioSequenciaBrutaMs ?: timestampMs
          fimBrutoAjustadoMs = if (brutoEmMovimento) fimBrutoDesteFrame else inicioBrutoMs
          inicioSequenciaBrutaMs = null
          pausaAcumuladaMs = 0L
          oclusaoAcumuladaMs = 0L
        }
      }
      EstadoSinalizacao.SINALIZANDO -> {
        if (brutoEmMovimento) fimBrutoAjustadoMs = fimBrutoDesteFrame
        if (v >= parametros.limiarSaida) {
          tsUltimoMovimento = timestampMs
          pausaAcumuladaMs = 0L
        } else if (!ambasAusentes) {
          pausaAcumuladaMs += dtMs
        }
        oclusaoAcumuladaMs = if (ambasAusentes) oclusaoAcumuladaMs + dtMs else 0L

        val motivo =
            when {
              oclusaoAcumuladaMs >= parametros.tetoOclusaoMs -> MotivoFechamento.OCLUSAO
              pausaAcumuladaMs >= parametros.pausaMs -> MotivoFechamento.PAUSA
              timestampMs - tsInicio >= parametros.duracaoMaximaMs -> MotivoFechamento.DURACAO_MAXIMA
              else -> null
            }
        if (motivo != null) fechar(motivo)
      }
    }
  }

  /**
   * Volta de uma pausa do stream (docs/prontidao-demo/03 §3.2): esquece o frame de referência e o
   * instante do último frame, para que o intervalo sem frames não vire pausa (que fecharia o sinal),
   * oclusão nem velocidade. O estado e o sinal em andamento continuam.
   */
  fun descontarPausa() {
    recentes.clear()
    tsAnterior = null
  }

  /**
   * Fecha o segmento em aberto, ao encerrar a sessão. Aplica a duração mínima como qualquer outro
   * fechamento: um espasmo no fim da sessão continua não sendo sinal. Devolve true se chamou
   * [onBoundary].
   */
  fun forcarFechamento(): Boolean {
    if (estado != EstadoSinalizacao.SINALIZANDO) return false
    return fechar(MotivoFechamento.FIM_DA_SESSAO)
  }

  private fun fechar(motivo: MotivoFechamento): Boolean {
    estado = EstadoSinalizacao.PARADO
    val duracao = maxOf(0L, fimBrutoAjustadoMs - inicioBrutoMs)
    val limites = LimitesSegmento(tsInicio, tsUltimoMovimento, duracao, motivo)
    return if (limites.duracaoMovimentoMs >= parametros.duracaoMinimaMs) {
      onBoundary(limites)
      true
    } else {
      onDescartado(limites)
      false
    }
  }

  // O frame mais recente (já guardado) com pelo menos janelaVelocidadeMs de idade.
  private fun referenciaPara(tsMs: Long): Amostra? =
      recentes.lastOrNull { tsMs - it.tsMs >= parametros.janelaVelocidadeMs }

  // Mantém uma única amostra mais velha que a janela: é a que pode ainda servir de referência.
  private fun descartarAntigos(tsMs: Long) {
    while (recentes.size >= 2 && tsMs - recentes[1].tsMs >= parametros.janelaVelocidadeMs) {
      recentes.removeFirst()
    }
  }

  private fun medir(referencia: Amostra, atual: Array<FloatArray>, tsMs: Long): MedicaoVelocidade {
    val dtS = (tsMs - referencia.tsMs) / 1000f
    val maoEsq = velocidadeMao(referencia.pontos, atual, LandmarkNormalizer.OFFSET_MAO_ESQ, dtS)
    val maoDir = velocidadeMao(referencia.pontos, atual, LandmarkNormalizer.OFFSET_MAO_DIR, dtS)
    val pulsos =
        maxOf(
            distancia(referencia.pontos[PULSO_ESQ], atual[PULSO_ESQ]),
            distancia(referencia.pontos[PULSO_DIR], atual[PULSO_DIR]),
        ) / dtS
    val final = maxOf(maoEsq ?: 0f, maoDir ?: 0f, pulsos)
    suavizada =
        if (temSuavizada) parametros.alfaSuavizacao * final + (1 - parametros.alfaSuavizacao) * suavizada
        else final
    temSuavizada = true
    return MedicaoVelocidade(maoEsq, maoDir, pulsos, final, suavizada)
  }

  private fun velocidadeMao(anterior: Array<FloatArray>, atual: Array<FloatArray>, offset: Int, dtS: Float): Float? {
    if (maoAusente(anterior, offset) || maoAusente(atual, offset)) return null
    var soma = 0f
    for (i in 0 until LandmarkNormalizer.N_MAO) soma += distancia(anterior[offset + i], atual[offset + i])
    return soma / LandmarkNormalizer.N_MAO / dtS
  }

  // Só x e y: o z do MediaPipe é ruidoso e a segmentação não precisa dele.
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

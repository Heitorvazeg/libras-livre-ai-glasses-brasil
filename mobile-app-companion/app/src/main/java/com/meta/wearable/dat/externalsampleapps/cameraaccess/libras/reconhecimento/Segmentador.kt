/*
 * Libras Livre — recorte de cada sinal com margem de repouso (docs/prontidao-demo/01-segmentacao.md §1.1).
 *
 * Antes, o classificador recebia TUDO desde o sinal anterior: 6 s parado + 1 s de sinal viravam um
 * segmento de ~7,7 s, que não se parece com nada do treino (um sinal por clipe, começando e
 * terminando em repouso). Agora o segmento vai de `inicio − preRollMs` a
 * `fimDoMovimento + posRollMs`, com os frames guardados numa janela limitada.
 *
 * Função pura sobre frames com timestamp: o LandmarkPipeline chama [onFrame] na thread de frames e
 * recebe o segmento pronto em [onSegmento].
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

/** Um frame normalizado e o instante em que foi capturado. */
class FrameComTempo(val tsMs: Long, val pontos: Array<FloatArray>)

class Segmentador(
    private val parametros: ParametrosSegmentacao = ParametrosSegmentacao(),
    // Os frames entregues são CÓPIAS: quem recebe pode imputar sobre eles sem afetar a janela.
    private val onSegmento: (frames: List<FrameComTempo>, limites: LimitesSegmento) -> Unit,
    private val onDescartado: (LimitesSegmento) -> Unit = {},
) {

  private val janela = ArrayDeque<FrameComTempo>()

  private val detector =
      SignBoundaryDetector(
          parametros = parametros,
          onBoundary = { limites -> onSegmento(recortar(limites), limites) },
          onDescartado = onDescartado,
      )

  val estadoAtual: EstadoSinalizacao
    get() = detector.estadoAtual

  val ultimaMedicao: MedicaoVelocidade?
    get() = detector.ultimaMedicao

  fun onFrame(frame: Array<FloatArray>, timestampMs: Long) {
    janela.addLast(FrameComTempo(timestampMs, frame))
    while (janela.isNotEmpty() && timestampMs - janela.first().tsMs > parametros.retencaoMs) {
      janela.removeFirst()
    }
    detector.onFrame(frame, timestampMs)
  }

  fun forcarFechamento(): Boolean = detector.forcarFechamento()

  /** Volta de uma pausa do stream (3.2): o intervalo sem frames não conta como pausa nem oclusão. */
  fun descontarPausa() = detector.descontarPausa()

  private fun recortar(limites: LimitesSegmento): List<FrameComTempo> {
    val de = limites.inicioMs - parametros.preRollMs
    val ate = limites.fimDoMovimentoMs + parametros.posRollMs
    return janela
        .filter { it.tsMs in de..ate }
        .map { f -> FrameComTempo(f.tsMs, Array(f.pontos.size) { i -> f.pontos[i].copyOf() }) }
  }
}

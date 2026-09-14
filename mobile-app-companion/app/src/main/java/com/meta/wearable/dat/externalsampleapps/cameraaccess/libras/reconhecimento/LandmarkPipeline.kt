/*
 * Libras Livre — pipeline de reconhecimento no app (o "onde a IA entra").
 *
 * Liga o vídeo dos óculos ao classificador local. O caminho de um sinal:
 *
 *   frames HEVC (handleVideoFrame)
 *     -> HevcDecoder DEDICADO renderiza para um ImageReader (o preview segue no decoder original)
 *     -> android.media.Image (YUV) -> ARGB -> MediaPipe Pose+Hands (LandmarkExtractor)
 *     -> LandmarkNormalizer (normaliza por ombros — 57 pontos × 3 coordenadas)
 *     -> Segmentador (detector de fronteiras + recorte com margem de repouso, 1.1–1.6)
 *     -> a cada segmento: HandGapImputer na linha do tempo real (2.3)
 *        -> SignClassifier.classify() (reamostra pelo tempo, 2.2) -> onRecognized(classificação)
 *
 * startSession()/endSession() delimitam a SESSÃO de captura inteira (vários sinais), abertas pelo
 * DialogOrchestrator.
 *
 * Custo: o MediaPipe só roda ENQUANTO a sessão está aberta. Fora disso o ImageReader é drenado sem
 * inferência, então o overhead em repouso é só o do segundo decode.
 *
 * Ciclo de vida do MediaPipe (docs/prontidao-demo/03-captura-e-landmarks.md §3.1, defeito A):
 * carregado UMA VEZ por [carregarModelos], ao abrir o app e fora da thread de frames; fica ocioso
 * na memória entre as capturas e só fecha em [dispose].
 *
 * Diagnóstico (1.9, 3.8, 6.5): cada frame processado vai para [onFrameProcessado] (gravador de
 * sessão), os eventos de segmento e classificação para [onEvento], e as contagens e tempos para
 * [metricas]. Tudo opcional; sem ouvinte, nada é montado.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Etapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.FrameProcessado
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Metricas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Estado do reconhecimento, observado pela UI (via CameraViewModel). */
data class LibrasState(
    val modelsReady: Boolean = false,
    val isCollecting: Boolean = false,
    // O primeiro frame normalizável da sessão (pose com os dois ombros) chegou (3.1). Antes dele,
    // quem sinaliza sinaliza para ninguém: o stream ainda está subindo ou o tronco não está no
    // quadro.
    val podeSinalizar: Boolean = false,
    // 1.11: "● sinalizando / ○ parado" e quantos sinais a sessão já capturou.
    val estadoSinalizacao: EstadoSinalizacao = EstadoSinalizacao.PARADO,
    val sinaisNaSessao: Int = 0,
    // 3.5: tronco fora do quadro / ninguém no quadro.
    val enquadramento: Enquadramento = Enquadramento.OK,
    val isClassifying: Boolean = false,
    val lastResult: String? = null,
    val error: String? = null,
)

class LandmarkPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val classifier: SignClassifier,
    private val onState: (LibrasState.() -> LibrasState) -> Unit,
    // Quem fala o resultado (e quando) é decisão do DialogOrchestrator. Dispara UMA VEZ POR SINAL.
    private val onRecognized: (Classificacao) -> Unit,
    private val onRecognitionFailed: () -> Unit = {},
    // Lidos a cada sessão: a edição nas configurações de demo (1.8) vale na próxima captura.
    private val parametros: () -> ParametrosSegmentacao = { ParametrosSegmentacao() },
    private val metricas: Metricas? = null,
    // Chamado na thread de frames: quem recebe precisa só enfileirar (o gravador faz isso).
    private val onFrameProcessado: ((FrameProcessado) -> Unit)? = null,
    private val onEvento: (nome: String, detalhe: String) -> Unit = { _, _ -> },
    // Mudanças de estado do detector (SINALIZANDO/PARADO), para o fim de frase automático do 4.1.
    // Chamado na thread de frames, só quando o estado muda.
    private val onEstadoSinalizacao: (EstadoSinalizacao) -> Unit = {},
) {

  companion object {
    private const val TAG = "Libras:Pipeline"
    private const val MAX_IMAGES = 3
    private const val MIN_FRAMES_PARA_CLASSIFICAR = 5
    // Quanto o dispose() espera a thread de frames sair de uma extração antes de fechar o MediaPipe.
    private const val JOIN_THREAD_MS = 1_000L
    const val ERRO_MODELOS = "Modelos do MediaPipe não carregaram — veja libras/README.md (assets)."
  }

  private var decoder: HevcDecoder? = null
  private var imageReader: ImageReader? = null
  private var readerThread: HandlerThread? = null
  // Threads de frames de streams anteriores, já com quitSafely(): o dispose() espera cada uma
  // terminar antes de fechar o extrator que ela pode estar usando.
  private val threadsEncerradas = mutableListOf<HandlerThread>()
  // "Fila do decodificador cheia" dos decoders de streams anteriores (3.8).
  @Volatile private var filaCheiaAcumulada = 0

  // Escrito pela coroutine de carregamento, lido pela thread de frames.
  @Volatile private var extractor: LandmarkExtractor? = null
  private val carregamento = Mutex()
  @Volatile private var descartado = false

  private var frameW = 0
  private var frameH = 0

  @Volatile private var collecting = false
  @Volatile private var aguardandoPrimeiroFrame = false
  // Último estado do detector repassado a onEstadoSinalizacao; null força repassar o próximo (início
  // de sessão, volta de pausa).
  @Volatile private var estadoInformado: EstadoSinalizacao? = null

  // 3.5: frames descartados por falta de ombros ou de pose, na última janela de 1 s.
  private val janelaEnquadramento = JanelaEnquadramento()
  @Volatile private var enquadramentoInformado = Enquadramento.OK
  // 1.11: segmentos entregues ao classificador na sessão.
  @Volatile private var sinaisNaSessao = 0

  /**
   * Frames que passaram pelo MediaPipe na sessão atual, normalizáveis ou não. Zera a cada
   * [startSession]. É a prova de que a sessão coleta (3.1) e a base das contagens do 3.5/3.8.
   */
  @Volatile var framesExtraidosNaSessao = 0
    private set

  // Guarda segmentador/classificacoesEmVoo — escritos pela thread do ImageReader e trocados por
  // startSession()/endSession(). Reentrante: o segmento é entregue de dentro de onFrame, já sob o lock.
  private val sessionLock = Any()
  private var segmentador: Segmentador? = null
  private val classificacoesEmVoo = mutableListOf<Job>()

  // Modo VIDEO do MediaPipe exige timestamps estritamente crescentes por detector,
  // inclusive ENTRE capturas — por isso um relógio monotônico que nunca reinicia.
  @Volatile private var lastTsMs = 0L

  /** Total de "fila do decodificador cheia" do decoder de inferência, desde que o app abriu. */
  val filaCheiaDecoder: Int
    get() = filaCheiaAcumulada + (decoder?.vezesFilaCheia ?: 0)

  /**
   * Carrega o MediaPipe, se ainda não estiver carregado. Idempotente; roda em
   * [Dispatchers.Default], nunca na thread de frames. Devolve false se os modelos não carregaram
   * — o erro vai para [LibrasState.error] já aqui, e não no meio de uma sessão.
   */
  suspend fun carregarModelos(): Boolean =
      carregamento.withLock {
        if (extractor != null) return@withLock true
        withContext(Dispatchers.Default) {
          runCatching { LandmarkExtractor(context) }
              .onSuccess { criado ->
                if (descartado) {
                  criado.close()
                } else {
                  extractor = criado
                  onState { copy(modelsReady = true, error = if (error == ERRO_MODELOS) null else error) }
                }
              }
              .onFailure {
                Log.e(TAG, "Não consegui criar o LandmarkExtractor (modelos ausentes?)", it)
                onState { copy(modelsReady = false, error = ERRO_MODELOS) }
              }
              .isSuccess
        }
      }

  /**
   * Recebe um frame HEVC comprimido do stream (chamado de handleVideoFrame). Cria o
   * decoder/ImageReader de inferência na primeira vez, com as dimensões e o CSD do stream.
   */
  fun feedCompressedFrame(bytes: ByteArray, presentationTimeUs: Long, width: Int, height: Int,
                          config: ByteArray?) {
    ensurePipeline(width, height, config) ?: return
    decoder?.decodeFrame(bytes, presentationTimeUs)
  }

  /**
   * Abre uma sessão: passa a acumular sinais (um ou mais) até [endSession]. Liga a coleta mesmo
   * sem nenhum frame ainda — é o caso normal, porque o stream fica STREAMING antes do primeiro
   * frame. [LibrasState.podeSinalizar] vira true no primeiro frame normalizável.
   */
  fun startSession() {
    synchronized(sessionLock) {
      segmentador =
          Segmentador(
              parametros = parametros(),
              onSegmento = ::onSegmento,
              onDescartado = { limites ->
                Log.d(TAG, "Movimento de ${limites.duracaoMovimentoMs} ms descartado (espasmo)")
                onEvento("descartado", descricao(limites))
              },
          )
    }
    aguardandoPrimeiroFrame = true
    estadoInformado = null
    enquadramentoInformado = Enquadramento.OK
    sinaisNaSessao = 0
    synchronized(sessionLock) { janelaEnquadramento.reiniciar() }
    framesExtraidosNaSessao = 0
    collecting = true
    onState {
      copy(isCollecting = true, podeSinalizar = false, lastResult = null,
          estadoSinalizacao = EstadoSinalizacao.PARADO, sinaisNaSessao = 0, enquadramento = Enquadramento.OK,
          error = if (error == ERRO_MODELOS || error?.startsWith(ModeloRecusado.PREFIXO) == true) error else null)
    }
    // O aquecimento ainda não terminou, ou falhou: tenta de novo, fora desta thread. Frames que
    // chegarem antes são drenados sem extração.
    if (extractor == null) scope.launch { carregarModelos() }
  }

  /**
   * Fecha a sessão. Se havia um sinal em curso, força o fechamento dele (com a duração mínima do
   * 1.5) antes de retornar. Suspende até TODAS as classificações disparadas durante a sessão
   * terminarem — é assim que quem chama sabe que já pode ler a lista completa de sinais.
   */
  suspend fun endSession() {
    collecting = false
    aguardandoPrimeiroFrame = false
    synchronized(sessionLock) { segmentador?.forcarFechamento() }
    onState { copy(isCollecting = false, podeSinalizar = false, enquadramento = Enquadramento.OK) }
    val pendentes = synchronized(sessionLock) { classificacoesEmVoo.toList() }
    pendentes.joinAll()
  }

  // Chamado de dentro de onFrame (thread de frames, sob o sessionLock) ou de endSession.
  private fun onSegmento(frames: List<FrameComTempo>, limites: LimitesSegmento) {
    metricas?.marcar(Etapa.FIM_MOVIMENTO_SEGMENTO, SystemClock.uptimeMillis() - limites.fimDoMovimentoMs)
    onEvento("segmento", descricao(limites) + ",frames=${frames.size}")
    if (frames.size < MIN_FRAMES_PARA_CLASSIFICAR) {
      Log.d(TAG, "Segmento com ${frames.size} frames — curto demais, ignorado")
      return
    }
    val sinais = ++sinaisNaSessao
    onState { copy(sinaisNaSessao = sinais) }
    onState { copy(isClassifying = true, error = null) }
    val job = scope.launch {
      val resultado =
          withContext(Dispatchers.Default) {
            runCatching {
              // 2.3: imputa as mãos no segmento recortado, na linha do tempo real, antes de o
              // classificador reamostrar. Os frames são cópias (Segmentador), então podem mudar.
              val imputador = HandGapImputer()
              frames.forEach { imputador.offer(it.pontos) }
              val inicio = SystemClock.elapsedRealtime()
              val c = classifier.classify(SegmentoSinal(imputador.snapshot(), LongArray(frames.size) { frames[it].tsMs }))
              metricas?.marcar(Etapa.CLASSIFICACAO, SystemClock.elapsedRealtime() - inicio, "glosa=${c.glosa}")
              c
            }
          }
      resultado
          .onSuccess { c ->
            onEvento("classificacao", "glosa=${c.glosa},confianca=${c.confianca},margem=${c.margem}")
            onState { copy(isClassifying = false, lastResult = c.glosa, error = null) }
            onRecognized(c)
          }
          .onFailure { e ->
            Log.e(TAG, "Falha na classificação", e)
            onEvento("falha_classificacao", e.message ?: e.javaClass.simpleName)
            onState { copy(isClassifying = false, error = e.message ?: "Falha ao classificar") }
            onRecognitionFailed()
          }
    }
    synchronized(sessionLock) { classificacoesEmVoo.add(job) }
    job.invokeOnCompletion { synchronized(sessionLock) { classificacoesEmVoo.remove(job) } }
  }

  /**
   * O stream voltou de uma pausa dos óculos (toque na haste, 3.2). O detector desconta o intervalo
   * parado — a pausa não fecha o sinal em andamento nem conta como inatividade — e o indicador volta a
   * "aguarde o primeiro frame válido".
   */
  fun retomarDepoisDePausa() {
    synchronized(sessionLock) {
      segmentador?.descontarPausa()
      janelaEnquadramento.reiniciar()
    }
    if (!collecting) return
    aguardandoPrimeiroFrame = true
    estadoInformado = null
    enquadramentoInformado = Enquadramento.OK
    onState { copy(podeSinalizar = false, enquadramento = Enquadramento.OK) }
  }

  private fun descricao(l: LimitesSegmento) =
      "inicio=${l.inicioMs},fim_movimento=${l.fimDoMovimentoMs},duracao_movimento=${l.duracaoMovimentoMs},motivo=${l.motivo}"

  /**
   * Libera decoder, ImageReader e thread. Chamar ao parar o STREAM, que reinicia a cada turno.
   * NÃO fecha o MediaPipe nem o [classifier]: os dois vivem o app inteiro (ver [dispose]).
   */
  fun stop() {
    collecting = false
    decoder?.let { filaCheiaAcumulada += it.vezesFilaCheia }
    decoder?.stop()
    decoder = null
    imageReader?.close()
    imageReader = null
    readerThread?.let {
      it.quitSafely()
      threadsEncerradas.removeAll { t -> !t.isAlive }
      threadsEncerradas.add(it)
    }
    readerThread = null
    frameW = 0
    frameH = 0
  }

  /** Teardown final — chamar só quando o pipeline inteiro vai embora (ex.: onCleared do ViewModel). */
  fun dispose() {
    descartado = true
    stop()
    // Fechar o MediaPipe no meio de uma extração pode derrubar o processo (mapa de riscos §2.4):
    // espera as threads de frames saírem antes.
    threadsEncerradas.forEach { it.join(JOIN_THREAD_MS) }
    threadsEncerradas.clear()
    extractor?.close()
    extractor = null
    classifier.close()
  }

  /**
   * Cria (uma vez por stream) o ImageReader + HandlerThread + HevcDecoder. Devolve null só se as
   * dimensões ainda não chegaram; o extrator não é pré-condição (frames sem ele são drenados).
   */
  private fun ensurePipeline(width: Int, height: Int, config: ByteArray?): Unit? {
    if (decoder != null) return Unit
    if (width <= 0 || height <= 0) return null

    frameW = width
    frameH = height

    val thread = HandlerThread("LibrasLandmarkThread").also { it.start() }
    readerThread = thread
    val handler = Handler(thread.looper)

    val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES)
    reader.setOnImageAvailableListener({ r ->
      // acquireLatestImage descarta frames intermediários se a inferência não acompanhar
      // o frame rate — subamostragem natural; a reamostragem pelo tempo (2.2) absorve.
      val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
      metricas?.frameDecodificado()
      try {
        val ex = extractor
        if (collecting && ex != null) processar(ex, image)
      } catch (e: Throwable) {
        Log.e(TAG, "Erro extraindo/normalizando landmarks do frame", e)
      } finally {
        image.close()
      }
    }, handler)
    imageReader = reader

    decoder =
        HevcDecoder().also { d ->
          d.start(width, height, reader.surface)
          // Prime com o CSD acumulado (VPS/SPS/PPS), como o decoder de preview faz — assim
          // um decoder criado no meio do stream ativa no próximo keyframe.
          config?.let { d.decodeFrame(it, 0) }
        }
    return Unit
  }

  private fun processar(ex: LandmarkExtractor, image: android.media.Image) {
    val ts = nextTimestampMs()
    val fl = ex.extract(image, ts)
    framesExtraidosNaSessao++
    val normalizado = fl?.let { LandmarkNormalizer.normalize(it, frameW, frameH) }
    metricas?.frameProcessado(comPose = normalizado != null)
    val resultadoFrame =
        when {
          fl == null -> ResultadoFrame.SEM_POSE
          normalizado == null -> ResultadoFrame.SEM_OMBROS
          else -> ResultadoFrame.NORMALIZADO
        }
    val enquadramento = synchronized(sessionLock) { janelaEnquadramento.registrar(ts, resultadoFrame) }
    if (enquadramento != enquadramentoInformado) {
      enquadramentoInformado = enquadramento
      onEvento("enquadramento", enquadramento.name)
      onState { copy(enquadramento = enquadramento) }
    }
    if (normalizado != null && aguardandoPrimeiroFrame) {
      aguardandoPrimeiroFrame = false
      metricas?.marcarDesdeOInicio(Etapa.INICIAR_PODE_SINALIZAR, SystemClock.uptimeMillis())
      onState { copy(podeSinalizar = true) }
    }
    val seg: Segmentador?
    synchronized(sessionLock) {
      seg = segmentador
      if (normalizado != null) seg?.onFrame(normalizado, ts)
    }
    val estadoAgora = seg?.estadoAtual
    if (estadoAgora != null && estadoAgora != estadoInformado) {
      estadoInformado = estadoAgora
      onState { copy(estadoSinalizacao = estadoAgora) }
      onEstadoSinalizacao(estadoAgora)
    }
    onFrameProcessado?.invoke(
        FrameProcessado(
            tsMs = ts,
            estado = if (normalizado != null) seg?.estadoAtual else null,
            medicao = if (normalizado != null) seg?.ultimaMedicao else null,
            pose = normalizado != null,
            maoEsq = fl?.leftHand != null,
            maoDir = fl?.rightHand != null,
            pontos = normalizado,
        ))
  }

  private fun nextTimestampMs(): Long {
    val now = SystemClock.uptimeMillis()
    val ts = if (now > lastTsMs) now else lastTsMs + 1
    lastTsMs = ts
    return ts
  }
}

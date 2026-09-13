/*
 * Libras Livre — pipeline de reconhecimento no app (o "onde a IA entra").
 *
 * Liga o vídeo dos óculos ao classificador local, reusando peças que já existem no app. O
 * caminho de um sinal:
 *
 *   frames HEVC (handleVideoFrame)
 *     -> HevcDecoder DEDICADO renderiza para um ImageReader (o preview segue no
 *        decoder original; este é um segundo decoder só para inferência)
 *     -> android.media.Image (YUV) -> MediaPipe Pose+Hands (LandmarkExtractor)
 *     -> LandmarkNormalizer (normaliza por ombros — 57 pontos x 2 canais)
 *     -> HandGapImputer (acumula o segmento em curso, preenche lacunas curtas de mão)
 *     -> SignBoundaryDetector (decide onde cada sinal começa/termina)
 *     -> a cada boundary: SignClassifier.classify() -> onRecognized(palavra)
 *
 * A SEGMENTAÇÃO de cada sinal individual é automática (SignBoundaryDetector — ver
 * docs/sign-boundary-detector-plano.md). startSession()/endSession() delimitam a SESSÃO
 * inteira (pode ter vários sinais), disparados pela wake word via DialogOrchestrator — não
 * mais um sinal por vez.
 *
 * Custo: o MediaPipe (caro) só roda ENQUANTO a sessão está aberta. Fora disso o ImageReader
 * é drenado sem inferência, então o overhead em repouso é só o do segundo decode.
 *
 * Ciclo de vida do MediaPipe (docs/prontidao-demo/03-captura-e-landmarks.md §3.1, defeito A):
 * carregado UMA VEZ por [carregarModelos], ao abrir o app e fora da thread de frames; fica ocioso
 * na memória entre as capturas e só fecha em [dispose]. Antes ele nascia no primeiro frame e
 * fechava a cada fim de stream, e como o "iniciar" chega com o stream STREAMING — antes do
 * primeiro frame —, a sessão encontrava o extrator nulo e não coletava nada a partir do segundo
 * turno.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
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
    val isClassifying: Boolean = false,
    val lastResult: String? = null,
    val error: String? = null,
)

class LandmarkPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val classifier: SignClassifier,
    private val onState: (LibrasState.() -> LibrasState) -> Unit,
    // Quem fala o resultado (e quando) é decisão do DialogOrchestrator, não deste pipeline —
    // ver docs/orquestracao-dialogo-audio-plano.md §6.5. Dispara UMA VEZ POR SINAL (boundary)
    // agora, não mais uma vez por sessão inteira — ver docs/sign-boundary-detector-plano.md §5.3.
    private val onRecognized: (sinal: String) -> Unit,
    private val onRecognitionFailed: () -> Unit = {},
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

  // Escrito pela coroutine de carregamento, lido pela thread de frames.
  @Volatile private var extractor: LandmarkExtractor? = null
  private val carregamento = Mutex()
  @Volatile private var descartado = false

  private var frameW = 0
  private var frameH = 0

  @Volatile private var collecting = false
  @Volatile private var aguardandoPrimeiroFrame = false

  /**
   * Frames que passaram pelo MediaPipe na sessão atual, normalizáveis ou não. Zera a cada
   * [startSession]. É a prova de que a sessão coleta (3.1) e a base das contagens do 3.5/3.8.
   */
  @Volatile var framesExtraidosNaSessao = 0
    private set

  // Guarda handGapImputer/boundaryDetector/classificacoesEmVoo — escritos pela thread do
  // ImageReader (onFrame) e lidos/trocados por startSession()/endSession() (chamados da
  // coroutine do DialogOrchestrator). Reentrante: onBoundary (chamado de dentro de onFrame,
  // já sob o lock) pode chamar classificarSegmentoAtual(), que toma o lock de novo.
  private val sessionLock = Any()
  private var handGapImputer = HandGapImputer()
  private var boundaryDetector: SignBoundaryDetector? = null
  private val classificacoesEmVoo = mutableListOf<Job>()

  // Modo VIDEO do MediaPipe exige timestamps estritamente crescentes por detector,
  // inclusive ENTRE capturas — por isso um relógio monotônico que nunca reinicia.
  @Volatile private var lastTsMs = 0L

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
      handGapImputer = HandGapImputer()
      boundaryDetector = SignBoundaryDetector(onBoundary = ::classificarSegmentoAtual)
    }
    aguardandoPrimeiroFrame = true
    framesExtraidosNaSessao = 0
    collecting = true
    onState {
      copy(isCollecting = true, podeSinalizar = false, lastResult = null,
          error = if (error == ERRO_MODELOS) error else null)
    }
    // O aquecimento ainda não terminou, ou falhou: tenta de novo, fora desta thread. Frames que
    // chegarem antes são drenados sem extração.
    if (extractor == null) scope.launch { carregarModelos() }
  }

  /**
   * Fecha a sessão. Se havia um sinal em curso (segmento em aberto, sem boundary ainda),
   * força a classificação dele antes de retornar — não perde silenciosamente o último sinal
   * (docs/sign-boundary-detector-plano.md §5.3). Suspende até TODAS as classificações
   * disparadas durante a sessão (a forçada aqui, e quaisquer outras ainda em voo) terminarem
   * — é assim que quem chama sabe que já pode ler a lista completa de sinais reconhecidos.
   */
  suspend fun endSession() {
    collecting = false
    aguardandoPrimeiroFrame = false
    synchronized(sessionLock) { boundaryDetector?.forcarFechamento() }
    onState { copy(isCollecting = false, podeSinalizar = false) }
    val pendentes = synchronized(sessionLock) { classificacoesEmVoo.toList() }
    pendentes.joinAll()
  }

  private fun classificarSegmentoAtual() {
    val frames: List<Array<FloatArray>>
    synchronized(sessionLock) {
      frames = handGapImputer.snapshot()
      handGapImputer = HandGapImputer() // o próximo segmento da sessão começa vazio
    }
    if (frames.size < MIN_FRAMES_PARA_CLASSIFICAR) {
      Log.d(TAG, "Segmento com ${frames.size} frames — curto demais, ignorado")
      return
    }
    onState { copy(isClassifying = true, error = null) }
    val job = scope.launch {
      val resultado = withContext(Dispatchers.Default) { runCatching { classifier.classify(frames) } }
      resultado
          .onSuccess { sinal ->
            onState { copy(isClassifying = false, lastResult = sinal, error = null) }
            onRecognized(sinal)
          }
          .onFailure { e ->
            Log.e(TAG, "Falha na classificação", e)
            onState { copy(isClassifying = false, error = e.message ?: "Falha ao classificar") }
            onRecognitionFailed()
          }
    }
    synchronized(sessionLock) { classificacoesEmVoo.add(job) }
    job.invokeOnCompletion { synchronized(sessionLock) { classificacoesEmVoo.remove(job) } }
  }

  /**
   * Libera decoder, ImageReader e thread. Chamar ao parar o STREAM, que reinicia a cada turno.
   * NÃO fecha o MediaPipe nem o [classifier]: os dois vivem o app inteiro (ver [dispose]).
   */
  fun stop() {
    collecting = false
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
      // o frame rate — subamostragem natural; o classificador lida com sequências de
      // tamanho variável.
      val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
      try {
        val ex = extractor
        if (collecting && ex != null) {
          val ts = nextTimestampMs()
          val fl = ex.extract(image, ts)
          framesExtraidosNaSessao++
          val normalizado = fl?.let { LandmarkNormalizer.normalize(it, frameW, frameH) }
          if (normalizado != null) {
            if (aguardandoPrimeiroFrame) {
              aguardandoPrimeiroFrame = false
              onState { copy(podeSinalizar = true) }
            }
            synchronized(sessionLock) {
              handGapImputer.offer(normalizado)
              boundaryDetector?.onFrame(normalizado, ts)
            }
          }
        }
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

  private fun nextTimestampMs(): Long {
    val now = SystemClock.uptimeMillis()
    val ts = if (now > lastTsMs) now else lastTsMs + 1
    lastTsMs = ts
    return ts
  }
}

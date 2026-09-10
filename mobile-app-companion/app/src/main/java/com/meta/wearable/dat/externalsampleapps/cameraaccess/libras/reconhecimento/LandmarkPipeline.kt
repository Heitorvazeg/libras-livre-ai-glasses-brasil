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
import kotlinx.coroutines.withContext

/** Estado do reconhecimento, observado pela UI (via CameraViewModel). */
data class LibrasState(
    val modelsReady: Boolean = false,
    val isCollecting: Boolean = false,
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
  }

  private var decoder: HevcDecoder? = null
  private var imageReader: ImageReader? = null
  private var readerThread: HandlerThread? = null
  private var extractor: LandmarkExtractor? = null

  private var frameW = 0
  private var frameH = 0

  @Volatile private var collecting = false

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
   * Recebe um frame HEVC comprimido do stream (chamado de handleVideoFrame). Cria o
   * decoder/ImageReader de inferência na primeira vez, com as dimensões e o CSD do stream.
   */
  fun feedCompressedFrame(bytes: ByteArray, presentationTimeUs: Long, width: Int, height: Int,
                          config: ByteArray?) {
    ensurePipeline(width, height, config) ?: return
    decoder?.decodeFrame(bytes, presentationTimeUs)
  }

  /** Abre uma sessão: passa a acumular sinais (um ou mais) até [endSession]. */
  fun startSession() {
    if (extractor == null) {
      onState { copy(error = "Modelos do MediaPipe não carregaram — veja libras/README.md (assets).") }
      return
    }
    synchronized(sessionLock) {
      handGapImputer = HandGapImputer()
      boundaryDetector = SignBoundaryDetector(onBoundary = ::classificarSegmentoAtual)
    }
    collecting = true
    onState { copy(isCollecting = true, error = null, lastResult = null) }
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
    synchronized(sessionLock) { boundaryDetector?.forcarFechamento() }
    onState { copy(isCollecting = false) }
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
   * Libera decoder, ImageReader, thread e modelos. Chamar ao parar o STREAM — não fecha o
   * [classifier] aqui de propósito: o stream (e portanto stop()) pode reiniciar várias vezes
   * na vida do pipeline (reconexão dos óculos etc.), mas o classificador é o mesmo pelo tempo
   * todo. Ver [dispose] para o teardown final, chamado só uma vez.
   */
  fun stop() {
    collecting = false
    decoder?.stop()
    decoder = null
    imageReader?.close()
    imageReader = null
    readerThread?.quitSafely()
    readerThread = null
    extractor?.close()
    extractor = null
    frameW = 0
    frameH = 0
  }

  /** Teardown final — chamar só quando o pipeline inteiro vai embora (ex.: onCleared do ViewModel). */
  fun dispose() {
    stop()
    classifier.close()
  }

  /**
   * Cria (uma vez) o ImageReader + HandlerThread + HevcDecoder + LandmarkExtractor.
   * Devolve non-null quando o pipeline está pronto; null se os modelos não carregaram.
   */
  private fun ensurePipeline(width: Int, height: Int, config: ByteArray?): Unit? {
    if (decoder != null) return Unit
    if (width <= 0 || height <= 0) return null

    // Extrator primeiro: se os modelos não estão nos assets, nem monta o resto.
    if (extractor == null) {
      extractor =
          runCatching { LandmarkExtractor(context) }
              .onFailure {
                Log.e(TAG, "Não consegui criar o LandmarkExtractor (modelos ausentes?)", it)
                onState {
                  copy(modelsReady = false,
                      error = "Modelos do MediaPipe não encontrados em assets/ — ver libras/README.md.")
                }
              }
              .getOrNull() ?: return null
      onState { copy(modelsReady = true) }
    }

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
        if (collecting) {
          val ts = nextTimestampMs()
          val fl = extractor?.extract(image, ts)
          val normalizado = fl?.let { LandmarkNormalizer.normalize(it, frameW, frameH) }
          if (normalizado != null) {
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

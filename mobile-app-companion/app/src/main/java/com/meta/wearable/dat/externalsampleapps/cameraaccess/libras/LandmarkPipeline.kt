/*
 * Libras Livre — pipeline de reconhecimento no app (o "onde a IA entra").
 *
 * Liga o vídeo dos óculos ao classificador da PoC via API, reusando peças que já
 * existem no app. O caminho de um sinal:
 *
 *   frames HEVC (handleVideoFrame)
 *     -> HevcDecoder DEDICADO renderiza para um ImageReader (o preview segue no
 *        decoder original; este é um segundo decoder só para inferência)
 *     -> android.media.Image (YUV) -> MediaPipe Pose+Hands (LandmarkExtractor)
 *     -> acumula os frames do sinal enquanto o usuário está "capturando"
 *     -> POST /classify (LandmarkApi) -> palavra -> TextToSpeech (Speaker)
 *
 * A SEGMENTAÇÃO (onde um sinal começa/termina) é manual nesta fase: o usuário
 * toca "capturar", sinaliza, toca "parar" — igual ao record.py da PoC. Detecção
 * automática de pausa é trabalho futuro; começar manual tira essa variável da
 * validação da integração.
 *
 * Custo: o MediaPipe (caro) só roda ENQUANTO se captura um sinal. Fora disso o
 * ImageReader é drenado sem inferência, então o overhead em repouso é só o do
 * segundo decode.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado do reconhecimento, observado pela UI (via CameraViewModel). */
data class LibrasState(
    val modelsReady: Boolean = false,
    val isCollecting: Boolean = false,
    val isClassifying: Boolean = false,
    val lastResult: ClassifyResult? = null,
    val error: String? = null,
)

class LandmarkPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: LandmarkApi,
    private val speaker: Speaker,
    private val onState: (LibrasState.() -> LibrasState) -> Unit,
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
  private val collected = ArrayList<FrameLandmarks>()
  private val collectLock = Any()

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

  /** Começa a acumular frames de um sinal. */
  fun startCollecting() {
    if (extractor == null) {
      onState { copy(error = "Modelos do MediaPipe não carregaram — veja libras/README.md (assets).") }
      return
    }
    synchronized(collectLock) { collected.clear() }
    collecting = true
    onState { copy(isCollecting = true, error = null, lastResult = null) }
  }

  /** Para de acumular e classifica o sinal capturado via API. */
  fun stopCollectingAndClassify() {
    if (!collecting) return
    collecting = false
    val frames: List<FrameLandmarks>
    synchronized(collectLock) { frames = ArrayList(collected) }
    onState { copy(isCollecting = false) }

    if (frames.size < MIN_FRAMES_PARA_CLASSIFICAR) {
      onState {
        copy(error = "Clipe curto demais (${frames.size} frames com pose). Enquadre o tronco e as " +
            "mãos e capture de novo.")
      }
      return
    }

    val w = frameW
    val h = frameH
    onState { copy(isClassifying = true, error = null) }
    scope.launch {
      val resultado = withContext(Dispatchers.IO) {
        runCatching { api.classify(w, h, frames) }
      }
      resultado
          .onSuccess { r ->
            onState { copy(isClassifying = false, lastResult = r, error = null) }
            speaker.speak(r.sinal)
          }
          .onFailure { e ->
            Log.e(TAG, "Falha na classificação", e)
            onState { copy(isClassifying = false, error = e.message ?: "Falha ao classificar") }
          }
    }
  }

  /** Libera decoder, ImageReader, thread e modelos. Chamar ao parar o stream. */
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
      // o frame rate — subamostragem natural; o DTW lida com sequências de tamanho variável.
      val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
      try {
        if (collecting) {
          val ts = nextTimestampMs()
          val fl = extractor?.extract(image, ts)
          if (fl != null) synchronized(collectLock) { collected.add(fl) }
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Erro extraindo landmarks do frame", e)
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

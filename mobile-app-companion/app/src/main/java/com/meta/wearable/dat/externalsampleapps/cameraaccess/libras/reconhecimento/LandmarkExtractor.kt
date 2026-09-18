/*
 * Libras Livre — extração de landmarks com MediaPipe Tasks (Pose + Hands).
 *
 * Recebe um frame decodificado (android.media.Image, YUV_420_888, convertido para ARGB) e devolve
 * os pontos CRUS na convenção do pipeline de referência (computer-vision-model/PoC/src/extract.py):
 *   - pose: os 33 pontos do MediaPipe Pose, cada um [x, y, z, visibility] (0..1)
 *   - left_hand / right_hand: 21 pontos [x, y, z] cada, ou null se a mão não veio
 *
 * A PoC gerou as referências com MediaPipe Holistic; aqui usamos os detectores
 * separados (Pose + Hands), que compartilham os MESMOS índices e convenção de
 * pontos. Ressalvas conhecidas (a validar com dado real):
 *   - com 2 poses, fica a de ombros mais afastados; das até 4 mãos, só as com o punho perto de um
 *     pulso dessa pose, e o lado vem da distância aos pulsos, não da handedness (AtribuicaoMaos);
 *   - Holistic e Pose+Hands podem divergir alguns pixels na detecção;
 *   - rotação do feed dos óculos é assumida como 0 (ajustar se vier girado).
 *
 * Requer dois modelos em app/src/main/assets/ (ver libras/README.md):
 *   pose_landmarker_lite.task   e   hand_landmarker.task
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.nio.ByteBuffer
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class LandmarkExtractor(context: Context) {

  companion object {
    private const val TAG = "Libras:LandmarkExtractor"
    private const val POSE_MODEL = "pose_landmarker_lite.task"
    private const val HAND_MODEL = "hand_landmarker.task"
    private const val N_POSE = 33
    private const val N_HAND = 21
    private const val PULSO_ESQ = 15
    private const val PULSO_DIR = 16
    private const val OMBRO_ESQ = 11
    private const val OMBRO_DIR = 12
    // 3.6: mais pessoas e mãos detectadas, para o filtro escolher. Com 2 mãos, as do atendente podiam
    // ocupar as vagas e esconder as da pessoa surda. Se pesar no painel (3.8), voltar a 2 mãos
    // mantendo o filtro.
    private const val MAX_POSES = 2
    private const val MAX_MAOS = 4
  }

  // Delegate do MediaPipe. GPU dá ~22 fps de inferência (contra ~5 em CPU) e resolveria a
  // segmentação, MAS a criação dos modelos em GPU custa ~55 s NESTE aparelho (Galaxy A57) — e a
  // cada lançamento, não é cache. Inaceitável no warmup. Fica desligado por padrão, atrás deste
  // flag, até termos um init em segundo plano (ou um device onde o custo seja aceitável).
  // Medições em docs/ / conversa 2026-09-18.
  private val usarGpu = false

  private fun <T> criarLandmarker(nome: String, cria: (Delegate) -> T): T {
    if (usarGpu) {
      try {
        return cria(Delegate.GPU).also { Log.i(TAG, "$nome em GPU") }
      } catch (e: Throwable) {
        Log.w(TAG, "$nome: GPU indisponível ($e) — usando CPU")
      }
    }
    return cria(Delegate.CPU).also { Log.i(TAG, "$nome em CPU") }
  }

  private val poseLandmarker: PoseLandmarker =
      criarLandmarker("pose") { delegate ->
        PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(POSE_MODEL).setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(MAX_POSES)
                .build(),
        )
      }

  private val handLandmarker: HandLandmarker =
      criarLandmarker("maos") { delegate ->
        HandLandmarker.createFromOptions(
            context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(HAND_MODEL).setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(MAX_MAOS)
                .build(),
        )
      }

  // Buffers reaproveitados entre frames (a thread de frames é uma só): o frame YUV é copiado em
  // bloco para arrays e convertido para um Bitmap ARGB — ver YuvParaArgb para o porquê.
  private var planoY = ByteArray(0)
  private var planoU = ByteArray(0)
  private var planoV = ByteArray(0)
  private var argb = IntArray(0)
  private var bitmap: Bitmap? = null

  /**
   * Extrai os landmarks de um frame. Devolve null se não houver pose confiável (sem
   * tronco não há como o servidor normalizar — mesmo critério da PoC, aplicado lá).
   *
  * @param image frame YUV_420_888; NÃO é fechado aqui. O chamador mantém tanto a Image quanto
  * seu ImageReader abertos até o retorno e serializa extract/close inclusive entre streams.
   * @param timestampMs carimbo monotônico crescente exigido pelo modo VIDEO.
   */
  fun extract(image: Image, timestampMs: Long): FrameLandmarks? {
    val mpImage = BitmapImageBuilder(paraBitmap(image)).build()

    val largura = image.width.toFloat()
    val altura = image.height.toFloat()
    fun px(p: FloatArray) = AtribuicaoMaos.Ponto(p[0] * largura, p[1] * altura)

    val poseResult: PoseLandmarkerResult = poseLandmarker.detectForVideo(mpImage, timestampMs)
    val poses =
        poseResult.landmarks()
            .map { pessoa -> pessoa.map { lm -> floatArrayOf(lm.x(), lm.y(), lm.z(), lm.visibility().orElse(0f)) } }
            .filter { it.size == N_POSE }
    if (poses.isEmpty()) return null // sem pessoa/tronco: frame não normalizável

    // 3.6: das pessoas no quadro, a de ombros mais afastados é a mais próxima da câmera.
    val pose = poses[AtribuicaoMaos.escolherPose(poses.map { px(it[OMBRO_ESQ]) to px(it[OMBRO_DIR]) }) ?: 0]
    val pulsoEsq = px(pose[PULSO_ESQ])
    val pulsoDir = px(pose[PULSO_DIR])
    val larguraOmbros = AtribuicaoMaos.distancia(px(pose[OMBRO_ESQ]), px(pose[OMBRO_DIR]))

    val handResult: HandLandmarkerResult = handLandmarker.detectForVideo(mpImage, timestampMs)
    val detectadas =
        handResult.landmarks()
            .map { mao -> mao.map { lm -> floatArrayOf(lm.x(), lm.y(), lm.z()) } }
            .filter { it.size == N_HAND }
    // 3.6: só as mãos perto dos pulsos dessa pessoa (descarta as do atendente e as de quem passa).
    val maos =
        AtribuicaoMaos.filtrarPorPulso(detectadas.map { px(it[0]) }, pulsoEsq, pulsoDir, larguraOmbros)
            .map { detectadas[it] }

    // 2.4: o lado vem do pulso da pose mais próximo do punho (ponto 0), em pixels, e não do rótulo
    // de handedness do HandLandmarker — é o que o Holistic do treino faz.
    val lados = AtribuicaoMaos.atribuir(maos.map { px(it[0]) }, pulsoEsq, pulsoDir)

    return FrameLandmarks(
        pose = pose,
        leftHand = lados.esquerda?.let { maos[it] },
        rightHand = lados.direita?.let { maos[it] },
    )
  }

  private fun paraBitmap(image: Image): Bitmap {
    val largura = image.width
    val altura = image.height
    val (py, pu, pv) = image.planes
    planoY = copiar(py.buffer, planoY)
    planoU = copiar(pu.buffer, planoU)
    planoV = copiar(pv.buffer, planoV)
    if (argb.size != largura * altura) argb = IntArray(largura * altura)
    YuvParaArgb.converter(
        largura, altura, planoY, py.rowStride, planoU, planoV, pu.rowStride, pu.pixelStride, argb)
    val destino =
        bitmap?.takeIf { it.width == largura && it.height == altura }
            ?: Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888).also { bitmap = it }
    destino.setPixels(argb, 0, largura, 0, 0, largura, altura)
    return destino
  }

  // O último plano pode vir sem o preenchimento da última linha: o array é do tamanho do buffer.
  private fun copiar(buffer: ByteBuffer, reuso: ByteArray): ByteArray {
    val origem = buffer.duplicate().apply { rewind() }
    val destino = if (reuso.size == origem.remaining()) reuso else ByteArray(origem.remaining())
    origem.get(destino)
    return destino
  }

  fun close() {
    runCatching { poseLandmarker.close() }
    runCatching { handLandmarker.close() }
    bitmap?.recycle()
    bitmap = null
  }
}

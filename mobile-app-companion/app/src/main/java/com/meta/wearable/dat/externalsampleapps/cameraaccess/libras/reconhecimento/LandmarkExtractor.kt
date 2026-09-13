/*
 * Libras Livre — extração de landmarks com MediaPipe Tasks (Pose + Hands).
 *
 * Recebe um frame decodificado (android.media.Image, YUV_420_888) e devolve os
 * pontos CRUS na convenção do pipeline de referência (computer-vision-model/PoC/src/extract.py):
 *   - pose: os 33 pontos do MediaPipe Pose, cada um [x, y, z, visibility] (0..1)
 *   - left_hand / right_hand: 21 pontos [x, y, z] cada, ou null se a mão não veio
 *
 * A PoC gerou as referências com MediaPipe Holistic; aqui usamos os detectores
 * separados (Pose + Hands), que compartilham os MESMOS índices e convenção de
 * pontos. Como o pipeline descarta z e normaliza por ombros, o que
 * importa numericamente é x,y da pose/mãos e a visibility dos ombros — todos
 * diretamente comparáveis. Ressalvas conhecidas (a validar com dado real):
 *   - atribuição esquerda/direita das mãos vem da handedness do MediaPipe;
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
  }

  private val poseLandmarker: PoseLandmarker =
      PoseLandmarker.createFromOptions(
          context,
          PoseLandmarker.PoseLandmarkerOptions.builder()
              .setBaseOptions(BaseOptions.builder().setModelAssetPath(POSE_MODEL).build())
              .setRunningMode(RunningMode.VIDEO)
              .setNumPoses(1)
              .build(),
      )

  private val handLandmarker: HandLandmarker =
      HandLandmarker.createFromOptions(
          context,
          HandLandmarker.HandLandmarkerOptions.builder()
              .setBaseOptions(BaseOptions.builder().setModelAssetPath(HAND_MODEL).build())
              .setRunningMode(RunningMode.VIDEO)
              .setNumHands(2)
              .build(),
      )

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
   * @param image frame YUV_420_888; NÃO é fechado aqui (o chamador é dono do ciclo dele).
   * @param timestampMs carimbo monotônico crescente exigido pelo modo VIDEO.
   */
  fun extract(image: Image, timestampMs: Long): FrameLandmarks? {
    val mpImage = BitmapImageBuilder(paraBitmap(image)).build()

    val poseResult: PoseLandmarkerResult = poseLandmarker.detectForVideo(mpImage, timestampMs)
    val poses = poseResult.landmarks()
    if (poses.isEmpty()) return null // sem pessoa/tronco: frame não normalizável

    val pose = poses[0].map { lm ->
      floatArrayOf(lm.x(), lm.y(), lm.z(), lm.visibility().orElse(0f))
    }
    if (pose.size != N_POSE) {
      Log.w(TAG, "pose com ${pose.size} pontos (esperado $N_POSE) — frame ignorado")
      return null
    }

    val handResult: HandLandmarkerResult = handLandmarker.detectForVideo(mpImage, timestampMs)
    var left: List<FloatArray>? = null
    var right: List<FloatArray>? = null
    val hands = handResult.landmarks()
    val handedness = handResult.handedness()
    for (i in hands.indices) {
      val pontos = hands[i].map { lm -> floatArrayOf(lm.x(), lm.y(), lm.z()) }
      if (pontos.size != N_HAND) continue
      // handedness[i][0].categoryName() == "Left" / "Right" (convenção do MediaPipe,
      // a mesma do Holistic que gerou as referências).
      val label = handedness.getOrNull(i)?.firstOrNull()?.categoryName()
      when (label) {
        "Left" -> left = pontos
        "Right" -> right = pontos
      }
    }

    return FrameLandmarks(pose = pose, leftHand = left, rightHand = right)
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

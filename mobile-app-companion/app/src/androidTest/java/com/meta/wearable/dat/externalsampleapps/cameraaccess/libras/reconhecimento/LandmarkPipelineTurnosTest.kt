/*
 * A corrida do "iniciar" (docs/prontidao-demo/03-captura-e-landmarks.md §3.1, defeito A).
 *
 * O defeito: o MediaPipe nascia no primeiro frame e fechava a cada fim de stream, e o "iniciar"
 * abre a sessão assim que o stream fica STREAMING, antes do primeiro frame. A partir do segundo
 * turno, startSession() encontrava o extrator nulo, gravava "Modelos do MediaPipe não carregaram"
 * e não ligava a coleta.
 *
 * Aqui cada turno reproduz a ordem real: sessão aberta ANTES de qualquer frame, frames do stream,
 * fim da sessão e stop() do stream. Os frames são os do pessoa.mp4 (uma pessoa de corpo inteiro,
 * ver assets/pessoa.LEIAME.txt): cada turno precisa extrair frames E acender "pode sinalizar",
 * o que também prova a conversão YUV -> ARGB (YuvParaArgb) ponta a ponta.
 *
 * Instrumentado: MediaCodec, ImageReader e MediaPipe não existem na JVM.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.FormatoCsv
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.GravadorSessao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LandmarkPipelineTurnosTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  private class Video(val csd: ByteArray, val largura: Int, val altura: Int, val amostras: List<Pair<ByteArray, Long>>)

  @Test
  fun tresTurnosSeguidosColetamFramesComASessaoAbertaAntesDoPrimeiroFrame() = runBlocking {
    // O AAR do MediaPipe não traz x86_64: num emulador só x86_64 (o Pixel_7 API 33 do guia) a
    // biblioteca nativa não existe e não há o que testar aqui. Roda em aparelho ARM.
    assumeTrue(
        "biblioteca nativa do MediaPipe indisponível para ${Build.SUPPORTED_ABIS.toList()}",
        listOf("mediapipe_tasks_vision_jni", "mediapipe_tasks_jni").any {
          runCatching { System.loadLibrary(it) }.isSuccess
        },
    )
    val lock = Any()
    var estado = LibrasState()
    // 1.9: o gravador de sessão acompanha os três turnos; no fim, uma linha de frame por frame que
    // passou pelo MediaPipe.
    val gravador = GravadorSessao(context.getExternalFilesDir(null)!!)
    val csv = gravador.abrir()
    var linhasDeFrame = 0
    val pipeline =
        LandmarkPipeline(
            context = context,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            classifier = PlaceholderSignClassifier(),
            onState = { mudar -> synchronized(lock) { estado = estado.mudar() } },
            onRecognized = {},
            onFrameProcessado = { f ->
              linhasDeFrame++
              gravador.frame(f, turno = 1)
            },
        )
    var framesExtraidosTotal = 0
    try {
      assertTrue("o MediaPipe não carregou no aquecimento", pipeline.carregarModelos())
      val video = lerVideoAnnexB("pessoa.mp4", maxAmostras = 45)

      for (turno in 1..3) {
        pipeline.startSession()
        synchronized(lock) {
          assertTrue("turno $turno: a coleta não ligou", estado.isCollecting)
          assertNull("turno $turno: erro ao abrir a sessão", estado.error)
        }

        for ((bytes, ptsUs) in video.amostras) {
          pipeline.feedCompressedFrame(bytes, ptsUs, video.largura, video.altura, video.csd)
          Thread.sleep(33)
        }
        val limite = SystemClock.elapsedRealtime() + 5_000
        while (pipeline.framesExtraidosNaSessao == 0 && SystemClock.elapsedRealtime() < limite) {
          Thread.sleep(50)
        }
        assertTrue("turno $turno: nenhum frame passou pelo MediaPipe", pipeline.framesExtraidosNaSessao > 0)
        // Há uma pessoa de corpo inteiro no vídeo: a pose com os dois ombros tem de sair, senão o
        // MediaPipe está rodando sem ver nada (foi o que acontecia com a entrada em YUV).
        val limitePose = SystemClock.elapsedRealtime() + 5_000
        while (!synchronized(lock) { estado.podeSinalizar } && SystemClock.elapsedRealtime() < limitePose) {
          Thread.sleep(50)
        }
        synchronized(lock) { assertTrue("turno $turno: nenhuma pose normalizável", estado.podeSinalizar) }

        pipeline.endSession()
        pipeline.stop() // fim do stream, como o CameraViewModel faz a cada turno
        framesExtraidosTotal += pipeline.framesExtraidosNaSessao
      }
    } finally {
      pipeline.dispose()
      gravador.encerrar()
    }
    val linhas = csv.readLines()
    assertEquals(FormatoCsv.cabecalho(), linhas.first())
    assertEquals("uma linha por frame processado", framesExtraidosTotal, linhas.count { it.startsWith("frame,") })
    assertEquals(framesExtraidosTotal, linhasDeFrame)
    android.util.Log.i("PipelineTurnosTest", "CSV da sessão: ${csv.absolutePath} (${linhas.size - 1} linhas)")
    Unit // o JUnit exige método de teste void: o bloco do runBlocking não pode terminar num Int
  }

  /** Amostras do MP4 em Annex-B (start codes), como o stream dos óculos entrega. */
  private fun lerVideoAnnexB(asset: String, maxAmostras: Int): Video {
    val arquivo = File(context.cacheDir, asset)
    instrumentation.context.assets.open(asset).use { entrada ->
      arquivo.outputStream().use { entrada.copyTo(it) }
    }
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(arquivo.canonicalPath)
      val trilha =
          (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
          }
      val formato = extractor.getTrackFormat(trilha)
      extractor.selectTrack(trilha)
      val csdBuffer = checkNotNull(formato.getByteBuffer("csd-0"))
      val csd = ByteArray(csdBuffer.remaining()).also { csdBuffer.get(it) }

      val amostras = mutableListOf<Pair<ByteArray, Long>>()
      val buffer = ByteBuffer.allocate(1 shl 21)
      while (amostras.size < maxAmostras) {
        buffer.clear()
        val tamanho = extractor.readSampleData(buffer, 0)
        if (tamanho < 0) break
        val bruto = ByteArray(tamanho).also { buffer.get(it, 0, tamanho) }
        amostras.add(paraAnnexB(bruto) to extractor.sampleTime)
        extractor.advance()
      }
      return Video(
          csd,
          formato.getInteger(MediaFormat.KEY_WIDTH),
          formato.getInteger(MediaFormat.KEY_HEIGHT),
          amostras,
      )
    } finally {
      extractor.release()
    }
  }

  private fun paraAnnexB(amostra: ByteArray): ByteArray {
    // O MediaExtractor do Android já converte HEVC/AVC para start codes na maioria das versões;
    // reinterpretar "00 00 00 01" como tamanho destruiria a amostra.
    val jaAnnexB =
        amostra.size >= 4 &&
            amostra[0].toInt() == 0 && amostra[1].toInt() == 0 &&
            (amostra[2].toInt() == 1 || (amostra[2].toInt() == 0 && amostra[3].toInt() == 1))
    if (jaAnnexB) return amostra
    val saida = java.io.ByteArrayOutputStream(amostra.size + 16)
    var i = 0
    while (i + 4 <= amostra.size) {
      val n =
          ((amostra[i].toInt() and 0xff) shl 24) or ((amostra[i + 1].toInt() and 0xff) shl 16) or
              ((amostra[i + 2].toInt() and 0xff) shl 8) or (amostra[i + 3].toInt() and 0xff)
      i += 4
      if (n <= 0 || i + n > amostra.size) break
      saida.write(byteArrayOf(0, 0, 0, 1))
      saida.write(amostra, i, n)
      i += n
    }
    return saida.toByteArray()
  }
}

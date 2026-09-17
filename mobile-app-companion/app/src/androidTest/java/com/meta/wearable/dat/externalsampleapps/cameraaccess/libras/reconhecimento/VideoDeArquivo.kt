package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Lê um vídeo de arquivo no formato que a pipeline recebe dos óculos: HEVC em Annex-B, amostra a
 * amostra. Um vídeo que já é HEVC é só demuxado; qualquer outro codec (os clipes do MINDS são AVC)
 * é transcodificado no próprio aparelho, decoder -> Surface -> encoder HEVC, preservando os PTS.
 *
 * Transcodificar muda os pixels (é uma recompressão), então isto serve para exercitar o caminho
 * real de decodificação/MediaPipe/classificador, não para medir qualidade de vídeo.
 */
object VideoDeArquivo {

  class Video(
      val csd: ByteArray,
      val largura: Int,
      val altura: Int,
      val amostras: List<Pair<ByteArray, Long>>,
      val sha256: String,
      val codecOrigem: String,
      val transcodificado: Boolean,
  )

  private const val HEVC = "video/hevc"
  private const val TEMPO_ESPERA_US = 10_000L

  fun carregar(arquivo: File): Video {
    val sha = sha256(arquivo)
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(arquivo.canonicalPath)
      val trilha = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
      } ?: error("Sem trilha de vídeo em ${arquivo.name}")
      extractor.selectTrack(trilha)
      val formato = extractor.getTrackFormat(trilha)
      val mime = checkNotNull(formato.getString(MediaFormat.KEY_MIME))
      return if (mime == HEVC) demuxar(extractor, formato, sha)
      else transcodificar(extractor, formato, mime, sha)
    } finally {
      extractor.release()
    }
  }

  private fun demuxar(extractor: MediaExtractor, formato: MediaFormat, sha: String): Video {
    val csd = checkNotNull(formato.getByteBuffer("csd-0")) { "HEVC sem csd-0" }.let { b ->
      ByteArray(b.remaining()).also { b.get(it) }
    }
    val amostras = mutableListOf<Pair<ByteArray, Long>>()
    val buffer = ByteBuffer.allocate(4 shl 20)
    while (true) {
      buffer.clear()
      val n = extractor.readSampleData(buffer, 0)
      if (n < 0) break
      val bruto = ByteArray(n).also { buffer.get(it) }
      amostras.add(paraAnnexB(bruto) to extractor.sampleTime)
      extractor.advance()
    }
    require(amostras.isNotEmpty()) { "Vídeo vazio" }
    return Video(csd, formato.getInteger(MediaFormat.KEY_WIDTH), formato.getInteger(MediaFormat.KEY_HEIGHT),
        amostras.sortedBy { it.second }, sha, HEVC, transcodificado = false)
  }

  private fun transcodificar(extractor: MediaExtractor, formato: MediaFormat, mime: String, sha: String): Video {
    val largura = formato.getInteger(MediaFormat.KEY_WIDTH)
    val altura = formato.getInteger(MediaFormat.KEY_HEIGHT)
    val saida = MediaFormat.createVideoFormat(HEVC, largura, altura).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, maxOf(4_000_000, largura * altura * 4))
      setInteger(MediaFormat.KEY_FRAME_RATE, 30)
      // Todo frame intra: o teste alimenta a pipeline amostra a amostra e nunca faz seek.
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
    }
    val encoder = MediaCodec.createEncoderByType(HEVC)
    encoder.configure(saida, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val superficie = encoder.createInputSurface()
    encoder.start()
    val decoder = MediaCodec.createDecoderByType(mime)
    decoder.configure(formato, superficie, null, 0)
    decoder.start()

    var csd: ByteArray? = null
    val amostras = mutableListOf<Pair<ByteArray, Long>>()
    val infoEnc = MediaCodec.BufferInfo()
    val infoDec = MediaCodec.BufferInfo()
    try {
      var fimDaEntrada = false
      var fimDoDecoder = false
      var fimDoEncoder = false
      while (!fimDoEncoder) {
        if (!fimDaEntrada) {
          val idx = decoder.dequeueInputBuffer(TEMPO_ESPERA_US)
          if (idx >= 0) {
            val buffer = checkNotNull(decoder.getInputBuffer(idx))
            val n = extractor.readSampleData(buffer, 0)
            if (n < 0) {
              decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              fimDaEntrada = true
            } else {
              decoder.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        if (!fimDoDecoder) {
          when (val idx = decoder.dequeueOutputBuffer(infoDec, TEMPO_ESPERA_US)) {
            MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
            else -> {
              // Renderizar na Surface do encoder carrega o PTS do frame decodificado.
              val temImagem = infoDec.size > 0
              decoder.releaseOutputBuffer(idx, temImagem)
              if (infoDec.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                encoder.signalEndOfInputStream()
                fimDoDecoder = true
              }
            }
          }
        }
        when (val idx = encoder.dequeueOutputBuffer(infoEnc, TEMPO_ESPERA_US)) {
          MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
              csd = encoder.outputFormat.getByteBuffer("csd-0")?.let { b ->
                ByteArray(b.remaining()).also { b.get(it) }
              }
          else -> {
            val buffer = checkNotNull(encoder.getOutputBuffer(idx))
            val bytes = ByteArray(infoEnc.size).also {
              buffer.position(infoEnc.offset)
              buffer.get(it)
            }
            if (infoEnc.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = bytes
            else if (infoEnc.size > 0) amostras.add(bytes to infoEnc.presentationTimeUs)
            encoder.releaseOutputBuffer(idx, false)
            if (infoEnc.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fimDoEncoder = true
          }
        }
      }
    } finally {
      runCatching { decoder.stop() }
      decoder.release()
      runCatching { encoder.stop() }
      encoder.release()
      superficie.release()
    }
    require(amostras.isNotEmpty()) { "Transcodificação não produziu amostras" }
    // PTS estritamente crescentes: a pipeline usa o timestamp como relógio da segmentação.
    var anterior = Long.MIN_VALUE
    val ordenadas = amostras.sortedBy { it.second }.filter { (_, ts) ->
      (ts > anterior).also { if (it) anterior = ts }
    }
    return Video(checkNotNull(csd) { "Encoder HEVC não entregou csd-0" }, largura, altura,
        ordenadas, sha, mime, transcodificado = true)
  }

  private fun paraAnnexB(bytes: ByteArray): ByteArray {
    if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        (bytes[2] == 1.toByte() || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))) return bytes
    val saida = ByteArrayOutputStream(bytes.size + 32)
    var i = 0
    while (i < bytes.size) {
      require(i + 4 <= bytes.size) { "Prefixo HEVC truncado" }
      val n = ByteBuffer.wrap(bytes, i, 4).int
      i += 4
      require(n > 0 && n <= bytes.size - i) { "NAL HEVC inválida" }
      saida.write(byteArrayOf(0, 0, 0, 1))
      saida.write(bytes, i, n)
      i += n
    }
    return saida.toByteArray()
  }

  private fun sha256(arquivo: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    arquivo.inputStream().use { entrada ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val n = entrada.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

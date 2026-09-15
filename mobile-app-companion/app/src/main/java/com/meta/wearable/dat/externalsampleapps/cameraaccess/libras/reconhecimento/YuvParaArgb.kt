/*
 * Libras Livre — conversão de frame YUV_420_888 para ARGB_8888, antes do MediaPipe.
 *
 * Existe porque o MediaPipe Tasks NÃO aceita android.media.Image em YUV: o AndroidPacketCreator
 * (conferido no bytecode da 0.10.14 e visto em execução na 0.10.35) lança "Android media image
 * must use RGBA_8888 config" para qualquer outro formato. O app entregava YUV_420_888 pelo
 * MediaImageBuilder, e TODA extração falhava — capturada por frame no LandmarkPipeline, sem
 * nenhum landmark sair, em qualquer aparelho. Pôr o ImageReader direto em RGBA_8888 não serve: o
 * decodificador de vídeo entrega YV12 e o ImageReader recusa ("producer output buffer format ...
 * doesn't match").
 *
 * Função pura sobre arrays, para testar na JVM. Matriz BT.601 de faixa limitada (a do
 * YuvImage/NV21 do Android); um erro pequeno de cor não muda os landmarks.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

object YuvParaArgb {

  /**
   * @param y plano de luma, com [passoLinhaY] bytes por linha (pode ter preenchimento no fim).
   * @param u plano Cb e [v] plano Cr, em meia resolução, com [passoLinhaUv] bytes por linha e
   *   [passoPixelUv] bytes por amostra (2 quando os planos são intercalados, NV12/NV21).
   * @param saida recebe `largura * altura` pixels ARGB, linha a linha.
   */
  fun converter(
      largura: Int,
      altura: Int,
      y: ByteArray,
      passoLinhaY: Int,
      u: ByteArray,
      v: ByteArray,
      passoLinhaUv: Int,
      passoPixelUv: Int,
      saida: IntArray,
  ) {
    require(saida.size >= largura * altura) { "saída com ${saida.size} pixels para ${largura}x$altura" }
    var o = 0
    for (linha in 0 until altura) {
      val baseY = linha * passoLinhaY
      val baseUv = (linha shr 1) * passoLinhaUv
      for (coluna in 0 until largura) {
        val iUv = baseUv + (coluna shr 1) * passoPixelUv
        val c = ((y[baseY + coluna].toInt() and 0xff) - 16).coerceAtLeast(0) * 1192
        val d = (u[iUv].toInt() and 0xff) - 128
        val e = (v[iUv].toInt() and 0xff) - 128
        // Coeficientes BT.601 em ponto fixo (× 1024): 1,164 · 1,596 · 0,391 · 0,813 · 2,018.
        val r = ((c + 1634 * e) shr 10).coerceIn(0, 255)
        val g = ((c - 833 * e - 400 * d) shr 10).coerceIn(0, 255)
        val b = ((c + 2066 * d) shr 10).coerceIn(0, 255)
        saida[o++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
  }
}

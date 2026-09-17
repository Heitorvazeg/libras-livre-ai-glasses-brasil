package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

/**
 * Ownership de um ImageReader, sem dependência do Android.
 *
 * [enfileirar] SEMPRE posta na mesma fila serial dos callbacks, nunca executa inline. A fila
 * permanece viva até a barreira final de dispose. O lifecycle é serializado pelo chamador;
 * callbacks apenas consultam [ativo], sem segurar lock ao extrair ou notificar o app.
 */
internal class DonoLeitorLandmarks(private val enfileirar: (() -> Unit) -> Unit) {
  @Volatile var ativo = true
    private set
  private var fechamentoEnfileirado = false

  fun invalidar() {
    ativo = false
  }

  /** Chamado na fila serial; callbacks já postados depois de stop não podem adquirir Image. */
  fun executar(callback: () -> Unit) {
    if (ativo) callback()
  }

  /** Revalidar na fila de publicação, serializada com início/fim de captura pelo chamador. */
  fun podePublicar(sessaoCapturada: Any, sessaoAtual: Any?): Boolean =
      ativo && sessaoCapturada === sessaoAtual

  /**
   * O produtor (decoder) já deve estar parado. Não espera callback, nem fecha inline: até um
   * stop reentrante precisa deixar o callback chegar ao finally/Image.close antes do reader.close.
   */
  fun fecharDepoisDosCallbacks(fechar: () -> Unit) {
    invalidar()
    if (fechamentoEnfileirado) return
    fechamentoEnfileirado = true
    enfileirar(fechar)
  }
}
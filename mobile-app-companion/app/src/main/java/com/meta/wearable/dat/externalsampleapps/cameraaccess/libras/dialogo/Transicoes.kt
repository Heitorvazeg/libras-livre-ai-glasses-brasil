/*
 * Libras Livre — regras de transição do diálogo, sem efeitos (docs/prontidao-demo/04).
 *
 * Funções puras (estado atual -> decisão), para testar na JVM o que hoje só se via no aparelho.
 * O DialogOrchestrator continua dono dos efeitos (câmera, fala, escuta, avatar) e consulta estas
 * regras. A onda 3 (4.1) traz para cá as demais transições.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

object Transicoes {

  /**
   * Estados em que a wake word ouve: ①, ② e ④ (4.4).
   *
   * Fica FORA do ⑤: o microfone da resposta é o do celular por padrão (5.2), o mesmo que a wake
   * word usa, e os dois disputariam a captura. No ④ não há disputa, porque o Vosk ainda não abriu.
   */
  val ESTADOS_COM_WAKE_WORD: Set<DialogState> =
      setOf(
          DialogState.AGUARDANDO_SINAL,
          DialogState.CAPTURANDO_SINAIS,
          DialogState.AGUARDANDO_RESPOSTA,
      )

  fun wakeWordAtiva(estado: DialogState): Boolean = estado in ESTADOS_COM_WAKE_WORD
}

/*
 * Libras Livre — reação à falta de memória (docs/prontidao-demo/08-memoria.md §8.1).
 *
 * Com o MediaPipe carregado desde a abertura e o avatar pré-carregado, a memória pode faltar num
 * celular fraco. A ordem de sacrifício é fixa: PRIMEIRO o avatar (~300 MB no processo do renderer; a
 * legenda cobre a resposta), depois os motores de reserva ociosos. Reconhecimento, fala e escuta
 * nunca são liberados no meio de um atendimento. Um avatar animando termina antes de ser liberado.
 *
 * Função pura; os gatilhos (onTrimMemory e a verificação por segundo) ficam no CameraViewModel.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.AvatarState

object PressaoDeMemoria {

  enum class AcaoAvatar {
    NENHUMA,
    LIBERAR_AGORA,
    LIBERAR_DEPOIS_DA_ANIMACAO,
  }

  data class Decisao(val avatar: AcaoAvatar, val liberarVozReserva: Boolean)

  /**
   * Memória baixa: o sistema já sinalizou `lowMemory`, ou o disponível caiu abaixo de
   * [fator] × o limiar em que o sistema começa a matar processos.
   */
  fun memoriaBaixa(disponivelBytes: Long, limiarSistemaBytes: Long, lowMemory: Boolean, fator: Float): Boolean =
      lowMemory || disponivelBytes < (limiarSistemaBytes * fator).toLong()

  fun decidir(baixa: Boolean, avatar: AvatarState, vozReservaCriada: Boolean, vozReservaEmUso: Boolean): Decisao {
    if (!baixa) return Decisao(AcaoAvatar.NENHUMA, liberarVozReserva = false)
    val acaoAvatar =
        when (avatar) {
          AvatarState.OCIOSO -> AcaoAvatar.NENHUMA
          AvatarState.ANIMANDO -> AcaoAvatar.LIBERAR_DEPOIS_DA_ANIMACAO
          // FALHOU por erro de JS ainda segura a WebView.
          AvatarState.CARREGANDO,
          AvatarState.PRONTO,
          AvatarState.FALHOU -> AcaoAvatar.LIBERAR_AGORA
        }
    return Decisao(acaoAvatar, liberarVozReserva = vozReservaCriada && !vozReservaEmUso)
  }
}

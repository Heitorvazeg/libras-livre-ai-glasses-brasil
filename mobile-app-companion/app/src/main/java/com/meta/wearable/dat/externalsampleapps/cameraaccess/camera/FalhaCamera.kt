/*
 * Libras Livre — por que a câmera dos óculos não subiu (docs/prontidao-demo/03 §3.4).
 *
 * Antes, `ensureCameraActiveForLibras` devolvia só false, e o "iniciar" era ignorado em silêncio. A
 * causa agora vai para a faixa de estado. A ordem das checagens é a do diagnóstico: primeiro o que
 * se sabe antes de tentar (dispositivo, atualização), depois o que só aparece tentando.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

enum class FalhaCamera {
  SEM_DISPOSITIVO,
  PERMISSAO_PENDENTE,
  ATUALIZACAO_OBRIGATORIA,
  SESSAO_SEM_RESPOSTA,
  STREAM_NAO_SUBIU,
  PAUSA_LONGA,
  // [NOVO — docs/confirmacao-e-modo-economia-plano.md §2] Modo economia ligado por
  // DeviceSessionError.BATTERY_CRITICAL/StreamError.BATTERY_LOW (CameraViewModel.onBateriaBaixa).
  // "Antes de tentar" verifica isso primeiro (ensureCameraActiveForLibras), então nem chega a
  // tentar ligar o stream.
  BATERIA_BAIXA,
  ;

  companion object {
    /** Antes de tentar abrir a sessão: o que já impede. */
    fun antesDeTentar(temDispositivoAtivo: Boolean, atualizacaoObrigatoria: Boolean): FalhaCamera? =
        when {
          atualizacaoObrigatoria -> ATUALIZACAO_OBRIGATORIA
          !temDispositivoAtivo -> SEM_DISPOSITIVO
          else -> null
        }

    /** Depois de esperar a sessão e o stream: por que não ficou pronto. */
    fun depoisDeEsperar(sessaoPronta: Boolean, streamPronto: Boolean, permissaoPendente: Boolean): FalhaCamera? =
        when {
          !sessaoPronta -> SESSAO_SEM_RESPOSTA
          streamPronto -> null
          permissaoPendente -> PERMISSAO_PENDENTE
          else -> STREAM_NAO_SUBIU
        }
  }
}

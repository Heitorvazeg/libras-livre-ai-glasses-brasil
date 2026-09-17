/*
 * Libras Livre — textos que o CameraViewModel põe na faixa de estado e no aquecimento
 * (docs/prontidao-demo 3.2, 3.4, 5.2, 5.5, 6.4, 7.3, 8.1, 10.2), lidos das strings do app.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.FalhaCamera

class TextosLibras(context: Context) {
  private val c = context.applicationContext

  val vozReserva = c.getString(R.string.aviso_voz_reserva)
  val repita = c.getString(R.string.aviso_repita)
  val desistiu = c.getString(R.string.aviso_desistiu)
  val streamPausado = c.getString(R.string.aviso_stream_pausado)
  val micOculosIndisponivel = c.getString(R.string.aviso_mic_oculos_indisponivel)
  val celularQuente = c.getString(R.string.aviso_celular_quente)
  val avatarLiberadoMemoria = c.getString(R.string.aviso_avatar_liberado_memoria)
  val voskNaoCarregou = c.getString(R.string.aquecimento_vosk_falhou)
  val avatarNaoCarregou = c.getString(R.string.aquecimento_avatar_falhou)
  val consentimentoRecusado = c.getString(R.string.aviso_consentimento_recusado)
  val consentimentoSemLibras = c.getString(R.string.aviso_consentimento_sem_libras)
  val confirmacaoExpirada = c.getString(R.string.aviso_confirmacao_expirada)
  val correcaoSemCamera = c.getString(R.string.aviso_correcao_sem_camera)

  val etapaMediaPipe = c.getString(R.string.aquecimento_etapa_mediapipe)
  val etapaClassificador = c.getString(R.string.aquecimento_etapa_classificador)
  val etapaContextualizacao = c.getString(R.string.aquecimento_etapa_contextualizacao)
  val etapaVosk = c.getString(R.string.aquecimento_etapa_vosk)
  val etapaVoz = c.getString(R.string.aquecimento_etapa_voz)
  val etapaAvatar = c.getString(R.string.aquecimento_etapa_avatar)

  fun aquecimentoFalhou(detalhe: String) = c.getString(R.string.aviso_aquecimento_falhou, detalhe)

  fun falhaCamera(falha: FalhaCamera): String =
      c.getString(
          when (falha) {
            FalhaCamera.SEM_DISPOSITIVO -> R.string.falha_camera_sem_dispositivo
            FalhaCamera.PERMISSAO_PENDENTE -> R.string.falha_camera_permissao
            FalhaCamera.ATUALIZACAO_OBRIGATORIA -> R.string.falha_camera_atualizacao
            FalhaCamera.SESSAO_SEM_RESPOSTA -> R.string.falha_camera_sessao
            FalhaCamera.STREAM_NAO_SUBIU -> R.string.falha_camera_stream
            FalhaCamera.PAUSA_LONGA -> R.string.falha_camera_pausa_longa
            FalhaCamera.BATERIA_BAIXA -> R.string.falha_camera_bateria_baixa
          })
}

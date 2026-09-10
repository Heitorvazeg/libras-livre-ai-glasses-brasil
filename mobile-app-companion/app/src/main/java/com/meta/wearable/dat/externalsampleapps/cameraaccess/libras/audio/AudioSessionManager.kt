/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AudioSessionManager - Dono exclusivo da troca de perfil Bluetooth A2DP <-> HFP
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4 item 4-5, §6.1. TTS sai por A2DP (roteamento
// padrão do Android, sem chamada nenhuma aqui — ver Speaker.kt); só a ESCUTA da resposta do
// atendente (mic dos óculos) precisa de HFP/SCO, ligado sob demanda por acquireListening() e
// liberado por releaseListening(). A2DP e HFP são mutuamente exclusivos no mesmo par Bluetooth —
// nunca chamar acquireListening() enquanto o TTS ainda estiver falando.
//
// setCommunicationDevice (API 31+, minSdk deste projeto) é assíncrono: só confiamos na troca
// depois do callback de addOnCommunicationDeviceChangedListener, com um timeout de segurança pra
// não travar se o SCO nunca conectar (ex.: óculos sem HFP pareado).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AudioSessionManager(context: Context) {
  private val context: Context = context.applicationContext
  private val audioManager =
      this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  companion object {
    private const val TAG = "Libras:AudioSessionManager"
    private const val SWITCH_TIMEOUT_MS = 3000L
  }

  /**
   * Troca o roteamento de áudio pra HFP/SCO dos óculos e espera a confirmação do sistema (ou
   * [SWITCH_TIMEOUT_MS]). Devolve o [AudioDeviceInfo] usado, ou null se não achou nenhum
   * dispositivo SCO disponível (óculos sem HFP pareado/ligado) — nesse caso nada foi trocado.
   */
  suspend fun acquireListening(): AudioDeviceInfo? {
    val scoDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (scoDevice == null) {
      Log.w(TAG, "Nenhum dispositivo SCO disponível — HFP dos óculos não pareado/ligado?")
      return null
    }

    val confirmed =
        withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
          awaitCommunicationDeviceChange(scoDevice) { audioManager.setCommunicationDevice(scoDevice) }
        }
    if (confirmed != true) {
      Log.w(TAG, "Troca pra HFP não confirmada em ${SWITCH_TIMEOUT_MS}ms — seguindo mesmo assim")
    }
    return scoDevice
  }

  /** Libera o HFP e volta ao roteamento padrão (A2DP). Idempotente. */
  fun releaseListening() {
    audioManager.clearCommunicationDevice()
  }

  private suspend fun awaitCommunicationDeviceChange(
      expected: AudioDeviceInfo,
      trigger: () -> Boolean,
  ): Boolean =
      suspendCancellableCoroutine { cont ->
        lateinit var listener: AudioManager.OnCommunicationDeviceChangedListener
        listener =
            AudioManager.OnCommunicationDeviceChangedListener { device ->
              if (device?.id == expected.id) {
                audioManager.removeOnCommunicationDeviceChangedListener(listener)
                if (cont.isActive) cont.resume(true)
              }
            }
        audioManager.addOnCommunicationDeviceChangedListener(
            ContextCompat.getMainExecutor(context),
            listener,
        )
        cont.invokeOnCancellation {
          runCatching { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
        }

        if (!trigger()) {
          audioManager.removeOnCommunicationDeviceChangedListener(listener)
          if (cont.isActive) cont.resume(false)
        }
      }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// PcmMicCapture - captura contínua de PCM cru de um microfone, configurável por fonte/dispositivo.
//
// Unifica as duas classes de captura que existiam antes e eram quase idênticas (mesma thread de
// gravação, mesmas constantes de formato, mesmo callback de PCM), diferindo só na fonte de áudio e
// no dispositivo fixado:
//   - AudioInputHandler       (mic do CELULAR, VOICE_RECOGNITION, TYPE_BUILTIN_MIC) — era p/ wake word
//   - AttendantAudioCapture   (mic dos ÓCULOS,  VOICE_COMMUNICATION, TYPE_BLUETOOTH_SCO) — p/ STT
// Agora é UMA classe parametrizada; cada caso de uso só escolhe [audioSource] e [preferredDeviceType].
//
// Casos de uso (ver docs/orquestracao-dialogo-audio-plano.md §4 itens 1/4, §6.4):
//   - STT do atendente (VoskSttEngine): VOICE_COMMUNICATION + TYPE_BLUETOOTH_SCO, pra pegar o mic dos
//     óculos DEPOIS que AudioSessionManager.acquireListening() troca pra HFP/SCO.
//   - Wake word por PCM cru (Porcupine/TFLite, §8): VOICE_RECOGNITION + TYPE_BUILTIN_MIC. Sem
//     consumidor hoje — o motor de wake word atual (SpeechRecognizerWakeWordDetector) usa
//     android.speech.SpeechRecognizer, que gerencia o próprio mic, e o OpenWakeWordDetector usa o
//     AudioRecorder vendorizado do openWakeWord.
//
// SAMPLE_RATE = 16000 (mono, PCM 16-bit) é o formato comum dos motores on-device. No caso HFP assume
// codec wideband (mSBC); se o par negociar narrowband (8kHz), este valor precisa mudar junto com o
// sampleRate passado pro Recognizer do Vosk (VoskSttEngine.kt) — Fase 0 (§7) ainda não validou isso
// em hardware real.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * @param audioSource valor de [android.media.MediaRecorder.AudioSource] (ex.: VOICE_COMMUNICATION
 *   pro mic dos óculos via HFP, VOICE_RECOGNITION pro mic do celular).
 * @param preferredDeviceType tipo de [AudioDeviceInfo] a fixar via setPreferredDevice (ex.:
 *   TYPE_BLUETOOTH_SCO pros óculos, TYPE_BUILTIN_MIC pro celular). Best-effort: se o dispositivo não
 *   estiver disponível no momento, segue com o roteamento padrão do sistema.
 */
class PcmMicCapture(
    context: Context,
    private val audioSource: Int,
    private val preferredDeviceType: Int,
) {
  // Guardado como applicationContext — esta classe não tem ciclo de vida, nunca deve reter Activity.
  private val context: Context = context.applicationContext

  companion object {
    private const val TAG = "Libras:PcmMicCapture"
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BUFFER_SIZE_FACTOR = 2
  }

  /** PCM entregue aqui (na thread de captura) enquanto a gravação estiver ativa. */
  @Volatile var pcmDataCallback: ((ByteArray, Int, Int) -> Unit)? = null

  // Atribuído na thread chamadora (startRecording/cleanup) e lido na thread de captura; @Volatile
  // publica esses reads/writes com segurança.
  @Volatile private var audioRecord: AudioRecord? = null
  @Volatile private var recordingThreadActive = false

  private val threadLock = Any()
  private var recordingThread: Thread? = null

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  // Vira true quando a captura para inesperadamente (ex.: uma ligação sequestra o mic), pra quem
  // chama derrubar a sessão com elegância. Resetado a cada startRecording().
  private val _wasInterrupted = MutableStateFlow(false)
  val wasInterrupted: StateFlow<Boolean> = _wasInterrupted.asStateFlow()

  /**
   * Começa a captura contínua. Devolve false — sem lançar — quando não há mic utilizável (sem
   * permissão RECORD_AUDIO, buffer inválido, ou AudioRecord não inicializa), pra quem chama tratar
   * como falha de captura (equivalente a [SttEngine.start] chamando onError, ou "wake word
   * indisponível").
   */
  fun startRecording(): Boolean {
    if (_isRecording.value) return true

    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "Sem permissão RECORD_AUDIO — captura não iniciada")
      return false
    }

    val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      Log.w(TAG, "Buffer size inválido para AudioRecord")
      return false
    }

    val record =
        try {
          AudioRecord(
              audioSource,
              SAMPLE_RATE,
              CHANNEL_CONFIG,
              AUDIO_FORMAT,
              bufferSize * BUFFER_SIZE_FACTOR,
          )
        } catch (e: Exception) {
          Log.w(TAG, "Falha ao inicializar AudioRecord", e)
          return false
        }

    if (record.state != AudioRecord.STATE_INITIALIZED) {
      Log.w(TAG, "AudioRecord não inicializou")
      record.release()
      return false
    }

    pinToPreferredDevice(record)
    audioRecord = record
    _isRecording.value = true
    _wasInterrupted.value = false
    recordingThreadActive = true

    synchronized(threadLock) {
      recordingThread =
          Thread {
                var interruptedBySystem = false
                try {
                  record.startRecording()
                  val buffer = ByteArray(bufferSize)
                  while (recordingThreadActive && !Thread.currentThread().isInterrupted) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    when {
                      bytesRead > 0 -> if (recordingThreadActive) pcmDataCallback?.invoke(buffer, 0, bytesRead)
                      bytesRead == 0 -> Thread.sleep(10) // sem dados nesta rodada (ex.: mic mudo no emulador)
                      else -> {
                        Log.e(TAG, "Captura interrompida (code=$bytesRead)")
                        interruptedBySystem = true
                        break
                      }
                    }
                  }
                } catch (e: InterruptedException) {
                  Log.d(TAG, "Thread de captura interrompida")
                } catch (e: Exception) {
                  Log.e(TAG, "Erro durante a captura", e)
                  interruptedBySystem = true
                } finally {
                  runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                  }
                  _isRecording.value = false
                  if (interruptedBySystem) _wasInterrupted.value = true
                }
              }
              .also { it.start() }
    }
    return true
  }

  fun stopRecording() {
    if (!_isRecording.value) return
    recordingThreadActive = false
    _isRecording.value = false
    val thread = synchronized(threadLock) { recordingThread?.interrupt(); recordingThread }
    thread?.let {
      it.join(1000)
      if (it.isAlive) Log.w(TAG, "Thread de captura não terminou dentro do timeout")
    }
  }

  fun cleanup() {
    pcmDataCallback = null
    stopRecording()
    _wasInterrupted.value = false
    audioRecord?.release()
    audioRecord = null
  }

  // Fixa a captura no dispositivo do tipo [preferredDeviceType] — mitigação pro roteamento do
  // sistema preferir outro device (ex.: com o HFP dos óculos ativo, o mic embutido pode ser
  // "sequestrado", e vice-versa). Best-effort: procura tanto entre os dispositivos de comunicação
  // (SCO aparece aqui) quanto nas entradas gerais (mic embutido); se não achar, segue no padrão.
  private fun pinToPreferredDevice(record: AudioRecord) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val candidates =
        audioManager.availableCommunicationDevices +
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    val device = candidates.firstOrNull { it.type == preferredDeviceType }
    if (device != null) {
      record.setPreferredDevice(device)
    } else {
      Log.w(TAG, "Dispositivo preferido (type=$preferredDeviceType) indisponível — roteamento padrão")
    }
  }
}

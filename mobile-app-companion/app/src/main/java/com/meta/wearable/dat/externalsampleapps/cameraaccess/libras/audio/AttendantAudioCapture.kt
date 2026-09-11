/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AttendantAudioCapture - PCM cru do mic dos ÓCULOS (HFP/SCO), pra alimentar o SttEngine
//
// Ver docs/orquestracao-dialogo-audio-plano.md §6.4, §7 Fase 5. Classe nova, não uma extensão do
// AudioInputHandler existente (que capta do mic do CELULAR, pra wake word — ver header daquele
// arquivo). Motivo de existir: VoskSttEngine.kt consome PCM cru (Recognizer.acceptWaveForm), ao
// contrário de AndroidSpeechRecognizerSttEngine, que usa android.speech.SpeechRecognizer (gerencia
// a própria captura de mic internamente, sem expor uma API de "alimentar PCM manualmente").
//
// AudioSource.VOICE_COMMUNICATION (não MIC nem VOICE_RECOGNITION) segue o roteamento de
// comunicação do sistema — é o que traz o áudio do mic dos óculos depois que
// AudioSessionManager.acquireListening() troca pra HFP/SCO (§4 item 4). setPreferredDevice fixa
// explicitamente no dispositivo TYPE_BLUETOOTH_SCO disponível no momento — mesma mitigação de
// "roteamento pode preferir outro device" que AudioInputHandler já aplica pro mic embutido.
//
// SAMPLE_RATE = 16000 assume codec wideband (mSBC) negociado no HFP — Fase 0 (§7) ainda não validou
// isso em hardware real; se o par negociar narrowband (8kHz), este valor precisa mudar junto com o
// sampleRate passado pro Recognizer do Vosk (VoskSttEngine.kt).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AttendantAudioCapture(context: Context) {
  private val context: Context = context.applicationContext

  companion object {
    private const val TAG = "Libras:AttendantAudioCapture"
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BUFFER_SIZE_FACTOR = 2
  }

  /** PCM entregue aqui enquanto a captura estiver ativa — plugado pelo VoskSttEngine. */
  @Volatile var pcmDataCallback: ((ByteArray, Int, Int) -> Unit)? = null

  @Volatile private var audioRecord: AudioRecord? = null
  @Volatile private var recordingThreadActive = false
  private val threadLock = Any()
  private var recordingThread: Thread? = null

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  /**
   * Começa a captura contínua do mic dos óculos (dispositivo SCO corrente). Devolve false — sem
   * lançar — quando não há permissão/hardware disponível, pra quem chama tratar como falha de
   * captura (equivalente a [SttEngine.start] chamando onError).
   */
  fun startRecording(): Boolean {
    if (_isRecording.value) return true

    val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      Log.w(TAG, "Invalid buffer size for AudioRecord")
      return false
    }

    val record =
        try {
          AudioRecord(
              MediaRecorder.AudioSource.VOICE_COMMUNICATION,
              SAMPLE_RATE,
              CHANNEL_CONFIG,
              AUDIO_FORMAT,
              bufferSize * BUFFER_SIZE_FACTOR,
          )
        } catch (e: Exception) {
          Log.w(TAG, "Exception initializing AudioRecord", e)
          return false
        }

    if (record.state != AudioRecord.STATE_INITIALIZED) {
      Log.w(TAG, "AudioRecord initialization failed")
      record.release()
      return false
    }

    pinToScoDevice(record)
    audioRecord = record
    _isRecording.value = true
    recordingThreadActive = true

    synchronized(threadLock) {
      recordingThread =
          Thread {
                try {
                  record.startRecording()
                  val buffer = ByteArray(bufferSize)
                  while (recordingThreadActive) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0 && recordingThreadActive) {
                      pcmDataCallback?.invoke(buffer, 0, bytesRead)
                    } else if (bytesRead < 0) {
                      Log.e(TAG, "Attendant audio capture interrupted (code=$bytesRead)")
                      break
                    }
                  }
                } catch (e: Exception) {
                  Log.e(TAG, "Error during attendant audio capture", e)
                } finally {
                  runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                  }
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
    thread?.join(1000)
  }

  fun cleanup() {
    pcmDataCallback = null
    stopRecording()
    audioRecord?.release()
    audioRecord = null
  }

  // Fixa a captura no dispositivo SCO corrente (óculos) — best-effort: se nenhum SCO estiver
  // disponível no momento, segue com o roteamento padrão do sistema mesmo assim.
  private fun pinToScoDevice(record: AudioRecord) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val scoDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (scoDevice != null) {
      record.setPreferredDevice(scoDevice)
    } else {
      Log.w(TAG, "Nenhum dispositivo SCO disponível ao iniciar captura — seguindo com roteamento padrão")
    }
  }
}

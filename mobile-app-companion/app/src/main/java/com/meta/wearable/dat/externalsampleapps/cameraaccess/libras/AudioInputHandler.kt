/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AudioInputHandler - Phone Microphone Audio Input (fonte de PCM pra wake word)
//
// Movido de stream/ pra cá: antes alimentava a trilha de áudio da gravação MP4
// (stream/VideoRecorder.kt); essa trilha foi removida porque o reconhecimento de sinal roda só
// sobre landmarks (não precisa de áudio no clipe) — a gravação agora é sempre vídeo-only. Esta
// classe ficou livre pra virar a fonte de PCM contínuo de um motor de wake word (ver
// WakeWordDetector.kt), captando do mic do CELULAR (não dos óculos — ver
// docs/orquestracao-dialogo-audio-plano.md §4 item 1 pro motivo).
//
// Ajustes em relação à versão original (que servia o encoder AAC): AudioSource passou de MIC pra
// VOICE_RECOGNITION (menos processamento agressivo, mais adequado a keyword-spotting) e o sample
// rate de 44100 pra 16000 (o que a maioria dos motores de wake word on-device espera). A captura
// também fixa explicitamente o dispositivo de entrada no mic embutido (setPreferredDevice) — com o
// HFP dos óculos ativo (DialogState.ESCUTANDO_ATENDENTE), o roteamento padrão pode preferir o
// link SCO em alguns aparelhos/HALs, o que "sequestraria" esta captura; fixar reduz esse risco,
// mas não elimina — a concorrência real só se confirma em hardware (§4 item 3, §7 Fase 0 do
// plano).
//
// Nenhum motor de detecção está plugado nesta classe ainda — hoje o WakeWordDetector ativo é o
// ManualWakeWordDetector (botões), e nada instancia esta classe. Ela fica pronta pra quando um
// motor de verdade (Porcupine, TFLite — §8 do plano) for escolhido.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioInputHandler(context: Context) {
  // Stored as the application context so this non-lifecycle class never retains an Activity.
  private val context: Context = context.applicationContext

  companion object {
    private const val TAG = "Libras:AudioInputHandler"
    // 16kHz mono é o formato de entrada comum pra motores de wake word on-device (Porcupine,
    // Vosk) — diferente do 44100 usado quando esta classe alimentava o encoder AAC do MP4.
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BUFFER_SIZE_FACTOR = 2
  }

  // PCM data is delivered to this callback — hoje sem nenhum consumidor plugado (ver header).
  @Volatile var pcmDataCallback: ((ByteArray, Int, Int) -> Unit)? = null

  // Reference assigned on the caller thread (initializeAudioRecord/cleanup) and read on the
  // recording thread; @Volatile publishes those reads/writes safely.
  @Volatile private var audioRecord: AudioRecord? = null

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  // Set when capture stops unexpectedly (e.g. a phone call grabs the mic) so the caller can
  // tear the recording down gracefully.
  private val _wasInterrupted = MutableStateFlow(false)
  val wasInterrupted: StateFlow<Boolean> = _wasInterrupted.asStateFlow()

  @Volatile private var recordingThreadActive = false

  // Recording thread — guarded by `threadLock`
  private val threadLock = Any()
  private var recordingThread: Thread? = null

  private val handler = Handler(Looper.getMainLooper())

  private fun initializeAudioRecord(): Boolean {
    if (audioRecord != null) {
      return true
    }

    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "Audio recording permission not granted")
      return false
    }

    val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      Log.w(TAG, "Invalid buffer size for AudioRecord")
      return false
    }

    try {
      val record =
          AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              SAMPLE_RATE,
              CHANNEL_CONFIG,
              AUDIO_FORMAT,
              bufferSize * BUFFER_SIZE_FACTOR,
          )

      if (record.state != AudioRecord.STATE_INITIALIZED) {
        Log.w(TAG, "AudioRecord initialization failed")
        record.release()
        return false
      }

      pinToBuiltInMic(record)
      audioRecord = record
      Log.d(TAG, "AudioRecord initialized successfully")
      return true
    } catch (e: Exception) {
      Log.w(TAG, "Exception initializing AudioRecord", e)
      audioRecord?.release()
      audioRecord = null
      return false
    }
  }

  // Fixa a captura no mic embutido do celular em vez do roteamento padrão do sistema — mitigação
  // pro risco de concorrência com o HFP dos óculos descrito no header. Best-effort: se o
  // dispositivo não aparecer em getDevices(), segue com o roteamento padrão mesmo assim.
  private fun pinToBuiltInMic(record: AudioRecord) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val builtInMic =
        audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    if (builtInMic != null) {
      record.setPreferredDevice(builtInMic)
    } else {
      Log.w(TAG, "Mic embutido não encontrado em getDevices() — seguindo com roteamento padrão")
    }
  }

  /**
   * Começa a captura contínua do mic do celular. Devolve false — sem lançar — quando não há mic
   * utilizável (sem permissão RECORD_AUDIO, buffer inválido, ou AudioRecord não inicializa), pra
   * quem chama tratar como "wake word indisponível" e confiar só nos botões de fallback.
   */
  fun startRecording(): Boolean {
    if (_isRecording.value) {
      return true
    }

    if (!initializeAudioRecord()) {
      Log.w(TAG, "Microphone unavailable; wake word listening not started")
      return false
    }

    _isRecording.value = true
    _wasInterrupted.value = false
    recordingThreadActive = true

    synchronized(threadLock) {
      recordingThread =
          Thread {
                val localAudioRecord = audioRecord
                if (localAudioRecord == null) {
                  Log.e(TAG, "AudioRecord is null, cannot start recording")
                  handler.post {
                    _isRecording.value = false
                    recordingThreadActive = false
                  }
                  return@Thread
                }

                var wasInterruptedBySystem = false
                try {
                  localAudioRecord.startRecording()
                  val buffer =
                      ByteArray(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))

                  while (recordingThreadActive && !Thread.currentThread().isInterrupted) {
                    val bytesRead = localAudioRecord.read(buffer, 0, buffer.size)
                    when {
                      bytesRead > 0 -> {
                        if (recordingThreadActive) {
                          pcmDataCallback?.invoke(buffer, 0, bytesRead)
                        }
                      }
                      bytesRead == AudioRecord.ERROR_DEAD_OBJECT ||
                          bytesRead == AudioRecord.ERROR_INVALID_OPERATION ||
                          bytesRead == AudioRecord.ERROR_BAD_VALUE ||
                          bytesRead == AudioRecord.ERROR -> {
                        Log.e(TAG, "Audio recording interrupted (code=$bytesRead)")
                        wasInterruptedBySystem = true
                        break
                      }
                      else -> {
                        // bytesRead == 0: no data this round (common on an emulator's silent mic).
                        // Yield instead of spinning.
                        Thread.sleep(10)
                      }
                    }
                  }
                } catch (e: InterruptedException) {
                  Log.d(TAG, "Recording thread interrupted")
                } catch (e: IllegalStateException) {
                  Log.e(TAG, "AudioRecord illegal state - likely interrupted by system", e)
                  wasInterruptedBySystem = true
                } catch (e: Exception) {
                  Log.e(TAG, "Error during audio recording", e)
                  wasInterruptedBySystem = true
                } finally {
                  try {
                    if (localAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                      localAudioRecord.stop()
                    }
                  } catch (e: Exception) {
                    Log.e(TAG, "Error stopping audio record", e)
                  }
                  val interrupted = wasInterruptedBySystem
                  handler.post {
                    _isRecording.value = false
                    if (interrupted) {
                      _wasInterrupted.value = true
                    }
                  }
                }
              }
              .also { it.start() }
    }

    Log.d(TAG, "Audio recording started")
    return true
  }

  fun stopRecording() {
    if (!_isRecording.value) {
      return
    }

    recordingThreadActive = false
    _isRecording.value = false

    val thread =
        synchronized(threadLock) {
          recordingThread?.interrupt()
          recordingThread
        }

    thread?.let { audioThread ->
      audioThread.join(1000)
      if (audioThread.isAlive) {
        Log.w(TAG, "Recording thread did not terminate within timeout")
      }
    }
  }

  fun cleanup() {
    pcmDataCallback = null
    stopRecording()
    _wasInterrupted.value = false

    audioRecord?.release()
    audioRecord = null
  }
}

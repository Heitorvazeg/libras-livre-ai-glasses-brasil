/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// VideoRecorder - Streaming Video Recording Orchestrator
//
// Streams compressed HEVC frames from the DAT SDK directly to an MP4 file in the cache
// directory via VideoCaptureHandler — no video re-encoding. Video-only: the reconhecimento de
// sinal roda só sobre landmarks (ver libras/LandmarkPipeline.kt), então esta gravação (usada só
// pelo botão de captura/preview/share, sem relação com a sessão de diálogo) não precisa de
// áudio — o mic do celular ficou livre pra virar a fonte da wake word (ver
// libras/audio/PcmMicCapture.kt). The track opens on the first detectable keyframe (falling back to
// any frame so recording always starts). The finished file is exposed as a FileProvider
// Uri for preview/share and is deleted by the caller once previewed.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of finalizing a recording. */
sealed interface RecordingResult {
  /** A playable file was written and is exposed at [uri] (caller deletes it after previewing). */
  data class Completed(val uri: Uri) : RecordingResult

  /** Nothing was recorded — e.g. stopped before the first keyframe arrived. */
  data object NoRecording : RecordingResult

  /** A file was started but could not be finalized. */
  data object Failed : RecordingResult
}

class VideoRecorder(
    context: Context,
    // The owner's scope (e.g. viewModelScope) — drives the elapsed timer so it's cancelled with
    // the owner. close() cancels its own job but never this shared scope.
    private val scope: CoroutineScope,
) {

  // Stored as the application context so this non-lifecycle class never retains an Activity.
  private val context: Context = context.applicationContext

  companion object {
    private const val TAG = "VideoRecorder"
  }

  private val videoCaptureHandler = VideoCaptureHandler()

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  private val _recordingElapsedSeconds = MutableStateFlow(0L)
  val recordingElapsedSeconds: StateFlow<Long> = _recordingElapsedSeconds.asStateFlow()

  // True once the first keyframe has been written and the file is actually capturing. Lets the
  // caller wait briefly on stop so a clip taken right before a keyframe still finalizes.
  private val _hasStartedWriting = MutableStateFlow(false)
  val hasStartedWriting: StateFlow<Boolean> = _hasStartedWriting.asStateFlow()

  // @Volatile: published across the caller, frame-delivery, and stop threads — timerJob is
  // assigned on the frame thread (writeCompressedFrame) and cancelled on the caller thread
  // (stopRecording/close); no compound state to guard, only publication visibility.
  @Volatile private var timerJob: Job? = null
  @Volatile private var tempFile: File? = null

  fun writeCompressedFrame(
      data: ByteArray,
      presentationTimeUs: Long,
      width: Int,
      height: Int,
      isCodecConfig: Boolean = false,
  ) {
    if (!_isRecording.value) return
    val justStarted =
        videoCaptureHandler.writeVideoFrame(data, presentationTimeUs, width, height, isCodecConfig)
    if (justStarted && !_hasStartedWriting.value) {
      _hasStartedWriting.value = true
      startTimer()
    }
  }

  /**
   * Starts recording. [codecConfig] is the most recent codec-config frame seen while streaming; it
   * primes the muxer's CSD so a recording that begins mid-stream still gets a video track (the SDK
   * sends the config only once, at stream start).
   */
  suspend fun startRecording(codecConfig: ByteArray? = null) {
    if (_isRecording.value) {
      return
    }
    _isRecording.value = true
    _recordingElapsedSeconds.value = 0
    _hasStartedWriting.value = false

    // Temp-file creation and the MediaMuxer setup are blocking I/O; keep them off the main
    // thread, mirroring stopRecording's withContext(IO). A frame that races this setup is a safe
    // no-op — writeVideoFrame returns early while the muxer is still null.
    withContext(Dispatchers.IO) {
      val file = createTempFile()
      tempFile = file

      videoCaptureHandler.resetState()
      videoCaptureHandler.prepare(file.canonicalPath)
      codecConfig?.let { videoCaptureHandler.setInitialCodecConfig(it) }
    }
    // The elapsed timer starts on the first keyframe (see writeCompressedFrame).
  }

  private fun startTimer() {
    timerJob?.cancel()
    timerJob = scope.launch {
      while (_isRecording.value) {
        delay(1000L)
        _recordingElapsedSeconds.value += 1
      }
    }
  }

  suspend fun stopRecording(): RecordingResult {
    if (!_isRecording.value) {
      return RecordingResult.NoRecording
    }

    _isRecording.value = false
    _hasStartedWriting.value = false
    timerJob?.cancel()
    timerJob = null

    return withContext(Dispatchers.IO) {
      val hadVideo = videoCaptureHandler.stopRecording()
      val file = tempFile
      tempFile = null

      if (hadVideo && file != null && file.length() > 0L) {
        try {
          val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
          RecordingResult.Completed(uri)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to expose recording uri: ${e.message}", e)
          file.delete()
          RecordingResult.Failed
        }
      } else {
        Log.w(TAG, "No video data was recorded")
        file?.delete()
        RecordingResult.NoRecording
      }
    }
  }

  private fun createTempFile(): File {
    val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
    return File(dir, "temp_recording_${SystemClock.elapsedRealtime()}.mp4")
  }

  fun close() {
    timerJob?.cancel()
    // Release an in-progress recording so the muxer doesn't leak if the owner is torn down
    // mid-recording (e.g. the activity is destroyed).
    if (_isRecording.value) {
      _isRecording.value = false
      runCatching { videoCaptureHandler.stopRecording() }
      tempFile?.delete()
      tempFile = null
    }
  }
}

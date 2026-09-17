/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// HevcDecoder - On-device HEVC decode for the live preview
//
// Decodes the compressed HEVC frames delivered by the DAT SDK and renders them directly
// to a Surface via MediaCodec (the GPU handles YUV->RGB). Replicates the SDK's
// VideoDecoder NAL-parsing / enqueue logic: parse NAL units, cache config, activate on
// the first keyframe, then feed each NAL separately.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HevcDecoder(
  // Observabilidade opcional da pipeline de inferência; não altera recuperação do decoder.
  private val onFailure: (etapa: String, erro: Throwable) -> Unit = { _, _ -> },
) {

  companion object {
    private const val TAG = "HevcDecoder"
    private const val DATA_QUEUE_CAPACITY = 100
    // Hardware HEVC decoders that corrupt this stream into fragments/trash; the SDK's own decoder
    // skips them too. Prefer a software decoder instead (see createHevcDecoder).
    private val BLOCKED_DECODERS = setOf("OMX.Exynos.hevc.dec", "c2.mtk.hevc.decoder")
  }

  private class DecoderFrame(
      val data: ByteBuffer,
      val offset: Int = 0,
      val size: Int = data.remaining(),
      val presentationTimeUs: Long = 0L,
      val isKeyFrame: Boolean = false,
      val isConfigFrame: Boolean = false,
  ) {
    val flags: Int
      get() {
        var bitmask = 0
        if (isKeyFrame) bitmask = bitmask or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (isConfigFrame) bitmask = bitmask or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        return bitmask
      }
  }

  // Serializes producers and native lifecycle only. Callbacks NEVER acquire this lock.
  // CodecCallbackOwner has a separate short lock for identity/state + buffer operations.
  // In particular, native stop/release may wait for callbacks without holding their lock.
  private val lifecycleLock = Any()
  private val owner = CodecCallbackOwner<MediaCodec>()
  private val conclusaoCallbacks = ConclusaoCallbacksCodec()
  private var decoderThread: HandlerThread? = null
  private var codecStarted = false
  private val incomingDataQueue = LinkedBlockingQueue<DecoderFrame>(DATA_QUEUE_CAPACITY)

  private var mediaFormat: MediaFormat? = null
  private var cachedVideoCodec: ByteBuffer? = null
  // Shared with callbacks; all other parsing/setup state belongs to lifecycleLock.
  @Volatile private var active = false
  private var firstInputFrame = true
  private var receivedKeyframe = false
  private var outputSurface: Surface? = null

  private data class Failure(val stage: String, val error: Throwable)

  // Libras Livre: vezes em que a fila de entrada encheu e o decoder desativou até o próximo
  // keyframe — métrica do painel (docs/prontidao-demo/03 §3.8).
  @Volatile var vezesFilaCheia = 0
    private set

  fun start(width: Int, height: Int, surface: Surface) {
    val failures = mutableListOf<Failure>()
    synchronized(lifecycleLock) {
      // One start per instance, including stop-before-start. Existing callers replace the
      // HevcDecoder at each stream/surface; a late producer must not resurrect this one.
      if (!owner.canPrepare()) return
      try {
        outputSurface = surface
        mediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).also {
              it.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
              it.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
              it.setInteger(MediaFormat.KEY_BIT_RATE, 750000)
            }
        // stop/start cannot interleave creation and publication: both own lifecycleLock.
        check(owner.prepare(createHevcDecoder()))
      } catch (e: Exception) {
        failures += Failure("criacao", e)
        stopLocked(failures)
      }
    }
    reportFailures(failures)
  }

  fun decodeFrame(data: ByteArray, presentationTimeUs: Long) {
    if (data.isEmpty()) return
    val failures = mutableListOf<Failure>()
    synchronized(lifecycleLock) {
      if (owner.current() == null) return
      decodeFrameLocked(data, presentationTimeUs, failures)
    }
    reportFailures(failures)
  }

  private fun decodeFrameLocked(
      data: ByteArray,
      presentationTimeUs: Long,
      failures: MutableList<Failure>,
  ) {
    // Replicate SDK VideoDecoder.enqueue(buffer, presentationTimeUs) exactly:
    // parse NAL units, cache config, activate on keyframe, feed each NAL separately.
    val buffer = ByteBuffer.wrap(data)
    val writableByteArray = data.copyOf()
    var index = 0
    val prefixFlags = BooleanArray(3)

    index = findNalUnit(writableByteArray, index, data.size, prefixFlags)
    while (index < data.size) {
      val unitType = getH265NalUnitType(writableByteArray, index)
      val isKeyFrame = isIrapNalType(unitType)
      val isConfigFrame = unitType == 32 || unitType == 33 || unitType == 34

      if (isConfigFrame) {
        cachedVideoCodec = cloneByteBuffer(buffer)
      } else if (isKeyFrame) {
        if (!active) {
          active = true
          cachedVideoCodec?.let { cachedConfig -> enqueuePublic(cachedConfig, failures) }
          if (owner.current() == null) return // Cached config activation failed and tore down.
        }
        if (!receivedKeyframe) {
          receivedKeyframe = true
        }
      }

      enqueuePrivate(
          DecoderFrame(
              data = cloneByteBuffer(buffer),
              offset = index,
              presentationTimeUs = presentationTimeUs,
              isKeyFrame = isKeyFrame,
              isConfigFrame = isConfigFrame,
          ),
          failures = failures,
      )

      // Activation failure is terminal; do not parse/reactivate after teardown.
      if (owner.current() == null) {
        return
      }
      index = findNalUnit(writableByteArray, index + 1, data.size, prefixFlags)
    }
  }

  // Na própria fila de callbacks, apenas solicita a parada fora dela: nunca esperar lifecycle
  // nativo que pode estar aguardando este callback. Owners finais devem usar stopAndDrain.
  fun stop() {
    conclusaoCallbacks.parar {
      val failures = mutableListOf<Failure>()
      synchronized(lifecycleLock) { stopLocked(failures) }
      reportFailures(failures)
    }
  }

  /**
   * Barreira final, inclusive reportFailures dos callbacks e de stop solicitado por onFailure.
   * Chamar após drenar o produtor, de outro job, sem locks. stop() sozinho NÃO é essa barreira.
   */
  suspend fun stopAndDrain() {
    conclusaoCallbacks.pararEDrenar(::stop)
  }

  // Requires lifecycleLock, NOT the callback lock. Invalidation waits only for a buffer
  // operation already in progress, never for poll(1s). The completion helper retains the
  // thread after quit; only stopAndDrain joins it, outside both locks and off main.
  private fun stopLocked(failures: MutableList<Failure>) {
    val codec = owner.stop()
    val thread = decoderThread
    decoderThread = null
    active = false
    incomingDataQueue.clear()
    try {
      if (codec != null) {
        try {
          // A created/configured codec whose start never succeeded only needs release.
          if (codecStarted) codec.stop()
        } catch (e: Throwable) {
          failures += Failure("encerramento", e)
        } finally {
          try {
            codec.release()
          } catch (e: Throwable) {
            failures += Failure("encerramento", e)
          }
        }
      }
    } finally {
      codecStarted = false
      firstInputFrame = true
      receivedKeyframe = false
      cachedVideoCodec = null
      mediaFormat = null
      outputSurface = null
      try {
        thread?.quit()
      } catch (e: Throwable) {
        failures += Failure("encerramento", e)
      }
    }
  }

  // Observers may call back into the owner/caller; never invoke them under either lock.
  private fun reportFailures(failures: List<Failure>) {
    failures.forEach { (stage, error) ->
      Log.e(TAG, "Decoder $stage: ${error.message}", error)
      onFailure(stage, error)
    }
  }

  // Mirrors SDK VideoDecoder's public enqueue(ByteBuffer) — recursive entry for cached config
  private fun enqueuePublic(buffer: ByteBuffer, failures: MutableList<Failure>) {
    val writableByteArray =
        ByteBuffer.allocate(buffer.capacity())
            .apply {
              buffer.rewind()
              put(buffer)
              flip()
              buffer.rewind()
            }
            .array()
    var index = 0
    val prefixFlags = BooleanArray(3)
    index = findNalUnit(writableByteArray, index, buffer.limit(), prefixFlags)
    while (index < buffer.limit()) {
      val unitType = getH265NalUnitType(writableByteArray, index)
      val isKeyFrame = isIrapNalType(unitType)
      val isConfigFrame = unitType == 32 || unitType == 33 || unitType == 34
      if (isConfigFrame) {
        cachedVideoCodec = cloneByteBuffer(buffer)
      } else if (isKeyFrame) {
        if (!receivedKeyframe) {
          receivedKeyframe = true
        }
      }
      enqueuePrivate(
          DecoderFrame(
              data = cloneByteBuffer(buffer),
              offset = index,
              presentationTimeUs = 0,
              isKeyFrame = isKeyFrame,
              isConfigFrame = isConfigFrame,
          ),
          failures = failures,
      )
      if (owner.current() == null) {
        return
      }
      index = findNalUnit(writableByteArray, index + 1, buffer.limit(), prefixFlags)
    }
  }

  // Mirrors SDK VideoDecoder's private enqueue(VideoFrame)
  private fun enqueuePrivate(frame: DecoderFrame, failures: MutableList<Failure>) {
    if (!active || owner.current() == null) return
    if (!frame.isConfigFrame && !receivedKeyframe) return
    if (firstInputFrame) {
      firstInputFrame = false
      if (!activateDecoder(failures)) return
    }
    if (incomingDataQueue.remainingCapacity() == 0) {
      Log.w(TAG, "Decoder queue full")
      vezesFilaCheia++
      active = false
      return
    }
    incomingDataQueue.offer(frame)
  }

  // Prefer a software HEVC decoder
  private fun createHevcDecoder(): MediaCodec {
    val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
    val softwareName =
        MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .firstOrNull { info ->
              !info.isEncoder &&
                  info.isSoftwareOnly &&
                  info.name !in BLOCKED_DECODERS &&
                  info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            ?.name
    return if (softwareName != null) {
      Log.d(TAG, "Using software HEVC decoder: $softwareName")
      MediaCodec.createByCodecName(softwareName)
    } else {
      Log.w(TAG, "No software HEVC decoder found; using platform default")
      MediaCodec.createDecoderByType(mime)
    }
  }

  // Requires lifecycleLock. Only start() creates a codec; activation never recreates one.
  private fun activateDecoder(failures: MutableList<Failure>): Boolean {
    val codec = owner.current() ?: return false
    try {
      val thread = HandlerThread("HevcDecoderThread", Process.THREAD_PRIORITY_VIDEO)
      conclusaoCallbacks.registrar(thread)
      decoderThread = thread
      thread.start()

      // Fresh codec, configured once. No reset/reuse of old callback indices.
      codec.configure(mediaFormat, outputSurface, null, 0)
      codec.setCallback(
          object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
              onInputBuffer(codec, index)
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
              onOutputBuffer(codec, index, info)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
              val failure = owner.use(codec) { Failure("codec", e) }
              failure?.let { reportFailures(listOf(it)) }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
              owner.use(codec) { Unit } // No format state to publish; reject stale callbacks too.
            }
          },
          // Deliver MediaCodec callbacks on our own decoder thread.
          Handler(thread.looper),
      )
      // Enable before start: an async buffer can arrive before start() returns. Native
      // callbacks only expose valid indices; stop cannot interleave under lifecycleLock.
      check(owner.enableCallbacks(codec))
      codec.start()
      codecStarted = true
      return true
    } catch (e: MediaCodec.CodecException) {
      failures += Failure("ativacao_codec", e)
    } catch (e: Throwable) {
      failures += Failure("ativacao", e)
    }
    stopLocked(failures)
    return false
  }

  // Mirrors VideoDecoderBufferHandler.onInputBuffer — feeds ENTIRE buffer with offset
  private fun onInputBuffer(codec: MediaCodec, index: Int) {
    // Check before waiting, then revalidate after waiting. Never retain an input buffer
    // across poll: stop may invalidate and release the codec during this entire second.
    val queue = owner.use(codec) { incomingDataQueue } ?: return
    val frame = try {
      queue.poll(1, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      val failure = owner.use(codec) {
        active = false
        Failure("entrada", e)
      }
      failure?.let { reportFailures(listOf(it)) }
      return
    }
    val failure = owner.use(codec) {
      var bufferQueued = false
      try {
        val inputBuffer = codec.getInputBuffer(index)
        if (frame == null || inputBuffer == null || !active) {
          codec.queueInputBuffer(index, 0, 0, 0, 0)
        } else {
          frame.data.rewind()
          inputBuffer.clear()
          inputBuffer.put(frame.data)
          inputBuffer.flip()
          val clampedSize = minOf(frame.size, inputBuffer.limit() - frame.offset)
          codec.queueInputBuffer(index, frame.offset, clampedSize, frame.presentationTimeUs, frame.flags)
        }
        bufferQueued = true
        null
      } catch (e: Throwable) {
        active = false
        Failure("entrada", e)
      } finally {
        // Even the fallback belongs to the same identity-checked section. An obsolete
        // callback never queues/recycles an index, including an empty buffer.
        if (!bufferQueued) {
          try {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
          } catch (_: Throwable) {}
        }
      }
    }
    failure?.let { reportFailures(listOf(it)) }
  }

  private fun onOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
    val failure = owner.use(codec) {
      try {
        // Render directly to the Surface — the GPU handles YUV→RGB conversion.
        codec.releaseOutputBuffer(index, active && info.size != 0)
        null
      } catch (e: Throwable) {
        try {
          codec.releaseOutputBuffer(index, false)
        } catch (_: Throwable) {}
        Failure("saida", e)
      }
    }
    failure?.let { reportFailures(listOf(it)) }
  }

  // --- NalUnitUtil (copied from SDK) ---

  private fun findNalUnit(
      data: ByteArray,
      startOffset: Int,
      endOffset: Int,
      prefixFlags: BooleanArray,
  ): Int {
    val length = endOffset - startOffset
    if (length == 0) return endOffset

    when {
      prefixFlags[0] -> {
        clearPrefixFlags(prefixFlags)
        return startOffset - 3
      }
      length > 1 && prefixFlags[1] && data[startOffset].toInt() == 1 -> {
        clearPrefixFlags(prefixFlags)
        return startOffset - 2
      }
      length > 2 &&
          prefixFlags[2] &&
          data[startOffset].toInt() == 0 &&
          data[startOffset + 1].toInt() == 1 -> {
        clearPrefixFlags(prefixFlags)
        return startOffset - 1
      }
    }

    val limit = endOffset - 1
    var i = startOffset + 2
    while (i < limit) {
      if ((data[i].toInt() and 0xFE) != 0) {
        // no NAL prefix here or next two positions
      } else if (data[i - 2].toInt() == 0 && data[i - 1].toInt() == 0 && data[i].toInt() == 1) {
        clearPrefixFlags(prefixFlags)
        return i - 2
      } else {
        i -= 2
      }
      i += 3
    }

    prefixFlags[0] =
        if (length > 2)
            (data[endOffset - 3].toInt() == 0 &&
                data[endOffset - 2].toInt() == 0 &&
                data[endOffset - 1].toInt() == 1)
        else
            (if (length == 2)
                (prefixFlags[2] &&
                    data[endOffset - 2].toInt() == 0 &&
                    data[endOffset - 1].toInt() == 1)
            else (prefixFlags[1] && data[endOffset - 1].toInt() == 1))
    prefixFlags[1] =
        if (length > 1) (data[endOffset - 2].toInt() == 0 && data[endOffset - 1].toInt() == 0)
        else (prefixFlags[2] && data[endOffset - 1].toInt() == 0)
    prefixFlags[2] = data[endOffset - 1].toInt() == 0

    return endOffset
  }

  private fun clearPrefixFlags(prefixFlags: BooleanArray) {
    prefixFlags[0] = false
    prefixFlags[1] = false
    prefixFlags[2] = false
  }

  private fun getH265NalUnitType(data: ByteArray, offset: Int): Int {
    if (offset + 3 >= data.size) return -1
    return (data[offset + 3].toInt() and 0x7E) shr 1
  }

  // Any IRAP picture (BLA 16-18, IDR 19-20, CRA 21) is a decode-refresh point the decoder can
  // (re)activate on. Detecting only IDR misses CRA keyframes some encoders emit, which leaves the
  // preview black after the decoder deactivates on a full queue. Must match the recorder's keyframe
  // definition so preview and recording stay consistent.
  private fun isIrapNalType(unitType: Int): Boolean = unitType in 16..21

  private fun cloneByteBuffer(original: ByteBuffer): ByteBuffer {
    val clone: ByteBuffer =
        if (original.isDirect) ByteBuffer.allocateDirect(original.capacity())
        else ByteBuffer.allocate(original.capacity())
    original.rewind()
    clone.put(original)
    original.rewind()
    clone.flip()
    return clone
  }
}

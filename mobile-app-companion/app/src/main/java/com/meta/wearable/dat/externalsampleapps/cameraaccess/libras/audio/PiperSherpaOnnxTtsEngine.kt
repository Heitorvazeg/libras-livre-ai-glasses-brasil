/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// PiperSherpaOnnxTtsEngine - motor real de TTS, local/offline (voz Piper pt-BR via sherpa-onnx)
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4 item 10, §6.6, §7 Fase 2, §8 item 4. Piper roda
// sobre ONNX Runtime (VITS) — não existe caminho de conversão pra TFLite com precedente de sucesso
// (ops dinâmicos do duration predictor quebram a conversão). sherpa-onnx (k2-fsa, Apache 2.0)
// empacota Piper + ONNX Runtime Mobile + eSpeak-ng com uma API Kotlin pronta (OfflineTts) — não é
// dependência Maven (não publicado em Maven Central); o projeto consome o .aar pré-compilado da
// release oficial, variante "static-link-onnxruntime" (não a genérica — essa embute o
// libonnxruntime.so dentro do próprio libsherpa-onnx-jni.so em vez de expor um .so solto, evitando
// colidir com o libonnxruntime.so que a dependência onnxruntime-android do wake word também
// empacota — ver packaging.jniLibs em app/build.gradle.kts), colocado em
// app/libs/sherpa-onnx-1.13.8.aar.
//
// Assets esperados (baixar de https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models,
// vits-piper-pt_BR-edresson-low-int8.tar.bz2, ~21MB, e extrair o CONTEÚDO do tar — não a pasta —
// pra dentro de app/src/main/assets/tts/pt_br/):
//  - tts/pt_br/pt_BR-edresson-low.onnx  (modelo VITS)
//  - tts/pt_br/tokens.txt               (vocabulário)
//  - tts/pt_br/espeak-ng-data/…         (dados do eSpeak-ng — únicos que precisam sair pro
//    filesystem real antes de usar, eSpeak não lê de dentro do APK; o .onnx/tokens.txt continuam
//    lidos direto do AssetManager pelo próprio sherpa-onnx, sem cópia)
//
// Threading segue o padrão do exemplo oficial (MainActivity.kt do sherpa-onnx): geração roda numa
// Thread crua (não um Dispatcher de coroutine) porque generateWithCallback é uma chamada nativa
// bloqueante que não coopera com cancelamento de coroutine — o callback consulta uma flag
// `stopped` a cada chunk de áudio gerado pra poder interromper cedo quando stop()/cancelamento
// pedir.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class PiperSherpaOnnxTtsEngine(context: Context) : TtsEngine {

  private val context: Context = context.applicationContext
  private val loadMutex = Mutex()

  @Volatile private var tts: OfflineTts? = null
  @Volatile private var track: AudioTrack? = null
  @Volatile private var stopped = false

  companion object {
    private const val TAG = "Libras:PiperTtsEngine"

    private const val MODEL_DIR = "tts/pt_br"
    private const val MODEL_FILE = "pt_BR-edresson-low.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val ESPEAK_DATA_SUBDIR = "espeak-ng-data"
  }

  override suspend fun speakAndAwait(text: String) {
    if (text.isBlank()) return
    val engine = ensureLoaded() ?: return
    val audioTrack = ensureAudioTrack(engine.sampleRate())

    stopped = false
    runCatching {
      audioTrack.pause()
      audioTrack.flush()
    }
    audioTrack.play()

    suspendCancellableCoroutine<Unit> { cont ->
      cont.invokeOnCancellation { stopped = true }
      Thread {
            runCatching {
                  engine.generateWithCallback(text = text, sid = 0, speed = 1.0f) { samples ->
                    if (stopped) {
                      0 // sinaliza pro motor nativo interromper a geração
                    } else {
                      audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                      1
                    }
                  }
                }
                .onFailure { e -> Log.e(TAG, "Falha ao gerar áudio Piper pra \"$text\"", e) }
            if (cont.isActive) cont.resume(Unit)
          }
          .start()
    }
  }

  override fun stop() {
    stopped = true
    runCatching {
      track?.pause()
      track?.flush()
    }
  }

  override fun shutdown() {
    stop()
    runCatching { track?.release() }
    track = null
    runCatching { tts?.release() }
    tts = null
  }

  private suspend fun ensureLoaded(): OfflineTts? {
    tts?.let { return it }
    return loadMutex.withLock {
      tts?.let { return it }
      withContext(Dispatchers.IO) {
            runCatching { load() }
                .onFailure { e -> Log.e(TAG, "Falha ao carregar o modelo Piper/sherpa-onnx", e) }
                .getOrNull()
          }
          .also { tts = it }
    }
  }

  private fun load(): OfflineTts {
    val dataDir = copyEspeakDataToFilesystem()
    val config =
        OfflineTtsConfig(
            model =
                OfflineTtsModelConfig(
                    vits =
                        OfflineTtsVitsModelConfig(
                            model = "$MODEL_DIR/$MODEL_FILE",
                            tokens = "$MODEL_DIR/$TOKENS_FILE",
                            dataDir = dataDir,
                        ),
                    numThreads = 2,
                    provider = "cpu",
                ),
        )
    return OfflineTts(assetManager = context.assets, config = config)
  }

  // eSpeak-ng não sabe ler de dentro do APK — só o dataDir precisa sair pro filesystem real; o
  // .onnx/tokens.txt continuam lidos direto do AssetManager por baixo (OfflineTts.newFromAsset).
  private fun copyEspeakDataToFilesystem(): String {
    val destRoot = context.getExternalFilesDir(null)!!
    val destDir = File(destRoot, "$MODEL_DIR/$ESPEAK_DATA_SUBDIR")
    if (!destDir.exists()) {
      copyAssetTree("$MODEL_DIR/$ESPEAK_DATA_SUBDIR", destRoot)
    }
    return destDir.absolutePath
  }

  private fun copyAssetTree(assetPath: String, destRoot: File) {
    val children = context.assets.list(assetPath)
    if (children.isNullOrEmpty()) {
      copyAssetFile(assetPath, destRoot)
      return
    }
    File(destRoot, assetPath).mkdirs()
    for (child in children) copyAssetTree("$assetPath/$child", destRoot)
  }

  private fun copyAssetFile(assetPath: String, destRoot: File) {
    try {
      context.assets.open(assetPath).use { input ->
        FileOutputStream(File(destRoot, assetPath)).use { output -> input.copyTo(output) }
      }
    } catch (e: IOException) {
      Log.e(TAG, "Falha ao copiar asset $assetPath", e)
    }
  }

  private fun ensureAudioTrack(sampleRate: Int): AudioTrack {
    track?.let { return it }
    val bufferSize =
        AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    val attrs =
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    val format =
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
    return AudioTrack(
            attrs, format, bufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        .also { track = it }
  }
}

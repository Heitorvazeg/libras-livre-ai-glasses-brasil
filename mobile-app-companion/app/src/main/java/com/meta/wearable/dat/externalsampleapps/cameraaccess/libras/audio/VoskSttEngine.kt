/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// VoskSttEngine - motor real de STT, local/offline (Vosk pt-BR small)
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4 item 11, §6.4, §7 Fase 5, §8 item 2. Segunda
// implementação de SttEngine — AndroidSpeechRecognizerSttEngine (mesma interface) continua no
// código como implementação-base/fallback; trocar qual o CameraViewModel instancia é a única
// mudança necessária pra ativar este motor.
//
// Diferente de AndroidSpeechRecognizerSttEngine (que não expõe captura própria porque
// android.speech.SpeechRecognizer gerencia o mic internamente), Vosk consome PCM cru — por isso
// depende de PcmMicCapture.kt (configurado pro mic dos óculos, HFP/SCO) pra captura.
//
// Asset esperado: app/src/main/assets/vosk-model-small-pt-0.3/ (baixar de
// https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip, ~31MB, extrair o CONTEÚDO do
// zip — não a pasta — pra dentro dessa pasta de assets). Copiado pro filesystem real na primeira
// vez que o motor é usado (org.vosk.Model não lê de dentro do APK) — cópia simples, idempotente só
// por existência de diretório (não usa o mecanismo de versionamento por arquivo "uuid" da
// StorageService oficial do Vosk, que os zips do site não incluem por padrão); uma atualização do
// app que troque o conteúdo do asset sem trocar o nome da pasta não vai re-copiar sozinha — ok pro
// MVP, revisitar se isso incomodar.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskSttEngine(
    context: Context,
    private val audioCapture: PcmMicCapture,
) : SttEngine {

  private val context: Context = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val loadMutex = Mutex()

  companion object {
    private const val TAG = "Libras:VoskSttEngine"
    private const val MODEL_DIR = "vosk-model-small-pt-0.3"
  }

  @Volatile private var model: Model? = null
  @Volatile private var recognizer: Recognizer? = null
  @Volatile private var stopRequested = false

  private var pendingOnResult: ((String) -> Unit)? = null
  private var pendingOnError: ((Throwable) -> Unit)? = null

  override fun start(onResult: (String) -> Unit, onError: (Throwable) -> Unit) {
    stopRequested = false
    pendingOnResult = onResult
    pendingOnError = onError

    scope.launch {
      val loadedModel = ensureModelLoaded()
      if (loadedModel == null) {
        dispatchError(IllegalStateException("Modelo Vosk indisponível (asset ausente ou falha ao carregar)"))
        return@launch
      }
      if (stopRequested) {
        dispatchError(IllegalStateException("Escuta cancelada antes do modelo carregar"))
        return@launch
      }

      val rec =
          try {
            Recognizer(loadedModel, PcmMicCapture.SAMPLE_RATE.toFloat())
          } catch (e: IOException) {
            dispatchError(e)
            return@launch
          }
      recognizer = rec

      audioCapture.pcmDataCallback = { buffer, offset, size ->
        // Roda na thread de captura (PcmMicCapture), não na main — acceptWaveForm é só
        // uma chamada JNA sobre o ponteiro nativo do recognizer, sem estado compartilhado com a
        // main thread além do próprio `rec`.
        val chunk = if (offset == 0 && size == buffer.size) buffer else buffer.copyOfRange(offset, offset + size)
        rec.acceptWaveForm(chunk, chunk.size)
      }

      if (stopRequested) {
        finalizeAndDispatch(rec)
        return@launch
      }
      if (!audioCapture.startRecording()) {
        dispatchError(IllegalStateException("Captura de áudio do atendente falhou ao iniciar"))
      }
    }
  }

  override fun stop() {
    stopRequested = true
    val rec = recognizer
    // Junta a thread de captura ANTES de fechar o recognizer — garante que nenhuma chamada de
    // acceptWaveForm ainda em voo toque um ponteiro nativo já liberado.
    audioCapture.stopRecording()
    audioCapture.pcmDataCallback = null
    if (rec != null) {
      scope.launch { finalizeAndDispatch(rec) }
    }
    // Se rec ainda for null aqui, start() ainda está carregando o modelo — ele mesmo reage a
    // stopRequested assim que terminar (ver acima).
  }

  private suspend fun finalizeAndDispatch(rec: Recognizer) {
    val json = withContext(Dispatchers.IO) { rec.getFinalResult() }
    runCatching { rec.close() }
    if (recognizer === rec) recognizer = null
    val text = runCatching { JSONObject(json).optString("text", "") }.getOrDefault("")
    dispatchResult(text)
  }

  private fun dispatchResult(text: String) {
    val result = pendingOnResult
    val error = pendingOnError
    pendingOnResult = null
    pendingOnError = null
    if (text.isBlank()) {
      error?.invoke(IllegalStateException("Transcrição vazia"))
    } else {
      result?.invoke(text)
    }
  }

  private fun dispatchError(t: Throwable) {
    val error = pendingOnError
    pendingOnResult = null
    pendingOnError = null
    Log.w(TAG, "Falha na transcrição", t)
    error?.invoke(t)
  }

  private suspend fun ensureModelLoaded(): Model? {
    model?.let { return it }
    return loadMutex.withLock {
      model?.let { return it }
      withContext(Dispatchers.IO) {
            runCatching { loadModel() }.onFailure { e -> Log.e(TAG, "Falha ao carregar modelo Vosk", e) }.getOrNull()
          }
          .also { model = it }
    }
  }

  private fun loadModel(): Model {
    val destRoot = context.getExternalFilesDir(null)!!
    val destDir = File(destRoot, MODEL_DIR)
    if (!destDir.exists()) {
      copyAssetTree(MODEL_DIR, destRoot)
    }
    return Model(destDir.absolutePath)
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
}

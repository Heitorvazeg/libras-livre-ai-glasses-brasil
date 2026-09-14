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
// zip — não a pasta — pra dentro dessa pasta de assets). Copiado pro filesystem real no
// aquecimento (org.vosk.Model não lê de dentro do APK), pela CopiaDeAssets: um marcador de cópia
// completa faz uma cópia interrompida ser refeita (docs/prontidao-demo/05 §5.4, §5.6). Uma
// atualização do app que troque o conteúdo do asset sem trocar o nome da pasta não re-copia sozinha.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
  @Volatile private var encerrado = false

  private var pendingOnResult: ((String) -> Unit)? = null
  private var pendingOnError: ((Throwable) -> Unit)? = null
  @Volatile private var pendingOnFimDeFala: (() -> Unit)? = null

  // Enunciados que o Vosk já fechou nesta escuta (acceptWaveForm devolveu true). O resultado final
  // é isto + o getFinalResult: sem acumular, o texto de antes do fim de fala se perderia.
  private val textoAcumulado = StringBuilder()

  override fun start(onResult: (String) -> Unit, onError: (Throwable) -> Unit, onFimDeFala: () -> Unit) {
    stopRequested = false
    pendingOnResult = onResult
    pendingOnError = onError
    pendingOnFimDeFala = onFimDeFala
    synchronized(textoAcumulado) { textoAcumulado.setLength(0) }

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
        // true = o Vosk fechou um enunciado (silêncio depois de fala): é o fim de fala do 4.1.
        if (rec.acceptWaveForm(chunk, chunk.size)) {
          val trecho = runCatching { JSONObject(rec.result).optString("text", "") }.getOrDefault("").trim()
          if (trecho.isNotEmpty()) {
            synchronized(textoAcumulado) {
              if (textoAcumulado.isNotEmpty()) textoAcumulado.append(' ')
              textoAcumulado.append(trecho)
            }
            pendingOnFimDeFala?.let { aviso -> scope.launch { aviso() } }
          }
        }
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

  /**
   * Teardown final (onCleared do ViewModel): para a escuta e libera o modelo nativo. Um carregamento
   * ainda em curso (aquecimento) fecha o modelo assim que termina.
   */
  fun encerrar() {
    stopRequested = true
    audioCapture.stopRecording()
    audioCapture.pcmDataCallback = null
    scope.cancel()
    pendingOnResult = null
    pendingOnError = null
    pendingOnFimDeFala = null
    recognizer?.let { runCatching { it.close() } }
    recognizer = null
    synchronized(this) {
      encerrado = true
      runCatching { model?.close() }
      model = null
    }
  }

  private suspend fun finalizeAndDispatch(rec: Recognizer) {
    val json = withContext(Dispatchers.IO) { rec.getFinalResult() }
    runCatching { rec.close() }
    if (recognizer === rec) recognizer = null
    val final = runCatching { JSONObject(json).optString("text", "") }.getOrDefault("").trim()
    val text = synchronized(textoAcumulado) { listOf(textoAcumulado.toString(), final).filter { it.isNotBlank() }.joinToString(" ") }
    pendingOnFimDeFala = null
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

  /**
   * Carrega o modelo agora (aquecimento, docs/prontidao-demo/05 §5.4): sem isto, as primeiras palavras
   * da primeira resposta se perdiam enquanto o modelo carregava. Devolve false se não carregou.
   */
  suspend fun carregarModelo(): Boolean = ensureModelLoaded() != null

  private suspend fun ensureModelLoaded(): Model? {
    model?.let { return it }
    return loadMutex.withLock {
      model?.let { return it }
      withContext(Dispatchers.IO) {
        val carregado =
            runCatching { loadModel() }.onFailure { e -> Log.e(TAG, "Falha ao carregar modelo Vosk", e) }.getOrNull()
        synchronized(this@VoskSttEngine) {
          if (encerrado) {
            carregado?.close()
            null
          } else {
            carregado.also { model = it }
          }
        }
      }
    }
  }

  // Cópia para o disco com marcador de cópia completa (5.6): uma cópia interrompida é refeita no
  // próximo carregamento, em vez de deixar a pasta incompleta para sempre.
  private fun loadModel(): Model {
    val destDir =
        CopiaDeAssets.garantir(
            origem = MODEL_DIR,
            destinoRaiz = context.getExternalFilesDir(null) ?: context.filesDir,
            listar = { context.assets.list(it) },
            abrir = { context.assets.open(it) },
        )
    return Model(destDir.absolutePath)
  }
}

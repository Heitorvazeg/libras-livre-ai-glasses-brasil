/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// PiperSherpaOnnxTtsEngine - motor real de TTS, local/offline (voz Piper pt-BR via sherpa-onnx)
//
// Piper roda sobre ONNX Runtime (VITS); sherpa-onnx (k2-fsa, Apache 2.0) empacota Piper + ONNX
// Runtime Mobile + eSpeak-ng com uma API Kotlin pronta (OfflineTts). O projeto consome o .aar
// pré-compilado da release oficial, variante "static-link-onnxruntime" (ver packaging.jniLibs em
// app/build.gradle.kts), em app/libs/sherpa-onnx-1.13.8.aar.
//
// Assets (app/src/main/assets/tts/pt_br/): pt_BR-edresson-low.onnx, tokens.txt e espeak-ng-data/ —
// este último é o único copiado para o disco, porque o eSpeak não lê de dentro do APK.
//
// Threading segue o exemplo oficial: a geração roda numa Thread crua porque generateWithCallback é
// nativo e bloqueante; o callback consulta `stopped` a cada trecho para interromper cedo.
//
// Libras Livre, prontidão da demo (docs/prontidao-demo/05-audio.md):
//   - 5.1: saída selecionável — "celular" fixa o alto-falante do aparelho; "óculos" usa o roteamento
//     padrão (A2DP).
//   - 5.3: só devolve depois de o áudio terminar de TOCAR, não de ser gerado.
//   - 5.5: devolve false quando não fala (modelo que não carregou, síntese que falhou), para a
//     TtsEmCadeia usar a reserva; aquecer() carrega o modelo e pré-sintetiza frases fixas.
//   - 5.6: a cópia do espeak-ng-data usa a CopiaDeAssets (marcador de cópia completa).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.SaidaVoz
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PiperSherpaOnnxTtsEngine(
    context: Context,
    private val saida: () -> SaidaVoz = { SaidaVoz.OCULOS },
) : TtsEngine {

  private val context: Context = context.applicationContext
  private val loadMutex = Mutex()

  @Volatile private var tts: OfflineTts? = null
  @Volatile private var track: AudioTrack? = null
  @Volatile private var stopped = false
  // Depois do shutdown, um carregamento que ainda estava em curso libera o modelo em vez de guardá-lo:
  // o aquecimento (6.4) pode estar no meio do load quando o ViewModel vai embora.
  @Volatile private var encerrado = false

  // Frases pré-sintetizadas no aquecimento (os avisos do "repita", 2.8): tocam sem esperar a síntese.
  private val preSintetizadas = ConcurrentHashMap<String, FloatArray>()

  companion object {
    private const val TAG = "Libras:PiperTtsEngine"

    private const val MODEL_DIR = "tts/pt_br"
    private const val MODEL_FILE = "pt_BR-edresson-low.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val ESPEAK_DATA_SUBDIR = "espeak-ng-data"
  }

  override suspend fun aquecer(frases: List<String>): Boolean {
    val engine = ensureLoaded() ?: return false
    return withContext(Dispatchers.Default) {
      runCatching {
            for (frase in frases) {
              if (frase.isBlank() || preSintetizadas.containsKey(frase)) continue
              val audio = engine.generate(frase, 0, 1.0f)
              if (audio.samples.isEmpty()) error("síntese vazia para \"$frase\"")
              preSintetizadas[frase] = audio.samples
            }
          }
          .onFailure { Log.e(TAG, "Falha ao pré-sintetizar", it) }
          .isSuccess
    }
  }

  override suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit): Boolean {
    if (text.isBlank()) return true
    val engine = ensureLoaded() ?: return false
    val audioTrack = runCatching { ensureAudioTrack(engine.sampleRate()) }.getOrElse {
      Log.e(TAG, "AudioTrack indisponível", it)
      return false
    }
    aplicarSaida(audioTrack)

    stopped = false
    runCatching {
      audioTrack.pause()
      audioTrack.flush()
    }
    audioTrack.play()

    return suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { stopped = true }
      Thread {
            var amostrasEscritas = 0L
            fun escrever(samples: FloatArray) {
              if (amostrasEscritas == 0L) runCatching(onInicioAudio)
              audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
              amostrasEscritas += samples.size
            }
            val gerou =
                runCatching {
                      val pronta = preSintetizadas[text]
                      if (pronta != null) {
                        escrever(pronta)
                      } else {
                        engine.generateWithCallback(text = text, sid = 0, speed = 1.0f) { samples ->
                          if (stopped) {
                            0 // sinaliza pro motor nativo interromper a geração
                          } else {
                            escrever(samples)
                            1
                          }
                        }
                      }
                    }
                    .onFailure { e -> Log.e(TAG, "Falha ao gerar áudio Piper pra \"$text\"", e) }
                    .isSuccess
            esperarFimDaReproducao(audioTrack, amostrasEscritas)
            // Interrompido por stop() conta como falado: não é falha do motor.
            if (cont.isActive) cont.resume(gerou && (amostrasEscritas > 0 || stopped))
          }
          .start()
    }
  }

  /**
   * A geração acaba antes do som (5.3): o último trecho ainda está no buffer do AudioTrack. Com a
   * escuta abrindo sozinha depois da fala (4.1), o microfone do celular pegaria o fim da própria
   * frase. Espera a posição de reprodução alcançar o que foi escrito, olhando a cada 20 ms, com teto
   * de duração esperada + 1 s (saída que não consome não trava).
   */
  private fun esperarFimDaReproducao(audioTrack: AudioTrack, amostrasEscritas: Long) {
    if (amostrasEscritas <= 0 || stopped) return
    val tetoMs = amostrasEscritas * 1000 / audioTrack.sampleRate + 1000
    val inicio = SystemClock.elapsedRealtime()
    while (!stopped &&
        (audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < amostrasEscritas &&
        SystemClock.elapsedRealtime() - inicio < tetoMs) {
      Thread.sleep(20)
    }
  }

  // 5.1: "celular" fixa o alto-falante do aparelho; "óculos" volta ao roteamento padrão (A2DP).
  private fun aplicarSaida(audioTrack: AudioTrack) {
    val alvo =
        if (saida() == SaidaVoz.CELULAR) {
          val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
          audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
          }
        } else {
          null
        }
    runCatching { audioTrack.setPreferredDevice(alvo) }
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
    synchronized(this) {
      encerrado = true
      runCatching { tts?.release() }
      tts = null
    }
    preSintetizadas.clear()
  }

  private suspend fun ensureLoaded(): OfflineTts? {
    tts?.let { return it }
    return loadMutex.withLock {
      tts?.let { return it }
      withContext(Dispatchers.IO) {
        val carregado =
            runCatching { load() }
                .onFailure { e -> Log.e(TAG, "Falha ao carregar o modelo Piper/sherpa-onnx", e) }
                .getOrNull()
        synchronized(this@PiperSherpaOnnxTtsEngine) {
          if (encerrado) {
            carregado?.release()
            null
          } else {
            carregado.also { tts = it }
          }
        }
      }
    }
  }

  private fun load(): OfflineTts {
    val raiz = context.getExternalFilesDir(null) ?: context.filesDir
    val dataDir =
        CopiaDeAssets.garantir(
            origem = "$MODEL_DIR/$ESPEAK_DATA_SUBDIR",
            destinoRaiz = raiz,
            listar = { context.assets.list(it) },
            abrir = { context.assets.open(it) },
        )
    val config =
        OfflineTtsConfig(
            model =
                OfflineTtsModelConfig(
                    vits =
                        OfflineTtsVitsModelConfig(
                            model = "$MODEL_DIR/$MODEL_FILE",
                            tokens = "$MODEL_DIR/$TOKENS_FILE",
                            dataDir = dataDir.absolutePath,
                        ),
                    numThreads = 2,
                    provider = "cpu",
                ),
        )
    return OfflineTts(assetManager = context.assets, config = config)
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

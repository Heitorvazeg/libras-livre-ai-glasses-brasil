/*
 * Libras Livre — síntese de voz (TextToSpeech) para falar o sinal reconhecido.
 *
 * A "última milha" do fluxo: sinal reconhecido pela API -> voz, para quem não
 * conhece Libras entender. Pequeno de propósito; a contextualização sinal->frase
 * (juntar palavras numa frase natural) é trabalho futuro, fora deste andaime.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class Speaker(context: Context) {

  companion object {
    private const val TAG = "Libras:Speaker"
  }

  @Volatile private var ready = false

  // O OnInitListener dispara de forma assíncrona, depois que o construtor retorna —
  // então `tts` já está atribuído quando o callback acessa setLanguage().
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          val result = tts.setLanguage(Locale("pt", "BR"))
          ready =
              result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
          if (!ready) Log.w(TAG, "pt-BR indisponível no TTS do aparelho (result=$result)")
        } else {
          Log.e(TAG, "Falha ao inicializar TextToSpeech (status=$status)")
        }
      }

  fun speak(text: String) {
    if (!ready) {
      Log.w(TAG, "TTS ainda não está pronto — ignorando \"$text\"")
      return
    }
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "libras-$text")
  }

  /**
   * Como [speak], mas suspende até o TTS terminar de falar (`onDone`/`onError`) — usado pelo
   * DialogOrchestrator pra sequenciar a transição ③→④ sem `delay()` arbitrário (ver
   * docs/orquestracao-dialogo-audio-plano.md §6.2).
   */
  suspend fun speakAndAwait(text: String) {
    if (!ready) {
      Log.w(TAG, "TTS ainda não está pronto — ignorando \"$text\"")
      return
    }
    val utteranceId = "libras-${System.nanoTime()}"
    suspendCancellableCoroutine<Unit> { cont ->
      tts.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
              if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
              if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }

            override fun onError(id: String?, errorCode: Int) {
              if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
          }
      )
      cont.invokeOnCancellation { tts.stop() }
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
  }

  fun shutdown() {
    runCatching {
      tts.stop()
      tts.shutdown()
    }
  }
}

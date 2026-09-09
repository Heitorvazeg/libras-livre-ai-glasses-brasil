/*
 * Libras Livre — síntese de voz (TextToSpeech) para falar o sinal reconhecido.
 *
 * A "última milha" do fluxo: sinal reconhecido pela API -> voz, para quem não
 * conhece Libras entender. Pequeno de propósito; a contextualização sinal->frase
 * (juntar palavras numa frase natural) é trabalho futuro, fora deste andaime.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

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

  fun shutdown() {
    runCatching {
      tts.stop()
      tts.shutdown()
    }
  }
}

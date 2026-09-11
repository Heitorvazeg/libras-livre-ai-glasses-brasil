/*
 * Libras Livre — síntese de voz para falar o sinal reconhecido/a frase da sessão.
 *
 * A "última milha" do fluxo: sinal reconhecido pela API -> voz, para quem não
 * conhece Libras entender. Pequeno de propósito; a contextualização sinal->frase
 * (juntar palavras numa frase natural) é trabalho futuro, fora deste andaime.
 *
 * Casca fina sobre TtsEngine.kt (docs/orquestracao-dialogo-audio-plano.md §4 item 10, §6.6) — só
 * delega. Existe pra o DialogOrchestrator continuar chamando `speaker.speakAndAwait(...)` sem
 * conhecer qual motor está por baixo (Android nativo vs. Piper local); trocar de motor é só trocar
 * o TtsEngine passado no construtor, sem tocar no orquestrador.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context

class Speaker(context: Context, private val engine: TtsEngine = AndroidTextToSpeechEngine(context)) {

  /**
   * Fala [text] e suspende até terminar (nunca lança) — usado pelo DialogOrchestrator pra
   * sequenciar a transição ③→④ sem `delay()` arbitrário (ver
   * docs/orquestracao-dialogo-audio-plano.md §6.2).
   */
  suspend fun speakAndAwait(text: String) = engine.speakAndAwait(text)

  fun stop() = engine.stop()

  fun shutdown() = engine.shutdown()
}

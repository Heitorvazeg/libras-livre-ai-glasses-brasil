/*
 * Libras Livre — fachada da voz usada pelo DialogOrchestrator.
 *
 * Casca fina sobre TtsEngine.kt: o orquestrador chama `speaker.speakAndAwait(...)` sem conhecer o
 * motor por baixo. No app, o motor é a TtsEmCadeia (Piper com o TTS do Android como reserva).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.content.Context

class Speaker(context: Context, private val engine: TtsEngine = AndroidTextToSpeechEngine(context)) {

  /** Fala [text] e suspende até terminar; false se nenhum motor conseguiu falar. */
  suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit = {}): Boolean =
      engine.speakAndAwait(text, onInicioAudio)

  /** Carrega a voz e prepara [frases] sem tocar (6.4). */
  suspend fun aquecer(frases: List<String>): Boolean = engine.aquecer(frases)

  fun stop() = engine.stop()

  fun shutdown() = engine.shutdown()
}

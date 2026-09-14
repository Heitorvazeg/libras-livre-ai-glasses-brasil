/*
 * Libras Livre — voz em cadeia: Piper, e o TTS do Android se o Piper falhar
 * (docs/prontidao-demo/05-audio.md §5.5, 08-memoria.md §8.3).
 *
 * Antes, um Piper que não carregava deixava o app mudo em silêncio. Aqui a primeira falha do motor
 * principal (ao aquecer ou ao falar) troca para a reserva, que é CRIADA só nessa hora — um motor de
 * cada tipo carregado por vez —, avisa uma vez (faixa de estado: "voz de reserva em uso") e fica na
 * reserva até o app reiniciar. A frase que falhou é falada pela reserva.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.util.Log

class TtsEmCadeia(
    private val principal: TtsEngine,
    private val criarReserva: () -> TtsEngine,
    private val onReserva: () -> Unit = {},
) : TtsEngine {

  private companion object {
    const val TAG = "Libras:TtsEmCadeia"
  }

  @Volatile private var reserva: TtsEngine? = null

  /** true depois da primeira falha do principal. */
  @Volatile var emReserva = false
    private set

  /** A reserva existe (8.1 decide se pode ser liberada). */
  val reservaCriada: Boolean
    get() = reserva != null

  override suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit): Boolean {
    if (!emReserva) {
      if (principal.speakAndAwait(text, onInicioAudio)) return true
      trocarParaReserva("falhou ao falar")
    }
    return reservaOuCriar().speakAndAwait(text, onInicioAudio)
  }

  override suspend fun aquecer(frases: List<String>): Boolean {
    if (!emReserva && principal.aquecer(frases)) return true
    if (!emReserva) trocarParaReserva("falhou ao aquecer")
    reservaOuCriar().aquecer(frases)
    return false
  }

  override fun stop() {
    if (emReserva) reserva?.stop() else principal.stop()
  }

  override fun shutdown() {
    principal.shutdown()
    reserva?.shutdown()
    reserva = null
  }

  /** 8.1: libera a reserva se ela existe e não está em uso. Devolve true se liberou. */
  fun liberarReservaOciosa(): Boolean {
    if (emReserva) return false
    val r = reserva ?: return false
    r.shutdown()
    reserva = null
    return true
  }

  @Synchronized
  private fun trocarParaReserva(motivo: String) {
    if (emReserva) return
    Log.w(TAG, "motor principal $motivo — usando a voz de reserva até reiniciar o app")
    emReserva = true
    onReserva()
  }

  @Synchronized
  private fun reservaOuCriar(): TtsEngine = reserva ?: criarReserva().also { reserva = it }
}

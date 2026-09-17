package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

/**
 * Autoridade de callbacks, independente do Android. Nunca executar espera de fila, lifecycle
 * nativo (configure/start/stop/release) ou código do caller dentro de [use].
 * O dono serializa criação/ativação/parada separadamente; stop é terminal nesta instância.
 */
internal class CodecCallbackOwner<C : Any> {
  private enum class State { NEW, PREPARED, RUNNING, STOPPED }

  private val lock = Any()
  private var state = State.NEW
  private var codec: C? = null

  fun canPrepare(): Boolean = synchronized(lock) { state == State.NEW }

  fun prepare(candidate: C): Boolean = synchronized(lock) {
    if (state != State.NEW) return@synchronized false
    codec = candidate
    state = State.PREPARED
    true
  }

  fun current(): C? = synchronized(lock) { codec }

  fun enableCallbacks(candidate: C): Boolean = synchronized(lock) {
    if (state != State.PREPARED || codec !== candidate) return@synchronized false
    state = State.RUNNING
    true
  }

  /** A validação e TODO acesso ao buffer pertencem à mesma seção crítica. */
  fun <R> use(candidate: C, action: () -> R): R? = synchronized(lock) {
    if (state != State.RUNNING || codec !== candidate) return@synchronized null
    action()
  }

  /** Invalida primeiro; devolve ownership exclusivo da liberação, fora deste lock. */
  fun stop(): C? = synchronized(lock) {
    val retired = codec
    codec = null
    state = State.STOPPED
    retired
  }
}
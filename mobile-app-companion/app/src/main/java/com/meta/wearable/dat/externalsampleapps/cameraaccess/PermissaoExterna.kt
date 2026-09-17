package com.meta.wearable.dat.externalsampleapps.cameraaccess

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * Ponte sem Android para um launcher sem ID no resultado. Todas as operações são na main.
 * Cancelar quem espera NÃO cancela o pedido externo nem libera seu slot. Só o callback (ou
 * falha síncrona ao lançar) libera a exclusão. O deferred não é filho da coroutine solicitante.
 * Dois launchers podem compartilhar a exclusão sem compartilhar o destino dos resultados.
 */
class PermissaoExterna<I, T>(private val exclusao: Mutex = Mutex()) {
  private class Launcher<I>(val dono: Any, val lancar: (I) -> Unit)
  private val launcher = MutableStateFlow<Launcher<I>?>(null)
  private var pendente: CompletableDeferred<T>? = null

  fun associar(dono: Any, lancar: (I) -> Unit) {
    launcher.value = Launcher(dono, lancar)
  }

  fun dissociar(dono: Any) {
    if (launcher.value?.dono === dono) launcher.value = null
  }

  suspend fun solicitar(entrada: I): T {
    val pedido = CompletableDeferred<T>()
    exclusao.lock(pedido)
    try {
      while (true) {
        currentCoroutineContext().ensureActive()
        // Não guardar launcher/Activity antes de uma suspensão. Reconsultar mesmo depois
        // de first: a Activity que acordou a espera pode ter parado antes da retomada.
        val atual = launcher.value
        if (atual != null) {
          pendente = pedido
          atual.lancar(entrada)
          break
        }
        launcher.first { it != null }
      }
    } catch (erro: Throwable) {
      if (pendente === pedido) pendente = null
      // Inclui cancelamento esperando Activity; callback síncrono pode já ter liberado.
      if (exclusao.holdsLock(pedido)) exclusao.unlock(pedido)
      throw erro
    }
    // Sem finally que libere o slot: o app externo pode retornar depois do cancelamento.
    return pedido.await()
  }

  fun receber(resultado: T) {
    val pedido = pendente ?: return
    // Limpar ANTES de acordar qualquer coroutine (inclusive Main.immediate/reentrante).
    pendente = null
    exclusao.unlock(pedido)
    pedido.complete(resultado)
  }
}
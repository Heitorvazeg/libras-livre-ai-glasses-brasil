package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Retém a fila até o término REAL da thread, incluindo observadores fora de CodecCallbackOwner.use.
 * O dono registra a thread antes de start, serializa o lifecycle e solicita quit na parada.
 * Nenhum lock do dono/callback pode envolver [pararEDrenar].
 */
internal class ConclusaoCallbacksCodec {
  @Volatile private var thread: Thread? = null
  private val lock = Any()
  private var paradaDoCallback: Deferred<Unit>? = null

  fun registrar(fila: Thread) {
    check(thread == null) { "Uma única fila de callbacks por decoder" }
    thread = fila
  }

  fun parar(acao: () -> Unit) {
    if (Thread.currentThread() !== thread) {
      acao()
      return
    }
    // onFailure pode chamar stop enquanto outra thread está em stop/release nativo,
    // esperando este callback. Não disputar o lifecycle nem liberar o codec nesta fila.
    val pendente = synchronized(lock) {
      paradaDoCallback ?: CoroutineScope(Dispatchers.IO).async(start = CoroutineStart.LAZY) {
        acao()
      }.also { paradaDoCallback = it }
    }
    pendente.start()
  }

  /** Chamar de outro job/thread, após drenar produtores; cancelamento não encurta a barreira. */
  suspend fun pararEDrenar(parar: () -> Unit) {
    check(Thread.currentThread() !== thread) { "Callback não pode aguardar a própria conclusão" }
    withContext(NonCancellable + Dispatchers.IO) {
      try {
        parar()
      } finally {
        // quit apenas descarta mensagens pendentes; join inclui o callback já despachado e
        // TODO reportFailures. Não limpar a referência no stop, nem usar timeout como sucesso.
        thread?.join()
        // O último callback ainda pode ter solicitado stop durante o join acima.
        val pendente = synchronized(lock) { paradaDoCallback }
        pendente?.await()
      }
    }
  }
}
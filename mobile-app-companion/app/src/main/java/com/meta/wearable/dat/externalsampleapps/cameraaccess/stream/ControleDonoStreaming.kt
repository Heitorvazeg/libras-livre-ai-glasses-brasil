package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

/** Estado desejado do serviço, retido no processo e confinado à main, inclusive o envio. */
class ControleDonoStreaming {
  data class Comando(val revisao: Long, val dono: String, val iniciar: Boolean)
  data class Decisao(val donoAtivo: String?, val intentDesatualizado: Boolean)

  private var revisao = 0L
  private var atual: Comando? = null

  fun iniciar(dono: String, enviar: (Comando) -> Unit) {
    publicar(Comando(++revisao, dono, true), enviar)
  }

  fun parar(dono: String, enviar: (Comando) -> Unit) {
    val anterior = atual ?: return
    if (!anterior.iniciar || anterior.dono != dono) return
    publicar(Comando(++revisao, dono, false), enviar)
  }

  private fun publicar(comando: Comando, enviar: (Comando) -> Unit) {
    val anterior = atual
    atual = comando
    try {
      enviar(comando)
    } catch (erro: Throwable) {
      // Uma rejeição síncrona do Android não transfere ownership. A sequência não recua.
      if (atual == comando) atual = anterior
      throw erro
    }
  }

  fun receber(intent: Comando?): Decisao {
    // O Intent é apenas uma notificação de mudança, NÃO a autoridade. START antigo após
    // STOP não ressuscita dono; STOP antigo após START novo não derruba o sucessor.
    // Sem estado no processo (inclusive restart/null intent), não há stream a restaurar.
    val desejado = atual
    return Decisao(
        donoAtivo = desejado?.takeIf { it.iniciar }?.dono,
        intentDesatualizado = intent == null || intent != desejado,
    )
  }
}
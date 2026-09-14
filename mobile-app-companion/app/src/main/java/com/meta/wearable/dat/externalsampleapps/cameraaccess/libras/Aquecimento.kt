/*
 * Libras Livre — aquecimento com diagnóstico (docs/prontidao-demo/06-latencia.md §6.4).
 *
 * Tudo o que é pesado carrega ao abrir o app, em segundo plano e em SEQUÊNCIA (para não disputar CPU
 * consigo mesmo), e a tela mostra o que deu certo: ✓ ou ✗, com o tempo e o motivo. Um ✗ aparece na
 * hora, e não no meio de uma sessão. O botão principal fica habilitado quando as etapas que bloqueiam
 * terminarem (com ✓ ou ✗) — o avatar pode continuar carregando.
 *
 * Sem dependência de Android, para testar a ordem e o registro na JVM.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

enum class StatusEtapa {
  PENDENTE,
  EXECUTANDO,
  OK,
  FALHOU,
}

data class ResultadoEtapa(
    val nome: String,
    val status: StatusEtapa = StatusEtapa.PENDENTE,
    val ms: Long? = null,
    val motivo: String? = null,
)

/**
 * @param bloqueiaIniciar o "iniciar" espera esta etapa terminar (etapas 1 a 5 do plano).
 * @param executar lança para marcar ✗; a mensagem vira o motivo.
 */
class EtapaAquecimento(val nome: String, val bloqueiaIniciar: Boolean, val executar: suspend () -> Unit)

class Aquecimento(
    private val etapas: List<EtapaAquecimento>,
    private val relogioMs: () -> Long,
    private val onMudanca: (resultados: List<ResultadoEtapa>, prontoParaIniciar: Boolean) -> Unit,
) {

  /** Roda as etapas em ordem; nunca lança. */
  suspend fun executar(): List<ResultadoEtapa> {
    val resultados = etapas.map { ResultadoEtapa(it.nome) }.toMutableList()
    fun publicar() = onMudanca(resultados.toList(), pronto(resultados))
    publicar()
    for ((i, etapa) in etapas.withIndex()) {
      resultados[i] = resultados[i].copy(status = StatusEtapa.EXECUTANDO)
      publicar()
      val inicio = relogioMs()
      val falha =
          try {
            etapa.executar()
            null
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Throwable) {
            e
          }
      val ms = relogioMs() - inicio
      resultados[i] =
          if (falha == null) resultados[i].copy(status = StatusEtapa.OK, ms = ms)
          else resultados[i].copy(status = StatusEtapa.FALHOU, ms = ms, motivo = falha.message ?: falha.javaClass.simpleName)
      publicar()
    }
    return resultados
  }

  private fun pronto(resultados: List<ResultadoEtapa>): Boolean =
      etapas.indices.all { !etapas[it].bloqueiaIniciar || resultados[it].status in setOf(StatusEtapa.OK, StatusEtapa.FALHOU) }
}

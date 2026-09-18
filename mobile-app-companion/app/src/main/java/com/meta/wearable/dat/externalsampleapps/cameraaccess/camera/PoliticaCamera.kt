package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Autoridade de abertura, compartilhada pelo diálogo e pelo preview sample. Mutações na main.
 * A geração invalida permissões/esperas antigas mesmo após um novo Aceitar (problema ABA).
 * Parar o stream não revoga o consentimento: Corrigir e preview podem reabrir no mesmo turno.
 *
 * FRONTEIRA DO ATENDIMENTO: o consentimento é por atendimento, e [revogarConsentimento] é o único
 * jeito de acabar com ele — Cancelar, Recusar, ocioso (60 s), fim de preview, novo atendimento
 * sem consentimento e onCleared do VM passam todos por aqui. Por isso o que precisa morrer junto
 * com o atendimento (hoje, o cache de glosa do avatar) se pendura em [aoEncerrarAtendimento], e
 * não em cada caminho de saída do DialogOrchestrator: um caminho de fim novo que revogue o
 * consentimento limpa o resto sem ninguém lembrar, e um que não revogue já estaria errado pelo
 * consentimento. Modo economia e "iniciar" que reaproveita o consentimento NÃO passam por aqui.
 */
class PoliticaCamera(private val aoEncerrarAtendimento: () -> Unit = {}) {
  private val _geracao = MutableStateFlow(0L)
  val geracao = _geracao.asStateFlow()
  var consentimento = false
    private set
  var economia = false
    private set

  val permitida: Boolean get() = consentimento && !economia

  fun token(): Long? = if (permitida) geracao.value else null

  fun valida(token: Long): Boolean = permitida && token == geracao.value

  fun aceitarConsentimento() {
    consentimento = true
    invalidarAbertura()
  }

  fun revogarConsentimento() {
    consentimento = false
    invalidarAbertura()
    aoEncerrarAtendimento()
  }

  fun ativarEconomia() {
    if (economia) return
    economia = true
    invalidarAbertura()
  }

  fun invalidarAbertura() {
    _geracao.value++
  }
}
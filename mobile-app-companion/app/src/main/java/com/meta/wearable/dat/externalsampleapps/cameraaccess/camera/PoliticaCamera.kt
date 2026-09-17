package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Autoridade de abertura, compartilhada pelo diálogo e pelo preview sample. Mutações na main.
 * A geração invalida permissões/esperas antigas mesmo após um novo Aceitar (problema ABA).
 * Parar o stream não revoga o consentimento: Corrigir e preview podem reabrir no mesmo turno.
 */
class PoliticaCamera {
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
package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PoliticaCameraTest {
  @Test
  fun `preview e redirect precisam de consentimento e nao podem sair da economia`() {
    val politica = PoliticaCamera()
    assertNull(politica.token())
    politica.aceitarConsentimento()
    assertTrue(politica.valida(politica.token()!!))
    politica.ativarEconomia()
    assertNull(politica.token())
    politica.revogarConsentimento()
    politica.aceitarConsentimento()
    assertNull(politica.token())
    assertTrue(politica.economia)
  }

  @Test
  fun `parada invalida abertura mesmo sem stream e permite reabrir com novo token`() {
    val politica = PoliticaCamera()
    politica.aceitarConsentimento()
    val antigo = politica.token()!!
    politica.invalidarAbertura()
    assertFalse(politica.valida(antigo))
    assertTrue(politica.consentimento)
    assertTrue(politica.valida(politica.token()!!))
  }

  @Test
  fun `recusar seguido de aceitar nao revalida callback da geracao antiga`() {
    val politica = PoliticaCamera()
    politica.aceitarConsentimento()
    val antigo = politica.token()!!
    politica.revogarConsentimento()
    assertNull(politica.token())
    politica.aceitarConsentimento()
    assertFalse(politica.valida(antigo))
    assertTrue(politica.valida(politica.token()!!))
  }

  @Test
  fun `permissao concedida tarde nao abre apos parada recusa ou economia`() = runTest {
    for (cancelar in listOf<(PoliticaCamera) -> Unit>(
        { it.invalidarAbertura() }, { it.revogarConsentimento() }, { it.ativarEconomia() },
    )) {
      val politica = PoliticaCamera()
      politica.aceitarConsentimento()
      val token = politica.token()!!
      val permissao = CompletableDeferred<Unit>()
      var aberturas = 0
      // Simula fonte que entrega callback mesmo sem cooperar com cancelamento de Job.
      val callback = launch {
        permissao.await()
        if (politica.valida(token)) aberturas++
      }
      runCurrent()
      cancelar(politica)
      permissao.complete(Unit)
      runCurrent()
      assertTrue(callback.isCompleted)
      assertEquals(0, aberturas)
    }
  }
}
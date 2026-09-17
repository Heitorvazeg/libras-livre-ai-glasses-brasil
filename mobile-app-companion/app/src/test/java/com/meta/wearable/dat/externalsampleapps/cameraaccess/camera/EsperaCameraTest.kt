package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EsperaCameraTest {
  private enum class Estado { TECNICA, PERMISSAO, PRONTA, INVALIDADA }

  private suspend fun esperar(estados: MutableStateFlow<Estado>) = aguardarCameraSemContarPermissao(
      estados, 8_000L,
      aguardandoPermissao = { it == Estado.PERMISSAO },
      concluida = { it == Estado.PRONTA || it == Estado.INVALIDADA },
  )

  @Test
  fun `decisao humana demorada nao gasta prazo tecnico apos permissao`() = runTest {
    val estados = MutableStateFlow(Estado.TECNICA)
    val espera = async { esperar(estados) }
    runCurrent()
    advanceTimeBy(7_000)
    estados.value = Estado.PERMISSAO
    runCurrent()
    advanceTimeBy(120_000) // diálogo local + fila/redirect externo, sem teto de decisão.
    runCurrent()
    assertFalse(espera.isCompleted)
    estados.value = Estado.TECNICA
    runCurrent()
    advanceTimeBy(7_999)
    assertFalse(espera.isCompleted)
    estados.value = Estado.PRONTA
    runCurrent()
    assertEquals(Estado.PRONTA, espera.await())
  }

  @Test
  fun `abertura tecnica continua limitada a oito segundos`() = runTest {
    val estados = MutableStateFlow(Estado.PERMISSAO)
    val espera = async { esperar(estados) }
    runCurrent()
    advanceTimeBy(90_000)
    estados.value = Estado.TECNICA
    runCurrent()
    advanceTimeBy(8_000)
    runCurrent()
    assertNull(espera.await())
  }

  @Test
  fun `consulta tecnica sem resposta tambem tem prazo`() = runTest {
    val espera = async { esperar(MutableStateFlow(Estado.TECNICA)) }
    runCurrent()
    advanceTimeBy(8_000)
    runCurrent()
    assertNull(espera.await())
  }

  @Test
  fun `invalida token durante espera humana sem aguardar callback`() = runTest {
    val estados = MutableStateFlow(Estado.PERMISSAO)
    val espera = async { esperar(estados) }
    runCurrent()
    estados.value = Estado.INVALIDADA
    runCurrent()
    assertEquals(Estado.INVALIDADA, espera.await())
  }

  @Test
  fun `cancelamento humano nao vira timeout nem falha de camera`() = runTest {
    val estados = MutableStateFlow(Estado.PERMISSAO)
    val espera = async { esperar(estados) }
    runCurrent()
    espera.cancelAndJoin()
    estados.value = Estado.PRONTA
    runCurrent()
    assertTrue(espera.isCancelled)
  }
}
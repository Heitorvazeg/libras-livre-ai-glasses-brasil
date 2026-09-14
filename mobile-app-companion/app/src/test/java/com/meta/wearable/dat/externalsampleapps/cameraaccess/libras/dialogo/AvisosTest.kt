/*
 * Faixa de estado (docs/prontidao-demo/10-tela.md §10.2) e indicadores da captura (3.1, 3.5, 1.11).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.Enquadramento
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.EstadoSinalizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LibrasState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvisosTest {

  private val textos =
      TextosCaptura(
          aguarde = "Aguarde…",
          sinalizando = { "● sinalizando · $it" },
          parado = { "○ parado · $it" },
          troncoFora = "Afaste-se: tronco fora do quadro",
          ninguem = "Ninguém no quadro",
      )

  @Test
  fun `bloqueio vence atencao mesmo sendo mais antigo`() {
    val escolhido =
        Avisos.escolherAviso(
            listOf(
                Aviso(TipoAviso.VOZ_RESERVA, "voz de reserva", desdeMs = 900),
                Aviso(TipoAviso.CAMERA_NAO_SUBIU, "Óculos não conectados", desdeMs = 100),
                Aviso(TipoAviso.CAPTURA, "Aguarde…", desdeMs = 1_000),
            ))
    assertEquals(TipoAviso.CAMERA_NAO_SUBIU, escolhido?.tipo)
  }

  @Test
  fun `dentro do mesmo nivel vence o mais recente`() {
    val escolhido =
        Avisos.escolherAviso(
            listOf(
                Aviso(TipoAviso.CELULAR_QUENTE, "quente", desdeMs = 100),
                Aviso(TipoAviso.REPITA, "repita", desdeMs = 500),
                Aviso(TipoAviso.VOZ_RESERVA, "reserva", desdeMs = 300),
            ))
    assertEquals(TipoAviso.REPITA, escolhido?.tipo)
  }

  @Test
  fun `sem avisos nao ha faixa`() {
    assertNull(Avisos.escolherAviso(emptyList()))
  }

  @Test
  fun `captura mostra aguarde, depois sinalizando ou parado com o numero de sinais`() {
    fun faixa(libras: LibrasState) =
        Avisos.escolherAviso(Avisos.avisosDaCaptura(libras, DialogState.CAPTURANDO_SINAIS, textos))?.texto
    assertEquals("Aguarde…", faixa(LibrasState(isCollecting = true)))
    assertEquals(
        "● sinalizando · 2",
        faixa(LibrasState(podeSinalizar = true, estadoSinalizacao = EstadoSinalizacao.SINALIZANDO, sinaisNaSessao = 2)))
    assertEquals("○ parado · 3", faixa(LibrasState(podeSinalizar = true, sinaisNaSessao = 3)))
    // Fora da captura, nada da captura.
    assertEquals(emptyList<Aviso>(), Avisos.avisosDaCaptura(LibrasState(podeSinalizar = true), DialogState.FALANDO, textos))
  }

  @Test
  fun `tronco fora do quadro vence o indicador da captura`() {
    val avisos =
        Avisos.avisosDaCaptura(
            LibrasState(podeSinalizar = true, enquadramento = Enquadramento.TRONCO_FORA), DialogState.CAPTURANDO_SINAIS, textos)
    assertEquals("Afaste-se: tronco fora do quadro", Avisos.escolherAviso(avisos)?.texto)
  }
}

/*
 * Causa de falha da câmera (docs/prontidao-demo/03 §3.4).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FalhaCameraTest {

  @Test
  fun `antes de tentar - atualizacao vence falta de dispositivo`() {
    assertEquals(FalhaCamera.ATUALIZACAO_OBRIGATORIA, FalhaCamera.antesDeTentar(temDispositivoAtivo = false, atualizacaoObrigatoria = true))
    assertEquals(FalhaCamera.SEM_DISPOSITIVO, FalhaCamera.antesDeTentar(temDispositivoAtivo = false, atualizacaoObrigatoria = false))
    assertNull(FalhaCamera.antesDeTentar(temDispositivoAtivo = true, atualizacaoObrigatoria = false))
  }

  @Test
  fun `depois de esperar - sessao, permissao e stream`() {
    assertEquals(FalhaCamera.SESSAO_SEM_RESPOSTA, FalhaCamera.depoisDeEsperar(sessaoPronta = false, streamPronto = false, permissaoPendente = true))
    assertEquals(FalhaCamera.PERMISSAO_PENDENTE, FalhaCamera.depoisDeEsperar(sessaoPronta = true, streamPronto = false, permissaoPendente = true))
    assertEquals(FalhaCamera.STREAM_NAO_SUBIU, FalhaCamera.depoisDeEsperar(sessaoPronta = true, streamPronto = false, permissaoPendente = false))
    assertNull(FalhaCamera.depoisDeEsperar(sessaoPronta = true, streamPronto = true, permissaoPendente = false))
  }
}

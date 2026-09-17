/*
 * Confirma que a rede de segurança do roteiro (Contextualizadores.kt) protege a cadeia REAL —
 * criarGlossContextualizer(usarModelo = true), com o .tflite de verdade carregado, não um
 * delegate falso. É o complemento instrumentado de RoteiroGlossContextualizerTest (JVM, que só
 * prova a lógica do decorator em isolamento) e de ContextualizacaoRoteiroTest (que mostra o que
 * o modelo cru diria se não houvesse rede de segurança nenhuma).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoteiroComModeloLigadoTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun casosDoRoteiroSaemCorretosComOModeloDeVerdadeLigado() {
    val contextualizador = criarGlossContextualizer(context, usarModelo = true)
    try {
      for ((glosas, esperado) in CASOS_ROTEIRO) {
        val resultado = runBlocking { contextualizador.contextualize(glosas.toList()) }
        assertEquals("glosas=$glosas, com usarModelo=true", esperado, resultado.texto)
        assertEquals(Contextualizacao.Origem.TEMPLATE, resultado.origem)
      }
    } finally {
      contextualizador.close()
    }
  }

  @Test
  fun combinacaoForaDoRoteiroContinuaUsandoOModeloDeVerdade() {
    val contextualizador = criarGlossContextualizer(context, usarModelo = true)
    try {
      // banco+esquina não é um caso do roteiro: passa direto pra guarda/modelo real, como antes
      // da rede de segurança existir. Confirma que a rede não capturou nada fora do escopo dela.
      val resultado = runBlocking { contextualizador.contextualize(listOf("banco", "esquina")) }
      assertEquals("o banco fica na esquina", resultado.texto)
      assertEquals(Contextualizacao.Origem.MODELO, resultado.origem)
    } finally {
      contextualizador.close()
    }
  }
}

/*
 * O modelo de contextualização roda no app, nas 4 sequências do roteiro da demo (2.9).
 *
 * Existe porque ele NÃO funcionava, e nada acusava. Dois defeitos, ver
 * docs/prontidao-demo/06-latencia.md §6.1:
 *   - o TfliteGlossContextualizer passava INT32 para tensores INT64: cada frase lançava, a guarda
 *     registrava "exceção/timeout" e o template falava;
 *   - as tabelas glosa_ids/destokenizar vinham de uma poda refeita depois do treino (1.985 peças
 *     contra 1.996 do modelo): com os ids deslocados, o modelo gerava "a retornaró é a retornar".
 *
 * As frases fixadas são a saída do modelo v2 int8 com as tabelas recuperadas do checkpoint,
 * idênticas às do LiteRT em Python (2026-09-13). Servem para que mexer no laço de decodificação
 * ou nas tabelas não mude a saída em silêncio; trocar o modelo exige atualizá-las junto com o
 * carimbo de proveniência. "o banheiro é a vontade" é ruim de verdade — a guarda e a decisão de
 * ligar o modelo (MODELO_CONTEXTUALIZACAO_ATIVO) cuidam disso, não este teste.
 *
 * O tempo por sequência vai para o logcat (`adb logcat | grep RoteiroTest`), dado do 6.6.
 *
 * Instrumentado: o LiteRT não roda na JVM dos testes de unidade.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextualizacaoRoteiroTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private val saidaDoModeloV2 =
      linkedMapOf(
          listOf("filho", "vacina", "vontade") to "o meu filho toma a vacina",
          listOf("cinco") to "cinco",
          listOf("filho", "medo") to "o meu filho está assustado",
          listOf("banheiro", "vontade") to "o banheiro é a vontade",
          listOf("banco", "esquina") to "o banco fica na esquina",
      )

  @Test
  fun modeloRodaNoRoteiroComASaidaFixada() {
    val modelo = TfliteGlossContextualizer(context)
    try {
      for ((glosas, esperado) in saidaDoModeloV2) {
        val inicio = SystemClock.elapsedRealtime()
        val resultado = runBlocking { modelo.contextualize(glosas) }
        val ms = SystemClock.elapsedRealtime() - inicio
        Log.i("RoteiroTest", "glosas=$glosas texto=\"${resultado.texto}\" ms=$ms")
        assertEquals("saída do modelo para $glosas", esperado, resultado.texto)
        assertEquals(Contextualizacao.Origem.MODELO, resultado.origem)
      }
    } finally {
      modelo.close()
    }
  }
}

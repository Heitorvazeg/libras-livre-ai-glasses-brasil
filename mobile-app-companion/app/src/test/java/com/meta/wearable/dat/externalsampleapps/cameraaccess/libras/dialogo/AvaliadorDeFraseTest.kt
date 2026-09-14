/*
 * Confiança por frase e fluxo "repita" (docs/prontidao-demo/02-classificador.md §2.5, §2.8).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.Classificacao
import org.junit.Assert.assertEquals
import org.junit.Test

class AvaliadorDeFraseTest {

  private val lexico = setOf("filho", "vacina", "vontade", "cinco", "medo", "banheiro")

  private fun c(glosa: String, confianca: Float = 0.9f) = Classificacao(glosa, confianca, margem = 0.5f)

  private fun sessao(vararg c: Classificacao, falhas: Int = 0, motivo: MotivoEncerramento = MotivoEncerramento.SILENCIO) =
      ResultadoSessao(c.toList(), falhas, motivo)

  @Test
  fun `todas conhecidas e acima do limiar fala e zera o contador`() {
    val a = AvaliadorDeFrase(lexico)
    a.avaliar(sessao(c("filho", 0.3f)))
    assertEquals(1, a.rejeicoesSeguidas)
    assertEquals(DecisaoFrase.Falar(listOf("filho", "vacina", "vontade")), a.avaliar(sessao(c("filho"), c("vacina"), c("vontade"))))
    assertEquals(0, a.rejeicoesSeguidas)
  }

  @Test
  fun `a menor confianca decide, nao a media`() {
    // Média 0,77, mínimo 0,55: abaixo do limiar de 0,60.
    val a = AvaliadorDeFrase(lexico)
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("filho", 0.95f), c("vacina", 0.55f), c("vontade", 0.80f))))
  }

  @Test
  fun `falha de classificacao pede repeticao`() {
    val a = AvaliadorDeFrase(lexico)
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("filho"), falhas = 1)))
    assertEquals(1, a.rejeicoesSeguidas)
  }

  @Test
  fun `terceira rejeicao seguida desiste e zera`() {
    val a = AvaliadorDeFrase(lexico)
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("filho", 0.2f))))
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("filho", 0.2f))))
    assertEquals(DecisaoFrase.Desistir, a.avaliar(sessao(c("filho", 0.2f))))
    assertEquals(0, a.rejeicoesSeguidas)
    // Depois de desistir, a próxima frase começa do zero.
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("filho", 0.2f))))
  }

  @Test
  fun `sem segmentos por timeout ignora sem mexer no contador`() {
    val a = AvaliadorDeFrase(lexico)
    a.avaliar(sessao(c("filho", 0.2f)))
    assertEquals(DecisaoFrase.Ignorar, a.avaliar(sessao(motivo = MotivoEncerramento.TIMEOUT)))
    assertEquals(1, a.rejeicoesSeguidas)
  }

  @Test
  fun `sem segmentos encerrada manualmente pede repeticao`() {
    val a = AvaliadorDeFrase(lexico)
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(motivo = MotivoEncerramento.MANUAL)))
    assertEquals(1, a.rejeicoesSeguidas)
  }

  @Test
  fun `so glosas fora do lexico pede repeticao e sessao mista fala so as conhecidas`() {
    val a = AvaliadorDeFrase(lexico)
    // `maca` é rótulo do MINDS que não existe no léxico (lá é `maçã`, 2.5).
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("maca"))))
    assertEquals(DecisaoFrase.Falar(listOf("filho", "medo")), a.avaliar(sessao(c("filho"), c("maca"), c("medo"))))
    assertEquals(true, a.foraDoLexico("maca"))
  }

  @Test
  fun `fim do atendimento zera o contador`() {
    val a = AvaliadorDeFrase(lexico)
    a.avaliar(sessao(c("filho", 0.2f)))
    a.avaliar(sessao(c("filho", 0.2f)))
    a.zerar()
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("filho", 0.2f))))
  }

  @Test
  fun `limiar e configuravel e sem lexico todas sao aceitas`() {
    var limiar = 0.6f
    val a = AvaliadorDeFrase(glosasConhecidas = null, limiar = { limiar })
    assertEquals(DecisaoFrase.Falar(listOf("qualquer")), a.avaliar(sessao(c("qualquer", 0.7f))))
    limiar = 0.8f
    assertEquals(DecisaoFrase.PedirRepeticao, a.avaliar(sessao(c("qualquer", 0.7f))))
  }
}

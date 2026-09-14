/*
 * Libras Livre — parâmetros da segmentação de sinais (docs/prontidao-demo/01-segmentacao.md §1.8).
 *
 * VALORES ESTIMADOS, NÃO CALIBRADOS. Saem da discussão do plano, não de medição com os óculos. A
 * calibração rápida (1.10, guia de testes B.1 item 4) ajusta estes números a partir dos CSVs do
 * gravador (1.9); na onda 4 eles passam a ser editáveis nas configurações de demo, sem outro APK.
 *
 * Tudo em tempo real (ms) ou em larguras de ombro por segundo: nada depende do fps processado.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

data class ParametrosSegmentacao(
    /** PARADO -> SINALIZANDO quando a velocidade suavizada chega aqui (ombros/s). */
    val limiarEntrada: Float = 0.7f,
    /** Enquanto SINALIZANDO, acima disto ainda é movimento (ombros/s). Menor que a entrada. */
    val limiarSaida: Float = 0.4f,
    /** A velocidade compara o frame atual com o mais recente que tenha pelo menos esta idade. */
    val janelaVelocidadeMs: Long = 110L,
    /** Peso do valor novo na média móvel exponencial da velocidade. */
    val alfaSuavizacao: Float = 0.5f,
    /** Pausa (mãos visíveis, abaixo do limiar de saída) que fecha o sinal. */
    val pausaMs: Long = 500L,
    /** Ausência das duas mãos que fecha o sinal. Antes disso, o relógio da pausa não anda. */
    val tetoOclusaoMs: Long = 900L,
    /** Movimento mais curto que isto é espasmo: descartado sem classificar e sem contar falha. */
    val duracaoMinimaMs: Long = 250L,
    /** Teto de segurança: fecha o sinal mesmo sem pausa. */
    val duracaoMaximaMs: Long = 3_500L,
    /** Repouso incluído antes do início do movimento. */
    val preRollMs: Long = 250L,
    /** Repouso incluído depois do último movimento. */
    val posRollMs: Long = 150L,
) {
  init {
    require(limiarSaida < limiarEntrada) { "limiarSaida ($limiarSaida) precisa ser menor que limiarEntrada ($limiarEntrada)" }
    require(alfaSuavizacao in 0f..1f) { "alfaSuavizacao fora de [0, 1]" }
    require(posRollMs < pausaMs) { "posRollMs precisa caber na pausa que fecha o sinal" }
  }

  /** Quanto tempo de frames o segmentador precisa guardar para recortar o maior segmento possível. */
  val retencaoMs: Long
    get() = preRollMs + duracaoMaximaMs + maxOf(pausaMs, tetoOclusaoMs) + posRollMs
}

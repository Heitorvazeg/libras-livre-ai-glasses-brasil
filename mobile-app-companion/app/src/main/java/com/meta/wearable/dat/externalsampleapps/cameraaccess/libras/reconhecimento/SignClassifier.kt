/*
 * Libras Livre — classificação local do sinal (docs/prontidao-demo/02-classificador.md).
 *
 * Interface trocável: o fio segmento -> classificar -> acumular -> falar funciona sem o `.tflite`
 * existir. Recebe o segmento que o Segmentador recortou (1.1), já normalizado e com as lacunas de
 * mão imputadas na linha do tempo real (2.3), COM os timestamps: é quem conhece o contrato do
 * modelo que decide como reamostrar (2.2). Este componente só resolve *o que* é o sinal.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import java.util.Random

/** Um sinal delimitado pela segmentação: frames normalizados e imputados, com o instante de cada um. */
class SegmentoSinal(val frames: List<Array<FloatArray>>, val tsMs: LongArray) {
  init {
    require(frames.size == tsMs.size) { "${frames.size} frames e ${tsMs.size} timestamps" }
  }
}

/**
 * @property confianca probabilidade do top-1 (softmax), de 0 a 1.
 * @property margem top-1 menos top-2, na mesma escala.
 */
data class Classificacao(val glosa: String, val confianca: Float, val margem: Float)

interface SignClassifier {
  /** Classifica um segmento. Lança em caso de falha (o LandmarkPipeline trata). */
  fun classify(segmento: SegmentoSinal): Classificacao

  /** Carrega e roda uma inferência descartável (aquecimento, 6.4). Lança se o modelo não serve. */
  fun aquecer() {}

  fun close()
}

/** Modos do placeholder (2.6), escolhidos nas configurações de demo enquanto não há modelo. */
enum class ModoPlaceholder {
  /** As glosas das 4 sequências do roteiro (2.9), em ordem, com confiança alta. */
  ROTEIRO,
  ALTA,
  BAIXA,
  ALEATORIA,
}

/**
 * Placeholder enquanto o `sinal_classifier.tflite` não existe nos assets. Não olha os frames: devolve
 * as glosas do roteiro da demo em ordem, com a confiança do [modo] atual. É o que permite ensaiar o
 * fluxo inteiro no MockDeviceKit (contextualização, fala, escuta e avatar) antes do modelo, e testar o
 * fluxo "repita" (2.8) com confiança baixa.
 */
class PlaceholderSignClassifier(
    private val modo: () -> ModoPlaceholder = { ModoPlaceholder.ROTEIRO },
    private val aleatorio: Random = Random(),
) : SignClassifier {

  companion object {
    /** FILHO VACINA VONTADE · CINCO · FILHO MEDO · BANHEIRO VONTADE (2.9), como chaves do léxico. */
    val ROTEIRO = listOf("filho", "vacina", "vontade", "cinco", "filho", "medo", "banheiro", "vontade")
    const val CONFIANCA_ALTA = 0.92f
    const val CONFIANCA_BAIXA = 0.30f
  }

  private var posicao = 0

  @Synchronized
  override fun classify(segmento: SegmentoSinal): Classificacao {
    val glosa = ROTEIRO[posicao % ROTEIRO.size]
    posicao++
    val confianca =
        when (modo()) {
          ModoPlaceholder.ROTEIRO,
          ModoPlaceholder.ALTA -> CONFIANCA_ALTA
          ModoPlaceholder.BAIXA -> CONFIANCA_BAIXA
          ModoPlaceholder.ALEATORIA -> aleatorio.nextFloat()
        }
    return Classificacao(glosa, confianca, margem = confianca / 2f)
  }

  override fun close() {}
}

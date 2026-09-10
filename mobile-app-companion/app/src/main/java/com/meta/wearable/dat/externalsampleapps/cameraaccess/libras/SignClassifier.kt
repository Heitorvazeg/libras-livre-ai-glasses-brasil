/*
 * Libras Livre — classificação local do sinal (Fase 3a/3b de
 * docs/sign-boundary-detector-plano.md §5).
 *
 * Interface trocável (mesmo padrão de WakeWordDetector/SttEngine em
 * orquestracao-dialogo-audio-plano.md): destrava o fio inteiro
 * boundary -> classificar -> acumular -> falar sem depender do `.tflite` existir. Recebe
 * exatamente o segmento que SignBoundaryDetector delimitou (do boundary anterior até o
 * atual) — já normalizado e com lacunas de mão imputadas (LandmarkNormalizer +
 * HandGapImputer, rodados por LandmarkPipeline antes de chamar isto). Este componente só
 * resolve *o que* é o sinal, não *onde* ele está.
 *
 * Substitui o papel que libras/LandmarkApi.kt tinha nesse fluxo (removido — nada mais chama
 * a API de classificação por sinal).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

interface SignClassifier {
  /** Classifica um segmento. Lança em caso de falha (LandmarkPipeline já trata via runCatching). */
  fun classify(frames: List<Array<FloatArray>>): String

  fun close()
}

/**
 * Placeholder enquanto o `.tflite` (computer-vision-model/treino/gcn.py exportado, ver §5.1,
 * §5.4 item 1 do plano) não existe. Não tenta classificar de verdade — só prova que o
 * restante da orquestração (boundary -> aqui -> DialogOrchestrator -> TTS) funciona ponta a
 * ponta. `TfliteSignClassifier` substitui esta implementação quando o modelo existir, sem
 * mudar `LandmarkPipeline`/`DialogOrchestrator`.
 */
class PlaceholderSignClassifier : SignClassifier {
  override fun classify(frames: List<Array<FloatArray>>): String = "[placeholder:${frames.size}f]"

  override fun close() {}
}

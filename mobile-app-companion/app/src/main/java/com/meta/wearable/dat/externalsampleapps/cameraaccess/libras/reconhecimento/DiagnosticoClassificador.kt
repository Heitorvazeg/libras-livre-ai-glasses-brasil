package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

/** Metadados apenas: não guarda classificador, frames, áudio nem resultados de pessoas. */
data class DiagnosticoClassificador(
    val modo: ModoClassificador,
    val identidade: IdentidadeClassificador? = null,
    val motivo: String? = null,
    val versaoApp: String = "não informada",
) {
  val titulo: String
    get() = when (modo) {
      ModoClassificador.SIMULADO -> "SIMULADO — não reconhece sinais reais"
      ModoClassificador.REAL_EXPERIMENTAL -> "REAL EXPERIMENTAL — não aprovado para entrega"
      ModoClassificador.RECUSADO -> "RECUSADO — reconhecimento bloqueado"
    }

  fun resumo(limiar: Float): String = when (modo) {
    ModoClassificador.SIMULADO -> "Resultados de demonstração; limiar manual: $limiar"
    ModoClassificador.RECUSADO -> motivo ?: "Falha ao carregar o classificador"
    ModoClassificador.REAL_EXPERIMENTAL ->
      "${identidade?.experimento} · SHA ${identidade?.modeloSha256?.take(12)}\n" +
          "Sem calibração · T=1 · limiar manual: $limiar (não é acurácia)"
  }

  fun detalhes(limiar: Float): String = buildString {
    append("app=$versaoApp; modo=$modo; limiar_manual=$limiar")
    identidade?.let {
      append("; experimento=${it.experimento}; modelo_sha256=${it.modeloSha256}")
      append("; sidecar_sha256=${it.sidecarSha256}; checkpoint_sha256=${it.checkpointSha256}")
      append("; calibracao=${it.calibracao}; temperatura=1; aprovado_entrega=false")
    }
    motivo?.let { append("; motivo=$it") }
  }
}
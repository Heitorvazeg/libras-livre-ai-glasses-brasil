package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import java.security.MessageDigest
import org.json.JSONObject

enum class ModoClassificador { SIMULADO, REAL_EXPERIMENTAL, RECUSADO }

data class IdentidadeClassificador(
    val experimento: String,
    val modeloSha256: String,
    val sidecarSha256: String,
    val checkpointSha256: String,
    val calibracao: String,
)

data class CarregamentoClassificador(
    val classificador: SignClassifier,
    val modo: ModoClassificador,
    val identidade: IdentidadeClassificador? = null,
    val motivo: String? = null,
)

/** Política pura testável; erro no real solicitado NUNCA chama criarSimulado. */
object CarregadorClassificador {
  const val MODELO = "sinal_classifier.tflite"
  const val SIDECAR = "sinal_classifier.json"
  const val IDENTIDADE = "sinal_classifier.identidade.json"

  fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes).joinToString("") { "%02x".format(it) }

  fun carregar(
      obrigatorio: Boolean,
      identidadeEsperada: String,
      temModelo: Boolean,
      lerAsset: (String) -> ByteArray,
      criarReal: (String, ByteArray) -> SignClassifier,
      criarSimulado: () -> SignClassifier,
  ): CarregamentoClassificador {
    if (!obrigatorio && !temModelo) {
      return CarregamentoClassificador(criarSimulado(), ModoClassificador.SIMULADO)
    }
    return try {
      require(obrigatorio) { "Modelo nos assets sem seleção privada explícita no build" }
      require(identidadeEsperada.matches(Regex("[0-9a-f]{64}"))) { "Identidade não fixada no build" }
      val idBytes = lerAsset(IDENTIDADE)
      require(sha256(idBytes) == identidadeEsperada) { "Identidade diverge do build" }
      val id = JSONObject(idBytes.toString(Charsets.UTF_8))
      require(id.get("schema") == 1 && id.get("experimental") == true &&
          id.get("aprovado_entrega") == false) { "Pacote não é experimental privado" }
      val identidade = IdentidadeClassificador(
          id.getString("experimento"), id.getString("modelo_sha256"),
          id.getString("sidecar_sha256"), id.getString("checkpoint_sha256"), id.getString("calibracao"))
      require(identidade.experimento.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}"))) { "Experimento inválido" }
      require(listOf(identidade.modeloSha256, identidade.sidecarSha256, identidade.checkpointSha256)
          .all { it.matches(Regex("[0-9a-f]{64}")) }) { "Hash de identidade inválido" }
      val modelo = lerAsset(MODELO)
      val sidecar = lerAsset(SIDECAR)
      require(sha256(modelo) == identidade.modeloSha256 && sha256(sidecar) == identidade.sidecarSha256) {
        "Modelo/sidecar divergem da identidade"
      }
      val texto = sidecar.toString(Charsets.UTF_8)
      val json = JSONObject(texto)
      require(json.getString("sha256") == identidade.modeloSha256 &&
          json.getJSONObject("origem").getString("sha256") == identidade.checkpointSha256) {
        "Origem do sidecar diverge da identidade"
      }
      require(identidade.calibracao == "ausente_nao_calibrado" && !json.has("calibracao")) {
        "Integração privada exige export sem calibração; não ajustar limiar automaticamente"
      }
      CarregamentoClassificador(criarReal(texto, modelo), ModoClassificador.REAL_EXPERIMENTAL, identidade)
    } catch (e: Exception) {
      recusado(e)
    } catch (e: LinkageError) {
      // Biblioteca nativa/ABI indisponível também é recusa, não simulação nem crash de inicialização.
      recusado(e)
    }
  }

  private fun recusado(e: Throwable): CarregamentoClassificador {
    val motivo = if (e is ModeloRecusado) e.message.orEmpty()
        else ModeloRecusado.PREFIXO + (e.message ?: e.javaClass.simpleName)
    return CarregamentoClassificador(ClassificadorRecusado(motivo), ModoClassificador.RECUSADO, motivo = motivo)
  }
}
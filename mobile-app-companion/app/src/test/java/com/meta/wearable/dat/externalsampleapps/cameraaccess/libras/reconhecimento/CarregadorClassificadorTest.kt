package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CarregadorClassificadorTest {
  private val modelo = "modelo de teste, sem executar LiteRT".toByteArray()
  private val checkpoint = "ab".repeat(32)
  private val fake = object : SignClassifier {
    override fun classify(segmento: SegmentoSinal) = Classificacao("teste", 1f, 1f)
    override fun close() {}
  }

  private inner class Pacote {
    val sidecar = JSONObject().put("sha256", CarregadorClassificador.sha256(modelo))
        .put("origem", JSONObject().put("sha256", checkpoint))
    val id = JSONObject().put("schema", 1).put("experimental", true)
        .put("aprovado_entrega", false).put("experimento", "baseline-v1")
        .put("modelo_sha256", CarregadorClassificador.sha256(modelo))
        .put("checkpoint_sha256", checkpoint).put("calibracao", "ausente_nao_calibrado")
    fun assets(): MutableMap<String, ByteArray> {
      val sidecarBytes = sidecar.toString().toByteArray()
      id.put("sidecar_sha256", CarregadorClassificador.sha256(sidecarBytes))
      return mutableMapOf(CarregadorClassificador.MODELO to modelo,
          CarregadorClassificador.SIDECAR to sidecarBytes,
          CarregadorClassificador.IDENTIDADE to id.toString().toByteArray())
    }
  }

  private fun carregar(
      assets: Map<String, ByteArray>, obrigatorio: Boolean = true,
      hash: String = assets[CarregadorClassificador.IDENTIDADE]?.let(CarregadorClassificador::sha256).orEmpty(),
      real: (String, ByteArray) -> SignClassifier = { _, _ -> fake },
      simulado: () -> SignClassifier = { error("Não deve simular") },
  ) = CarregadorClassificador.carregar(obrigatorio, hash,
      assets.containsKey(CarregadorClassificador.MODELO),
      { assets[it] ?: error("Asset ausente: $it") }, real, simulado)

  private fun recusa(assets: Map<String, ByteArray>, hash: String? = null) {
    val resultado = carregar(assets, hash = hash ?: assets[CarregadorClassificador.IDENTIDADE]
        ?.let(CarregadorClassificador::sha256).orEmpty(), real = { _, _ -> fail("Não deve criar real"); fake })
    assertEquals(ModoClassificador.RECUSADO, resultado.modo)
    assertTrue(resultado.motivo!!.startsWith(ModeloRecusado.PREFIXO))
    assertThrows(IllegalStateException::class.java) { resultado.classificador.aquecer() }
  }

  @Test fun `ausencia opcional e simulacao explicita`() {
    val r = carregar(emptyMap(), obrigatorio = false, simulado = { fake })
    assertEquals(ModoClassificador.SIMULADO, r.modo)
    assertSame(fake, r.classificador)
    assertNull(r.identidade)
  }

  @Test fun `pacote valido preserva identidade e bytes`() {
    val assets = Pacote().assets()
    var chamadas = 0
    val r = carregar(assets, real = { texto, bytes ->
      chamadas++
      assertEquals(assets.getValue(CarregadorClassificador.SIDECAR).toString(Charsets.UTF_8), texto)
      assertArrayEquals(modelo, bytes)
      fake
    })
    assertEquals(1, chamadas)
    assertEquals(ModoClassificador.REAL_EXPERIMENTAL, r.modo)
    assertEquals(checkpoint, r.identidade!!.checkpointSha256)
    assertEquals("baseline-v1", r.identidade.experimento)
    assertSame(fake, r.classificador)
  }

  @Test fun `qualquer asset ausente recusa sem simular`() {
    for (nome in Pacote().assets().keys) recusa(Pacote().assets().apply { remove(nome) })
  }

  @Test fun `hash fixado vazio incorreto ou identidade adulterada recusa`() {
    val assets = Pacote().assets()
    recusa(assets, "")
    recusa(assets, "cd".repeat(32))
    val hash = CarregadorClassificador.sha256(assets.getValue(CarregadorClassificador.IDENTIDADE))
    assets[CarregadorClassificador.IDENTIDADE] = assets.getValue(CarregadorClassificador.IDENTIDADE) + byteArrayOf(32)
    recusa(assets, hash)
  }

  @Test fun `modelo ou sidecar adulterado recusa`() {
    for (nome in listOf(CarregadorClassificador.MODELO, CarregadorClassificador.SIDECAR)) {
      recusa(Pacote().assets().apply { this[nome] = getValue(nome) + byteArrayOf(32) })
    }
  }

  @Test fun `origem divergente mesmo com hashes atualizados recusa`() {
    val p = Pacote()
    p.sidecar.put("origem", JSONObject().put("sha256", "cd".repeat(32)))
    recusa(p.assets())
  }

  @Test fun `identidade malformada e flags invalidas recusam`() {
    for ((chave, valor) in listOf("schema" to 2, "experimental" to false,
        "aprovado_entrega" to true, "experimento" to "../invalido",
        "checkpoint_sha256" to "xyz", "calibracao" to "calibrado")) {
      val p = Pacote()
      p.id.put(chave, valor)
      recusa(p.assets())
    }
    recusa(Pacote().assets().apply { this[CarregadorClassificador.IDENTIDADE] = "{".toByteArray() })
  }

  @Test fun `bloco de calibracao inclusive null recusa`() {
    for (valor in listOf(JSONObject.NULL, JSONObject().put("temperatura", 1))) {
      val p = Pacote()
      p.sidecar.put("calibracao", valor)
      recusa(p.assets())
    }
  }

  @Test fun `falha no construtor real recusa sem placeholder`() {
    val r = carregar(Pacote().assets(), real = { _, _ -> error("LiteRT indisponível") })
    assertEquals(ModoClassificador.RECUSADO, r.modo)
    assertTrue(r.motivo!!.contains("LiteRT indisponível"))
  }

  @Test fun `modelo nos assets sem opt in recusa`() {
    val r = carregar(Pacote().assets(), obrigatorio = false)
    assertEquals(ModoClassificador.RECUSADO, r.modo)
  }

  @Test fun `biblioteca nativa ausente recusa sem simular`() {
    val r = carregar(Pacote().assets(), real = { _, _ -> throw UnsatisfiedLinkError("ABI ausente") })
    assertEquals(ModoClassificador.RECUSADO, r.modo)
    assertTrue(r.motivo!!.contains("ABI ausente"))
  }
}
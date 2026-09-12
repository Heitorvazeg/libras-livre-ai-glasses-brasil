/*
 * Libras Livre — tradutor glosa via endpoint público do VLibras.
 *
 * POR QUE TRADUZIR AQUI, E NÃO NO PLAYER (§7.1 do plano): o vlibras.js tem um
 * Player.translate() que faria isso sozinho, mas ele usa o config.js do repositório, que aponta
 * para https://traducao2-dth.vlibras.gov.br/dl/translate — URL MORTA (503 medido em 2026-09-12).
 * Traduzir no Kotlin dá três coisas: a URL certa sem editar o bundle do player, cache entre
 * sessões, e um ponto único para o comportamento sem rede.
 *
 * CONTRATO DO ENDPOINT (medido, não documentado oficialmente):
 *   POST https://traducao2.vlibras.gov.br/translate
 *   Content-Type: application/json          {"text": "..."}
 *   200 -> corpo é TEXTO PURO, não JSON:    VOCÊ PRECISAR MARCAR&REGISTRAR ...
 * É o mesmo endpoint que o app oficial da Play Store usa
 * (vlibras-mobile-cross-platform/src/services/translate.ts). Sem autenticação. A variante
 * /dl/translate exige token (401) e não serve.
 *
 * SEM DEPENDÊNCIA NOVA: HttpURLConnection basta. O projeto não tem Retrofit/OkHttp e não vale
 * adicionar um por uma única chamada.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "Libras:GlosaTranslator"
private const val ENDPOINT = "https://traducao2.vlibras.gov.br/translate"

/** Mesmo timeout que o player oficial usa (GlosaTranslator.js: 30s). */
private const val TIMEOUT_MS = 30_000

class VLibrasGlosaTranslator(
    private val cache: GlosaCache,
    private val endpoint: String = ENDPOINT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GlosaTranslator {

  override suspend fun traduzir(texto: String): String? {
    val chave = normalizar(texto)
    if (chave.isEmpty()) return null

    cache.obter(chave)?.let {
      Log.d(TAG, "cache hit: \"$chave\"")
      return it
    }

    val glosa = withContext(io) { runCatching { requisitar(texto) }.getOrElse { e ->
      Log.w(TAG, "falha ao traduzir \"$chave\"", e)
      null
    } }

    if (glosa.isNullOrBlank()) return null
    cache.guardar(chave, glosa)
    return glosa
  }

  private fun requisitar(texto: String): String? {
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "text/plain")
    }
    try {
      conn.outputStream.use { it.write(JSONObject().put("text", texto).toString().toByteArray()) }
      if (conn.responseCode != HttpURLConnection.HTTP_OK) {
        Log.w(TAG, "HTTP ${conn.responseCode} de $endpoint")
        return null
      }
      // Resposta é texto puro. Não tentar JSONObject aqui: o corpo não é JSON.
      return conn.inputStream.bufferedReader().use(BufferedReader::readText).trim()
    } finally {
      conn.disconnect()
    }
  }

  /** Chave de cache: o atendente repete a mesma frase o dia inteiro com pontuação/caixa variando. */
  private fun normalizar(texto: String) = texto.trim().lowercase().replace(Regex("\\s+"), " ")
}

/**
 * Cache de glosa em disco, uma linha por par. É o que torna o modo sem rede parcialmente útil:
 * as perguntas de balcão se repetem muito.
 *
 * Formato: `<chave>\t<glosa>` por linha. Simples de propósito — não vale um banco para isto, e
 * o arquivo é inspecionável à mão durante a calibração do vocabulário (Fase 3.5).
 */
class GlosaCache(private val arquivo: File, private val maxEntradas: Int = 500) {

  private val memoria = LinkedHashMap<String, String>(0, 0.75f, true)
  private var carregado = false

  @Synchronized
  fun obter(chave: String): String? {
    carregar()
    return memoria[chave]
  }

  @Synchronized
  fun guardar(chave: String, glosa: String) {
    carregar()
    memoria[chave] = glosa
    while (memoria.size > maxEntradas) {
      val maisAntiga = memoria.keys.firstOrNull() ?: break
      memoria.remove(maisAntiga)
    }
    persistir()
  }

  @Synchronized
  fun tamanho(): Int {
    carregar()
    return memoria.size
  }

  private fun carregar() {
    if (carregado) return
    carregado = true
    if (!arquivo.exists()) return
    runCatching {
      arquivo.forEachLine { linha ->
        val i = linha.indexOf('\t')
        if (i > 0) memoria[linha.substring(0, i)] = linha.substring(i + 1)
      }
    }.onFailure { Log.w(TAG, "cache ilegível, começando vazio", it) }
  }

  private fun persistir() {
    runCatching {
      arquivo.parentFile?.mkdirs()
      arquivo.writeText(memoria.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }.onFailure { Log.w(TAG, "não consegui persistir o cache", it) }
  }
}

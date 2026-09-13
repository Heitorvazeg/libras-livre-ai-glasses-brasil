/*
 * Libras Livre — tradutor glosa via endpoint público do VLibras.
 *
 * POR QUE TRADUZIR AQUI, E NÃO NO PLAYER (§7.1 do plano): o vlibras.js tem um Player.translate()
 * que faria isso sozinho — e o bundle que o download-assets.sh gera aponta para o MESMO endpoint
 * que usamos aqui (conferido no build de 2026-09-12; a URL morta /dl/translate é a de outra
 * variante do config, não a deste bundle). O que traduzir no Kotlin dá, e o player não dá:
 * cache em disco entre sessões, um ponto único para o comportamento sem rede, e independência
 * de um config que vive dentro de um bundle de 72 KB que não versionamos.
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val TAG = "Libras:GlosaTranslator"
private const val ENDPOINT = "https://traducao2.vlibras.gov.br/translate"

/**
 * Teto da tradução, conexão e leitura SOMADAS (docs/prontidao-demo/09-avatar.md §9.1). Era o do
 * player oficial, 30 s de conexão + 30 s de leitura, e prendia o ⑦ por um minuto sem rede.
 */
private const val TETO_MS = 5_000L

class VLibrasGlosaTranslator(
    private val cache: GlosaCache,
    private val endpoint: String = ENDPOINT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tetoMs: Long = TETO_MS,
) : GlosaTranslator {

  // A requisição roda num escopo próprio para que o teto possa ABANDONÁ-LA: um withContext(io)
  // esperaria o HttpURLConnection sair do bloqueio, e socket não responde a cancelamento.
  private val requisicoes = CoroutineScope(SupervisorJob() + io)

  override suspend fun traduzir(texto: String): String? {
    val chave = normalizar(texto)
    if (chave.isEmpty()) return null

    // Cache e rede no dispatcher de IO: a primeira [GlosaCache.obter] lê o TSV do disco, e esta
    // função é chamada da main thread (CameraViewModel.playAvatar roda em viewModelScope).
    withContext(io) { cache.obter(chave) }?.let {
      Log.d(TAG, "cache hit: \"$chave\"")
      return it
    }

    val conexao = AtomicReference<HttpURLConnection?>(null)
    val emVoo =
        requisicoes.async {
          runCatching { requisitar(texto, conexao) }.getOrElse { e ->
            Log.w(TAG, "falha ao traduzir \"$chave\"", e)
            null
          }
        }
    val glosa = withTimeoutOrNull(tetoMs) { emVoo.await() }
    if (glosa == null && emVoo.isActive) {
      Log.w(TAG, "teto de ${tetoMs}ms estourado traduzindo \"$chave\" — segue sem glosa")
      // Best-effort: derruba o socket em voo. Se não derrubar, os timeouts abaixo encerram.
      conexao.get()?.disconnect()
      emVoo.cancel()
    }

    if (glosa.isNullOrBlank()) return null
    withContext(io) { cache.guardar(chave, glosa) }
    return glosa
  }

  private fun requisitar(texto: String, conexao: AtomicReference<HttpURLConnection?>): String? {
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = tetoMs.toInt()
      readTimeout = tetoMs.toInt()
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "text/plain")
    }
    conexao.set(conn)
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
    // O formato é uma linha por par: um \t ou \n dentro da glosa cortaria o arquivo ao meio na
    // releitura (a linha órfã é descartada em silêncio por [carregar]). Frases longas do atendente
    // podem voltar assim do endpoint.
    memoria[chave] = glosa.replace(Regex("[\t\n\r]+"), " ").trim()
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

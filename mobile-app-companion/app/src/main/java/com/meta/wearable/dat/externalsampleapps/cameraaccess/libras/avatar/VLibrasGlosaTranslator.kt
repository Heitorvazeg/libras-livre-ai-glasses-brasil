/*
 * Libras Livre — tradutor glosa via endpoint público do VLibras.
 *
 * POR QUE TRADUZIR AQUI, E NÃO NO PLAYER (§7.1 do plano): o vlibras.js tem um Player.translate()
 * que faria isso sozinho — e o bundle que o download-assets.sh gera aponta para o MESMO endpoint
 * que usamos aqui (conferido no build de 2026-09-12; a URL morta /dl/translate é a de outra
 * variante do config, não a deste bundle). O que traduzir no Kotlin dá, e o player não dá:
 * cache controlado por nós (só em memória, apagado no fim de cada atendimento — ver
 * [GlosaCache]), um ponto único para o comportamento sem rede, e independência de um config que
 * vive dentro de um bundle de 72 KB que não versionamos.
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    tetoMs: Long = TETO_MS,
) : GlosaTranslator {

  /** Editável nas configurações de demo (9.1); vale na próxima tradução. */
  @Volatile var tetoMs: Long = tetoMs

  // A requisição roda num escopo próprio para que o teto possa ABANDONÁ-LA: um withContext(io)
  // esperaria o HttpURLConnection sair do bloqueio, e socket não responde a cancelamento.
  private val requisicoes = CoroutineScope(SupervisorJob() + io)

  override suspend fun traduzir(texto: String): String? {
    val chave = normalizar(texto)
    if (chave.isEmpty()) return null

    // Época do cache ANTES de qualquer espera: se o atendimento acabar com esta requisição em voo,
    // [GlosaCache.limpar] muda a época e o guardar() lá embaixo é recusado — a frase de quem já
    // foi embora não entra no cache do atendimento seguinte. O cache é só memória e as operações
    // são curtas e sincronizadas, então dispensam o dispatcher de IO.
    val epoca = cache.epoca()
    cache.obter(chave)?.let {
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
    // A glosa volta para quem pediu mesmo fora da época: descartar ou não a animação é decisão do
    // orquestrador (a geração dele já ignora turnos de um atendimento encerrado). Só o cache recusa.
    if (!cache.guardar(chave, glosa, epoca)) {
      Log.i(TAG, "atendimento encerrado durante a tradução — glosa fora do cache")
    }
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
 * Cache de glosa do ATENDIMENTO: só memória, e apagado por [limpar] no fim de cada atendimento
 * (o gancho `aoEncerrarAtendimento` da PoliticaCamera, o mesmo ponto que revoga o consentimento).
 * Dentro de um atendimento a mesma frase não volta à rede — o atendente repete, a pessoa surda
 * repete, o texto do consentimento e o pedido de repetição reaparecem; o atendimento seguinte
 * começa vazio.
 *
 * POR QUE NÃO HÁ DISCO: as chaves são o texto da conversa (respostas do atendente, frases
 * reconhecidas da pessoa surda). Gravá-las contradiz o "Nada é gravado" do consentimento e, num
 * serviço de saúde, retém conteúdo sensível sem finalidade. O preço é aceito por desenho: a
 * primeira ocorrência de cada frase em cada atendimento vai à rede, e não existe mais "aquecer o
 * cache no local" — o aquecimento seria apagado no fim do primeiro atendimento. Nenhuma exceção
 * para textos fixos do sistema: um cache que só às vezes é apagado é um cache que ninguém audita.
 *
 * ÉPOCA: [limpar] também avança [epoca]. Quem traduz captura a época antes de ir à rede e a passa
 * a [guardar]; se o atendimento acabou no meio, o par é recusado em vez de vazar para o seguinte.
 */
class GlosaCache(private val maxEntradas: Int = 500) {

  // Ordem de acesso: o LRU descarta a entrada usada há mais tempo.
  private val memoria = LinkedHashMap<String, String>(0, 0.75f, true)
  private var epoca = 0L

  /** Época atual; muda a cada [limpar]. Capturar antes da requisição e devolver em [guardar]. */
  @Synchronized fun epoca(): Long = epoca

  @Synchronized fun obter(chave: String): String? = memoria[chave]

  /**
   * Guarda o par se [epoca] ainda for a atual; devolve false (e não guarda nada) se o cache foi
   * limpo desde que a tradução começou.
   */
  @Synchronized
  fun guardar(chave: String, glosa: String, epoca: Long): Boolean {
    if (epoca != this.epoca) return false
    // Glosa numa linha só: frases longas do atendente podem voltar do endpoint com \t ou \n. Era
    // exigência do antigo formato em disco; fica para que o que sai do cache siga igual a antes.
    memoria[chave] = glosa.replace(Regex("[\t\n\r]+"), " ").trim()
    while (memoria.size > maxEntradas) {
      val maisAntiga = memoria.keys.firstOrNull() ?: break
      memoria.remove(maisAntiga)
    }
    return true
  }

  /** Fim do atendimento: esquece tudo e invalida as traduções ainda em voo. */
  @Synchronized
  fun limpar() {
    memoria.clear()
    epoca++
  }

  @Synchronized fun tamanho(): Int = memoria.size
}

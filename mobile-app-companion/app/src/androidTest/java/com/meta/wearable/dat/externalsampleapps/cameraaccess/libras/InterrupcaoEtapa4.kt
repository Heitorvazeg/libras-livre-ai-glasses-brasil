package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.CarregadorClassificador
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import org.json.JSONObject

/** Exclusivo de androidTest: snapshot dos mesmos journals, sem Activity, grafo ou teardown.
 * Após PRONTO não há retorno normal nem finally restaurador: somente o host encerra o target.
 */
internal object InterrupcaoEtapa4 {
  private val context get() = Etapa4Suporte.context
  private val preferencias get() = context.getSharedPreferences("configuracoes_demo", Context.MODE_PRIVATE)
  private val processoUuid = UUID.randomUUID().toString()

  fun processo(): JSONObject = JSONObject().put("pid", Process.myPid())
      .put("start_elapsed_ms", Process.getStartElapsedRealtime()).put("processo_uuid", processoUuid)
      .put("start_ticks", File("/proc/self/stat").readText().substringAfterLast(") ")
          .trim().split(Regex("\\s+"))[19])

  fun mapa(snapshot: JSONObject): Map<String, String> = buildMap {
    snapshot.keys().forEach { chave ->
      val valor = snapshot.get(chave)
      check(valor is String) { "Snapshot corrompido: $chave não é string" }
      put(chave, valor)
    }
  }

  fun snapshot(): JSONObject {
    check(preferencias.edit().commit()) { "Falha na barreira anterior ao snapshot" }
    return JSONObject().also { json ->
      preferencias.all.forEach { (chave, valor) ->
        check(valor is String) { "Tipo novo nas preferências: $chave; adaptar snapshot" }
        json.put(chave, valor)
      }
    }
  }

  // Ordenação UTF-8 e enquadramento por comprimento em bytes, não serialização JSON dependente
  // de plataforma. Strings, chaves desconhecidas e ausências são preservadas literalmente.
  fun hash(snapshot: JSONObject): String {
    val bytes = ByteArrayOutputStream()
    val mapa = mapa(snapshot)
    mapa.keys.sortedWith { a, b ->
      val aa = a.toByteArray(Charsets.UTF_8)
      val bb = b.toByteArray(Charsets.UTF_8)
      var comparacao = 0
      for (i in 0 until minOf(aa.size, bb.size)) {
        comparacao = (aa[i].toInt() and 255).compareTo(bb[i].toInt() and 255)
        if (comparacao != 0) break
      }
      if (comparacao != 0) comparacao else aa.size.compareTo(bb.size)
    }.forEach { chave ->
      for (texto in listOf(chave, mapa.getValue(chave))) {
        val bruto = texto.toByteArray(Charsets.UTF_8)
        bytes.write("${bruto.size}:".toByteArray(Charsets.US_ASCII))
        bytes.write(bruto)
      }
    }
    return CarregadorClassificador.sha256(bytes.toByteArray())
  }

  fun restaurar(snapshot: JSONObject) {
    val exato = mapa(snapshot) // Validar tudo ANTES de tocar preferências/singleton.
    val original = ConfiguracoesDemo(object : ConfiguracoesDemo.Armazenamento {
      override fun ler(chave: String): String? = exato[chave]
      override fun gravar(chave: String, valor: String) = error("Snapshot somente leitura")
    }).valores.value
    val configs = ConfiguracoesDemo.de(context)
    configs.atualizar { original }
    val editor = preferencias.edit().clear()
    exato.forEach { (chave, valor) -> editor.putString(chave, valor) }
    check(editor.commit()) { "Restauração falhou; manter journal" }
    check(preferencias.all == exato && configs.valores.value == original) {
      "Snapshot inexato; manter journal"
    }
  }

  fun publicarEAguardar(registro: JSONObject, tipo: String, metodo: String,
                       salvar: (JSONObject) -> Unit): Nothing {
    val marcador = Etapa4Suporte.argumentoUuid("etapa4Marcador")
    val execucao = registro.getString("execucao")
    val antes = registro.getJSONObject("snapshot")
    val depois = snapshot() // commit espera todos os apply de ConfiguracoesDemo.
    check(mapa(antes) != mapa(depois)) { "Ensaio não alterou preferências" }
    val pronto = processo().put("versao", 1).put("tipo", tipo).put("status", "PRONTO")
        .put("fase", metodo).put("execucao", execucao).put("marcador", marcador)
        .put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
        .put("snapshot", antes).put("snapshot_sha256", hash(antes))
        .put("alterado", depois).put("alterado_sha256", hash(depois))
    registro.put("interrupcao", pronto).put("fase", "PRONTO")
    salvar(registro) // Mesmo journal de produção do teste, ANTES de publicar prontidão.
    val pasta = File(context.filesDir, "etapa4-interrupcao")
    check(pasta.isDirectory || pasta.mkdirs())
    check(pasta.canonicalFile == File(context.filesDir.canonicalFile, "etapa4-interrupcao"))
    val arquivo = File(pasta, "$execucao-$marcador.json")
    check(!arquivo.exists() && arquivo.canonicalFile == arquivo.absoluteFile) { "Marcador já existe/redirecionado" }
    for (sufixo in listOf(".new", ".bak")) {
      val auxiliar = File(arquivo.path + sufixo)
      check(!auxiliar.exists() && auxiliar.canonicalFile == auxiliar.absoluteFile)
    }
    val atomico = AtomicFile(arquivo)
    val stream = atomico.startWrite()
    try {
      stream.write(pronto.toString().toByteArray(Charsets.UTF_8))
      stream.fd.sync() // AtomicFile.finishWrite apenas registra certas falhas de sync no log.
      atomico.finishWrite(stream) // fsync + rename: host nunca consome JSON parcial.
    } catch (erro: Throwable) {
      atomico.failWrite(stream)
      throw erro
    }
    val fd = Os.open(pasta.path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
    CountDownLatch(1).await() // Indefinido; sem sleep, timeout ou aprovação artificial.
    error("Espera de interrupção retornou indevidamente")
  }

  /** Executada antes de qualquer escrita na recuperação; journal alheio/corrompido fica intacto. */
  fun verificarRecuperacao(registro: JSONObject, tipo: String): JSONObject? {
    if (!registro.has("interrupcao")) return null // Preserva recuperação manual anterior ao ensaio.
    val pronto = registro.getJSONObject("interrupcao")
    check(pronto.getInt("versao") == 1 && pronto.getString("status") == "PRONTO")
    check(pronto.getString("tipo") == tipo && registro.getString("fase") == "PRONTO")
    check(pronto.getString("execucao") == registro.getString("execucao"))
    check(pronto.getString("identidade_sha256") == BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
    check(mapa(pronto.getJSONObject("snapshot")) == mapa(registro.getJSONObject("snapshot")))
    for (nome in listOf("snapshot", "alterado")) {
      check(hash(pronto.getJSONObject(nome)) == pronto.getString("${nome}_sha256"))
    }
    val atual = processo()
    check(pronto.getInt("pid") != atual.getInt("pid") ||
        pronto.getLong("start_elapsed_ms") != atual.getLong("start_elapsed_ms")) { "Mesmo processo" }
    check(pronto.getString("processo_uuid") != atual.getString("processo_uuid"))
    check(pronto.getInt("pid") != atual.getInt("pid") ||
        pronto.getString("start_ticks") != atual.getString("start_ticks"))
    val marcador = Etapa4Suporte.argumentoUuid("etapa4Marcador")
    check(pronto.getString("marcador") != marcador)
    check(preferencias.all == mapa(pronto.getJSONObject("alterado"))) { "Estado pós-kill divergiu" }
    return atual.put("marcador", marcador).put("pronto", pronto)
        .put("verificado_antes_de_alterar", true)
  }

  fun concluirRecuperacao(prova: JSONObject, evidencia: JSONObject?) {
    if (evidencia == null) return
    val restaurado = snapshot()
    check(mapa(restaurado) == mapa(evidencia.getJSONObject("pronto").getJSONObject("snapshot")))
    evidencia.put("snapshot_restaurado", restaurado).put("snapshot_restaurado_sha256", hash(restaurado))
        .put("snapshot_exato", true)
    prova.put("interrupcao", evidencia)
  }
}
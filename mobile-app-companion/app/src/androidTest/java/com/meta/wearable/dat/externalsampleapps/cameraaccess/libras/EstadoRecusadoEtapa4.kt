package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import org.json.JSONObject

/** Journal privado e separado do REAL. Em crash, só o UUID dono pode recuperar; nunca resetar.
 * Exclusivo do protocolo serial em emulador dedicado (não suporta instrumentações concorrentes).
 */
internal object EstadoRecusadoEtapa4 {
  private val context get() = Etapa4Suporte.context
  private val preferencias get() = context.getSharedPreferences("configuracoes_demo", Context.MODE_PRIVATE)
  private val temporario get() = context.getSharedPreferences("etapa4_recusado_test_owned", Context.MODE_PRIVATE)

  fun exigirSemPendente() {
    check(temporario.all.isEmpty()) { "RECUSADO interrompido: recuperar explicitamente com UUID dono" }
  }

  private fun exigirSemReal() {
    check(context.getSharedPreferences("etapa4_persistencia_test_owned", Context.MODE_PRIVATE).all.isEmpty()) {
      "Marcador REAL pendente: não alterar configurações nem limpar marcador REAL"
    }
  }

  fun salvarAntesDeAlterar(execucao: String) {
    exigirSemReal()
    exigirSemPendente()
    check(preferencias.edit().commit()) { "Falha na barreira de preferências antes do snapshot" }
    val snapshot = JSONObject()
    preferencias.all.forEach { (chave, valor) ->
      check(valor is String) { "Tipo novo em ConfiguracoesDemo: $chave; adaptar snapshot antes de alterar" }
      snapshot.put(chave, valor)
    }
    val registro = JSONObject().put("versao", 1).put("tipo", "RECUSADO")
        .put("execucao", execucao).put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
        .put("snapshot", snapshot)
    // commit ANTES do primeiro atualizar(false). Se falhar, nenhum seletor foi alterado.
    check(temporario.edit().putString("registro", registro.toString()).commit()) { "Snapshot RECUSADO não persistido" }
  }

  fun prepararInterrupcao(execucao: String): Nothing {
    Etapa4Suporte.argumentoUuid("etapa4Marcador")
    salvarAntesDeAlterar(execucao)
    val registro = carregarDoDono(execucao)
    val configs = ConfiguracoesDemo.de(context)
    configs.atualizar { it.copy(comandoDeVoz = !it.comandoDeVoz, gravadorSessao = !it.gravadorSessao) }
    check(preferencias.edit().commit())
    InterrupcaoEtapa4.publicarEAguardar(registro, "RECUSADO", "prepararInterrupcaoRecusado") {
      check(temporario.edit().putString("registro", it.toString()).commit())
    }
  }

  fun carregarDoDono(execucao: String): JSONObject {
    exigirSemReal()
    check(temporario.all.keys == setOf("registro")) { "Journal RECUSADO ausente ou inesperado; não limpar" }
    val registro = JSONObject(checkNotNull(temporario.getString("registro", null)))
    check(registro.getInt("versao") == 1 && registro.getString("tipo") == "RECUSADO")
    check(registro.getString("execucao") == execucao) { "UUID dono difere; não tocar estado alheio" }
    check(registro.getString("identidade_sha256") == BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256) {
      "Pacote diferente do snapshot RECUSADO; não restaurar"
    }
    return registro
  }

  fun restaurarDoDono(execucao: String) {
    val registro = carregarDoDono(execucao)
    InterrupcaoEtapa4.restaurar(registro.getJSONObject("snapshot"))
    check(temporario.edit().remove("registro").commit()) { "Snapshot restaurado, mas journal não removido" }
  }
}
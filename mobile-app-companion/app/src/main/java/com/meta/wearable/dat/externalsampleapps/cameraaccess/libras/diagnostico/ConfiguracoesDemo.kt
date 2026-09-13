/*
 * Libras Livre — configurações de demo (docs/prontidao-demo/10-tela.md §10.6).
 *
 * Um lugar só para os seletores, salvo entre execuções e exposto como StateFlow. Na onda 2 guarda
 * apenas o que o primeiro teste com os óculos e os testes do fluxo no mock precisam: o gravador de
 * sessão (1.9), o painel de métricas (3.8) e o modo do placeholder do classificador (2.6). A onda 4
 * completa o resto, sem mudar nenhum padrão.
 *
 * O armazenamento é uma interface para os padrões e o "voltar ao padrão" serem testáveis na JVM; no
 * app é um SharedPreferences.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoPlaceholder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ValoresDemo(
    val gravadorSessao: Boolean = false,
    val painelMetricas: Boolean = false,
    val modoPlaceholder: ModoPlaceholder = ModoPlaceholder.ROTEIRO,
)

class ConfiguracoesDemo(private val armazenamento: Armazenamento) {

  interface Armazenamento {
    fun ler(chave: String): String?

    fun gravar(chave: String, valor: String)
  }

  private val _valores = MutableStateFlow(carregar())
  val valores: StateFlow<ValoresDemo> = _valores.asStateFlow()

  @Synchronized
  fun atualizar(mudar: (ValoresDemo) -> ValoresDemo) {
    val novo = mudar(_valores.value)
    armazenamento.gravar(GRAVADOR, novo.gravadorSessao.toString())
    armazenamento.gravar(PAINEL, novo.painelMetricas.toString())
    armazenamento.gravar(MODO_PLACEHOLDER, novo.modoPlaceholder.name)
    _valores.value = novo
  }

  fun voltarAoPadrao() = atualizar { ValoresDemo() }

  // Valor ausente ou ilegível (renomeado entre versões) cai no padrão, sem quebrar a abertura do app.
  private fun carregar(): ValoresDemo {
    val padrao = ValoresDemo()
    return ValoresDemo(
        gravadorSessao = armazenamento.ler(GRAVADOR)?.toBooleanStrictOrNull() ?: padrao.gravadorSessao,
        painelMetricas = armazenamento.ler(PAINEL)?.toBooleanStrictOrNull() ?: padrao.painelMetricas,
        modoPlaceholder =
            armazenamento.ler(MODO_PLACEHOLDER)?.let { nome -> ModoPlaceholder.entries.firstOrNull { it.name == nome } }
                ?: padrao.modoPlaceholder,
    )
  }

  companion object {
    private const val ARQUIVO = "configuracoes_demo"
    private const val GRAVADOR = "gravador_sessao"
    private const val PAINEL = "painel_metricas"
    private const val MODO_PLACEHOLDER = "modo_placeholder"

    @Volatile private var instancia: ConfiguracoesDemo? = null

    /** A instância do app: a tela e o CameraViewModel precisam ver as mesmas mudanças. */
    fun de(context: Context): ConfiguracoesDemo =
        instancia
            ?: synchronized(this) {
              instancia
                  ?: ConfiguracoesDemo(
                          PreferenciasAndroid(
                              context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)))
                      .also { instancia = it }
            }
  }
}

private class PreferenciasAndroid(private val prefs: SharedPreferences) : ConfiguracoesDemo.Armazenamento {
  override fun ler(chave: String): String? = prefs.getString(chave, null)

  override fun gravar(chave: String, valor: String) {
    prefs.edit().putString(chave, valor).apply()
  }
}

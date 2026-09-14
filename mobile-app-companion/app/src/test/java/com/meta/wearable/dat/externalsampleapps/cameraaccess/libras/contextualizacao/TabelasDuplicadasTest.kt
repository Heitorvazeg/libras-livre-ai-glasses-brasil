/*
 * As tabelas de contrato existem em dois lugares: onde nascem (contextualization-model/) e onde
 * o app as lê (assets/). ModeloContextualizacaoProvenienciaTest amarra a cópia do app ao modelo;
 * este teste amarra a cópia do app à da trilha — sem ele, alguém atualiza o léxico na trilha,
 * retreina, e o app segue com o léxico velho, sem erro nenhum (as guardas passam a rejeitar
 * saídas corretas, ou a aceitar erradas).
 *
 * Compara o CONTEÚDO JSON, não os bytes: o lexico-glosas.json das duas pastas já difere só em
 * indentação (medido em 2026-09-13), e isso não é divergência.
 *
 * O diretório de trabalho dos testes de unidade é o módulo app/; a raiz do repositório fica
 * dois níveis acima.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Test

class TabelasDuplicadasTest {

  private val assets = File("src/main/assets")
  private val trilha = File("../../contextualization-model")

  @Test
  fun `glosa_ids do app e da trilha tem o mesmo conteudo`() {
    comparar("glosa_ids.json", "artefatos/glosa_ids.json")
  }

  @Test
  fun `destokenizar do app e da trilha tem o mesmo conteudo`() {
    comparar("destokenizar.json", "artefatos/destokenizar.json")
  }

  @Test
  fun `lexico do app e da trilha tem o mesmo conteudo`() {
    comparar("lexico-glosas.json", "lexico/lexico-glosas.json")
  }

  private fun comparar(noApp: String, naTrilha: String) {
    val app = conteudo(File(assets, noApp).readText())
    val origem = conteudo(arquivoDaTrilha(naTrilha).readText())
    assertTrue(mensagem(noApp, naTrilha), app == origem)
  }

  // JSON -> Map/List do Kotlin, que têm igualdade estrutural. O `similar()` do org.json não serve:
  // o compilador dos testes enxerga o org.json antigo do android.jar, que não o tem.
  private fun conteudo(texto: String): Any? = normalizar(JSONTokener(texto).nextValue())

  private fun normalizar(valor: Any?): Any? =
      when (valor) {
        is JSONObject -> valor.keys().asSequence().associateWith { normalizar(valor.get(it)) }
        is JSONArray -> (0 until valor.length()).map { normalizar(valor.get(it)) }
        else -> valor
      }

  private fun arquivoDaTrilha(caminho: String): File {
    val arquivo = File(trilha, caminho)
    assertTrue("${arquivo.path} ausente — o teste roda a partir de mobile-app-companion/app/", arquivo.isFile)
    return arquivo
  }

  private fun mensagem(noApp: String, naTrilha: String) =
      "assets/$noApp diverge de contextualization-model/$naTrilha — copie a versão da trilha " +
          "para o app (e, se for glosa_ids/destokenizar, atualize o carimbo de proveniência)"
}

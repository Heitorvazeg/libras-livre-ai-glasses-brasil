/*
 * Libras Livre — montagem do contextualizador (§3.3 do plano).
 *
 * Uma função, porque a ordem das camadas é uma decisão de produto e não deve ficar espalhada:
 *
 *     modelo (.tflite)  ->  [guarda]  ->  template  ->  passthrough
 *
 * O template é o PISO e o modelo tem que merecer cada sessão. Fundamentado em medição: em três
 * rodadas de fine-tuning o modelo não bateu o template em F1 na validação sintética (0,826 a
 * 0,837 contra 0,871 a 0,884), mas a guarda o aceita em ~95% das sessões e ele ganha
 * justamente onde há relação gramatical entre glosas ("banco esquina" -> "o banco fica na
 * esquina"), que é o que uma tabela não faz.
 *
 * Se o asset do modelo não estiver presente (ele é gitignored, 46 MB), tudo continua
 * funcionando com o template — sem erro, só um log.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import android.content.Context
import android.util.Log

private const val TAG = "Libras:Contextualizacao"

/**
 * O modelo fica FORA da cadeia na demo (decisão de 2026-09-13, docs/prontidao-demo/06-latencia.md
 * §6.1). Até essa data ele nunca tinha rodado no app: o contrato de entrada estava errado e a
 * guarda caía no template em toda frase. Com o contrato corrigido, a saída do v2 nas sequências do
 * roteiro não é falável ("o esta é de vontade") e a guarda deixaria passar "filho medo" no lugar
 * de "O meu filho está com medo.". Religar é trocar esta constante, depois que a trilha do modelo
 * validar a saída.
 */
const val MODELO_CONTEXTUALIZACAO_ATIVO = false

/**
 * Monta a cadeia completa. [onRejeicao] recebe o motivo sempre que a guarda barra o modelo — é
 * o gancho para medir em campo a taxa de fallback do §10, a métrica mais informativa sobre se o
 * `.tflite` está se pagando.
 */
fun criarGlossContextualizer(
    context: Context,
    usarModelo: Boolean = MODELO_CONTEXTUALIZACAO_ATIVO,
    onRejeicao: (String) -> Unit = { Log.i(TAG, "guarda rejeitou o modelo — $it") },
): GlossContextualizer {
  val lexico =
      runCatching { LexicoGlosas.fromAssets(context) }
          .onFailure { Log.e(TAG, "lexico-glosas.json ausente em assets/ — caindo pro passthrough", it) }
          .getOrNull() ?: return PassthroughGlossContextualizer()

  val template = TemplateGlossContextualizer()
  if (!usarModelo) {
    // Nem carrega os 47 MB: fora da cadeia, o Interpreter só ocuparia memória.
    Log.i(TAG, "contextualização: só template (modelo desligado, ver MODELO_CONTEXTUALIZACAO_ATIVO)")
    return template
  }

  val modelo =
      runCatching { TfliteGlossContextualizer(context) }
          .onFailure {
            Log.w(TAG, "modelo_contextualizacao.tflite indisponível — só template " +
                "(ver assets/.gitignore para gerá-lo)", it)
          }
          .getOrNull() ?: return template

  Log.i(TAG, "contextualização: modelo .tflite sob guarda, template como fallback")
  return GuardedGlossContextualizer(
      primario = modelo, fallback = template, lexico = lexico, onRejeicao = onRejeicao)
}

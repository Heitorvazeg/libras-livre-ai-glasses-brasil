/*
 * Libras Livre — tradução português -> glosa do VLibras (docs/vlibras-webview-plano.md §6, Fase 1).
 *
 * Sentido OUVINTE -> SURDO: recebe o texto que o SttEngine transcreveu da resposta do atendente
 * (estado ⑥) e devolve a glosa que o player VLibras anima (estado ⑦).
 *
 * NÃO confundir com libras/contextualizacao/: aquele é o sentido inverso (glosa -> português) e
 * usa o nosso lexico-glosas.json. Notações diferentes, artefatos diferentes — ver §0.5 do plano.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

/** Traduz texto em PT-BR para a glosa do VLibras. Devolve null quando não conseguiu. */
interface GlosaTranslator {
  suspend fun traduzir(texto: String): String?
}

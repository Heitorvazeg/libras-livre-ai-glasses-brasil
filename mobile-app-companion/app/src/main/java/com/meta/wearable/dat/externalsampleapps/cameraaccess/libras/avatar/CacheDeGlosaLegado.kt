/*
 * Libras Livre — remoção do cache de glosa que as versões antigas gravavam em disco.
 *
 * Até 2026-09-18 o [GlosaCache] persistia `texto normalizado -> glosa` em
 * `filesDir/vlibras/glosa-cache.tsv`, sem prazo e entre atendimentos: o arquivo guarda frases de
 * conversas passadas (respostas do atendente e frases reconhecidas da pessoa surda). O cache agora
 * vive só em memória e morre com o atendimento; este arquivo é o resto que precisa sumir dos
 * aparelhos que já rodaram o app. Fica fora do [GlosaCache] de propósito: o cache não sabe mais
 * nada de disco.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import android.util.Log
import java.io.File

private const val TAG = "Libras:GlosaTranslator"

/** Caminho do TSV legado, relativo a `filesDir`. */
internal const val CAMINHO_CACHE_GLOSA_LEGADO = "vlibras/glosa-cache.tsv"

/**
 * Apaga o TSV legado (e a pasta `vlibras/`, se ficar vazia). Não lê o conteúdo. Idempotente:
 * sem o arquivo, não faz nada. Chamar fora da main — é I/O de disco.
 */
fun apagarCacheDeGlosaLegado(filesDir: File) {
  val arquivo = File(filesDir, CAMINHO_CACHE_GLOSA_LEGADO)
  if (arquivo.exists() && !arquivo.delete()) {
    Log.w(TAG, "não consegui apagar o cache de glosa legado em ${arquivo.path}")
    return
  }
  // delete() de diretório só remove se estiver vazio: outra coisa guardada em vlibras/ fica.
  arquivo.parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
}

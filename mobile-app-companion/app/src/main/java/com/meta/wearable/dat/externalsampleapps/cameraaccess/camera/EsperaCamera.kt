package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A decisão humana não gasta o prazo de cada fase técnica; toda espera é cancelável. */
internal suspend fun <T> aguardarCameraSemContarPermissao(
    estados: Flow<T>,
    timeoutTecnicoMs: Long,
    aguardandoPermissao: (T) -> Boolean,
    concluida: (T) -> Boolean,
): T? {
  while (true) {
    val estado = withTimeoutOrNull(timeoutTecnicoMs) {
      estados.first { concluida(it) || aguardandoPermissao(it) }
    } ?: return null
    if (concluida(estado)) return estado
    val decisao = estados.first { concluida(it) || !aguardandoPermissao(it) }
    if (concluida(decisao)) return decisao
    // Resultado recebido: começa o prazo técnico de abertura, não o prazo do usuário.
  }
}
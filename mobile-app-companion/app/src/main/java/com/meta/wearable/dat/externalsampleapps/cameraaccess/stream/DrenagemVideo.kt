package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * cancel() não interrompe um frame síncrono. O dono só pode liberar/reutilizar os decoders,
 * CSD e recorder depois do join. Chamar de OUTRO job, sem locks dos consumidores e sem
 * bloquear a main: callbacks do recorder/pipeline podem precisar dela para progredir.
 */
internal suspend fun drenarVideoAntesDeLimpar(produtor: Job?, limpar: suspend () -> Unit) {
  produtor?.cancelAndJoin()
  limpar()
}
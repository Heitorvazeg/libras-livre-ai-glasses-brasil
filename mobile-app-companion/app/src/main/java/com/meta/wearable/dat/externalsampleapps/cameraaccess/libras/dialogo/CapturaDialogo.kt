package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

/** Porta mínima para testar os efeitos do diálogo sem Context, decoder ou modelos nativos. */
class CapturaDialogo(
    val startSession: () -> Unit,
    val endSession: suspend () -> Unit,
    val retomarDepoisDePausa: () -> Unit,
)
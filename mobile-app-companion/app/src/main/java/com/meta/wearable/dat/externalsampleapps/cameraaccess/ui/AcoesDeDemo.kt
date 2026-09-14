/*
 * Libras Livre — ações do menu de debug que precisam do CameraViewModel
 * (docs/prontidao-demo/10-tela.md §10.6).
 *
 * O menu de debug vive no CameraAccessScaffold, fora da tela da câmera; o CameraViewModel registra
 * aqui o que o menu pode acionar enquanto ele existe.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

object AcoesDeDemo {
  /** "Simular queda do avatar" (9.5): derruba o renderer da WebView. Null sem tela da câmera. */
  @Volatile var simularQuedaDoAvatar: (() -> Unit)? = null
}

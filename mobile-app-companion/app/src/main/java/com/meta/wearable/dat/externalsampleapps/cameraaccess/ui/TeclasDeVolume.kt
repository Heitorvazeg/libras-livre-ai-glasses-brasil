/*
 * Libras Livre — teclas de volume como botão principal (docs/prontidao-demo/04 §4.7).
 *
 * A tela da câmera registra um ouvinte enquanto há sessão com os óculos; a MainActivity o consulta
 * no onKeyDown. Um controle Bluetooth de selfie envia tecla de volume, então usa o mesmo caminho. Sem
 * ouvinte (outra tela, sem sessão), a tecla ajusta o volume normalmente.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

object TeclasDeVolume {
  /**
   * Recebe `primeiroToque` (false nas repetições de tecla segurada, que não devem disparar de novo) e
   * devolve true se a tecla foi tratada — e portanto não deve mexer no volume.
   */
  @Volatile var ouvinte: ((primeiroToque: Boolean) -> Boolean)? = null
}

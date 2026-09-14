/*
 * Libras Livre — cópia de uma árvore de assets para o disco, à prova de interrupção
 * (docs/prontidao-demo/05-audio.md §5.6).
 *
 * O Vosk e o eSpeak do Piper não leem de dentro do APK: na primeira execução, ~70 MB são copiados
 * para o disco. Antes, a cópia só conferia se a pasta existia — uma cópia interrompida (app fechado,
 * disco cheio) deixava a pasta incompleta PARA SEMPRE, e os motores falhavam em todo boot. Aqui um
 * marcador `.completo` é gravado só depois de copiar tudo; pasta sem marcador é apagada e copiada de
 * novo. "Listar" e "abrir" são parâmetros para testar sem AssetManager.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import java.io.File
import java.io.InputStream

object CopiaDeAssets {

  const val MARCADOR = ".completo"

  /**
   * Garante `destinoRaiz/origem` com o conteúdo completo da árvore de assets [origem]. Lança se a
   * cópia falhar (o marcador não é gravado, e a próxima chamada recomeça do zero).
   *
   * @param listar filhos de um caminho de asset; vazio ou null = é um arquivo.
   * @param abrir abre um arquivo de asset.
   * @return a pasta de destino.
   */
  fun garantir(
      origem: String,
      destinoRaiz: File,
      listar: (String) -> Array<String>?,
      abrir: (String) -> InputStream,
  ): File {
    val destino = File(destinoRaiz, origem)
    val marcador = File(destino, MARCADOR)
    if (marcador.isFile) return destino
    if (destino.exists()) destino.deleteRecursively()
    copiarArvore(origem, destinoRaiz, listar, abrir)
    marcador.writeText("ok")
    return destino
  }

  private fun copiarArvore(
      caminho: String,
      destinoRaiz: File,
      listar: (String) -> Array<String>?,
      abrir: (String) -> InputStream,
  ) {
    val filhos = listar(caminho)
    val alvo = File(destinoRaiz, caminho)
    if (filhos.isNullOrEmpty()) {
      alvo.parentFile?.mkdirs()
      abrir(caminho).use { entrada -> alvo.outputStream().use { entrada.copyTo(it) } }
      return
    }
    alvo.mkdirs()
    for (filho in filhos) copiarArvore("$caminho/$filho", destinoRaiz, listar, abrir)
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// TtsEngine - Motor de síntese de voz, trocável (interface)
//
// Speaker.kt (fachada usada pelo DialogOrchestrator) delega pra um TtsEngine. Implementações:
//   - PiperSherpaOnnxTtsEngine.kt — motor real, local (Piper via sherpa-onnx).
//   - AndroidTextToSpeechEngine.kt — TTS nativo do Android, reserva.
//   - TtsEmCadeia.kt — Piper com o nativo como reserva (docs/prontidao-demo/05 §5.5).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

interface TtsEngine {
  /**
   * Fala [text] e suspende até terminar. Nunca lança: devolve false quando não conseguiu falar
   * (motor que não carregou, erro na síntese), para quem chama poder trocar de motor em vez de
   * ficar mudo em silêncio (5.5). Chamável da main thread.
   *
   * [onInicioAudio] é chamado uma vez, quando o primeiro trecho de áudio é entregue à saída — é a
   * etapa "frase -> primeiro áudio" do painel (docs/prontidao-demo/06 §6.5).
   */
  suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit = {}): Boolean

  /**
   * Carrega o motor e prepara [frases] sem tocar (aquecimento, 6.4). Devolve false se o motor não
   * carregou. Padrão: nada a preparar.
   */
  suspend fun aquecer(frases: List<String>): Boolean = true

  /** Interrompe a fala em andamento, se houver. */
  fun stop()

  /** Libera recursos — chamado só no teardown do dono (ex.: onCleared do ViewModel). */
  fun shutdown()
}

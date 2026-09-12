/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// TtsEngine - Motor de síntese de voz, trocável (interface)
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4 item 10, §6.6, §8 item 4. Speaker.kt (fachada
// usada pelo DialogOrchestrator) delega pra um TtsEngine, permitindo trocar o motor sem tocar no
// orquestrador (que só conhece Speaker.speakAndAwait). Implementações (cada uma no seu arquivo,
// como WakeWordDetector/SttEngine):
//   - AndroidTextToSpeechEngine.kt — impl-base usando a API nativa do Android (fallback).
//   - PiperSherpaOnnxTtsEngine.kt — motor real, local (Piper via sherpa-onnx).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

interface TtsEngine {
  /** Fala [text] e suspende até terminar (erro incluso — nunca lança). Chamável da main thread. */
  suspend fun speakAndAwait(text: String)

  /** Interrompe a fala em andamento, se houver. */
  fun stop()

  /** Libera recursos — chamado só no teardown do dono (ex.: onCleared do ViewModel). */
  fun shutdown()
}

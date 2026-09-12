/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// SttEngine - Transcrição da resposta do atendente (interface trocável)
//
// Ver docs/orquestracao-dialogo-audio-plano.md §6.4, §8 item 2. Implementações (cada uma no seu
// arquivo, como WakeWordDetector):
//   - AndroidSpeechRecognizerSttEngine.kt — impl-base mínima (API nativa do Android, sem dependência
//     nova), pra destravar o DialogOrchestrator sem escolher motor.
//   - VoskSttEngine.kt — motor real on-device/offline (Vosk pt-BR), consome PCM cru via PcmMicCapture.
// Trocar qual o CameraViewModel instancia é a única mudança necessária pra alternar o motor.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

interface SttEngine {
  /**
   * Começa a escutar. [onResult] é chamado no máximo uma vez com a transcrição; [onError] no
   * máximo uma vez se a captura falhar ou não render nada. Deve ser chamado a partir da main
   * thread (SpeechRecognizer exige um Looper).
   */
  fun start(onResult: (String) -> Unit, onError: (Throwable) -> Unit)

  /**
   * Encerra a escuta agora — o áudio capturado até aqui é finalizado como se o atendente tivesse
   * parado de falar neste instante (dispara [onResult]/[onError] de forma assíncrona, não aqui).
   * É assim que a wake word "encerrar" corta a captura, em vez de depender de detecção de
   * silêncio.
   */
  fun stop()
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WakeWordDetector - Fonte de eventos de wake word pro DialogOrchestrator
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4.2, §6.3, §8. Duas frases simétricas ("Libras
// Livre, iniciar" / "Libras Livre, encerrar") delimitam sessões — o que cada uma significa
// depende do DialogState atual, decidido inteiramente pelo DialogOrchestrator (nunca por esta
// interface). Ativa em ①②④⑤, pausada em ③⑥⑦ (start()/pause() chamados pelo orquestrador).
//
// SpeechRecognizerWakeWordDetector (SpeechRecognizerWakeWordDetector.kt) é a implementação real,
// escuta contínua no mic do celular — ver header daquele arquivo pro motivo da escolha e as
// limitações conhecidas. Os botões "Iniciar"/"Encerrar" da UI (ui/CameraScreen.kt,
// DialogControlRow) continuam funcionando como fallback: eles chamam
// CameraViewModel.onWakeWordButton, que invoca DialogOrchestrator.onWakeWord diretamente — não
// passam por esta interface, então funcionam mesmo se o motor real falhar, estiver pausado ou sem
// permissão de microfone.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

/** As duas wake words que delimitam sessões (docs/orquestracao-dialogo-audio-plano.md §4.2). */
enum class WakeWord {
  INICIAR,
  ENCERRAR,
}

interface WakeWordDetector {
  /** Passa a emitir eventos (chamado pelo orquestrador ao entrar em ①②④⑤). */
  fun start()

  /** Para de emitir eventos sem liberar recursos (chamado ao entrar em ③⑥⑦). */
  fun pause()

  /** Libera recursos — chamado só no teardown do dono (ex.: onCleared do ViewModel). */
  fun stop()
}

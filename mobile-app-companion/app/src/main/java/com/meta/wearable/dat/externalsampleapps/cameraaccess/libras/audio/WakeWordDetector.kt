/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WakeWordDetector - Fonte de eventos de wake word pro DialogOrchestrator
//
// Ver docs/orquestracao-dialogo-audio-plano.md §4.2, §6.3. Duas frases simétricas ("Libras
// Livre, iniciar" / "Libras Livre, encerrar") delimitam sessões — o que cada uma significa
// depende do DialogState atual, decidido inteiramente pelo DialogOrchestrator (nunca por esta
// interface). Ativa em ①②④⑤, pausada em ③⑥⑦ (start()/pause() chamados pelo orquestrador).
//
// ManualWakeWordDetector é a ÚNICA implementação hoje: dois botões na UI (ver
// ui/CameraScreen.kt, DialogControlRow) disparam os eventos diretamente. Um motor de escuta
// contínua de verdade (Porcupine, TFLite — §8 do plano) ficaria por trás desta mesma interface,
// tratado como upgrade — os botões continuam servindo de fallback caso ele falhe ou não esteja
// disponível no aparelho. Não implementamos esse motor agora porque a concorrência entre ele e o
// HFP ativo do estado ESCUTANDO_ATENDENTE (⑤) não foi validada em hardware real (§4.3, §7 Fase 0
// do plano) — AudioInputHandler.kt já deixa pronta a captura de PCM contínuo do mic do celular
// pra quando essa validação acontecer, mas nada aqui a consome ainda.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

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

/**
 * Implementação por toque: dois botões na UI chamam [trigger] diretamente. Serve hoje como a
 * única fonte de wake word (ver header) — os eventos só chegam ao [onWakeWord] enquanto
 * [start] foi chamado por último (isto é, enquanto o orquestrador considera a wake word ativa).
 */
class ManualWakeWordDetector(
    private val onWakeWord: (WakeWord) -> Unit,
) : WakeWordDetector {

  @Volatile private var active = false

  override fun start() {
    active = true
  }

  override fun pause() {
    active = false
  }

  override fun stop() {
    active = false
  }

  /** Chamado pelos botões "Iniciar"/"Encerrar" da UI (ver DialogControlRow). */
  fun trigger(word: WakeWord) {
    if (active) onWakeWord(word)
  }
}

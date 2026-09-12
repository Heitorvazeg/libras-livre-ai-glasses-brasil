/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// DialogState - Estados da sessão de diálogo bidirecional
//
// Ver docs/orquestracao-dialogo-audio-plano.md §5. Cada transição é disparada por uma wake word
// (ou, nesta fase, pelo botão de fallback — ver WakeWordDetector.kt) através do
// DialogOrchestrator, que é o único dono deste estado. O que acontece DENTRO de
// CAPTURANDO_SINAIS (quantos sinais, onde cada um termina) é responsabilidade do pipeline de
// reconhecimento (LandmarkPipeline / futuro SignBoundaryDetector — docs/sign-boundary-detector-plano.md),
// não deste enum.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

enum class DialogState {
  /** ① Esperando "Libras Livre, iniciar" pra abrir uma nova sessão de sinais. */
  AGUARDANDO_SINAL,

  /** ② Sessão aberta — LandmarkPipeline acumulando sinais até "Libras Livre, encerrar". */
  CAPTURANDO_SINAIS,

  /** ③ Falando (TTS) a frase reconhecida na sessão. Wake word pausada. */
  FALANDO,

  /** ④ TTS terminou — esperando "Libras Livre, iniciar" pra escutar a resposta do atendente. */
  AGUARDANDO_RESPOSTA,

  /** ⑤ HFP ativo, mic dos óculos capturando a resposta — esperando "Libras Livre, encerrar". */
  ESCUTANDO_ATENDENTE,

  /** ⑥ STT convertendo a captura em texto. Wake word pausada. */
  TRANSCREVENDO,

  /** ⑦ Entregando o texto pro pipeline do avatar (docs/vlibras-webview-plano.md). Wake word pausada. */
  GERANDO_AVATAR,
}

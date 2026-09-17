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

  /**
   * ①.5 [NOVO — docs/consentimento-por-atendimento-plano.md §1] Antes de ligar a câmera, mostra
   * pra pessoa surda (mesmo playAvatar() de ②.5/⑦, mais a legenda) o que o sistema faz, e espera
   * o atendente decidir por ela: "Aceitar" liga a câmera e segue pra CAPTURANDO_SINAIS; "Recusar"
   * volta a AGUARDANDO_SINAL sem captar nenhum sinal, com aviso de bilhete/intérprete. Os dois
   * botões têm o mesmo peso visual — não é o modelo de botão único (§2.2 do plano). Sem timeout
   * que aceita sozinho: silêncio não é consentimento. Câmera desligada, wake word pausada.
   */
  PEDINDO_CONSENTIMENTO,

  /** ② Sessão aberta — LandmarkPipeline acumulando sinais até "Libras Livre, encerrar". */
  CAPTURANDO_SINAIS,

  /**
   * ②.5 [NOVO — docs/confirmacao-e-modo-economia-plano.md §1] Mostra a frase reconhecida pro
   * SURDO (mesmo avatar do ⑦, mais a legenda) e espera o botão do operador: "Confirmar" fala pro
   * atendente e segue o ciclo; "Corrigir" descarta e reabre CAPTURANDO_SINAIS. Um timeout de
   * segurança confirma sozinho se nenhum dos dois for apertado. Câmera desligada, wake word
   * pausada, como ③⑥⑦.
   */
  CONFIRMANDO_RECONHECIMENTO,

  /** ③ Falando (TTS) a frase reconhecida na sessão. Wake word pausada. */
  FALANDO,

  /**
   * ③.5 Pedido de repetição (2.8) apresentado à PESSOA SURDA no avatar, depois de o aviso ter sido
   * falado ao atendente. Antes a captura reabria sozinha, e quem sinalizou nunca via por que: o
   * avatar dizia algo só para o atendente ouvir. Aqui a captura fica fechada (câmera desligada,
   * como em ②.5) até o operador tocar "Capturar de novo" — ninguém repete um sinal que não sabe
   * que precisou repetir. Não volta a pedir consentimento: é o mesmo atendimento.
   */
  PEDINDO_REPETICAO,

  /** ④ TTS terminou — esperando "Libras Livre, iniciar" pra escutar a resposta do atendente. */
  AGUARDANDO_RESPOSTA,

  /** ⑤ HFP ativo, mic dos óculos capturando a resposta — esperando "Libras Livre, encerrar". */
  ESCUTANDO_ATENDENTE,

  /** ⑥ STT convertendo a captura em texto. Wake word pausada. */
  TRANSCREVENDO,

  /** ⑦ Entregando o texto pro pipeline do avatar (docs/vlibras-webview-plano.md). Wake word pausada. */
  GERANDO_AVATAR,
}

/**
 * Por que a frase mostrada em ②.5 foi descartada sem ser falada. Nos dois casos a decisão fica com
 * o operador — nenhum deles fala automaticamente o que não foi conferido.
 */
enum class MotivoConfirmacaoNaoConcluida {
  /** O teto de ②.5 expirou sem "Confirmar" nem "Corrigir": o atendimento voltou ao ①. */
  TETO_EXPIRADO,
  /** "Corrigir" não conseguiu religar a câmera: a frase pendente segue em ②.5, sem ser falada. */
  CORRECAO_SEM_CAMERA,
}

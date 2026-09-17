/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// DialogOrchestrator - Dono do DialogState e de todos os EFEITOS das transições
//
// Coordena LandmarkPipeline (captura e classificação), GlossContextualizer (glosas -> frase),
// Speaker (TTS), SttEngine (transcrição), o avatar e a câmera dos óculos. As REGRAS — para onde
// cada evento leva, quando a captura acaba sozinha, o que o botão principal faz — moram em
// Transicoes.kt e AvaliadorDeFrase.kt, funções puras testadas na JVM (docs/prontidao-demo/04 §4.1).
//
// Uma volta tem um comando só (4.1): "iniciar" (wake word, botão principal ou tecla de volume) abre
// a captura; a pausa longa depois dos sinais a fecha; a decisão sobre a frase (2.8) fala e abre a
// escuta sozinha, ou pede repetição, ou desiste; o fim de fala do Vosk fecha a escuta; o avatar
// responde e o ciclo volta ao ①. Os botões continuam valendo em todo passo, como gatilho e correção.
//
// [NOVO — docs/confirmacao-e-modo-economia-plano.md] Dois pontos do feedback da banca de
// 2026-09-15, encaixados nesta arquitetura (não construídos à parte):
//  - ②.5 CONFIRMANDO_RECONHECIMENTO, entre a decisão Falar e a escuta do atendente
//    (Transicoes.estadoAposDecisao): mostra pro SURDO (via playAvatar, o mesmo do ⑦) a frase que
//    o sistema entendeu, e espera o botão do operador — confirmarReconhecimento() fala pro
//    atendente e segue; corrigirReconhecimento() descarta e reabre a captura (reaproveita
//    beginSignSession()/iniciarCaptura()). Um timeout de segurança confirma sozinho.
//  - ③.5 PEDINDO_REPETICAO, entre o aviso de "repita" falado ao atendente e a nova captura: o
//    mesmo pedido é apresentado em Libras a quem sinalizou (playAvatar) e a captura só reabre no
//    "Capturar de novo" (repetirCaptura). Antes ela reabria sozinha, e a pessoa surda via a câmera
//    voltar sem nunca saber que o sistema não tinha entendido — o "repita" era só voz.
//  - onBateriaBaixa(): chamado pelo CameraViewModel quando o DAT reporta bateria baixa/crítica
//    dos óculos. Encerra a captura em curso (mesmo padrão de encerrarCapturaPorPausaLonga) e
//    marca a flag que faz ensureCameraActive() (dono: CameraViewModel) devolver
//    FalhaCamera.BATERIA_BAIXA dali em diante — nenhuma outra mudança de fluxo é necessária, o
//    "iniciar" já é bloqueado pelo mesmo caminho de qualquer outra falha de câmera.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import android.os.SystemClock
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.FalhaCamera
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.Speaker
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.SttEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWordDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.DesfechoAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.Contextualizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.GlossContextualizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Etapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Metricas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.Classificacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.EstadoSinalizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LandmarkPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tempos do diálogo que as configurações de demo editam (4.3, 5.3); lidos a cada uso. */
data class ParametrosDialogo(
    val tetoCapturaMs: Long = Transicoes.TETO_CAPTURA_SEM_SEGMENTO_MS,
    val tetoEscutaMs: Long = Transicoes.TETO_ESCUTA_MS,
    val folgaAposFalaMs: Long = Transicoes.FOLGA_APOS_FALA_MS,
    val silencioFimFraseMs: Long = Transicoes.SILENCIO_FIM_FRASE_MS,
)

class DialogOrchestrator(
    private val scope: CoroutineScope,
    private val landmarkPipeline: LandmarkPipeline,
    private val speaker: Speaker,
    private val sttEngine: SttEngine,
    // Glossário -> frase em PT-BR (modelo sob guarda -> template -> passthrough).
    private val contextualizer: GlossContextualizer,
    // Liga a câmera/stream dos óculos sob demanda e espera ficar pronta; devolve a causa se não subiu
    // (3.4), e quem chama a mostra na faixa de estado.
    private val ensureCameraActive: suspend () -> FalhaCamera?,
    // Desliga o stream; a captura de vídeo só é necessária no ②.
    private val deactivateCamera: () -> Unit,
    // ⑦: suspende até a animação terminar, um teto estourar ou o operador pular (9.1).
    private val playAvatar: suspend (String) -> DesfechoAvatar,
    // Cada "iniciar" que abre a câmera: é quando o avatar que caiu volta a carregar sozinho (9.5). O
    // pré-carregamento em si é do aquecimento (6.3, 6.4).
    private val aoIniciarCaptura: () -> Unit,
    // Fim do atendimento por inatividade: devolve os ~300 MB do processo do renderer.
    private val releaseAvatar: () -> Unit,
    // Caminho degradado do ⑦: a legenda fica na tela.
    private val onAvatarUnavailable: (String) -> Unit,
    // Painel de conversa (10.1). Só informa.
    private val onConversa: (EventoConversa) -> Unit = {},
    // Tempo por etapa de cada turno (6.5).
    private val metricas: Metricas? = null,
    // Confiança por frase, léxico e contador do "repita" (2.5, 2.8).
    private val avaliador: AvaliadorDeFrase = AvaliadorDeFrase(glosasConhecidas = null),
    // Esconde a tela do avatar sem destruir o Unity (9.2, 4.7).
    private val esconderAvatar: () -> Unit = {},
    // Encerra o ⑦ na hora ("Pular", 9.1).
    private val pularAvatar: () -> Unit = {},
    // Eventos para o gravador de sessão (1.9): decisão, glosa fora do léxico, cancelamento.
    private val onEvento: (nome: String, detalhe: String) -> Unit = { _, _ -> },
    // Tetos e folga editáveis (4.3, 5.3).
    private val parametros: () -> ParametrosDialogo = { ParametrosDialogo() },
    // Antes de abrir a escuta: escolhe o microfone (celular, ou óculos com troca de perfil, 5.2).
    private val antesDeEscutar: suspend () -> Unit = {},
    // Depois da escuta: devolve o perfil Bluetooth, se foi trocado.
    private val depoisDeEscutar: () -> Unit = {},
    // A câmera não subiu ou uma pausa longa encerrou a captura (3.4, 3.2).
    private val onFalhaCamera: (FalhaCamera) -> Unit = {},
    // [NOVO — docs/consentimento-por-atendimento-plano.md §2.5, §2.7] "Recusar" em ①.5: aviso de
    // bilhete/intérprete. Chamado toda vez que um pedido de consentimento novo começa, pra limpar
    // o aviso do atendimento anterior (não persiste entre atendimentos diferentes).
    private val onConsentimentoRecusado: () -> Unit = {},
    private val onConsentimentoPedido: () -> Unit = {},
    // [NOVO — §2.1] O avatar não animou (nem foi pulado) mostrando o consentimento: a explicação
    // ficou só na legenda em português. Decisão em aberto no plano — hoje só sinaliza, não bloqueia.
    private val onConsentimentoSemLibras: () -> Unit = {},
) {

  companion object {
    private const val TAG = "Libras:DialogOrchestrator"

    // Corta "Libras Livre, encerrar" do final da transcrição, se a frase vazar para o STT.
    private val TRAILING_ENCERRAR_PATTERN =
        Regex("""\s*libras\s+livre,?\s+encerrar[.!?]?\s*$""", RegexOption.IGNORE_CASE)

    // Atendimento ocioso depois do ⑦: libera o avatar e zera o contador do "repita" (4.3: sem mudança).
    private const val OCIOSO_ATENDIMENTO_MS = 60_000L

    // Stream pausado nos óculos por mais que isto encerra a captura (3.2).
    const val TETO_PAUSA_MS = 30_000L

    // Avisos do fluxo "repita" (2.8), falados ao atendente no ③.
    const val AVISO_REPITA = "Não consegui entender. Peça para repetir, com uma pausa entre os sinais."
    const val AVISO_DESISTIR = "Não foi possível entender. Tente outro meio de comunicação."

    // O mesmo pedido de repetição, agora dito a QUEM SINALIZOU (③.5). O [AVISO_REPITA] acima fala
    // com o atendente ("peça para repetir"); este é o texto que vai para o avatar, na segunda
    // pessoa. Frase curta e direta de propósito: passa pelo tradutor de glosa antes de virar
    // animação, e período longo vira glosa ruim.
    const val TEXTO_REPITA_PARA_O_SURDO = "Não entendi. Pode repetir devagar, com uma pausa entre cada sinal?"

    // Teto de ③.5, mesma escala e mesmo motivo do [TETO_CONFIRMACAO_MS]: o estado espera um toque
    // do operador e não pode prender o atendimento se a tela for largada. Aqui não decide por
    // ninguém — só devolve ao ①, porque a câmera já está desligada e nada foi captado.
    const val TETO_REPETICAO_MS = 60_000L

    // [NOVO — docs/consentimento-por-atendimento-plano.md §2.1] PLACEHOLDER: este texto NÃO foi
    // revisado juridicamente nem pela comunidade surda (o plano é explícito: "este plano não
    // propõe o texto"). Existe aqui só pra o fluxo ser exercitável e testável; não usar em
    // atendimento real sem substituir por um texto revisado.
    const val TEXTO_CONSENTIMENTO_PLACEHOLDER =
        "Vou usar a câmera pra reconhecer seus sinais e transformar em voz pro atendente. " +
            "Nada é gravado. Você pode recusar sem prejuízo: o atendimento segue por bilhete ou intérprete."

    // [NOVO] Timeout de segurança em ②.5 CONFIRMANDO_RECONHECIMENTO
    // (docs/confirmacao-e-modo-economia-plano.md §1.3, §1.6): se ninguém apertar "Confirmar" nem
    // "Corrigir", confirma sozinho — mesma escala do resto dos tetos de estado ativo (captura,
    // escuta), pra este estado nunca travar o atendimento se o operador largar a tela. Adição de
    // engenharia desta revisão, não pedido explícito da banca.
    const val TETO_CONFIRMACAO_MS = 60_000L
  }

  private val _state = MutableStateFlow(DialogState.AGUARDANDO_SINAL)
  val state: StateFlow<DialogState> = _state.asStateFlow()

  private var wakeWordDetector: WakeWordDetector? = null

  // Interruptor "Comando de voz" (4.6).
  private var wakeWordHabilitada = true

  // Stream pausado nos óculos (3.2) e o relógio da pausa longa.
  private var streamPausado = false
  private var pausaJob: Job? = null

  // Teto do estado ativo: captura sem segmento (30 s), escuta (20 s) ou atendimento ocioso (60 s).
  // Um campo só, porque os três estados são mutuamente exclusivos.
  private var tetoJob: Job? = null
  // Fim de frase automático do ② (4.1): armado quando o detector para.
  private var silencioJob: Job? = null

  // O que a captura em curso produziu; vai inteiro para o AvaliadorDeFrase ao fechar.
  private val classificacoes = mutableListOf<Classificacao>()
  private var falhas = 0
  // Aberta do startSession até o endSession terminar: classificações que chegam fora disso (depois
  // de um cancelamento) não entram em nenhuma frase.
  private var capturaAberta = false

  // Instante em que a escuta foi encerrada: começo da etapa "fim da fala -> texto" (6.5).
  private var fimDaFalaMs: Long? = null

  // Muda a cada "Cancelar atendimento": coroutines de antes (fala, avatar, timers) comparam com a
  // geração que viram ao começar e desistem de continuar o fluxo.
  private var geracao = 0

  // Evita um segundo "iniciar" enquanto a câmera do primeiro ainda sobe.
  private var startingSignSession = false

  // [NOVO — docs/consentimento-por-atendimento-plano.md] `startingSignSession` já está true
  // durante ①.5 (armado por beginSignSession, antes mesmo do consentimento) — não serve pra
  // proteger aceitarConsentimento() de um duplo toque rápido em "Aceitar", porque o estado só
  // sai de PEDINDO_CONSENTIMENTO depois de ensureCameraActive() (suspend) resolver. Guarda à
  // parte, só pra essa janela.
  private var ligandoCameraAposConsentimento = false

  // [NOVO] ②.5 CONFIRMANDO_RECONHECIMENTO (docs/confirmacao-e-modo-economia-plano.md §1): a frase
  // já contextualizada, mostrada pro surdo, esperando confirmarReconhecimento()/
  // corrigirReconhecimento() ou o timeout de segurança. null fora desse estado.
  private data class ConfirmacaoPendente(val texto: String, val origem: Contextualizacao.Origem)
  private var confirmacaoPendente: ConfirmacaoPendente? = null

  // [NOVO] Modo economia de bateria (docs/confirmacao-e-modo-economia-plano.md §2) — ligado uma
  // vez por onBateriaBaixa() e nunca desligado sozinho (o DAT não expõe "bateria recuperada").
  // Só usado aqui pra não repetir o encerramento forçado da captura duas vezes; quem realmente
  // bloqueia "iniciar"/"corrigir" é o CameraViewModel (ensureCameraActive devolvendo
  // FalhaCamera.BATERIA_BAIXA).
  private var bateriaBaixa = false

  /**
   * Liga a fonte de wake words. Aceita troca em tempo de execução (4.5): o motor anterior é parado e
   * liberado antes de o novo começar a ouvir — um motor de cada tipo por vez (8.3).
   */
  fun attachWakeWordDetector(detector: WakeWordDetector) {
    wakeWordDetector?.takeIf { it !== detector }?.stop()
    wakeWordDetector = detector
    if (Transicoes.wakeWordAtiva(_state.value, wakeWordHabilitada)) detector.start()
  }

  /** Religa o detector depois que RECORD_AUDIO é concedido, se o estado atual espera wake word. */
  fun resumeWakeWordDetectorIfActive() {
    if (Transicoes.wakeWordAtiva(_state.value, wakeWordHabilitada)) wakeWordDetector?.start()
  }

  /** Interruptor "Comando de voz" (4.6): desligado, nenhum estado ouve; os botões seguem valendo. */
  fun setWakeWordHabilitada(habilitada: Boolean) {
    if (wakeWordHabilitada == habilitada) return
    wakeWordHabilitada = habilitada
    if (habilitada) resumeWakeWordDetectorIfActive() else wakeWordDetector?.pause()
  }

  /**
   * O stream dos óculos pausou (toque na haste) ou voltou (3.2). Durante a captura, a pausa congela o
   * detector: não fecha o sinal, não conta como inatividade nem como falha do "repita". Passando de
   * [TETO_PAUSA_MS], a captura encerra com aviso e o diálogo volta ao ①. Chamar na main.
   */
  fun onStreamPausado(pausado: Boolean) {
    if (streamPausado == pausado) return
    streamPausado = pausado
    if (_state.value != DialogState.CAPTURANDO_SINAIS) return
    if (pausado) {
      tetoJob?.cancel()
      silencioJob?.cancel()
      val minhaGeracao = geracao
      pausaJob?.cancel()
      pausaJob =
          scope.launch {
            delay(TETO_PAUSA_MS)
            if (minhaGeracao == geracao && streamPausado && _state.value == DialogState.CAPTURANDO_SINAIS) {
              encerrarCapturaPorPausaLonga()
            }
          }
    } else {
      pausaJob?.cancel()
      landmarkPipeline.retomarDepoisDePausa()
      armarTetoDaCaptura()
    }
  }

  private fun encerrarCapturaPorPausaLonga() {
    Log.w(TAG, "Stream pausado por mais de ${TETO_PAUSA_MS}ms — encerrando a captura")
    geracao++
    tetoJob?.cancel()
    silencioJob?.cancel()
    capturaAberta = false
    scope.launch { landmarkPipeline.endSession() }
    classificacoes.clear()
    falhas = 0
    deactivateCamera()
    onEvento("pausa_longa", "teto_ms=$TETO_PAUSA_MS")
    onFalhaCamera(FalhaCamera.PAUSA_LONGA)
    voltarAoInicio()
  }

  /**
   * [NOVO] Chamado pelo CameraViewModel quando o DAT reporta `DeviceSessionError.BATTERY_CRITICAL`
   * (sessão) ou `StreamError.BATTERY_LOW` (stream) — docs/confirmacao-e-modo-economia-plano.md
   * §2. Se uma captura estiver em curso, encerra na hora (mesmo padrão de
   * [encerrarCapturaPorPausaLonga]): bateria crítica é urgente, não tenta preservar o que já foi
   * capturado. Fora da captura, só marca a flag — o bloqueio de fato é do CameraViewModel
   * (`ensureCameraActive` devolvendo `FalhaCamera.BATERIA_BAIXA`), o mesmo caminho que qualquer
   * outra falha de câmera já usa. Idempotente: um segundo evento de bateria não repete nada.
   */
  fun onBateriaBaixa() {
    if (bateriaBaixa) return
    bateriaBaixa = true
    if (_state.value != DialogState.CAPTURANDO_SINAIS) return
    Log.w(TAG, "Bateria baixa/crítica nos óculos — encerrando a captura em curso")
    geracao++
    tetoJob?.cancel()
    silencioJob?.cancel()
    pausaJob?.cancel()
    capturaAberta = false
    scope.launch { landmarkPipeline.endSession() }
    classificacoes.clear()
    falhas = 0
    deactivateCamera()
    onEvento("bateria_baixa", "")
    onFalhaCamera(FalhaCamera.BATERIA_BAIXA)
    voltarAoInicio()
  }

  /** Chamado pelo [WakeWordDetector] quando uma frase é ouvida. */
  fun onWakeWord(word: WakeWord) {
    when (_state.value) {
      DialogState.AGUARDANDO_SINAL -> if (word == WakeWord.INICIAR) beginSignSession()
      DialogState.CAPTURANDO_SINAIS -> if (word == WakeWord.ENCERRAR) endSignSession(MotivoEncerramento.MANUAL)
      DialogState.AGUARDANDO_RESPOSTA -> if (word == WakeWord.INICIAR) beginListening()
      DialogState.ESCUTANDO_ATENDENTE -> if (word == WakeWord.ENCERRAR) endListening()
      DialogState.PEDINDO_CONSENTIMENTO,
      DialogState.CONFIRMANDO_RECONHECIMENTO,
      DialogState.FALANDO,
      // ③.5 fica de fora pelo mesmo motivo de ①.5/②.5: a decisão de reabrir a captura é um toque
      // do operador, não uma palavra ouvida enquanto o avatar ainda está explicando.
      DialogState.PEDINDO_REPETICAO,
      DialogState.TRANSCREVENDO,
      DialogState.GERANDO_AVATAR ->
          Log.w(TAG, "Wake word '$word' ignorada em ${_state.value} (deveria estar pausada)")
    }
  }

  /**
   * O botão principal (e as teclas de volume, 4.7). Só age se a ação ainda for a do estado atual: um
   * toque atrasado, depois de a conversa avançar sozinha, não dispara o passo seguinte por engano.
   */
  fun onBotaoPrincipal(acao: AcaoBotao) {
    val esperada = Transicoes.botaoPrincipal(_state.value, oculosDisponiveis = true).acao
    if (acao != esperada) {
      Log.w(TAG, "Botão '$acao' ignorado em ${_state.value}")
      return
    }
    when (acao) {
      AcaoBotao.INICIAR -> beginSignSession()
      AcaoBotao.ENCERRAR_CAPTURA -> endSignSession(MotivoEncerramento.MANUAL)
      AcaoBotao.OUVIR -> beginListening()
      AcaoBotao.ENCERRAR_ESCUTA -> endListening()
      AcaoBotao.PULAR -> pularAvatar()
      AcaoBotao.CONFIRMAR -> confirmarReconhecimento()
      AcaoBotao.REPETIR -> repetirCaptura()
    }
  }

  /** Um sinal classificado na captura em curso (um por segmento). */
  fun onSignRecognized(classificacao: Classificacao) {
    if (!capturaAberta) return
    classificacoes.add(classificacao)
    val foraDoLexico = avaliador.foraDoLexico(classificacao.glosa)
    if (foraDoLexico) {
      // 2.5: não é falada; fica registrada.
      Log.i(TAG, "Glosa fora do léxico, não será falada: ${classificacao.glosa}")
      onEvento("glosa_fora_do_lexico", classificacao.glosa)
    }
    onConversa(
        EventoConversa.SinalClassificado(
            SinalNaConversa(
                glosa = classificacao.glosa,
                confianca = classificacao.confianca,
                abaixoDoLimiar = avaliador.abaixoDoLimiar(classificacao),
                foraDoLexico = foraDoLexico,
            )))
    if (_state.value == DialogState.CAPTURANDO_SINAIS) armarTetoDaCaptura()
  }

  /** Um segmento que o classificador não conseguiu classificar: conta como falha da frase (2.8). */
  fun onSignRecognitionFailed() {
    if (!capturaAberta) return
    falhas++
    Log.w(TAG, "Um segmento da sessão não foi classificado ($falhas na frase)")
    if (_state.value == DialogState.CAPTURANDO_SINAIS) armarTetoDaCaptura()
  }

  /**
   * O detector de fronteiras mudou de estado (4.1). Parado, arma o fim de frase; sinalizando, desarma.
   * Chamar na main.
   */
  fun onEstadoSinalizacao(estado: EstadoSinalizacao) {
    if (_state.value != DialogState.CAPTURANDO_SINAIS) return
    silencioJob?.cancel()
    if (estado != EstadoSinalizacao.PARADO || streamPausado) return
    val paradoDesde = SystemClock.elapsedRealtime()
    val minhaGeracao = geracao
    silencioJob =
        scope.launch {
          val silencio = parametros().silencioFimFraseMs
          delay(silencio)
          val segmentos = classificacoes.size + falhas
          if (minhaGeracao == geracao &&
              _state.value == DialogState.CAPTURANDO_SINAIS &&
              !streamPausado &&
              Transicoes.encerrarCapturaPorSilencio(segmentos, SystemClock.elapsedRealtime() - paradoDesde, silencio)) {
            endSignSession(MotivoEncerramento.SILENCIO)
          }
        }
  }

  /**
   * "Cancelar atendimento" (4.7): para fala e escuta, fecha a captura, desliga o stream, esconde o
   * avatar sem destruir, zera o contador do "repita" e volta ao ①.
   */
  fun cancelarAtendimento() {
    val anterior = _state.value
    geracao++
    tetoJob?.cancel()
    silencioJob?.cancel()
    pausaJob?.cancel()
    startingSignSession = false
    speaker.stop()
    if (anterior == DialogState.ESCUTANDO_ATENDENTE || anterior == DialogState.TRANSCREVENDO) {
      sttEngine.stop()
      depoisDeEscutar()
    }
    if (capturaAberta) {
      capturaAberta = false
      scope.launch { landmarkPipeline.endSession() }
    }
    classificacoes.clear()
    falhas = 0
    confirmacaoPendente = null
    deactivateCamera()
    // ①.5/②.5/③.5 também podem ter o avatar animando (aguardando consentimento, confirmação ou o
    // reinício da captura) — mesmo tratamento do ⑦.
    if (anterior == DialogState.GERANDO_AVATAR || anterior == DialogState.CONFIRMANDO_RECONHECIMENTO ||
        anterior == DialogState.PEDINDO_CONSENTIMENTO || anterior == DialogState.PEDINDO_REPETICAO) {
      pularAvatar()
    }
    esconderAvatar()
    avaliador.zerar()
    onEvento("atendimento_cancelado", "estado=$anterior")
    Log.i(TAG, "Atendimento cancelado em $anterior")
    voltarAoInicio()
  }

  private fun beginSignSession() {
    if (startingSignSession) return
    startingSignSession = true
    // O relógio da etapa "iniciar -> pode sinalizar" começa no comando, antes do consentimento e
    // antes de a câmera subir — ①.5 agora faz parte dessa latência.
    metricas?.novoTurno(SystemClock.uptimeMillis())
    val minhaGeracao = geracao
    scope.launch { pedirConsentimento(minhaGeracao) }
  }

  /**
   * ①.5 PEDINDO_CONSENTIMENTO (docs/consentimento-por-atendimento-plano.md §1, §2.4): antes de
   * ligar a câmera, mostra pra pessoa surda — mesmo playAvatar() de ②.5/⑦ — o que o sistema faz.
   * Fica esperando [aceitarConsentimento]/[recusarConsentimento]; sem timeout que decide sozinho
   * (§2.3 — silêncio não é consentimento). `startingSignSession` continua true até uma das duas
   * decidir, pra um segundo "iniciar" não reabrir o pedido por cima.
   */
  private suspend fun pedirConsentimento(minhaGeracao: Int) {
    onConsentimentoPedido()
    setState(DialogState.PEDINDO_CONSENTIMENTO)
    val desfecho = playAvatar(TEXTO_CONSENTIMENTO_PLACEHOLDER)
    if (minhaGeracao != geracao || _state.value != DialogState.PEDINDO_CONSENTIMENTO) {
      startingSignSession = false
      return
    }
    if (desfecho != DesfechoAvatar.ANIMOU && desfecho != DesfechoAvatar.PULADO) {
      // Degradação explícita (mesma legenda de sempre via onAvatarUnavailable), mais um aviso à
      // parte: aqui a apresentação em Libras não é só UX, é o requisito de acessibilidade do
      // consentimento (§2.1 do plano — decisão em aberto sobre bloquear ou não).
      onEvento("consentimento_sem_libras", "")
      onConsentimentoSemLibras()
    }
    // Fica em PEDINDO_CONSENTIMENTO à espera do atendente; startingSignSession segue true.
  }

  /**
   * Botão "Aceitar" em ①.5: só agora a câmera liga (§2.4 — nada é captado antes disso). Se a
   * câmera não subir, volta ao ①, como um "iniciar" comum que falhou. Sem estado próprio de
   * "consentimento já dado neste atendimento": um "iniciar" seguinte pede consentimento de novo
   * (não há como distinguir isso de um atendimento novo com o que existe hoje) — mais perguntas
   * que o estritamente necessário numa retentativa, não uma violação do §2.6 (o reset entre
   * atendimentos continua garantido; só não há um atalho para o caso de retentativa).
   */
  fun aceitarConsentimento() {
    if (_state.value != DialogState.PEDINDO_CONSENTIMENTO) return
    // Duplo toque rápido em "Aceitar": o estado só sai de PEDINDO_CONSENTIMENTO depois de
    // ensureCameraActive() (suspend) resolver, então a checagem acima sozinha não bastaria.
    if (ligandoCameraAposConsentimento) return
    ligandoCameraAposConsentimento = true
    onEvento("consentimento", "aceito")
    val minhaGeracao = geracao
    scope.launch {
      try {
        val falha = ensureCameraActive()
        if (minhaGeracao != geracao) return@launch
        if (falha != null) {
          Log.w(TAG, "Câmera/stream não subiu ($falha) — consentimento aceito, 'iniciar' não completou")
          onEvento("camera_nao_subiu", falha.name)
          onFalhaCamera(falha)
          pularAvatar()
          esconderAvatar()
          voltarAoInicio()
          return@launch
        }
        if (_state.value != DialogState.PEDINDO_CONSENTIMENTO) return@launch
        pularAvatar()
        esconderAvatar()
        aoIniciarCaptura()
        iniciarCaptura()
      } finally {
        startingSignSession = false
        ligandoCameraAposConsentimento = false
      }
    }
  }

  /**
   * Botão "Recusar" em ①.5 (§2.5): não trava, não insiste — some o avatar e volta ao ①, com um
   * aviso informativo pro atendente. Nenhum sinal foi captado (a câmera nunca ligou).
   */
  fun recusarConsentimento() {
    if (_state.value != DialogState.PEDINDO_CONSENTIMENTO) return
    // Invalida um aceitarConsentimento() concorrente (duplo toque em Aceitar-depois-Recusar):
    // a checagem de geração em aceitarConsentimento() já cobre isso antes mesmo de olhar o
    // resultado de ensureCameraActive() — mesmo padrão de cancelarAtendimento()/onBateriaBaixa().
    geracao++
    startingSignSession = false
    ligandoCameraAposConsentimento = false
    onEvento("consentimento", "recusado")
    pularAvatar()
    esconderAvatar()
    onConsentimentoRecusado()
    Log.i(TAG, "Consentimento recusado — atendimento segue por bilhete/intérprete")
    voltarAoInicio()
  }

  // Abre uma captura: no "iniciar" (depois do consentimento), no "Corrigir" de ②.5 e no "Capturar
  // de novo" de ③.5 — nos três a câmera já foi religada por quem chama.
  private fun iniciarCaptura() {
    classificacoes.clear()
    falhas = 0
    capturaAberta = true
    onConversa(EventoConversa.TurnoIniciado)
    setState(DialogState.CAPTURANDO_SINAIS)
    armarTetoDaCaptura()
    landmarkPipeline.startSession()
  }

  // 4.3: 30 s sem nenhum segmento encerram a captura; cada segmento reinicia o relógio.
  private fun armarTetoDaCaptura() {
    tetoJob?.cancel()
    if (streamPausado) return
    val minhaGeracao = geracao
    tetoJob =
        scope.launch {
          delay(parametros().tetoCapturaMs)
          if (minhaGeracao == geracao && _state.value == DialogState.CAPTURANDO_SINAIS) {
            endSignSession(MotivoEncerramento.TIMEOUT)
          }
        }
  }

  private fun endSignSession(motivo: MotivoEncerramento) {
    if (_state.value != DialogState.CAPTURANDO_SINAIS) return
    tetoJob?.cancel()
    silencioJob?.cancel()
    pausaJob?.cancel()
    // Pausa a wake word já aqui; a classificação de um sinal em aberto ainda vai terminar.
    setState(DialogState.FALANDO)
    val minhaGeracao = geracao
    scope.launch {
      // Força classificar o segmento em aberto e espera as classificações em voo.
      landmarkPipeline.endSession()
      capturaAberta = false
      if (minhaGeracao != geracao) return@launch

      val decisao = avaliador.avaliar(ResultadoSessao(classificacoes.toList(), falhas, motivo))
      Log.i(TAG, "Frase ($motivo, ${classificacoes.size} sinais, $falhas falhas): $decisao")
      onEvento(
          "decisao",
          "motivo=$motivo,decisao=${Conversas.decisaoNaConversa(decisao)},sinais=${classificacoes.size}," +
              "falhas=$falhas,rejeicoes_seguidas=${avaliador.rejeicoesSeguidas}")
      onConversa(EventoConversa.DecisaoTomada(Conversas.decisaoNaConversa(decisao)))

      // Efeitos da decisão no ③. [MUDOU] A câmera desliga em TODOS os caminhos: depois desta
      // decisão nenhum deles volta a captar sem um toque do operador (②.5 "Corrigir", ③.5
      // "Capturar de novo") ou um "iniciar" novo. Antes a repetição era a exceção — mantinha a
      // câmera ligada porque reabria a captura no mesmo instante.
      deactivateCamera()
      when (decisao) {
        // [MUDOU] Falar não fala mais direto: contextualiza e mostra pro SURDO em ②.5 primeiro
        // (docs/confirmacao-e-modo-economia-plano.md §1) — iniciarConfirmacao cuida do resto do
        // turno (fala e escuta, se confirmado; nova captura, se corrigido), por isso o early return.
        is DecisaoFrase.Falar -> {
          iniciarConfirmacao(decisao.glosas, minhaGeracao)
          return@launch
        }
        DecisaoFrase.PedirRepeticao -> speaker.speakAndAwait(AVISO_REPITA)
        DecisaoFrase.Desistir -> speaker.speakAndAwait(AVISO_DESISTIR)
        DecisaoFrase.Ignorar -> Unit
      }
      if (minhaGeracao != geracao || _state.value != DialogState.FALANDO) return@launch

      when (Transicoes.estadoAposDecisao(decisao)) {
        // ③.5: o aviso acabou de ser falado ao atendente; o mesmo pedido vai agora a quem
        // sinalizou, e a captura só reabre no "Capturar de novo".
        DialogState.PEDINDO_REPETICAO -> pedirRepeticaoAoSurdo(minhaGeracao)
        else -> voltarAoInicio()
      }
    }
  }

  /**
   * ③.5 PEDINDO_REPETICAO: mostra o pedido de repetição a QUEM SINALIZOU — o mesmo playAvatar() de
   * ①.5/②.5/⑦ — e fica esperando [repetirCaptura]. Antes desta etapa o "repita" era só voz para o
   * atendente e a captura reabria no mesmo instante: a pessoa surda via a câmera voltar sem saber
   * que precisava repetir. Chamada de dentro do scope.launch de [endSignSession] (por isso é
   * suspend, não abre um launch novo).
   */
  private suspend fun pedirRepeticaoAoSurdo(minhaGeracao: Int) {
    setState(DialogState.PEDINDO_REPETICAO)
    val desfecho = playAvatar(TEXTO_REPITA_PARA_O_SURDO)
    if (minhaGeracao != geracao) return
    // O operador pode ter tocado "Capturar de novo" (ou cancelado) durante a animação: quem já
    // decidiu vale mais que o aviso de avatar indisponível e que o teto armado abaixo.
    if (_state.value != DialogState.PEDINDO_REPETICAO) return
    // playAvatar() já deixou a legenda na tela; o aviso é para o caso de o avatar não ter animado.
    if (desfecho != DesfechoAvatar.ANIMOU && desfecho != DesfechoAvatar.PULADO) {
      onAvatarUnavailable(TEXTO_REPITA_PARA_O_SURDO)
    }
    tetoJob?.cancel()
    tetoJob =
        scope.launch {
          delay(TETO_REPETICAO_MS)
          if (minhaGeracao == geracao && _state.value == DialogState.PEDINDO_REPETICAO) {
            Log.i(TAG, "③.5 sem resposta do operador — voltando ao ①")
            pularAvatar()
            esconderAvatar()
            voltarAoInicio()
          }
        }
  }

  /**
   * Botão "Capturar de novo" em ③.5: o pedido de repetição já foi apresentado — religa a câmera e
   * reabre a captura, sem passar por ①.5 (o consentimento é por atendimento, e este é o mesmo).
   * Mesmo caminho de [corrigirReconhecimento]: em modo economia de bateria [ensureCameraActive]
   * devolve `FalhaCamera.BATERIA_BAIXA` e o atendimento volta ao ① com o aviso, em vez de o botão
   * ficar sem efeito. Tocar durante a animação do avatar vale como "pular".
   */
  fun repetirCaptura() {
    if (_state.value != DialogState.PEDINDO_REPETICAO) return
    if (startingSignSession) return
    startingSignSession = true
    tetoJob?.cancel()
    val minhaGeracao = geracao
    scope.launch {
      try {
        val falha = ensureCameraActive()
        if (minhaGeracao != geracao) return@launch
        if (falha != null) {
          Log.w(TAG, "Câmera não religou para a repetição ($falha) — voltando ao ①")
          onEvento("repetir_sem_camera", falha.name)
          onFalhaCamera(falha)
          pularAvatar()
          esconderAvatar()
          voltarAoInicio()
          return@launch
        }
        if (_state.value != DialogState.PEDINDO_REPETICAO) return@launch
        pularAvatar()
        esconderAvatar()
        aoIniciarCaptura()
        iniciarCaptura()
      } finally {
        startingSignSession = false
      }
    }
  }

  /**
   * ②.5 CONFIRMANDO_RECONHECIMENTO (docs/confirmacao-e-modo-economia-plano.md §1): contextualiza
   * a frase e mostra pro SURDO — o mesmo playAvatar() do ⑦ — antes de falar pro atendente.
   * Chamada só quando o avaliador decidiu Falar, de dentro do scope.launch de [endSignSession]
   * (por isso é suspend, não abre um launch novo). Fica em ②.5 esperando
   * [confirmarReconhecimento]/[corrigirReconhecimento] ou o timeout de segurança.
   */
  private suspend fun iniciarConfirmacao(glosas: List<String>, minhaGeracao: Int) {
    val inicioContextualizacao = SystemClock.elapsedRealtime()
    val resultado = contextualizer.contextualize(glosas)
    metricas?.marcar(
        Etapa.CONTEXTUALIZACAO, SystemClock.elapsedRealtime() - inicioContextualizacao, "origem=${resultado.origem}")
    Log.i(TAG, "glosas=$glosas -> \"${resultado.texto}\" (${resultado.origem})")
    if (minhaGeracao != geracao) return
    if (resultado.texto.isBlank()) {
      voltarAoInicio()
      return
    }
    confirmacaoPendente = ConfirmacaoPendente(resultado.texto, resultado.origem)
    setState(DialogState.CONFIRMANDO_RECONHECIMENTO)
    // playAvatar() já preenche a legenda mesmo se o avatar não subir (piso de acessibilidade,
    // 9.3) — o operador vê o texto pra confirmar/corrigir de qualquer jeito.
    val desfecho = playAvatar(resultado.texto)
    if (minhaGeracao != geracao) return
    // O operador (ou onBateriaBaixa()/cancelarAtendimento()) pode ter decidido enquanto o avatar
    // animava — nem o aviso de "avatar indisponível" nem o timeout valem por cima de uma decisão
    // que já aconteceu (ex.: "Confirmar" apertado durante a animação, antes dela terminar).
    if (_state.value != DialogState.CONFIRMANDO_RECONHECIMENTO) return
    if (desfecho != DesfechoAvatar.ANIMOU && desfecho != DesfechoAvatar.PULADO) onAvatarUnavailable(resultado.texto)
    tetoJob?.cancel()
    tetoJob =
        scope.launch {
          delay(TETO_CONFIRMACAO_MS)
          if (minhaGeracao == geracao && _state.value == DialogState.CONFIRMANDO_RECONHECIMENTO) {
            confirmarReconhecimento()
          }
        }
  }

  /**
   * Botão "Confirmar" em ②.5 (ou o timeout de segurança): a frase mostrada era isso mesmo — fala
   * pro atendente e a escuta abre sozinha, como o fluxo já fazia antes da confirmação existir.
   * No-op fora de CONFIRMANDO_RECONHECIMENTO ou sem frase pendente (ex.: os dois botões
   * apertados em sequência rápida, ou o timeout disparando depois de o operador já ter decidido).
   */
  fun confirmarReconhecimento() {
    if (_state.value != DialogState.CONFIRMANDO_RECONHECIMENTO) return
    val pendente = confirmacaoPendente ?: return
    confirmacaoPendente = null
    tetoJob?.cancel()
    val minhaGeracao = geracao
    setState(DialogState.FALANDO)
    scope.launch {
      onConversa(EventoConversa.FraseFalada(pendente.texto, pendente.origem))
      val inicioFala = SystemClock.elapsedRealtime()
      speaker.speakAndAwait(pendente.texto) {
        metricas?.marcar(Etapa.FRASE_PRIMEIRO_AUDIO, SystemClock.elapsedRealtime() - inicioFala)
      }
      if (minhaGeracao != geracao || _state.value != DialogState.FALANDO) return@launch
      // 5.3: a fala já terminou de tocar; a folga evita pegar o eco no microfone do celular.
      delay(parametros().folgaAposFalaMs)
      if (minhaGeracao == geracao && _state.value == DialogState.FALANDO) beginListening()
    }
  }

  /**
   * Botão "Corrigir" em ②.5: não era isso — descarta a frase pendente e reabre a captura de
   * sinais do zero, reaproveitando o mesmo caminho de [beginSignSession] (religa a câmera, avisa
   * [aoIniciarCaptura] pro avatar caído recarregar, arma o timeout de ② normal). Em modo economia
   * de bateria (§2) a câmera não religa — [ensureCameraActive] devolve
   * `FalhaCamera.BATERIA_BAIXA` do mesmo jeito que bloquearia um "iniciar" comum, e o fallback é
   * falar o que já foi reconhecido (melhor esforço) em vez de deixar o botão sem efeito.
   */
  fun corrigirReconhecimento() {
    if (_state.value != DialogState.CONFIRMANDO_RECONHECIMENTO) return
    if (startingSignSession) return
    startingSignSession = true
    tetoJob?.cancel()
    val minhaGeracao = geracao
    scope.launch {
      try {
        val falha = ensureCameraActive()
        if (minhaGeracao != geracao) return@launch
        if (falha != null) {
          Log.w(TAG, "Câmera não religou pra correção ($falha) — falando o que já foi reconhecido")
          onEvento("corrigir_sem_camera", falha.name)
          onFalhaCamera(falha)
          confirmarReconhecimento()
          return@launch
        }
        if (_state.value != DialogState.CONFIRMANDO_RECONHECIMENTO) return@launch
        confirmacaoPendente = null
        aoIniciarCaptura()
        iniciarCaptura()
      } finally {
        startingSignSession = false
      }
    }
  }

  private fun beginListening() {
    val atual = _state.value
    if (atual != DialogState.FALANDO && atual != DialogState.AGUARDANDO_RESPOSTA) return
    setState(DialogState.ESCUTANDO_ATENDENTE)
    val minhaGeracao = geracao
    tetoJob?.cancel()
    scope.launch {
      // 5.2: microfone do celular, ou dos óculos com troca de perfil (e queda para o celular sem SCO).
      antesDeEscutar()
      if (minhaGeracao == geracao && _state.value == DialogState.TRANSCREVENDO) {
        // "Encerrar agora" antes de o microfone abrir: o STT nem começou e nenhum resultado virá.
        onAttendantTranscriptionFailed()
        return@launch
      }
      if (minhaGeracao != geracao || _state.value != DialogState.ESCUTANDO_ATENDENTE) {
        depoisDeEscutar()
        return@launch
      }
      sttEngine.start(
          onResult = { text -> onAttendantTranscribed(text) },
          onError = { onAttendantTranscriptionFailed() },
          // 4.1: o Vosk fechou um enunciado com texto.
          onFimDeFala = {
            if (minhaGeracao == geracao && _state.value == DialogState.ESCUTANDO_ATENDENTE) endListening()
          },
      )
      tetoJob =
          scope.launch {
            delay(parametros().tetoEscutaMs)
            if (minhaGeracao == geracao && _state.value == DialogState.ESCUTANDO_ATENDENTE) endListening()
          }
    }
  }

  private fun endListening() {
    if (_state.value != DialogState.ESCUTANDO_ATENDENTE) return
    tetoJob?.cancel()
    fimDaFalaMs = SystemClock.elapsedRealtime()
    setState(DialogState.TRANSCREVENDO)
    // O resultado chega pelo onResult/onError configurado em beginListening().
    sttEngine.stop()
  }

  private fun onAttendantTranscribed(rawText: String) {
    if (_state.value != DialogState.TRANSCREVENDO) return
    fimDaFalaMs?.let { metricas?.marcar(Etapa.FIM_FALA_TEXTO, SystemClock.elapsedRealtime() - it) }
    fimDaFalaMs = null
    depoisDeEscutar()
    val text = rawText.replace(TRAILING_ENCERRAR_PATTERN, "").trim()
    if (Transicoes.estadoAposTranscricao(text) == DialogState.AGUARDANDO_RESPOSTA) {
      setState(DialogState.AGUARDANDO_RESPOSTA)
      return
    }
    val minhaGeracao = geracao
    scope.launch {
      onConversa(EventoConversa.RespostaTranscrita(text))
      setState(DialogState.GERANDO_AVATAR)
      val desfecho = playAvatar(text)
      Log.i(TAG, "⑦ terminou: $desfecho")
      if (minhaGeracao != geracao) return@launch
      if (desfecho != DesfechoAvatar.ANIMOU && desfecho != DesfechoAvatar.PULADO) onAvatarUnavailable(text)
      onConversa(EventoConversa.AvatarTerminou(desfecho))
      // O avatar fica carregado para o próximo turno (4.2: o ⑦ volta ao ① e espera o "iniciar").
      voltarAoInicio()
    }
  }

  // Escuta que falhou ou voltou vazia: ④, com o botão "Ouvir resposta".
  private fun onAttendantTranscriptionFailed() {
    val atual = _state.value
    if (atual != DialogState.TRANSCREVENDO && atual != DialogState.ESCUTANDO_ATENDENTE) return
    tetoJob?.cancel()
    fimDaFalaMs = null
    depoisDeEscutar()
    setState(DialogState.AGUARDANDO_RESPOSTA)
  }

  private fun voltarAoInicio() {
    setState(DialogState.AGUARDANDO_SINAL)
    tetoJob?.cancel()
    val minhaGeracao = geracao
    tetoJob =
        scope.launch {
          delay(OCIOSO_ATENDIMENTO_MS)
          if (minhaGeracao == geracao && _state.value == DialogState.AGUARDANDO_SINAL) encerrarAtendimento()
        }
  }

  /** Fim do ATENDIMENTO por inatividade: libera o avatar e a próxima pessoa começa com o contador zerado. */
  private fun encerrarAtendimento() {
    Log.i(TAG, "Atendimento ocioso — liberando o avatar")
    releaseAvatar()
    avaliador.zerar()
  }

  // Único ponto que muda o estado E decide se a wake word deve estar ouvindo.
  private fun setState(newState: DialogState) {
    _state.value = newState
    if (Transicoes.wakeWordAtiva(newState, wakeWordHabilitada)) {
      wakeWordDetector?.start()
    } else {
      wakeWordDetector?.pause()
    }
  }
}

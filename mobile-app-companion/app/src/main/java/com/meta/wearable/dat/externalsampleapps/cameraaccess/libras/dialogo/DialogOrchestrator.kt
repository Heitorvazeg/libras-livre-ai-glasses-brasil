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

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import android.os.SystemClock
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.AudioSessionManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.Speaker
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.SttEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWordDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.DesfechoAvatar
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

class DialogOrchestrator(
    private val scope: CoroutineScope,
    private val landmarkPipeline: LandmarkPipeline,
    private val speaker: Speaker,
    // Troca A2DP/HFP: fica fora do caminho da escuta enquanto o microfone da resposta é o do celular
    // (docs/prontidao-demo/05 §5.2); volta com o modo óculos do seletor (onda 4).
    private val audioSessionManager: AudioSessionManager,
    private val sttEngine: SttEngine,
    // Glossário -> frase em PT-BR (modelo sob guarda -> template -> passthrough).
    private val contextualizer: GlossContextualizer,
    // Liga a câmera/stream dos óculos sob demanda e espera ficar pronta; false se não subiu a tempo.
    private val ensureCameraActive: suspend () -> Boolean,
    // Desliga o stream; a captura de vídeo só é necessária no ②.
    private val deactivateCamera: () -> Unit,
    // ⑦: suspende até a animação terminar, um teto estourar ou o operador pular (9.1).
    private val playAvatar: suspend (String) -> DesfechoAvatar,
    // Pré-carrega o avatar escondido (o Unity leva 6-9 s). O aquecimento do 6.4 assume na onda 4.
    private val prepareAvatar: () -> Unit,
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
) {

  companion object {
    private const val TAG = "Libras:DialogOrchestrator"

    // Corta "Libras Livre, encerrar" do final da transcrição, se a frase vazar para o STT.
    private val TRAILING_ENCERRAR_PATTERN =
        Regex("""\s*libras\s+livre,?\s+encerrar[.!?]?\s*$""", RegexOption.IGNORE_CASE)

    // Atendimento ocioso depois do ⑦: libera o avatar e zera o contador do "repita" (4.3: sem mudança).
    private const val OCIOSO_ATENDIMENTO_MS = 60_000L

    // Avisos do fluxo "repita" (2.8), falados ao atendente no ③.
    const val AVISO_REPITA = "Não consegui entender. Peça para repetir, com uma pausa entre os sinais."
    const val AVISO_DESISTIR = "Não foi possível entender. Tente outro meio de comunicação."
  }

  private val _state = MutableStateFlow(DialogState.AGUARDANDO_SINAL)
  val state: StateFlow<DialogState> = _state.asStateFlow()

  private var wakeWordDetector: WakeWordDetector? = null

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

  /** Liga a fonte de wake words — chamar uma vez, na criação. */
  fun attachWakeWordDetector(detector: WakeWordDetector) {
    wakeWordDetector = detector
    if (Transicoes.wakeWordAtiva(_state.value)) detector.start()
  }

  /** Religa o detector depois que RECORD_AUDIO é concedido, se o estado atual espera wake word. */
  fun resumeWakeWordDetectorIfActive() {
    if (Transicoes.wakeWordAtiva(_state.value)) wakeWordDetector?.start()
  }

  /** Chamado pelo [WakeWordDetector] quando uma frase é ouvida. */
  fun onWakeWord(word: WakeWord) {
    when (_state.value) {
      DialogState.AGUARDANDO_SINAL -> if (word == WakeWord.INICIAR) beginSignSession()
      DialogState.CAPTURANDO_SINAIS -> if (word == WakeWord.ENCERRAR) endSignSession(MotivoEncerramento.MANUAL)
      DialogState.AGUARDANDO_RESPOSTA -> if (word == WakeWord.INICIAR) beginListening()
      DialogState.ESCUTANDO_ATENDENTE -> if (word == WakeWord.ENCERRAR) endListening()
      DialogState.FALANDO,
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
    if (estado != EstadoSinalizacao.PARADO) return
    val paradoDesde = SystemClock.elapsedRealtime()
    val minhaGeracao = geracao
    silencioJob =
        scope.launch {
          delay(Transicoes.SILENCIO_FIM_FRASE_MS)
          val segmentos = classificacoes.size + falhas
          if (minhaGeracao == geracao &&
              _state.value == DialogState.CAPTURANDO_SINAIS &&
              Transicoes.encerrarCapturaPorSilencio(segmentos, SystemClock.elapsedRealtime() - paradoDesde)) {
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
    startingSignSession = false
    speaker.stop()
    if (anterior == DialogState.ESCUTANDO_ATENDENTE || anterior == DialogState.TRANSCREVENDO) sttEngine.stop()
    if (capturaAberta) {
      capturaAberta = false
      scope.launch { landmarkPipeline.endSession() }
    }
    classificacoes.clear()
    falhas = 0
    deactivateCamera()
    if (anterior == DialogState.GERANDO_AVATAR) pularAvatar()
    esconderAvatar()
    avaliador.zerar()
    onEvento("atendimento_cancelado", "estado=$anterior")
    Log.i(TAG, "Atendimento cancelado em $anterior")
    voltarAoInicio()
  }

  private fun beginSignSession() {
    if (startingSignSession) return
    startingSignSession = true
    // O relógio da etapa "iniciar -> pode sinalizar" começa no comando, antes de a câmera subir.
    metricas?.novoTurno(SystemClock.uptimeMillis())
    val minhaGeracao = geracao
    scope.launch {
      try {
        if (!ensureCameraActive()) {
          Log.w(TAG, "Câmera/stream não ficou pronta a tempo — 'iniciar' ignorado")
          return@launch
        }
        if (minhaGeracao != geracao || _state.value != DialogState.AGUARDANDO_SINAL) return@launch
        prepareAvatar()
        iniciarCaptura()
      } finally {
        startingSignSession = false
      }
    }
  }

  // Abre uma captura: no "iniciar" e, com a câmera ainda ligada, depois de um "repita" (2.8).
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
    val minhaGeracao = geracao
    tetoJob =
        scope.launch {
          delay(Transicoes.TETO_CAPTURA_SEM_SEGMENTO_MS)
          if (minhaGeracao == geracao && _state.value == DialogState.CAPTURANDO_SINAIS) {
            endSignSession(MotivoEncerramento.TIMEOUT)
          }
        }
  }

  private fun endSignSession(motivo: MotivoEncerramento) {
    if (_state.value != DialogState.CAPTURANDO_SINAIS) return
    tetoJob?.cancel()
    silencioJob?.cancel()
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

      // Efeitos da decisão no ③. Na repetição a câmera continua ligada: é o mesmo turno de captura.
      when (decisao) {
        is DecisaoFrase.Falar -> {
          deactivateCamera()
          falarFrase(decisao.glosas)
        }
        DecisaoFrase.PedirRepeticao -> speaker.speakAndAwait(AVISO_REPITA)
        DecisaoFrase.Desistir -> {
          deactivateCamera()
          speaker.speakAndAwait(AVISO_DESISTIR)
        }
        DecisaoFrase.Ignorar -> deactivateCamera()
      }
      if (minhaGeracao != geracao || _state.value != DialogState.FALANDO) return@launch

      when (Transicoes.estadoAposDecisao(decisao)) {
        DialogState.ESCUTANDO_ATENDENTE -> {
          // 5.3: a fala já terminou de tocar; a folga evita pegar o eco no microfone do celular.
          delay(Transicoes.FOLGA_APOS_FALA_MS)
          if (minhaGeracao == geracao && _state.value == DialogState.FALANDO) beginListening()
        }
        DialogState.CAPTURANDO_SINAIS -> iniciarCaptura()
        else -> voltarAoInicio()
      }
    }
  }

  private suspend fun falarFrase(glosas: List<String>) {
    val inicioContextualizacao = SystemClock.elapsedRealtime()
    val resultado = contextualizer.contextualize(glosas)
    metricas?.marcar(
        Etapa.CONTEXTUALIZACAO, SystemClock.elapsedRealtime() - inicioContextualizacao, "origem=${resultado.origem}")
    Log.i(TAG, "glosas=$glosas -> \"${resultado.texto}\" (${resultado.origem})")
    if (resultado.texto.isBlank()) return
    onConversa(EventoConversa.FraseFalada(resultado.texto, resultado.origem))
    val inicioFala = SystemClock.elapsedRealtime()
    speaker.speakAndAwait(resultado.texto) {
      metricas?.marcar(Etapa.FRASE_PRIMEIRO_AUDIO, SystemClock.elapsedRealtime() - inicioFala)
    }
  }

  private fun beginListening() {
    val atual = _state.value
    if (atual != DialogState.FALANDO && atual != DialogState.AGUARDANDO_RESPOSTA) return
    setState(DialogState.ESCUTANDO_ATENDENTE)
    val minhaGeracao = geracao
    // Microfone do celular (5.2): sem troca de perfil Bluetooth.
    sttEngine.start(
        onResult = { text -> onAttendantTranscribed(text) },
        onError = { onAttendantTranscriptionFailed() },
        // 4.1: o Vosk fechou um enunciado com texto.
        onFimDeFala = {
          if (minhaGeracao == geracao && _state.value == DialogState.ESCUTANDO_ATENDENTE) endListening()
        },
    )
    tetoJob?.cancel()
    tetoJob =
        scope.launch {
          delay(Transicoes.TETO_ESCUTA_MS)
          if (minhaGeracao == geracao && _state.value == DialogState.ESCUTANDO_ATENDENTE) endListening()
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
    if (Transicoes.wakeWordAtiva(newState)) {
      wakeWordDetector?.start()
    } else {
      wakeWordDetector?.pause()
    }
  }
}

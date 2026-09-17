/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraViewModel - DAT camera lifecycle, capture, and recording
//
// Drives the camera screen by exercising the SDK's camera lifecycle as explicit steps: create and
// start a DeviceSession, add and start a Stream, capture a photo or record video, stop the stream,
// end the session. Owns the screen-scoped pieces — the DeviceSession, its Stream, the on-device
// HEVC preview decoder, and the passthrough video recorder. The UI binds to the SDK's own
// DeviceSessionState / StreamState so the sample shows the real state machine.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import android.webkit.WebView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.AudioSessionManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.PcmMicCapture
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.AvatarPlayer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.AvatarState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.GlosaCache
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.VLibrasGlosaTranslator
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.PiperSherpaOnnxTtsEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.Speaker
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.SpeechRecognizerWakeWordDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.SttEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.VoskSttEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWordDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.criarGlossContextualizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogOrchestrator
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.CapturaDialogo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.MotivoConfirmacaoNaoConcluida
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LandmarkPipeline
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.PlaceholderSignClassifier
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcDecoder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcParameterSetCollector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.RecordingResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.VideoRecorder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.drenarVideoAntesDeLimpar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import android.os.SystemClock
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.DesfechoAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.TetosAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Conversas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Etapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.GravadorSessao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.LeitorSistema
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.LeituraSistema
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Metricas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.LexicoGlosas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.AcaoBotao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.AvaliadorDeFrase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModeloRecusado
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.SignClassifier
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.TfliteSignClassifier
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.PowerManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.Aquecimento
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.TextosLibras
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.EtapaAquecimento
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.StatusEtapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.AndroidTextToSpeechEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.OpenWakeWordDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.TtsEmCadeia
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.MicrofoneResposta
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.MotorWakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.PressaoDeMemoria
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Aviso
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DecisaoNaConversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.EventoConversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.ParametrosDialogo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.TipoAviso
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LandmarkPipeline.Companion.ERRO_MODELOS
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.AcoesDeDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.FabricaClassificadorApp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.DiagnosticoClassificador

class CameraViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:CameraViewModel"
    private const val FRAME_RATE = 24
    private const val KEYFRAME_WAIT_STEP_MS = 25L
    private const val KEYFRAME_WAIT_MAX_MS = 500L
    // Tempos-limite de ensureCameraActiveForLibras() esperando o DeviceSession/Stream do DAT
    // convergir — generosos porque envolvem handshake real com os óculos via Bluetooth.
    private const val CAMERA_SESSION_READY_TIMEOUT_MS = 6000L
    private const val CAMERA_STREAM_READY_TIMEOUT_MS = 8000L
    // Espera do avatar ficar pronto no aquecimento (o AvatarPlayer desiste sozinho em 20 s).
    private const val AVATAR_AQUECIMENTO_MS = 25_000L
    private const val MB = 1024L * 1024L
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  // Identidade do VM, não do Service global nem do Stream que será drenado em onCleared.
  private val donoServico = UUID.randomUUID().toString()

  private val _uiState = MutableStateFlow(CameraUiState())
  val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private val politicaCamera = PoliticaCamera()
  private var aberturaCameraJob: Job? = null
  private var permissaoPendenteToken: Long? = null
  // Inclui confirmação local, fila do launcher e decisão no app Meta; não tem prazo humano.
  private val aguardandoPermissaoCamera = MutableStateFlow(false)
  // A causa "permissão da câmera pendente" sobrevive à invalidação da abertura: o stream que cai
  // enquanto o pedido está na tela apaga o pedido e invalida a geração, e sem isto o diagnóstico
  // que chega à faixa de estado viraria o genérico "stream não subiu".
  private var permissaoPendenteNaUltimaTentativa = false
  private var streamEncerrando: Stream? = null
  // O viewModelScope já está cancelado em onCleared. A limpeza precisa sobreviver para drenar
  // o frame/IO em voo sem runBlocking na main, que também recebe callbacks do recorder.
  private val encerramentoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var encerramentoStreamJob: Job? = null
  private val gravacaoMutex = Mutex()

  // Recording pieces. The single compressed-HEVC stream feeds both the on-screen decoder and the
  // passthrough MP4 writer. Video-only (ver stream/VideoRecorder.kt) — o mic do celular não é
  // mais usado aqui.
  private val videoRecorder = VideoRecorder(application, viewModelScope)

  // Libras Livre — reconhecimento de sinal local + voz, e orquestração da sessão de diálogo
  // bidirecional (docs/orquestracao-dialogo-audio-plano.md). dialogOrchestrator é referenciado
  // por lambdas capturadas ANTES de ser inicializado (landmarkPipeline/wakeWordDetector, abaixo)
  // — seguro porque essas lambdas só são invocadas depois que o init{} abaixo o atribui.
  private lateinit var dialogOrchestrator: DialogOrchestrator

  // Motor real de TTS: Piper (pt-BR) local via sherpa-onnx (ver docs/orquestracao-dialogo-audio-plano.md
  // §4 item 10, §8 item 4). Pra voltar ao motor nativo do Android (fallback, sem depender dos
  // assets de tts/pt_br/), troque por Speaker(application) — construtor usa
  // AndroidTextToSpeechEngine por padrão quando nenhum TtsEngine é passado.
  // Diagnóstico da demo (docs/prontidao-demo: 1.9 gravador, 3.8 painel, 6.5 tempo por etapa, 10.6
  // configurações). As configurações são a mesma instância que o menu de debug altera.
  private val configuracoes = ConfiguracoesDemo.de(application)
  private val gravador = GravadorSessao(application.getExternalFilesDir(null) ?: application.filesDir)
  private val leitorSistema = LeitorSistema(application)
  private val metricas =
      Metricas(
          onMarca = { marca ->
            gravador.evento(SystemClock.uptimeMillis(), marca.turno, "latencia", Metricas.linhaLog(marca))
          })

  // Voz em cadeia (5.5): Piper com saída selecionável (5.1); o TTS do Android só é criado se o Piper
  // falhar (8.3), e aí fica em uso até reiniciar o app, com aviso na faixa.
  private val vozEmCadeia =
      TtsEmCadeia(
          principal = PiperSherpaOnnxTtsEngine(application, saida = { configuracoes.valores.value.saidaVoz }),
          criarReserva = { AndroidTextToSpeechEngine(application) },
          onReserva = { definirAviso(TipoAviso.VOZ_RESERVA, textos.vozReserva) },
      )
  private val speaker = Speaker(application, vozEmCadeia)
  // "Simular queda do avatar" (9.5), registrado no menu de debug no init.
  private val simularQuedaDoAvatar: () -> Unit = { viewModelScope.launch { avatarPlayer.simularQueda() } }
  private val classificador: SignClassifier by lazy { criarClassificador() }
  private val textos = TextosLibras(application)
  private val landmarkPipeline =
      LandmarkPipeline(
          context = application,
          scope = viewModelScope,
          classifier = classificador,
          onState = { transform -> _uiState.update { it.copy(libras = it.libras.transform()) } },
          onRecognized = { classificacao -> dialogOrchestrator.onSignRecognized(classificacao) },
          onRecognitionFailed = { dialogOrchestrator.onSignRecognitionFailed() },
          parametros = { configuracoes.valores.value.segmentacao },
          metricas = metricas,
          onFrameProcessado = { frame -> gravador.frame(frame, metricas.turno) },
          onEvento = { nome, detalhe -> gravador.evento(SystemClock.uptimeMillis(), metricas.turno, nome, detalhe) },
          // 4.1: o fim de frase automático é decidido pelo orquestrador, na main.
          onEstadoSinalizacao = { estado -> viewModelScope.launch { dialogOrchestrator.onEstadoSinalizacao(estado) } },
      )

  /**
    * O build privado exige identidade fixada e assets íntegros. Qualquer falha recusa o modelo,
    * sem fallback. Apenas o build sem opt-in e sem modelo usa a simulação configurada.
   */
  private fun criarClassificador(): SignClassifier {
    val assets = getApplication<Application>().assets
    val carregado = FabricaClassificadorApp.carregar(
      assets = assets,
        criarSimulado = { PlaceholderSignClassifier(modo = { configuracoes.valores.value.modoPlaceholder }) },
    )
    val diagnostico = DiagnosticoClassificador(carregado.modo, carregado.identidade, carregado.motivo,
      "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
    val limiar = configuracoes.valores.value.limiarConfianca
    _uiState.update { it.copy(classificador = diagnostico, limiarClassificador = limiar) }
    Log.i(TAG, diagnostico.detalhes(limiar))
    carregado.motivo?.let { motivo ->
      Log.e(TAG, motivo)
      _uiState.update { it.copy(libras = it.libras.copy(error = motivo)) }
    }
    return carregado.classificador
  }
  private val audioSessionManager = AudioSessionManager(application)

  // Sentido OUVINTE -> SURDO (docs/vlibras-webview-plano.md). O tradutor fala com o endpoint
  // público do VLibras e guarda o resultado em disco — as perguntas de balcão se repetem, e o
  // cache é o que torna o modo sem rede parcialmente útil.
  private val glosaTranslator =
      VLibrasGlosaTranslator(GlosaCache(File(application.filesDir, "vlibras/glosa-cache.tsv")))

  // O avatar é criado uma vez e reaproveitado durante o atendimento inteiro; só o ciclo
  // prepare/release é dirigido pelo DialogOrchestrator (§4.2 do plano).
  private val avatarPlayer =
      AvatarPlayer(
          context = application,
          onState = { s ->
            _uiState.update { it.copy(avatarState = s) }
            // 6.5: "texto -> avatar sinalizando" termina quando a animação começa de verdade.
            if (s == AvatarState.ANIMANDO) {
              inicioTextoAvatarMs?.let { metricas.marcar(Etapa.TEXTO_AVATAR, SystemClock.elapsedRealtime() - it) }
              inicioTextoAvatarMs = null
            }
            // 8.1: a animação terminou com uma liberação por memória pendente.
            if (s == AvatarState.PRONTO && liberarAvatarAoTerminar) {
              liberarAvatar(porMemoria = true)
            }
            // Falhou no meio da espera do ⑦ (renderer morto, Unity que não ficou pronto): desiste
            // agora. Sem isto o turno pagaria os AVATAR_TIMEOUT_MS inteiros para chegar à mesma
            // conclusão que o AvatarPlayer já tinha.
            if (s == AvatarState.FALHOU) {
              avatarAnimacaoTerminada?.let { if (it.isActive) it.complete(false) }
            }
          },
          onGlossEnd = { avatarAnimacaoTerminada?.let { if (it.isActive) it.complete(true) } },
      )

  /**
   * A WebView do avatar, para o [ui.AvatarScreen] anexar.
   *
   * Fica FORA do [CameraUiState] de propósito: uma View numa data class de estado quebra a
   * igualdade estrutural e segura contexto vivo dentro de um StateFlow. Quem dispara a
   * recomposição é o `avatarState`, que muda exatamente quando esta View nasce, fica pronta ou
   * morre — a tela então busca a referência atual aqui.
   */
  val avatarView: WebView?
    get() = avatarPlayer.view

  /** Congela/descongela o Unity com o app em background — ver AvatarScreen. */
  fun pausarAvatar() = avatarPlayer.pause()

  fun retomarAvatar() = avatarPlayer.resume()

  // Completado por onGlossEnd (true) ou pela transição para FALHOU (false) — é como playAvatar()
  // sabe que pode devolver o controle ao ⑦.
  @Volatile private var avatarAnimacaoTerminada: CompletableDeferred<Boolean>? = null

  // Cadeia modelo -> guarda -> template -> passthrough (§3.3). Nasce uma vez e vive até
  // onCleared(): o Interpreter do .tflite não deve ser recriado por sessão (§7.1).
  // usarModelo vem do toggle de DEBUG (ConfiguracoesDemo) — lido uma vez aqui, não reativo:
  // trocar o toggle vale a partir do próximo lançamento do app, mesma semântica do
  // classificador de sinal. Nunca exposto pro atendente fora do menu de debug (11,9% de
  // taxa de invenção medida, ver Guardas.kt) — serve pra coletar dado real de quando erra.
  private val glossContextualizer =
      criarGlossContextualizer(application, usarModelo = configuracoes.valores.value.modeloContextualizacaoAtivo)

  // Motor real de STT: Vosk pt-BR local (§4 item 11, §8 item 2), sobre PCM cru. Pra voltar ao motor
  // nativo do Android (fallback, sem depender do asset vosk-model-small-pt-0.3/), troque por
  // AndroidSpeechRecognizerSttEngine(application).
  //
  // Microfone da resposta: o do CELULAR (docs/prontidao-demo/05-audio.md §5.2), para a demo não
  // depender da troca A2DP/HFP. Sem dispositivo SCO, a escuta pelos óculos abortava em silêncio. O
  // modo óculos (VOICE_COMMUNICATION + TYPE_BLUETOOTH_SCO) volta com o seletor, na onda 4.
  private val attendantAudioCapture =
      PcmMicCapture(
          context = application,
          audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
          preferredDeviceType = AudioDeviceInfo.TYPE_BUILTIN_MIC,
      )
  private val sttEngine = VoskSttEngine(application, attendantAudioCapture)

  // Motor de wake word: ainda SpeechRecognizerWakeWordDetector (motor de destravamento, §4 item
  // 7), NÃO OpenWakeWordDetector (motor real escolhido, §4 item 9) — este último exige os dois
  // classificadores .onnx treinados (Fase 3, §7), que não existem neste repo ainda. Trocar pra ele
  // é só trocar a implementação aqui embaixo por
  // OpenWakeWordDetector(context = application, onWakeWord = { ... }) depois que os assets
  // existirem — comparar objetivamente contra este motor antes (§7 Fase 3 critério de sucesso).
  // Os botões de fallback (DialogControlRow) não passam por aqui — chamam
  // dialogOrchestrator.onWakeWord diretamente via onWakeWordButton, então continuam funcionando
  // mesmo se o motor real falhar/estiver sem permissão.
  // Trocável pelas configurações de demo (4.5); só o motor selecionado existe (8.3).
  private var motorAtual = configuracoes.valores.value.motorWakeWord
  private var wakeWordDetector: WakeWordDetector = criarWakeWordDetector(configuracoes.valores.value.motorWakeWord)

  private fun criarWakeWordDetector(motor: MotorWakeWord): WakeWordDetector =
      when (motor) {
        MotorWakeWord.SPEECH_RECOGNIZER ->
            SpeechRecognizerWakeWordDetector(
                context = getApplication(),
                onWakeWord = { word -> dialogOrchestrator.onWakeWord(word) },
            )
        MotorWakeWord.OPEN_WAKE_WORD ->
            OpenWakeWordDetector(
                context = getApplication(),
                onWakeWord = { word -> dialogOrchestrator.onWakeWord(word) },
            )
      }

  // Per-frame work (byte copy, NAL parsing, MediaMuxer writes, decoder feed) runs at frame rate and
  // must stay off the main thread. A single-threaded dispatcher keeps frames serialized so the
  // MediaMuxer/MediaCodec see in-order calls from one consistent thread.
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

  // Guards decoder create/teardown so a frame can't bind a new decoder to a Surface that
  // setSurface(null) just released — the @Volatile refs alone can't fix that check-then-act.
  private val decoderLock = Any()

  // @Volatile: single refs shared by the frame-collector and main threads, nulled at teardown.
  @Volatile private var hevcDecoder: HevcDecoder? = null
  @Volatile private var decoderSurface: Surface? = null
  // Protegido por decoderLock; não perder a barreira ao remover/trocar o Surface.
  private val decodersPreviewParados = mutableSetOf<HevcDecoder>()

  // Accumulates the stream's HEVC parameter sets (VPS/SPS/PPS) so a recording (or decoder) started
  // mid-stream can be primed with a complete format. The SDK emits the VPS once at stream start, so
  // a partial set yields an unfinalizable file ("Missing codec specific data"). Internally
  // synchronized.
  private val csdCollector = HevcParameterSetCollector()

  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null

  init {
    // Mirror the recorder's intent/elapsed into UI state.
    viewModelScope.launch {
      videoRecorder.isRecording.collect { recording ->
        _uiState.update { it.copy(isRecording = recording) }
      }
    }
    viewModelScope.launch {
      videoRecorder.recordingElapsedSeconds.collect { seconds ->
        _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
      }
    }

    // Libras Livre: orquestrador da sessão de diálogo (ver comentário no campo lateinit acima).
    dialogOrchestrator =
        DialogOrchestrator(
            scope = viewModelScope,
          landmarkPipeline = CapturaDialogo(
            landmarkPipeline::startSession,
            landmarkPipeline::endSession,
            landmarkPipeline::retomarDepoisDePausa,
          ),
          speaker = vozEmCadeia,
          politicaCamera = politicaCamera,
            sttEngine = sttEngine,
            contextualizer = glossContextualizer,
            ensureCameraActive = ::ensureCameraActiveForLibras,
            deactivateCamera = ::deactivateCameraForLibras,
            playAvatar = ::playAvatar,
            aoIniciarCaptura = ::recarregarAvatarSeCaiu,
            releaseAvatar = ::liberarAvatar,
            onAvatarUnavailable = { text ->
              // Degradação explícita, nunca silêncio: a pessoa surda perde o avatar, mas a
              // legenda aparece e o atendente ouve que a resposta não foi sinalizada.
              Log.w(TAG, "Avatar indisponível — caindo para legenda: \"$text\"")
              _uiState.update { it.copy(avatarLegenda = text) }
            },
            onConversa = { evento ->
              _uiState.update { it.copy(conversa = Conversas.reduzir(it.conversa, evento)) }
              // 2.8: "não entendi" fica na faixa até a próxima frase aceita.
              if (evento is EventoConversa.DecisaoTomada) {
                when (evento.decisao) {
                  DecisaoNaConversa.REPITA -> definirAviso(TipoAviso.REPITA, textos.repita)
                  DecisaoNaConversa.DESISTIU -> definirAviso(TipoAviso.REPITA, textos.desistiu)
                  else -> limparAviso(TipoAviso.REPITA)
                }
              }
            },
            metricas = metricas,
            // 2.5/2.8: sem o léxico (asset ausente), o avaliador aceita todas as glosas.
            avaliador =
                AvaliadorDeFrase(
                    glosasConhecidas = runCatching { LexicoGlosas.fromAssets(application).glosas }.getOrNull(),
                    limiar = { configuracoes.valores.value.limiarConfianca },
                ),
            // A legenda sai junto: ela é o texto do passo que acabou (consentimento, confirmação,
            // pedido de repetição). Deixá-la para trás fazia o "Avatar" reaberto no meio da
            // captura mostrar o consentimento do início do atendimento, sob o rótulo errado.
            esconderAvatar = {
              _uiState.update { it.copy(avatarVisivel = false, avatarLegenda = null, avatarAssunto = null) }
            },
            pularAvatar = ::pularAvatar,
            onEvento = { nome, detalhe -> gravador.evento(SystemClock.uptimeMillis(), metricas.turno, nome, detalhe) },
            parametros = {
              val v = configuracoes.valores.value
              ParametrosDialogo(tetoCapturaMs = v.tetoCapturaMs, tetoEscutaMs = v.tetoEscutaMs, folgaAposFalaMs = v.folgaAposFalaMs)
            },
            antesDeEscutar = ::prepararMicrofoneDaResposta,
            depoisDeEscutar = ::devolverMicrofoneDaResposta,
            onFalhaCamera = { falha -> definirAviso(TipoAviso.CAMERA_NAO_SUBIU, textos.falhaCamera(falha)) },
            // Libras Livre — consentimento por atendimento (docs/consentimento-por-atendimento-plano.md).
            onConsentimentoPedido = {
              limparAviso(TipoAviso.CONSENTIMENTO_RECUSADO)
              limparAviso(TipoAviso.CONSENTIMENTO_SEM_LIBRAS)
            },
            onConsentimentoRecusado = { definirAviso(TipoAviso.CONSENTIMENTO_RECUSADO, textos.consentimentoRecusado) },
            onConsentimentoSemLibras = { definirAviso(TipoAviso.CONSENTIMENTO_SEM_LIBRAS, textos.consentimentoSemLibras) },
            onConfirmacaoNaoConcluida = { motivo ->
              definirAviso(
                  TipoAviso.CONFIRMACAO_NAO_CONCLUIDA,
                  when (motivo) {
                    MotivoConfirmacaoNaoConcluida.TETO_EXPIRADO -> textos.confirmacaoExpirada
                    MotivoConfirmacaoNaoConcluida.CORRECAO_SEM_CAMERA -> textos.correcaoSemCamera
                  })
            },
        )
    dialogOrchestrator.attachWakeWordDetector(wakeWordDetector)
    AcoesDeDemo.simularQuedaDoAvatar = simularQuedaDoAvatar

    viewModelScope.launch {
      configuracoes.valores.map { it.limiarConfianca }.distinctUntilChanged().collect { limiar ->
        _uiState.update { it.copy(limiarClassificador = limiar) }
        _uiState.value.classificador?.let { Log.i(TAG, it.detalhes(limiar)) }
      }
    }

    // 4.5, 4.6: motor da wake word e interruptor "Comando de voz" seguem as configurações de demo.
    viewModelScope.launch {
      configuracoes.valores.map { it.motorWakeWord }.distinctUntilChanged().collect { motor ->
        if (motor == motorAtual) return@collect
        Log.i(TAG, "trocando o motor de wake word: $motorAtual -> $motor")
        wakeWordDetector.stop()
        motorAtual = motor
        wakeWordDetector = criarWakeWordDetector(motor)
        dialogOrchestrator.attachWakeWordDetector(wakeWordDetector)
      }
    }
    viewModelScope.launch {
      configuracoes.valores.map { it.comandoDeVoz }.distinctUntilChanged().collect { ligado ->
        dialogOrchestrator.setWakeWordHabilitada(ligado)
        _uiState.update { it.copy(comandoDeVoz = ligado) }
      }
    }
    // 9.1: o teto da tradução mora no tradutor.
    viewModelScope.launch {
      configuracoes.valores.map { it.tetoTraducaoMs }.distinctUntilChanged().collect { glosaTranslator.tetoMs = it }
    }
    // 6.3: o Unity pausa quando a tela do avatar fecha.
    viewModelScope.launch {
      uiState.map { it.avatarVisivel }.distinctUntilChanged().collect { avatarPlayer.visivel = it }
    }
    // 3.2: pausa do stream pelo toque na haste, com aviso na faixa.
    viewModelScope.launch {
      uiState.map { it.isPaused }.distinctUntilChanged().collect { pausado ->
        dialogOrchestrator.onStreamPausado(pausado)
        if (pausado) definirAviso(TipoAviso.STREAM_PAUSADO, textos.streamPausado) else limparAviso(TipoAviso.STREAM_PAUSADO)
      }
    }
    // A câmera subiu: some o aviso de falha e o de erro dos óculos.
    viewModelScope.launch {
      uiState.map { it.isStreaming }.distinctUntilChanged().collect { streaming ->
        if (streaming) {
          limparAviso(TipoAviso.CAMERA_NAO_SUBIU)
          limparAviso(TipoAviso.ERRO_OCULOS)
          limparAviso(TipoAviso.PAUSA_LONGA)
        }
      }
    }

    // 6.4 (e 3.1, 5.4, 5.5, 6.3): tudo o que é pesado carrega ao abrir o app, em sequência.
    viewModelScope.launch { aquecer() }

    // 1.9: com o gravador ligado, um CSV por sessão com os óculos — abre quando a sessão começa (ou
    // quando o interruptor é ligado no meio dela) e fecha quando qualquer um dos dois termina.
    viewModelScope.launch {
      combine(configuracoes.valores, uiState.map { it.hasSession }.distinctUntilChanged()) { valores, sessao ->
            valores to sessao
          }
          .collect { (valores, sessao) ->
            if (valores.gravadorSessao && sessao) {
              if (gravador.arquivo == null) {
                withContext(Dispatchers.IO) { gravador.abrir() }
              }
              _uiState.value.classificador?.let {
                gravador.evento(SystemClock.uptimeMillis(), metricas.turno, "identidade_classificador",
                    it.detalhes(valores.limiarConfianca))
              }
            } else if (gravador.arquivo != null) {
              withContext(Dispatchers.IO) { gravador.fechar() }
            }
            _uiState.update {
              it.copy(painelMetricas = valores.painelMetricas, arquivoGravacao = gravador.arquivo?.name)
            }
          }
    }

    // 3.8: uma amostra por segundo. A leitura do sistema (getPss) só roda com o painel ou o gravador
    // ligados; os contadores de fps são baratos e fecham a janela sempre.
    viewModelScope.launch {
      while (isActive) {
        delay(1_000)
        val valores = configuracoes.valores.value
        verificarTemperaturaEMemoria(valores.fatorLimiarMemoria)
        val ligado = valores.painelMetricas || gravador.arquivo != null
        val sistema = if (ligado) withContext(Dispatchers.Default) { leitorSistema.ler() } else LeituraSistema()
        val filaCheia = landmarkPipeline.filaCheiaDecoder + (hevcDecoder?.vezesFilaCheia ?: 0)
        val amostra = metricas.amostrar(SystemClock.uptimeMillis(), sistema, filaCheia)
        if (!ligado) continue
        if (valores.painelMetricas) {
          _uiState.update { it.copy(metricas = amostra, etapasTurno = metricas.etapasDoTurnoAtual()) }
        }
        val ts = SystemClock.uptimeMillis()
        val turno = metricas.turno
        gravador.metrica(ts, turno, "fps_recebido", amostra.fpsRecebido.toString())
        gravador.metrica(ts, turno, "fps_decodificado", amostra.fpsDecodificado.toString())
        gravador.metrica(ts, turno, "fps_processado", amostra.fpsProcessado.toString())
        gravador.metrica(ts, turno, "pct_sem_pose", amostra.pctSemPose.toString())
        gravador.metrica(ts, turno, "fila_cheia", amostra.filaCheia.toString())
        sistema.folgaTermica?.let { gravador.metrica(ts, turno, "folga_termica", it.toString()) }
        sistema.estadoTermico?.let { gravador.metrica(ts, turno, "estado_termico", it.toString()) }
        sistema.bateriaPct?.let { gravador.metrica(ts, turno, "bateria_pct", it.toString()) }
        sistema.ramAppMb?.let { gravador.metrica(ts, turno, "ram_app_mb", it.toString()) }
      }
    }
    viewModelScope.launch {
      dialogOrchestrator.state.collect { state -> _uiState.update { it.copy(dialogState = state) } }
    }
  }

  // Tetos do ⑦ (docs/prontidao-demo/09-avatar.md §9.1). O da tradução mora no glosaTranslator,
  // que é quem faz a requisição; os dois precisam andar juntos.
  private val tetosAvatar: TetosAvatar
    get() {
      val v = configuracoes.valores.value
      return TetosAvatar(traducaoMs = v.tetoTraducaoMs, animacaoBaseMs = v.tetoAnimacaoBaseMs, animacaoPorSinalMs = v.tetoAnimacaoPorSinalMs)
    }

  // Completado por pularAvatar(): o "Pular" do operador encerra o ⑦ em qualquer ponto.
  @Volatile private var puloDoAvatar: CompletableDeferred<Unit>? = null

  // Início da etapa "texto -> avatar sinalizando" do turno em curso (6.5).
  @Volatile private var inicioTextoAvatarMs: Long? = null

  /**
   * Estado ⑦: traduz o texto do atendente para glosa e manda o avatar sinalizar, suspendendo até
   * a animação terminar, um teto estourar ou o operador tocar "Pular". Tudo que não for
   * [DesfechoAvatar.ANIMOU] ou [DesfechoAvatar.PULADO] faz o DialogOrchestrator ficar só com a
   * legenda.
   *
   * Os tetos existem porque o gloss:end vem do Unity, e um player travado não pode prender a
   * conversa. Antes eram 45 s fixos para a animação e 30 s + 30 s para a tradução, com os botões
   * desabilitados — até ~105 s.
   */
  private suspend fun playAvatar(text: String): DesfechoAvatar {
    // Abre a tela ANTES de traduzir, e com a legenda já preenchida: a pessoa surda vê o que foi
    // dito enquanto a glosa vem da rede, e os dois caminhos de falha (sem rede, player caído)
    // encontram a tela aberta mostrando o texto em vez de devolverem preto.
    // O assunto vem do estado de AGORA porque o orquestrador sempre entra no passo antes de
    // chamar playAvatar (①.5, ②.5, ③.5, ⑦); depois disso o estado anda e a legenda fica.
    val assunto = assuntoDoEstado(dialogOrchestrator.state.value)
    _uiState.update { it.copy(avatarVisivel = true, avatarLegenda = text, avatarAssunto = assunto) }
    inicioTextoAvatarMs = SystemClock.elapsedRealtime()
    val pulo = CompletableDeferred<Unit>().also { puloDoAvatar = it }
    return try {
      coroutineScope {
        val trabalho = async { traduzirEAnimar(text, inicioMs = SystemClock.elapsedRealtime()) }
        select {
          trabalho.onAwait { it }
          pulo.onAwait {
            trabalho.cancel()
            avatarPlayer.parar()
            DesfechoAvatar.PULADO
          }
        }
      }
    } finally {
      puloDoAvatar = null
      avatarAnimacaoTerminada = null
    }
  }

  private fun assuntoDoEstado(estado: DialogState): AssuntoAvatar =
      when (estado) {
        DialogState.PEDINDO_CONSENTIMENTO -> AssuntoAvatar.CONSENTIMENTO
        DialogState.CONFIRMANDO_RECONHECIMENTO -> AssuntoAvatar.CONFIRMACAO
        DialogState.PEDINDO_REPETICAO -> AssuntoAvatar.REPETICAO
        else -> AssuntoAvatar.RESPOSTA
      }

  private suspend fun traduzirEAnimar(text: String, inicioMs: Long): DesfechoAvatar {
    if (avatarPlayer.state == AvatarState.FALHOU) return DesfechoAvatar.AVATAR_INDISPONIVEL
    val glosa = glosaTranslator.traduzir(text) ?: return DesfechoAvatar.SEM_GLOSA
    Log.i(TAG, "glosa para o avatar: \"$glosa\"")

    val tetoAnimacao = tetosAvatar.animacaoMs(glosa)
    val restanteDoTotal = tetosAvatar.totalMs(glosa) - (SystemClock.elapsedRealtime() - inicioMs)
    val espera = CompletableDeferred<Boolean>()
    avatarAnimacaoTerminada = espera
    avatarPlayer.play(glosa)
    val concluiu =
        withTimeoutOrNull(minOf(tetoAnimacao, restanteDoTotal).coerceAtLeast(0L)) { espera.await() }
    return when (concluiu) {
      true -> DesfechoAvatar.ANIMOU
      false -> DesfechoAvatar.AVATAR_INDISPONIVEL
      null -> {
        avatarPlayer.parar()
        val desfecho =
            if (tetoAnimacao <= restanteDoTotal) DesfechoAvatar.TETO_ANIMACAO else DesfechoAvatar.TETO_TOTAL
        Log.w(TAG, "⑦: $desfecho (animação ${tetoAnimacao}ms, restante do total ${restanteDoTotal}ms)")
        desfecho
      }
    }
  }

  /** "Pular" (9.1): encerra o ⑦ na hora, com a legenda na tela. No-op fora do ⑦. */
  fun pularAvatar() {
    puloDoAvatar?.complete(Unit)
  }

  /**
   * Abre a tela do avatar por ação explícita do operador (botão), não pela máquina de estados.
   *
   * Existe porque o avatar só tem o que mostrar no ⑦, e entre um atendimento e outro ele ficaria
   * ocioso segurando ~300 MB. Fechar e reabrir custa os 6-9 s de carga do Unity — aceitável
   * justamente por ser intencional: quem apertou o botão sabe que pediu, e a UI mostra
   * [AvatarState.CARREGANDO] enquanto isso.
   */
  fun abrirAvatar() {
    avatarPlayer.prepare()
    _uiState.update { it.copy(avatarVisivel = true) }
  }

  /**
   * "Fechar" só ESCONDE a tela (docs/prontidao-demo/09 §9.2): o Unity continua carregado e a próxima
   * resposta anima sem os 6-9 s de carga. O avatar só é liberado por inatividade do atendimento (ou,
   * na onda 4, por pressão de memória).
   */
  fun fecharAvatar() {
    _uiState.update { it.copy(avatarVisivel = false) }
  }

  /** "Iniciar" dentro da tela do avatar (9.2): esconde a tela e começa a captura. */
  fun iniciarPeloAvatar() {
    fecharAvatar()
    dialogOrchestrator.onBotaoPrincipal(AcaoBotao.INICIAR)
  }

  /** Interruptor "Comando de voz" da tela principal (4.6), salvo nas configurações de demo. */
  fun definirComandoDeVoz(ligado: Boolean) = configuracoes.atualizar { it.copy(comandoDeVoz = ligado) }

  /** "Cancelar atendimento" (4.7). */
  fun cancelarAtendimento() = dialogOrchestrator.cancelarAtendimento()

  /**
   * Botão "Corrigir" em ②.5 CONFIRMANDO_RECONHECIMENTO
   * (docs/confirmacao-e-modo-economia-plano.md §1.3). "Confirmar" já passa pelo botão principal
   * (AcaoBotao.CONFIRMAR, via onBotaoPrincipal) — este é o pequeno, à parte, mesmo padrão de
   * [cancelarAtendimento].
   */
  fun corrigirReconhecimento() = dialogOrchestrator.corrigirReconhecimento()

  /**
   * Botões "Aceitar"/"Recusar" em ①.5 PEDINDO_CONSENTIMENTO
   * (docs/consentimento-por-atendimento-plano.md §2.2). Os dois têm o mesmo peso visual — não é o
   * modelo de botão principal + botão pequeno de [corrigirReconhecimento] — por isso os dois
   * ficam de fora de [onBotaoPrincipal].
   */
  fun aceitarConsentimento() = dialogOrchestrator.aceitarConsentimento()

  fun recusarConsentimento() = dialogOrchestrator.recusarConsentimento()

  /**
   * Destrói a WebView E fecha a tela. As duas coisas andam juntas: o DialogOrchestrator chama
   * isto quando o atendimento encerra por inatividade, e uma tela aberta sobre uma WebView
   * destruída mostraria um retângulo preto sem dono.
   */
  private fun liberarAvatar() = liberarAvatar(porMemoria = false)

  private fun liberarAvatar(porMemoria: Boolean) {
    liberarAvatarAoTerminar = false
    avatarLiberadoPorMemoria = porMemoria
    avatarPlayer.release()
    // Por memória, a tela e a legenda ficam: a resposta escrita é o piso da pessoa surda (9.3), e só
    // a WebView precisa ir embora.
    if (!porMemoria) _uiState.update { it.copy(avatarVisivel = false, avatarLegenda = null, avatarAssunto = null) }
    if (porMemoria) {
      gravador.evento(SystemClock.uptimeMillis(), metricas.turno, "avatar_liberado_memoria", "")
      definirAviso(TipoAviso.AVATAR_LIBERADO_MEMORIA, textos.avatarLiberadoMemoria)
    }
  }

  // MARK: - Libras: avisos, aquecimento, microfone, memória (docs/prontidao-demo, onda 4)

  private fun definirAviso(tipo: TipoAviso, texto: String) {
    _uiState.update { it.copy(avisos = it.avisos + (tipo to Aviso(tipo, texto, SystemClock.uptimeMillis()))) }
  }

  private fun limparAviso(tipo: TipoAviso) {
    if (tipo !in _uiState.value.avisos) return
    _uiState.update { it.copy(avisos = it.avisos - tipo) }
  }

  private fun onBateriaBaixa() {
    if (politicaCamera.economia) return
    politicaCamera.ativarEconomia()
    _uiState.update { it.copy(bateriaBaixa = true) }
    Log.w(TAG, "Bateria baixa/crítica nos óculos — modo economia ligado")
    // Persistente (ao contrário do ERRO_OCULOS acima, que é um evento único): o operador precisa
    // lembrar que a captura de sinais ficou desligada pelo resto do atendimento, não só no
    // instante em que a bateria caiu.
    definirAviso(TipoAviso.BATERIA_OCULOS_BAIXA, textos.falhaCamera(FalhaCamera.BATERIA_BAIXA))
    dialogOrchestrator.onBateriaBaixa()
  }

  /** 6.4: as etapas do plano, em ordem. O "iniciar" libera depois das cinco primeiras. */
  private suspend fun aquecer() {
    val etapas =
        listOf(
            EtapaAquecimento(textos.etapaMediaPipe, bloqueiaIniciar = true) {
              if (!landmarkPipeline.carregarModelos()) error(ERRO_MODELOS)
            },
            EtapaAquecimento(textos.etapaClassificador, bloqueiaIniciar = true) {
              withContext(Dispatchers.Default) { classificador.aquecer() }
            },
            EtapaAquecimento(textos.etapaContextualizacao, bloqueiaIniciar = true) {
              glossContextualizer.contextualize(listOf("filho", "medo"))
            },
            EtapaAquecimento(textos.etapaVosk, bloqueiaIniciar = true) {
              if (!sttEngine.carregarModelo()) error(textos.voskNaoCarregou)
            },
            EtapaAquecimento(textos.etapaVoz, bloqueiaIniciar = true) {
              val frases = listOf(DialogOrchestrator.AVISO_REPITA, DialogOrchestrator.AVISO_DESISTIR)
              if (!speaker.aquecer(frases)) error(textos.vozReserva)
            },
            EtapaAquecimento(textos.etapaAvatar, bloqueiaIniciar = false) {
              avatarPlayer.prepare()
              val final =
                  withTimeoutOrNull(AVATAR_AQUECIMENTO_MS) {
                    uiState.first { it.avatarState == AvatarState.PRONTO || it.avatarState == AvatarState.FALHOU }
                  }
              if (final?.avatarState != AvatarState.PRONTO) error(textos.avatarNaoCarregou)
            },
        )
    val resultados =
        Aquecimento(etapas, relogioMs = { SystemClock.elapsedRealtime() }) { lista, pronto ->
              _uiState.update { it.copy(aquecimento = lista, aquecido = pronto) }
            }
            .executar()
    for (r in resultados) {
      val detalhe = "status=${r.status},ms=${r.ms}" + (r.motivo?.let { ",motivo=$it" } ?: "")
      gravador.evento(SystemClock.uptimeMillis(), 0, "aquecimento_${r.nome}", detalhe)
      Log.i(TAG, "aquecimento: ${r.nome} $detalhe")
    }
    // O avatar tem legenda como piso: só as etapas que bloqueiam viram bloqueio na faixa.
    val falhas = resultados.filterIndexed { i, r -> r.status == StatusEtapa.FALHOU && etapas[i].bloqueiaIniciar }
    if (falhas.isNotEmpty()) {
      definirAviso(TipoAviso.AQUECIMENTO_FALHOU, textos.aquecimentoFalhou(falhas.joinToString { "${it.nome}: ${it.motivo}" }))
    }
  }

  // 5.2: microfone da resposta. No celular, sem troca de perfil Bluetooth; nos óculos, troca para
  // HFP e, sem SCO, cai para o celular com aviso em vez de abortar a escuta.
  @Volatile private var escutaPelosOculos = false

  private suspend fun prepararMicrofoneDaResposta() {
    if (configuracoes.valores.value.microfoneResposta == MicrofoneResposta.OCULOS) {
      if (audioSessionManager.acquireListening() != null) {
        attendantAudioCapture.configurar(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        escutaPelosOculos = true
        limparAviso(TipoAviso.MIC_OCULOS_INDISPONIVEL)
        return
      }
      definirAviso(TipoAviso.MIC_OCULOS_INDISPONIVEL, textos.micOculosIndisponivel)
    } else {
      limparAviso(TipoAviso.MIC_OCULOS_INDISPONIVEL)
    }
    attendantAudioCapture.configurar(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioDeviceInfo.TYPE_BUILTIN_MIC)
    escutaPelosOculos = false
  }

  private fun devolverMicrofoneDaResposta() {
    if (!escutaPelosOculos) return
    escutaPelosOculos = false
    audioSessionManager.releaseListening()
  }

  // 8.1: liberação pendente de um avatar que estava animando, e o motivo da última liberação (9.5).
  @Volatile private var liberarAvatarAoTerminar = false
  @Volatile private var avatarLiberadoPorMemoria = false
  @Volatile private var memoriaBaixaAgora = false

  private val memoriaCallbacks by lazy {
      object : ComponentCallbacks2 {
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
          // A partir do Android 14 os níveis RUNNING_* podem não chegar a apps em primeiro plano; a
          // verificação por segundo cobre esse caso.
          if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            viewModelScope.launch { reagirAMemoriaBaixa("onTrimMemory($level)") }
          }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
          viewModelScope.launch { reagirAMemoriaBaixa("onLowMemory") }
        }
      }
  }

  // Registrado depois da declaração acima (a ordem dos init{} segue a do arquivo).
  init {
    getApplication<Application>().registerComponentCallbacks(memoriaCallbacks)
  }

  private fun verificarTemperaturaEMemoria(fatorLimiar: Float) {
    val app = getApplication<Application>()
    // 7.3: estado térmico sério ou pior avisa na faixa.
    val power = app.getSystemService(Application.POWER_SERVICE) as? PowerManager
    val termico = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
    if (termico >= PowerManager.THERMAL_STATUS_SEVERE) definirAviso(TipoAviso.CELULAR_QUENTE, textos.celularQuente)
    else limparAviso(TipoAviso.CELULAR_QUENTE)
    // 8.1: verificação ativa.
    val am = app.getSystemService(Application.ACTIVITY_SERVICE) as? ActivityManager ?: return
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    memoriaBaixaAgora = PressaoDeMemoria.memoriaBaixa(info.availMem, info.threshold, info.lowMemory, fatorLimiar)
    if (memoriaBaixaAgora) reagirAMemoriaBaixa("availMem=${info.availMem / MB}MB threshold=${info.threshold / MB}MB")
  }

  private fun reagirAMemoriaBaixa(origem: String) {
    val decisao =
        PressaoDeMemoria.decidir(
            baixa = true,
            avatar = avatarPlayer.state,
            vozReservaCriada = vozEmCadeia.reservaCriada,
            vozReservaEmUso = vozEmCadeia.emReserva,
        )
    when (decisao.avatar) {
      PressaoDeMemoria.AcaoAvatar.LIBERAR_AGORA -> {
        Log.w(TAG, "memória baixa ($origem) — liberando o avatar")
        liberarAvatar(porMemoria = true)
      }
      PressaoDeMemoria.AcaoAvatar.LIBERAR_DEPOIS_DA_ANIMACAO -> liberarAvatarAoTerminar = true
      PressaoDeMemoria.AcaoAvatar.NENHUMA -> Unit
    }
    if (decisao.liberarVozReserva && vozEmCadeia.liberarReservaOciosa()) {
      Log.w(TAG, "memória baixa ($origem) — voz de reserva ociosa liberada")
    }
  }

  /**
   * 9.5: no "iniciar", um avatar que caiu (renderer morto, carga que travou) ou foi liberado volta a
   * carregar em segundo plano — sem ninguém tocar em "Tentar de novo". Não tenta se a última
   * liberação foi por falta de memória e ela continua baixa.
   */
  private fun recarregarAvatarSeCaiu() {
    val estado = avatarPlayer.state
    if (estado != AvatarState.FALHOU && estado != AvatarState.OCIOSO) return
    if (avatarLiberadoPorMemoria && memoriaBaixaAgora) return
    Log.i(TAG, "avatar em $estado no iniciar — recarregando em segundo plano")
    avatarLiberadoPorMemoria = false
    limparAviso(TipoAviso.AVATAR_LIBERADO_MEMORIA)
    avatarPlayer.prepare()
  }

  // MARK: - Surface

  fun setSurface(surface: Surface?) {
    synchronized(decoderLock) {
      decoderSurface = surface
      if (surface == null) {
        aposentarDecoderPreviewLocked()
      }
    }
    if (surface == null) encerramentoScope.launch { drenarDecodersPreviewParados() }
  }

  private fun aposentarDecoderPreviewLocked() {
    val parado = hevcDecoder ?: return
    hevcDecoder = null
    decodersPreviewParados.add(parado)
    parado.stop()
  }

  private suspend fun drenarDecodersPreviewParados() {
    val pendentes = synchronized(decoderLock) { decodersPreviewParados.toList() }
    for (parado in pendentes) {
      parado.stopAndDrain()
      synchronized(decoderLock) { decodersPreviewParados.remove(parado) }
    }
  }

  // MARK: - Lifecycle step 1: session

  /** Creates and starts a [DeviceSession] (no stream yet). */
  fun startSession() {
    if (_uiState.value.hasSession) return
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no initial transitions are missed.
          observeSession(created)
          _uiState.update { it.copy(sessionState = DeviceSessionState.STARTING) }
          created.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to start session: ${error.description}")
          wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          cleanupSession()
        }
  }

  /**
   * Ends the device session. The stream and any in-progress recording are not torn down here
  * directly by the SDK alone: canceling the dialogue first invalidates opening/permission work
  * and stops the camera, then [onStreamTerminated] finalizes recording and releases stream
  * resources.
   *
   * Libras Livre: encerra o ATENDIMENTO junto. São duas coisas diferentes — a [DeviceSession] é o
   * vínculo com os óculos, o atendimento é a máquina de estados do diálogo —, mas quem toca
   * "Encerrar sessão" está mandando parar, e o diálogo não tem como seguir sem câmera. Sem isto o
   * orquestrador ficava capturando às cegas até o teto de ② expirar, com painel e avatar de pé.
   * Só no toque do operador: uma sessão que cai sozinha (bateria, alcance) não cancela nada, porque
   * a escuta da resposta usa o microfone do celular e continua valendo (5.2).
   */
  fun endSession() {
    dialogOrchestrator.cancelarAtendimento()
    val current = session ?: return
    _uiState.update { it.copy(sessionState = DeviceSessionState.STOPPING) }
    current.stop()
  }

  private fun observeSession(session: DeviceSession) {
    sessionStateJob = viewModelScope.launch {
      session.state.collect { state ->
        if (this@CameraViewModel.session !== session) return@collect
        _uiState.update { it.copy(sessionState = state) }
        if (state == DeviceSessionState.STOPPED) {
          cleanupSession()
        }
      }
    }
    sessionErrorJob = viewModelScope.launch {
      session.errors.collect { error ->
        if (this@CameraViewModel.session !== session) return@collect
        // All session errors surface through the snackbar, including
        // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, which the SDK delivers as a one-shot event.
        Log.e(TAG, "Session error: ${error.description}")
        val mensagem = error.getLocalizedDescription(getApplication())
        wearablesViewModel.setRecentError(mensagem)
        definirAviso(TipoAviso.ERRO_OCULOS, mensagem)
        // Libras Livre — modo economia de bateria (docs/confirmacao-e-modo-economia-plano.md §2).
        // Confirmado por inspeção do mwdat-core-0.9.0.aar real (javap): enum, comparável com ==.
        if (error == DeviceSessionError.BATTERY_CRITICAL) onBateriaBaixa()
      }
    }
  }

  private fun cleanupSession() {
    stopStreaming() // Invalida também uma espera de sessão/permissão sem Camera criada.
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    session = null
  }

  // MARK: - Lifecycle step 2: stream (preview)

  /**
   * Starts the camera stream (preview). Requires an active session. Camera permission is checked
   * first (a query, no redirect); if it isn't granted, the actual request — which redirects to the
   * Meta AI app — is deferred to [confirmCameraPermissionRedirect] so the app-switch is confirmed.
   */
  fun startStreaming() {
    if (politicaCamera.economia) {
      definirAviso(TipoAviso.BATERIA_OCULOS_BAIXA, textos.falhaCamera(FalhaCamera.BATERIA_BAIXA))
      return
    }
    // O botão do sample não é uma autorização implícita de câmera. O Aceitar abre só preview,
    // sem iniciar o pipeline; o Iniciar de Libras continua abrindo captura após consentimento.
    val token = politicaCamera.token()
    if (token == null) {
      dialogOrchestrator.pedirPreview()
      return
    }
    startStreaming(token)
  }

  private fun startStreaming(token: Long) {
    if (!politicaCamera.valida(token)) return
    if (!_uiState.value.isSessionActive) {
      wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.error_start_session_first)
      )
      return
    }
    if (stream != null || aberturaCameraJob?.isActive == true || permissaoPendenteToken != null) return
    permissaoPendenteNaUltimaTentativa = false
    _uiState.update { it.copy(isStartingStream = true) }
    aberturaCameraJob = viewModelScope.launch {
      try {
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { status ->
              if (!politicaCamera.valida(token) || !_uiState.value.isSessionActive) return@onSuccess
              if (status == PermissionStatus.Granted) {
                beginStream(token)
              } else {
                permissaoPendenteToken = token
                aguardandoPermissaoCamera.value = true
                permissaoPendenteNaUltimaTentativa = true
                // A espera pela decisão humana é ilimitada de propósito, então a causa vai para a
                // faixa agora: sem isto o atendente fica sem explicação até o stream cair.
                definirAviso(TipoAviso.CAMERA_NAO_SUBIU, textos.falhaCamera(FalhaCamera.PERMISSAO_PENDENTE))
                _uiState.update { it.copy(showCameraPermissionRedirectConfirm = true) }
              }
            }
            .onFailure { error, _ ->
              if (!politicaCamera.valida(token)) return@onFailure
              Log.e(TAG, "Failed to check camera permission: ${error.description}")
              wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
            }
      } finally {
        if (politicaCamera.valida(token)) _uiState.update { it.copy(isStartingStream = false) }
      }
    }
  }

  /** Confirmed from the permission prompt: requests camera access, then starts the stream. */
  fun confirmCameraPermissionRedirect(
      requestPermission: suspend (Permission) -> PermissionStatus,
  ) {
    // Consome o pedido original uma vez; nunca cria uma geração nova para um redirect atrasado.
    val token = permissaoPendenteToken ?: return
    permissaoPendenteToken = null
    permissaoPendenteNaUltimaTentativa = false
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
    if (!politicaCamera.valida(token) || !_uiState.value.isSessionActive || stream != null) return
    _uiState.update { it.copy(isStartingStream = true) }
    aberturaCameraJob = viewModelScope.launch {
      try {
        val status = requestPermission(Permission.CAMERA)
        if (!politicaCamera.valida(token) || !_uiState.value.isSessionActive) return@launch
        aguardandoPermissaoCamera.value = false
        if (status == PermissionStatus.Granted) {
          beginStream(token)
        } else {
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_camera_permission_denied)
          )
        }
      } finally {
        if (politicaCamera.valida(token)) {
          aguardandoPermissaoCamera.value = false
          _uiState.update { it.copy(isStartingStream = false) }
        }
      }
    }
  }

  fun cancelCameraPermissionRedirect() {
    stopStreaming()
  }

  private fun beginStream(token: Long) {
    if (!politicaCamera.valida(token) || !_uiState.value.isSessionActive) return
    val current = session ?: return
    if (stream != null) return
    current
        .addCamera(
            StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = FRAME_RATE,
                // Compressed HEVC so frames feed both the on-screen decoder and the passthrough
                // MP4 writer.
                compressVideo = true,
            )
        )
        .onSuccess { addedCamera ->
          if (!politicaCamera.valida(token) || session !== current || !_uiState.value.isSessionActive) {
            stopCamera(addedCamera)
            return@onSuccess
          }
          // Só depois da validação: não deixa serviço ligado se addCamera chegar cancelado.
          StreamingService.start(getApplication(), donoServico)
          camera = addedCamera
          val added = addedCamera.stream
          stream = added
          // Subscribe before start() so no initial transitions are missed.
          setupStreamListeners(added)
          // Collectors na Main.immediate podem receber bateria/terminal já no subscribe.
          if (!politicaCamera.valida(token) || stream !== added) {
            stopCamera(addedCamera)
            return@onSuccess
          }
          _uiState.update { it.copy(streamState = StreamState.STARTING) }
          added.start().onFailure { error, _ ->
            if (stream !== added || !politicaCamera.valida(token)) return@onFailure
            Log.e(TAG, "Failed to start stream: ${error.description}")
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
            // A failed start leaves the stream attached and the FGS running — tear both down so the
            // UI doesn't stick at STARTING, matching the addCamera() failure path below.
            onStreamTerminated(added)
          }
        }
        .onFailure { error, _ ->
          if (!politicaCamera.valida(token)) return@onFailure
          Log.e(TAG, "Failed to add camera: ${error.description}")
          wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
        }
  }

  /** Stops the camera stream but keeps the [DeviceSession] connected. */
  fun stopStreaming() {
    invalidarAberturaCamera()
    val current = camera ?: return
    _uiState.update { it.copy(streamState = StreamState.STOPPING) }
    videoJob?.cancel()
    current.stop()
    // Também fecha uma Camera parada ainda no STARTING, sem depender de uma transição do SDK.
    stream?.let { onStreamTerminated(it) }
  }

  private fun invalidarAberturaCamera() {
    politicaCamera.invalidarAbertura()
    aberturaCameraJob?.cancel()
    aberturaCameraJob = null
    permissaoPendenteToken = null
    aguardandoPermissaoCamera.value = false
    _uiState.update { it.copy(isStartingStream = false, showCameraPermissionRedirectConfirm = false) }
  }

  private fun setupStreamListeners(stream: Stream) {
    videoJob =
        viewModelScope.launch(frameDispatcher) {
          stream.videoStream.collect { handleVideoFrame(it) }
        }
    streamStateJob = viewModelScope.launch {
      // state replays its current value (STOPPED) on subscribe, and we subscribe before start().
      var hasBeenActive = false
      stream.state.collect { state ->
        if (this@CameraViewModel.stream !== stream || streamEncerrando === stream) return@collect
        _uiState.update { it.copy(streamState = state) }
        val isTerminal = state == StreamState.STOPPED || state == StreamState.CLOSED
        if (!isTerminal) {
          hasBeenActive = true
        } else if (hasBeenActive) {
          hasBeenActive = false
          onStreamTerminated(stream)
        }
      }
    }
    streamErrorJob = viewModelScope.launch {
      stream.errorStream.collect { error ->
        if (this@CameraViewModel.stream !== stream) return@collect
        Log.e(TAG, "Stream error: ${error.description}")
        val mensagem = error.getLocalizedDescription(getApplication())
        wearablesViewModel.setRecentError(mensagem)
        definirAviso(TipoAviso.ERRO_OCULOS, mensagem)
        // Libras Livre — modo economia de bateria (docs/confirmacao-e-modo-economia-plano.md §2).
        // Confirmado por inspeção do mwdat-camera-0.9.0.aar real (javap): enum, comparável com ==.
        if (error == StreamError.BATTERY_LOW) onBateriaBaixa()
      }
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    if (!videoFrame.isCompressed) return
    metricas.frameRecebido()

    val buffer = videoFrame.buffer
    val width = videoFrame.width
    val height = videoFrame.height
    val presentationTimeUs = videoFrame.presentationTimeUs

    val byteArray = ByteArray(buffer.remaining())
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    buffer.position(originalPosition)

    // Accumulate the parameter sets so a recording (or decoder) started after stream start can be
    // primed with a complete VPS+SPS+PPS set.
    csdCollector.offer(byteArray)

    // Append to the recorder (no-op unless recording); keeps writing while backgrounded.
    videoRecorder.writeCompressedFrame(
        byteArray,
        presentationTimeUs,
        width,
        height,
        videoFrame.isCodecConfig,
    )

    // Libras Livre: alimenta o pipeline de reconhecimento com o MESMO frame comprimido. Ele
    // mantém um decoder próprio (para um ImageReader) e só roda o MediaPipe enquanto captura um
    // sinal, então o custo em repouso é baixo. Independente do preview/gravação acima.
    landmarkPipeline.feedCompressedFrame(
        byteArray,
        presentationTimeUs,
        width,
        height,
        csdCollector.complete(),
    )

    // Lazily create the decoder once a Surface is available; it renders directly to it. Prime it
    // with the cached config in case the surface arrived after the config frame. Guarded so a
    // concurrent setSurface(null) can't leave a decoder bound to a released Surface.
    synchronized(decoderLock) {
      val surface = decoderSurface
      if (hevcDecoder == null && surface != null) {
        hevcDecoder =
            HevcDecoder().also { decoder ->
              decoder.start(width, height, surface)
              csdCollector.complete()?.let { decoder.decodeFrame(it, 0) }
            }
      }
      // Feed under the lock so teardown can't null the decoder between check and feed.
      hevcDecoder?.decodeFrame(byteArray, presentationTimeUs)
    }

    if (!videoFrame.isCodecConfig && !_uiState.value.hasReceivedFirstFrame) {
      _uiState.update { it.copy(hasReceivedFirstFrame = true) }
    }
  }

  private fun onStreamTerminated(terminated: Stream) {
    if (stream !== terminated || streamEncerrando === terminated) return
    streamEncerrando = terminated
    invalidarAberturaCamera()
    _uiState.update { it.copy(streamState = StreamState.STOPPING) }
    val produtor = videoJob
    produtor?.cancel()
    // Finalize an in-progress recording before releasing the foreground service / wake lock, so the
    // MP4 mux on Dispatchers.IO isn't cut off when the stream stops while backgrounded.
    // stream permanece ocupado até acabar a drenagem E a limpeza. Nenhum sucessor pode
    // reutilizar os consumidores nesse intervalo. Nunca fazemos join sob decoderLock/muxerLock.
    encerramentoStreamJob = encerramentoScope.launch(start = CoroutineStart.LAZY) {
      drenarVideoAntesDeLimpar(produtor) {
        try {
          stopVideoRecording(aguardarKeyframe = false)
        } finally {
          if (stream === terminated) clearStreamResources()
        }
      }
    }
    encerramentoStreamJob?.start()
  }

  /** Só após drenar o produtor; não chamar diretamente de callback de erro/stop do DAT. */
  private suspend fun clearStreamResources() = withContext(NonCancellable) {
    videoJob = null
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    synchronized(decoderLock) {
      aposentarDecoderPreviewLocked()
    }
    drenarDecodersPreviewParados()
    // Libras: solta o decoder/ImageReader do pipeline de reconhecimento junto com o stream. O
    // MediaPipe fica carregado para o próximo "iniciar" (docs/prontidao-demo/03 §3.1).
    landmarkPipeline.stopAndDrain()
    // streamEncerrando/stream continuam ocupados até os callbacks e reportFailures terminarem.
    csdCollector.reset()
    StreamingService.stop(getApplication(), donoServico)
    // STOPPED is restartable, so only stop() detaches the capability; without it the next
    // addCamera() fails with "a capability of this type is already active". Stopping the camera
    // cascades to its stream child.
    stopCamera(camera)
    camera = null
    stream = null
    streamEncerrando = null
    _uiState.update { it.copy(streamState = StreamState.STOPPED, hasReceivedFirstFrame = false) }
  }

  /**
   * Stops the camera by closing it. Accepting a [java.io.Closeable] parameter satisfies the
   * AutoCloseableUse detector, which skips methods that take an AutoCloseable argument — the camera
   * outlives any single `use {}` block, so it is stopped explicitly here rather than auto-closed.
   */
  private fun stopCamera(capability: java.io.Closeable?) {
    capability?.close()
  }

  // MARK: - Capture

  fun capturePhoto() {
    if (_uiState.value.isCapturingPhoto || !_uiState.value.isStreaming) return
    _uiState.update { it.copy(isCapturingPhoto = true) }
    viewModelScope.launch {
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            // Decode/rotate is CPU-bound and blocking; keep it off the main thread so capture
            // doesn't jank the UI. Resumes on main for the state update.
            val bitmap = withContext(Dispatchers.Default) { decodePhoto(photoData) }
            if (bitmap != null) {
              _uiState.update {
                it.copy(isCapturingPhoto = false, activePreview = CapturePreview.Photo(bitmap))
              }
            } else {
              _uiState.update { it.copy(isCapturingPhoto = false) }
              wearablesViewModel.setRecentError(
                  getApplication<Application>().getString(R.string.error_photo_capture_failed)
              )
            }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Failed to capture photo: ${error.description}")
            _uiState.update { it.copy(isCapturingPhoto = false) }
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          } ?: _uiState.update { it.copy(isCapturingPhoto = false) }
    }
  }

  // MARK: - Recording

  fun toggleRecording() {
    if (_uiState.value.isRecording) {
      viewModelScope.launch { stopVideoRecording() }
    } else {
      startVideoRecording()
    }
  }

  fun startVideoRecording() {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    viewModelScope.launch {
      gravacaoMutex.withLock {
        if (!_uiState.value.isStreaming || streamEncerrando != null || videoRecorder.isRecording.value) return@withLock
        // startRecording faz IO antes de retornar. Cancelar o dono não pode soltar a exclusão
        // enquanto esse IO ainda prepara um muxer que o teardown já teria fechado.
        withContext(NonCancellable) { videoRecorder.startRecording(csdCollector.complete()) }
      }
    }
  }

  suspend fun stopVideoRecording(aguardarKeyframe: Boolean = true) = gravacaoMutex.withLock {
    if (!videoRecorder.isRecording.value) return@withLock
    // The writer starts on the first keyframe. If stop lands just before that frame, wait briefly
    // so even a quick recording finalizes to a file instead of being discarded.
    var waited = 0L
    while (aguardarKeyframe && streamEncerrando == null &&
      !videoRecorder.hasStartedWriting.value && waited < KEYFRAME_WAIT_MAX_MS) {
      delay(KEYFRAME_WAIT_STEP_MS)
      waited += KEYFRAME_WAIT_STEP_MS
    }
    when (val result = withContext(NonCancellable) { videoRecorder.stopRecording() }) {
      is RecordingResult.Completed ->
          _uiState.update { it.copy(activePreview = CapturePreview.Video(result.uri)) }
      RecordingResult.NoRecording ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_too_short)
          )
      RecordingResult.Failed ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_save_failed)
          )
    }
  }

  // MARK: - Libras: sessão de diálogo (wake word)

  /**
   * Chamado pelos botões de fallback "Iniciar"/"Encerrar" da UI (ver ui/CameraScreen.kt,
   * DialogControlRow) — chama o orquestrador diretamente, sem passar pelo [wakeWordDetector], por
   * isso continua funcionando mesmo se o motor real de wake word (SpeechRecognizerWakeWordDetector)
   * estiver sem permissão, pausado ou falhando. Pede RECORD_AUDIO só no momento em que o
   * dialogState em curso está prestes a precisar do mic (④→⑤, escuta do atendente via STT) — nos
   * outros estados o evento não depende de permissão nenhuma.
   */
  fun onBotaoPrincipal(acao: AcaoBotao, requestRecordAudioPermission: suspend () -> Boolean) {
    // 4.7: botão principal e teclas de volume. Só "Ouvir resposta" precisa do microfone.
    if (acao != AcaoBotao.OUVIR) {
      dialogOrchestrator.onBotaoPrincipal(acao)
      return
    }
    viewModelScope.launch {
      if (requestRecordAudioPermission()) {
        // RECORD_AUDIO acabou de ser concedido (ou já estava) — garante que o foreground
        // service já está anunciado como tipo "microphone" antes de escutar de verdade (ver
        // StreamingService.refreshForegroundServiceType).
        StreamingService.refreshForegroundServiceType(getApplication(), donoServico)
        dialogOrchestrator.onBotaoPrincipal(acao)
      } else {
        wearablesViewModel.setRecentError(
            getApplication<Application>().getString(R.string.error_record_audio_permission_denied)
        )
      }
    }
  }

  /**
   * Pede RECORD_AUDIO uma vez, ao abrir a tela (ver ui/CameraScreen.kt, LaunchedEffect), pra
   * destravar o motor real de wake word sem esperar o primeiro toque em "Iniciar"/"Encerrar" — sem
   * a permissão, [SpeechRecognizerWakeWordDetector.start] fica mudo e só os botões funcionam.
   */
  fun enableWakeWordListening(requestRecordAudioPermission: suspend () -> Boolean) {
    viewModelScope.launch {
      if (requestRecordAudioPermission()) {
        dialogOrchestrator.resumeWakeWordDetectorIfActive()
      } else {
        Log.w(TAG, "RECORD_AUDIO negado — wake word real desativada, só os botões funcionam")
      }
    }
  }

  /**
   * Liga câmera+stream sob demanda pro DialogOrchestrator (①→②, ver docs/orquestracao-dialogo-audio-plano.md)
   * — reaproveita startSession()/startStreaming() já existentes, só espera o resultado via
  * [uiState] e a geração da política. Devolve a falha se a sessão/stream não ficarem prontos;
  * cancelamento continua sendo cancelamento de coroutine (não é convertido em falha).
   */
  private suspend fun ensureCameraActiveForLibras(): FalhaCamera? {
    val token = politicaCamera.token() ?: return falhaDaPoliticaCamera()
    var pronta = false
    try {
      if (_uiState.value.isStreaming) {
        pronta = true
        return null
      }
      if (_uiState.value.isPaused) {
        definirAviso(TipoAviso.STREAM_PAUSADO, textos.streamPausado)
        val retomou = aguardarCamera(token, DialogOrchestrator.TETO_PAUSA_MS) { it.isStreaming }
        if (!politicaCamera.valida(token)) return falhaDaPoliticaCamera()
        pronta = retomou != null
        return if (pronta) null else FalhaCamera.PAUSA_LONGA
      }
      val wearables = wearablesViewModel.uiState.value
      FalhaCamera.antesDeTentar(wearables.hasActiveDevice, wearables.isFirmwareUpdateRequired)?.let { return it }
      if (!_uiState.value.hasSession) startSession()
      val sessionReady = aguardarCamera(token, CAMERA_SESSION_READY_TIMEOUT_MS) { it.isSessionActive }
      if (!politicaCamera.valida(token)) return falhaDaPoliticaCamera()
      if (sessionReady == null) return FalhaCamera.SESSAO_SEM_RESPOSTA

      // Corrigir pode chegar enquanto o stream anterior finaliza a gravação/teardown.
      val livre = aguardarCamera(token, CAMERA_STREAM_READY_TIMEOUT_MS) {
        stream == null || (streamEncerrando == null && it.isStreaming)
      }
      if (!politicaCamera.valida(token)) return falhaDaPoliticaCamera()
      if (livre == null) return FalhaCamera.STREAM_NAO_SUBIU
      startStreaming(token)
      val streamReady = aguardarCameraSemContarPermissao(
          estados = combine(uiState, politicaCamera.geracao, aguardandoPermissaoCamera) { ui, _, humana -> ui to humana },
          timeoutTecnicoMs = CAMERA_STREAM_READY_TIMEOUT_MS,
          aguardandoPermissao = { it.second },
          concluida = { !politicaCamera.valida(token) || it.first.isStreaming },
      )
      if (!politicaCamera.valida(token)) return falhaDaPoliticaCamera()
      pronta = streamReady != null
      return FalhaCamera.depoisDeEsperar(
          sessaoPronta = true,
          streamPronto = pronta,
          permissaoPendente = _uiState.value.showCameraPermissionRedirectConfirm ||
              permissaoPendenteNaUltimaTentativa,
      )
    } finally {
      // Timeout/cancelamento também cancela permissão pendente. Uma espera antiga nunca para
      // a câmera de um novo Aceitar, nem apaga flags pertencentes à nova geração.
      if (!pronta && politicaCamera.valida(token)) stopStreaming()
    }
  }

  private fun falhaDaPoliticaCamera(): FalhaCamera = when {
    politicaCamera.economia -> FalhaCamera.BATERIA_BAIXA
    // O pedido de permissão sumiu da tela junto com a invalidação, mas a causa é ele.
    permissaoPendenteNaUltimaTentativa -> FalhaCamera.PERMISSAO_PENDENTE
    else -> FalhaCamera.STREAM_NAO_SUBIU
  }

  private suspend fun aguardarCamera(
      token: Long,
      timeoutMs: Long,
      pronta: (CameraUiState) -> Boolean,
  ): CameraUiState? = withTimeoutOrNull(timeoutMs) {
    combine(uiState, politicaCamera.geracao) { ui, _ -> ui }
        .first { !politicaCamera.valida(token) || pronta(it) }
  }

  /**
   * Desliga o stream (câmera+display) pro DialogOrchestrator (②→③) — mantém a [DeviceSession]
   * conectada aos óculos pra a próxima "Libras Livre, iniciar" não pagar o custo de reconexão
   * inteiro, só o de religar o stream.
   */
  private fun deactivateCameraForLibras() {
    stopStreaming()
  }

  // MARK: - Dismissers

  fun dismissCapturePreview() {
    val preview = _uiState.value.activePreview
    _uiState.update { it.copy(activePreview = null) }
    if (preview is CapturePreview.Video) {
      // The clip lives in the cache dir, exposed as a FileProvider content URI; delete through the
      // resolver so it resolves back to the real cache file (the URI's path is the provider
      // mapping,
      // not a filesystem path). Photos are held in memory — nothing on disk to clean up.
      viewModelScope.launch(Dispatchers.IO) {
        runCatching {
          getApplication<Application>().contentResolver.delete(preview.uri, null, null)
        }
            .onFailure { Log.w(TAG, "Failed to delete temp recording", it) }
      }
    }
  }

  // MARK: - Photo decoding

  private fun decodePhoto(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> decodeWithOrientation(photo.data)
      }

  // The glasses store orientation in an EXIF tag that BitmapFactory/ImageDecoder don't apply for
  // HEIC, so read TAG_ORIENTATION and rotate — otherwise the preview and shared image are sideways.
  private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
    val buffer = data.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
      bitmap?.recycle()
      Log.e(TAG, "Failed to decode captured photo")
      return null
    }

    val matrix = exifOrientationMatrix(bytes)
    if (matrix.isIdentity) return bitmap

    // Rotating allocates a second full-size bitmap; recycle the source, and fall back to the
    // unrotated image if the device is too low on memory to make the copy.
    return try {
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        bitmap.recycle()
      }
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to rotate captured photo", e)
      bitmap
    }
  }

  private fun exifOrientationMatrix(bytes: ByteArray): Matrix {
    val orientation =
        try {
          ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
          }
        } catch (e: IOException) {
          Log.w(TAG, "Failed to read EXIF orientation", e)
          ExifInterface.ORIENTATION_NORMAL
        }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return matrix
  }

  override fun onCleared() {
    super.onCleared()
    politicaCamera.revogarConsentimento()
    stopStreaming()
    session?.stop()
    cleanupSession()
    encerramentoScope.launch {
      try {
        encerramentoStreamJob?.join()
        drenarVideoAntesDeLimpar(videoJob) {
          clearStreamResources()
          gravacaoMutex.withLock { videoRecorder.close() }
          // dispose drena também decoders aposentados e seus reportFailures. Não segurar lock:
          // onFrameProcessado/onEvento ainda podem enfileirar no GravadorSessao até terminar.
          withContext(Dispatchers.Default) { landmarkPipeline.dispose() }
          gravador.encerrar()
        }
      } finally {
        encerramentoScope.cancel()
      }
    }
    avatarPlayer.release()
    glossContextualizer.close()
    speaker.shutdown()
    sttEngine.encerrar()
    attendantAudioCapture.cleanup()
    audioSessionManager.releaseListening()
    wakeWordDetector.stop()
    // Um ViewModel novo pode ter registrado o seu antes deste ir embora.
    if (AcoesDeDemo.simularQuedaDoAvatar === simularQuedaDoAvatar) AcoesDeDemo.simularQuedaDoAvatar = null
    getApplication<Application>().unregisterComponentCallbacks(memoriaCallbacks)
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST")
        return CameraViewModel(application, wearablesViewModel) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.TtsEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.Contextualizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.GlossContextualizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.LexicoGlosas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.MODELO_CONTEXTUALIZACAO_ATIVO
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.FrameProcessado
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ValoresDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DecisaoNaConversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogOrchestrator
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.CarregadorClassificador
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.Classificacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoClassificador
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.TfliteSignClassifier
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Etapa 3: UI e componentes do app, sem injetar glosas ou substituir o classificador.
 * Os observadores abaixo delegam integralmente aos callbacks/motores já criados pelo VM.
 * Reflexão fica somente em androidTest: mudanças nos campos privados devem falhar, não pular.
 * Não usa gravador CSV (landmarks), não liga modelo neural de contextualização nem muda limiares.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class AtendimentoClassificadorPrivadoTest {
  companion object {
    private const val UI_MS = 20_000L
    private const val AQUECIMENTO_MS = 180_000L
    private const val DECISAO_MS = 120_000L
    private const val OBSERVACAO_MS = 1_500L
  }

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext.applicationContext
  // Não lança MainActivity antes do opt-in, do relatório fresco e do BuildConfig obrigatório.
  @get:Rule val compose = createEmptyComposeRule()
  private lateinit var vm: CameraViewModel
  private lateinit var observador: Observador

  @Test fun recusarConsentimentoNaoAbreStreamComClassificadorReal() = executar("recusa") {
    pedirConsentimento()
    observarSemCaptura()
    compose.onNodeWithTag("consentimento_recusar_button").performClick()
    esperarEstado(DialogState.AGUARDANDO_SINAL)
    compose.onNodeWithText(context.getString(R.string.aviso_consentimento_recusado), substring = true)
        .assertExists()
    observarSemCaptura()
    assertTrue(vm.uiState.value.conversa.turnos.isEmpty())
    assertTrue(observador.contextos.isEmpty())
    assertTrue(observador.falas.isEmpty())
    it.put("ramo", "CONSENTIMENTO_RECUSADO_SEM_STREAM")
  }

  @Test fun aceitarVideoRealObservaDecisaoECancelaSemConfirmarFrase() = executar("aceite") { relatorio ->
    pedirConsentimento()
    observarSemCaptura()
    observador.aceiteSolicitado.set(true)
    compose.onNodeWithTag("consentimento_aceitar_button").performClick()
    compose.waitUntil(UI_MS) {
      vm.uiState.value.showCameraPermissionRedirectConfirm ||
          vm.uiState.value.dialogState == DialogState.CAPTURANDO_SINAIS
    }
    if (vm.uiState.value.showCameraPermissionRedirectConfirm) {
      compose.onNodeWithText(context.getString(R.string.camera_permission_continue)).performClick()
    }
    esperarEstado(DialogState.CAPTURANDO_SINAIS)
    // Não encerra artificialmente um segmento, não repete até acertar. Aceita a primeira decisão
    // natural do orquestrador, guardada nos eventos mesmo se REPITA já tiver aberto outro turno.
    compose.waitUntil(DECISAO_MS) {
      observador.eventos.any { it.nome == "decisao" } &&
          vm.uiState.value.conversa.turnos.any { it.decisao != null }
    }
    val evento = observador.eventos.first { it.nome == "decisao" }
    relatorio.put("primeira_decisao", evento.detalhe)
    val decisao = vm.uiState.value.conversa.turnos.firstOrNull { it.decisao != null }
        ?: error("Evento de decisão sem turno avaliado no CameraViewModel")
    relatorio.put("decisao_painel", decisao.decisao!!.name)
    // FALADA é o nome legado de Falar no painel, NÃO comprova confirmação nem áudio.
    assertTrue(evento.detalhe.contains("decisao=${decisao.decisao}"))
    when (decisao.decisao) {
      DecisaoNaConversa.FALADA -> {
        relatorio.put("ramo", "FALAR_AGUARDANDO_CONFIRMACAO")
        esperarEstado(DialogState.CONFIRMANDO_RECONHECIMENTO)
        compose.onNodeWithText(context.getString(R.string.avatar_caption_label_confirmacao)).assertExists()
        compose.onNodeWithTag("botao_principal_avatar").assertIsEnabled()
          .assertTextContains(context.getString(R.string.avatar_confirmacao_confirmar))
        compose.onNodeWithTag("avatar_corrigir_button").assertIsEnabled()
        esperarCameraParada()
        observarDurante {
          assertEquals(DialogState.CONFIRMANDO_RECONHECIMENTO, vm.uiState.value.dialogState)
          assertTrue("Frase enviada ao TTS sem confirmação explícita", observador.falas.isEmpty())
          assertTrue(vm.uiState.value.conversa.turnos.all { it.falado == null })
          assertCameraParada()
        }
        assertEquals(1, observador.contextos.size)
        val contexto = observador.contextos.single()
        assertEquals(decisao.sinais.filterNot { it.foraDoLexico }.map { it.glosa }, contexto.glosas)
        assertTrue(contexto.resultado.texto.isNotBlank())
        assertEquals(contexto.resultado.texto, vm.uiState.value.avatarLegenda)
        // avatar_legenda é uma Column: o texto é um descendente na árvore não mesclada.
        // O label de confirmação e o valor no VM, sozinhos, não provam a frase na UI.
        compose.onNodeWithTag("avatar_legenda", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(
          hasText(contexto.resultado.texto, substring = false)
            .and(hasAnyAncestor(hasTestTag("avatar_legenda"))),
          useUnmergedTree = true,
        ).assertIsDisplayed().assertTextEquals(contexto.resultado.texto)
        relatorio.put("confirmacao_exibida", true)
        // Fechar a sobreposição NÃO confirma/cancela. Depois clica Cancelar na tela principal.
        compose.onNodeWithTag("close_avatar_button").performClick()
        compose.onNodeWithTag("cancelar_atendimento_button").performClick()
        esperarEstado(DialogState.AGUARDANDO_SINAL)
        esperarCameraParada()
        observarAposCancelar(relatorio) {
          assertTrue("Cancelar disparou TTS", observador.falas.isEmpty())
        }
        relatorio.put("ramo", "FALAR_CONFIRMACAO_EXIBIDA_CANCELADA_SEM_TTS")
      }
      DecisaoNaConversa.REPITA, DecisaoNaConversa.DESISTIU -> {
        relatorio.put("ramo", "AVALIADO_REJEITADO_${decisao.decisao}")
        // O app pode falar AVISO_REPITA/AVISO_DESISTIR. Não fingir que houve confirmação,
        // nem substituir esse TTS por fake/silêncio para obter um resultado favorável.
        compose.onNodeWithTag("cancelar_atendimento_button").performClick()
        esperarEstado(DialogState.AGUARDANDO_SINAL)
        esperarCameraParada()
        assertTrue("Rejeição chamou contextualizador", observador.contextos.isEmpty())
        assertTrue(observador.eventos.none { it.nome == "contextualizacao_solicitada" })
        assertTrue(observador.falas.all {
          it == DialogOrchestrator.AVISO_REPITA || it == DialogOrchestrator.AVISO_DESISTIR
        })
        val chamadas = observador.falas.size
        observarAposCancelar(relatorio) {
          assertEquals("TTS novo após cancelar", chamadas, observador.falas.size)
        }
      }
      else -> error("Nenhuma decisão útil de classificação real: ${decisao.decisao}; $evento")
    }
    assertTrue("Nenhum frame processado", observador.frames.get() > 0)
    assertTrue("Nenhuma pose", observador.poses.get() > 0)
    assertTrue("Nenhuma mão", observador.maos.get() > 0)
    assertTrue("Vídeo não produziu segmento", observador.eventos.any { it.nome == "segmento" })
    assertTrue("Não chegou ao classificador real", observador.classificacoes.isNotEmpty())
    assertTrue("Turno avaliado sem classificações", decisao.sinais.isNotEmpty())
    assertEquals("Falha técnica não é rejeição válida", 0, observador.falhas.get())
    assertTrue(observador.classificacoes.all {
      it.glosa.isNotBlank() && it.confianca.isFinite() && it.confianca in 0f..1f &&
          it.margem.isFinite() && it.margem in 0f..1f
    })
    assertTrue(vm.uiState.value.conversa.turnos.all { it.falado == null && it.resposta == null })
  }

  private fun executar(caso: String, corpo: (JSONObject) -> Unit) {
    assumeTrue("Opt-in atendimentoClassificadorPrivado=true não solicitado",
        InstrumentationRegistry.getArguments().getString("atendimentoClassificadorPrivado") == "true")
    val destino = File(context.filesDir, "atendimento-classificador-privado/$caso.json")
    check(destino.parentFile!!.isDirectory || destino.parentFile!!.mkdirs())
    val relatorio = JSONObject().put("schema", 3).put("execucao", UUID.randomUUID().toString())
        .put("inicio_epoch_ms", System.currentTimeMillis()).put("caso", caso)
        .put("relatorio_caminho", destino.absolutePath).put("status", "EM_EXECUCAO")
        .put("ramo", "PRE_CONDICOES").put("confirmacao_exibida", false)
        .put("confirmacao_explicita_executada", false).put("audio_real_verificado", false)
        .put("avaliacao_linguistica", false).put("classificador_privado_obrigatorio", BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO)
        .put("identidade_sha256_build", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
        .put("apk_caminho", context.applicationInfo.sourceDir)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("sdk", Build.VERSION.SDK_INT)
        .put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        .put("contextualizador_neural_ativo", MODELO_CONTEXTUALIZACAO_ATIVO)
        .put("limites_ms", JSONObject().put("ui", UI_MS).put("aquecimento", AQUECIMENTO_MS)
            .put("decisao", DECISAO_MS).put("observacao", OBSERVACAO_MS))
    // Só invalida após entrar no método e passar o assume. Build/instalação já ocorreram.
    destino.writeText(relatorio.toString(2)) // Antes de validar BuildConfig/assets e lançar Activity.
    var scenario: ActivityScenario<MainActivity>? = null
    var anterior: ValoresDemo? = null
    var video: File? = null
    var kitHabilitado = false
    var erro: Throwable? = null
    val errosLimpeza = mutableListOf<Throwable>()
    try {
      assertTrue("Opt-in exige APK debug privado; não aceitar SIMULADO/RECUSADO",
          BuildConfig.DEBUG && BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO)
      val identidade = validarAssets(relatorio)
      val configs = ConfiguracoesDemo.de(context)
      val configuracaoOriginal = configs.valores.value
      anterior = configuracaoOriginal
      // Só isola entrada de voz externa e desliga gravação sensível; não reseta preferências.
      configs.atualizar { it.copy(comandoDeVoz = false, gravadorSessao = false) }
      relatorio.put("configuracao_anterior", configuracao(configuracaoOriginal))
          .put("configuracao_efetiva", configuracao(configs.valores.value))
      concederPermissoes()
      scenario = ActivityScenario.launch(MainActivity::class.java)
      val kit = MockDeviceKit.getInstance(context)
      kit.enable(MockDeviceKitConfig(initialPermissionsGranted = true))
      kitHabilitado = true
      kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
      val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
      oculos.powerOn()
      oculos.don()
      oculos.unfold()
      // Vídeo já existente no APK de TESTES; os modelos são sempre do APK ALVO.
      video = File(context.cacheDir, "atendimento-${relatorio.getString("execucao")}-sinais.mp4")
      val bytes = instrumentation.context.assets.open("sinais.mp4").use { it.readBytes() }
      video.writeBytes(bytes)
      relatorio.put("video_asset", "androidTest/assets/sinais.mp4")
          .put("video_caminho", video.canonicalPath).put("video_sha256", CarregadorClassificador.sha256(bytes))
      oculos.services.camera.setCameraFeed(Uri.fromFile(video))
      compose.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), UI_MS)
      scenario.onActivity { activity ->
        // Recupera SOMENTE o VM criado por CameraScreen, nunca um segundo pipeline de teste.
        vm = ViewModelProvider(activity, object : ViewModelProvider.Factory {
          override fun <T : ViewModel> create(modelClass: Class<T>): T =
              error("CameraScreen não criou ${modelClass.name} no owner esperado")
        })[CameraViewModel::class.java]
      }
      val diagnostico = checkNotNull(vm.uiState.value.classificador)
      relatorio.put("diagnostico", diagnostico.detalhes(configs.valores.value.limiarConfianca))
      assertEquals(diagnostico.motivo, ModoClassificador.REAL_EXPERIMENTAL, diagnostico.modo)
      val id = checkNotNull(diagnostico.identidade) { "REAL_EXPERIMENTAL sem identidade" }
      assertEquals(identidade.getString("modelo_sha256"), id.modeloSha256)
      assertEquals(identidade.getString("sidecar_sha256"), id.sidecarSha256)
      assertEquals(identidade.getString("checkpoint_sha256"), id.checkpointSha256)
      scenario.onActivity { observador = Observador(vm) }
      if (compose.onAllNodesWithTag("start_session_button").fetchSemanticsNodes().isEmpty()) {
        compose.onNodeWithTag("mais_controles").performClick()
      }
      compose.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), UI_MS)
      compose.onNodeWithTag("start_session_button").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), AQUECIMENTO_MS)
      corpo(relatorio)
      assertEquals(configuracaoOriginal.copy(comandoDeVoz = false, gravadorSessao = false), configs.valores.value)
      assertFalse("Houve stream/captura antes do clique Aceitar", observador.capturaAntesDoAceite.get())
      assertNull(vm.uiState.value.libras.error)
      observador.assertSemFalhasTecnicas()
    } catch (e: Throwable) {
      erro = e
    } finally {
      fun limpar(acao: () -> Unit) { runCatching(acao).onFailure { errosLimpeza.add(it) } }
      relatorio.put("status", "ENCERRANDO")
      limpar { destino.writeText(relatorio.toString(2)) }
      if (::vm.isInitialized) {
        limpar { instrumentation.runOnMainSync { vm.cancelarAtendimento() } }
        limpar { esperarCameraParada() }
      }
      limpar { scenario?.close() }
      if (kitHabilitado) limpar { MockDeviceKit.getInstance(context).disable() }
      if (::observador.isInitialized) {
        limpar { instrumentation.runOnMainSync { observador.fechar() } }
        // Também reprova falhas técnicas observadas durante a limpeza; não só no corpo.
        limpar { observador.assertSemFalhasTecnicas() }
        limpar { observador.relatar(relatorio) }
      }
      anterior?.let { salvo -> limpar { ConfiguracoesDemo.de(context).atualizar { salvo } } }
      video?.let { arquivo -> limpar { check(!arquivo.exists() || arquivo.delete()) } }
      if (erro == null && errosLimpeza.isNotEmpty()) erro = errosLimpeza.first()
      errosLimpeza.filter { it !== erro }.forEach { erro?.addSuppressed(it) }
      relatorio.put("fim_epoch_ms", System.currentTimeMillis())
          .put("status", if (erro == null) "APROVADO_NO_RAMO_OBSERVADO" else "FALHOU")
          .put("erro", erro?.toString() ?: JSONObject.NULL)
          .put("erros_limpeza", JSONArray(errosLimpeza.map { it.toString() }))
      // Erro de persistência também falha; o arquivo anterior fica ENCERRANDO, nunca sucesso velho.
      try { destino.writeText(relatorio.toString(2)) }
      catch (e: Throwable) { if (erro == null) erro = e else erro!!.addSuppressed(e) }
    }
    erro?.let { throw it }
  }

  private fun validarAssets(relatorio: JSONObject): JSONObject {
    fun ler(nome: String) = context.assets.open(nome).use { it.readBytes() }
    val idBytes = ler(CarregadorClassificador.IDENTIDADE)
    val id = JSONObject(String(idBytes, Charsets.UTF_8))
    relatorio.put("identidade_asset", CarregadorClassificador.IDENTIDADE).put("identidade", id)
    assertEquals(BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256, CarregadorClassificador.sha256(idBytes))
    for ((nome, chave) in listOf(CarregadorClassificador.MODELO to "modelo_sha256",
        CarregadorClassificador.SIDECAR to "sidecar_sha256")) {
      val hash = CarregadorClassificador.sha256(ler(nome))
      relatorio.put(chave, hash).put("${chave}_asset", nome)
      assertEquals(id.getString(chave), hash)
    }
    assertTrue("Léxico do app ausente/vazio", LexicoGlosas.fromAssets(context).glosas.isNotEmpty())
    return id
  }

  private fun configuracao(v: ValoresDemo) = JSONObject()
      .put("valores_completos", v.toString()).put("segmentacao", v.segmentacao.toString())
      .put("limiar_confianca", v.limiarConfianca).put("teto_captura_ms", v.tetoCapturaMs)
      .put("modo_placeholder_ignorado_pelo_real", v.modoPlaceholder.name)
      .put("comando_de_voz", v.comandoDeVoz).put("gravador_sessao", v.gravadorSessao)

  private fun concederPermissoes() {
    listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_CONNECT",
        "android.permission.CAMERA", "android.permission.INTERNET", "android.permission.RECORD_AUDIO")
        .forEach { permissao ->
          android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
              .executeShellCommand("pm grant ${context.packageName} $permissao")).use { it.readBytes() }
        }
  }

  private fun pedirConsentimento() {
    assertCameraParada()
    compose.onNodeWithTag("botao_principal").performClick()
    esperarEstado(DialogState.PEDINDO_CONSENTIMENTO)
    compose.onNodeWithTag("consentimento_aceitar_button").assertIsEnabled()
    compose.onNodeWithTag("consentimento_recusar_button").assertIsEnabled()
  }

  private fun esperarEstado(estado: DialogState) = compose.waitUntil(UI_MS) { vm.uiState.value.dialogState == estado }

  private fun cameraParada(): Boolean {
    val ui = vm.uiState.value
    return !ui.hasStream && !ui.isStartingStream && !ui.hasReceivedFirstFrame &&
        lerCampo<Any?>(vm, "stream") == null && lerCampo<Any?>(vm, "camera") == null &&
        lerCampo<Any?>(vm, "streamEncerrando") == null
  }

  private fun assertCameraParada() = instrumentation.runOnMainSync {
    assertTrue("Stream/câmera ainda presentes ou drenando", cameraParada())
  }

  private fun esperarCameraParada() = compose.waitUntil(UI_MS) {
    var parada = false
    instrumentation.runOnMainSync { parada = cameraParada() }
    parada
  }

  private fun observarDurante(verificar: () -> Unit) {
    val fim = SystemClock.elapsedRealtime() + OBSERVACAO_MS
    do { verificar(); SystemClock.sleep(50) } while (SystemClock.elapsedRealtime() < fim)
  }

  private fun observarSemCaptura() = observarDurante {
    assertCameraParada()
    assertEquals(0, observador.frames.get())
    assertTrue(observador.classificacoes.isEmpty())
    assertFalse(observador.capturaAntesDoAceite.get())
  }

  private fun observarAposCancelar(relatorio: JSONObject, verificar: () -> Unit) {
    val janela = JSONObject().put("inicio_elapsed_ms", SystemClock.elapsedRealtime())
        .put("concluida", false)
    relatorio.put("observacao_pos_cancelar", janela)
    // Executada ANTES do segundo cancelamento defensivo do finally: ele não pode mascarar
    // uma retomada indevida provocada pelo primeiro clique na UI.
    observarDurante {
      instrumentation.runOnMainSync {
        assertEquals(DialogState.AGUARDANDO_SINAL, vm.uiState.value.dialogState)
        assertTrue("Câmera retomou após Cancelar", cameraParada())
      }
      verificar()
    }
    janela.put("fim_elapsed_ms", SystemClock.elapsedRealtime()).put("concluida", true)
  }

  private data class EstadoTecnico(
      val dialogo: DialogState,
      val stream: StreamState,
      val cameraParada: Boolean,
      val coletando: Boolean,
      val classificando: Boolean,
      val decoderAusente: Boolean,
      val snapshotEstavel: Boolean,
  ) {
    fun json() = JSONObject().put("dialogo", dialogo.name).put("stream", stream.name)
        .put("camera_parada", cameraParada).put("coletando", coletando)
        .put("classificando", classificando).put("decoder_ausente", decoderAusente)
        .put("snapshot_estavel", snapshotEstavel)
  }

  private data class Evento(
      val nome: String,
      val detalhe: String,
      val ms: Long = SystemClock.elapsedRealtime(),
      val estadoTecnico: EstadoTecnico? = null,
  ) {
    val falhaTecnica: Boolean get() = nome.startsWith("falha_")
  }
  private data class Contexto(val glosas: List<String>, val resultado: Contextualizacao)

  private class Observador(private val vm: CameraViewModel) {
    val aceiteSolicitado = AtomicBoolean()
    val capturaAntesDoAceite = AtomicBoolean()
    val frames = AtomicInteger()
    val poses = AtomicInteger()
    val maos = AtomicInteger()
    val falhas = AtomicInteger()
    val classificacoes = ConcurrentLinkedQueue<Classificacao>()
    val eventos = ConcurrentLinkedQueue<Evento>()
    val contextos = ConcurrentLinkedQueue<Contexto>()
    val falas = ConcurrentLinkedQueue<String>()
    private val restaurar = mutableListOf<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
      try {
        val pipeline = lerCampo<Any>(vm, "landmarkPipeline")
        val dialogo = lerCampo<Any>(vm, "dialogOrchestrator")
        val classificador = lerCampo<Any>(pipeline, "classifier")
        assertTrue("Pipeline não usa o TFLite real do app", classificador is TfliteSignClassifier)
        eventos.add(Evento("classificador_pipeline", classificador.javaClass.name))
        for (dono in listOf(pipeline, dialogo)) {
          envolver<(String, String) -> Unit>(dono, "onEvento") { original ->
            { nome, detalhe -> registrarEvento(nome, detalhe, pipeline); original(nome, detalhe) }
          }
        }
        envolver<((FrameProcessado) -> Unit)?>(pipeline, "onFrameProcessado") { original ->
          { f ->
            frames.incrementAndGet()
            if (f.pose) poses.incrementAndGet()
            if (f.maoEsq || f.maoDir) maos.incrementAndGet()
            if (!aceiteSolicitado.get()) capturaAntesDoAceite.set(true)
            original?.invoke(f) // Não retém pontos/landmarks nem serializa frames.
          }
        }
        envolver<(Classificacao) -> Unit>(pipeline, "onRecognized") { original ->
          { c -> classificacoes.add(c); original(c) }
        }
        envolver<() -> Unit>(pipeline, "onRecognitionFailed") { original ->
          { falhas.incrementAndGet(); original() }
        }
        envolver<GlossContextualizer>(dialogo, "contextualizer") { original ->
          assertSame("Contextualizador não é o padrão criado pelo VM",
              lerCampo<GlossContextualizer>(vm, "glossContextualizer"), original)
          eventos.add(Evento("contextualizador_padrao", original.javaClass.name))
          object : GlossContextualizer by original {
            override suspend fun contextualize(glosas: List<String>): Contextualizacao {
              eventos.add(Evento("contextualizacao_solicitada", "glosas=${glosas.size}"))
              val resultado = original.contextualize(glosas)
              contextos.add(Contexto(glosas.toList(), resultado))
              return resultado
            }
          }
        }
        envolver<TtsEngine>(dialogo, "speaker") { original ->
          assertSame("TTS não é a cadeia real criada pelo VM", lerCampo<TtsEngine>(vm, "vozEmCadeia"), original)
          eventos.add(Evento("tts_delegado", original.javaClass.name))
          object : TtsEngine by original {
            override suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit): Boolean {
              falas.add(text) // Conta chamadas, NÃO comprova saída acústica nem sucesso do motor.
              return original.speakAndAwait(text, onInicioAudio)
            }
          }
        }
        scope.launch {
          var anterior: String? = null
          vm.uiState.collect { ui ->
            if (!aceiteSolicitado.get() && (ui.hasStream || ui.isStartingStream ||
                ui.hasReceivedFirstFrame || ui.dialogState == DialogState.CAPTURANDO_SINAIS)) {
              capturaAntesDoAceite.set(true)
            }
            val atual = "dialogo=${ui.dialogState},stream=${ui.streamState},abrindo=${ui.isStartingStream}"
            if (atual != anterior) { eventos.add(Evento("estado_observado", atual)); anterior = atual }
          }
        }
      } catch (e: Throwable) { fechar(); throw e }
    }

    private fun registrarEvento(nome: String, detalhe: String, pipeline: Any) {
      val ms = SystemClock.elapsedRealtime()
      if (!nome.startsWith("falha_")) {
        eventos.add(Evento(nome, detalhe, ms))
        return
      }
      // Snapshot NO callback, nunca inferido depois pelo estado final ou só por "após decisão".
      // Não aguarda a main: ela pode estar encerrando o codec. É apenas diagnóstico;
      // nenhuma assinatura de erro ou estado de encerramento isenta uma falha técnica.
      val ui = vm.uiState.value
      val estado = EstadoTecnico(
          dialogo = ui.dialogState,
          stream = ui.streamState,
          cameraParada = ui.streamState == StreamState.STOPPED && !ui.hasStream &&
              !ui.isStartingStream && !ui.hasReceivedFirstFrame &&
              lerCampo<Any?>(vm, "stream") == null && lerCampo<Any?>(vm, "camera") == null &&
              lerCampo<Any?>(vm, "streamEncerrando") == null,
          coletando = ui.libras.isCollecting,
          classificando = ui.libras.isClassifying,
          decoderAusente = lerCampo<Any?>(pipeline, "decoder") == null,
          snapshotEstavel = vm.uiState.value === ui,
      )
          eventos.add(Evento(nome, detalhe, ms, estadoTecnico = estado))
    }

    fun assertSemFalhasTecnicas() {
      val tecnicas = eventos.filter { it.falhaTecnica }
      assertTrue("Falha técnica na pipeline: ${tecnicas.joinToString()}", tecnicas.isEmpty())
      assertEquals("Falha no callback de classificação", 0, falhas.get())
    }

    private fun <T> envolver(dono: Any, nome: String, criar: (T) -> T) {
      val campo = dono.javaClass.getDeclaredField(nome).apply { isAccessible = true }
      @Suppress("UNCHECKED_CAST") val original = campo.get(dono) as T
      val observado = criar(original)
      campo.set(dono, observado)
      restaurar.add { campo.set(dono, original) }
      check(campo.get(dono) === observado) { "Observador não instalado: $nome" }
    }

    fun fechar() {
      scope.cancel()
      restaurar.asReversed().forEach { it() }
      restaurar.clear()
    }

    fun relatar(r: JSONObject) {
      r.put("frames_processados", frames.get()).put("frames_pose", poses.get()).put("frames_maos", maos.get())
          .put("captura_antes_do_aceite", capturaAntesDoAceite.get()).put("falhas_classificacao", falhas.get())
          .put("falhas_tecnicas", eventos.count { it.falhaTecnica })
          .put("classificacoes", JSONArray(classificacoes.map {
            JSONObject().put("glosa", it.glosa)
                .put("confianca", if (it.confianca.isFinite()) it.confianca else JSONObject.NULL)
                .put("margem", if (it.margem.isFinite()) it.margem else JSONObject.NULL)
          }))
          .put("eventos", JSONArray(eventos.map {
            JSONObject().put("nome", it.nome).put("detalhe", it.detalhe).put("elapsed_ms", it.ms)
                .put("estado_no_evento", it.estadoTecnico?.json() ?: JSONObject.NULL)
                .put("falha_tecnica", it.falhaTecnica)
          }))
          .put("contextualizacoes", JSONArray(contextos.map {
            JSONObject().put("glosas", JSONArray(it.glosas)).put("texto", it.resultado.texto)
                .put("origem", it.resultado.origem.name)
          }))
          .put("chamadas_tts_dialogo", JSONArray(falas.toList()))
    }
  }
}

@Suppress("UNCHECKED_CAST")
private fun <T> lerCampo(dono: Any, nome: String): T =
    dono.javaClass.getDeclaredField(nome).apply { isAccessible = true }.get(dono) as T
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.TtsEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.Contextualizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.GlossContextualizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.FrameProcessado
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DecisaoNaConversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogOrchestrator
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.*
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.TensorFlowLite

/** MainActivity + DAT mock/vídeo + decoder/MediaPipe/segmentador/pipeline/orquestrador reais.
 * NÃO é stub de CapturaDialogo; não injeta segmento, resultado ou falha no orquestrador.
 * Só o pacote do classificador é sintético inválido. Observadores delegam sem mudar resultados.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class RecusadoVideoOrquestradorTest {
  @get:Rule val compose = createEmptyComposeRule()
  private val context get() = Etapa4Suporte.context
  private val instrumentation get() = Etapa4Suporte.instrumentation

  @Test fun segmentoRealRecusadoNaoGeraGlosaConfirmacaoOuTraducao() {
    val execucao = Etapa4Suporte.optIn("etapa4RecusadoVideo")
    val destino = File(context.filesDir, "etapa4-recusado/$execucao.json")
    check(destino.parentFile!!.isDirectory || destino.parentFile!!.mkdirs())
    check(destino.createNewFile()) { "Relatório já existe: não aceitar evidência antiga" }
    val relatorio = JSONObject().put("execucao", execucao).put("status", "EM_EXECUCAO")
        .put("fase", "segmentoRealRecusadoNaoGeraGlosaConfirmacaoOuTraducao")
        .put("pipeline", "MAIN_ACTIVITY_VIDEO_REAL_SEM_STUB")
        .put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
        .put("inicio_epoch_ms", System.currentTimeMillis()).put("audio_acustico_verificado", false)
    destino.writeText(relatorio.toString(2))
    var scenario: ActivityScenario<MainActivity>? = null
    var vm: CameraViewModel? = null
    var observador: Observador? = null
    var barreira: BarreiraEncerramentoEtapa4? = null
    var snapshotPersistido = false
    var video: File? = null
    var kitAtivo = false
    var erro: Throwable? = null
    try {
      val id = Etapa4Suporte.identidade()
      assertEquals("etapa4-recusado-sintetico-v1", id.getString("experimento"))
      val modelo = context.assets.open(CarregadorClassificador.MODELO).use { it.readBytes() }
      assertArrayEquals(byteArrayOf(-1, -1, -1, 0x7f, 0x54, 0x46, 0x4c, 0x33), modelo)
      val sidecar = SidecarClassificador.ler(context.assets.open(CarregadorClassificador.SIDECAR)
          .bufferedReader().use { it.readText() })
      assertEquals(20, sidecar.rotulos.size)
      assertTrue(ValidacaoClassificador.motivosDeRecusa(sidecar,
          InterfaceModelo(id.getString("modelo_sha256"), listOf(1, 96, 57, 3), "float32", 20)).isEmpty())
      // Recusa por biblioteca/ABI ausente não é a recusa do FlatBuffer que queremos testar.
      relatorio.put("litert_runtime", TensorFlowLite.runtimeVersion().also { assertTrue(it.isNotBlank()) })
      relatorio.put("identidade", id)
      val configs = ConfiguracoesDemo.de(context)
      val original = configs.valores.value // Apenas asserção durante o teste; recuperação usa o journal.
      EstadoRecusadoEtapa4.salvarAntesDeAlterar(execucao)
      snapshotPersistido = true
      configs.atualizar { it.copy(comandoDeVoz = false, gravadorSessao = false) }
      relatorio.put("configuracao_original", original.toString())
      Etapa4Suporte.permissoes()
      kitAtivo = true
      val oculos = Etapa4Suporte.prepararMock()
      val bytes = instrumentation.context.assets.open("sinais.mp4").use { it.readBytes() }
      val arquivo = File(context.cacheDir, "etapa4-$execucao-sinais.mp4")
      check(arquivo.createNewFile())
      video = arquivo
      arquivo.writeBytes(bytes)
      relatorio.put("video_sha256", CarregadorClassificador.sha256(bytes))
      oculos.services.camera.setCameraFeed(Uri.fromFile(arquivo))
      scenario = ActivityScenario.launch(MainActivity::class.java)
      compose.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), 20_000L)
      lateinit var atual: CameraViewModel
      scenario.onActivity {
        atual = Etapa4Suporte.vm(it)
        barreira = BarreiraEncerramentoEtapa4(atual)
      }
      vm = atual
      val diagnostico = checkNotNull(atual.uiState.value.classificador)
      assertEquals(diagnostico.motivo, ModoClassificador.RECUSADO, diagnostico.modo)
      val motivo = checkNotNull(diagnostico.motivo)
      assertTrue(motivo, motivo.startsWith(ModeloRecusado.PREFIXO))
      assertTrue("Recusa não identifica grafo inválido: $motivo",
          Regex("flatbuffer|not a valid.*model|invalid.*model|model.*invalid|model identifier",
              RegexOption.IGNORE_CASE).containsMatchIn(motivo))
      relatorio.put("motivo_recusa", motivo)
      lateinit var obs: Observador
      scenario.onActivity { obs = Observador(atual, motivo) }
      observador = obs
      if (compose.onAllNodesWithTag("start_session_button").fetchSemanticsNodes().isEmpty()) {
        compose.onNodeWithTag("mais_controles").performClick()
      }
      compose.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), 20_000L)
      compose.onNodeWithTag("start_session_button").performClick()
      compose.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), 180_000L)
      compose.onNodeWithText("RECUSADO", substring = true).assertIsDisplayed()
      compose.onNodeWithTag("botao_principal").performClick()
      compose.waitUntil(20_000L) { atual.uiState.value.dialogState == DialogState.PEDINDO_CONSENTIMENTO }
      assertEquals(0, obs.tentativas.get())
      compose.onNodeWithTag("consentimento_aceitar_button").performClick()
      compose.waitUntil(20_000L) {
        atual.uiState.value.showCameraPermissionRedirectConfirm ||
            atual.uiState.value.dialogState == DialogState.CAPTURANDO_SINAIS
      }
      if (atual.uiState.value.showCameraPermissionRedirectConfirm) {
        compose.onNodeWithText(context.getString(R.string.camera_permission_continue)).performClick()
      }
      // Primeira decisão NATURAL. Nenhum forcarFechamento/endSession/onRecognitionFailed de teste.
      compose.waitUntil(120_000L) {
        obs.eventos.any { it.first == "decisao" } &&
            atual.uiState.value.conversa.turnos.any { it.decisao != null }
      }
      val decisao = obs.eventos.first { it.first == "decisao" }.second
      relatorio.put("primeira_decisao", decisao)
      assertTrue("Resultado sem falha de segmento: $decisao",
          Regex("(?:^|,)falhas=[1-9][0-9]*(?:,|$)").containsMatchIn(decisao))
      assertTrue(decisao, decisao.contains("sinais=0,"))
      val turno = atual.uiState.value.conversa.turnos.first { it.decisao != null }
      assertTrue(turno.decisao in setOf(DecisaoNaConversa.REPITA, DecisaoNaConversa.DESISTIU))
      assertTrue(decisao.contains("decisao=${turno.decisao}"))
      assertTrue(turno.sinais.isEmpty())
      assertEquals(original.copy(comandoDeVoz = false, gravadorSessao = false), configs.valores.value)
      compose.onNodeWithTag("cancelar_atendimento_button").performClick()
      compose.waitUntil(20_000L) {
        atual.uiState.value.dialogState == DialogState.AGUARDANDO_SINAL && parada(atual) &&
        !atual.uiState.value.libras.isCollecting && !atual.uiState.value.libras.isClassifying
      }
      val falasAntes = obs.falas.size
      val fim = SystemClock.elapsedRealtime() + 1_500L
      do {
        assertEquals(DialogState.AGUARDANDO_SINAL, atual.uiState.value.dialogState)
        assertTrue("Câmera retomou depois de cancelar", parada(atual))
        assertEquals("TTS novo após cancelar", falasAntes, obs.falas.size)
        obs.validar()
        SystemClock.sleep(50)
      } while (SystemClock.elapsedRealtime() < fim)
      assertTrue(atual.uiState.value.conversa.turnos.all {
        it.sinais.isEmpty() && it.falado == null && it.resposta == null
      })
      relatorio.put("pos_cancelamento_ms", 1_500).put("sem_glosa_confirmacao_traducao", true)
    } catch (e: Throwable) {
      erro = e
    } finally {
      fun limpar(acao: () -> Unit) {
        try { acao() } catch (e: Throwable) { if (erro == null) erro = e else erro!!.addSuppressed(e) }
      }
      vm?.let { atual ->
        limpar { instrumentation.runOnMainSync { atual.cancelarAtendimento() } }
        limpar { compose.waitUntil(20_000L) { parada(atual) } }
      }
      limpar { scenario?.close() }
      var teardownConcluido = false
      barreira?.let { fim -> limpar { fim.aguardar(); teardownConcluido = true } }
      relatorio.put("teardown_pipeline_concluido", teardownConcluido)
      observador?.let { obs ->
        // close da Activity não é barreira. Só validar/remover após a drenagem/dispose reais.
        // Se timeout/falha, manter delegados até morrer o processo; nunca encurtar a observação.
        if (teardownConcluido) limpar { obs.validar() }
        limpar { obs.relatar(relatorio) }
        if (teardownConcluido) limpar { instrumentation.runOnMainSync { obs.fechar() } }
      }
      if (kitAtivo) limpar { MockDeviceKit.getInstance(context).disable() }
      if (snapshotPersistido) limpar {
        EstadoRecusadoEtapa4.restaurarDoDono(execucao)
        relatorio.put("cleanup", true)
      }
      video?.let { arquivo -> limpar { check(!arquivo.exists() || arquivo.delete()) } }
      relatorio.put("status", if (erro == null) "APROVADO" else "FALHOU")
          .put("fim_epoch_ms", System.currentTimeMillis()).put("erro", erro?.toString() ?: JSONObject.NULL)
      limpar { destino.writeText(relatorio.toString(2)) }
    }
    erro?.let { throw it }
    Etapa4Suporte.prova(relatorio)
  }

  /** Método separado, sem Activity/mock/vídeo. Não é evidência de recusa por segmento. */
  @Test fun prepararInterrupcaoRecusado() {
    val execucao = Etapa4Suporte.optIn("etapa4InterromperRecusado")
    val id = Etapa4Suporte.identidade()
    assertEquals("etapa4-recusado-sintetico-v1", id.getString("experimento"))
    EstadoRecusadoEtapa4.prepararInterrupcao(execucao)
  }

  @Test fun recuperarEstadoRecusadoInterrompido() {
    val execucao = Etapa4Suporte.optIn("etapa4RecuperarRecusado")
    val id = Etapa4Suporte.identidade()
    assertEquals("etapa4-recusado-sintetico-v1", id.getString("experimento"))
    val interrupcao = InterrupcaoEtapa4.verificarRecuperacao(EstadoRecusadoEtapa4.carregarDoDono(execucao), "RECUSADO")
    EstadoRecusadoEtapa4.restaurarDoDono(execucao)
    val prova = JSONObject().put("execucao", execucao)
        .put("fase", "recuperarEstadoRecusadoInterrompido").put("cleanup", true)
        .put("status", "RECUPERADO_SEM_EVIDENCIA").put("tipo", "RECUSADO")
      .put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
    InterrupcaoEtapa4.concluirRecuperacao(prova, interrupcao)
    Etapa4Suporte.prova(prova)
  }

  private fun parada(vm: CameraViewModel): Boolean = vm.uiState.value.let {
    !it.hasStream && !it.isStartingStream && !it.hasReceivedFirstFrame &&
        campoEtapa4<Any?>(vm, "stream") == null && campoEtapa4<Any?>(vm, "camera") == null &&
        campoEtapa4<Any?>(vm, "streamEncerrando") == null
  }

  private class Observador(private val vm: CameraViewModel, private val motivo: String) {
    val tentativas = AtomicInteger()
    val recusas = AtomicInteger()
    val callbacksFalha = AtomicInteger()
    val glosas = AtomicInteger()
    val contextos = AtomicInteger()
    val frames = AtomicInteger()
    val poses = AtomicInteger()
    val maos = AtomicInteger()
    val eventos = ConcurrentLinkedQueue<Pair<String, String>>()
    val falas = ConcurrentLinkedQueue<String>()
    private val estados = ConcurrentLinkedQueue<DialogState>()
    private val restaurar = mutableListOf<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
      try {
        val pipeline = campoEtapa4<Any>(vm, "landmarkPipeline")
        val dialogo = campoEtapa4<Any>(vm, "dialogOrchestrator")
        for (dono in listOf(pipeline, dialogo)) {
          envolver<(String, String) -> Unit>(dono, "onEvento") { original ->
            { nome, detalhe -> eventos.add(nome to detalhe); original(nome, detalhe) }
          }
        }
        envolver<SignClassifier>(pipeline, "classifier") { original ->
          assertTrue("Pipeline não carrega a recusa real do APK", original is ClassificadorRecusado)
          object : SignClassifier by original {
            override fun classify(segmento: SegmentoSinal): Classificacao {
              // Conta uma chamada REAL com segmento vindo do vídeo; não retém coordenadas.
              tentativas.incrementAndGet()
              check(segmento.frames.size >= 2 && segmento.tsMs.last() > segmento.tsMs.first())
              try { return original.classify(segmento) }
              catch (e: Exception) {
                if (e is IllegalStateException && e.message == motivo) recusas.incrementAndGet()
                throw e // A pipeline original transforma em onRecognitionFailed().
              }
            }
          }
        }
        envolver<((FrameProcessado) -> Unit)?>(pipeline, "onFrameProcessado") { original ->
          { frame ->
            frames.incrementAndGet()
            if (frame.pose) poses.incrementAndGet()
            if (frame.maoEsq || frame.maoDir) maos.incrementAndGet()
            original?.invoke(frame)
          }
        }
        envolver<(Classificacao) -> Unit>(pipeline, "onRecognized") { original ->
          { c -> glosas.incrementAndGet(); original(c) }
        }
        envolver<() -> Unit>(pipeline, "onRecognitionFailed") { original ->
          { callbacksFalha.incrementAndGet(); original() }
        }
        envolver<GlossContextualizer>(dialogo, "contextualizer") { original ->
          assertSame(campoEtapa4<GlossContextualizer>(vm, "glossContextualizer"), original)
          object : GlossContextualizer by original {
            override suspend fun contextualize(glosas: List<String>): Contextualizacao {
              contextos.incrementAndGet() // Antes de delegar: conta até falha/throw.
              return original.contextualize(glosas)
            }
          }
        }
        envolver<TtsEngine>(dialogo, "speaker") { original ->
          assertSame(campoEtapa4<TtsEngine>(vm, "vozEmCadeia"), original)
          object : TtsEngine by original {
            override suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit): Boolean {
              falas.add(text)
              return original.speakAndAwait(text, onInicioAudio)
            }
          }
        }
        scope.launch { vm.uiState.collect { estados.add(it.dialogState) } }
      } catch (e: Throwable) { fechar(); throw e }
    }

    fun validar() {
      assertTrue("Vídeo não chegou ao MediaPipe", frames.get() > 0 && poses.get() > 0 && maos.get() > 0)
      assertTrue("Nenhum segmento natural", eventos.any { it.first == "segmento" })
      assertTrue("Classificador recusado não executado por segmento", tentativas.get() > 0)
      assertEquals("Houve falha diferente da recusa esperada", tentativas.get(), recusas.get())
      assertTrue("Recusa não chegou ao callback real", callbacksFalha.get() > 0)
      assertTrue("Falha técnica diferente da recusa: $eventos", eventos.filter { it.first.startsWith("falha_") }
          .all { it.first == "falha_classificacao" && it.second == motivo })
      assertTrue(eventos.any { it.first == "falha_classificacao" && it.second == motivo })
      assertEquals(0, glosas.get())
      assertTrue(eventos.none { it.first == "classificacao" })
      assertEquals("Recusa chamou contextualizador", 0, contextos.get())
      assertFalse("Confirmação de tradução foi aberta", DialogState.CONFIRMANDO_RECONHECIMENTO in estados)
      assertTrue("Tradução chegou ao TTS: $falas", falas.all {
        it == DialogOrchestrator.AVISO_REPITA || it == DialogOrchestrator.AVISO_DESISTIR
      })
      assertTrue(vm.uiState.value.conversa.turnos.all { it.sinais.isEmpty() && it.falado == null })
    }

    private fun <T> envolver(dono: Any, nome: String, criar: (T) -> T) {
      val campo = dono.javaClass.getDeclaredField(nome).apply { isAccessible = true }
      @Suppress("UNCHECKED_CAST") val original = campo.get(dono) as T
      val observado = criar(original)
      campo.set(dono, observado)
      restaurar.add { campo.set(dono, original) }
      check(campo.get(dono) === observado) { "Observador não instalado: $nome" }
    }

    fun fechar() { scope.cancel(); restaurar.asReversed().forEach { it() }; restaurar.clear() }

    fun relatar(r: JSONObject) {
      r.put("frames", frames.get()).put("poses", poses.get()).put("maos", maos.get())
          .put("classify_tentativas", tentativas.get()).put("classify_recusas", recusas.get())
          .put("callbacks_falha", callbacksFalha.get()).put("glosas", glosas.get())
          .put("contextualizacoes", contextos.get()).put("tts_chamadas", JSONArray(falas.toList()))
          .put("estados", JSONArray(estados.map { it.name }))
          .put("eventos", JSONArray(eventos.map { JSONObject().put("nome", it.first).put("detalhe", it.second) }))
    }
  }
}
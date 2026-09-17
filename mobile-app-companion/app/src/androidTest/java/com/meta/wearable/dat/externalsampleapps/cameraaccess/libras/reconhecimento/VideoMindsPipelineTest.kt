package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.LexicoGlosas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.GravadorSessao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.LeituraSistema
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Metricas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.AvaliadorDeFrase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DecisaoFrase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.MotivoEncerramento
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.ResultadoSessao
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Passa clipes de vídeo reais (MINDS-Libras) pelo caminho de produção — decoder HEVC, MediaPipe,
 * segmentador, classificador do pacote privado, avaliador de frase — e registra o que saiu.
 *
 * Opt-in `videoMinds=true`; os clipes ficam em `videoMindsDir` no aparelho (nunca no repositório).
 * Este teste NÃO afirma acurácia: o rótulo esperado vem do nome do arquivo e é só anotado no
 * relatório. Ele falha em falha técnica (decoder, extração, classificação), não em erro de
 * reconhecimento. Cada parâmetro de [ParametrosSegmentacao] pode ser sobrescrito por argumento
 * (`seg.pausaMs=...`), e a sessão sai em CSV no formato do gravador para
 * `scripts/calibracao_fronteiras.py`.
 */
@RunWith(AndroidJUnit4::class)
class VideoMindsPipelineTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val argumentos = InstrumentationRegistry.getArguments()

  @Test fun clipesReaisPercorremPipelineCompleta(): Unit = runBlocking {
    assumeTrue("Opt-in de vídeo MINDS não solicitado", argumentos.getString("videoMinds") == "true")
    assertTrue("Vídeo real exige pacote privado selecionado no build",
        BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO)
    val pasta = File(argumentos.getString("videoMindsDir") ?: "/sdcard/Download/libras-minds")
    val clipes = pasta.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }.orEmpty().sortedBy { it.name }
    assertTrue("Nenhum .mp4 em $pasta", clipes.isNotEmpty())

    val destino = File(context.filesDir, "video-minds/relatorio.json")
    destino.parentFile!!.mkdirs()
    val parametros = parametrosDosArgumentos()
    val relatorio = JSONObject()
        .put("execucao", java.util.UUID.randomUUID().toString())
        .put("inicio_epoch_ms", System.currentTimeMillis())
        .put("status", "EM_EXECUCAO")
        .put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("parametros_segmentacao", parametros.toString())
        .put("limiar_confianca", AvaliadorDeFrase.LIMIAR_PADRAO)
        // Os clipes desta sinalizante estão no treino do baseline: isto mede transporte e
        // segmentação ponta a ponta, não generalização do modelo.
        .put("avaliacao_de_generalizacao", false)
    destino.writeText(relatorio.toString(2))

    val lexico = LexicoGlosas.fromAssets(context)
    val carregado = FabricaClassificadorApp.carregar(context.assets) { error("Simulação proibida") }
    assertTrue("Classificador real exigido, veio ${carregado.modo}: ${carregado.motivo}",
        carregado.modo == ModoClassificador.REAL_EXPERIMENTAL)
    carregado.classificador.aquecer()

    // Uma pipeline para todos os clipes, como em produção: cada clipe é um turno de captura.
    // Criar uma por clipe recarregaria o MediaPipe e, no dispose, fecharia o classificador
    // compartilhado — o segundo clipe já classificaria em cima de um modelo fechado.
    val clipeAtual = AtomicReference<ColetaDoClipe?>(null)
    val estado = AtomicReference(LibrasState())
    val metricas = Metricas()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pipeline = LandmarkPipeline(
        context, scope, carregado.classificador,
        onState = { mudar -> estado.updateAndGet { it.mudar() } },
        onRecognized = { c -> clipeAtual.get()?.classificacoes?.add(c) },
        onRecognitionFailed = { clipeAtual.get()?.falhas?.incrementAndGet() },
        parametros = { parametros },
        metricas = metricas,
        onFrameProcessado = { f ->
          clipeAtual.get()?.let { coleta ->
            coleta.frames.incrementAndGet()
            if (f.pose) coleta.poses.incrementAndGet()
            if (f.maoEsq || f.maoDir) coleta.maos.incrementAndGet()
            coleta.ultimoFrame.set(SystemClock.elapsedRealtime())
            coleta.gravador.frame(f, coleta.turno)
          }
        },
        onEvento = { evento, detalhe ->
          clipeAtual.get()?.let { coleta ->
            coleta.eventos.add(evento to detalhe)
            coleta.gravador.evento(SystemClock.elapsedRealtime(), coleta.turno, evento, detalhe)
            Log.i(TAG, "clipe=${coleta.nome} evento=$evento $detalhe")
          }
        },
    )
    val porClipe = JSONArray()
    var acertos = 0
    var comGlosa = 0
    try {
      assertTrue("MediaPipe não carregou: ${estado.get().error}", pipeline.carregarModelos())
      for ((indice, clipe) in clipes.withIndex()) {
        val resultado = executarClipe(clipe, indice, pipeline, clipeAtual, estado, metricas, parametros, lexico)
        porClipe.put(resultado)
        if (resultado.optBoolean("acertou")) acertos++
        if (resultado.optString("glosa_top1").isNotEmpty()) comGlosa++
        relatorio.put("clipes", porClipe)
        destino.writeText(relatorio.toString(2))
      }
      relatorio.put("status", "APROVADO")
    } catch (e: Throwable) {
      relatorio.put("status", "FALHOU").put("erro", e.toString())
      throw e
    } finally {
      runCatching { withContext(Dispatchers.Main.immediate) { pipeline.stop() } }
      runCatching { pipeline.dispose() }
      scope.cancel()
      relatorio.put("clipes", porClipe)
          .put("total_clipes", clipes.size)
          .put("clipes_com_glosa", comGlosa)
          .put("clipes_com_rotulo_esperado_no_top1", acertos)
      destino.writeText(relatorio.toString(2))
      Log.i(TAG, "status=${relatorio.optString("status")} clipes=${clipes.size} " +
          "com_glosa=$comGlosa rotulo_no_top1=$acertos")
    }
  }

  /** O que um clipe acumula enquanto roda; a pipeline é compartilhada entre todos. */
  private class ColetaDoClipe(val nome: String, val turno: Int, val gravador: GravadorSessao) {
    val frames = AtomicInteger()
    val poses = AtomicInteger()
    val maos = AtomicInteger()
    val falhas = AtomicInteger()
    val ultimoFrame = AtomicLong()
    val classificacoes = ConcurrentLinkedQueue<Classificacao>()
    val eventos = ConcurrentLinkedQueue<Pair<String, String>>()
  }

  private suspend fun executarClipe(
      clipe: File,
      turno: Int,
      pipeline: LandmarkPipeline,
      clipeAtual: AtomicReference<ColetaDoClipe?>,
      estado: AtomicReference<LibrasState>,
      metricas: Metricas,
      parametros: ParametrosSegmentacao,
      lexico: LexicoGlosas,
  ): JSONObject {
    val esperado = rotuloEsperado(clipe.name)
    val video = VideoDeArquivo.carregar(clipe)
    val gravador = GravadorSessao(File(context.filesDir, "video-minds/${clipe.nameWithoutExtension}"))
    val csv = gravador.abrir()
    val coleta = ColetaDoClipe(clipe.name, turno, gravador)
    clipeAtual.set(coleta)
    val frames = coleta.frames
    val poses = coleta.poses
    val maos = coleta.maos
    val falhas = coleta.falhas
    val ultimoFrame = coleta.ultimoFrame
    val classificacoes = coleta.classificacoes
    val eventos = coleta.eventos
    var decisao: DecisaoFrase? = null
    var iniciada = false
    var encerrada = false
    val inicio = SystemClock.elapsedRealtime()
    try {
      metricas.novoTurno(SystemClock.uptimeMillis())
      withContext(Dispatchers.Main.immediate) { pipeline.startSession() }
      iniciada = true
      val primeiroPts = video.amostras.first().second
      for ((bytes, ptsUs) in video.amostras) {
        // Mesma cadência do vídeo: o segmentador mede velocidade pelo relógio real.
        val espera = inicio + (ptsUs - primeiroPts) / 1_000 - SystemClock.elapsedRealtime()
        if (espera > 0) delay(espera)
        metricas.frameRecebido()
        pipeline.feedCompressedFrame(bytes, ptsUs, video.largura, video.altura, video.csd)
      }
      val fimEnvio = SystemClock.elapsedRealtime()
      withTimeout(20_000) {
        do { delay(100) }
        while (SystemClock.elapsedRealtime() - maxOf(fimEnvio, ultimoFrame.get()) < 1_500)
      }
      withTimeout(30_000) { withContext(Dispatchers.Main.immediate) { pipeline.endSession() } }
      encerrada = true
      decisao = AvaliadorDeFrase(lexico.glosas)
          .avaliar(ResultadoSessao(classificacoes.toList(), falhas.get(), MotivoEncerramento.MANUAL))
      assertTrue("Nenhum frame extraído de ${clipe.name}", frames.get() > 0)
      assertNull("Erro persistente na pipeline", estado.get().error)
    } finally {
      if (iniciada && !encerrada) {
        runCatching { withTimeout(30_000) { withContext(Dispatchers.Main.immediate) { pipeline.endSession() } } }
      }
      clipeAtual.set(null)
      gravador.fechar()
    }
    val falhasTecnicas = eventos.filter { it.first == "falha_decoder" || it.first == "falha_extracao" }
    assertTrue("Falhas técnicas em ${clipe.name}: $falhasTecnicas", falhasTecnicas.isEmpty())
    assertTrue("Falha de classificação em ${clipe.name}", falhas.get() == 0)
    val taxas = metricas.amostrar(SystemClock.elapsedRealtime(), LeituraSistema(), pipeline.filaCheiaDecoder)
    val top1 = classificacoes.maxByOrNull { it.confianca }
    return JSONObject()
        .put("arquivo", clipe.name)
        .put("video_sha256", video.sha256)
        .put("codec_origem", video.codecOrigem)
        .put("transcodificado_para_hevc", video.transcodificado)
        .put("resolucao", "${video.largura}x${video.altura}")
        .put("amostras", video.amostras.size)
        .put("duracao_ms", SystemClock.elapsedRealtime() - inicio)
        .put("frames_extraidos", frames.get())
        .put("frames_pose", poses.get())
        .put("frames_com_maos", maos.get())
        .put("fps_decodificados", taxas.fpsDecodificado)
        .put("segmentos", eventos.count { it.first == "segmento" })
        .put("descartados", eventos.count { it.first == "descartado" })
        .put("falhas_classificacao", falhas.get())
        .put("rotulo_esperado", esperado ?: JSONObject.NULL)
        .put("glosa_top1", top1?.glosa ?: "")
        .put("confianca_top1", top1?.confianca ?: JSONObject.NULL)
        .put("acertou", esperado != null && top1?.glosa == esperado)
        .put("decisao", decisao?.toString() ?: JSONObject.NULL)
        .put("csv_sessao", csv.absolutePath)
        .put("classificacoes", JSONArray(classificacoes.map {
          JSONObject().put("glosa", it.glosa).put("confianca", it.confianca).put("margem", it.margem)
        }))
        .put("eventos", JSONArray(eventos.map { JSONObject().put("nome", it.first).put("detalhe", it.second) }))
  }

  /** `20VontadeSinalizador08-5.mp4` -> `vontade`. Nome fora do padrão fica sem rótulo esperado. */
  private fun rotuloEsperado(nome: String): String? =
      Regex("^\\d+([A-Za-zÀ-ÿ]+)Sinalizador").find(nome)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

  private fun parametrosDosArgumentos(): ParametrosSegmentacao {
    val padrao = ParametrosSegmentacao()
    fun f(nome: String, atual: Float) = argumentos.getString("seg.$nome")?.toFloat() ?: atual
    fun l(nome: String, atual: Long) = argumentos.getString("seg.$nome")?.toLong() ?: atual
    return ParametrosSegmentacao(
        limiarEntrada = f("limiarEntrada", padrao.limiarEntrada),
        limiarSaida = f("limiarSaida", padrao.limiarSaida),
        janelaVelocidadeMs = l("janelaVelocidadeMs", padrao.janelaVelocidadeMs),
        alfaSuavizacao = f("alfaSuavizacao", padrao.alfaSuavizacao),
        pausaMs = l("pausaMs", padrao.pausaMs),
        tetoOclusaoMs = l("tetoOclusaoMs", padrao.tetoOclusaoMs),
        duracaoMinimaMs = l("duracaoMinimaMs", padrao.duracaoMinimaMs),
        duracaoMaximaMs = l("duracaoMaximaMs", padrao.duracaoMaximaMs),
        preRollMs = l("preRollMs", padrao.preRollMs),
        posRollMs = l("posRollMs", padrao.posRollMs),
    )
  }

  private companion object {
    const val TAG = "VideoMinds"
  }
}

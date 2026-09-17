package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.LexicoGlosas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.MODELO_CONTEXTUALIZACAO_ATIVO
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.criarGlossContextualizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.LeituraSistema
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.Metricas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.AvaliadorDeFrase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DecisaoFrase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.MotivoEncerramento
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.ResultadoSessao
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in videoClassificadorPrivado=true: vídeo HEVC → pipeline real → avaliação →
 * contextualizador padrão SOMENTE se aceito. Não navega pela UI, não usa áudio/óculos,
 * não injeta glosas e não mede acurácia. Use assets androidTest padrão (sem fixtures privadas).
 * Relatório sem landmarks/mídia em files/video-classificador-privado/<vídeo>.json do alvo.
 */
@RunWith(AndroidJUnit4::class)
class VideoClassificadorPrivadoTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Test fun movimentoSinteticoPercorreClassificadorEAvaliacao() = runBlocking {
    executar("sinais.mp4", comPessoa = true)
  }

  @Test fun videoSemPessoaNaoAutorizaFrase() = runBlocking {
    executar("plant.mp4", comPessoa = false)
  }

  private suspend fun executar(nome: String, comPessoa: Boolean) {
    assumeTrue("Opt-in de vídeo não solicitado",
        InstrumentationRegistry.getArguments().getString("videoClassificadorPrivado") == "true")
    val destino = File(context.filesDir, "video-classificador-privado/$nome.json")
    destino.parentFile!!.mkdirs()
    val relatorio = JSONObject().put("execucao", java.util.UUID.randomUUID().toString())
        .put("inicio_epoch_ms", System.currentTimeMillis()).put("video", nome).put("status", "EM_EXECUCAO")
    destino.writeText(relatorio.toString(2)) // invalida sucesso antigo ANTES das pré-condições
    var erroExecucao: Throwable? = null
    try {
      executarPipeline(nome, comPessoa, relatorio, destino)
      relatorio.put("status", "APROVADO")
    } catch (e: Throwable) {
      erroExecucao = e
      relatorio.put("status", "FALHOU").put("erro", e.toString())
      throw e
    } finally {
      val erroEscrita = runCatching { destino.writeText(relatorio.toString(2)) }.exceptionOrNull()
      if (erroEscrita != null) {
        relatorio.put("status", "FALHOU").put("erro_persistencia", erroEscrita.toString())
        erroExecucao?.addSuppressed(erroEscrita)
      }
      // Eventos individuais ficam separados para não truncar a linha de resumo do logcat.
      val resumo = JSONObject(relatorio.toString()).apply { remove("eventos") }
      Log.i("VideoClassificador", resumo.toString())
      if (erroExecucao == null && erroEscrita != null) throw erroEscrita
    }
  }

  private suspend fun executarPipeline(nome: String, comPessoa: Boolean, relatorio: JSONObject, destino: File) {
    assertTrue("Vídeo real exige pacote privado selecionado no build",
        BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO)
    // Pré-condições falham em vez de pular silenciosamente quando o opt-in foi solicitado.
    val video = lerVideo(nome)
    val lexico = LexicoGlosas.fromAssets(context)
    val carregado = FabricaClassificadorApp.carregar(context.assets) { error("Simulação proibida") }
    assertEquals(carregado.motivo, ModoClassificador.REAL_EXPERIMENTAL, carregado.modo)
    val parametros = ParametrosSegmentacao()
    val estado = AtomicReference(LibrasState())
    val frames = AtomicInteger()
    val poses = AtomicInteger()
    val maos = AtomicInteger()
    val falhas = AtomicInteger()
    var enviosTentados = 0
    var enviosConcluidos = 0
    val ultimoFrame = AtomicLong()
    val classificacoes = ConcurrentLinkedQueue<Classificacao>()
    val eventos = ConcurrentLinkedQueue<Pair<String, String>>()
    val metricas = Metricas()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pipeline = LandmarkPipeline(
        context, scope, carregado.classificador,
        onState = { mudar -> estado.updateAndGet { it.mudar() } },
        onRecognized = { classificacoes.add(it) },
        onRecognitionFailed = { falhas.incrementAndGet() },
        parametros = { parametros },
        metricas = metricas,
        onFrameProcessado = {
          frames.incrementAndGet()
          if (it.pose) poses.incrementAndGet()
          if (it.maoEsq || it.maoDir) maos.incrementAndGet()
          ultimoFrame.set(SystemClock.elapsedRealtime())
        },
        onEvento = { evento, detalhe ->
          eventos.add(evento to detalhe)
          Log.i("VideoClassificadorEvento", "video=$nome evento=$evento $detalhe")
        },
    )
    var decisao: DecisaoFrase? = null
    var texto: String? = null
    var origem: String? = null
    var erro: Throwable? = null
    var iniciada = false
    var encerrada = false
    try {
      assertTrue("MediaPipe não carregou: ${estado.get().error}", pipeline.carregarModelos())
      carregado.classificador.aquecer()
      val inicio = SystemClock.elapsedRealtime()
      metricas.novoTurno(SystemClock.uptimeMillis())
      metricas.amostrar(inicio, LeituraSistema(), 0)
      // Mesmo dispatcher e ordem do app: sessão antes do primeiro frame.
      withContext(Dispatchers.Main.immediate) { pipeline.startSession() }
      iniciada = true
      for ((bytes, ptsUs) in video.amostras) {
        // Preserva a cadência do vídeo; a pipeline mede velocidade com o relógio real.
        val espera = inicio + (ptsUs - video.amostras.first().second) / 1_000 - SystemClock.elapsedRealtime()
        if (espera > 0) delay(espera)
        metricas.frameRecebido()
        enviosTentados++
        pipeline.feedCompressedFrame(bytes, ptsUs, video.largura, video.altura, video.csd)
        enviosConcluidos++
      }
      // O decoder não expõe EOS/idle. Janela limitada de quiescência antes de endSession;
      // não afirmar que todos os frames foram processados (acquireLatestImage pode descartá-los).
      val fimEnvio = SystemClock.elapsedRealtime()
      withTimeout(10_000) {
        do { delay(100) }
        while (SystemClock.elapsedRealtime() - maxOf(fimEnvio, ultimoFrame.get()) < 1_500)
      }
      withTimeout(30_000) { withContext(Dispatchers.Main.immediate) { pipeline.endSession() } }
      encerrada = true
      withContext(Dispatchers.Main.immediate) { pipeline.stop() }
      val resultado = ResultadoSessao(classificacoes.toList(), falhas.get(), MotivoEncerramento.MANUAL)
      decisao = AvaliadorDeFrase(lexico.glosas).avaliar(resultado)
        val conhecidas = resultado.classificacoes.map { it.glosa }.filter { it in lexico.glosas }
        val deveRejeitar = resultado.falhas > 0 || conhecidas.isEmpty() ||
          resultado.classificacoes.any { it.confianca < AvaliadorDeFrase.LIMIAR_PADRAO }
        assertEquals("Decisão incoerente com confiança/léxico reais",
          if (deveRejeitar) DecisaoFrase.PedirRepeticao else DecisaoFrase.Falar(conhecidas), decisao)
      if (decisao is DecisaoFrase.Falar) {
        val contextualizador = criarGlossContextualizer(context)
        try {
          val c = contextualizador.contextualize(decisao.glosas)
          texto = c.texto
          origem = c.origem.name
          assertTrue("Contextualização vazia", c.texto.isNotBlank())
        } finally { contextualizador.close() }
      }
      assertTrue("Nenhum frame extraído", frames.get() > 0)
      assertEquals("Falha técnica de classificação", 0, falhas.get())
      assertNull("Erro persistente na pipeline", estado.get().error)
      assertEquals(classificacoes.size, eventos.count { it.first == "classificacao" })
      assertTrue(classificacoes.all {
        it.glosa.isNotBlank() && it.confianca.isFinite() && it.confianca in 0f..1f &&
            it.margem.isFinite() && it.margem in 0f..1f
      })
      if (comPessoa) {
        assertTrue("Nenhuma pose normalizável", poses.get() > 0)
        assertTrue("Nenhuma mão detectada", maos.get() > 0)
        assertTrue("Vídeo não gerou segmento: $eventos", eventos.any { it.first == "segmento" })
        assertTrue("Vídeo não chegou ao classificador real: $eventos", classificacoes.isNotEmpty())
      } else {
        // MediaPipe pode alucinar pose até no controle: medir, não exigir detector perfeito.
        // O critério deste fixture é NÃO autorizar uma frase, sem alterar o limiar para obtê-lo.
        assertEquals("Controle negativo autorizou frase", DecisaoFrase.PedirRepeticao, decisao)
        assertNull(texto)
      }
    } catch (e: Throwable) {
      erro = e
      throw e
    } finally {
        // Se o nativo bloquear no close, permanece ENCERRANDO, nunca um falso sucesso.
        relatorio.put("status", "ENCERRANDO").put("erro", erro?.toString() ?: JSONObject.NULL)
        val errosLimpeza = mutableListOf<Throwable>()
        runCatching { destino.writeText(relatorio.toString(2)) }.onFailure { errosLimpeza.add(it) }
        if (iniciada && !encerrada) {
          runCatching { withTimeout(30_000) { withContext(Dispatchers.Main.immediate) { pipeline.endSession() } } }
              .onFailure { errosLimpeza.add(it) }
        }
        runCatching { pipeline.dispose() }.onFailure { errosLimpeza.add(it) }
        scope.cancel()
        val falhasTecnicas = eventos.filter { it.first == "falha_decoder" || it.first == "falha_extracao" }
        if (falhasTecnicas.isNotEmpty()) errosLimpeza.add(AssertionError("Falhas técnicas: $falhasTecnicas"))
        val taxas = metricas.amostrar(SystemClock.elapsedRealtime(), LeituraSistema(), pipeline.filaCheiaDecoder)
        relatorio.put("video_sha256", video.sha256)
            .put("fixture_movimento_sintetico", comPessoa).put("avaliacao_linguistica", false)
            .put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
            .put("modelo_sha256", carregado.identidade!!.modeloSha256)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("parametros_segmentacao", parametros.toString())
            .put("limiar_confianca", AvaliadorDeFrase.LIMIAR_PADRAO)
            .put("contextualizador_neural_ativo", MODELO_CONTEXTUALIZACAO_ATIVO)
            .put("amostras_fixture", video.amostras.size)
            .put("envios_tentados", enviosTentados).put("envios_concluidos", enviosConcluidos)
            .put("frames_extraidos", frames.get()).put("frames_pose", poses.get())
            .put("frames_com_maos", maos.get()).put("fila_cheia", pipeline.filaCheiaDecoder)
            .put("fps_decodificados", taxas.fpsDecodificado)
            .put("falhas_classificacao", falhas.get())
            .put("falhas_tecnicas", falhasTecnicas.size)
            .put("erros_limpeza", JSONArray(errosLimpeza.map { it.toString() }))
            .put("eventos", JSONArray(eventos.map { JSONObject().put("nome", it.first).put("detalhe", it.second) }))
            .put("classificacoes", JSONArray(classificacoes.map {
              JSONObject().put("glosa", it.glosa).put("confianca", it.confianca).put("margem", it.margem)
            }))
            .put("decisao", decisao?.toString() ?: JSONObject.NULL)
            .put("contextualizacao_executada", texto != null)
            .put("texto", texto ?: JSONObject.NULL).put("origem_texto", origem ?: JSONObject.NULL)
            .put("erro", erro?.toString() ?: JSONObject.NULL)
        if (erro != null) errosLimpeza.forEach { erro.addSuppressed(it) }
        else if (errosLimpeza.isNotEmpty()) throw errosLimpeza.first()
    }
  }

  private data class Video(val csd: ByteArray, val largura: Int, val altura: Int,
      val amostras: List<Pair<ByteArray, Long>>, val sha256: String)

  private fun lerVideo(nome: String): Video {
    val arquivo = File(context.cacheDir, "video-privado-$nome")
    val bytes = instrumentation.context.assets.open(nome).use { it.readBytes() }
    arquivo.writeBytes(bytes)
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(arquivo.canonicalPath)
      val trilha = (0 until extractor.trackCount).first {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/hevc"
      }
      extractor.selectTrack(trilha)
      val formato = extractor.getTrackFormat(trilha)
      val csd = checkNotNull(formato.getByteBuffer("csd-0")).let { b ->
        ByteArray(b.remaining()).also { b.get(it) }
      }
      val amostras = mutableListOf<Pair<ByteArray, Long>>()
      val buffer = ByteBuffer.allocate(2 shl 20)
      while (true) {
        buffer.clear()
        val n = extractor.readSampleData(buffer, 0)
        if (n < 0) break
        require(n <= buffer.capacity()) { "Amostra HEVC acima do limite" }
        val bruto = ByteArray(n).also { buffer.get(it) }
        val ts = extractor.sampleTime
        require(amostras.isEmpty() || ts > amostras.last().second) { "Fixture exige PTS crescentes, sem B-frames" }
        amostras.add(paraAnnexB(bruto) to ts)
        extractor.advance()
      }
      require(amostras.isNotEmpty()) { "Vídeo vazio" }
      return Video(csd, formato.getInteger(MediaFormat.KEY_WIDTH), formato.getInteger(MediaFormat.KEY_HEIGHT),
          amostras, CarregadorClassificador.sha256(bytes))
    } finally { extractor.release(); arquivo.delete() }
  }

  private fun paraAnnexB(bytes: ByteArray): ByteArray {
    if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        (bytes[2] == 1.toByte() || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))) return bytes
    val saida = ByteArrayOutputStream(bytes.size + 32)
    var i = 0
    while (i < bytes.size) {
      require(i + 4 <= bytes.size) { "Prefixo HEVC truncado" }
      val n = ByteBuffer.wrap(bytes, i, 4).int
      i += 4
      require(n > 0 && n <= bytes.size - i) { "NAL HEVC inválida" }
      saida.write(byteArrayOf(0, 0, 0, 1))
      saida.write(bytes, i, n)
      i += n
    }
    return saida.toByteArray()
  }
}
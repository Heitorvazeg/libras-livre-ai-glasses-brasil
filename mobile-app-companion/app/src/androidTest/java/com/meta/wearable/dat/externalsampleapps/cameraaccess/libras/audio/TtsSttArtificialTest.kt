package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio

import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogOrchestrator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Voz sem alto-falante nem microfone: o TTS offline do app sintetiza de verdade e o áudio
 * sintetizado é entregue ao STT real (modelo Vosk nos assets) pelo mesmo callback de PCM que a
 * captura do microfone usa. Roda no emulador, onde não há mic acústico — e por isso mesmo NÃO
 * prova áudio audível, roteamento de fone/óculos (SCO), eco, ruído nem voz humana. Prova que os
 * dois modelos carregam, sintetizam, reconhecem e encerram sem vazar recurso nativo.
 */
@RunWith(AndroidJUnit4::class)
class TtsSttArtificialTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun ttsSintetizaOsAvisosDoDialogoEFalaSemErro() = runBlocking {
    val engine = PiperSherpaOnnxTtsEngine(context)
    try {
      val avisos = listOf(DialogOrchestrator.AVISO_REPITA, DialogOrchestrator.AVISO_DESISTIR)
      val inicioAquecimento = SystemClock.elapsedRealtime()
      assertTrue("Pré-síntese dos avisos falhou", engine.aquecer(avisos))
      val msAquecimento = SystemClock.elapsedRealtime() - inicioAquecimento
      var inicioAudio = 0L
      val inicio = SystemClock.elapsedRealtime()
      assertTrue("TTS não falou o aviso de repetição",
          engine.speakAndAwait(DialogOrchestrator.AVISO_REPITA) {
            inicioAudio = SystemClock.elapsedRealtime() - inicio
          })
      val total = SystemClock.elapsedRealtime() - inicio
      assertTrue("speakAndAwait terminou sem sinalizar início de áudio", inicioAudio > 0)
      // Frase vazia é no-op: o diálogo chama o speaker com texto de contextualização que pode vir
      // em branco, e isso não é falha de áudio.
      assertTrue(engine.speakAndAwait(""))
      Log.i(TAG, "tts aquecimento_ms=$msAquecimento primeiro_audio_ms=$inicioAudio total_ms=$total")
    } finally {
      engine.shutdown()
    }
  }

  @Test fun sttTranscreveOAudioSintetizadoPeloTtsDoApp() {
    val frase = "qual é a idade dele"
    val (taxa, amostras) = sintetizar(frase)
    // O reconhecedor é construído com PcmMicCapture.SAMPLE_RATE; entregar outra taxa mudaria o
    // tom da fala e o teste mediria outra coisa.
    val pcm = paraPcm16(reamostrar(amostras, taxa, PcmMicCapture.SAMPLE_RATE))
    assertTrue("Síntese silenciosa", pcm.isNotEmpty())

    val captura = PcmMicCapture(context, MediaRecorder.AudioSource.VOICE_RECOGNITION,
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC)
    val stt = VoskSttEngine(context, captura)
    val texto = AtomicReference("")
    val erro = AtomicReference<Throwable?>(null)
    val resultado = CountDownLatch(1)
    val fimDeFala = CountDownLatch(1)
    try {
      stt.start(
          onResult = { texto.set(it); resultado.countDown() },
          onError = { erro.set(it) },
          onFimDeFala = { fimDeFala.countDown() },
      )
      // start() carrega o modelo fora da main; o callback de PCM só existe depois disso.
      val prazo = SystemClock.elapsedRealtime() + 60_000
      while (captura.pcmDataCallback == null && SystemClock.elapsedRealtime() < prazo) Thread.sleep(50)
      val entrada = checkNotNull(captura.pcmDataCallback) { "Vosk não abriu a escuta: ${erro.get()}" }
      // Sem mic acústico no emulador, startRecording pode falhar; o áudio deste teste é injetado
      // aqui, no mesmo ponto em que a captura entregaria o buffer do microfone.
      captura.stopRecording()

      val silencio = ByteArray(PcmMicCapture.SAMPLE_RATE) // 0,5 s de PCM16 zerado
      entrada(silencio, 0, silencio.size)
      var i = 0
      val bloco = PcmMicCapture.SAMPLE_RATE / 5 // 100 ms de PCM16
      while (i < pcm.size) {
        val n = minOf(bloco, pcm.size - i)
        entrada(pcm, i, n)
        i += n
      }
      // O Vosk fecha o enunciado no silêncio depois da fala (4.1).
      repeat(3) { entrada(silencio, 0, silencio.size) }
      assertTrue("Vosk não fechou o enunciado depois da fala",
          fimDeFala.await(30, TimeUnit.SECONDS))
      stt.stop()
      assertTrue("Sem resultado final do STT", resultado.await(30, TimeUnit.SECONDS))
    } finally {
      stt.encerrar()
    }
    val reconhecido = texto.get().lowercase()
    Log.i(TAG, "stt esperado=\"$frase\" reconhecido=\"$reconhecido\"")
    // Acurácia de fala é do modelo, não deste teste: exige-se que o caminho reconheça conteúdo,
    // com as palavras plenas da frase; "é"/"de" são átonas e o modelo pequeno as troca.
    assertTrue("STT devolveu vazio", reconhecido.isNotBlank())
    val esperadas = listOf("qual", "idade")
    val faltando = esperadas.filterNot { it in reconhecido }
    assertEquals("Palavras ausentes na transcrição \"$reconhecido\"", emptyList<String>(), faltando)
  }

  /** Mesma configuração do [PiperSherpaOnnxTtsEngine]: o áudio do STT é o do TTS de produção. */
  private fun sintetizar(texto: String): Pair<Int, FloatArray> {
    val raiz = context.getExternalFilesDir(null) ?: context.filesDir
    val dataDir = CopiaDeAssets.garantir(
        origem = "tts/pt_br/espeak-ng-data",
        destinoRaiz = raiz,
        listar = { context.assets.list(it) },
        abrir = { context.assets.open(it) },
    )
    val tts = OfflineTts(
        assetManager = context.assets,
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "tts/pt_br/pt_BR-edresson-low.onnx",
                    tokens = "tts/pt_br/tokens.txt",
                    dataDir = dataDir.absolutePath,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
        ),
    )
    try {
      val audio = tts.generate(texto, sid = 0, speed = 1.0f)
      assertTrue("Síntese vazia", audio.samples.isNotEmpty())
      return audio.sampleRate to audio.samples
    } finally {
      tts.release()
    }
  }

  private fun reamostrar(amostras: FloatArray, de: Int, para: Int): FloatArray {
    if (de == para) return amostras
    val saida = FloatArray((amostras.size.toLong() * para / de).toInt())
    for (i in saida.indices) {
      val origem = i.toDouble() * de / para
      val a = origem.toInt()
      val b = minOf(a + 1, amostras.size - 1)
      val peso = (origem - a).toFloat()
      saida[i] = amostras[a] * (1 - peso) + amostras[b] * peso
    }
    return saida
  }

  private fun paraPcm16(amostras: FloatArray): ByteArray {
    val bytes = ByteArray(amostras.size * 2)
    for (i in amostras.indices) {
      val v = (amostras[i].coerceIn(-1f, 1f) * 32767).roundToInt()
      bytes[2 * i] = (v and 0xFF).toByte()
      bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return bytes
  }

  private companion object {
    const val TAG = "TtsSttArtificial"
  }
}

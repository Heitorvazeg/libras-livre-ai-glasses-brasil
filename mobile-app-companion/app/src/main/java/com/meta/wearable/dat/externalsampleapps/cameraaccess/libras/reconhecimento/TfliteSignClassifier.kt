/*
 * Libras Livre — classificador de sinais pelo `.tflite` do ST-GCN (docs/prontidao-demo/02 §2.6).
 *
 * Carrega `<nome>.tflite` e o sidecar `<nome>.json` dos assets, confere um contra o outro e contra o
 * app (ValidacaoClassificador) e RECUSA o modelo com o motivo se algo divergir: lança
 * [ModeloRecusado], e quem cria mostra o erro na tela.
 *
 * O grafo do export já faz a recentragem do z, a imputação, os ossos e a reamostragem 96 -> 64. O
 * app entrega exatamente o contrato: o segmento imputado (2.3), reamostrado PELO TEMPO para os
 * frames do sidecar (2.2), com as dimensões que o sidecar pede (2.1). A saída vira confiança pelo
 * softmax com a temperatura do sidecar (2.8).
 *
 * float32, sem quantização (2.6). Instrumentado: o LiteRT não roda na JVM.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.res.AssetManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class ModeloRecusado(val motivos: List<String>) :
    IllegalStateException(PREFIXO + motivos.joinToString("; ")) {
  companion object {
    /** Início da mensagem: a faixa de estado mantém este erro entre sessões (é bloqueio, 10.2). */
    const val PREFIXO = "Modelo de sinais recusado: "
  }
}

class TfliteSignClassifier(sidecarJson: String, modelo: ByteArray) : SignClassifier {

  /** Lê `<nome>.json` e `<nome>.tflite` dos assets. */
  constructor(assets: AssetManager, nome: String = NOME_PADRAO) :
      this(
          assets.open("$nome.json").bufferedReader().use { it.readText() },
          assets.open("$nome.tflite").use { it.readBytes() },
      )

  companion object {
    private const val TAG = "Libras:TfliteSinais"
    const val NOME_PADRAO = "sinal_classifier"
    private const val NUM_THREADS = 2

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }

  val sidecar: SidecarClassificador
  private val interpreter: Interpreter
  // Segmentos e aquecimento podem chegar de workers distintos. O mesmo lock protege close/run.
  private val interpreterLock = Any()
  private var fechado = false

  init {
    sidecar = SidecarClassificador.ler(sidecarJson)
    val bytes = modelo
    if (!sidecar.sha256.equals(sha256(bytes), ignoreCase = true)) {
      throw ModeloRecusado(listOf("sha256 do .tflite não bate com o sidecar"))
    }
    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
    interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(NUM_THREADS))
    try {
      if (interpreter.inputTensorCount != 1 || interpreter.outputTensorCount != 1) {
        throw ModeloRecusado(listOf("exige exatamente uma entrada e uma saída"))
      }
      val saida = interpreter.getOutputTensor(0)
      if (saida.dataType() != DataType.FLOAT32 || !saida.shape().contentEquals(intArrayOf(1, sidecar.rotulos.size))) {
        throw ModeloRecusado(listOf("saída deve ser float32 [1, rótulos]"))
      }
      val entrada = interpreter.getInputTensor(0)
      val interfaceModelo =
          InterfaceModelo(
              sha256 = sha256(bytes),
              shapeEntrada = entrada.shape().toList(),
              dtypeEntrada = if (entrada.dataType() == DataType.FLOAT32) "float32" else entrada.dataType().name,
              tamanhoSaida = saida.shape().last(),
          )
      val motivos = ValidacaoClassificador.motivosDeRecusa(sidecar, interfaceModelo)
      if (motivos.isNotEmpty()) throw ModeloRecusado(motivos)
      Log.i(TAG, "modelo aceito: ${sidecar.rotulos.size} rótulos, entrada ${sidecar.shape}, " +
          "imputação no grafo=${sidecar.imputacaoEmbutida} (o app imputa nos dois casos, 2.3)")
    } catch (e: Throwable) {
      interpreter.close()
      throw e
    }
  }

  override fun classify(segmento: SegmentoSinal): Classificacao {
    val logits = logits(segmento)
    val top = Probabilidades.top2(Probabilidades.softmax(logits, sidecar.temperatura))
    return Classificacao(sidecar.rotulos[top.indice], top.confianca, top.margem)
  }

  // 6.4: uma inferência com zeros, no contrato do sidecar.
  override fun aquecer() {
    val zeros = List(2) { Array(sidecar.pontos) { FloatArray(sidecar.dimensoes) } }
    logits(SegmentoSinal(zeros, longArrayOf(0, 1)))
  }

  /** Os logits crus do modelo para um segmento — exposto para os testes de paridade (2.7). */
  fun logits(segmento: SegmentoSinal): FloatArray = synchronized(interpreterLock) {
    check(!fechado) { "Classificador de sinais já fechado" }
    if (segmento.frames.size > sidecar.frames) {
      // 2.2: com a duração máxima do 1.8 isto não deveria acontecer; se acontecer, a imputação do
      // grafo pode deixar de ficar ociosa.
      Log.w(TAG, "segmento com ${segmento.frames.size} frames, acima dos ${sidecar.frames} do contrato")
    }
    val reamostrado = ReamostragemTemporal.reamostrar(segmento.frames, segmento.tsMs, sidecar.frames)
    val d = sidecar.dimensoes
    val entrada = Array(1) { Array(sidecar.frames) { t -> Array(sidecar.pontos) { p -> FloatArray(d) { c -> reamostrado[t][p][c] } } } }
    val saida = Array(1) { FloatArray(sidecar.rotulos.size) }
    interpreter.run(entrada, saida)
    check(saida[0].all { it.isFinite() }) { "Modelo produziu logits não finitos" }
    saida[0]
  }

  override fun close() {
    synchronized(interpreterLock) {
      if (!fechado) {
        fechado = true
        interpreter.close()
      }
    }
  }
}

/** Ocupa o lugar do classificador quando o modelo foi recusado: toda classificação falha com o motivo. */
class ClassificadorRecusado(private val motivo: String) : SignClassifier {
  override fun classify(segmento: SegmentoSinal): Classificacao = throw IllegalStateException(motivo)

  override fun aquecer() = throw IllegalStateException(motivo)

  override fun close() {}
}

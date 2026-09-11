/*
 * Libras Livre — contextualização pelo modelo local (§6.3 do plano).
 *
 * Modelo: ptt5-small com vocabulário podado (60,5M -> 45,1M parâmetros, vocab 32.128 -> 1.987),
 * fine-tunado em 2.048 pares glosa->PT e quantizado em int8 dynamic-range. 47,5 MB.
 * Pipeline em contextualization-model/ (ver docs/contextualizacao-implementacao.md).
 *
 * DUAS ASSINATURAS, sem KV cache:
 *   encode      : input_ids[1,16], attention_mask[1,16]                 -> encoder_hidden[1,16,512]
 *   decode_step : decoder_input_ids[1,24], encoder_hidden, attention_mask -> logits[1,24,V]
 *
 * O decoder recebe o PREFIXO INTEIRO com comprimento fixo e devolve logits de todas as
 * posições; o laço abaixo lê `logits[0][t]` no passo t. Custa O(T²) em vez de O(T), o que é
 * aceitável porque a inferência acontece UMA VEZ POR SESSÃO — e compra shapes estáticos e
 * nenhum estado entre passos. KV cache fica como otimização se a medição em aparelho exigir.
 *
 * O TOKENIZER NÃO EXISTE AQUI, de propósito (§6.1.3): a entrada é vocabulário fechado, então
 * `glosa_ids.json` (41 entradas) resolve; a saída só precisa de destokenização, que é
 * `destokenizar.json` (1.987 peças) mais a convenção de espaço do SentencePiece. Verificado no
 * Python que concatenar ids por glosa é idêntico a tokenizar a frase inteira.
 *
 * DECODIFICAÇÃO GULOSA E LIVRE, não restrita. Medido: forçar cobertura TRIPLICA a degeneração
 * em combinações não vistas (15,0% contra 5,0%) e cega a guarda — omissão, que a
 * GuardedGlossContextualizer detecta, vira incoerência que passava. Melhor deixar o modelo
 * omitir e ser barrado.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

import android.content.Context
import android.util.Log
// LiteRT é o nome atual do runtime do TensorFlow Lite e manteve o NAMESPACE antigo:
// o artefato com.google.ai.edge.litert:litert publica org.tensorflow.lite.Interpreter.
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TfliteGlossContextualizer(
    context: Context,
    modelo: String = "modelo_contextualizacao.tflite",
) : GlossContextualizer {

  private val glosaIds: Map<String, IntArray>
  private val pecas: Array<String>
  private val interpreter: Interpreter

  init {
    glosaIds = lerGlosaIds(context)
    pecas = lerPecas(context)
    // XNNPACK é a aposta do §7.1: o custo por passo é dominado por LER os pesos do decoder
    // (~25MB em int8) a cada um dos ~15 passos, e o int8 dynamic-range só acelera na CPU —
    // o delegate de GPU dequantizaria os pesos (§6.2). GPU só se a medição em aparelho pedir.
    val opcoes = Interpreter.Options().setNumThreads(NUM_THREADS).setUseXNNPACK(true)
    interpreter = Interpreter(mapearModelo(context, modelo), opcoes)
  }

  override suspend fun contextualize(glosas: List<String>): Contextualizacao =
      withContext(Dispatchers.Default) {
        val texto = gerar(glosas)
        Contextualizacao(texto, Contextualizacao.Origem.MODELO)
      }

  /** Ids de entrada: concatena as peças de cada glosa e fecha com EOS. */
  private fun montarEntrada(glosas: List<String>): IntArray {
    val ids = mutableListOf<Int>()
    for (g in glosas) glosaIds[g]?.let { ids.addAll(it.toList()) }
    ids.add(EOS)
    return ids.take(S_ENC).toIntArray()
  }

  private fun gerar(glosas: List<String>): String {
    val ids = montarEntrada(glosas)
    val entrada = Array(1) { IntArray(S_ENC) { i -> if (i < ids.size) ids[i] else PAD } }
    val mascara = Array(1) { IntArray(S_ENC) { i -> if (i < ids.size) 1 else 0 } }

    val oculto = Array(1) { Array(S_ENC) { FloatArray(D_MODEL) } }
    interpreter.runSignature(
        mapOf("input_ids" to entrada, "attention_mask" to mascara),
        mapOf("output_0" to oculto),
        "encode",
    )

    val seq = mutableListOf(PAD)
    val logits = Array(1) { Array(T_DEC) { FloatArray(pecas.size) } }
    repeat(T_DEC - 1) {
      val dec = Array(1) { IntArray(T_DEC) { i -> if (i < seq.size) seq[i] else PAD } }
      interpreter.runSignature(
          mapOf(
              "decoder_input_ids" to dec,
              "encoder_hidden" to oculto,
              "attention_mask" to mascara,
          ),
          mapOf("output_0" to logits),
          "decode_step",
      )
      val proximo = argmax(logits[0][seq.size - 1])
      if (proximo == EOS) return@repeat
      seq.add(proximo)
    }
    return destokenizar(seq)
  }

  private fun argmax(v: FloatArray): Int {
    var melhor = 0
    for (i in v.indices) if (v[i] > v[melhor]) melhor = i
    return melhor
  }

  /** Peças -> texto. `▁` do SentencePiece marca início de palavra. */
  private fun destokenizar(ids: List<Int>): String =
      ids.filter { it > UNK }
          .joinToString("") { pecas.getOrElse(it) { "" } }
          .replace('▁', ' ')
          .trim()

  override fun close() {
    runCatching { interpreter.close() }
  }

  companion object {
    private const val TAG = "Libras:Tflite"
    // Fixados NO EXPORT (contextualization-model/exportacao/para_tflite.py) — não são
    // parâmetros de runtime. Mudar aqui sem reexportar quebra silenciosamente.
    const val S_ENC = 16
    const val T_DEC = 24
    const val D_MODEL = 512
    const val PAD = 0
    const val EOS = 1
    const val UNK = 2
    private const val NUM_THREADS = 4

    private fun mapearModelo(context: Context, nome: String): MappedByteBuffer =
        context.assets.openFd(nome).use { fd ->
          fd.createInputStream().channel.map(
              FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }

    private fun lerGlosaIds(context: Context): Map<String, IntArray> {
      val raiz = JSONObject(
          context.assets.open("glosa_ids.json").bufferedReader().use { it.readText() })
      return raiz.keys().asSequence().associateWith { g ->
        val arr = raiz.getJSONArray(g)
        IntArray(arr.length()) { arr.getInt(it) }
      }
    }

    private fun lerPecas(context: Context): Array<String> {
      val arr = JSONArray(
          context.assets.open("destokenizar.json").bufferedReader().use { it.readText() })
      return Array(arr.length()) { arr.getString(it) }
    }
  }
}

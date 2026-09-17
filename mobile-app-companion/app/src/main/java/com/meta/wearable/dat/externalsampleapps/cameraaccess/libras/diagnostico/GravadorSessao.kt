/*
 * Libras Livre — gravador de sessão em CSV (docs/prontidao-demo/01-segmentacao.md §1.9).
 *
 * É pré-requisito de quase tudo que se mede com os óculos: a calibração do detector (1.10), a
 * confiança dos gestos que não são sinal (2.8) e os vídeos de referência do modelo. Um arquivo por
 * sessão com os óculos, em `getExternalFilesDir(null)/sessoes/<AAAAMMDD-HHMMSS>.csv`, recuperável sem
 * root:
 *
 *   adb pull /sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes
 *
 * Três tipos de linha no mesmo arquivo, com as mesmas colunas (ver [FormatoCsv]):
 *   - `frame`: um por frame que passou pelo MediaPipe, com estado do detector, velocidades, presença
 *     e os 57 pontos × 3 coordenadas normalizadas (vazios quando não houve pose);
 *   - `evento`: segmento, descarte, classificação, latência por etapa (6.5)...;
 *   - `metrica`: amostras por segundo do painel (3.8).
 *
 * A formatação e a escrita rodam numa thread própria, com buffer: nunca na thread do ImageReader.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.EstadoSinalizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.MedicaoVelocidade
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Um frame processado pelo pipeline, na forma que o gravador precisa. */
class FrameProcessado(
    val tsMs: Long,
    val estado: EstadoSinalizacao?,
    val medicao: MedicaoVelocidade?,
    val pose: Boolean,
    val maoEsq: Boolean,
    val maoDir: Boolean,
    /** Pontos normalizados (antes da imputação), ou null sem pose. Não é alterado depois de entregue. */
    val pontos: Array<FloatArray>?,
)

object FormatoCsv {

  const val N_PONTOS = 57

  private val FIXAS =
      listOf(
          "tipo", "ts_ms", "turno", "estado", "v_mao_esq", "v_mao_dir", "v_pulsos", "v_final",
          "v_suavizada", "pose", "mao_esq", "mao_dir", "nome", "detalhe")

  val COLUNAS: List<String> =
      FIXAS + (0 until N_PONTOS).flatMap { p -> listOf("x", "y", "z").map { c -> "p%02d_%s".format(Locale.ROOT, p, c) } }

  fun cabecalho(): String = COLUNAS.joinToString(",")

  fun linhaFrame(f: FrameProcessado, turno: Int): String {
    val campos = ArrayList<String>(COLUNAS.size)
    campos += "frame"
    campos += f.tsMs.toString()
    campos += turno.toString()
    campos += f.estado?.name ?: ""
    campos += numero(f.medicao?.maoEsq)
    campos += numero(f.medicao?.maoDir)
    campos += numero(f.medicao?.pulsos)
    campos += numero(f.medicao?.final)
    campos += numero(f.medicao?.suavizada)
    campos += bit(f.pose)
    campos += bit(f.maoEsq)
    campos += bit(f.maoDir)
    campos += ""
    campos += ""
    for (p in 0 until N_PONTOS) {
      val ponto = f.pontos?.getOrNull(p)
      for (c in 0 until 3) campos += numero(ponto?.getOrNull(c))
    }
    return campos.joinToString(",")
  }

  fun linhaEvento(tsMs: Long, turno: Int, nome: String, detalhe: String): String =
      linhaSemPontos("evento", tsMs, turno, nome, detalhe)

  fun linhaMetrica(tsMs: Long, turno: Int, nome: String, valor: String): String =
      linhaSemPontos("metrica", tsMs, turno, nome, valor)

  /** RFC 4180: aspas em volta se houver vírgula, aspas ou quebra de linha; aspas internas dobradas. */
  fun escapar(campo: String): String =
      if (campo.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + campo.replace("\"", "\"\"") + "\""
      else campo

  private fun linhaSemPontos(tipo: String, tsMs: Long, turno: Int, nome: String, detalhe: String): String {
    val fixas = listOf(tipo, tsMs.toString(), turno.toString(), "", "", "", "", "", "", "", "", "", escapar(nome), escapar(detalhe))
    return (fixas + List(N_PONTOS * 3) { "" }).joinToString(",")
  }

  // Float.toString é independente de locale (ponto decimal), que é o que o pandas espera.
  private fun numero(v: Float?): String = v?.toString() ?: ""

  private fun bit(v: Boolean) = if (v) "1" else "0"
}

class GravadorSessao(private val diretorioBase: File) {

  private companion object {
    const val TAG = "Libras:Gravador"
  }

  private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LibrasGravadorSessao") }
  private var escritor: BufferedWriter? = null // só tocado na thread do executor

  @Volatile var arquivo: File? = null
    private set

  /** Abre um arquivo novo e escreve o cabeçalho. Devolve o arquivo. */
  fun abrir(agora: Date = Date()): File {
    val pasta = File(diretorioBase, "sessoes").apply { mkdirs() }
    val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(agora)
    var destino = File(pasta, "$base.csv")
    var n = 2
    while (destino.exists()) destino = File(pasta, "$base-${n++}.csv")
    arquivo = destino
    val alvo = destino
    executor.execute {
      runCatching {
            escritor = alvo.bufferedWriter().also {
              it.write(FormatoCsv.cabecalho())
              it.newLine()
            }
          }
          .onFailure { Log.e(TAG, "não consegui abrir $alvo", it) }
    }
    Log.i(TAG, "gravando sessão em ${destino.absolutePath}")
    return destino
  }

  fun frame(f: FrameProcessado, turno: Int) = escrever { FormatoCsv.linhaFrame(f, turno) }

  fun evento(tsMs: Long, turno: Int, nome: String, detalhe: String) =
      escrever { FormatoCsv.linhaEvento(tsMs, turno, nome, detalhe) }

  fun metrica(tsMs: Long, turno: Int, nome: String, valor: String) =
      escrever { FormatoCsv.linhaMetrica(tsMs, turno, nome, valor) }

  /** Fecha o arquivo, esperando a fila de escrita esvaziar (até 2 s). */
  fun fechar() {
    if (arquivo == null) return
    arquivo = null
    executor.execute {
      runCatching { escritor?.close() }.onFailure { Log.w(TAG, "falha ao fechar o CSV", it) }
      escritor = null
    }
    val espera = executor.submit {}
    runCatching { espera.get(2, TimeUnit.SECONDS) }
  }

  /** Fecha e libera a thread. Depois disso o gravador não abre mais. */
  fun encerrar() {
    fechar()
    executor.shutdown()
  }

  private inline fun escrever(crossinline linha: () -> String) {
    if (arquivo == null) return
    executor.execute {
      val w = escritor ?: return@execute
      runCatching {
            w.write(linha())
            w.newLine()
          }
          .onFailure { Log.w(TAG, "falha ao escrever no CSV", it) }
    }
  }
}

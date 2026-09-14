/*
 * Formato do CSV do gravador de sessão (docs/prontidao-demo/01-segmentacao.md §1.9).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.EstadoSinalizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.MedicaoVelocidade
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GravadorSessaoTest {

  /** Divide uma linha CSV respeitando aspas (o suficiente para conferir o formato). */
  private fun campos(linha: String): List<String> {
    val saida = mutableListOf<String>()
    val atual = StringBuilder()
    var entreAspas = false
    var i = 0
    while (i < linha.length) {
      val c = linha[i]
      when {
        entreAspas && c == '"' && i + 1 < linha.length && linha[i + 1] == '"' -> { atual.append('"'); i++ }
        c == '"' -> entreAspas = !entreAspas
        c == ',' && !entreAspas -> { saida += atual.toString(); atual.clear() }
        else -> atual.append(c)
      }
      i++
    }
    saida += atual.toString()
    return saida
  }

  private fun frame(comPose: Boolean) =
      FrameProcessado(
          tsMs = 1234,
          estado = EstadoSinalizacao.SINALIZANDO,
          medicao = MedicaoVelocidade(maoEsq = 0.5f, maoDir = null, pulsos = 0.25f, final = 0.5f, suavizada = 0.4f),
          pose = comPose,
          maoEsq = true,
          maoDir = false,
          pontos = if (comPose) Array(57) { p -> floatArrayOf(p.toFloat(), -p.toFloat(), 0.125f) } else null,
      )

  @Test
  fun `cabecalho tem as colunas fixas e 57 pontos com 3 coordenadas`() {
    assertEquals(14 + 57 * 3, FormatoCsv.COLUNAS.size)
    assertEquals("p00_x", FormatoCsv.COLUNAS[14])
    assertEquals("p56_z", FormatoCsv.COLUNAS.last())
  }

  @Test
  fun `toda linha tem o mesmo numero de colunas do cabecalho`() {
    val n = FormatoCsv.COLUNAS.size
    assertEquals(n, campos(FormatoCsv.linhaFrame(frame(comPose = true), turno = 3)).size)
    assertEquals(n, campos(FormatoCsv.linhaFrame(frame(comPose = false), turno = 3)).size)
    assertEquals(n, campos(FormatoCsv.linhaEvento(10, 1, "segmento", "inicio=1,fim=2")).size)
    assertEquals(n, campos(FormatoCsv.linhaMetrica(10, 1, "fps_processado", "23.5")).size)
  }

  @Test
  fun `linha de frame traz estado, velocidades, presenca e pontos`() {
    val c = campos(FormatoCsv.linhaFrame(frame(comPose = true), turno = 3))
    val col = { nome: String -> c[FormatoCsv.COLUNAS.indexOf(nome)] }
    assertEquals("frame", col("tipo"))
    assertEquals("1234", col("ts_ms"))
    assertEquals("3", col("turno"))
    assertEquals("SINALIZANDO", col("estado"))
    assertEquals("0.5", col("v_mao_esq"))
    assertEquals("", col("v_mao_dir"))
    assertEquals("1", col("pose"))
    assertEquals("0", col("mao_dir"))
    assertEquals("56.0", col("p56_x"))
    assertEquals("-56.0", col("p56_y"))
    assertEquals("0.125", col("p56_z"))
  }

  @Test
  fun `detalhe com virgula, aspas e quebra de linha e escapado`() {
    val detalhe = "glosa=\"filho\", confianca=0.9\nfim"
    val c = campos(FormatoCsv.linhaEvento(5, 2, "classificacao", detalhe))
    assertEquals(detalhe, c[FormatoCsv.COLUNAS.indexOf("detalhe")])
    assertEquals("sem vírgula não ganha aspas", "simples", FormatoCsv.escapar("simples"))
  }

  @Test
  fun `gravador escreve cabecalho e uma linha por chamada, e fecha o arquivo`() {
    val pasta = Files.createTempDirectory("gravador").toFile()
    val gravador = GravadorSessao(pasta)
    val arquivo = gravador.abrir()
    repeat(5) { gravador.frame(frame(comPose = it % 2 == 0), turno = 1) }
    gravador.evento(99, 1, "segmento", "a,b")
    gravador.encerrar()

    assertTrue(arquivo.path.contains("sessoes"))
    val linhas = arquivo.readLines()
    assertEquals(FormatoCsv.cabecalho(), linhas.first())
    assertEquals(1 + 5 + 1, linhas.size)
    // Depois de fechar, nada mais é escrito.
    gravador.frame(frame(true), 1)
    assertEquals(7, arquivo.readLines().size)
  }
}

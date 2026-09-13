/*
 * Libras Livre — coletor de métricas (docs/prontidao-demo/03 §3.8 e 06 §6.5).
 *
 * "Medir antes de otimizar", no aparelho que houver na demo. Um coletor único para:
 *   - fps recebido dos óculos, decodificado para inferência e processado pelo MediaPipe, e o % de
 *     frames sem pose (3.5);
 *   - ocorrências de "fila do decodificador cheia";
 *   - tempo por etapa de cada turno (6.5), com uma linha de log `LibrasLatencia` por marca;
 *   - leitura do sistema (térmico, bateria, RAM), preenchida por quem tem Context.
 *
 * Os contadores são atômicos: são incrementados nas threads de frame e lidos uma vez por segundo.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** As etapas medidas por turno (6.5), na ordem em que acontecem. */
enum class Etapa(val nome: String) {
  INICIAR_PODE_SINALIZAR("iniciar_pode_sinalizar"),
  FIM_MOVIMENTO_SEGMENTO("fim_movimento_segmento"),
  CLASSIFICACAO("classificacao"),
  CONTEXTUALIZACAO("contextualizacao"),
  FRASE_PRIMEIRO_AUDIO("frase_primeiro_audio"),
  FIM_FALA_TEXTO("fim_fala_texto"),
  TEXTO_AVATAR("texto_avatar"),
}

data class MarcaEtapa(val turno: Int, val etapa: Etapa, val ms: Long, val detalhe: String?)

/** Leitura do sistema; nulo = indisponível no aparelho. */
data class LeituraSistema(
    val folgaTermica: Float? = null,
    val estadoTermico: Int? = null,
    val bateriaPct: Int? = null,
    val ramAppMb: Int? = null,
)

data class AmostraMetricas(
    val fpsRecebido: Float,
    val fpsDecodificado: Float,
    val fpsProcessado: Float,
    val pctSemPose: Float,
    val filaCheia: Int,
    val sistema: LeituraSistema,
)

class Metricas(
    private val onMarca: (MarcaEtapa) -> Unit = {},
) {

  companion object {
    /** Tag sem ':' de propósito: `adb logcat -s` não aceita dois-pontos no nome da tag. */
    const val TAG_LATENCIA = "LibrasLatencia"

    fun linhaLog(m: MarcaEtapa): String =
        "turno=${m.turno} etapa=${m.etapa.nome} ms=${m.ms}" + (m.detalhe?.let { " $it" } ?: "")
  }

  private val recebidos = AtomicInteger()
  private val decodificados = AtomicInteger()
  private val processados = AtomicInteger()
  private val semPose = AtomicInteger()

  private var ultimaAmostraMs: Long? = null
  private var anteriores = IntArray(4)

  @Volatile var turno = 0
    private set

  @Volatile private var inicioTurnoMs = 0L
  private val etapasDoTurno = ConcurrentHashMap<Etapa, MarcaEtapa>()

  fun frameRecebido() {
    recebidos.incrementAndGet()
  }

  fun frameDecodificado() {
    decodificados.incrementAndGet()
  }

  fun frameProcessado(comPose: Boolean) {
    processados.incrementAndGet()
    if (!comPose) semPose.incrementAndGet()
  }

  /** Um "iniciar": numera o turno e guarda o instante, para a etapa "iniciar -> pode sinalizar". */
  @Synchronized
  fun novoTurno(agoraMs: Long): Int {
    turno++
    inicioTurnoMs = agoraMs
    etapasDoTurno.clear()
    return turno
  }

  fun marcar(etapa: Etapa, ms: Long, detalhe: String? = null) {
    val marca = MarcaEtapa(turno, etapa, ms, detalhe)
    etapasDoTurno[etapa] = marca
    Log.i(TAG_LATENCIA, linhaLog(marca))
    onMarca(marca)
  }

  /** Marca o tempo desde o "iniciar" do turno atual. */
  fun marcarDesdeOInicio(etapa: Etapa, agoraMs: Long, detalhe: String? = null) =
      marcar(etapa, agoraMs - inicioTurnoMs, detalhe)

  /** As etapas já medidas no turno atual, na ordem do fluxo. */
  fun etapasDoTurnoAtual(): List<MarcaEtapa> = Etapa.entries.mapNotNull { etapasDoTurno[it] }

  /** Fecha uma janela de amostragem: fps pelos contadores desde a amostra anterior. */
  @Synchronized
  fun amostrar(agoraMs: Long, sistema: LeituraSistema, filaCheia: Int): AmostraMetricas {
    val atuais = intArrayOf(recebidos.get(), decodificados.get(), processados.get(), semPose.get())
    val anteriorMs = ultimaAmostraMs
    ultimaAmostraMs = agoraMs
    val delta = IntArray(4) { atuais[it] - anteriores[it] }
    anteriores = atuais
    val segundos = if (anteriorMs == null || agoraMs <= anteriorMs) 0f else (agoraMs - anteriorMs) / 1000f
    fun fps(n: Int) = if (segundos > 0f) n / segundos else 0f
    return AmostraMetricas(
        fpsRecebido = fps(delta[0]),
        fpsDecodificado = fps(delta[1]),
        fpsProcessado = fps(delta[2]),
        pctSemPose = if (delta[2] > 0) 100f * delta[3] / delta[2] else 0f,
        filaCheia = filaCheia,
        sistema = sistema,
    )
  }
}

/*
 * Libras Livre — configurações de demo (docs/prontidao-demo/10-tela.md §10.6).
 *
 * Todos os seletores num lugar só, salvos entre execuções e expostos como StateFlow, com "voltar ao
 * padrão". Os padrões são os decididos nos arquivos do plano; mudar um valor aqui vale a partir do
 * próximo uso (a próxima captura, a próxima escuta, a próxima fala), sem gerar outro APK.
 *
 * Ficam de fora os seletores dos itens P2 (onda 5, não implementada): decodificador de hardware
 * (3.8b), MediaPipe CPU/GPU (3.9), transcrição restrita ao roteiro (5.7), qualidade e fps do stream
 * (7.5), avatar sob demanda (8.2) e esqueleto sobre o preview (10.5b).
 *
 * O armazenamento é uma interface para os padrões e o "voltar ao padrão" serem testáveis na JVM; no
 * app é um SharedPreferences.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.MODELO_CONTEXTUALIZACAO_ATIVO
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoPlaceholder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ParametrosSegmentacao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Motor da wake word (4.5). */
enum class MotorWakeWord {
  SPEECH_RECOGNIZER,
  OPEN_WAKE_WORD,
}

/** Por onde sai a voz do app (5.1). */
enum class SaidaVoz {
  OCULOS,
  CELULAR,
}

/** De onde vem a resposta do atendente (5.2). */
enum class MicrofoneResposta {
  CELULAR,
  OCULOS,
}

data class ValoresDemo(
    // 1.9, 3.8, 2.6
    val gravadorSessao: Boolean = false,
    val painelMetricas: Boolean = false,
    val modoPlaceholder: ModoPlaceholder = ModoPlaceholder.ROTEIRO,
    // 1.8
    val segmentacao: ParametrosSegmentacao = ParametrosSegmentacao(),
    // 4.3
    val tetoCapturaMs: Long = 30_000L,
    val tetoEscutaMs: Long = 20_000L,
    // 4.5, 4.6
    val motorWakeWord: MotorWakeWord = MotorWakeWord.SPEECH_RECOGNIZER,
    val comandoDeVoz: Boolean = true,
    // 5.1, 5.2, 5.3
    val saidaVoz: SaidaVoz = SaidaVoz.OCULOS,
    val microfoneResposta: MicrofoneResposta = MicrofoneResposta.CELULAR,
    val folgaAposFalaMs: Long = 300L,
    // 2.8 — Calibração 2026-09-18 (CSVs ao vivo): a confiança separa certo/errado com um degrau
    // nítido entre ~0.66 (errados) e ~0.76 (certos). 0.60 deixava passar acertos "por sorte" de
    // confiança baixa que saíam errados; 0.72 rejeita esses (viram "repita") e mantém os ≥0.76.
    val limiarConfianca: Float = 0.72f,
    // 8.1: libera o avatar quando a memória disponível cai abaixo de fator × threshold do sistema
    val fatorLimiarMemoria: Float = 1.5f,
    // 9.1
    val tetoTraducaoMs: Long = 5_000L,
    val tetoAnimacaoBaseMs: Long = 3_000L,
    val tetoAnimacaoPorSinalMs: Long = 1_500L,
    // Toggle de DEBUG: liga o modelo neural de contextualização, sob guarda, no lugar do
    // template puro. Nunca exposto pro atendente fora do menu de debug — o modelo tem 11,9%
    // de taxa de invenção medida (Guardas.kt); serve pra coletar dado real sobre quando ele
    // erra, não pra uso em atendimento de verdade.
    val modeloContextualizacaoAtivo: Boolean = MODELO_CONTEXTUALIZACAO_ATIVO,
)

class ConfiguracoesDemo(private val armazenamento: Armazenamento) {

  interface Armazenamento {
    fun ler(chave: String): String?

    fun gravar(chave: String, valor: String)
  }

  private val _valores = MutableStateFlow(carregar())
  val valores: StateFlow<ValoresDemo> = _valores.asStateFlow()

  /**
   * Aplica e grava. Uma combinação inválida (ex.: limiar de saída acima do de entrada) lança
   * IllegalArgumentException ao construir os valores, antes de gravar: nada muda.
   */
  @Synchronized
  fun atualizar(mudar: (ValoresDemo) -> ValoresDemo) {
    val novo = mudar(_valores.value)
    serializar(novo).forEach { (chave, valor) -> armazenamento.gravar(chave, valor) }
    _valores.value = novo
  }

  fun voltarAoPadrao() = atualizar { ValoresDemo() }

  // Valor ausente, ilegível ou inválido cai no padrão, campo a campo, sem quebrar a abertura do app.
  private fun carregar(): ValoresDemo {
    val p = ValoresDemo()
    fun bool(chave: String, padrao: Boolean) = armazenamento.ler(chave)?.toBooleanStrictOrNull() ?: padrao
    fun long(chave: String, padrao: Long) = armazenamento.ler(chave)?.toLongOrNull()?.takeIf { it >= 0 } ?: padrao
    fun float(chave: String, padrao: Float) = armazenamento.ler(chave)?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: padrao
    fun <E : Enum<E>> enum(chave: String, padrao: E, valores: Array<E>) =
        armazenamento.ler(chave)?.let { nome -> valores.firstOrNull { it.name == nome } } ?: padrao

    val s = p.segmentacao
    val segmentacao =
        runCatching {
              ParametrosSegmentacao(
                  limiarEntrada = float(SEG_ENTRADA, s.limiarEntrada),
                  limiarSaida = float(SEG_SAIDA, s.limiarSaida),
                  janelaVelocidadeMs = long(SEG_JANELA, s.janelaVelocidadeMs),
                  alfaSuavizacao = float(SEG_ALFA, s.alfaSuavizacao),
                  pausaMs = long(SEG_PAUSA, s.pausaMs),
                  tetoOclusaoMs = long(SEG_OCLUSAO, s.tetoOclusaoMs),
                  duracaoMinimaMs = long(SEG_MINIMA, s.duracaoMinimaMs),
                  duracaoMaximaMs = long(SEG_MAXIMA, s.duracaoMaximaMs),
                  preRollMs = long(SEG_PREROLL, s.preRollMs),
                  posRollMs = long(SEG_POSROLL, s.posRollMs),
              )
            }
            .getOrDefault(s)
    return ValoresDemo(
        gravadorSessao = bool(GRAVADOR, p.gravadorSessao),
        painelMetricas = bool(PAINEL, p.painelMetricas),
        modoPlaceholder = enum(MODO_PLACEHOLDER, p.modoPlaceholder, ModoPlaceholder.entries.toTypedArray()),
        segmentacao = segmentacao,
        tetoCapturaMs = long(TETO_CAPTURA, p.tetoCapturaMs),
        tetoEscutaMs = long(TETO_ESCUTA, p.tetoEscutaMs),
        motorWakeWord = enum(MOTOR_WAKE_WORD, p.motorWakeWord, MotorWakeWord.entries.toTypedArray()),
        comandoDeVoz = bool(COMANDO_DE_VOZ, p.comandoDeVoz),
        saidaVoz = enum(SAIDA_VOZ, p.saidaVoz, SaidaVoz.entries.toTypedArray()),
        microfoneResposta = enum(MICROFONE, p.microfoneResposta, MicrofoneResposta.entries.toTypedArray()),
        folgaAposFalaMs = long(FOLGA, p.folgaAposFalaMs),
        limiarConfianca = float(LIMIAR_CONFIANCA, p.limiarConfianca).coerceAtMost(1f),
        fatorLimiarMemoria = float(FATOR_MEMORIA, p.fatorLimiarMemoria),
        tetoTraducaoMs = long(TETO_TRADUCAO, p.tetoTraducaoMs),
        tetoAnimacaoBaseMs = long(TETO_ANIMACAO_BASE, p.tetoAnimacaoBaseMs),
        tetoAnimacaoPorSinalMs = long(TETO_ANIMACAO_SINAL, p.tetoAnimacaoPorSinalMs),
        modeloContextualizacaoAtivo = bool(MODELO_CONTEXTUALIZACAO, p.modeloContextualizacaoAtivo),
    )
  }

  private fun serializar(v: ValoresDemo): Map<String, String> =
      mapOf(
          GRAVADOR to v.gravadorSessao.toString(),
          PAINEL to v.painelMetricas.toString(),
          MODO_PLACEHOLDER to v.modoPlaceholder.name,
          SEG_ENTRADA to v.segmentacao.limiarEntrada.toString(),
          SEG_SAIDA to v.segmentacao.limiarSaida.toString(),
          SEG_JANELA to v.segmentacao.janelaVelocidadeMs.toString(),
          SEG_ALFA to v.segmentacao.alfaSuavizacao.toString(),
          SEG_PAUSA to v.segmentacao.pausaMs.toString(),
          SEG_OCLUSAO to v.segmentacao.tetoOclusaoMs.toString(),
          SEG_MINIMA to v.segmentacao.duracaoMinimaMs.toString(),
          SEG_MAXIMA to v.segmentacao.duracaoMaximaMs.toString(),
          SEG_PREROLL to v.segmentacao.preRollMs.toString(),
          SEG_POSROLL to v.segmentacao.posRollMs.toString(),
          TETO_CAPTURA to v.tetoCapturaMs.toString(),
          TETO_ESCUTA to v.tetoEscutaMs.toString(),
          MOTOR_WAKE_WORD to v.motorWakeWord.name,
          COMANDO_DE_VOZ to v.comandoDeVoz.toString(),
          SAIDA_VOZ to v.saidaVoz.name,
          MICROFONE to v.microfoneResposta.name,
          FOLGA to v.folgaAposFalaMs.toString(),
          LIMIAR_CONFIANCA to v.limiarConfianca.toString(),
          FATOR_MEMORIA to v.fatorLimiarMemoria.toString(),
          TETO_TRADUCAO to v.tetoTraducaoMs.toString(),
          TETO_ANIMACAO_BASE to v.tetoAnimacaoBaseMs.toString(),
          TETO_ANIMACAO_SINAL to v.tetoAnimacaoPorSinalMs.toString(),
          MODELO_CONTEXTUALIZACAO to v.modeloContextualizacaoAtivo.toString(),
      )

  companion object {
    private const val ARQUIVO = "configuracoes_demo"
    private const val GRAVADOR = "gravador_sessao"
    private const val PAINEL = "painel_metricas"
    private const val MODO_PLACEHOLDER = "modo_placeholder"
    private const val SEG_ENTRADA = "seg_limiar_entrada"
    private const val SEG_SAIDA = "seg_limiar_saida"
    private const val SEG_JANELA = "seg_janela_velocidade_ms"
    private const val SEG_ALFA = "seg_alfa"
    private const val SEG_PAUSA = "seg_pausa_ms"
    private const val SEG_OCLUSAO = "seg_teto_oclusao_ms"
    private const val SEG_MINIMA = "seg_duracao_minima_ms"
    private const val SEG_MAXIMA = "seg_duracao_maxima_ms"
    private const val SEG_PREROLL = "seg_preroll_ms"
    private const val SEG_POSROLL = "seg_posroll_ms"
    private const val TETO_CAPTURA = "teto_captura_ms"
    private const val TETO_ESCUTA = "teto_escuta_ms"
    private const val MOTOR_WAKE_WORD = "motor_wake_word"
    private const val COMANDO_DE_VOZ = "comando_de_voz"
    private const val SAIDA_VOZ = "saida_voz"
    private const val MICROFONE = "microfone_resposta"
    private const val FOLGA = "folga_apos_fala_ms"
    private const val LIMIAR_CONFIANCA = "limiar_confianca"
    private const val FATOR_MEMORIA = "fator_limiar_memoria"
    private const val TETO_TRADUCAO = "teto_traducao_ms"
    private const val TETO_ANIMACAO_BASE = "teto_animacao_base_ms"
    private const val TETO_ANIMACAO_SINAL = "teto_animacao_por_sinal_ms"
    private const val MODELO_CONTEXTUALIZACAO = "modelo_contextualizacao_ativo"

    @Volatile private var instancia: ConfiguracoesDemo? = null

    /** A instância do app: a tela e o CameraViewModel precisam ver as mesmas mudanças. */
    fun de(context: Context): ConfiguracoesDemo =
        instancia
            ?: synchronized(this) {
              instancia
                  ?: ConfiguracoesDemo(
                          PreferenciasAndroid(
                              context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)))
                      .also { instancia = it }
            }
  }
}

private class PreferenciasAndroid(private val prefs: SharedPreferences) : ConfiguracoesDemo.Armazenamento {
  override fun ler(chave: String): String? = prefs.getString(chave, null)

  override fun gravar(chave: String, valor: String) {
    prefs.edit().putString(chave, valor).apply()
  }
}

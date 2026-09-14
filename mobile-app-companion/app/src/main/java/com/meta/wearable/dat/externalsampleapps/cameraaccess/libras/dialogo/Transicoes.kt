/*
 * Libras Livre — regras de transição do diálogo, sem efeitos (docs/prontidao-demo/04).
 *
 * Funções puras (evento + estado atual -> decisão), para testar na JVM o que antes só se via no
 * aparelho. O DialogOrchestrator continua dono dos efeitos (câmera, fala, escuta, avatar) e consulta
 * estas regras.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

/** O que o botão principal faz (4.7). */
enum class AcaoBotao {
  INICIAR,
  ENCERRAR_CAPTURA,
  OUVIR,
  ENCERRAR_ESCUTA,
  PULAR,
}

/** O texto do botão principal; a tela resolve para uma string. */
enum class RotuloBotao {
  INICIAR,
  ENCERRAR_AGORA,
  FALANDO,
  OUVIR_RESPOSTA,
  TRANSCREVENDO,
  PULAR,
  CONECTE_OS_OCULOS,
  PREPARANDO,
}

data class BotaoPrincipal(val rotulo: RotuloBotao, val acao: AcaoBotao?) {
  val habilitado: Boolean
    get() = acao != null
}

object Transicoes {

  /** ②→③ sozinho depois desta pausa com o detector PARADO — bem acima da pausa entre sinais (500 ms). */
  const val SILENCIO_FIM_FRASE_MS = 2_500L

  /** ② encerra se passar este tempo sem nenhum segmento; o relógio reinicia a cada segmento (4.3). */
  const val TETO_CAPTURA_SEM_SEGMENTO_MS = 30_000L

  /** ⑤ encerra neste teto; o normal é o fim de fala do Vosk (4.1, 4.3). */
  const val TETO_ESCUTA_MS = 20_000L

  /** Espera depois de a fala terminar de tocar, antes de abrir a escuta (5.3). */
  const val FOLGA_APOS_FALA_MS = 300L

  /**
   * Estados em que a wake word ouve: ①, ② e ④ (4.4).
   *
   * Fica FORA do ⑤: o microfone da resposta é o do celular por padrão (5.2), o mesmo que a wake
   * word usa, e os dois disputariam a captura. No ④ não há disputa, porque o Vosk ainda não abriu.
   */
  val ESTADOS_COM_WAKE_WORD: Set<DialogState> =
      setOf(
          DialogState.AGUARDANDO_SINAL,
          DialogState.CAPTURANDO_SINAIS,
          DialogState.AGUARDANDO_RESPOSTA,
      )

  /** [habilitada] é o interruptor "Comando de voz" (4.6): desligado, nenhum estado ouve. */
  fun wakeWordAtiva(estado: DialogState, habilitada: Boolean = true): Boolean =
      habilitada && estado in ESTADOS_COM_WAKE_WORD

  /**
   * ②→③ automático (4.1): com pelo menos um segmento entregue ao classificador e o detector PARADO há
   * [silencioMs]. Sem segmento, uma pausa não encerra nada — a pessoa talvez nem tenha começado.
   */
  fun encerrarCapturaPorSilencio(
      segmentos: Int,
      paradoHaMs: Long,
      silencioMs: Long = SILENCIO_FIM_FRASE_MS,
  ): Boolean = segmentos >= 1 && paradoHaMs >= silencioMs

  /** ⑥ → ⑦ com texto; transcrição vazia volta ao ④, onde o botão vira "Ouvir resposta". */
  fun estadoAposTranscricao(texto: String): DialogState =
      if (texto.isBlank()) DialogState.AGUARDANDO_RESPOSTA else DialogState.GERANDO_AVATAR

  /**
   * Para onde a conversa vai depois do ③, conforme a decisão sobre a frase (2.8, 4.1): falada, a
   * escuta abre sozinha, pulando o ④; "repita" volta ao ② sem passar pelo ⑤; desistência e sessão
   * ignorada voltam ao ①.
   */
  fun estadoAposDecisao(decisao: DecisaoFrase): DialogState =
      when (decisao) {
        is DecisaoFrase.Falar -> DialogState.ESCUTANDO_ATENDENTE
        DecisaoFrase.PedirRepeticao -> DialogState.CAPTURANDO_SINAIS
        DecisaoFrase.Desistir,
        DecisaoFrase.Ignorar -> DialogState.AGUARDANDO_SINAL
      }

  /**
   * O botão principal de cada estado (4.7): o atendente só precisa lembrar que o botão grande faz o
   * próximo passo. Sem óculos disponíveis, o ① não tem como começar a captura.
   */
  fun botaoPrincipal(estado: DialogState, oculosDisponiveis: Boolean, aquecido: Boolean = true): BotaoPrincipal =
      when (estado) {
        DialogState.AGUARDANDO_SINAL ->
            when {
              !oculosDisponiveis -> BotaoPrincipal(RotuloBotao.CONECTE_OS_OCULOS, null)
              // 6.4: habilita quando as etapas 1 a 5 do aquecimento terminaram (com ✓ ou ✗).
              !aquecido -> BotaoPrincipal(RotuloBotao.PREPARANDO, null)
              else -> BotaoPrincipal(RotuloBotao.INICIAR, AcaoBotao.INICIAR)
            }
        DialogState.CAPTURANDO_SINAIS -> BotaoPrincipal(RotuloBotao.ENCERRAR_AGORA, AcaoBotao.ENCERRAR_CAPTURA)
        DialogState.FALANDO -> BotaoPrincipal(RotuloBotao.FALANDO, null)
        DialogState.AGUARDANDO_RESPOSTA -> BotaoPrincipal(RotuloBotao.OUVIR_RESPOSTA, AcaoBotao.OUVIR)
        DialogState.ESCUTANDO_ATENDENTE -> BotaoPrincipal(RotuloBotao.ENCERRAR_AGORA, AcaoBotao.ENCERRAR_ESCUTA)
        DialogState.TRANSCREVENDO -> BotaoPrincipal(RotuloBotao.TRANSCREVENDO, null)
        DialogState.GERANDO_AVATAR -> BotaoPrincipal(RotuloBotao.PULAR, AcaoBotao.PULAR)
      }
}

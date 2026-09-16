/*
 * Libras Livre — a faixa de estado: um aviso por vez, escolhido por prioridade
 * (docs/prontidao-demo/10-tela.md §10.2).
 *
 * Várias partes do app têm algo a dizer ao mesmo tempo (câmera que não subiu, tronco fora do quadro,
 * voz de reserva, celular quente...). A tela mostra UM, sempre no mesmo lugar: bloqueio vence
 * atenção, atenção vence informação, e dentro do mesmo nível vence o mais recente. Função pura.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.Enquadramento
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.EstadoSinalizacao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LibrasState

enum class NivelAviso {
  BLOQUEIO,
  ATENCAO,
  INFORMACAO,
}

/** A origem de cada aviso; o nível vem do plano (tabela do 10.2). */
enum class TipoAviso(val nivel: NivelAviso) {
  CAMERA_NAO_SUBIU(NivelAviso.BLOQUEIO), // 3.4
  STREAM_PAUSADO(NivelAviso.BLOQUEIO), // 3.2
  MODELO_RECUSADO(NivelAviso.BLOQUEIO), // 2.6
  AQUECIMENTO_FALHOU(NivelAviso.BLOQUEIO), // 6.4
  ENQUADRAMENTO(NivelAviso.ATENCAO), // 3.5
  REPITA(NivelAviso.ATENCAO), // 2.8
  VOZ_RESERVA(NivelAviso.ATENCAO), // 5.5
  MIC_OCULOS_INDISPONIVEL(NivelAviso.ATENCAO), // 5.2
  AVATAR_LIBERADO_MEMORIA(NivelAviso.ATENCAO), // 8.1
  CELULAR_QUENTE(NivelAviso.ATENCAO), // 7.3
  ERRO_OCULOS(NivelAviso.ATENCAO), // 3.4: erros do SDK que antes só iam para a snackbar
  PAUSA_LONGA(NivelAviso.ATENCAO), // 3.2: sessão encerrada depois de pausa longa
  ERRO_RECONHECIMENTO(NivelAviso.ATENCAO), // falha ao classificar um segmento
  // [NOVO] docs/confirmacao-e-modo-economia-plano.md §2 — persistente desde o evento de bateria
  // até o fim do app (o SDK não expõe "bateria recuperada"), não só quando "iniciar" é bloqueado
  // (isso também dispara CAMERA_NAO_SUBIU, via FalhaCamera.BATERIA_BAIXA).
  BATERIA_OCULOS_BAIXA(NivelAviso.ATENCAO),
  // [NOVO] docs/consentimento-por-atendimento-plano.md §2.1 — o avatar não animou (nem foi
  // pulado) no momento de ①.5: a explicação do consentimento ficou só na legenda em português,
  // que pode não satisfazer "acessível em Libras" pra quem não lê português fluente. Sinaliza pro
  // atendente considerar bilhete/intérprete; não bloqueia sozinho (decisão em aberto no plano).
  CONSENTIMENTO_SEM_LIBRAS(NivelAviso.ATENCAO),
  // [NOVO] docs/consentimento-por-atendimento-plano.md §2.5 — "Recusar" em ①.5: aviso
  // informativo, não bloqueio (a pessoa pode mudar de ideia no mesmo atendimento). Limpo no
  // início do próximo pedido de consentimento, não persiste entre atendimentos diferentes.
  CONSENTIMENTO_RECUSADO(NivelAviso.INFORMACAO),
  CAPTURA(NivelAviso.INFORMACAO), // 3.1, 1.11
}

data class Aviso(val tipo: TipoAviso, val texto: String, val desdeMs: Long)

object Avisos {

  /** O aviso vigente, ou null se não há nada a dizer. */
  fun escolherAviso(avisos: Collection<Aviso>): Aviso? =
      avisos.minWithOrNull(compareBy<Aviso> { it.tipo.nivel.ordinal }.thenByDescending { it.desdeMs })

  /**
   * O aviso informativo da captura, derivado do estado do reconhecimento (3.1, 1.11): "Aguarde…" até
   * o primeiro frame válido; depois "● sinalizando" / "○ parado" com o número de sinais capturados.
   * O tronco fora do quadro (3.5) é atenção, não informação.
   */
  fun avisosDaCaptura(libras: LibrasState, estado: DialogState, textos: TextosCaptura): List<Aviso> {
    if (estado != DialogState.CAPTURANDO_SINAIS) return emptyList()
    val avisos = mutableListOf<Aviso>()
    val info =
        when {
          !libras.podeSinalizar -> textos.aguarde
          libras.estadoSinalizacao == EstadoSinalizacao.SINALIZANDO -> textos.sinalizando(libras.sinaisNaSessao)
          else -> textos.parado(libras.sinaisNaSessao)
        }
    avisos += Aviso(TipoAviso.CAPTURA, info, desdeMs = 0L)
    when (libras.enquadramento) {
      Enquadramento.TRONCO_FORA -> avisos += Aviso(TipoAviso.ENQUADRAMENTO, textos.troncoFora, desdeMs = 1L)
      Enquadramento.NINGUEM -> avisos += Aviso(TipoAviso.ENQUADRAMENTO, textos.ninguem, desdeMs = 1L)
      Enquadramento.OK -> Unit
    }
    return avisos
  }
}

/** Os textos que a tela resolve a partir das strings; separados para a regra ficar pura. */
data class TextosCaptura(
    val aguarde: String,
    val sinalizando: (Int) -> String,
    val parado: (Int) -> String,
    val troncoFora: String,
    val ninguem: String,
)

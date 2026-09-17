/*
 * Libras Livre — a tela do avatar VLibras (docs/vlibras-webview-plano.md §6, Fase 5).
 *
 * É a única superfície do app voltada para a PESSOA SURDA: todo o resto da tela (preview,
 * botões, banner) serve a quem opera. Por isso ela cobre tudo, o avatar ocupa o espaço que
 * sobra e a legenda fica fixa embaixo.
 *
 * A WebView é do AvatarPlayer, não desta composição — ele a cria antes (durante ②③④⑤⑥, para o
 * ⑦ não pagar os 6-9 s de carga do Unity) e a destrói quando o atendimento encerra. Aqui só
 * anexamos e soltamos; destruir daqui tiraria o avatar de baixo do próximo turno.
 *
 * O padrão de sobreposição é o mesmo do CapturePreviewScreen: um Dialog em tela cheia. Vem
 * de graça daí o botão voltar do sistema fechando a tela — o app não tem NavHost.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.AcaoBotao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.BotaoPrincipal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.AssuntoAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.AvatarState

/**
 * @param webView lida por referência, e não pelo [CameraUiState]: uma View dentro de uma data
 *   class de estado quebra a igualdade e segura contexto. Quem dispara a recomposição é
 *   [estado], que muda exatamente quando a WebView nasce, fica pronta ou morre.
 */
@Composable
fun AvatarScreen(
    estado: AvatarState,
    legenda: String?,
    // De que passo é a [legenda] — o rótulo acima dela. Vem gravado com o texto, e não do
    // DialogState atual: a frase confirmada em ②.5 fica na tela durante ③⑤ e continua sendo dela.
    assunto: AssuntoAvatar?,
    webView: () -> WebView?,
    onFechar: () -> Unit,
    onTentarDeNovo: () -> Unit,
    onPausar: () -> Unit,
    onRetomar: () -> Unit,
    // Botão principal dentro da tela do avatar (docs/prontidao-demo/09 §9.2): "Pular" no ⑦, "Iniciar"
    // depois, "Confirmar" em ②.5 (docs/confirmacao-e-modo-economia-plano.md §1.3). Null esconde o
    // botão.
    botaoPrincipal: BotaoPrincipal? = null,
    onBotaoPrincipal: (AcaoBotao) -> Unit = {},
    modifier: Modifier = Modifier,
    // Libras Livre — confirmação de reconhecimento (docs/confirmacao-e-modo-economia-plano.md
    // §1.3, §1.5): true quando a legenda é a frase que o sistema entendeu do SURDO (②.5), não a
    // resposta do atendente (⑦) — troca o rótulo acima do texto e mostra o botão "Corrigir"
    // pequeno, ao lado do "Confirmar" (que já vem por [botaoPrincipal]/[onBotaoPrincipal]).
    confirmacaoDoSurdo: Boolean = false,
    onCorrigirReconhecimento: () -> Unit = {},
    // Libras Livre — consentimento por atendimento (docs/consentimento-por-atendimento-plano.md
    // §2.2): true em ①.5. Troca o rótulo da legenda e substitui [botaoPrincipal] por dois botões
    // do MESMO peso visual — "Aceitar"/"Recusar" não são um principal + um pequeno, de propósito.
    pedindoConsentimento: Boolean = false,
    onAceitarConsentimento: () -> Unit = {},
    onRecusarConsentimento: () -> Unit = {},
) {
  // Congela o Unity com o app em background — ele desenha a 30 fps mesmo sem ninguém olhando.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> onPausar()
        Lifecycle.Event.ON_RESUME -> onRetomar()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Dialog(
      onDismissRequest = onFechar,
      properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black).testTag("avatar_screen")) {
      Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
          AvatarCanvas(estado = estado, webView = webView)
          AvatarStatus(estado = estado, onTentarDeNovo = onTentarDeNovo)
        }

        Legenda(texto = legenda, assunto = assunto)

        if (pedindoConsentimento) {
          // §2.2 do plano: dois botões do mesmo componente, mesmo tamanho — nenhum é "o padrão".
          Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CapturePill(
                modifier = Modifier.weight(1f).testTag("consentimento_recusar_button"),
                icon = Icons.Filled.Close,
                label = stringResource(R.string.consentimento_recusar),
                contentDescription = stringResource(R.string.consentimento_recusar),
                enabled = true,
                onClick = onRecusarConsentimento,
            )
            CapturePill(
                modifier = Modifier.weight(1f).testTag("consentimento_aceitar_button"),
                icon = Icons.Filled.Check,
                label = stringResource(R.string.consentimento_aceitar),
                contentDescription = stringResource(R.string.consentimento_aceitar),
                enabled = true,
                onClick = onAceitarConsentimento,
            )
          }
        } else {
          botaoPrincipal?.let {
            BotaoPrincipalGrande(
                botao = it,
                onAcao = onBotaoPrincipal,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                tag = "botao_principal_avatar",
            )
          }
          // ②.5: "Corrigir" é um botão pequeno à parte do principal "Confirmar" (§1.3 do plano) —
          // mesmo padrão de "Cancelar atendimento" na tela principal, fora do modelo de botão único.
          if (confirmacaoDoSurdo) {
            CapturePill(
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
                        .testTag("avatar_corrigir_button"),
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.avatar_confirmacao_corrigir),
                contentDescription = stringResource(R.string.avatar_confirmacao_corrigir),
                enabled = true,
                onClick = onCorrigirReconhecimento,
            )
          }
        }
      }

      CircleButton(
          onClick = onFechar,
          modifier =
              Modifier.align(Alignment.TopEnd)
                  .padding(20.dp)
                  .size(44.dp)
                  .testTag("close_avatar_button"),
      ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.avatar_close),
            tint = Color.Black,
        )
      }
    }
  }
}

/**
 * Anexa a WebView do [AvatarState]. Em OCIOSO não há o que anexar — a WebView não existe, e é
 * esse o estado depois de um release().
 */
@Composable
private fun AvatarCanvas(estado: AvatarState, webView: () -> WebView?) {
  if (estado == AvatarState.OCIOSO) return
  val wv = webView() ?: return

  // key(wv): numa retentativa o AvatarPlayer cria uma WebView NOVA. Sem a chave, o AndroidView
  // reaproveitaria o nó antigo e a tela ficaria presa na View destruída.
  key(wv) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        // A View tem dono (o AvatarPlayer) e pode já estar anexada de uma abertura anterior:
        // reanexar sem soltar o pai lança "The specified child already has a parent".
        factory = { (wv.parent as? ViewGroup)?.removeView(wv); wv },
        // Solta, NÃO destrói: o mesmo avatar atende o próximo turno sem recarregar o Unity.
        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
    )
  }
}

/** O que se vê enquanto o avatar não está animando: carga, falha ou espera. */
@Composable
private fun AvatarStatus(estado: AvatarState, onTentarDeNovo: () -> Unit) {
  when (estado) {
    AvatarState.PRONTO,
    AvatarState.ANIMANDO -> Unit
    AvatarState.CARREGANDO ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
          Text(
              text = stringResource(R.string.avatar_loading),
              color = Color.White,
              fontSize = 16.sp,
              modifier = Modifier.padding(top = 16.dp),
          )
        }
    AvatarState.FALHOU ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
          Text(
              text = stringResource(R.string.avatar_failed),
              color = AppColor.Yellow,
              fontSize = 16.sp,
              textAlign = TextAlign.Center,
          )
          Box(modifier = Modifier.padding(top = 20.dp)) {
            CapturePill(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.avatar_retry),
                contentDescription = stringResource(R.string.avatar_retry),
                enabled = true,
                onClick = onTentarDeNovo,
                modifier = Modifier.testTag("avatar_retry_button"),
            )
          }
        }
    AvatarState.OCIOSO ->
        Text(
            text = stringResource(R.string.avatar_waiting),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
  }
}

/**
 * A resposta do atendente por escrito, sempre visível — não só quando o avatar falha. Cobre
 * quem lê português com mais conforto que Libras, serve de registro do turno, e é o caminho
 * degradado sem precisar trocar o layout no pior momento.
 */
@Composable
private fun Legenda(texto: String?, assunto: AssuntoAvatar?) {
  if (texto.isNullOrBlank()) return
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .padding(16.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(Color.White.copy(alpha = 0.12f))
              .padding(horizontal = 20.dp, vertical = 16.dp)
              .testTag("avatar_legenda"),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
        text =
            stringResource(
                when (assunto) {
                  AssuntoAvatar.CONSENTIMENTO -> R.string.avatar_caption_label_consentimento
                  AssuntoAvatar.CONFIRMACAO -> R.string.avatar_caption_label_confirmacao
                  AssuntoAvatar.REPETICAO -> R.string.avatar_caption_label_repeticao
                  AssuntoAvatar.RESPOSTA,
                  null -> R.string.avatar_caption_label
                }
            ),
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
    )
    Text(
        text = texto,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        // Frase longa não pode empurrar o avatar para fora da tela nem ser cortada.
        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
    )
  }
}

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
    webView: () -> WebView?,
    onFechar: () -> Unit,
    onTentarDeNovo: () -> Unit,
    onPausar: () -> Unit,
    onRetomar: () -> Unit,
    modifier: Modifier = Modifier,
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

        Legenda(texto = legenda)
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
private fun Legenda(texto: String?) {
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
        text = stringResource(R.string.avatar_caption_label),
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

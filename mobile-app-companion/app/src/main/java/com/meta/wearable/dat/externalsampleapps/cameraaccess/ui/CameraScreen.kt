/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraScreen - DAT camera capture screen
//
// A full-bleed camera preview with controls overlaid on a scrim. Walks the SDK's camera lifecycle
// as explicit steps (Start Session -> Start Preview -> Capture / Record -> Stop Preview -> End
// Session) and shows the live DeviceSessionState / StreamState so the state machine is legible.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.AmostraMetricas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.MarcaEtapa
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.ResultadoEtapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.StatusEtapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.AcaoBotao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Aviso
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Avisos
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.NivelAviso
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.TextosCaptura
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.TipoAviso
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LandmarkPipeline
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModeloRecusado
import androidx.compose.material3.Switch
import androidx.compose.runtime.saveable.rememberSaveable
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.BotaoPrincipal
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Conversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DecisaoNaConversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.RotuloBotao
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Transicoes
import java.util.Locale
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.TurnoConversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LibrasState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

// Scrims behind the top/bottom bars so the white controls stay legible over the live feed. Hoisted
// so they're allocated once instead of on every bar recomposition (the recording timer ticks).
private val TopScrimBrush =
    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))
private val BottomScrimBrush =
    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))

@Composable
fun CameraScreen(
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    onRequestRecordAudioPermission: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    cameraViewModel: CameraViewModel = viewModel(
        factory =
            CameraViewModel.Factory(
                application = (LocalActivity.current as ComponentActivity).application,
                wearablesViewModel = wearablesViewModel,
            ),
    ),
) {
  val ui by cameraViewModel.uiState.collectAsStateWithLifecycle()
  val wearablesUi by wearablesViewModel.uiState.collectAsStateWithLifecycle()
  val activity = LocalActivity.current
  var showSettingsMenu by remember { mutableStateOf(false) }

  val isUpdateRequired = wearablesUi.isFirmwareUpdateRequired

  // Pede RECORD_AUDIO uma vez, ao abrir a tela, pra destravar o motor real de wake word
  // (SpeechRecognizerWakeWordDetector) sem depender do usuário tocar em "Iniciar"/"Encerrar"
  // primeiro — ver CameraViewModel.enableWakeWordListening. Se negado, os botões de
  // DialogControlRow continuam funcionando como fallback.
  LaunchedEffect(Unit) { cameraViewModel.enableWakeWordListening(onRequestRecordAudioPermission) }

  // Tela ligada enquanto houver sessão com os óculos (docs/prontidao-demo/07 §7.1): o serviço em
  // primeiro plano mantém stream e microfone, mas não a tela, e o painel e o avatar sumiriam no
  // meio da demo. Sem sessão, o sistema volta a apagar no tempo configurado.
  val view = LocalView.current
  DisposableEffect(view, ui.hasSession) {
    view.keepScreenOn = ui.hasSession
    onDispose { view.keepScreenOn = false }
  }

  // 4.7: com sessão, as teclas de volume fazem o mesmo que o botão principal. Os valores são lidos no
  // momento da tecla (rememberUpdatedState), não no momento em que o ouvinte foi registrado.
  val botaoAtual by rememberUpdatedState(Transicoes.botaoPrincipal(ui.dialogState, wearablesUi.hasActiveDevice, ui.aquecido))
  val acionarBotao by rememberUpdatedState { acao: AcaoBotao ->
    cameraViewModel.onBotaoPrincipal(acao, onRequestRecordAudioPermission)
  }
  DisposableEffect(ui.hasSession) {
    if (ui.hasSession) {
      TeclasDeVolume.ouvinte = { primeiroToque ->
        val acao = botaoAtual.acao
        if (acao != null && primeiroToque) acionarBotao(acao)
        acao != null
      }
    }
    onDispose { TeclasDeVolume.ouvinte = null }
  }

  Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
    PreviewBackground(
        ui = ui,
        hasActiveDevice = wearablesUi.hasActiveDevice,
        isUpdateRequired = isUpdateRequired,
        onSurfaceChanged = cameraViewModel::setSurface,
    )

    // Tap outside the open settings menu dismisses it.
    if (showSettingsMenu) {
      Box(
          modifier =
              Modifier.fillMaxSize().clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
              ) {
                showSettingsMenu = false
              }
      )
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopBar(
          ui = ui,
          isDisconnectEnabled = wearablesUi.registrationState == RegistrationState.REGISTERED,
          showSettingsMenu = showSettingsMenu,
          onToggleSettings = { showSettingsMenu = !showSettingsMenu },
          onDisconnect = {
            activity?.let { wearablesViewModel.startUnregistration(it) }
            showSettingsMenu = false
          },
      )

      Spacer(modifier = Modifier.weight(1f))

      BottomBar(
          ui = ui,
          isUpdateRequired = isUpdateRequired,
          hasActiveDevice = wearablesUi.hasActiveDevice,
          onStartSession = cameraViewModel::startSession,
          onEndSession = cameraViewModel::endSession,
          onStartPreview = cameraViewModel::startStreaming,
          onStopPreview = cameraViewModel::stopStreaming,
          onCapturePhoto = cameraViewModel::capturePhoto,
          onToggleRecording = cameraViewModel::toggleRecording,
          onBotaoPrincipal = { acao -> cameraViewModel.onBotaoPrincipal(acao, onRequestRecordAudioPermission) },
          onCancelarAtendimento = cameraViewModel::cancelarAtendimento,
          onAbrirAvatar = cameraViewModel::abrirAvatar,
          onComandoDeVoz = cameraViewModel::definirComandoDeVoz,
          onUpdateFirmware = { activity?.let { wearablesViewModel.openFirmwareUpdate(it) } },
      )
    }

    // Libras Livre: estado da captura e painel de conversa (docs/prontidao-demo/10-tela.md §10.1).
    // Fora de qualquer condição do stream, de propósito: o resultado continua visível depois que a
    // câmera desliga, que é justamente quando a frase é falada e a resposta chega.
    Column(
        modifier =
            Modifier.align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 84.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 10.3: faixa de estado (médio), aquecimento e painel de conversa (grande).
      CartaoClassificador(ui.classificador, ui.limiarClassificador)
      FaixaDeEstado(ui = ui)
      CartaoAquecimento(resultados = ui.aquecimento, pronto = ui.aquecido)
      PainelConversa(conversa = ui.conversa)
      if (ui.painelMetricas) {
        PainelMetricas(amostra = ui.metricas, etapas = ui.etapasTurno, arquivoGravacao = ui.arquivoGravacao)
      }
    }

    ui.activePreview?.let { preview ->
      CapturePreviewScreen(
          preview = preview,
          onDismiss = { cameraViewModel.dismissCapturePreview() },
      )
    }

    // Libras Livre: a tela do avatar (estado ⑦). Abre sozinha quando a resposta do atendente
    // chega — ver CameraViewModel.playAvatar — e fica aberta até o operador fechar ou o
    // atendimento encerrar por inatividade, para a WebView não ser reanexada a cada turno.
    if (ui.avatarVisivel) {
      AvatarScreen(
          estado = ui.avatarState,
          legenda = ui.avatarLegenda,
          assunto = ui.avatarAssunto,
          webView = { cameraViewModel.avatarView },
          onFechar = cameraViewModel::fecharAvatar,
          onTentarDeNovo = cameraViewModel::abrirAvatar,
          onPausar = cameraViewModel::pausarAvatar,
          onRetomar = cameraViewModel::retomarAvatar,
          // 9.2: o botão principal também dentro da tela do avatar — "Pular" enquanto anima,
          // "Iniciar" depois (esconde a tela e começa a captura), "Confirmar" em ②.5
          // (docs/confirmacao-e-modo-economia-plano.md §1.3).
          botaoPrincipal = Transicoes.botaoPrincipal(ui.dialogState, wearablesUi.hasActiveDevice, ui.aquecido),
          onBotaoPrincipal = { acao ->
            if (acao == AcaoBotao.INICIAR) cameraViewModel.iniciarPeloAvatar()
            else cameraViewModel.onBotaoPrincipal(acao, onRequestRecordAudioPermission)
          },
          // ②.5: a legenda em tela é a frase que o SISTEMA ENTENDEU do surdo, não a resposta do
          // atendente — troca o rótulo e mostra "Corrigir" ao lado do "Confirmar" (botaoPrincipal).
          confirmacaoDoSurdo = ui.dialogState == DialogState.CONFIRMANDO_RECONHECIMENTO,
          onCorrigirReconhecimento = cameraViewModel::corrigirReconhecimento,
          // ①.5 (docs/consentimento-por-atendimento-plano.md §2.2): troca o rótulo e substitui
          // botaoPrincipal por "Aceitar"/"Recusar" de peso igual — nunca captura sem consentimento.
          pedindoConsentimento = ui.dialogState == DialogState.PEDINDO_CONSENTIMENTO,
          onAceitarConsentimento = cameraViewModel::aceitarConsentimento,
          onRecusarConsentimento = cameraViewModel::recusarConsentimento,
      )
    }

    if (ui.showCameraPermissionRedirectConfirm) {
      AlertDialog(
          onDismissRequest = { cameraViewModel.cancelCameraPermissionRedirect() },
          title = { Text(stringResource(R.string.camera_permission_redirect_title)) },
          text = { Text(stringResource(R.string.camera_permission_redirect_message)) },
          confirmButton = {
            TextButton(
                onClick = {
                  cameraViewModel.confirmCameraPermissionRedirect(onRequestWearablesPermission)
                }
            ) {
              Text(stringResource(R.string.camera_permission_continue))
            }
          },
          dismissButton = {
            TextButton(onClick = { cameraViewModel.cancelCameraPermissionRedirect() }) {
              Text(stringResource(R.string.camera_permission_cancel))
            }
          },
      )
    }
  }
}

// MARK: - Preview background

@Composable
private fun PreviewBackground(
    ui: CameraUiState,
    hasActiveDevice: Boolean,
    isUpdateRequired: Boolean,
    onSurfaceChanged: (android.view.Surface?) -> Unit,
) {
  val liveDescription = stringResource(R.string.live_preview)
  Box(modifier = Modifier.fillMaxSize()) {
    // 10.5: o preview só aparece durante a captura (e no preview manual dos controles da sessão, no
    // ①). Fora disso — fala, escuta, avatar —, o stream está desligando e a tela é do painel.
    val mostrarPreview =
        ui.hasStream &&
            (ui.dialogState == DialogState.CAPTURANDO_SINAIS || ui.dialogState == DialogState.AGUARDANDO_SINAL)
    if (mostrarPreview) {
      // The decoder renders into this Surface. AndroidExternalSurface is Compose's native,
      // SurfaceView-backed sink — drawn behind (default zOrder) so the scrim and controls
      // composite on top.
      AndroidExternalSurface(
          modifier = Modifier.fillMaxSize().semantics { contentDescription = liveDescription }
      ) {
        onSurface { surface, _, _ ->
          onSurfaceChanged(surface)
          surface.onDestroyed { onSurfaceChanged(null) }
        }
      }
    } else if (!ui.isBusy && !ui.hasSession) {
      // 10.3: com a sessão aberta, o centro da tela é do painel de conversa; o aviso do sample
      // ("Session started / Start the preview…") ficava por baixo do painel e do botão principal.
      StatusPlaceholder(
          ui = ui,
          hasActiveDevice = hasActiveDevice,
          isUpdateRequired = isUpdateRequired,
      )
    }

    // Paused (device-initiated): the surface stays mounted (hasStream is true) so the last frame
    // freezes; dim it and badge it so a held frame reads as intentionally paused, not stalled.
    if (ui.isPaused) {
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.35f))
                  .testTag("paused_overlay"),
          contentAlignment = Alignment.Center,
      ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Icon(
              imageVector = Icons.Filled.Pause,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(36.dp),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.paused_title),
              color = Color.White,
              fontSize = 20.sp,
              fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = stringResource(R.string.paused_subtitle),
              color = Color.White.copy(alpha = 0.7f),
              fontSize = 15.sp,
              textAlign = TextAlign.Center,
          )
        }
      }
    }

    if ((ui.isBusy || (ui.hasStream && !ui.hasReceivedFirstFrame)) && !ui.isPaused) {
      Box(
          modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
          contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(color = Color.White)
      }
    }
  }
}

@Composable
private fun StatusPlaceholder(
    ui: CameraUiState,
    hasActiveDevice: Boolean,
    isUpdateRequired: Boolean,
) {
  val title: String
  val subtitle: String?
  val showWaitingRow: Boolean
  when {
    !hasActiveDevice -> {
      title = stringResource(R.string.placeholder_put_on_glasses)
      subtitle = null
      showWaitingRow = true
    }
    isUpdateRequired -> {
      title = stringResource(R.string.update_required_title)
      subtitle = stringResource(R.string.update_required_subtitle)
      showWaitingRow = false
    }
    !ui.hasSession -> {
      title = stringResource(R.string.placeholder_ready_title)
      subtitle = stringResource(R.string.placeholder_ready_subtitle)
      showWaitingRow = false
    }
    else -> return
  }

  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Icon(
        painter = painterResource(id = R.drawable.camera_access_icon),
        contentDescription = stringResource(R.string.camera_access_icon_description),
        tint = Color.White,
        modifier = Modifier.size(88.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
    }
    if (showWaitingRow) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
          text = stringResource(R.string.waiting_for_active_device),
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 14.sp,
      )
    }
  }
}

// MARK: - Top bar

@Composable
private fun TopBar(
    ui: CameraUiState,
    isDisconnectEnabled: Boolean,
    showSettingsMenu: Boolean,
    onToggleSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .background(TopScrimBrush)
              .statusBarsPadding()
              .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      StatusChip(
          label = stringResource(R.string.status_session),
          value = ui.sessionStateText,
          active = ui.isSessionActive,
          present = ui.hasSession,
      )
      StatusChip(
          label = stringResource(R.string.status_stream),
          value = ui.streamStateText,
          active = ui.isStreaming,
          present = ui.hasStream,
      )
    }

    Spacer(modifier = Modifier.weight(1f))

    Box {
      // Pinned to TopEnd so it stays put when the Disconnect popover below widens this Box.
      Icon(
          imageVector = Icons.Filled.LinkOff,
          contentDescription = stringResource(R.string.unregister_button_title),
          tint = Color.White,
          modifier =
              Modifier.align(Alignment.TopEnd).size(28.dp).clickable(onClick = onToggleSettings),
      )
      if (showSettingsMenu) {
        SwitchButton(
            label = stringResource(R.string.unregister_button_title),
            onClick = onDisconnect,
            modifier = Modifier.align(Alignment.TopEnd).offset(y = 40.dp).width(150.dp),
            isDestructive = true,
            enabled = isDisconnectEnabled,
        )
      }
    }
  }
}

@Composable
private fun StatusChip(label: String, value: String, active: Boolean, present: Boolean) {
  val dotColor = if (active) AppColor.Green else if (present) AppColor.Yellow else Color.Gray
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
    Text(
        text = "$label: $value",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
    )
  }
}

// MARK: - Bottom bar

@Composable
private fun BottomBar(
    ui: CameraUiState,
    isUpdateRequired: Boolean,
    hasActiveDevice: Boolean,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    onStartPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onBotaoPrincipal: (AcaoBotao) -> Unit,
    onCancelarAtendimento: () -> Unit,
    onAbrirAvatar: () -> Unit,
    onComandoDeVoz: (Boolean) -> Unit,
    onUpdateFirmware: () -> Unit,
) {
  // 10.3: os controles do sample (sessão, preview, foto, gravação) ficam numa área recolhível; o
  // atendente só precisa do botão principal.
  var controlesAbertos by rememberSaveable { mutableStateOf(false) }
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .background(BottomScrimBrush)
              .navigationBarsPadding()
              .padding(horizontal = 24.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (isUpdateRequired) {
      UpdateRequiredMessage()
      SwitchButton(
          label = stringResource(R.string.update_firmware_button_title),
          onClick = onUpdateFirmware,
      )
    } else {
      ControlesDoAtendimento(
          ui = ui,
          oculosDisponiveis = hasActiveDevice,
          onBotaoPrincipal = onBotaoPrincipal,
          onCancelarAtendimento = onCancelarAtendimento,
          onAbrirAvatar = onAbrirAvatar,
          onComandoDeVoz = onComandoDeVoz,
      )
      TextButton(
          onClick = { controlesAbertos = !controlesAbertos },
          modifier = Modifier.align(Alignment.CenterHorizontally).testTag("mais_controles"),
      ) {
        Text(
            text = stringResource(R.string.mais_controles) + if (controlesAbertos) " ▴" else " ▾",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
        )
      }
      if (controlesAbertos) {
        CaptureRow(
            ui = ui,
            onStartPreview = onStartPreview,
            onStopPreview = onStopPreview,
            onCapturePhoto = onCapturePhoto,
            onToggleRecording = onToggleRecording,
        )
        AnchoredPrimaryButton(
            ui = ui,
            hasActiveDevice = hasActiveDevice,
            onStartSession = onStartSession,
            onEndSession = onEndSession,
        )
      }
    }
  }
}

@Composable
private fun CaptureRow(
    ui: CameraUiState,
    onStartPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
) {
  // previewActive mirrors iOS `previewIsActive`: live, recording, or tearing down. PAUSED is
  // excluded, so while paused the pill reverts to the (inert) start affordance instead of a live
  // stop button — the paused stream can't be torn down from here.
  val previewActive = ui.isStreaming || ui.isRecording || ui.streamState == StreamState.STOPPING
  val previewDisabled =
      if (previewActive) ui.isRecording || ui.isBusy else !ui.isSessionActive || ui.isBusy
  val captureEnabled = ui.isStreaming
  val recordEnabled = ui.isStreaming || ui.isRecording

  Row(
      // 10.4: sem sessão, os botões aparecem desabilitados; nenhum controle invisível responde ao toque.
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Preview pill (gateway).
    CapturePill(
        modifier =
            Modifier.weight(1f)
                .testTag(if (previewActive) "stop_preview_button" else "start_preview_button"),
        icon = if (previewActive) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
        label = stringResource(R.string.preview_label),
        contentDescription =
            if (previewActive) stringResource(R.string.stop_preview)
            else stringResource(R.string.start_preview),
        enabled = !previewDisabled,
        onClick = { if (previewActive) onStopPreview() else onStartPreview() },
    )

    // Photo capture.
    CircleIconButton(
        modifier = Modifier.testTag("capture_button"),
        icon = Icons.Filled.PhotoCamera,
        contentDescription = stringResource(R.string.capture_photo),
        enabled = captureEnabled,
        onClick = onCapturePhoto,
    )

    // Record / stop, morphing into a live timer.
    RecordPill(
        modifier = Modifier.weight(1f).testTag("record_button"),
        isRecording = ui.isRecording,
        elapsedSeconds = ui.recordingElapsedSeconds,
        enabled = recordEnabled,
        onClick = onToggleRecording,
    )
  }
}

@Composable
private fun AnchoredPrimaryButton(
    ui: CameraUiState,
    hasActiveDevice: Boolean,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
) {
  // One persistent button so it holds a fixed Y — only its label/style/action change. Start needs
  // an active device; End stays available mid-stream (the SDK cascades the stop), so only an
  // in-flight transition disables it.
  val hasSession = ui.hasSession
  val enabled = if (hasSession) !ui.isBusy else !ui.isBusy && hasActiveDevice
  SwitchButton(
      label =
          if (hasSession) stringResource(R.string.end_session_button)
          else stringResource(R.string.start_session_button),
      onClick = { if (hasSession) onEndSession() else onStartSession() },
      modifier = Modifier.testTag(if (hasSession) "end_session_button" else "start_session_button"),
      isDestructive = hasSession,
      enabled = enabled,
  )
}

@Composable
// Sem `private`: o AvatarScreen reusa a mesma pílula, para o botão de retentativa ter o mesmo
// peso visual dos botões de diálogo.
internal fun CapturePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier =
          modifier
              .height(50.dp)
              .clip(RoundedCornerShape(percent = 50))
              .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
              .clickable(enabled = enabled, onClick = onClick)
              .semantics { this.contentDescription = contentDescription }
              .padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
        modifier = Modifier.size(20.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        text = label,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun RecordPill(
    isRecording: Boolean,
    elapsedSeconds: Long,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val background =
      if (isRecording) AppColor.RecordAccent.copy(alpha = 0.5f)
      else Color.White.copy(alpha = if (enabled) 0.18f else 0.08f)
  Row(
      modifier =
          modifier
              .height(50.dp)
              .clip(RoundedCornerShape(percent = 50))
              .background(background)
              .clickable(enabled = enabled, onClick = onClick),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isRecording) {
      Icon(
          imageVector = Icons.Filled.Stop,
          contentDescription = stringResource(R.string.stop_recording),
          tint = Color.White,
          modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      val minutes = elapsedSeconds / 60
      val seconds = elapsedSeconds % 60
      Text(
          text = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds),
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.testTag("recording_indicator"),
      )
    } else {
      Icon(
          imageVector = Icons.Filled.Videocam,
          contentDescription = stringResource(R.string.record_video),
          tint = if (enabled) AppColor.RecordAccent else Color.White.copy(alpha = 0.45f),
          modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
          text = stringResource(R.string.record_label),
          color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
  Box(
      modifier =
          modifier
              .size(50.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
              .clickable(enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (enabled) tint else Color.White.copy(alpha = 0.45f),
        modifier = Modifier.size(22.dp),
    )
  }
}

// MARK: - Libras Livre

/**
 * Controles do atendimento (docs/prontidao-demo/04 §4.7): o botão principal grande faz o próximo
 * passo de cada estado; "Cancelar atendimento" fica pequeno e separado; o avatar pode ser aberto a
 * qualquer momento. Sempre visíveis: sem óculos, o botão aparece desabilitado com o motivo, em vez de
 * um controle invisível e clicável (10.4).
 */
@Composable
private fun ControlesDoAtendimento(
    ui: CameraUiState,
    oculosDisponiveis: Boolean,
    onBotaoPrincipal: (AcaoBotao) -> Unit,
    onCancelarAtendimento: () -> Unit,
    onAbrirAvatar: () -> Unit,
    onComandoDeVoz: (Boolean) -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    BotaoPrincipalGrande(
        botao = Transicoes.botaoPrincipal(ui.dialogState, oculosDisponiveis, ui.aquecido),
        onAcao = onBotaoPrincipal,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      CapturePill(
          modifier = Modifier.weight(1f).testTag("cancelar_atendimento_button"),
          icon = Icons.Filled.Close,
          label = stringResource(R.string.cancelar_atendimento),
          contentDescription = stringResource(R.string.cancelar_atendimento),
          enabled = ui.dialogState != DialogState.AGUARDANDO_SINAL || ui.avatarVisivel || ui.hasStream,
          onClick = onCancelarAtendimento,
      )
      // Abrir o avatar é ação do operador, não passo da máquina de estados: conferir o avatar antes
      // do atendimento e reabrir a tela depois de a ter fechado.
      CapturePill(
          modifier = Modifier.weight(1f).testTag("avatar_button"),
          icon = Icons.Filled.Accessibility,
          label = stringResource(R.string.avatar_open),
          contentDescription = stringResource(R.string.avatar_open),
          enabled = true,
          onClick = onAbrirAvatar,
      )
    }
    // 4.6: "Comando de voz", perto do botão principal, salvo nas configurações de demo.
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = stringResource(R.string.comando_de_voz), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
      Spacer(modifier = Modifier.width(8.dp))
      Switch(checked = ui.comandoDeVoz, onCheckedChange = onComandoDeVoz, modifier = Modifier.testTag("comando_de_voz"))
    }
  }
}

/** O botão principal (4.7), no mesmo lugar sempre; também usado dentro da tela do avatar (9.2). */
@Composable
internal fun BotaoPrincipalGrande(
    botao: BotaoPrincipal,
    onAcao: (AcaoBotao) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "botao_principal",
) {
  val rotulo = stringResource(rotuloDoBotao(botao.rotulo))
  Box(
      modifier =
          modifier
              .testTag(tag)
              .height(64.dp)
              .clip(RoundedCornerShape(percent = 50))
              .background(if (botao.habilitado) AppColor.Green else Color.White.copy(alpha = 0.12f))
              .clickable(enabled = botao.habilitado) { botao.acao?.let(onAcao) }
              .semantics { contentDescription = rotulo },
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = rotulo,
        color = if (botao.habilitado) Color.Black else Color.White.copy(alpha = 0.6f),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
    )
  }
}

@StringRes
internal fun rotuloDoBotao(rotulo: RotuloBotao): Int =
    when (rotulo) {
      RotuloBotao.INICIAR -> R.string.botao_iniciar
      RotuloBotao.ENCERRAR_AGORA -> R.string.botao_encerrar_agora
      RotuloBotao.FALANDO -> R.string.botao_falando
      RotuloBotao.OUVIR_RESPOSTA -> R.string.botao_ouvir_resposta
      RotuloBotao.TRANSCREVENDO -> R.string.botao_transcrevendo
      RotuloBotao.PULAR -> R.string.avatar_pular
      RotuloBotao.CONFIRMAR -> R.string.avatar_confirmacao_confirmar
      RotuloBotao.REPETIR -> R.string.botao_repetir
      // Inerte (ação null, §2.2 do plano de consentimento) — nunca chega a aparecer como texto
      // clicável, mas precisa de um recurso pra rotuloDoEstado/depuração não quebrarem.
      RotuloBotao.CONSENTIMENTO_PENDENTE -> R.string.botao_consentimento_pendente
      RotuloBotao.CONECTE_OS_OCULOS -> R.string.botao_conecte_oculos
      RotuloBotao.PREPARANDO -> R.string.botao_preparando
    }

/**
 * Faixa de estado (docs/prontidao-demo/10-tela.md §10.2): um aviso por vez, sempre no mesmo lugar,
 * escolhido por prioridade entre os avisos ativos, os indicadores da captura (3.1, 3.5, 1.11) e os erros
 * do reconhecimento. Ao lado, o estado do diálogo em português.
 */
@Composable
private fun FaixaDeEstado(ui: CameraUiState, modifier: Modifier = Modifier) {
  val textos =
      TextosCaptura(
          aguarde = stringResource(R.string.aviso_aguarde),
          sinalizando = { n -> "● sinalizando · $n sinais" },
          parado = { n -> "○ parado · $n sinais" },
          troncoFora = stringResource(R.string.aviso_tronco_fora),
          ninguem = stringResource(R.string.aviso_ninguem),
      )
  val sinalizando = stringResource(R.string.aviso_sinalizando, ui.libras.sinaisNaSessao)
  val parado = stringResource(R.string.aviso_parado, ui.libras.sinaisNaSessao)
  val candidatos = buildList {
    addAll(ui.avisos.values)
    addAll(Avisos.avisosDaCaptura(ui.libras, ui.dialogState, textos.copy(sinalizando = { sinalizando }, parado = { parado })))
    ui.libras.error?.let { erro ->
      val tipo =
          when {
            erro.startsWith(ModeloRecusado.PREFIXO) -> TipoAviso.MODELO_RECUSADO
            erro == LandmarkPipeline.ERRO_MODELOS -> TipoAviso.AQUECIMENTO_FALHOU
            else -> TipoAviso.ERRO_RECONHECIMENTO
          }
      add(Aviso(tipo, erro, desdeMs = 0L))
    }
  }
  val aviso = Avisos.escolherAviso(candidatos)
  Row(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = stringResource(rotuloDoEstado(ui.dialogState)),
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier.clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("estado_dialogo"),
    )
    if (aviso != null) {
      val cor =
          when (aviso.tipo.nivel) {
            NivelAviso.BLOQUEIO -> Color(0xFFFF6B6B)
            NivelAviso.ATENCAO -> AppColor.Yellow
            NivelAviso.INFORMACAO ->
                if (ui.libras.podeSinalizar) AppColor.Green else Color.White
          }
      Text(
          text = aviso.texto,
          color = cor,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          modifier =
              Modifier.weight(1f)
                  .clip(RoundedCornerShape(12.dp))
                  .background(Color.Black.copy(alpha = 0.6f))
                  .padding(horizontal = 12.dp, vertical = 8.dp)
                  .testTag("faixa_de_estado"),
      )
    }
  }
}

@StringRes
internal fun rotuloDoEstado(estado: DialogState): Int =
    when (estado) {
      DialogState.AGUARDANDO_SINAL -> R.string.estado_aguardando_sinal
      DialogState.PEDINDO_CONSENTIMENTO -> R.string.estado_pedindo_consentimento
      DialogState.CAPTURANDO_SINAIS -> R.string.estado_capturando
      DialogState.CONFIRMANDO_RECONHECIMENTO -> R.string.estado_confirmando
      DialogState.FALANDO -> R.string.estado_falando
      DialogState.PEDINDO_REPETICAO -> R.string.estado_pedindo_repeticao
      DialogState.AGUARDANDO_RESPOSTA -> R.string.estado_aguardando_resposta
      DialogState.ESCUTANDO_ATENDENTE -> R.string.estado_ouvindo
      DialogState.TRANSCREVENDO -> R.string.estado_transcrevendo
      DialogState.GERANDO_AVATAR -> R.string.estado_avatar
    }

/**
 * Aquecimento (docs/prontidao-demo/06-latencia.md §6.4): a lista ✓/✗ enquanto prepara, que vira um
 * resumo recolhível no fim.
 */
@Composable
private fun CartaoAquecimento(resultados: List<ResultadoEtapa>, pronto: Boolean) {
  if (resultados.isEmpty()) return
  val terminou = resultados.none { it.status == StatusEtapa.PENDENTE || it.status == StatusEtapa.EXECUTANDO }
  var aberto by rememberSaveable { mutableStateOf(true) }
  // Recolhe sozinho uma vez, quando tudo termina.
  LaunchedEffect(terminou) { if (terminou) aberto = false }
  val falhas = resultados.count { it.status == StatusEtapa.FALHOU }
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(Color.Black.copy(alpha = 0.6f))
              .clickable { aberto = !aberto }
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("cartao_aquecimento"),
  ) {
    Text(
        text =
            when {
              !terminou -> stringResource(R.string.aquecimento_titulo)
              falhas == 0 -> stringResource(R.string.aquecimento_resumo_ok)
              else -> stringResource(R.string.aquecimento_resumo_falhas, falhas)
            },
        color = if (falhas == 0) Color.White else AppColor.Yellow,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (aberto) {
      resultados.forEach { r ->
        val marca =
            when (r.status) {
              StatusEtapa.OK -> "✓"
              StatusEtapa.FALHOU -> "✗"
              StatusEtapa.EXECUTANDO -> "…"
              StatusEtapa.PENDENTE -> "·"
            }
        val tempo = r.ms?.let { " · $it ms" } ?: ""
        val motivo = r.motivo?.let { " — $it" } ?: ""
        Text(
            text = "$marca ${r.nome}$tempo$motivo",
            color = if (r.status == StatusEtapa.FALHOU) AppColor.Yellow else Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

/**
 * Painel de conversa (docs/prontidao-demo/10-tela.md §10.1): o turno atual em destaque e os
 * anteriores menores. É onde a banca vê o que o app entendeu, o que falou e o que ouviu.
 */
@Composable
private fun PainelConversa(conversa: Conversa, modifier: Modifier = Modifier) {
  if (conversa.turnos.none { it.temConteudo }) return
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .background(Color.Black.copy(alpha = 0.6f))
              .padding(horizontal = 16.dp, vertical = 12.dp)
              .testTag("painel_conversa"),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    for (turno in conversa.anteriores.filter { it.temConteudo }) TurnoNoPainel(turno, destaque = false)
    conversa.atual?.takeIf { it.temConteudo }?.let { TurnoNoPainel(it, destaque = true) }
  }
}

@Composable
private fun TurnoNoPainel(turno: TurnoConversa, destaque: Boolean) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (turno.sinais.isNotEmpty()) {
      // Abaixo do limiar em amarelo (2.8); fora do léxico riscado, porque não é falado (2.5).
      val sinais = buildAnnotatedString {
        turno.sinais.forEachIndexed { i, s ->
          if (i > 0) append(" · ")
          val estilo =
              when {
                s.foraDoLexico -> SpanStyle(color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                s.abaixoDoLimiar -> SpanStyle(color = AppColor.Yellow)
                else -> SpanStyle()
              }
          withStyle(estilo) { append(TurnoConversa.textoDoSinal(s)) }
        }
      }
      LinhaDoPainel(R.string.conversa_sinais, sinais, destaque)
    }
    when (turno.decisao) {
      DecisaoNaConversa.REPITA ->
          LinhaDoPainel(R.string.conversa_decisao, AnnotatedString(stringResource(R.string.conversa_decisao_repita)), destaque, AppColor.Yellow)
      DecisaoNaConversa.DESISTIU ->
          LinhaDoPainel(R.string.conversa_decisao, AnnotatedString(stringResource(R.string.conversa_decisao_desistiu)), destaque, AppColor.Yellow)
      else -> Unit
    }
    turno.falado?.let { LinhaDoPainel(R.string.conversa_falado, AnnotatedString("“$it”"), destaque) }
    turno.resposta?.let { LinhaDoPainel(R.string.conversa_resposta, AnnotatedString("“$it”"), destaque) }
  }
}

/**
 * Painel de métricas (docs/prontidao-demo/03 §3.8, 06 §6.5): uma amostra por segundo e a tabela de
 * tempos do último turno. Ligado pelas configurações de demo; é ferramenta de medição, não de palco.
 */
@Composable
private fun PainelMetricas(amostra: AmostraMetricas?, etapas: List<MarcaEtapa>, arquivoGravacao: String?) {
  val linhas = buildList {
    if (amostra == null) {
      add("medindo…")
    } else {
      add("fps óculos %.1f · decod %.1f · proc %.1f".format(Locale.ROOT, amostra.fpsRecebido, amostra.fpsDecodificado, amostra.fpsProcessado))
      add("sem pose %.0f%% · fila cheia %d".format(Locale.ROOT, amostra.pctSemPose, amostra.filaCheia))
      val s = amostra.sistema
      add(
          "térmico ${s.estadoTermico ?: "—"} (folga ${s.folgaTermica?.let { "%.2f".format(Locale.ROOT, it) } ?: "—"}) · " +
              "bateria ${s.bateriaPct?.let { "$it%" } ?: "—"} · RAM ${s.ramAppMb?.let { "$it MB" } ?: "—"}")
    }
    etapas.forEach { add("${it.etapa.nome}: ${it.ms} ms${it.detalhe?.let { d -> " ($d)" } ?: ""}") }
    arquivoGravacao?.let { add("gravando $it") }
  }
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(Color.Black.copy(alpha = 0.7f))
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("painel_metricas"),
  ) {
    linhas.forEach { Text(text = it, color = AppColor.Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
  }
}

@Composable
private fun LinhaDoPainel(@StringRes rotulo: Int, texto: AnnotatedString, destaque: Boolean, cor: Color = Color.White) {
  val alpha = if (destaque) 1f else 0.6f
  Row(verticalAlignment = Alignment.Top) {
    Text(
        text = stringResource(rotulo),
        color = Color.White.copy(alpha = 0.6f * alpha),
        fontSize = if (destaque) 13.sp else 11.sp,
        modifier = Modifier.width(76.dp).padding(top = if (destaque) 4.dp else 1.dp),
    )
    Text(
        text = texto,
        color = cor.copy(alpha = alpha),
        fontSize = if (destaque) 20.sp else 14.sp,
        fontWeight = if (destaque) FontWeight.SemiBold else FontWeight.Normal,
    )
  }
}

@Composable
private fun UpdateRequiredMessage(modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(20.dp))
              .background(AppColor.UpdateRequiredBackground)
              .padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = null,
        tint = AppColor.UpdateRequiredForeground,
        modifier = Modifier.size(24.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
          text = stringResource(R.string.update_required_title),
          color = AppColor.UpdateRequiredForeground,
          fontWeight = FontWeight.SemiBold,
          fontSize = 16.sp,
      )
      Text(
          text = stringResource(R.string.update_required_firmware_message),
          color = AppColor.UpdateRequiredForeground,
          fontSize = 15.sp,
      )
    }
  }
}

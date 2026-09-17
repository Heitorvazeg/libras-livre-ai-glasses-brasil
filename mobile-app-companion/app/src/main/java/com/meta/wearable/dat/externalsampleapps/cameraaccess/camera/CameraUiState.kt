/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraUiState - Camera screen state
//
// Stores the SDK's own DeviceSessionState / StreamState directly and derives booleans from
// them, so the sample surfaces the real SDK state machine rather than a remapped one.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import android.graphics.Bitmap
import android.net.Uri
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.AvatarState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.AmostraMetricas
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.MarcaEtapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.ResultadoEtapa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Aviso
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.Conversa
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.TipoAviso
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo.DialogState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LibrasState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.DiagnosticoClassificador

/**
 * Libras Livre — de que passo é o texto que está na legenda do avatar, e portanto qual rótulo vai
 * acima dele. Fica GRAVADO junto com a legenda, em vez de ser derivado do [DialogState] na hora de
 * desenhar: os dois andam juntos só enquanto o avatar está no ar, e o texto sobrevive à mudança de
 * estado (a frase confirmada em ②.5 continua na tela durante ③⑤, e passaria a ser anunciada como
 * "O atendente disse").
 */
enum class AssuntoAvatar {
  /** ①.5 — a explicação do consentimento. */
  CONSENTIMENTO,
  /** ②.5 — a frase que o sistema entendeu de quem sinalizou. */
  CONFIRMACAO,
  /** ③.5 — o pedido de repetição. */
  REPETICAO,
  /** ⑦ — a resposta do atendente. */
  RESPOSTA,
}

/** A capture awaiting preview/share — a still photo or a recorded video file. */
sealed interface CapturePreview {
  data class Photo(val bitmap: Bitmap) : CapturePreview

  data class Video(val uri: Uri) : CapturePreview
}

data class CameraUiState(
    // Bound directly to the SDK state machines.
    // Libras Livre — avatar VLibras (docs/vlibras-webview-plano.md). Carrega escondido durante
    // a conversa e só aparece no estado ⑦; a legenda é o caminho degradado quando ele não sobe.
    val avatarState: AvatarState = AvatarState.OCIOSO,
    // Controlado por botão (abrirAvatar/fecharAvatar), não pela máquina de estados: o avatar só
    // tem o que mostrar no ⑦, e fora dele seguraria ~300 MB à toa.
    val avatarVisivel: Boolean = false,
    val avatarLegenda: String? = null,
    // De que passo é [avatarLegenda] — só o rótulo acima do texto depende disto. Null quando não há
    // legenda.
    val avatarAssunto: AssuntoAvatar? = null,
    // Libras Livre — modo economia de bateria (docs/confirmacao-e-modo-economia-plano.md §2).
    // Espelha DialogOrchestrator.economiaBateria: liga sozinho quando o DAT reporta
    // BATTERY_LOW/BATTERY_CRITICAL, nunca desliga sozinho (o SDK não expõe "bateria
    // recuperada").
    val bateriaBaixa: Boolean = false,
    val sessionState: DeviceSessionState = DeviceSessionState.IDLE,
    val streamState: StreamState = StreamState.STOPPED,
    // Flips once when the first preview frame arrives; drives the loading→preview swap. A one-shot
    // boolean (not a per-frame counter) keeps the screen from recomposing at the frame rate — the
    // SurfaceView renders frames independently of Compose.
    val hasReceivedFirstFrame: Boolean = false,
    // Capture / recording intent.
    val isCapturingPhoto: Boolean = false,
    val isRecording: Boolean = false,
    val recordingElapsedSeconds: Long = 0L,
    // The capture currently shown in the shared preview/share sheet (photo or video).
    val activePreview: CapturePreview? = null,
    // Drives the confirm prompt shown before the camera-permission redirect to the Meta AI app.
    val showCameraPermissionRedirectConfirm: Boolean = false,
    // True while the stream-start flow is in flight before the SDK stream state turns STARTING —
    // i.e. while the camera-permission check runs. Folded into isBusy so the Preview button stays
    // disabled for the whole flow, closing the gap where the SDK state machine hasn't moved yet.
    val isStartingStream: Boolean = false,
    // Libras Livre: estado do reconhecimento de sinal (captura, classificação, resultado, erro).
    val libras: LibrasState = LibrasState(),
    // Independente dos erros transitórios e da chave opcional do painel de métricas.
    val classificador: DiagnosticoClassificador? = null,
    val limiarClassificador: Float = 0.60f,
    // Libras Livre: estado da sessão de diálogo bidirecional (ver libras/DialogOrchestrator.kt).
    val dialogState: DialogState = DialogState.AGUARDANDO_SINAL,
    // Libras Livre: painel de conversa (docs/prontidao-demo/10-tela.md §10.1). Independe do stream:
    // o resultado de um turno fica visível até o próximo "iniciar".
    val conversa: Conversa = Conversa(),
    // Libras Livre: diagnóstico da demo (docs/prontidao-demo 3.8, 6.5, 1.9). Só preenchido com o
    // painel ou o gravador ligados nas configurações de demo.
    val painelMetricas: Boolean = false,
    val metricas: AmostraMetricas? = null,
    val etapasTurno: List<MarcaEtapa> = emptyList(),
    val arquivoGravacao: String? = null,
    // Libras Livre: avisos ativos da faixa de estado, por origem (10.2). A tela escolhe um.
    val avisos: Map<TipoAviso, Aviso> = emptyMap(),
    // Libras Livre: aquecimento ao abrir o app (6.4) e se o "iniciar" já pode ser usado.
    val aquecimento: List<ResultadoEtapa> = emptyList(),
    val aquecido: Boolean = false,
    // Libras Livre: interruptor "Comando de voz" (4.6), espelhado das configurações de demo.
    val comandoDeVoz: Boolean = true,
) {
  /** A session exists and is connected (or connecting); a stream can be started. */
  val hasSession: Boolean
    get() =
        sessionState == DeviceSessionState.STARTING ||
            sessionState == DeviceSessionState.STARTED ||
            sessionState == DeviceSessionState.PAUSED ||
            sessionState == DeviceSessionState.STOPPING

  val isSessionStarting: Boolean
    get() = sessionState == DeviceSessionState.STARTING

  val isSessionActive: Boolean
    get() = sessionState == DeviceSessionState.STARTED

  val isStreaming: Boolean
    get() = streamState == StreamState.STREAMING

  /**
   * The stream is paused by the device (single cap-touch tap); the last frame stays frozen on
   * screen.
   */
  val isPaused: Boolean
    get() = streamState == StreamState.PAUSED

  /** A stream is attached and not in a terminal state. */
  val hasStream: Boolean
    get() = streamState != StreamState.STOPPED && streamState != StreamState.CLOSED

  /**
   * A session or stream start/stop is in flight. The UI shows a spinner and disables controls until
   * the SDK settles on a stable state, so transient states never need their own controls.
   */
  val isBusy: Boolean
    get() =
        isSessionStarting ||
            sessionState == DeviceSessionState.STOPPING ||
            isStartingStream ||
            streamState == StreamState.STARTING ||
            streamState == StreamState.STARTED ||
            streamState == StreamState.STOPPING

  /** Human-readable session/stream state for the status chips. */
  val sessionStateText: String
    get() = sessionState.name.lowercase()

  val streamStateText: String
    get() = streamState.name.lowercase()
}

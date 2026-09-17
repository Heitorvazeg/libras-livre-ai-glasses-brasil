/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraAccess Sample App - Main Activity
//
// This is the main entry point for the CameraAccess sample application that demonstrates how to use
// the Meta Wearables Device Access Toolkit (DAT) to:
// - Initialize the DAT SDK
// - Handle device permissions (Bluetooth, Internet)
// - Request camera permissions from wearable devices (Ray-Ban Meta glasses)
// - Stream video and capture photos from connected wearable devices

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.TeclasDeVolume
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/** Conserva o dono do resultado também durante recriação da Activity por configuração. */
class PermissoesPendentesViewModel : ViewModel() {
  private val exclusao = Mutex()
  val wearables = PermissaoExterna<Permission, PermissionStatus>(exclusao)
  val audio = PermissaoExterna<Unit, Boolean>(exclusao)
}

class MainActivity : ComponentActivity() {
  companion object {
    // Required Android permissions for the DAT SDK to function properly
    val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET)
  }

  val viewModel: WearablesViewModel by viewModels()

  private val permissionCheckLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
        viewModel.onPermissionsResult(permissionsResult) {
          // Initialize the DAT SDK once the permissions are granted
          // This is REQUIRED before using any Wearables APIs
          Wearables.initialize(this)
        }
      }

  private val permissoesPendentes: PermissoesPendentesViewModel by viewModels()
  private val donoLaunchers = Any()
  // Requesting wearable device permissions via the Meta AI app
  private val permissionsResultLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        permissoesPendentes.wearables.receber(permissionStatus)
      }

  // O slot pertence ao pedido externo, não ao tempo de vida de quem aguarda a resposta.
  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
    return withContext(Dispatchers.Main.immediate) {
      permissoesPendentes.wearables.solicitar(permission)
    }
  }

  // Phone microphone permission, requested in context right before listening for the attendant's
  // reply (DialogState.AGUARDANDO_RESPOSTA -> ESCUTANDO_ATENDENTE — ver
  // libras/DialogOrchestrator.kt, CameraViewModel.onWakeWordButton).
  private val recordAudioPermissionLauncher =
      registerForActivityResult(RequestPermission()) { granted ->
        permissoesPendentes.audio.receber(granted)
      }

  // Requests RECORD_AUDIO. Returns true if granted (already or just now); false if denied, so the
  // caller can skip listening instead of crashing.
  suspend fun requestRecordAudioPermission(): Boolean {
    if (
        ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) {
      return true
    }
    return withContext(Dispatchers.Main.immediate) {
      permissoesPendentes.audio.solicitar(Unit)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CameraAccessScaffold(
          viewModel = viewModel,
          onRequestWearablesPermission = ::requestWearablesPermission,
          onRequestRecordAudioPermission = ::requestRecordAudioPermission,
      )
    }
  }

  // Libras Livre (docs/prontidao-demo/04 §4.7): com sessão ativa na tela da câmera, as teclas de
  // volume fazem o mesmo que o botão principal. Fora disso, ajustam o volume.
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
      if (TeclasDeVolume.ouvinte?.invoke(event.repeatCount == 0) == true) return true
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onStart() {
    super.onStart()
    // First, ensure the app has necessary Android permissions
    permissionCheckLauncher.launch(PERMISSIONS)
    permissoesPendentes.wearables.associar(donoLaunchers) { permissionsResultLauncher.launch(it) }
    permissoesPendentes.audio.associar(donoLaunchers) { recordAudioPermissionLauncher.launch(RECORD_AUDIO) }
  }

  private fun dissociarLaunchers() {
    permissoesPendentes.wearables.dissociar(donoLaunchers)
    permissoesPendentes.audio.dissociar(donoLaunchers)
  }

  override fun onStop() {
    // Não cancela o pedido externo: seu resultado ainda pertence ao deferred original.
    dissociarLaunchers()
    super.onStop()
  }

  override fun onDestroy() {
    // Um destroy tardio desta Activity não pode remover a associação da sucessora.
    dissociarLaunchers()
    super.onDestroy()
  }
}

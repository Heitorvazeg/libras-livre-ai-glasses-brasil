/*
 * O fluxo do atendimento pela tela, com os óculos simulados (docs/prontidao-demo, onda 1).
 *
 * Cobre o que dá para provar sem voz e sem uma pessoa no vídeo:
 *   - 3.1: "Iniciar" sem preview abre a captura com "Aguarde…", sem o erro falso do MediaPipe.
 *     Onde a biblioteca nativa do MediaPipe não existe (emulador só x86_64), o erro é verdadeiro e
 *     o teste confere que ele aparece e não prende a captura;
 *   - 7.1: a janela pede tela ligada enquanto há sessão com os óculos, e deixa de pedir sem ela;
 *   - 5.2: a escuta do ⑤ funciona sem nenhum dispositivo Bluetooth. Antes, sem SCO, ela voltava
 *     ao ④ na hora, sem escutar.
 *
 * O resto da volta (frase falada, transcrição com texto, avatar) depende de sinais reconhecidos e
 * de fala, e fica nos cenários manuais do guia de testes.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.LandmarkPipeline
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class FluxoOnda1Test {

  companion object {
    private const val TIMEOUT = 20_000L
  }

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

  @Before
  fun setup() {
    listOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.CAMERA",
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO",
        )
        .forEach {
          InstrumentationRegistry.getInstrumentation()
              .uiAutomation
              .executeShellCommand("pm grant ${targetContext.packageName} $it")
        }
  }

  @After
  fun tearDown() {
    MockDeviceKit.getInstance(targetContext).disable()
  }

  @Test
  fun iniciarSemPreviewCapturaTelaFicaLigadaEEscutaSemBluetooth() {
    parearOculos()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    assertEquals("sem sessão a tela pode apagar", false, telaLigadaPedida())

    composeTestRule.onNodeWithTag("start_session_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("wake_word_iniciar_button").and(isEnabled()), TIMEOUT)
    composeTestRule.waitUntil(TIMEOUT) { telaLigadaPedida() }
    // O pedido chegou ao sistema: o WindowManager está segurando a tela por uma janela do app.
    composeTestRule.waitUntil(TIMEOUT) { janelaQueSeguraATela()?.contains("MainActivity") == true }

    // ① -> ② pelo botão, sem ter tocado em preview.
    composeTestRule.onNodeWithTag("wake_word_iniciar_button").performClick()
    confirmarPermissaoDeCameraSePedida()
    esperarEstado("capturando_sinais")
    if (mediapipeDisponivel()) {
      composeTestRule.waitUntilExactlyOneExists(hasText(targetContext.getString(R.string.libras_aguarde)), TIMEOUT)
      assertTrue(
          "o erro falso do defeito A apareceu",
          composeTestRule.onAllNodesWithText(LandmarkPipeline.ERRO_MODELOS).fetchSemanticsNodes().isEmpty(),
      )
    } else {
      // Sem a biblioteca nativa (emulador só x86_64), o erro é verdadeiro: tem de estar na tela,
      // e a captura abre mesmo assim, sem prender o fluxo.
      composeTestRule.waitUntilExactlyOneExists(hasText(LandmarkPipeline.ERRO_MODELOS), TIMEOUT)
    }

    // ② -> ③ -> ④: sem sinais, nada a falar.
    composeTestRule.onNodeWithTag("wake_word_encerrar_button").performClick()
    esperarEstado("aguardando_resposta")

    // ④ -> ⑤ sem Bluetooth: fica escutando pelo microfone do celular.
    composeTestRule.onNodeWithTag("wake_word_iniciar_button").performClick()
    esperarEstado("escutando_atendente")
    Thread.sleep(2_000)
    composeTestRule.onNodeWithText(estadoTexto("escutando_atendente")).assertExists()

    // ⑤ -> ⑥ -> ④: o emulador não tem fala, a transcrição volta vazia.
    composeTestRule.onNodeWithTag("wake_word_encerrar_button").performClick()
    esperarEstado("aguardando_resposta")

    composeTestRule.onNodeWithTag("end_session_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button"), TIMEOUT)
    composeTestRule.waitUntil(TIMEOUT) { !telaLigadaPedida() }
  }

  private fun parearOculos() {
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = false))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)
    val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    oculos.powerOn()
    oculos.don()
    oculos.unfold()
    oculos.services.camera.setCameraFeed(copiarAsset("plant.mp4"))
  }

  // A permissão de câmera começa negada no mock: o primeiro stream pede a confirmação.
  private fun confirmarPermissaoDeCameraSePedida() {
    val continuar = targetContext.getString(R.string.camera_permission_continue)
    composeTestRule.waitUntil(TIMEOUT) {
      composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty() ||
          composeTestRule.onAllNodesWithText(estadoTexto("capturando_sinais")).fetchSemanticsNodes().isNotEmpty()
    }
    if (composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty()) {
      composeTestRule.onNodeWithText(continuar).performClick()
    }
  }

  // O AAR do MediaPipe traz arm64-v8a, armeabi-v7a e x86, mas não x86_64.
  private fun mediapipeDisponivel(): Boolean =
      listOf("mediapipe_tasks_vision_jni", "mediapipe_tasks_jni").any {
        runCatching { System.loadLibrary(it) }.isSuccess
      }

  private fun estadoTexto(estado: String) = targetContext.getString(R.string.dialog_state_label, estado)

  private fun esperarEstado(estado: String) =
      composeTestRule.waitUntilExactlyOneExists(hasText(estadoTexto(estado)), TIMEOUT)

  // View.keepScreenOn é o que o sistema junta, a cada passada de layout, na flag de tela ligada da
  // janela. A cópia de window.attributes que a Activity expõe não recebe essa flag, então o teste
  // procura a View que pede.
  private fun telaLigadaPedida(): Boolean {
    var ligada = false
    composeTestRule.runOnUiThread { ligada = algumaViewPedeTelaLigada(composeTestRule.activity.window.decorView) }
    return ligada
  }

  /** O `mHoldScreenWindow` do `dumpsys window`: a janela que mantém a tela ligada, ou null. */
  private fun janelaQueSeguraATela(): String? {
    val saida =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window").let { pfd ->
          android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }
    val valor = saida.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("mHoldScreenWindow=") }
    return valor?.removePrefix("mHoldScreenWindow=")?.takeUnless { it == "null" }
  }

  private fun algumaViewPedeTelaLigada(view: View): Boolean {
    if (view.keepScreenOn) return true
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) if (algumaViewPedeTelaLigada(view.getChildAt(i))) return true
    }
    return false
  }

  private fun copiarAsset(nome: String): Uri {
    val destino = File(targetContext.cacheDir, nome)
    InstrumentationRegistry.getInstrumentation().context.assets.open(nome).use { entrada ->
      destino.outputStream().use { entrada.copyTo(it) }
    }
    return Uri.fromFile(destino)
  }
}

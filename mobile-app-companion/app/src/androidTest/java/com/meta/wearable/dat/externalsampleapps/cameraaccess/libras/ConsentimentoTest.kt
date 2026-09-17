/*
 * Consentimento por atendimento, ①.5 (docs/consentimento-por-atendimento-plano.md). Sem este
 * teste, a regra pura em TransicoesTest não prova nada sobre o que acontece de verdade: se a
 * câmera realmente fica desligada até "Aceitar", e se "Recusar" realmente volta ao ① sem captar
 * nenhum sinal. Mesmo padrão de ConfirmacaoReconhecimentoTest — instrumentado, sem microfone.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class ConsentimentoTest {

  companion object {
    private const val TIMEOUT = 20_000L
    private const val TIMEOUT_AQUECIMENTO = 180_000L
  }

  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

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
          InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${targetContext.packageName} $it").close()
        }
  }

  @After
  fun tearDown() {
    MockDeviceKit.getInstance(targetContext).disable()
  }

  @Test
  fun recusarNaoLigaACameraEVoltaAoInicioSemCaptura() {
    parearOculos()
    iniciarSessao()
    esperarAquecimento()

    // "iniciar" leva a ①.5, não direto a ② — a câmera não sobe ainda (§2.4 do plano).
    composeTestRule.onNodeWithTag("botao_principal").performClick()
    esperarEstado(R.string.estado_pedindo_consentimento, TIMEOUT)
    composeTestRule.onNodeWithText(targetContext.getString(R.string.avatar_caption_label_consentimento)).assertExists()
    composeTestRule.onNodeWithTag("consentimento_recusar_button").assertExists()
    composeTestRule.onNodeWithTag("consentimento_aceitar_button").assertExists()

    composeTestRule.onNodeWithTag("consentimento_recusar_button").performClick()

    // Volta ao ① com o aviso de bilhete/intérprete — nunca passou por "Capturando".
    esperarEstado(R.string.estado_aguardando_sinal, TIMEOUT)
    composeTestRule.onNodeWithText(targetContext.getString(R.string.aviso_consentimento_recusado), substring = true).assertExists()
    composeTestRule.onAllNodesWithText(targetContext.getString(R.string.estado_capturando)).assertCountEquals(0)
  }

  @Test
  fun aceitarLigaACameraEComecaACaptura() {
    parearOculos()
    iniciarSessao()
    esperarAquecimento()

    composeTestRule.onNodeWithTag("botao_principal").performClick()
    esperarEstado(R.string.estado_pedindo_consentimento, TIMEOUT)

    composeTestRule.onNodeWithTag("consentimento_aceitar_button").performClick()

    // Só depois de aceitar a câmera liga e a captura começa — mesmo comportamento do "iniciar"
    // de antes do plano de consentimento existir.
    esperarEstado(R.string.estado_capturando, TIMEOUT)

    composeTestRule.onNodeWithTag("cancelar_atendimento_button").performClick()
    esperarEstado(R.string.estado_aguardando_sinal, TIMEOUT)
  }

  private fun parearOculos() {
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = true))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    oculos.powerOn()
    oculos.don()
    oculos.unfold()
    oculos.services.camera.setCameraFeed(copiarAsset("pessoa.mp4"))
  }

  private fun iniciarSessao() {
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), TIMEOUT)
    if (composeTestRule.onAllNodesWithTag("start_session_button").fetchSemanticsNodes().isEmpty()) {
      composeTestRule.onNodeWithTag("mais_controles").performClick()
    }
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    composeTestRule.onNodeWithTag("start_session_button").performClick()
  }

  private fun esperarAquecimento() =
      composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT_AQUECIMENTO)

  private fun esperarEstado(id: Int, timeout: Long) =
      composeTestRule.waitUntilAtLeastOneExists(hasText(targetContext.getString(id)), timeout)

  private fun copiarAsset(nome: String): Uri {
    val destino = File(targetContext.cacheDir, nome)
    InstrumentationRegistry.getInstrumentation().context.assets.open(nome).use { entrada ->
      destino.outputStream().use { entrada.copyTo(it) }
    }
    return Uri.fromFile(destino)
  }
}

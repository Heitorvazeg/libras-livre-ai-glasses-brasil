/*
 * Confirmação do reconhecimento pro surdo, ②.5 (docs/confirmacao-e-modo-economia-plano.md §1).
 * Sem esse teste, o estado novo só era coberto por TransicoesTest (regra pura) — nada exercitava
 * DialogOrchestrator.iniciarConfirmacao/confirmarReconhecimento/corrigirReconhecimento de ponta a
 * ponta. DialogOrchestrator não é testável na JVM (Speaker/LandmarkPipeline exigem Context real,
 * e o projeto não usa framework de mock) — por isso instrumentado, como os outros Fluxo*Test.
 *
 * Usa o placeholder no modo ROTEIRO (padrão de ConfiguracoesDemo) com sinais.mp4, igual ao
 * FluxoCompletoTest — mas para em ②.5 em vez de seguir para a escuta com voz real, então não
 * precisa de microfone: exercita "Corrigir" (volta a capturar) e "Confirmar" (segue pro ③),
 * sem depender do classificador real nem de hardware.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class ConfirmacaoReconhecimentoTest {

  companion object {
    private const val TIMEOUT = 20_000L
    private const val TIMEOUT_AQUECIMENTO = 180_000L
    // sinais.mp4 -> boundary detector -> ROTEIRO (alta confiança) -> AvaliadorDeFrase: o mesmo
    // orçamento do FluxoCompletoTest para uma decisão "Falar" aparecer.
    private const val TIMEOUT_DECISAO = 120_000L
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
  fun corrigirVoltaACapturarEConfirmarSeguePraEscuta() {
    parearOculos()
    iniciarSessao()
    esperarAquecimento()
    iniciarCaptura()

    // 1. A primeira decisão "Falar" leva a ②.5: legenda "Você sinalizou" (não "O atendente
    // disse") e o botão "Corrigir" — não é o ⑦ (resposta do atendente) reaproveitado por engano.
    esperarEstado(R.string.estado_confirmando, TIMEOUT_DECISAO)
    composeTestRule.onNodeWithText(targetContext.getString(R.string.avatar_caption_label_confirmacao)).assertExists()
    composeTestRule.onNodeWithTag("avatar_corrigir_button").assertExists()

    // 2. "Corrigir": descarta a frase e reabre a captura de sinais do zero (religa a câmera pelo
    // mesmo ensureCameraActive() do "iniciar" — não é um estado novo inventado pra correção).
    composeTestRule.onNodeWithTag("avatar_corrigir_button").performClick()
    esperarEstado(R.string.estado_capturando, TIMEOUT)

    // 3. Uma segunda decisão "Falar" chega igual à primeira: ②.5 de novo.
    esperarEstado(R.string.estado_confirmando, TIMEOUT_DECISAO)

    // 4. "Confirmar" (botão principal da tela do avatar, AcaoBotao.CONFIRMAR): fala pro atendente
    // e a escuta abre sozinha — chega em "Falando" e depois "Ouvindo a resposta" sem intervenção,
    // exatamente como o fluxo antigo fazia antes de ②.5 existir, só que depois da confirmação.
    composeTestRule.onNodeWithTag("botao_principal_avatar").performClick()
    esperarEstado(R.string.estado_ouvindo, TIMEOUT_DECISAO)

    // Encerra sem falar no microfone: não é objetivo deste teste validar a resposta do atendente.
    composeTestRule.onNodeWithTag("cancelar_atendimento_button").performClick()
    esperarEstado(R.string.estado_aguardando_sinal, TIMEOUT)
  }

  private fun parearOculos(): MockGlasses {
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = true))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    oculos.powerOn()
    oculos.don()
    oculos.unfold()
    oculos.services.camera.setCameraFeed(copiarAsset("sinais.mp4"))
    return oculos
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

  private fun iniciarCaptura() {
    composeTestRule.onNodeWithTag("botao_principal").performClick()
    val continuar = targetContext.getString(R.string.camera_permission_continue)
    val capturando = targetContext.getString(R.string.estado_capturando)
    composeTestRule.waitUntil(TIMEOUT) {
      composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty() ||
          composeTestRule.onAllNodesWithText(capturando).fetchSemanticsNodes().isNotEmpty()
    }
    if (composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty()) {
      composeTestRule.onNodeWithText(continuar).performClick()
    }
    esperarEstado(R.string.estado_capturando, TIMEOUT)
  }

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

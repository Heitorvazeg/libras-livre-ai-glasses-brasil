/*
 * Integração modelo↔app, item "próxima pendência" de docs/integracao-video-infraestrutura-2026-09-15.md:
 * o cartão de diagnóstico só tinha sido validado isolado (DiagnosticoClassificadorUiTest). Aqui ele é
 * conferido dentro da tela de câmera real, com o menu de configurações de demo de verdade e alternância
 * de turno/sessão — sem depender do modelo final (build padrão, classificador SIMULADO).
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
class DiagnosticoClassificadorTelaCompletaTest {

  companion object {
    private const val TIMEOUT = 20_000L
    private const val TIMEOUT_AQUECIMENTO = 180_000L
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
          InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${targetContext.packageName} $it").close()
        }
  }

  @After
  fun tearDown() {
    MockDeviceKit.getInstance(targetContext).disable()
  }

  @Test
  fun cartaoDeDiagnosticoNaTelaDeCameraAcompanhaMudancaDeLimiarEAlternanciaDeTurnoESessao() {
    parearOculos()
    iniciarSessao()
    esperarAquecimento()

    // 1. O cartão aparece na tela de câmera real (não isolado num teste Compose de unidade).
    composeTestRule.onNodeWithTag("diagnostico-classificador").assertExists()
    composeTestRule.onNodeWithText("SIMULADO", substring = true).assertIsDisplayed()

    // 2. O diálogo de identidade abre a partir do cartão de verdade, não de um mock isolado.
    composeTestRule.onNodeWithTag("diagnostico-classificador").performClick()
    composeTestRule.onNodeWithText("modo=SIMULADO", substring = true).assertExists()
    composeTestRule.onNodeWithText("Fechar").performClick()

    // 3. Menu de configurações de demo de verdade: volta ao padrão (execuções anteriores não podem
    // vazar limiar para esta) e depois muda o limiar; ambos refletem no cartão em tela. A folha modal
    // não é fechada entre os passos (ver nota em abrirConfiguracoesDemo); as consultas por tag/texto
    // abaixo continuam mirando a árvore real da tela de câmera, por baixo dela.
    abrirConfiguracoesDemo()
    composeTestRule.onNodeWithText(targetContext.getString(R.string.demo_config_reset)).performClick()
    composeTestRule.onNodeWithText("limiar manual: 0.6", substring = true).assertIsDisplayed()
    definirLimiar("0.75")
    composeTestRule.onNodeWithText("limiar manual: 0.75", substring = true).assertIsDisplayed()

    // 4. Alternar turno (iniciar → capturando → cancelar → aguardando sinal) não muda o diagnóstico.
    repeat(2) {
      alternarTurno()
      composeTestRule.onNodeWithText("limiar manual: 0.75", substring = true).assertIsDisplayed()
      composeTestRule.onNodeWithText("SIMULADO", substring = true).assertIsDisplayed()
    }

    // 5. Alternar sessão (encerrar e iniciar de novo) preserva modo e limiar efetivo.
    alternarSessao()
    esperarAquecimento()
    composeTestRule.onNodeWithText("limiar manual: 0.75", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("SIMULADO", substring = true).assertIsDisplayed()
  }

  private fun parearOculos(): MockGlasses {
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = true))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    oculos.powerOn()
    oculos.don()
    oculos.unfold()
    oculos.services.camera.setCameraFeed(copiarAsset("pessoa.mp4"))
    return oculos
  }

  private fun iniciarSessao() {
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), TIMEOUT)
    abrirMaisControles()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    composeTestRule.onNodeWithTag("start_session_button").performClick()
  }

  private fun abrirMaisControles() {
    if (composeTestRule.onAllNodesWithTag("start_session_button").fetchSemanticsNodes().isEmpty() &&
        composeTestRule.onAllNodesWithTag("end_session_button").fetchSemanticsNodes().isEmpty()) {
      composeTestRule.onNodeWithTag("mais_controles").performClick()
    }
  }

  private fun alternarTurno() {
    composeTestRule.onNodeWithTag("botao_principal").performClick()
    esperarTexto(R.string.estado_capturando)
    composeTestRule.onNodeWithTag("cancelar_atendimento_button").performClick()
    esperarTexto(R.string.estado_aguardando_sinal)
  }

  private fun alternarSessao() {
    abrirMaisControles()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("end_session_button").and(isEnabled()), TIMEOUT)
    composeTestRule.onNodeWithTag("end_session_button").performClick()
    abrirMaisControles()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    composeTestRule.onNodeWithTag("start_session_button").performClick()
  }

  /**
   * Abre o menu de debug (FAB) e a seção "Configurações de demo" dentro dele. Não fecha a folha
   * depois: nem o botão voltar do sistema nem tocar fora dela dispensam essa versão do Material3
   * (`skipPartiallyExpanded=true` deixa pouco/nenhum scrim visível). As consultas por tag/texto do
   * Compose miram a árvore de semântica de cada nó diretamente, não a pilha de janelas do Android, e
   * continuam enxergando o cartão de diagnóstico por baixo da folha aberta.
   */
  private fun abrirConfiguracoesDemo() {
    composeTestRule.onNodeWithContentDescription(targetContext.getString(R.string.debug_menu_description)).performClick()
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("demo_config_abrir"), TIMEOUT)
    composeTestRule.onNodeWithTag("demo_config_abrir").performClick()
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("demo_limiar"), TIMEOUT)
  }

  private fun definirLimiar(valor: String) {
    composeTestRule.onNodeWithTag("demo_limiar").performScrollTo().performTextReplacement(valor)
    composeTestRule.onNodeWithTag("demo_limiar").performImeAction()
  }

  private fun esperarAquecimento() =
      composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT_AQUECIMENTO)

  private fun esperarTexto(id: Int) =
      composeTestRule.waitUntilAtLeastOneExists(hasText(targetContext.getString(id)), TIMEOUT)

  private fun copiarAsset(nome: String): Uri {
    val destino = File(targetContext.cacheDir, nome)
    InstrumentationRegistry.getInstrumentation().context.assets.open(nome).use { entrada ->
      destino.outputStream().use { entrada.copyTo(it) }
    }
    return Uri.fromFile(destino)
  }
}

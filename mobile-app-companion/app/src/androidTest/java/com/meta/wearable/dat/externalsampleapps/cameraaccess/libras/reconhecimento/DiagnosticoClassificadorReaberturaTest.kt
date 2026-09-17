/*
 * Integração modelo↔app, item "próxima pendência" de docs/integracao-video-infraestrutura-2026-09-15.md:
 * "reabrir o app... conferir persistência do modo/identidade e limiar efetivo". Fecha a Activity de
 * verdade (não apenas recreate() de mudança de configuração, que manteria o ViewModel) e reabre com uma
 * nova ActivityScenario: CameraViewModel e o classificador são reconstruídos do zero. Sem depender do
 * modelo final — build padrão, classificador SIMULADO; a persistência de identidade/hash do modelo real
 * (REAL_EXPERIMENTAL) fica para quando o pacote privado estiver disponível neste ambiente.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class DiagnosticoClassificadorReaberturaTest {

  companion object {
    private const val TIMEOUT = 20_000L
    private const val TIMEOUT_AQUECIMENTO = 180_000L
  }

  // Sem createAndroidComposeRule<MainActivity>(): essa regra fecharia e relançaria a Activity por
  // conta própria no fim do teste. Aqui o fechar/reabrir é o próprio teste, feito com ActivityScenario.
  @get:Rule val composeTestRule = createEmptyComposeRule()

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
    // Pareamento é global ao processo (MockDeviceKit.getInstance(applicationContext)): sobrevive ao
    // fechar/reabrir da Activity, como um óculos já pareado sobreviveria a reabrir o app de verdade.
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = true))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow().apply {
      powerOn()
      don()
      unfold()
    }
  }

  @After
  fun tearDown() {
    MockDeviceKit.getInstance(targetContext).disable()
  }

  @Test
  fun modoELimiarDoClassificadorPersistemAoFecharEReabrirOApp() {
    ActivityScenario.launch(MainActivity::class.java).use {
      esperarAquecimento()
      // 10.3: o cartão de diagnóstico mora na gaveta, fechada por padrão a cada Activity nova.
      abrirDiagnostico()

      // Volta ao padrão: execuções anteriores não podem vazar limiar para o valor esperado aqui. A
      // folha modal de configurações não é fechada (ver nota em abrirConfiguracoesDemo); as consultas
      // por tag/texto abaixo continuam mirando a árvore real da tela de câmera, por baixo dela.
      abrirConfiguracoesDemo()
      composeTestRule.onNodeWithText(targetContext.getString(R.string.demo_config_reset)).performClick()
      composeTestRule.onNodeWithText("SIMULADO", substring = true).assertIsDisplayed()
      composeTestRule.onNodeWithText("limiar manual: 0.6", substring = true).assertIsDisplayed()

      definirLimiar("0.83")
      composeTestRule.onNodeWithText("limiar manual: 0.83", substring = true).assertIsDisplayed()
    }
    // `use { }` fecha a Activity (isFinishing=true): o ViewModelStore é limpo de verdade, não é
    // apenas uma mudança de configuração que o preservaria.

    ActivityScenario.launch(MainActivity::class.java).use {
      esperarAquecimento()
      abrirDiagnostico()
      // Modo recomputado do zero (novo CameraViewModel/classificador) chega ao mesmo resultado.
      composeTestRule.onNodeWithText("SIMULADO", substring = true).assertIsDisplayed()
      // Limiar não é recomputado: vem do SharedPreferences gravado antes de fechar o app.
      composeTestRule.onNodeWithText("limiar manual: 0.83", substring = true).assertIsDisplayed()
    }
  }

  private fun esperarAquecimento() =
      composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT_AQUECIMENTO)

  // 10.3: o cartão de diagnóstico (SIMULADO/REAL_EXPERIMENTAL) mora na gaveta, fechada por padrão.
  private fun abrirDiagnostico() {
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("mais_diagnostico"), TIMEOUT)
    if (composeTestRule.onAllNodesWithTag("diagnostico-classificador").fetchSemanticsNodes().isEmpty()) {
      composeTestRule.onNodeWithTag("mais_diagnostico").performClick()
    }
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
}

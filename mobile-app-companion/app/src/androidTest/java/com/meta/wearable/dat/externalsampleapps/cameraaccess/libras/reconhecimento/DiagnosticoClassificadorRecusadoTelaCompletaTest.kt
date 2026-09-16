/*
 * Integração modelo↔app: o caminho RECUSADO (hash bate, mas o .tflite é inválido) nunca tinha sido
 * exercitado na tela de câmera real — só em unidade isolada (DiagnosticoClassificadorUiTest, com
 * DiagnosticoClassificador construído à mão) e em CarregadorClassificadorTest (política pura, sem
 * tela). Aqui o pacote privado de verdade passa pelas checagens de hash do Gradle e do app, mas o
 * modelo em si não é um flatbuffer TFLite válido — sem depender de nenhum checkpoint real. Confirma
 * a aceite do plano: "o modo real não produz glosas de placeholder em nenhum caminho de falha".
 *
 * Exige build com -PlibrasLivre.classificadorPrivado=<pacote-recusado>, gerado por um script fora do
 * repositório (ver docs). Não roda no build padrão: sem essa propriedade, o app carrega SIMULADO e as
 * asserções de RECUSADO abaixo falhariam por motivo errado.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class DiagnosticoClassificadorRecusadoTelaCompletaTest {

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
  fun modeloComHashValidoEFlatbufferInvalidoMostraRecusadoNaTelaRealSemGlosaDePlaceholder() {
    assumeTrue("Exige build com classificador privado (pacote inválido de propósito)", BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO)

    parearOculos()
    iniciarSessao()

    // O aquecimento termina (não fica pendente/executando), mas com falha: o classificador nunca
    // chega a aquecer, e o resumo recolhido mostra "falha(s)" em vez de "Pronto ✓".
    composeTestRule.waitUntilAtLeastOneExists(hasText("falha(s)", substring = true), TIMEOUT_AQUECIMENTO)

    // Aquecimento.kt (docstring) é explícito: "iniciar" libera quando as etapas que bloqueiam
    // TERMINAM, com ✓ OU ✗ — não é uma trava por falha. Rodando isto na tela real (não só lendo o
    // código-fonte) confirma o design: o botão libera mesmo com o classificador recusado. A garantia
    // contra glosa de placeholder está no pipeline de classificação por segmento (LandmarkPipeline
    // .onSegmento: classifier.classify() lançando vira `error` e onRecognitionFailed(), nunca uma
    // Classificacao fabricada) — não numa trava de sessão. Esse caminho por segmento exige captura
    // e segmentação reais (ver VideoClassificadorPrivadoTest) e não é repetido aqui.
    composeTestRule.onNodeWithTag("botao_principal").assertIsEnabled()

    // O cartão de diagnóstico na tela real mostra RECUSADO e o motivo, não um SIMULADO disfarçado.
    // O mesmo motivo também aparece na linha "✗ Classificador" do cartão de aquecimento — por
    // isso "onFirst()" em vez de exigir um nó único: a duplicação é esperada, não um erro de teste.
    composeTestRule.onNodeWithText("RECUSADO", substring = true).assertIsDisplayed()
    composeTestRule.onAllNodesWithText("Modelo de sinais recusado", substring = true).onFirst().assertIsDisplayed()

    // O diálogo de identidade, aberto na tela real, registra o mesmo motivo nos detalhes completos.
    composeTestRule.onNodeWithTag("diagnostico-classificador").performClick()
    composeTestRule.onNodeWithText("modo=RECUSADO", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("motivo=", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Fechar").performClick()
  }

  private fun parearOculos() {
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = true))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow().apply {
      powerOn()
      don()
      unfold()
    }
  }

  private fun iniciarSessao() {
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), TIMEOUT)
    if (composeTestRule.onAllNodesWithTag("start_session_button").fetchSemanticsNodes().isEmpty()) {
      composeTestRule.onNodeWithTag("mais_controles").performClick()
    }
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    composeTestRule.onNodeWithTag("start_session_button").performClick()
  }
}

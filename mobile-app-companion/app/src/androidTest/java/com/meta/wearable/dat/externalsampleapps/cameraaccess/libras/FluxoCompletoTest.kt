/*
 * O atendimento inteiro na tela do app, com os óculos simulados e sem o modelo de visão: o
 * placeholder no modo roteiro (2.6) dá as glosas, o sinais.mp4 dá o movimento que o detector
 * segmenta (1.1–1.6), e a resposta do atendente chega pelo microfone do emulador.
 *
 * Não roda na suíte normal: precisa de voz no microfone no momento do ⑤. Para rodar, ver
 * docs/guia-de-testes-mock-e-oculos.md ("Fluxo completo no emulador"):
 *
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=...libras.FluxoCompletoTest \
 *     -Pandroid.testInstrumentationRunnerArguments.fluxoCompleto=true
 *
 * O teste escreve "FluxoCompleto: OUVINDO" no logcat quando a escuta abre (o script do host toca a
 * resposta nessa hora) e salva uma captura de tela por estado em files/fluxo-completo/.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.SaidaVoz
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class FluxoCompletoTest {

  companion object {
    private const val TAG = "FluxoCompleto"
    private const val TIMEOUT = 20_000L
    private const val TIMEOUT_AQUECIMENTO = 180_000L
    private const val TIMEOUT_TURNO = 120_000L
  }

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  private val targetContext: Context
    get() = instrumentation.targetContext.applicationContext

  @get:Rule
  val composeTestRule =
      createAndroidComposeRule<MainActivity>().also {
        ConfiguracoesDemo.de(InstrumentationRegistry.getInstrumentation().targetContext).atualizar { v ->
          v.copy(gravadorSessao = true, saidaVoz = SaidaVoz.CELULAR)
        }
      }

  @Before
  fun setup() {
    assumeTrue(
        "só com -Pandroid.testInstrumentationRunnerArguments.fluxoCompleto=true",
        InstrumentationRegistry.getArguments().getString("fluxoCompleto") == "true",
    )
    listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_CONNECT", "android.permission.CAMERA",
            "android.permission.INTERNET", "android.permission.RECORD_AUDIO")
        .forEach { instrumentation.uiAutomation.executeShellCommand("pm grant ${targetContext.packageName} $it") }
  }

  @After
  fun tearDown() {
    ConfiguracoesDemo.de(targetContext).voltarAoPadrao()
    MockDeviceKit.getInstance(targetContext).disable()
  }

  @Test
  fun sinaisFalaRespostaEAvatarNumAtendimento() {
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = false))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    oculos.powerOn()
    oculos.don()
    oculos.unfold()
    oculos.services.camera.setCameraFeed(copiarAsset("sinais.mp4"))

    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), TIMEOUT)
    composeTestRule.onNodeWithTag("mais_controles").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    composeTestRule.onNodeWithTag("start_session_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT_AQUECIMENTO)
    capturar("0-pronto")

    composeTestRule.onNodeWithTag("botao_principal").performClick()
    val continuar = targetContext.getString(R.string.camera_permission_continue)
    if (composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty()) {
      composeTestRule.onNodeWithText(continuar).performClick()
    }

    // Segue o estado até o ⑦ terminar e o diálogo voltar ao ①, com uma captura por estado.
    val esperados =
        listOf(R.string.estado_capturando, R.string.estado_falando, R.string.estado_ouvindo, R.string.estado_avatar)
            .map { targetContext.getString(it) }
    val aguardando = targetContext.getString(R.string.estado_aguardando_sinal)
    val vistos = mutableListOf<String>()
    val limite = System.currentTimeMillis() + TIMEOUT_TURNO
    while (System.currentTimeMillis() < limite) {
      val estado = estadoNaTela()
      if (estado != null && estado != vistos.lastOrNull()) {
        vistos += estado
        Log.i(TAG, "estado: $estado")
        if (estado == targetContext.getString(R.string.estado_ouvindo)) Log.i(TAG, "OUVINDO")
        capturar("${vistos.size}-$estado")
      }
      if (estado == aguardando && vistos.contains(esperados.last())) break
      Thread.sleep(100)
    }
    Thread.sleep(1_000)
    capturar("fim")
    Log.i(TAG, "sequência: $vistos")
    assertTrue("estados esperados $esperados, vistos $vistos", vistos.containsAll(esperados))
    assertTrue(
        "o painel não mostra a resposta transcrita",
        composeTestRule.onAllNodesWithText(targetContext.getString(R.string.conversa_resposta), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty(),
    )
  }

  private fun estadoNaTela(): String? =
      runCatching {
            composeTestRule.onAllNodesWithTag("estado_dialogo", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.getOrElseNullable(SemanticsProperties.Text) { null }
                ?.joinToString("") { texto -> texto.text }
          }
          .getOrNull()

  private fun capturar(nome: String) {
    val pasta = File(targetContext.getExternalFilesDir(null), "fluxo-completo").apply { mkdirs() }
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    File(pasta, "$nome.png".replace(' ', '_')).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
  }

  private fun copiarAsset(nome: String): Uri {
    val destino = File(targetContext.cacheDir, nome)
    instrumentation.context.assets.open(nome).use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
    return Uri.fromFile(destino)
  }
}

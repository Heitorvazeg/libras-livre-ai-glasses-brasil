/*
 * Painel de métricas e gravador numa sessão real do app, com os óculos simulados
 * (docs/prontidao-demo/03 §3.8 e 01 §1.9, onda 2).
 *
 * "Pronto quando uma sessão no emulador gera o overlay e as linhas de métrica no CSV": liga os dois
 * pelas configurações de demo (a mesma instância que o menu de debug altera), abre uma captura pelo
 * botão e confere o overlay na tela e as linhas `metrica` e `frame` no CSV da sessão.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.net.Uri
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.FormatoCsv
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
class FluxoOnda2Test {

  companion object {
    private const val TIMEOUT = 20_000L
  }

  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

  private val pastaSessoes: File
    get() = File(targetContext.getExternalFilesDir(null), "sessoes")

  // Liga painel e gravador ANTES de a Activity (e o CameraViewModel) nascer.
  @get:Rule
  val composeTestRule =
      createAndroidComposeRule<MainActivity>().also {
        ConfiguracoesDemo.de(InstrumentationRegistry.getInstrumentation().targetContext)
            .atualizar { v -> v.copy(gravadorSessao = true, painelMetricas = true) }
      }

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
    ConfiguracoesDemo.de(targetContext).voltarAoPadrao()
    MockDeviceKit.getInstance(targetContext).disable()
  }

  @Test
  fun sessaoGeraOverlayDeMetricasECsvComLinhasDeFrameEMetrica() {
    val antes = pastaSessoes.listFiles()?.map { it.name }?.toSet() ?: emptySet()

    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = false))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)
    val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    oculos.powerOn()
    oculos.don()
    oculos.unfold()
    oculos.services.camera.setCameraFeed(copiarAsset("pessoa.mp4"))

    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    composeTestRule.onNodeWithTag("start_session_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT)

    // O overlay aparece com a sessão e o painel ligado.
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("painel_metricas"), TIMEOUT)

    composeTestRule.onNodeWithTag("botao_principal").performClick()
    val continuar = targetContext.getString(R.string.camera_permission_continue)
    val capturando = targetContext.getString(R.string.dialog_state_label, "capturando_sinais")
    composeTestRule.waitUntil(TIMEOUT) {
      composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty() ||
          composeTestRule.onAllNodesWithText(capturando).fetchSemanticsNodes().isNotEmpty()
    }
    if (composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty()) {
      composeTestRule.onNodeWithText(continuar).performClick()
    }
    composeTestRule.waitUntilExactlyOneExists(hasText(capturando), TIMEOUT)
    // Alguns segundos de captura: frames processados e amostras de métrica por segundo.
    Thread.sleep(4_000)
    // "Cancelar atendimento" fecha a captura sem passar pelo fluxo "repita" (4.7).
    composeTestRule.onNodeWithTag("cancelar_atendimento_button").performClick()

    composeTestRule.onNodeWithTag("end_session_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button"), TIMEOUT)

    // O gravador fecha junto com a sessão; o arquivo novo é o desta sessão.
    lateinit var csv: File
    composeTestRule.waitUntil(TIMEOUT) {
      val novos = pastaSessoes.listFiles()?.filter { it.name !in antes && it.name.endsWith(".csv") }.orEmpty()
      novos.maxByOrNull { it.lastModified() }?.also { csv = it } != null
    }
    val linhas = csv.readLines()
    assertEquals(FormatoCsv.cabecalho(), linhas.first())
    val tipos = linhas.drop(1).groupingBy { it.substringBefore(',') }.eachCount()
    assertTrue("sem linhas de métrica: $tipos", (tipos["metrica"] ?: 0) > 0)
    assertTrue("sem linhas de frame: $tipos", (tipos["frame"] ?: 0) > 0)
    assertTrue(
        "sem fps_recebido nas métricas",
        linhas.any { it.startsWith("metrica,") && it.contains(",fps_recebido,") })
    android.util.Log.i("FluxoOnda2Test", "CSV ${csv.absolutePath}: $tipos")
  }

  private fun copiarAsset(nome: String): Uri {
    val destino = File(targetContext.cacheDir, nome)
    InstrumentationRegistry.getInstrumentation().context.assets.open(nome).use { entrada ->
      destino.outputStream().use { entrada.copyTo(it) }
    }
    return Uri.fromFile(destino)
  }
}

/*
 * Robustez e visibilidade no app, com os óculos simulados (docs/prontidao-demo, onda 4).
 *
 *   - 6.4: o aquecimento termina com as etapas que bloqueiam em ✓ e libera o "iniciar";
 *   - 3.2 e 10.2: o toque na haste durante a captura põe "Stream pausado" na faixa, sem encerrar a
 *     captura, e a retomada tira o aviso;
 *   - 3.4: câmera que não sobe mostra a causa (permissão pendente) em vez de ignorar o "iniciar";
 *   - 9.5: um avatar derrubado ("Simular queda do avatar") volta a carregar sozinho no próximo
 *     "iniciar" — só onde o avatar carrega (WebGL no emulador varia).
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.AcoesDeDemo
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class FluxoOnda4Test {

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
  fun aquecimentoLiberaOIniciarEPausaDoStreamApareceNaFaixaSemEncerrarACaptura() {
    val oculos = parearOculos(permissaoConcedida = true)
    iniciarSessao()
    esperarAquecimento()
    // As cinco etapas que bloqueiam terminaram com ✓ (o avatar pode falhar no emulador: a legenda cobre).
    abrirResumoDoAquecimento()
    for (etapa in listOf(R.string.aquecimento_etapa_mediapipe, R.string.aquecimento_etapa_classificador,
        R.string.aquecimento_etapa_contextualizacao, R.string.aquecimento_etapa_vosk, R.string.aquecimento_etapa_voz)) {
      composeTestRule.waitUntilAtLeastOneExists(hasText("✓ ${targetContext.getString(etapa)}", substring = true), TIMEOUT)
    }

    composeTestRule.onNodeWithTag("botao_principal").performClick()
    aceitarConsentimento()
    esperarTexto(R.string.estado_capturando)

    // 3.2: toque na haste pausa o stream; a faixa avisa e a captura continua aberta.
    oculos.services.captouch.tap()
    esperarTexto(R.string.aviso_stream_pausado)
    composeTestRule.onNodeWithText(targetContext.getString(R.string.estado_capturando)).assertExists()
    oculos.services.captouch.tap()
    composeTestRule.waitUntil(TIMEOUT) {
      composeTestRule.onAllNodesWithText(targetContext.getString(R.string.aviso_stream_pausado)).fetchSemanticsNodes().isEmpty()
    }
    composeTestRule.onNodeWithText(targetContext.getString(R.string.estado_capturando)).assertExists()

    composeTestRule.onNodeWithTag("cancelar_atendimento_button").performClick()
    esperarTexto(R.string.estado_aguardando_sinal)
  }

  @Test
  fun cameraQueNaoSobeMostraACausaNaFaixa() {
    // Permissão de câmera negada e ninguém confirma o pedido: o stream não sobe em 8 s.
    parearOculos(permissaoConcedida = false)
    iniciarSessao()
    esperarAquecimento()
    composeTestRule.onNodeWithTag("botao_principal").performClick()
    aceitarConsentimento()
    composeTestRule.waitUntilAtLeastOneExists(hasText(targetContext.getString(R.string.falha_camera_permissao)), TIMEOUT)
    esperarTexto(R.string.estado_aguardando_sinal)
  }

  @Test
  fun avatarDerrubadoVoltaACarregarSozinhoNoProximoIniciar() {
    parearOculos(permissaoConcedida = true)
    iniciarSessao()
    esperarAquecimento()
    // Só onde o avatar carrega: espera o aquecimento inteiro e lê a etapa do avatar.
    abrirResumoDoAquecimento()
    val avatar = targetContext.getString(R.string.aquecimento_etapa_avatar)
    assumeTrue(
        "o avatar não carrega neste emulador (WebGL)",
        composeTestRule.onAllNodesWithText("✓ $avatar", substring = true).fetchSemanticsNodes().isNotEmpty())

    limparLogcat()
    composeTestRule.runOnUiThread { AcoesDeDemo.simularQuedaDoAvatar?.invoke() }
    composeTestRule.waitUntil(TIMEOUT) { logcat().contains("renderer morreu") }

    composeTestRule.onNodeWithTag("botao_principal").performClick()
    aceitarConsentimento()
    esperarTexto(R.string.estado_capturando)
    composeTestRule.waitUntil(TIMEOUT) { logcat().contains("recarregando em segundo plano") }

    composeTestRule.onNodeWithTag("cancelar_atendimento_button").performClick()
    esperarTexto(R.string.estado_aguardando_sinal)
  }

  private fun parearOculos(permissaoConcedida: Boolean): MockGlasses {
    val kit = MockDeviceKit.getInstance(targetContext)
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = permissaoConcedida))
    if (!permissaoConcedida) {
      kit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)
    } else {
      kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    }
    val oculos = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    oculos.powerOn()
    oculos.don()
    oculos.unfold()
    oculos.services.camera.setCameraFeed(copiarAsset("pessoa.mp4"))
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

  /**
   * Espera o aquecimento terminar e abre a lista ✓/✗. O cartão do aquecimento só fica na tela
   * principal ENQUANTO aquece (10.3) — terminado, ele sai de lá e passa a existir só dentro da
   * gaveta de diagnóstico, onde continua sendo desenhado do início ao fim. Por isso a gaveta abre
   * primeiro, e é dentro dela que se espera o resumo "Pronto…".
   */
  private fun abrirResumoDoAquecimento() {
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("mais_diagnostico"), TIMEOUT)
    if (composeTestRule.onAllNodesWithTag("cartao_aquecimento").fetchSemanticsNodes().isEmpty()) {
      composeTestRule.onNodeWithTag("mais_diagnostico").performClick()
    }
    composeTestRule.waitUntilAtLeastOneExists(hasText("Pronto", substring = true), 60_000L)
    composeTestRule.onNodeWithTag("cartao_aquecimento").performClick()
    composeTestRule.waitUntilAtLeastOneExists(
        hasText(targetContext.getString(R.string.aquecimento_etapa_avatar), substring = true), TIMEOUT)
  }

  private fun esperarAquecimento() =
      composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT_AQUECIMENTO)

  /**
   * ①.5 (docs/consentimento-por-atendimento-plano.md): "iniciar" pede consentimento antes de
   * ligar a câmera. Chamar logo depois de clicar "botao_principal" pela primeira vez no
   * atendimento — "repita"/"Encerrar agora" reabrem a captura sem passar por ①.5 de novo.
   */
  private fun aceitarConsentimento() {
    esperarTexto(R.string.estado_pedindo_consentimento)
    composeTestRule.onNodeWithTag("consentimento_aceitar_button").performClick()
  }

  private fun esperarTexto(id: Int) =
      composeTestRule.waitUntilAtLeastOneExists(hasText(targetContext.getString(id)), TIMEOUT)

  private fun limparLogcat() {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -c").close()
  }

  private fun logcat(): String =
      InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d").let { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
      }

  private fun copiarAsset(nome: String): Uri {
    val destino = File(targetContext.cacheDir, nome)
    InstrumentationRegistry.getInstrumentation().context.assets.open(nome).use { entrada ->
      destino.outputStream().use { entrada.copyTo(it) }
    }
    return Uri.fromFile(destino)
  }
}

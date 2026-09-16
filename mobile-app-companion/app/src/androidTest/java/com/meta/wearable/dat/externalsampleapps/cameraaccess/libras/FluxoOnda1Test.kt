/*
 * O fluxo do atendimento pela tela, com os óculos simulados (docs/prontidao-demo, ondas 1 e 3).
 *
 * Cobre o que dá para provar sem voz e sem sinais reconhecidos (o plant.mp4 não tem pessoa):
 *   - 3.1: o botão principal ("Iniciar"), sem preview, abre a captura com "Aguarde…", sem o erro falso
 *     do MediaPipe (onde a biblioteca nativa não existe, confere que o erro aparece e não prende nada);
 *   - 7.1: a janela pede tela ligada enquanto há sessão com os óculos, e deixa de pedir sem ela;
 *   - 2.8 e 4.7: "Encerrar agora" sem nenhum sinal pede repetição — o aviso é falado, a captura reabre
 *     com a câmera ligada — e a terceira rejeição seguida desiste e volta ao ①.
 *
 * O caminho até a escuta (⑤) só existe quando uma frase é aceita, o que exige sinais: fica nos
 * cenários manuais do guia de testes (A5), junto com a transcrição e o avatar.
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
    private const val TIMEOUT_AQUECIMENTO = 180_000L
    // Os avisos do "repita" são falados pelo Piper; a primeira fala carrega o modelo.
    private const val TIMEOUT_FALA = 90_000L
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
  fun capturaSemSinaisPedeRepeticaoAteDesistirComATelaLigada() {
    parearOculos()
    abrirControlesDaSessao()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    assertEquals("sem sessão a tela pode apagar", false, telaLigadaPedida())

    composeTestRule.onNodeWithTag("start_session_button").performClick()
    // O "iniciar" espera o aquecimento (6.4): a primeira abertura copia os modelos para o disco.
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT_AQUECIMENTO)
    composeTestRule.waitUntil(TIMEOUT) { telaLigadaPedida() }
    // O pedido chegou ao sistema: o WindowManager está segurando a tela por uma janela do app.
    composeTestRule.waitUntil(TIMEOUT) { janelaQueSeguraATela()?.contains("MainActivity") == true }

    // ① -> ② pelo botão principal, sem ter tocado em preview.
    composeTestRule.onNodeWithTag("botao_principal").performClick()
    confirmarPermissaoDeCameraSePedida()
    esperarEstado("capturando_sinais")
    conferirIndicadorDaCaptura()

    // 2.8: sem nenhum sinal, "Encerrar agora" pede repetição e a captura reabre (duas vezes)...
    val repita = targetContext.getString(R.string.conversa_decisao_repita)
    for (vez in 1..2) {
      composeTestRule.onNodeWithTag("botao_principal").performClick()
      composeTestRule.waitUntil(TIMEOUT_FALA) {
        composeTestRule.onAllNodesWithText(repita).fetchSemanticsNodes().size == vez
      }
      esperarEstado("capturando_sinais", TIMEOUT_FALA)
    }
    // ...e a terceira rejeição seguida desiste e volta ao ①.
    composeTestRule.onNodeWithTag("botao_principal").performClick()
    composeTestRule.waitUntilExactlyOneExists(
        hasText(targetContext.getString(R.string.conversa_decisao_desistiu)), TIMEOUT_FALA)
    esperarEstado("aguardando_sinal", TIMEOUT_FALA)

    composeTestRule.onNodeWithTag("end_session_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button"), TIMEOUT)
    composeTestRule.waitUntil(TIMEOUT) { !telaLigadaPedida() }
  }

  /**
   * 4.7: com sessão ativa, a tecla de volume faz o mesmo que o botão principal; sem sessão, não é
   * consumida pelo app. A tecla vai pelo sistema (`input keyevent`), como a de um controle Bluetooth.
   */
  @Test
  fun teclaDeVolumeFazOMesmoQueOBotaoPrincipal() {
    parearOculos()
    abrirControlesDaSessao()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button").and(isEnabled()), TIMEOUT)
    // Sem sessão: a tecla não mexe no diálogo.
    teclaVolume()
    Thread.sleep(1_000)
    esperarEstado("aguardando_sinal")

    composeTestRule.onNodeWithTag("start_session_button").performClick()
    // O "iniciar" espera o aquecimento (6.4): a primeira abertura copia os modelos para o disco.
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("botao_principal").and(isEnabled()), TIMEOUT_AQUECIMENTO)
    // ① -> ② pela tecla.
    teclaVolume()
    confirmarPermissaoDeCameraSePedida()
    esperarEstado("capturando_sinais")

    composeTestRule.onNodeWithTag("cancelar_atendimento_button").performClick()
    esperarEstado("aguardando_sinal")
    composeTestRule.onNodeWithTag("end_session_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("start_session_button"), TIMEOUT)
  }

  private fun teclaVolume() {
    InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .executeShellCommand("input keyevent KEYCODE_VOLUME_UP")
        .close()
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
  }

  private fun conferirIndicadorDaCaptura() {
    if (mediapipeDisponivel()) {
      composeTestRule.waitUntilExactlyOneExists(hasText(targetContext.getString(R.string.libras_aguarde)), TIMEOUT)
      assertTrue(
          "o erro falso do defeito A apareceu",
          composeTestRule.onAllNodesWithText(LandmarkPipeline.ERRO_MODELOS).fetchSemanticsNodes().isEmpty(),
      )
    } else {
      composeTestRule.waitUntilExactlyOneExists(hasText(LandmarkPipeline.ERRO_MODELOS), TIMEOUT)
    }
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

  // ①.5 (docs/consentimento-por-atendimento-plano.md): "iniciar" agora pede consentimento antes
  // de ligar a câmera. Só no primeiro turno do atendimento — "Encerrar agora"/"repita" reabrem a
  // captura sem passar por ①.5 de novo (consentimento é por atendimento, não por turno).
  private fun aceitarConsentimentoSePedido() {
    val pedindo = targetContext.getString(R.string.estado_pedindo_consentimento)
    composeTestRule.waitUntil(TIMEOUT) {
      composeTestRule.onAllNodesWithText(pedindo).fetchSemanticsNodes().isNotEmpty() ||
          composeTestRule.onAllNodesWithText(estadoTexto("capturando_sinais")).fetchSemanticsNodes().isNotEmpty()
    }
    if (composeTestRule.onAllNodesWithText(pedindo).fetchSemanticsNodes().isNotEmpty()) {
      composeTestRule.onNodeWithTag("consentimento_aceitar_button").performClick()
    }
  }

  // A permissão de câmera começa negada no mock: o primeiro stream pede a confirmação.
  private fun confirmarPermissaoDeCameraSePedida() {
    aceitarConsentimentoSePedido()
    val continuar = targetContext.getString(R.string.camera_permission_continue)
    composeTestRule.waitUntil(TIMEOUT) {
      composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty() ||
          composeTestRule.onAllNodesWithText(estadoTexto("capturando_sinais")).fetchSemanticsNodes().isNotEmpty()
    }
    if (composeTestRule.onAllNodesWithText(continuar).fetchSemanticsNodes().isNotEmpty()) {
      composeTestRule.onNodeWithText(continuar).performClick()
    }
  }

  // O AAR do MediaPipe até a 0.10.14 não trazia x86_64; a partir da 0.10.35 a biblioteca mudou de nome.
  private fun mediapipeDisponivel(): Boolean =
      listOf("mediapipe_tasks_vision_jni", "mediapipe_tasks_jni").any {
        runCatching { System.loadLibrary(it) }.isSuccess
      }

  private fun estadoTexto(estado: String): String =
      targetContext.getString(
          when (estado) {
            "aguardando_sinal" -> R.string.estado_aguardando_sinal
            "capturando_sinais" -> R.string.estado_capturando
            "falando" -> R.string.estado_falando
            "aguardando_resposta" -> R.string.estado_aguardando_resposta
            "escutando_atendente" -> R.string.estado_ouvindo
            "transcrevendo" -> R.string.estado_transcrevendo
            else -> R.string.estado_avatar
          })

  private fun esperarEstado(estado: String, timeout: Long = TIMEOUT) =
      composeTestRule.waitUntilExactlyOneExists(hasText(estadoTexto(estado)), timeout)

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

  // 10.3: os controles do sample ficam numa área recolhível.
  private fun abrirControlesDaSessao() {
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), TIMEOUT)
    if (composeTestRule.onAllNodesWithTag("start_session_button").fetchSemanticsNodes().isEmpty() &&
        composeTestRule.onAllNodesWithTag("end_session_button").fetchSemanticsNodes().isEmpty()) {
      composeTestRule.onNodeWithTag("mais_controles").performClick()
    }
  }

  private fun copiarAsset(nome: String): Uri {
    val destino = File(targetContext.cacheDir, nome)
    InstrumentationRegistry.getInstrumentation().context.assets.open(nome).use { entrada ->
      destino.outputStream().use { entrada.copyTo(it) }
    }
    return Uri.fromFile(destino)
  }
}

package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.dialogo

import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.FalhaCamera
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.PoliticaCamera
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.SttEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.TtsEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar.DesfechoAvatar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao.PassthroughGlossContextualizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.Classificacao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Orquestrador real, portas falsas: nenhum DAT, Context, modelo nativo, sleep ou áudio real. */
@OptIn(ExperimentalCoroutinesApi::class)
class DialogOrchestratorCameraTest {
  @Test
  fun `nao abre antes de aceitar e preview nao inicia reconhecimento`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.pedirPreview()
    runCurrent()
    assertEquals(DialogState.PEDINDO_CONSENTIMENTO, c.estado)
    assertEquals(0, c.pedidosCamera)
    assertFalse(c.politica.permitida)
    c.dialogo.aceitarConsentimento()
    runCurrent()
    assertTrue(c.cameraAtiva)
    assertEquals(0, c.capturas)
    assertEquals(DialogState.AGUARDANDO_SINAL, c.estado)

    // Iniciar depois de preview não aproveita implicitamente o consentimento do sample.
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    assertFalse(c.cameraAtiva)
    assertFalse(c.politica.permitida)
    c.dialogo.aceitarConsentimento()
    runCurrent()
    assertEquals(1, c.capturas)
    assertEquals(DialogState.CAPTURANDO_SINAIS, c.estado)
  }

  @Test
  fun `aceitar recusar cancela ensure e desliga mesmo sem estado capturando`() = runTest {
    val c = Cenario(backgroundScope)
    c.portaCamera = CompletableDeferred()
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.cameraAtiva = true // SDK começou, ensure ainda espera confirmação de STREAMING.
    c.dialogo.recusarConsentimento()
    runCurrent()
    assertEquals(1, c.esperasCanceladas)
    assertFalse(c.cameraAtiva)
    assertFalse(c.politica.consentimento)
    assertEquals(DialogState.AGUARDANDO_SINAL, c.estado)
    c.portaCamera!!.complete(Unit)
    runCurrent()
    assertEquals(0, c.capturas)
    assertFalse(c.cameraAtiva)
  }

  @Test
  fun `callback nao cooperativo antigo nao religa nem limpa guarda de novo aceitar`() = runTest {
    val c = Cenario(backgroundScope)
    c.cameraNaoCoopera = true
    val antiga = CompletableDeferred<Unit>()
    c.portaCamera = antiga
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.recusarConsentimento()
    runCurrent()

    val nova = CompletableDeferred<Unit>()
    c.portaCamera = nova
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    antiga.complete(Unit)
    runCurrent()
    assertFalse(c.cameraAtiva)
    c.dialogo.aceitarConsentimento() // finally antigo não pode liberar duplo Aceitar novo.
    runCurrent()
    assertEquals(2, c.pedidosCamera)
    nova.complete(Unit)
    runCurrent()
    assertEquals(1, c.capturas)
    assertTrue(c.cameraAtiva)
  }

  @Test
  fun `bateria baixa durante abertura invalida retorno tardio e bloqueia novos pedidos`() = runTest {
    val c = Cenario(backgroundScope)
    c.cameraNaoCoopera = true
    c.portaCamera = CompletableDeferred()
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onBateriaBaixa()
    runCurrent()
    assertEquals(DialogState.AGUARDANDO_SINAL, c.estado)
    assertFalse(c.cameraAtiva)
    c.portaCamera!!.complete(Unit)
    runCurrent()
    c.dialogo.pedirPreview()
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    c.dialogo.aceitarConsentimento()
    runCurrent()
    assertEquals(1, c.pedidosCamera)
    assertEquals(0, c.capturas)
    assertTrue(c.politica.economia)
    assertFalse(c.cameraAtiva)
  }

  @Test
  fun `bateria baixa encerra captura e efeito e idempotente`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onBateriaBaixa()
    runCurrent()
    val paradas = c.paradasCamera
    c.dialogo.onBateriaBaixa()
    runCurrent()
    assertEquals(paradas, c.paradasCamera)
    assertEquals(1, c.finsCaptura)
    assertFalse(c.cameraAtiva)
    assertEquals(DialogState.AGUARDANDO_SINAL, c.estado)
  }

  @Test
  fun `bateria no TTS de repita nao permite iniciarCaptura tardio`() = runTest {
    val c = Cenario(backgroundScope)
    c.portaRepita = CompletableDeferred()
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onWakeWord(WakeWord.ENCERRAR) // Sem sinais, fechamento manual pede repetição.
    runCurrent()
    assertEquals(DialogState.FALANDO, c.estado)
    assertTrue(c.cameraAtiva)
    assertEquals(listOf(DialogOrchestrator.AVISO_REPITA), c.falas)
    c.dialogo.onBateriaBaixa()
    assertFalse(c.cameraAtiva)
    assertTrue(c.paradasVoz > 0)
    c.portaRepita!!.complete(Unit)
    runCurrent()
    assertEquals(1, c.capturas)
    assertEquals(DialogState.AGUARDANDO_SINAL, c.estado)
  }

  @Test
  fun `bateria enquanto pipeline fecha nao permite repetir depois`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.portaFimCaptura = CompletableDeferred()
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    c.dialogo.onBateriaBaixa()
    runCurrent()
    c.portaFimCaptura!!.complete(Unit)
    runCurrent()
    assertEquals(1, c.capturas)
    assertTrue(c.falas.isEmpty())
    assertFalse(c.cameraAtiva)
    assertEquals(DialogState.AGUARDANDO_SINAL, c.estado)
  }

  @Test
  fun `repeticao sem economia mantem captura e consentimento`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    assertEquals(2, c.capturas)
    assertEquals(1, c.pedidosCamera)
    assertTrue(c.politica.permitida)
    assertTrue(c.cameraAtiva)
  }

  @Test
  fun `guarda final consulta politica mesmo antes do efeito de bateria no dialogo`() = runTest {
    val c = Cenario(backgroundScope)
    c.portaRepita = CompletableDeferred()
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    c.politica.ativarEconomia() // VM já bloqueou; onBateriaBaixa do diálogo ainda não chegou.
    c.portaRepita!!.complete(Unit)
    runCurrent()
    assertEquals(1, c.capturas)
    assertFalse(c.cameraAtiva)
    assertEquals(DialogState.AGUARDANDO_SINAL, c.estado)
  }

  @Test
  fun `confirmacao timeout e fala seguem em economia sem novo modo voz somente`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onSignRecognized(Classificacao("filho", 0.99f, margem = 0.9f))
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    assertEquals(DialogState.CONFIRMANDO_RECONHECIMENTO, c.estado)
    assertFalse(c.cameraAtiva)
    c.dialogo.onBateriaBaixa()
    assertEquals(DialogState.CONFIRMANDO_RECONHECIMENTO, c.estado)
    advanceTimeBy(DialogOrchestrator.TETO_CONFIRMACAO_MS)
    runCurrent()
    assertEquals(listOf("filho"), c.falas)
    assertEquals(DialogState.FALANDO, c.estado)
    advanceTimeBy(Transicoes.FOLGA_APOS_FALA_MS)
    runCurrent()
    assertEquals(DialogState.ESCUTANDO_ATENDENTE, c.estado)
    assertEquals(1, c.escutas)
  }

  @Test
  fun `corrigir em voo preserva fallback quando bateria impede abertura`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onSignRecognized(Classificacao("filho", 0.99f, margem = 0.9f))
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    c.portaCamera = CompletableDeferred()
    c.dialogo.corrigirReconhecimento()
    runCurrent()
    c.dialogo.onBateriaBaixa()
    c.portaCamera!!.complete(Unit)
    runCurrent()
    assertEquals(1, c.capturas)
    assertFalse(c.cameraAtiva)
    assertEquals(listOf("filho"), c.falas)
    assertEquals(DialogState.FALANDO, c.estado)
    assertTrue(FalhaCamera.BATERIA_BAIXA in c.falhas)
  }

  @Test
  fun `cancelar atendimento e inatividade revogam preview`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.pedirPreview()
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.cancelarAtendimento()
    assertFalse(c.politica.consentimento)
    assertFalse(c.cameraAtiva)
    c.dialogo.pedirPreview()
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    advanceTimeBy(60_000L)
    runCurrent()
    assertFalse(c.politica.consentimento)
    assertFalse(c.cameraAtiva)
  }

  @Test
  fun `confirmar durante corrigir cancela abertura e fala uma unica vez`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onSignRecognized(Classificacao("filho", 0.99f, margem = 0.9f))
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    val abertura = CompletableDeferred<Unit>()
    c.portaCamera = abertura
    c.dialogo.corrigirReconhecimento()
    runCurrent()
    c.cameraAtiva = true // recurso adquirido, mas STREAMING ainda não chegou.
    c.dialogo.confirmarReconhecimento()
    c.dialogo.confirmarReconhecimento()
    runCurrent()
    assertEquals(1, c.esperasCanceladas)
    assertFalse(c.cameraAtiva)
    assertEquals(listOf("filho"), c.falas)
    abertura.complete(Unit)
    runCurrent()
    assertEquals(1, c.capturas)
    assertFalse(c.cameraAtiva)
    advanceTimeBy(Transicoes.FOLGA_APOS_FALA_MS)
    runCurrent()
    assertEquals(1, c.escutas)
  }

  @Test
  fun `confirmar invalida retorno nao cooperativo de corrigir mesmo sem cancelar atendimento`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onSignRecognized(Classificacao("filho", 0.99f, margem = 0.9f))
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    c.cameraNaoCoopera = true
    val antiga = CompletableDeferred<Unit>()
    c.portaCamera = antiga
    c.dialogo.corrigirReconhecimento()
    runCurrent()
    c.dialogo.confirmarReconhecimento()
    runCurrent()
    antiga.complete(Unit)
    runCurrent()
    assertFalse(c.cameraAtiva)
    assertEquals(1, c.capturas)
    assertEquals(listOf("filho"), c.falas)
    assertEquals(DialogState.FALANDO, c.estado)
  }

  @Test
  fun `corrigir sem falha reabre captura e confirmar atrasado nao desliga camera nova`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onSignRecognized(Classificacao("filho", 0.99f, margem = 0.9f))
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    c.dialogo.corrigirReconhecimento()
    runCurrent()
    c.dialogo.confirmarReconhecimento()
    runCurrent()
    assertTrue(c.cameraAtiva)
    assertEquals(2, c.capturas)
    assertTrue(c.falas.isEmpty())
    assertEquals(DialogState.CAPTURANDO_SINAIS, c.estado)
  }

  @Test
  fun `retorno de corrigir confirmado nao religa nem libera guarda de nova abertura`() = runTest {
    val c = Cenario(backgroundScope)
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    c.dialogo.aceitarConsentimento()
    runCurrent()
    c.dialogo.onSignRecognized(Classificacao("filho", 0.99f, margem = 0.9f))
    c.dialogo.onWakeWord(WakeWord.ENCERRAR)
    runCurrent()
    c.cameraNaoCoopera = true
    val antiga = CompletableDeferred<Unit>()
    c.portaCamera = antiga
    c.dialogo.corrigirReconhecimento()
    runCurrent()
    c.dialogo.confirmarReconhecimento()
    runCurrent()
    assertFalse(c.cameraAtiva)
    assertEquals(listOf("filho"), c.falas)

    c.dialogo.cancelarAtendimento()
    c.dialogo.onWakeWord(WakeWord.INICIAR)
    runCurrent()
    val nova = CompletableDeferred<Unit>()
    c.portaCamera = nova
    c.dialogo.aceitarConsentimento()
    runCurrent()
    antiga.complete(Unit)
    runCurrent()
    assertFalse(c.cameraAtiva)
    c.dialogo.aceitarConsentimento()
    runCurrent()
    assertEquals(3, c.pedidosCamera) // captura original, Corrigir, novo Aceitar (sem duplicata).
    nova.complete(Unit)
    runCurrent()
    assertTrue(c.cameraAtiva)
    assertEquals(2, c.capturas)
    assertEquals(listOf("filho"), c.falas)
  }

  private class Cenario(scope: CoroutineScope) {
    val politica = PoliticaCamera()
    var portaCamera: CompletableDeferred<Unit>? = null
    var portaRepita: CompletableDeferred<Unit>? = null
    var portaFimCaptura: CompletableDeferred<Unit>? = null
    var cameraNaoCoopera = false
    var cameraAtiva = false
    var pedidosCamera = 0
    var esperasCanceladas = 0
    var paradasCamera = 0
    var capturas = 0
    var finsCaptura = 0
    var paradasVoz = 0
    var escutas = 0
    val falas = mutableListOf<String>()
    val falhas = mutableListOf<FalhaCamera>()
    val estado get() = dialogo.state.value

    val dialogo = DialogOrchestrator(
        scope = scope,
        politicaCamera = politica,
        landmarkPipeline = CapturaDialogo(
            startSession = { capturas++ },
            endSession = { finsCaptura++; portaFimCaptura?.await(); Unit },
            retomarDepoisDePausa = {},
        ),
        speaker = object : TtsEngine {
          override suspend fun speakAndAwait(text: String, onInicioAudio: () -> Unit): Boolean {
            falas += text
            onInicioAudio()
            if (text == DialogOrchestrator.AVISO_REPITA) portaRepita?.await()
            return true
          }
          override fun stop() { paradasVoz++ }
          override fun shutdown() {}
        },
        sttEngine = object : SttEngine {
          override fun start(onResult: (String) -> Unit, onError: (Throwable) -> Unit, onFimDeFala: () -> Unit) {
            escutas++
          }
          override fun stop() {}
        },
        contextualizer = PassthroughGlossContextualizer(),
        ensureCameraActive = abrir@ {
          pedidosCamera++
          val token = politica.token() ?: return@abrir falhaPolitica()
          val porta = portaCamera
          try {
            if (cameraNaoCoopera) withContext(NonCancellable) { porta?.await() }
            else porta?.await()
          } catch (e: CancellationException) {
            esperasCanceladas++
            throw e
          }
          if (!politica.valida(token)) return@abrir falhaPolitica()
          cameraAtiva = true
          null
        },
        deactivateCamera = {
          // Mesmo contrato do VM: invalida inclusive sem stream criado.
          politica.invalidarAbertura()
          paradasCamera++
          cameraAtiva = false
        },
        playAvatar = { DesfechoAvatar.ANIMOU },
        aoIniciarCaptura = {},
        releaseAvatar = {},
        onAvatarUnavailable = {},
        onFalhaCamera = { falhas += it },
    )

    private fun falhaPolitica() =
        if (politica.economia) FalhaCamera.BATERIA_BAIXA else FalhaCamera.STREAM_NAO_SUBIU
  }
}
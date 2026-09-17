package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoClassificador
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.TfliteSignClassifier
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dois am instrument SEPARADOS; fechar/recreate Activity no mesmo processo não serve.
 * Não há @Before/reset nem @After/clear entre fases. Só fase 2 (ou recuperação explícita) restaura.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealExperimentalEntreProcessosTest {
  @get:Rule val compose = createEmptyComposeRule()
  private val context get() = Etapa4Suporte.context
  private val temporario get() = context.getSharedPreferences("etapa4_persistencia_test_owned", Context.MODE_PRIVATE)
  private val preferencias get() = context.getSharedPreferences("configuracoes_demo", Context.MODE_PRIVATE)

  /** Journal REAL verdadeiro, sem UI/grafo. Não retorna: host deve interromper o processo. */
  @Test fun prepararInterrupcaoReal() {
    val execucao = Etapa4Suporte.optIn("etapa4InterromperReal")
    val marcador = Etapa4Suporte.argumentoUuid("etapa4Marcador")
    val identidade = Etapa4Suporte.identidade()
    check(temporario.all.isEmpty()) { "Journal REAL pendente; recuperar pelo dono" }
    EstadoRecusadoEtapa4.exigirSemPendente()
    val snapshot = InterrupcaoEtapa4.snapshot()
    val configs = ConfiguracoesDemo.de(context)
    val original = configs.valores.value.limiarConfianca
    val novo = if (original == 0.83f) 0.77f else 0.83f
    val registro = processo().put("execucao", execucao).put("marcador", marcador)
        .put("fase", "PREPARANDO").put("snapshot", snapshot).put("limiar_original", original.toString())
        .put("limiar_novo", novo.toString()).put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
        .put("identidade", identidade)
    salvar(registro)
    // Sem catch/finally restaurador: qualquer falha mantém journal recuperável pelo dono.
    configs.atualizar { it.copy(limiarConfianca = novo) }
    check(preferencias.edit().commit())
    check(preferencias.getString("limiar_confianca", null) == novo.toString())
    InterrupcaoEtapa4.publicarEAguardar(registro, "REAL", "prepararInterrupcaoReal", ::salvar)
  }

  @Test fun fase1Gravar() {
    val execucao = Etapa4Suporte.optIn("etapa4RealPersistencia")
    val marcador = Etapa4Suporte.argumentoUuid("etapa4Marcador")
    val identidade = Etapa4Suporte.identidade()
    assertTrue("Há fase interrompida: recupere explicitamente pelo UUID dono; não limpar por padrão",
        temporario.all.isEmpty())
    EstadoRecusadoEtapa4.exigirSemPendente()
    val configs = ConfiguracoesDemo.de(context)
    val original = configs.valores.value.limiarConfianca
    val novo = if (original == 0.83f) 0.77f else 0.83f
    // Cópia exata das strings persistidas, incluindo ausência de chaves. atualizar() serializa
    // todos os campos; restaurar só o limiar deixaria valores materializados que antes não existiam.
    val snapshot = JSONObject()
    preferencias.all.forEach { (chave, valor) ->
      check(valor is String) { "Tipo novo em ConfiguracoesDemo: $chave; adaptar snapshot" }
      snapshot.put(chave, valor)
    }
    val registro = processo().put("execucao", execucao).put("marcador", marcador)
        .put("fase", "PREPARANDO").put("snapshot", snapshot).put("limiar_original", original.toString())
        .put("limiar_novo", novo.toString()).put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
        .put("identidade", identidade)
    salvar(registro)
    try {
      comActivity { vm ->
        validarReal(vm, identidade, original)
        // Escrita pelo mesmo singleton usado pela tela/VM, nunca editando a chave à mão.
        configs.atualizar { it.copy(limiarConfianca = novo) }
        // commit síncrono espera também as gravações apply pendentes neste SharedPreferences.
        check(preferencias.edit().commit()) { "Falha ao persistir ConfiguracoesDemo" }
        assertEquals(novo.toString(), preferencias.getString("limiar_confianca", null))
        compose.waitUntil(20_000L) { vm.uiState.value.limiarClassificador == novo }
        validarReal(vm, identidade, novo)
      }
      registro.put("fase", "GRAVADO")
      salvar(registro) // Somente depois da Activity fechada e da gravação concluída.
      Etapa4Suporte.prova(prova(registro, "fase1Gravar"))
    } catch (erro: Throwable) {
      try { restaurar(registro) } catch (limpeza: Throwable) { erro.addSuppressed(limpeza) }
      throw erro
    }
  }

  @Test fun fase2VerificarAntesDeAlterar() {
    val execucao = Etapa4Suporte.optIn("etapa4RealPersistencia")
    val salvo = carregarDoDono(execucao) // Sem defaults: execução isolada/fase 1 ausente FALHA.
    var evidencia: JSONObject? = null
    try {
      val marcador = Etapa4Suporte.argumentoUuid("etapa4Marcador")
      assertNotEquals("Marcador de invocação não é novo", salvo.getString("marcador"), marcador)
      assertEquals("GRAVADO", salvo.getString("fase"))
      val atual = processo()
      assertTrue("Mesmo processo: ActivityScenario/recreate não prova persistência",
          salvo.getInt("pid") != atual.getInt("pid") ||
              salvo.getLong("start_elapsed_ms") != atual.getLong("start_elapsed_ms"))
      assertNotEquals("UUID estático do processo foi reutilizado",
          salvo.getString("processo_uuid"), atual.getString("processo_uuid"))
      val identidade = Etapa4Suporte.identidade()
      assertEquals(salvo.getString("identidade_sha256"), BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
      for (chave in listOf("modelo_sha256", "sidecar_sha256", "checkpoint_sha256", "experimento", "calibracao")) {
        assertEquals(chave, salvo.getJSONObject("identidade").getString(chave), identidade.getString(chave))
      }
      val esperado = salvo.getString("limiar_novo").toFloat()
      assertEquals("Não há limiar salvo em disco", esperado.toString(), preferencias.getString("limiar_confianca", null))
      // PRIMEIRO uso do singleton neste novo processo. Nenhum atualizar/voltarAoPadrao antes disso.
      assertEquals(esperado, ConfiguracoesDemo.de(context).valores.value.limiarConfianca, 0f)
      comActivity { vm -> validarReal(vm, identidade, esperado) }
      evidencia = prova(salvo, "fase2VerificarAntesDeAlterar")
          .put("processo_anterior", JSONObject().put("pid", salvo.getInt("pid"))
              .put("start_elapsed_ms", salvo.getLong("start_elapsed_ms"))
              .put("processo_uuid", salvo.getString("processo_uuid")))
          .put("marcador", marcador).put("verificado_antes_de_alterar", true)
    } finally {
      // Apenas após as verificações; inclusive se falharem, restaurar o snapshot do dono.
      restaurar(salvo)
    }
    Etapa4Suporte.prova(checkNotNull(evidencia).put("cleanup", true))
  }

  /** Recuperação opt-in de crash/host interrompido. Não faz parte das duas fases e não é evidência. */
  @Test fun limparEstadoInterrompido() {
    val execucao = Etapa4Suporte.optIn("etapa4RecuperarPersistencia")
    Etapa4Suporte.identidade()
    val salvo = carregarDoDono(execucao)
    assertEquals("Pacote diferente do snapshot REAL; não restaurar",
        salvo.getString("identidade_sha256"), BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
    val interrupcao = InterrupcaoEtapa4.verificarRecuperacao(salvo, "REAL")
    restaurar(salvo)
    val prova = processo().put("execucao", execucao)
      .put("fase", "limparEstadoInterrompido").put("cleanup", true)
      .put("tipo", "REAL").put("status", "RECUPERADO_SEM_EVIDENCIA")
      .put("identidade_sha256", BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256)
    InterrupcaoEtapa4.concluirRecuperacao(prova, interrupcao)
    Etapa4Suporte.prova(prova)
  }

  private fun comActivity(corpo: (CameraViewModel) -> Unit) {
    Etapa4Suporte.permissoes()
    var scenario: ActivityScenario<MainActivity>? = null
    var barreira: BarreiraEncerramentoEtapa4? = null
    var erro: Throwable? = null
    try {
      // Pareamento não sobrevive ao force-stop. Repetir em CADA fase, sem reset/configs/stream.
      Etapa4Suporte.prepararMock()
      val aberta = ActivityScenario.launch(MainActivity::class.java)
      scenario = aberta
      compose.waitUntilAtLeastOneExists(hasTestTag("mais_controles"), 20_000L)
      lateinit var vm: CameraViewModel
      aberta.onActivity {
        vm = Etapa4Suporte.vm(it)
        barreira = BarreiraEncerramentoEtapa4(vm)
      }
      compose.waitUntil(20_000L) { vm.uiState.value.classificador != null }
      corpo(vm)
    } catch (e: Throwable) {
      erro = e
    } finally {
      fun limpar(acao: () -> Unit) {
        try { acao() } catch (e: Throwable) { if (erro == null) erro = e else erro!!.addSuppressed(e) }
      }
      limpar { scenario?.close() }
      limpar { barreira?.aguardar() }
      limpar { MockDeviceKit.getInstance(context).disable() }
    }
    erro?.let { throw it }
  }

  private fun validarReal(vm: CameraViewModel, id: JSONObject, limiar: Float) {
    assertFalse("Persistência não pode ligar stream", vm.uiState.value.hasStream || vm.uiState.value.isStartingStream)
    assertNull(campoEtapa4<Any?>(vm, "stream"))
    assertNull(campoEtapa4<Any?>(vm, "camera"))
    val diagnostico = checkNotNull(vm.uiState.value.classificador)
    assertEquals(diagnostico.motivo, ModoClassificador.REAL_EXPERIMENTAL, diagnostico.modo)
    val carregada = checkNotNull(diagnostico.identidade)
    assertEquals(id.getString("modelo_sha256"), carregada.modeloSha256)
    assertEquals(id.getString("sidecar_sha256"), carregada.sidecarSha256)
    assertEquals(id.getString("checkpoint_sha256"), carregada.checkpointSha256)
    assertEquals(id.getString("experimento"), carregada.experimento)
    assertEquals("ausente_nao_calibrado", carregada.calibracao)
    val pipeline = campoEtapa4<Any>(vm, "landmarkPipeline")
    assertTrue("Não aceitar diagnóstico sem classificador real no pipeline",
        campoEtapa4<Any>(pipeline, "classifier") is TfliteSignClassifier)
    compose.waitUntil(20_000L) { vm.uiState.value.limiarClassificador == limiar }
    compose.onNodeWithText("REAL EXPERIMENTAL", substring = true).assertIsDisplayed()
    compose.onNodeWithText("limiar manual: $limiar", substring = true).assertIsDisplayed()
  }

  private fun salvar(registro: JSONObject) {
    check(temporario.edit().putString("registro", registro.toString()).commit()) { "Marcador não persistido" }
  }

  private fun carregarDoDono(execucao: String): JSONObject {
    EstadoRecusadoEtapa4.exigirSemPendente()
    check(temporario.all.keys == setOf("registro")) { "Journal REAL ausente ou inesperado" }
    return JSONObject(checkNotNull(temporario.getString("registro", null)) { "Fase 1/UUID persistido ausente" }).also {
        assertEquals("UUID dono difere; não tocar estado alheio", execucao, it.getString("execucao"))
      }
  }

  private fun restaurar(registro: JSONObject) {
    InterrupcaoEtapa4.restaurar(registro.getJSONObject("snapshot"))
    check(temporario.edit().clear().commit()) { "Marcador temporário não removido" }
  }

  private fun prova(registro: JSONObject, fase: String): JSONObject = processo()
      .put("execucao", registro.getString("execucao")).put("fase", fase)
      .put("marcador", registro.getString("marcador")).put("limiar", registro.getString("limiar_novo"))
      .put("identidade_sha256", registro.getString("identidade_sha256"))
      .put("identidade", registro.getJSONObject("identidade"))

  private fun processo() = JSONObject().put("pid", Process.myPid())
      .put("start_elapsed_ms", Process.getStartElapsedRealtime()).put("processo_uuid", ProcessoEtapa4.uuid)
}

/** Inicializado uma vez por processo, nunca por Activity/método/regra. O start vem do Android. */
private object ProcessoEtapa4 { val uuid: String = UUID.randomUUID().toString() }
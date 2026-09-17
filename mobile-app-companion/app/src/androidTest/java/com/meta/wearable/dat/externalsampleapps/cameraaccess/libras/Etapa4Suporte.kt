package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.CarregadorClassificador
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.UUID

/** Somente androidTest. Não altera produção nem os observadores/testes da etapa 3. */
internal object Etapa4Suporte {
  val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  val context get() = instrumentation.targetContext.applicationContext

  fun argumentoUuid(nome: String): String =
      checkNotNull(InstrumentationRegistry.getArguments().getString(nome)) { "Falta $nome" }.also {
        check(UUID.fromString(it).toString() == it) { "$nome deve ser UUID canônico" }
      }

  fun optIn(nome: String): String {
    assumeTrue("Opt-in explícito $nome=true não solicitado",
        InstrumentationRegistry.getArguments().getString(nome) == "true")
    assertTrue("Exige APK debug privado, nunca SIMULADO",
        BuildConfig.DEBUG && BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO)
    return argumentoUuid("etapa4Execucao")
  }

  fun identidade(): JSONObject {
    fun ler(nome: String) = context.assets.open(nome).use { it.readBytes() }
    val bytes = ler(CarregadorClassificador.IDENTIDADE)
    val hash = CarregadorClassificador.sha256(bytes)
    assertEquals(BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256, hash)
    assertEquals("Hash selecionado pelo host diverge do APK atual",
        InstrumentationRegistry.getArguments().getString("etapa4IdentidadeSha256"), hash)
    return JSONObject(String(bytes, Charsets.UTF_8)).also { id ->
      assertEquals(id.getString("modelo_sha256"), CarregadorClassificador.sha256(ler(CarregadorClassificador.MODELO)))
      assertEquals(id.getString("sidecar_sha256"), CarregadorClassificador.sha256(ler(CarregadorClassificador.SIDECAR)))
    }
  }

  fun vm(activity: MainActivity): CameraViewModel =
      ViewModelProvider(activity, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            error("CameraScreen não criou ${modelClass.name}; proibido criar outro VM")
      })[CameraViewModel::class.java]

  fun permissoes() {
    listOf("BLUETOOTH", "BLUETOOTH_CONNECT", "CAMERA", "INTERNET", "RECORD_AUDIO").forEach {
      android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
          .executeShellCommand("pm grant ${context.packageName} android.permission.$it")).use { stream ->
        stream.readBytes()
      }
    }
  }

  fun prova(json: JSONObject) {
    instrumentation.addResults(Bundle().apply { putString("etapa4_prova", json.toString()) })
  }

  /** Preparar a cada processo, ANTES de lançar MainActivity. Não lê/escreve ConfiguracoesDemo,
   * não inicia sessão/stream e não troca VM nem classificador. O chamador desabilita no finally.
   */
  fun prepararMock() = MockDeviceKit.getInstance(context).let { kit ->
    kit.enable(MockDeviceKitConfig(initialPermissionsGranted = true))
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow().apply {
      powerOn()
      don()
      unfold()
    }
  }
}

/** Capturada antes de close, aguardada FORA da main depois de close. Só observa o lifecycle
 * existente: nunca chama dispose/stop para completar artificialmente um teardown defeituoso.
 */
internal class BarreiraEncerramentoEtapa4(vm: CameraViewModel) {
  private val pipeline = campoEtapa4<Any>(vm, "landmarkPipeline")
  private val dono = checkNotNull(campoEtapa4<CoroutineScope>(vm, "encerramentoScope").coroutineContext[Job])

  fun aguardar() {
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    // onCleared cancela esse dono no finally; join espera TAMBÉM todos os filhos terminarem.
    // Isso inclui drain do produtor, fila serial DonoLeitorLandmarks, extração e classify/close.
    runBlocking { withTimeout(30_000L) { dono.join() } }
    check(dono.isCompleted) { "Escopo de teardown ainda em voo" }
    // Cancelamento do escopo por si só não prova sucesso: dispose pode ter lançado exceção.
    check(campoEtapa4<Boolean>(pipeline, "recursosFechados")) { "dispose não concluiu; observadores mantidos" }
    for (nome in listOf("decoder", "imageReader", "donoLeitor", "extractor", "readerHandler", "readerThread")) {
      check(campoEtapa4<Any?>(pipeline, nome) == null) { "Recurso não liberado: $nome" }
    }
  }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> campoEtapa4(dono: Any, nome: String): T =
    dono.javaClass.getDeclaredField(nome).apply { isAccessible = true }.get(dono) as T
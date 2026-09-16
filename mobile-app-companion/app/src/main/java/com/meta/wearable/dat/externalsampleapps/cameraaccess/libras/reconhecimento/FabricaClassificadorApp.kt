package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import android.content.res.AssetManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig

/** Mesmo adaptador usado pelo ViewModel e pelo teste do APK alvo; não lê assets de instrumentação. */
object FabricaClassificadorApp {
  fun carregar(assets: AssetManager, criarSimulado: () -> SignClassifier): CarregamentoClassificador =
      CarregadorClassificador.carregar(
          obrigatorio = BuildConfig.CLASSIFICADOR_PRIVADO_OBRIGATORIO,
          identidadeEsperada = BuildConfig.CLASSIFICADOR_IDENTIDADE_SHA256,
          temModelo = runCatching { assets.list("")?.contains(CarregadorClassificador.MODELO) == true }.getOrDefault(false),
          lerAsset = { nome -> assets.open(nome).use { it.readBytes() } },
          criarReal = { sidecar, bytes -> TfliteSignClassifier(sidecar, bytes) },
          criarSimulado = criarSimulado,
      )
}
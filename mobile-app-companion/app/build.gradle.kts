/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Meta Wearables Device Access Toolkit Setup
    // Without Developer Mode, these values need to be set with credentials from the app registered
    // in Wearables Developer Center
    manifestPlaceholders["mwdat_application_id"] = ""
    manifestPlaceholders["mwdat_client_token"] = ""
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging {
    resources {
      // Base (mwdat) + conflitos comuns quando ONNX Runtime, JNA (vosk-android) e sherpa-onnx
      // coexistem — todos empacotam licenças/metadados em META-INF com nomes que colidem entre si.
      excludes +=
          setOf(
              "/META-INF/{AL2.0,LGPL2.1}",
              "/META-INF/LICENSE*",
              "/META-INF/NOTICE*",
              "/META-INF/DEPENDENCIES",
              "/META-INF/INDEX.LIST",
          )
    }
    jniLibs {
      // libs/sherpa-onnx-1.13.8.aar é a variante "static-link-onnxruntime" da release (não a
      // genérica) — nela, libonnxruntime.so vem embutido dentro de libsherpa-onnx-jni.so pras ABIs
      // reais (arm64-v8a, armeabi-v7a), evitando colidir com o libonnxruntime.so que
      // onnxruntime-android (usado pelo wake word) também empacota. A variante ainda deixa
      // libonnxruntime.so solto pra x86 (emulador 32-bit) — pickFirst cobre só esse resíduo.
      pickFirsts += "**/libonnxruntime.so"
    }
  }

  // O .tflite precisa ficar NÃO COMPRIMIDO no APK: o Interpreter o acessa por
  // mmap a partir do asset, e um asset comprimido não é mapeável (§7.3 do plano).
  androidResources { noCompress += "tflite" }

  // android.util.Log não existe na JVM e, por padrão, qualquer chamada a ele explode nos testes
  // de unidade. Os componentes do Libras Livre logam (é como se depura em campo), então os
  // stubs devolvem valor padrão em vez de lançar. Vale só para unit tests.
  testOptions { unitTests.isReturnDefaultValues = true }
  signingConfigs {
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

// Assets que o APK precisa e que NÃO vêm no clone (externos, baixados por ../download-assets.sh)
// — mesmos caminhos que o script confere — mais o modelo interno versionado. Sem esta checagem
// o build passa e gera um APK que "funciona" em fallbacks silenciosos: sem MediaPipe não há
// reconhecimento, sem Piper/Vosk não há fala nem escuta, sem o player não há avatar.
//
// Pendurada nas tarefas merge*Assets, e não no preBuild: só quem empacota assets (APK, testes
// instrumentados) precisa deles; os testes de unidade continuam rodando sem baixar nada.
// Para um build rápido sem os modelos: -PlibrasLivre.permitirAssetsFaltando=true (vira aviso).
val assetsObrigatorios =
    listOf(
        "pose_landmarker_lite.task",
        "hand_landmarker.task",
        "tts/pt_br/pt_BR-edresson-low.onnx",
        "tts/pt_br/tokens.txt",
        "tts/pt_br/espeak-ng-data",
        "vosk-model-small-pt-0.3/final.mdl",
        "melspectrogram.onnx",
        "embedding_model.onnx",
        "vlibras/target/playerweb.data.unityweb",
        "vlibras/vlibras.js",
        "modelo_contextualizacao.tflite",
    )
val verificarAssets =
    tasks.register("verificarAssets") {
      group = "verification"
      description = "Falha se faltar algum asset obrigatório (rode ../download-assets.sh)."
      val raiz = layout.projectDirectory.dir("src/main/assets").asFile
      val permitirFaltando =
          providers.gradleProperty("librasLivre.permitirAssetsFaltando").map { it.toBoolean() }.orElse(false)
      doLast {
        val faltando =
            assetsObrigatorios.filterNot { caminho ->
              val arquivo = File(raiz, caminho)
              if (arquivo.isDirectory) !arquivo.list().isNullOrEmpty() else arquivo.length() > 0
            }
        if (faltando.isEmpty()) return@doLast
        val mensagem =
            "Assets obrigatórios ausentes em app/src/main/assets/:\n" +
                faltando.joinToString("\n") { "  - $it" } +
                "\nRode ./download-assets.sh (a partir de mobile-app-companion/). " +
                "modelo_contextualizacao.tflite vem no clone — se só ele faltar, o checkout está incompleto. " +
                "Para buildar mesmo assim: -PlibrasLivre.permitirAssetsFaltando=true"
        if (permitirFaltando.get()) logger.warn("AVISO: $mensagem") else throw GradleException(mensagem)
      }
    }
tasks
    .matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(verificarAssets) }

// ModeloContextualizacaoProvenienciaTest e TabelasDuplicadasTest leem estes arquivos direto do
// disco. Eles não são entrada dos testes de unidade por padrão: sem declarar aqui, trocar só o
// .tflite, o carimbo ou uma tabela deixa a tarefa UP-TO-DATE e as guardas nem rodam.
tasks.withType<Test>().configureEach {
  inputs
      .files(
          "src/main/assets/modelo_contextualizacao.tflite",
          "src/main/assets/modelo_contextualizacao.proveniencia.json",
          "src/main/assets/glosa_ids.json",
          "src/main/assets/destokenizar.json",
          "src/main/assets/lexico-glosas.json",
          "../../contextualization-model/artefatos/glosa_ids.json",
          "../../contextualization-model/artefatos/destokenizar.json",
          "../../contextualization-model/lexico/lexico-glosas.json",
      )
      .withPropertyName("contratoContextualizacao")
      .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.litert)
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.androidx.webkit)

  // Libras Livre — motores de IA local (docs/orquestracao-dialogo-audio-plano.md §4 itens 9-11).
  //
  // Wake word real (OpenWakeWordDetector.kt): com.rementia.openwakeword.lib está vendorizado em
  // app/src/main/java/com/rementia/ (biblioteca não publicada em Maven/JitPack) — só precisa do
  // runtime ONNX Runtime por baixo.
  implementation(libs.onnxruntime.android)

  // STT real (VoskSttEngine.kt) — publicados como .aar com classifier "@aar"; o schema do catálogo
  // de versões não representa isso, por isso ficam como coordenada literal aqui (versões vêm de
  // gradle/libs.versions.toml, interpoladas via libs.versions.*.get()).
  implementation("com.alphacephei:vosk-android:${libs.versions.voskAndroid.get()}@aar")
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

  // TTS real (PiperSherpaOnnxTtsEngine.kt) — sherpa-onnx não publica em Maven Central; consome o
  // .aar pré-compilado da release oficial (variante "static-link-onnxruntime", ver comentário em
  // packaging.jniLibs acima) baixado em app/libs/ — arquivo grande, não versionado no histórico do
  // git por padrão.
  implementation(files("libs/sherpa-onnx-1.13.8.aar"))

  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
  // Libras Livre: testes de paridade numérica (LandmarkNormalizer/HandGapImputer contra
  // computer-vision-model/treino) — puro JVM, sem Android, não precisa de emulador.
  testImplementation(libs.junit)
  testImplementation(libs.org.json)
}

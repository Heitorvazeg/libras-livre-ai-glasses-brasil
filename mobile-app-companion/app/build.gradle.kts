/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.nio.file.Files

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

// Opt-in privado: só experimentos-privados/ ou caminhos fora do repositório.
// Valida também o destino do JSON para não aceitar links para fontes versionadas.
val classificadorFixtures =
    providers.gradleProperty("librasLivre.classificadorFixtures").orNull?.let { caminho ->
      val informado = File(caminho)
      if (!informado.isAbsolute) {
        throw GradleException("librasLivre.classificadorFixtures exige um diretório absoluto.")
      }
      val repositorio = rootProject.projectDir.parentFile.canonicalFile.toPath()
      val privados = repositorio.resolve("experimentos-privados")
      val diretorio = informado.canonicalFile
      val json = File(diretorio, "paridade_classificador.json").canonicalFile
      for (arquivo in listOf(diretorio, json)) {
        val destino = arquivo.toPath()
        if (destino.startsWith(repositorio) && !destino.startsWith(privados)) {
          throw GradleException(
              "librasLivre.classificadorFixtures deve ficar em experimentos-privados/ " +
                  "ou fora do repositório (inclusive o destino canônico do JSON)."
          )
        }
      }
      if (!diretorio.isDirectory || !json.isFile) {
        throw GradleException(
            "librasLivre.classificadorFixtures exige um diretório existente " +
                "contendo paridade_classificador.json."
        )
      }
      diretorio
    }
val paridadeClassificadorJson =
    File(
        classificadorFixtures ?: file("src/androidTest/assets"),
        "paridade_classificador.json",
    ).canonicalFile

// APK principal debug: distinto de classificadorFixtures (APK de testes).
val nomesClassificadorPrivado = setOf(
    "sinal_classifier.tflite", "sinal_classifier.json", "sinal_classifier.identidade.json")
fun hashPrivado(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
val classificadorPrivado = providers.gradleProperty("librasLivre.classificadorPrivado").orNull?.let {
  val pasta = File(it)
  if (!pasta.isAbsolute) throw GradleException("classificadorPrivado exige caminho absoluto privado.")
  val repo = rootProject.projectDir.parentFile.canonicalFile.toPath()
  val destino = pasta.canonicalFile.toPath()
  if (destino.startsWith(repo) && !destino.startsWith(repo.resolve("experimentos-privados"))) {
    throw GradleException("classificadorPrivado deve ficar em experimentos-privados/ ou fora do repositório.")
  }
  generateSequence(pasta.toPath()) { p -> p.parent }.forEach { p ->
    if (Files.isSymbolicLink(p)) throw GradleException("classificadorPrivado não aceita links simbólicos.")
  }
  if (!pasta.isDirectory || pasta.list()?.toSet() != nomesClassificadorPrivado) {
    throw GradleException("Pacote privado deve conter exatamente modelo, sidecar e identidade.")
  }
  nomesClassificadorPrivado.forEach { nome ->
    val p = pasta.resolve(nome)
    if (Files.isSymbolicLink(p.toPath()) || !p.isFile) throw GradleException("Arquivo privado irregular: $nome")
  }
  pasta.canonicalFile
}
// Fixa o manifesto ao APK, além dos hashes entre os três arquivos.
val identidadePrivadaSha = classificadorPrivado?.resolve("sinal_classifier.identidade.json")
    ?.readBytes()?.let(::hashPrivado) ?: ""
val assetsClassificadorPrivado = layout.buildDirectory.dir("generated/classificadorPrivado/assets")
val prepararClassificadorPrivado = tasks.register("prepararClassificadorPrivado") {
  group = "verification"
  description = "Valida e gera somente os assets debug do classificador privado."
  inputs.property("habilitado", classificadorPrivado != null)
  inputs.property("identidadeSha", identidadePrivadaSha)
  classificadorPrivado?.let { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE) }
  outputs.dir(assetsClassificadorPrivado)
  // Revalidar sempre; inclusive remoção da propriedade entre builds, sem resíduo.
  outputs.upToDateWhen { false }
  doLast {
    val destino = assetsClassificadorPrivado.get().asFile
    val pasta = classificadorPrivado
    if (pasta != null) {
      if (pasta.list()?.toSet() != nomesClassificadorPrivado) throw GradleException("Pacote privado mudou.")
      nomesClassificadorPrivado.forEach { nome ->
        if (!pasta.resolve(nome).isFile || Files.isSymbolicLink(pasta.resolve(nome).toPath()))
          throw GradleException("Arquivo privado irregular: $nome")
      }
      val modelo = pasta.resolve("sinal_classifier.tflite").readBytes()
      val sidecar = pasta.resolve("sinal_classifier.json").readBytes()
      val idBytes = pasta.resolve("sinal_classifier.identidade.json").readBytes()
      val identidade = JsonSlurper().parse(idBytes) as Map<*, *>
      val contrato = JsonSlurper().parse(sidecar) as Map<*, *>
      if (hashPrivado(idBytes) != identidadePrivadaSha || identidade["schema"] != 1 ||
          identidade["experimental"] != true || identidade["aprovado_entrega"] != false ||
          identidade["calibracao"] != "ausente_nao_calibrado" || contrato.containsKey("calibracao") ||
          identidade["modelo_sha256"] != hashPrivado(modelo) ||
          identidade["sidecar_sha256"] != hashPrivado(sidecar) ||
          contrato["sha256"] != hashPrivado(modelo) ||
          (contrato["origem"] as? Map<*, *>)?.get("sha256") != identidade["checkpoint_sha256"] ||
          !(identidade["checkpoint_sha256"] as? String).orEmpty().matches(Regex("[0-9a-f]{64}"))) {
        throw GradleException("Identidade/hash do pacote privado inválido; não empacotar.")
      }
      // Nenhuma colisão com assets normais, inclusive diretórios de source sets adicionais.
      android.sourceSets.filter { it.name != "androidTest" }.forEach { source ->
        source.assets.srcDirs.filter { it.canonicalFile != destino.canonicalFile }.forEach { dir ->
          if (nomesClassificadorPrivado.any { dir.resolve(it).exists() })
            throw GradleException("Colisão do classificador privado com assets em $dir")
        }
      }
      project.delete(destino)
      destino.mkdirs()
      // Copiar o snapshot validado, não reler arquivos que possam mudar nesse intervalo.
      destino.resolve("sinal_classifier.tflite").writeBytes(modelo)
      destino.resolve("sinal_classifier.json").writeBytes(sidecar)
      destino.resolve("sinal_classifier.identidade.json").writeBytes(idBytes)
    } else {
      project.delete(destino)
      destino.mkdirs()
    }
  }
}
// Abortar antes de executar qualquer tarefa de release, mesmo se invocada por assemble/build.
gradle.taskGraph.whenReady {
  if (classificadorPrivado != null && allTasks.any { it.project == project && it.name.contains("Release") }) {
    throw GradleException("classificadorPrivado é exclusivo de debug; release proibido.")
  }
}

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  classificadorFixtures?.let { diretorio ->
    // Substituição, não adição: evita colisão com o smoke versionado. Este opt-in é para
    // testes isolados do classificador; as demais fixtures de mídia padrão ficam de fora.
    sourceSets.getByName("androidTest").assets.setSrcDirs(listOf(diretorio))
  }

  buildFeatures { buildConfig = true }

  defaultConfig {
    buildConfigField("boolean", "CLASSIFICADOR_PRIVADO_OBRIGATORIO", "false")
    buildConfigField("String", "CLASSIFICADOR_IDENTIDADE_SHA256", "\"\"")
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
    getByName("debug") {
      buildConfigField("boolean", "CLASSIFICADOR_PRIVADO_OBRIGATORIO", (classificadorPrivado != null).toString())
      buildConfigField("String", "CLASSIFICADOR_IDENTIDADE_SHA256", "\"$identidadePrivadaSha\"")
    }
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  sourceSets.getByName("debug").assets.srcDir(assetsClassificadorPrivado)
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
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(prepararClassificadorPrivado) }

// ModeloContextualizacaoProvenienciaTest e TabelasDuplicadasTest leem estes arquivos direto do
// disco. Eles não são entrada dos testes de unidade por padrão: sem declarar aqui, trocar só o
// .tflite, o carimbo ou uma tabela deixa a tarefa UP-TO-DATE e as guardas nem rodam.
tasks.withType<Test>().configureEach {
  systemProperty("librasLivre.paridadeClassificadorJson", paridadeClassificadorJson.absolutePath)
  // Também declara o smoke padrão: editar só o JSON deve invalidar UP-TO-DATE.
  inputs
      .file(paridadeClassificadorJson)
      .withPropertyName("paridadeClassificadorJson")
      .withPathSensitivity(PathSensitivity.RELATIVE)
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
  // Activity hospedeira do teste isolado do cartão; não faz parte de release.
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
  // Libras Livre: testes de paridade numérica (LandmarkNormalizer/HandGapImputer contra
  // computer-vision-model/treino) — puro JVM, sem Android, não precisa de emulador.
  testImplementation(libs.junit)
  testImplementation(libs.org.json)
}

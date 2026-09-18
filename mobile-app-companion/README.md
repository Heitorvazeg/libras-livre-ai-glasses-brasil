# Mobile App Companion — Libras Livre

Trilha **mobile** do Libras Livre. Aplicativo Android que conecta aos óculos
Ray-Ban Meta, recebe o vídeo da câmera e roda todo o pipeline de tradução de
Libras **on-device**: landmarks, reconhecimento de sinal, montagem da frase em
português, fala e transcrição da resposta.

---

## 1. O papel deste app

Os óculos **não rodam** o aplicativo — eles são a câmera e o microfone. O celular
é a borda (*edge*) que recebe o vídeo, processa e devolve voz.

```
Óculos Ray-Ban Meta  --vídeo HEVC-->  este app (celular)  -->  voz
                     <--áudio A2DP/HFP-->
```

O projeto nasceu do **sample oficial de Camera Access** do *Meta Wearables Device
Access Toolkit* (DAT), que já entrega a parte difícil de conectar aos óculos e
consumir a câmera. Sobre essa base, o Libras Livre acrescentou o pacote
[`libras/`](./app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras):
extração de landmarks, detecção de fronteiras entre sinais, classificação,
contextualização glosa → português, síntese de voz, transcrição e wake word.

---

## 2. Como rodar

### 2.1 Pré-requisitos

| Requisito | Versão |
|---|---|
| Android Studio | Narwhal (2025.1.1) ou mais novo |
| JDK | 17 (embutido no Studio) |
| Android SDK | 36 (`compileSdk`) — instale pelo SDK Manager |
| minSdk | 31 (Android 12) |
| AGP / Gradle | 8.11.1 / 8.14.1 (via wrapper) |
| Kotlin | 2.2.21 |

### 2.2 Token do Maven privado da Meta

As bibliotecas `mwdat-*` vêm de um repositório Maven no GitHub Packages, que
exige autenticação. Gere um *personal access token (classic)* com o escopo
**`read:packages`** (basta ele) e declare-o de uma das duas formas:

```bash
echo "github_token=SEU_TOKEN" >> local.properties
# ou, alternativamente:
export GITHUB_TOKEN=SEU_TOKEN
```

Buildando pelo terminal, sem abrir o Android Studio antes, o Gradle também precisa
achar o SDK: `export ANDROID_HOME=~/Android/Sdk` ou `sdk.dir=...` no mesmo
`local.properties` (o Studio escreve essa linha sozinho).

Sem o token, o *sync* do Gradle falha nas dependências dos óculos. Ver o
[setup do SDK](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android#step-2-add-the-sdk-to-gradle).

### 2.3 Modelos: os nossos vêm no clone, os externos são baixados

A regra do projeto: **modelos internos** (treinados por nós) são versionados no
git e já vêm com o clone; **modelos externos** (de terceiros) ficam fora e são
baixados por script (ver
[`app/src/main/assets/.gitignore`](./app/src/main/assets/.gitignore)).

| Já vem no clone | Arquivos |
|---|---|
| Contextualização glosa → português | `modelo_contextualizacao.tflite` (45 MiB) + `glosa_ids.json` + `destokenizar.json` + `modelo_contextualizacao.proveniencia.json` |
| Léxico de glosas | `lexico-glosas.json` |
| Wake word pt-BR | `wakeword/libras_livre_{iniciar,encerrar}.onnx[.data]` |

Os externos são baixados de uma vez pelo script, que é idempotente — pula o que
já existe (~16 s numa conexão boa, medido em clone limpo em 2026-09-13):

```bash
./download-assets.sh
```

| Grupo | Arquivos | Origem |
|---|---|---|
| Visão | `pose_landmarker_lite.task`, `hand_landmarker.task` | Google / MediaPipe |
| Síntese de voz | `tts/pt_br/` (Piper pt-BR, int8) | release `tts-models` do `k2-fsa/sherpa-onnx` |
| Transcrição | `vosk-model-small-pt-0.3/` | alphacephei.com |
| Wake word (fixos) | `melspectrogram.onnx`, `embedding_model.onnx` | release v0.5.1 do `dscripka/openWakeWord` |
| Avatar (Unity WebGL) | `vlibras/vlibras.js`, `vlibras/target/` (13,5 MB) | `spbgovbr-vlibras/vlibras-player-webjs` (LGPLv3) |

**O build confere.** Gerar o APK (ou rodar os testes instrumentados) sem algum
desses arquivos falha com a lista do que falta — a tarefa `verificarAssets`, em
`app/build.gradle.kts`. Os testes de unidade não dependem deles. Para um build
rápido sem os modelos, `-PlibrasLivre.permitirAssetsFaltando=true` troca a falha
por um aviso — o APK resultante sobe, mas sem reconhecimento, fala ou avatar.

O player do avatar exige `npm` na máquina: o build Unity vem pronto no repositório
oficial, mas o wrapper `vlibras.js` sai de um `webpack`. Sem npm o script avisa e
segue — o resto dos assets continua sendo baixado.

**Atualizar o modelo de contextualização** (só quando ele for retreinado): o
`.tflite` e as duas tabelas que ele exige andam juntos, e o carimbo de
proveniência precisa ser atualizado no mesmo commit. O teste
`ModeloContextualizacaoProvenienciaTest` falha se um deles mudar sem os outros.

```bash
cd ../contextualization-model && python exportacao/para_tflite.py --experimento v2
cp artefatos/{modelo_contextualizacao.tflite,glosa_ids.json,destokenizar.json} \
   ../mobile-app-companion/app/src/main/assets/
# atualize os sha256 em assets/modelo_contextualizacao.proveniencia.json e rode:
cd ../mobile-app-companion && ./gradlew testDebugUnitTest
```

A variante `-fp16` (88 MiB) não é usada pelo app e não é versionada.

### 2.4 Build e execução

1. Abra o projeto no Android Studio.
2. **File > Sync Project with Gradle Files**.
3. **Run > app**.

Com óculos físicos: ative o *Developer Mode* no app Meta AI, toque em **Connect**
para registrar o app e depois em **Start Session > Preview**.

Sem óculos: abra o **menu de debug** (botão flutuante em builds DEBUG) >
`MockDeviceKit`, pareie um dispositivo simulado e escolha **Video file** como
fonte de câmera, apontando para um vídeo de Libras. O pipeline inteiro roda no
emulador.

### 2.5 Testes

```bash
./gradlew testDebugUnitTest      # JVM puro, sem emulador — paridade numérica e guardas
./gradlew connectedAndroidTest   # instrumentados, exigem emulador/dispositivo
```

**A suíte instrumentada não fecha numa única execução, e isso é por desenho.** O modo de
carregamento do classificador é fixado no build (não há fallback silencioso), então os testes
de tela se dividem em duas famílias incompatíveis: uns afirmam o cartão de diagnóstico em
`SIMULADO` (build padrão), outros exigem `REAL_EXPERIMENTAL` (build com pacote privado). Rode
duas vezes, uma por configuração — detalhes e resultados na
[rodada de 17/09](../docs/integracao-video-minds-e-calibracao-2026-09-17.md).

Alguns testes são **opt-in** e ficam ignorados (`assumeTrue`) sem os argumentos. Os que rodam
vídeo real precisam do pacote privado no build, dos clipes num diretório do aparelho e de
`am instrument` em vez de `connectedAndroidTest` — este desinstala o app no fim e leva embora
os relatórios:

```bash
PKG=com.meta.wearable.dat.externalsampleapps.cameraaccess
# 1. build com o pacote do classificador (caminho absoluto)
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    -PlibrasLivre.classificadorPrivado=$PWD/../experimentos-privados/app-baseline-v1
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 2. clipes onde o app lê sem permissão de armazenamento
adb shell mkdir -p /sdcard/Android/media/$PKG/minds
adb push meus-clipes/*.mp4 /sdcard/Android/media/$PKG/minds/

# 3. vídeo real pela pipeline inteira (transcodifica AVC -> HEVC no aparelho)
adb shell am instrument -w -r -e videoMinds true \
    -e videoMindsDir /sdcard/Android/media/$PKG/minds \
    -e class $PKG.libras.reconhecimento.VideoMindsPipelineTest \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner

# relatório e CSVs da sessão (para scripts/calibracao_fronteiras.py)
adb exec-out run-as $PKG tar c files/video-minds > video-minds.tar
```

Qualquer parâmetro de `ParametrosSegmentacao` pode ser sobrescrito na execução com
`-e seg.<nome> <valor>` (ex.: `-e seg.pausaMs 800`), que é como a pausa foi calibrada.
`TtsSttArtificialTest` exercita TTS e STT sem alto-falante nem microfone: o TTS do app
sintetiza e o PCM entra no STT pelo mesmo callback da captura — precisa de
`adb shell pm grant $PKG android.permission.RECORD_AUDIO`.

Os testes de unidade cobrem a paridade numérica de `LandmarkNormalizer` e
`HandGapImputer` contra o pipeline Python de `../computer-vision-model/treino`,
além do `SignBoundaryDetector`, das guardas da contextualização e dos contratos dos
modelos versionados: o carimbo do `.tflite` (`ModeloContextualizacaoProvenienciaTest`) e
as tabelas duplicadas entre a trilha e o app (`TabelasDuplicadasTest`). Entre os
instrumentados, `WakeWordModelosCarregamTest` confere que os classificadores de wake word
carregam no ONNX Runtime do Android.

---

## 3. Arquitetura

O app é 100% Kotlin + Jetpack Compose, em **MVVM** com fluxo de dados
unidirecional:

> **View** (Compose) desenha e emite eventos → **ViewModel** processa e guarda
> estado → **UiState** (`data class` imutável) desce de volta para a View.

Os pilares:

- **`StateFlow`** — cada ViewModel expõe um estado observável; a UI recompõe sozinha.
- **Coroutines + `Flow`** — todo o assíncrono (stream de vídeo, estados do SDK,
  erros) é consumido como fluxo, sem callbacks.
- **`sealed interface`** — resultados exaustivos (`CapturePreview`, `RecordingResult`).
- **Foreground Service** — mantém o stream vivo com o app em segundo plano.
- **Interfaces trocáveis** — `SignClassifier`, `WakeWordDetector`, `SttEngine`,
  `TtsEngine` e `GlossContextualizer` têm implementação real e fallback. Um asset
  ausente degrada o comportamento, não derruba o app.

### 3.1 Estrutura do código

```
app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/
├── MainActivity.kt          entry point; permissões do Android
├── camera/                  CameraViewModel + CameraUiState — ciclo da câmera e injeção dos motores
├── stream/                  pipeline de vídeo:
│   ├── HevcDecoder.kt         decode HEVC -> Surface (preview) via MediaCodec
│   ├── VideoRecorder.kt       orquestra gravação
│   ├── VideoCaptureHandler.kt frames HEVC -> MP4 (MediaMuxer)
│   └── StreamingService.kt    foreground service
├── ui/                      telas Compose (CameraScreen, HomeScreen, ...)
├── wearables/               WearablesViewModel — conexão e registro dos óculos
├── mockdevicekit/           simulador de óculos (desenvolver sem hardware)
└── libras/                  ⟵ tudo que é Libras Livre; ver o README do pacote
    ├── reconhecimento/        landmarks -> normalização -> fronteiras -> classificação
    ├── dialogo/               máquina de estados da sessão bidirecional
    ├── contextualizacao/      glosas -> frase em português (surdo -> ouvinte)
    ├── avatar/                frase -> glosa VLibras -> avatar 3D (ouvinte -> surdo)
    │                          a tela em si é ui/AvatarScreen.kt
    └── audio/                 wake word, TTS, STT, troca A2DP/HFP

app/src/main/java/com/rementia/openwakeword/
└── lib/                     openWakeWord vendorizado (não publicado em Maven)
```

O ponto de plugue é **`CameraViewModel.handleVideoFrame()`**: cada frame dos
óculos passa por ali e segue para o decoder de preview, para o gravador e para o
`LandmarkPipeline`.

O pacote `libras/` tem
[documentação própria](./app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/README.md),
com o fluxo detalhado e as ressalvas de paridade.

### 3.2 A sessão de diálogo

Uma sessão é aberta e fechada por duas frases-gatilho — "Libras Livre, iniciar" e
"Libras Livre, encerrar" — e passa por nove estados (`dialogo/DialogState.kt`):

```
① AGUARDANDO_SINAL -> ①.5 PEDINDO_CONSENTIMENTO -> ② CAPTURANDO_SINAIS
   -> ②.5 CONFIRMANDO_RECONHECIMENTO -> ③ FALANDO -> ④ AGUARDANDO_RESPOSTA
   -> ⑤ ESCUTANDO_ATENDENTE -> ⑥ TRANSCREVENDO -> ⑦ GERANDO_AVATAR
```

Dois estados entraram depois do desenho original, dos dois pontos de feedback
da banca (2026-09-15) — ambos reaproveitam o `playAvatar()` do ⑦, sem avatar novo:

- **①.5 PEDINDO_CONSENTIMENTO** — antes de ligar a câmera, mostra pra pessoa
  surda o que o sistema faz e espera o atendente decidir por ela: "Aceitar" liga
  a câmera; "Recusar" volta ao ① sem captar nada, com aviso de bilhete/intérprete.
  Plano: [`docs/consentimento-por-atendimento-plano.md`](../docs/consentimento-por-atendimento-plano.md).
- **②.5 CONFIRMANDO_RECONHECIMENTO** — depois de decidir a frase, mostra pra
  pessoa surda o que foi entendido antes de falar pro atendente: "Confirmar"
  segue o ciclo; "Corrigir" descarta e reabre a captura. Plano:
  [`docs/confirmacao-e-modo-economia-plano.md`](../docs/confirmacao-e-modo-economia-plano.md).

Uma sessão pode conter vários sinais em sequência; onde cada um começa e termina
é decidido pelo `SignBoundaryDetector`, não pela máquina de estados. Uma sessão
ociosa se encerra sozinha após um minuto. O plano original está em
[`docs/orquestracao-dialogo-audio-plano.md`](../docs/orquestracao-dialogo-audio-plano.md).

### 3.2.1 Modo economia de bateria

Reage a `DeviceSessionError.BATTERY_CRITICAL`/`StreamError.BATTERY_LOW` do SDK
real (API confirmada por inspeção do `.aar`, não suposição). Vira mais um caso
de `FalhaCamera` — bloqueia "iniciar"/"corrigir" pelo mesmo caminho de qualquer
outra falha de câmera, sem UI nova. Nunca desliga sozinho: o SDK não expõe
"bateria recuperada". Mesmo plano do ②.5 acima, §2.

### 3.3 Os dois canais dos óculos

Os óculos entregam dados por caminhos diferentes:

| Canal | Mecanismo | Onde |
|---|---|---|
| Vídeo (câmera) | SDK do DAT | `mwdat-camera` |
| Áudio (mic/speaker) | perfis Bluetooth nativos (A2DP/HFP) | `AudioManager`, fora do DAT |

**A2DP e HFP são mutuamente exclusivos.** Ligar HFP, necessário para usar o
microfone dos óculos, derruba a saída de áudio para 8 kHz mono durante toda a
sessão. O app trata essa troca explicitamente em `audio/AudioSessionManager.kt`:
fala pelo A2DP no estado ③ e alterna para HFP no estado ⑤, quando precisa
capturar a resposta do atendente pelo microfone dos óculos. A captura de PCM cru
é feita por `audio/PcmMicCapture.kt`, com fonte e dispositivo configuráveis.

---

## 4. Motores de IA em uso

Todos rodam localmente. Nenhuma chamada de rede acontece no fluxo de tradução.

| Função | Implementação ativa | Fallback |
|---|---|---|
| Landmarks | MediaPipe Pose + Hands (`tasks-vision`) | — (erro no banner se os `.task` faltarem) |
| Classificação de sinal | `TfliteSignClassifier`, com pacote privado opt-in (`librasLivre.classificadorPrivado`, hash/identidade conferidos em duas camadas) | `PlaceholderSignClassifier` — ativo no build padrão (nenhum `.tflite` de sinal nos assets versionados) |
| Contextualização glosa → PT | `TfliteGlossContextualizer` sob guarda (LiteRT) | template, depois passthrough |
| Síntese de voz | Piper/sherpa-onnx pt-BR | `AndroidTextToSpeechEngine` |
| Transcrição | Vosk pt-BR | `AndroidSpeechRecognizerSttEngine` |
| Wake word | `SpeechRecognizerWakeWordDetector` | botões Iniciar/Encerrar na tela |
| Tradução PT → glosa | endpoint público do VLibras, com cache em disco | legenda em texto |
| Avatar em Libras | player VLibras (Unity/WebGL) em WebView | legenda em texto |

Lacunas importantes, todas com trabalho conhecido pela frente:

- **O classificador de sinal real não está neste clone.** `TfliteSignClassifier`
  e a política de carregamento (`CarregadorClassificador`) já existem e são
  testados — três modos (`SIMULADO`, `REAL_EXPERIMENTAL`, `RECUSADO`), hash e
  identidade conferidos no build e no carregamento, sem fallback silencioso pra
  um modelo errado. O que falta é o **checkpoint treinado em si** (exportado como
  `final-s20260917-v1`, mas mantido fora do git por ser experimental e privado —
  ver [`docs/integracao-modelo-app-plano-2026-09-15.md`](../docs/integracao-modelo-app-plano-2026-09-15.md))
  e a **validação com sinais reais**: até agora só SIMULADO (placeholder) e um
  pacote RECUSADO fabricado foram exercitados de ponta a ponta na tela.
- **O wake word offline (`OpenWakeWordDetector`) não está ativo.** Os dois
  classificadores pt-BR estão treinados, versionados (§2.3) e carregam no ONNX Runtime
  (`WakeWordModelosCarregamTest`); falta validar recall e falso positivo em hardware real
  antes de trocar o motor em `CameraViewModel`.
- **O avatar é a única peça que depende de rede.** Traduzir a frase exige o endpoint
  público do VLibras, e o Unity busca cada sinal do dicionário na hora. O cache de
  glosa cobre repetições; o espelho local do dicionário ainda não existe
  ([`docs/vlibras-webview-plano.md`](../docs/vlibras-webview-plano.md) Fase 3.5).
- **O avatar não foi medido em aparelho ARM.** A validação foi num emulador com
  passthrough para GPU de desktop.

### Dependências

Além dos módulos `mwdat-*`, o build declara:

```
com.google.mediapipe:tasks-vision       extração de landmarks
com.google.ai.edge.litert:litert        runtime do .tflite (nome atual do TensorFlow Lite)
com.microsoft.onnxruntime:onnxruntime-android   wake word (openWakeWord vendorizado)
com.alphacephei:vosk-android@aar        transcrição pt-BR
app/libs/sherpa-onnx-1.13.8.aar         síntese de voz (não publicado em Maven)
```

O `.aar` do sherpa-onnx é a variante *static-link-onnxruntime* da release oficial,
escolhida para não colidir com o `libonnxruntime.so` que o ONNX Runtime do wake
word também empacota. Ver os comentários em `app/build.gradle.kts`.

---

## 5. Contrato do classificador TFLite

O exportador do treinamento produz um modelo e um JSON de contrato ao lado dele.
Esta seção define os requisitos de integração; **não afirma que o app já os
implemente**.

- Conferir o SHA-256 do modelo e a ordem dos rótulos do JSON antes de usá-lo.
- Conferir `contrato_entrada.shape` e `dtype` contra os tensores do interpretador.
  No modo landmarks o shape é **[1, T, P, 2]**, não imagem RGB. O checkpoint atual
  usa **57 pontos** (15 de pose + 21 de cada mão); ler P e a lista
  `layout_landmarks.pose_ordenada` do JSON, sem fixar 49 no aplicativo.
- T é **fixo por artefato**, por padrão **96 frames**. O aplicativo precisa entregar
  exatamente `frames_fixos`; não redimensionar o tensor para aceitar outro T. O
  grafo não faz padding nem reamostragem temporal. Escolher ou adaptar uma janela
  de captura é trabalho pendente de integração e validação com sinais reais, não
  uma propriedade já validada pelo teste numérico do conversor.
- Entregar x/y na ordem declarada, normalizados em unidades de ombro. A cabeça
  embute apenas Skeleton-DML e resize; normalização e imputação não estão nela.
  Reproduzir o pré-processamento do checkpoint antes da chamada ao modelo.
- Recusar checkpoints e layouts não verificados. A exportação 3D está bloqueada
  até portar e testar o pré-processamento específico de z.
- A saída contém logits, um índice por rótulo. A paridade em entradas aleatórias
  verifica conversão — não acurácia, segmentação temporal ou latência no aparelho.

Detalhes do lado do treino:
[exportação para TFLite](../computer-vision-model/treino/README.md#exportação-para-tflite-exportarpy).

---

## 6. O que falta

- [x] Export do ST-GCN para `.tflite` (`treino/exportar.py --arquitetura gcn`)
- [x] Infraestrutura de carregamento privado do classificador real — pacote com
      hash/identidade, três modos (SIMULADO/REAL_EXPERIMENTAL/RECUSADO), cartão
      de diagnóstico na tela, sem fallback silencioso
- [x] Pacote baseline do ST-GCN **neste clone** (`experimentos-privados/app-baseline-v1`,
      `final-s20260917-v1`, publicado em 17/09) e vídeo com sinais reais em
      REAL_EXPERIMENTAL — os seis clipes da sinalizante 08 do MINDS reconhecidos ponta a
      ponta no emulador. **Os clipes estão no treino do baseline**: isso valida o caminho,
      não generalização
      ([rodada de 17/09](../docs/integracao-video-minds-e-calibracao-2026-09-17.md))
- [ ] Calibração dos parâmetros do `SignBoundaryDetector` com dado real — **`pausaMs`
      calibrada** (500 → 800 ms, com os clipes do MINDS); limiares de velocidade e teto de
      oclusão ainda dependem do protocolo R1/R2 com os óculos
- [x] Treino dos classificadores pt-BR de wake word, versionados e carregando no app
- [ ] Validação do wake word offline em hardware e ativação do `OpenWakeWordDetector`
      (motor ativo por padrão ainda é o `SpeechRecognizer`, ver §4)
- [ ] Medição da taxa de fallback da contextualização em campo (modelo treinado
      integrado, mas desligado por padrão — `MODELO_CONTEXTUALIZACAO_ATIVO = false`,
      não bateu o template em F1 na validação sintética)
- [x] Entrega da resposta para a pessoa surda (estado ⑦: avatar VLibras + legenda)
- [x] Confirmação do reconhecimento pro surdo antes de falar pro atendente (②.5) e
      modo economia de bateria dos óculos (§3.2.1) — feedback da banca de 2026-09-15
- [x] Consentimento por atendimento antes de ligar a câmera (①.5) — requisito de
      `docs/libras-livre-arquitetura.md` §7 (LGPD); **texto do consentimento é um
      placeholder não revisado juridicamente nem pela comunidade surda**
- [ ] Espelho local do dicionário de sinais, para o avatar funcionar sem internet
- [ ] Medir o avatar (WebGL e memória) no celular que acompanha os óculos
- [ ] **Reduzir o tamanho do APK** — o debug com todos os assets está em **474 MB**.
      Avaliar Play Asset Delivery e a variante de release com minify
- [ ] Ajuste dinâmico de fps por bateria e limite térmico — diferente do modo
      economia de §3.2.1, que só reage aos dois eventos críticos do SDK, sem meio-termo
- [ ] Validação do pipeline completo com pessoas surdas no cenário de balcão

---

## 7. Solução de problemas

| Sintoma | Causa provável |
|---|---|
| Sync do Gradle falha em `com.meta.wearable:mwdat-*` | token ausente ou expirado em `local.properties` |
| Build falha com "Assets obrigatórios ausentes" | `./download-assets.sh` não foi executado, ou falhou no meio — a mensagem lista o que falta (§2.3) |
| Banner "Modelos do MediaPipe não encontrados" | APK gerado com `-PlibrasLivre.permitirAssetsFaltando=true` sem os `.task` |
| Log "modelo_contextualizacao.tflite indisponível" | **não esperado**: o modelo vem no clone. O app cai no template; rode `./gradlew testDebugUnitTest` — o `ModeloContextualizacaoProvenienciaTest` diz o que falta (§2.3) |
| Avatar não aparece e o log diz "sem WebGL nesta WebView" | WebView sem aceleração; o app cai na legenda |
| Avatar não aparece e o log diz "vlibras.js ausente" | `./download-assets.sh` não rodou, ou rodou sem npm |
| Avatar abre e fica no spinner até "Unity não ficou pronto" | WebView sem aceleração ou aparelho sem fôlego para o Unity; use **Tentar de novo** |
| Os botões Iniciar/Encerrar funcionam, mas a voz não dispara a sessão | permissão de microfone negada, ou o `SpeechRecognizer` do aparelho sem rede |
| Áudio some ao entrar no estado ⑤ | esperado: o HFP derruba o A2DP enquanto o mic dos óculos está ativo |

Para questões do próprio SDK dos óculos, veja a
[documentação do DAT](https://wearables.developer.meta.com/docs/develop/dat/) ou o
[fórum de discussões](https://github.com/facebook/meta-wearables-dat-android/discussions).

## Licença

O código-base do sample é da Meta Platforms, licenciado sob os termos do arquivo
LICENSE do repositório de origem.

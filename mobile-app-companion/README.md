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
exige autenticação. Gere um *personal access token (classic)* e declare-o de uma
das duas formas:

```bash
echo "github_token=SEU_TOKEN" >> local.properties
# ou, alternativamente:
export GITHUB_TOKEN=SEU_TOKEN
```

Sem o token, o *sync* do Gradle falha nas dependências dos óculos. Ver o
[setup do SDK](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android#step-2-add-the-sdk-to-gradle).

### 2.3 Baixar os modelos — obrigatório

**Nenhum modelo pesado é versionado no git** (ver
[`app/src/main/assets/.gitignore`](./app/src/main/assets/.gitignore)). O script
baixa todos de uma vez e é idempotente — pula o que já existe:

```bash
./download-assets.sh
```

| Grupo | Arquivos | Origem |
|---|---|---|
| Visão | `pose_landmarker_lite.task`, `hand_landmarker.task` | Google / MediaPipe |
| Síntese de voz | `tts/pt_br/` (Piper pt-BR, int8) | release `tts-models` do `k2-fsa/sherpa-onnx` |
| Transcrição | `vosk-model-small-pt-0.3/` | alphacephei.com |
| Wake word (fixos) | `melspectrogram.onnx`, `embedding_model.onnx` | release v0.5.1 do `dscripka/openWakeWord` |

Sem os arquivos `.task`, o app sobe mas a captura mostra o erro "Modelos do
MediaPipe não encontrados".

Dois assets **não** são baixáveis pelo script:

- **`modelo_contextualizacao.tflite`** (46 MB) — gerado pela trilha de
  contextualização. Sem ele, o app cai no contextualizador por template, sem
  erro. Para gerá-lo:
  ```bash
  cd ../contextualization-model && python exportacao/para_tflite.py --experimento v2
  cp artefatos/modelo_contextualizacao.tflite ../mobile-app-companion/app/src/main/assets/
  ```
- **`wakeword/libras_livre_{iniciar,encerrar}.onnx`** — precisam ser **treinados**.
  O openWakeWord só publica modelos prontos em inglês. Enquanto não existirem, o
  motor real não sobe e o fallback é o `SpeechRecognizer` do Android mais os
  botões Iniciar/Encerrar da tela.

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

Os testes de unidade cobrem a paridade numérica de `LandmarkNormalizer` e
`HandGapImputer` contra o pipeline Python de `../computer-vision-model/treino`,
além do `SignBoundaryDetector` e das guardas da contextualização.

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
    ├── contextualizacao/      glosas -> frase em português
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
"Libras Livre, encerrar" — e passa por sete estados (`dialogo/DialogState.kt`):

```
① AGUARDANDO_SINAL -> ② CAPTURANDO_SINAIS -> ③ FALANDO -> ④ AGUARDANDO_RESPOSTA
   -> ⑤ ESCUTANDO_ATENDENTE -> ⑥ TRANSCREVENDO -> ⑦ GERANDO_AVATAR
```

Uma sessão pode conter vários sinais em sequência; onde cada um começa e termina
é decidido pelo `SignBoundaryDetector`, não pela máquina de estados. Uma sessão
ociosa se encerra sozinha após um minuto. O plano completo está em
[`docs/orquestracao-dialogo-audio-plano.md`](../docs/orquestracao-dialogo-audio-plano.md).

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
| Classificação de sinal | **`PlaceholderSignClassifier`** | — |
| Contextualização glosa → PT | `TfliteGlossContextualizer` sob guarda (LiteRT) | template, depois passthrough |
| Síntese de voz | Piper/sherpa-onnx pt-BR | `AndroidTextToSpeechEngine` |
| Transcrição | Vosk pt-BR | `AndroidSpeechRecognizerSttEngine` |
| Wake word | `SpeechRecognizerWakeWordDetector` | botões Iniciar/Encerrar na tela |

Duas lacunas importantes, ambas com trabalho conhecido pela frente:

- **A classificação de sinal ainda é um placeholder.** O `.tflite` real depende do
  export do ST-GCN em `../computer-vision-model/treino/exportar.py`, que hoje só
  cobre a ResNet-18. `TfliteSignClassifier` substituirá a implementação atual sem
  mudar `LandmarkPipeline` nem `DialogOrchestrator`.
- **O wake word real (`OpenWakeWordDetector`) não está ativo.** O motor está
  implementado e a dependência de ONNX Runtime já está no build; falta treinar os
  dois classificadores pt-BR (§2.3).

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

- [ ] Export do ST-GCN para `.tflite` e troca do `PlaceholderSignClassifier` pelo real
- [ ] Calibração dos parâmetros do `SignBoundaryDetector` com dado real
- [ ] Treino dos classificadores pt-BR de wake word e ativação do `OpenWakeWordDetector`
- [ ] Medição da taxa de fallback da contextualização em campo
- [ ] Entrega da legenda para a pessoa surda (estado ⑦, avatar VLibras)
- [ ] Ajuste dinâmico de fps por bateria e limite térmico
- [ ] Validação do pipeline completo com pessoas surdas no cenário de balcão

---

## 7. Solução de problemas

| Sintoma | Causa provável |
|---|---|
| Sync do Gradle falha em `com.meta.wearable:mwdat-*` | token ausente ou expirado em `local.properties` |
| Banner "Modelos do MediaPipe não encontrados" | `./download-assets.sh` não foi executado |
| Log "modelo_contextualizacao.tflite indisponível" | esperado; o app usa o template (§2.3) |
| Os botões Iniciar/Encerrar funcionam, mas a voz não dispara a sessão | permissão de microfone negada, ou wake word real ausente |
| Áudio some ao entrar no estado ⑤ | esperado: o HFP derruba o A2DP enquanto o mic dos óculos está ativo |

Para questões do próprio SDK dos óculos, veja a
[documentação do DAT](https://wearables.developer.meta.com/docs/develop/dat/) ou o
[fórum de discussões](https://github.com/facebook/meta-wearables-dat-android/discussions).

## Licença

O código-base do sample é da Meta Platforms, licenciado sob os termos do arquivo
LICENSE do repositório de origem.

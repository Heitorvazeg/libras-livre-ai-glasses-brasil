# Contrato do classificador TFLite

O exportador do treinamento produz um modelo e um JSON de contrato ao lado dele.
Esta seção define os requisitos de integração; **não afirma que o app já os implemente**.

- Conferir o SHA-256 do modelo e a ordem dos rótulos do JSON antes de usá-lo.
- Conferir `contrato_entrada.shape` e `dtype` contra os tensores do interpretador.
  No modo landmarks, o shape é **[1, T, P, 2]**, não imagem RGB. O checkpoint atual
  usa **57 pontos** (15 pose + 21 mão esquerda + 21 mão direita); ler P e a lista
  `layout_landmarks.pose_ordenada` do JSON, sem fixar 49 no aplicativo.
- T é **fixo por artefato**, por padrão **96 frames**. O aplicativo precisa entregar
  exatamente `frames_fixos`; não redimensionar o tensor para aceitar outro T.
  O grafo não faz padding nem reamostragem temporal. Escolher/adaptar uma janela
  de captura é trabalho pendente de integração e validação com sinais reais,
  não uma propriedade já validada pelo teste numérico do conversor.
- Entregar x/y na ordem declarada, normalizados em unidades de ombro. A cabeça
  embute apenas Skeleton-DML + resize; normalização e imputação não estão nela.
  Reproduzir o pré-processamento do checkpoint antes da chamada ao modelo.
- Recusar checkpoints/layouts não verificados para entrega. A exportação 3D está
  bloqueada até portar e testar o pré-processamento específico de z.
- A saída contém logits, com um índice por rótulo. A paridade em entradas aleatórias
  verifica conversão, não acurácia, segmentação temporal nem latência no aparelho.

Detalhes: [exportação no treinamento](../computer-vision-model/treino/README.md#exportação-para-tflite-exportarpy).

# Mobile App Companion — Libras Livre

> Trilha **Mobile** do Libras Livre. App Android que conecta aos óculos Ray-Ban
> Meta, recebe o vídeo da câmera e roda o classificador de Libras **on-device**,
> em tempo real, traduzindo sinais em fala.

---

## 1. Contexto — o papel deste app

**Libras Livre** traduz Libras para fala usando óculos inteligentes. Os óculos
**não rodam** o app — eles são a câmera. O celular é a **borda** (edge) que
recebe o vídeo, processa e reconhece os sinais.

```
Óculos Ray-Ban Meta  ──vídeo──►  este app (celular)  ──►  landmarks → classificador → frase → voz
```

Este projeto nasceu do **sample oficial de Camera Access** do *Meta Wearables
Device Access Toolkit (DAT)* — que já entrega, robusto, toda a parte difícil de
conectar aos óculos e receber a câmera. Sobre essa base, o Libras Livre adiciona:
extração de landmarks (MediaPipe), o classificador `.tflite` (vindo de
`../computer-vision-model`), a lógica de fronteiras entre sinais e a saída em voz.

---

## 2. Como o app se conecta aos óculos — dois canais

Os óculos entregam dados por **dois caminhos diferentes** (guia §2.2):

| Canal | Mecanismo | Módulo |
|---|---|---|
| **Vídeo** (câmera) | SDK do DAT | `mwdat-camera` |
| **Áudio** (mic/speaker) | Perfis Bluetooth nativos (A2DP/HFP) | `AudioManager`, fora do DAT |

**Ponto crítico do áudio:** A2DP e HFP são mutuamente exclusivos. Ligar HFP
(necessário para o microfone) derruba a saída de áudio para 8 kHz mono durante a
sessão inteira. O sample base **não** usa o mic dos óculos — sua captura de áudio
(`AudioInputHandler`) é do **microfone do celular**. Usar o mic dos óculos exige
rotear via HFP (`AudioManager.setCommunicationDevice`), o que não é coberto pelo
sample. Para Libras (língua visual), o canal que importa é o **vídeo**.

---

## 3. Conceitos aplicados (arquitetura)

O app é **100% Kotlin + Jetpack Compose**, seguindo **MVVM** com **fluxo de dados
unidirecional (UDF)**:

> **View** (Compose) só desenha e emite eventos → **ViewModel** processa e guarda
> estado → **UiState** (`data class` imutável) desce de volta para a View.

Os pilares:

- **`StateFlow`** — cada ViewModel expõe um estado observável (`_uiState` mutável
  privado / `uiState` só-leitura público). A UI recompõe sozinha quando muda.
- **Coroutines + `Flow.collect`** — todo o assíncrono (stream de vídeo, estados
  do SDK, erros) é consumido como fluxos, sem callbacks.
- **`sealed interface`** — resultados exaustivos (`CapturePreview`, `RecordingResult`).
- **Foreground Service** — mantém o stream vivo com o app em segundo plano.

### O ciclo de vida da câmera (máquina de estados do DAT)

```
Start Session → Start Preview (stream) → [frames HEVC] → Capturar/Gravar → Stop → End Session
```

Está tudo em `CameraViewModel.kt`. Os frames comprimidos (HEVC) chegam em
`handleVideoFrame()` e hoje seguem para: o decoder de tela (`HevcDecoder`), o
gravador de MP4 (`VideoRecorder`) e o coletor de parâmetros do codec.

### 🎯 Onde a IA entra

**`handleVideoFrame()` (em `camera/CameraViewModel.kt`)** é o ponto de plugue do
Libras Livre. Cada frame dos óculos passa por ali. O fluxo a adicionar (guia §2.6):

```
frame HEVC → decodifica → MediaPipe Hands (landmarks)
           → detecta fronteira entre sinais (lógica das duas pausas)
           → SinalClassifier (.tflite)  → palavra
           → tabela de combinações → frase → TextToSpeech (voz)
```

A **segmentação** ("onde um sinal acaba e outro começa") vive aqui, no app, por
detecção de pausa/movimento sobre os landmarks — não no projeto de IA.

---

## 4. Estrutura do código

```
app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/
├── MainActivity.kt          entry point; permissões do Android
├── camera/                  CameraViewModel + CameraUiState  ← ciclo da câmera (IA entra aqui)
├── stream/                  pipeline de vídeo:
│   ├── HevcDecoder.kt         decode HEVC → Surface (preview) via MediaCodec
│   ├── VideoRecorder.kt       orquestra gravação
│   ├── VideoCaptureHandler.kt frames HEVC → MP4 (MediaMuxer)
│   ├── AudioInputHandler.kt   captura do MIC DO CELULAR (não dos óculos)
│   └── StreamingService.kt    foreground service (mantém stream em background)
├── ui/                      telas Compose (CameraScreen, HomeScreen…)
├── wearables/               WearablesViewModel ← conexão/registro dos óculos
└── mockdevicekit/           simulador de óculos (desenvolver sem hardware)
```

> **`mockdevicekit/` é chave para o desenvolvimento:** simula os óculos Ray-Ban
> Meta e permite **injetar um vídeo de Libras** como se viesse da câmera
> (`setCameraFeed(uri)`), testando o pipeline de inferência inteiro no emulador,
> sem hardware físico.

---

## 5. Dependências a adicionar (trilha Mobile, guia §2.1)

Além dos módulos `mwdat-*` já presentes:

```kotlin
// build.gradle.kts (app)
implementation("com.google.mediapipe:tasks-vision:0.10.14")  // extração de landmarks
implementation("org.tensorflow:tensorflow-lite:2.14.0")       // rodar o .tflite
```

O modelo `sinal_classifier.tflite` (de `../computer-vision-model`) vai em
`app/src/main/assets/`.

---

## 6. Pré-requisitos e build

| Requisito | Versão |
|-----------|--------|
| Android Studio | **Narwhal (2025.1.1)** ou mais novo |
| JDK | 17 (embutido no Studio) |
| Android SDK | 36 (`compileSdk`) — instale via SDK Manager |
| minSdk | 31 (Android 12) |
| AGP / Gradle | 8.11.1 / 8.14.1 (via wrapper) |
| Kotlin | 2.2.21 |

### Passos

1. Abra o projeto no Android Studio.
2. Adicione seu **personal access token (classic)** ao `local.properties` como
   `github_token=...` — necessário para baixar as libs `mwdat-*` do Maven privado
   da Meta ([setup do SDK](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android#step-2-add-the-sdk-to-gradle)).
   Sem o token, o Gradle sync falha nas dependências dos óculos.
3. **File > Sync Project with Gradle Files**.
4. **Run > app**.

### Rodando com os óculos

1. Ative o *Developer Mode* no app Meta AI.
2. Toque em **Connect** para registrar o app.
3. **Start Session** → **Preview** para iniciar a câmera ao vivo.

Sem óculos físicos: use o **menu de debug** (botão flutuante em builds DEBUG) →
`MockDeviceKit` para parear um dispositivo simulado e injetar um vídeo de teste.

---

## 7. Checklist da trilha Mobile (guia §2.10)

- [ ] Projeto configurado com credenciais do Wearables Developer Center
- [ ] `Wearables.initialize` + registro funcionando (testável via MockDeviceKit)
- [ ] Permissão de câmera concedida e verificada
- [ ] Sessão criada, HFP configurado e assentado antes do stream (se usar áudio)
- [ ] Stream de vídeo consumido com `conflate()`, sem acumular frames atrasados
- [ ] MediaPipe Hands extraindo landmarks do frame do DAT (não da câmera do celular)
- [ ] Classificador `.tflite` carregado, classificando uma palavra por vez
- [ ] Lógica das duas pausas (fim de palavra / fim de frase) calibrada com teste real
- [ ] Tabela `combinacoesConhecidas` validada com a comunidade
- [ ] TTS falando a frase resolvida
- [ ] Ajuste dinâmico de fps por bateria/térmico

---

## 8. Solução de problemas

Para questões do próprio SDK dos óculos, veja a
[documentação do DAT](https://wearables.developer.meta.com/docs/develop/dat/) ou o
[fórum de discussões](https://github.com/facebook/meta-wearables-dat-android/discussions).

## Licença

O código-base do sample é da Meta Platforms, licenciado sob os termos no arquivo
LICENSE na raiz do repositório de origem.

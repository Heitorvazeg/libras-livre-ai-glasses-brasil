# Libras Livre — a IA dentro do app

Este pacote é o "onde a IA entra": liga o vídeo dos óculos ao reconhecimento de
sinal, à montagem da frase em português e à fala — tudo local, sem nenhuma
chamada de rede.

## Fluxo

```
frames HEVC (CameraViewModel.handleVideoFrame)
  -> LandmarkPipeline
      -> HevcDecoder DEDICADO -> ImageReader (YUV)     [o preview segue no decoder original]
      -> android.media.Image -> MediaPipe Pose+Hands (LandmarkExtractor)
      -> LandmarkNormalizer (normaliza por ombros — 57 pontos x 2 canais x,y)
      -> HandGapImputer (acumula o segmento em curso, preenche lacunas curtas de mão)
      -> SignBoundaryDetector (decide onde cada sinal começa/termina — SINALIZANDO/PARADO)
      -> a cada boundary: SignClassifier.classify() -> onRecognized(glosa)
      -> DialogOrchestrator acumula as glosas da sessão
      -> GlossContextualizer transforma a lista de glosas numa frase em português
      -> Speaker fala a frase ao "encerrar"
```

Organizado em 4 subpacotes, por domínio (não por tipo de arquivo):

| Subpacote | Papel |
|---|---|
| `reconhecimento/` | pipeline de visão: extração, normalização, imputação, fronteiras, classificação |
| `dialogo/` | máquina de estados da sessão (①→⑦) |
| `contextualizacao/` | glosas reconhecidas → frase em português |
| `audio/` | wake word, síntese de voz, transcrição, troca A2DP/HFP |

### `reconhecimento/`

| Arquivo | Papel |
|---|---|
| `LandmarkPipeline.kt` | orquestra decoder → ImageReader → extração → normalização → fronteira → classificação |
| `LandmarkExtractor.kt` | MediaPipe Pose+Hands → `FrameLandmarks` (pontos crus 0..1) |
| `FrameLandmarks.kt` | contrato de dados entre extração e consumo |
| `LandmarkNormalizer.kt` | normalização por ombros (origem e escala) — 57 pontos, 2 canais (x, y) |
| `HandGapImputer.kt` | preenche lacunas curtas de mão não detectada (portagem online de `treino/dados.py:imputar_maos`) |
| `SignBoundaryDetector.kt` | heurística de deslocamento — decide onde cada sinal começa e termina dentro da sessão |
| `SignClassifier.kt` | interface do classificador local, mais `PlaceholderSignClassifier` |

### `dialogo/`

| Arquivo | Papel |
|---|---|
| `DialogOrchestrator.kt` | dono da sessão: decide quando capturar, falar e escutar; liga/desliga câmera e stream via callbacks do `CameraViewModel`; acumula as glosas |
| `DialogState.kt` | os 7 estados da sessão |

### `contextualizacao/`

| Arquivo | Papel |
|---|---|
| `GlossContextualizer.kt` | interface (glosas → frase), mais `PassthroughGlossContextualizer` |
| `Contextualizadores.kt` | monta a cadeia de camadas — uma função, porque a ordem é decisão de produto |
| `TfliteGlossContextualizer.kt` | seq2seq podado (`modelo_contextualizacao.tflite`) via LiteRT |
| `TemplateGlossContextualizer.kt` | piso determinístico por regras, sempre disponível |
| `Guardas.kt` | `GuardedGlossContextualizer` — rejeita a saída do modelo quando ela inventa ou perde conteúdo |
| `LexicoGlosas.kt` | léxico de glosas (`lexico-glosas.json`), contrato com a trilha de contextualização |

A cadeia é `modelo (.tflite) → guarda → template → passthrough`. **O template é o
piso e o modelo precisa merecer cada sessão**: em três rodadas de fine-tuning o
modelo não bateu o template em F1 na validação sintética, mas a guarda o aceita em
~95% das sessões e ele ganha justamente onde há relação gramatical entre glosas
("banco esquina" → "o banco fica na esquina"). Se o asset do modelo não estiver
presente, tudo continua funcionando com o template — sem erro, só um log.

### `audio/`

| Arquivo | Papel |
|---|---|
| `Speaker.kt` | fachada de fala; recebe um `TtsEngine` |
| `TtsEngine.kt` / `PiperSherpaOnnxTtsEngine.kt` / `AndroidTextToSpeechEngine.kt` | síntese de voz pt-BR — Piper/sherpa-onnx é o motor ativo, o Android TTS é fallback |
| `SttEngine.kt` / `VoskSttEngine.kt` / `AndroidSpeechRecognizerSttEngine.kt` | transcrição da resposta do atendente — **Vosk é o motor ativo** (`CameraViewModel`), o `SpeechRecognizer` é fallback |
| `WakeWordDetector.kt` | interface do gatilho da sessão (`INICIAR`/`ENCERRAR`) |
| `SpeechRecognizerWakeWordDetector.kt` | **motor ativo** — escuta contínua no mic do celular via `SpeechRecognizer` |
| `OpenWakeWordDetector.kt` | motor real escolhido; **não ativo** — depende dos classificadores pt-BR treinados |
| `AudioSessionManager.kt` | troca A2DP↔HFP para escutar a resposta do atendente pelo mic dos óculos |
| `PcmMicCapture.kt` | captura de PCM cru configurável (fonte e dispositivo); usada pelo `VoskSttEngine` |

O estado (capturando, reconhecendo, resultado, erro) fica em `CameraUiState.libras`
e é desenhado por `LibrasBanner` em `ui/CameraScreen.kt`, atualizando a cada sinal
reconhecido dentro da sessão. A sessão é aberta e fechada pelo `DialogOrchestrator`
(via `LandmarkPipeline.startSession()`/`endSession()`), disparada por "Libras
Livre, iniciar"/"encerrar", com os botões `DialogControlRow` como fallback. As
mesmas duas frases ligam e desligam a câmera e o stream dos óculos
(`CameraViewModel.ensureCameraActiveForLibras`/`deactivateCameraForLibras`).

---

## Normalização: local, não mais no servidor

Versões anteriores deste pacote mandavam landmarks crus para uma API Python
(`computer-vision-model/PoC/api`) que normalizava e classificava via DTW — um
andaime de validação, documentado como tal. Isso saiu. `LandmarkNormalizer.kt`
replica em Kotlin o mesmo algoritmo de
`computer-vision-model/PoC/src/extract.py:frame_normalizado` (origem no ponto
médio dos ombros, escala pela distância entre eles), e a classificação roda local.

**São 2 canais (x, y), não 3.** O modelo em treino (`treino/gcn.py`) usa
`canais_ent=2`; `LandmarkNormalizer` não carrega nem normaliza z.

Os testes de paridade numérica ficam em `app/src/test/` e rodam em JVM pura, sem
emulador: `./gradlew testDebugUnitTest`.

---

## Pré-requisito: assets em `app/src/main/assets/`

Nenhum modelo pesado entra no git. Baixe tudo a partir de `mobile-app-companion/`:

```bash
./download-assets.sh
```

O que o script traz, o que falta e como gerar o `.tflite` de contextualização
estão em `mobile-app-companion/README.md` §2.3.

Resumo do que **não** é baixável:

- `modelo_contextualizacao.tflite` — gerado por
  `contextualization-model/exportacao/para_tflite.py`. Ausente, o app usa o template.
- `wakeword/libras_livre_{iniciar,encerrar}.onnx` — precisam ser treinados; o
  openWakeWord só publica modelos prontos em inglês. Ausentes, o
  `OpenWakeWordDetector` não sobe e o fallback assume.

---

## Como testar sem óculos

1. Rode o app no emulador. Menu de debug > **MockDeviceKit** > parear Ray-Ban Meta
   e escolher **Video file** como fonte de câmera, apontando para um vídeo de Libras.
2. **Start session > Preview**. Com o preview ao vivo, toque **Iniciar**, sinalize
   um ou mais sinais em sequência, toque **Encerrar**.
3. O banner mostra o último sinal reconhecido a cada fronteira detectada. Com o
   `PlaceholderSignClassifier`, o "sinal reconhecido" é sempre um texto sintético
   (`[placeholder:Nf]`) — serve para confirmar que o ciclo fronteira → classificar
   → acumular → contextualizar → falar funciona, não que o reconhecimento está
   correto. Isso só com o `.tflite` real.
4. Erros (modelos ausentes, segmento curto demais) aparecem no banner.

---

## Ressalvas de paridade, a validar com dado real

- **Pose+Hands em vez de Holistic:** as referências da PoC vieram do Holistic; aqui
  usamos os detectores separados. Índices e convenção de pontos são os mesmos, mas
  pode haver divergência de detecção. Só dado real confirma.
- **Esquerda/direita das mãos** vêm da *handedness* do MediaPipe ("Left"/"Right").
- **Rotação do feed** assumida como 0. Se o vídeo dos óculos vier girado, ajustar a
  rotação no `LandmarkExtractor` (`ImageProcessingOptions`).
- **Parâmetros do `SignBoundaryDetector` não calibrados** (`LIMIAR_VELOCIDADE`,
  `JANELA_SUSTENTACAO_MS` e os demais). As fases de calibração
  (`docs/sign-boundary-detector-plano.md` §7) exigem device e dataset que não
  estavam disponíveis quando isto foi implementado; os valores em uso são os
  "pontos de partida sugeridos" do plano, marcados como tal no código.
- **A classificação é um placeholder.** O `.tflite` real depende do export do
  ST-GCN, que `computer-vision-model/treino/exportar.py` ainda não cobre.

Referências: `docs/extracao-landmarks-plano.md`,
`docs/sign-boundary-detector-plano.md`,
`docs/orquestracao-dialogo-audio-plano.md`,
`docs/contextualizacao-glosa-seq2seq-plano.md`.

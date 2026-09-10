# Libras Livre — integração no app (extração, normalização e reconhecimento local)

Este pacote é o **"onde a IA entra"** do app: liga o vídeo dos óculos ao
reconhecimento de sinal, hoje inteiramente local (sem chamada de rede).

## Fluxo

```
frames HEVC (CameraViewModel.handleVideoFrame)
  → LandmarkPipeline
      → HevcDecoder DEDICADO → ImageReader (YUV)     [o preview segue no decoder original]
      → android.media.Image → MediaPipe Pose+Hands (LandmarkExtractor)
      → LandmarkNormalizer (normaliza por ombros — 57 pontos x 2 canais x,y)
      → HandGapImputer (acumula o segmento em curso, preenche lacunas curtas de mão)
      → SignBoundaryDetector (decide onde cada sinal começa/termina — SINALIZANDO/PARADO)
      → a cada boundary: SignClassifier.classify() → onRecognized(palavra)
      → DialogOrchestrator acumula as palavras da sessão e fala a frase ao "encerrar"
```

Organizado em 3 subpacotes, por domínio (não por tipo de arquivo):

| Subpacote | Papel |
|---|---|
| `reconhecimento/` | pipeline de visão: extração → normalização → imputação → boundary → classificação |
| `dialogo/` | máquina de estados da sessão (①→⑦) |
| `audio/` | wake word, TTS, STT, troca A2DP↔HFP |

| Arquivo | Papel |
|---|---|
| `reconhecimento/LandmarkPipeline.kt` | orquestra decoder→ImageReader→extração→normalização→boundary→classificação |
| `reconhecimento/LandmarkExtractor.kt` | MediaPipe Pose+Hands → `FrameLandmarks` (pontos crus 0..1) |
| `reconhecimento/LandmarkNormalizer.kt` | normalização por ombros (origem/escala) — 57 pontos, 2 canais (x,y) |
| `reconhecimento/HandGapImputer.kt` | preenche lacunas curtas de mão não detectada (portagem online de `treino/dados.py:imputar_maos`) |
| `reconhecimento/SignBoundaryDetector.kt` | heurística de deslocamento — decide onde cada sinal começa/termina dentro da sessão |
| `reconhecimento/SignClassifier.kt` | interface do classificador local + `PlaceholderSignClassifier` (o `.tflite` real ainda não existe) |
| `dialogo/DialogOrchestrator.kt` | dono da sessão de diálogo (①→⑦) — decide quando capturar, falar, escutar; acumula as palavras da sessão numa frase |
| `dialogo/DialogState.kt` | os 7 estados da sessão |
| `audio/Speaker.kt` | TextToSpeech pt-BR |
| `audio/WakeWordDetector.kt` | gatilho da sessão — hoje só os botões de fallback (`ManualWakeWordDetector`) |
| `audio/AudioSessionManager.kt` | troca A2DP↔HFP pra escutar a resposta do atendente pelo mic dos óculos |
| `audio/SttEngine.kt` | transcrição da resposta do atendente |
| `audio/AudioInputHandler.kt` | fonte de PCM contínuo do mic do celular — preparado pra uma futura wake word real, não conectado a nenhum motor ainda |

O estado (capturando / reconhecendo / resultado / erro) fica em
`CameraUiState.libras` e é desenhado por `LibrasBanner` em `ui/CameraScreen.kt` — agora
atualiza a cada sinal reconhecido dentro da sessão, não só uma vez no fim. A sessão é
aberta/fechada pelo `DialogOrchestrator` (via `LandmarkPipeline.startSession()`/
`endSession()`), disparado pelos botões "Iniciar"/"Encerrar" (`DialogControlRow`) — ver
`docs/orquestracao-dialogo-audio-plano.md`. Onde cada sinal individual começa/termina
DENTRO da sessão é responsabilidade do `SignBoundaryDetector` — ver
`docs/sign-boundary-detector-plano.md`.

## Normalização: local agora, não mais no servidor

Versões anteriores deste pacote mandavam landmarks crus pra uma API (`PoC/api`) que
normalizava e classificava via DTW — era um andaime de validação, documentado como tal.
Isso saiu: `LandmarkNormalizer.kt` replica em Kotlin o mesmo algoritmo de
`computer-vision-model/PoC/src/extract.py:frame_normalizado` (origem = meio dos ombros,
escala = distância entre eles), e a classificação roda local (`SignClassifier`) — ver
`docs/extracao-landmarks-plano.md` e `docs/sign-boundary-detector-plano.md`. Nenhuma
chamada de rede acontece mais nesse fluxo.

**2 canais (x, y), não 3.** O modelo em treino (`treino/gcn.py`) usa `canais_ent=2` de
verdade — `LandmarkNormalizer` não carrega nem normaliza z.

## Pré-requisito: modelos do MediaPipe em `app/src/main/assets/`

Baixe os dois `.task` e coloque em `app/src/main/assets/`:

- `pose_landmarker_lite.task` — https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
- `hand_landmarker.task` — https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

Sem eles, o pipeline sobe mas a captura mostra o erro "Modelos do MediaPipe não
encontrados". (Os `.task` não entram no git — são baixáveis e pesados.)

Não precisa mais de servidor nenhum rodando — a classificação é local (hoje, via
`PlaceholderSignClassifier`, enquanto o `.tflite` de verdade não existe — ver
`docs/sign-boundary-detector-plano.md` §5.2).

## Como testar (com o mock do DAT, sem óculos)

1. Rode o app no emulador. Menu de debug → **MockDeviceKit** → parear Ray-Ban Meta
   e escolher **Video file** como *camera source*, apontando para um vídeo de Libras.
2. **Start session → Preview**. Com o preview ao vivo, toque **Iniciar** (abre a
   sessão), sinalize um ou mais sinais em sequência, toque **Encerrar**.
3. O banner mostra o último sinal reconhecido a cada boundary. Com
   `PlaceholderSignClassifier`, o "sinal reconhecido" é sempre um texto sintético
   (`[placeholder:Nf]`) — serve pra confirmar que o ciclo boundary→classificar→
   acumular→falar funciona, não que o reconhecimento está certo (isso só com o
   `.tflite` real).
4. Erros (modelos ausentes, segmento curto demais) aparecem no banner.

## Ressalvas de paridade (a validar com dado real)

- **Pose+Hands vs Holistic:** as referências da PoC vieram do Holistic; aqui usamos
  os detectores separados. Índices e convenção de pontos são os mesmos, mas pode
  haver pequena divergência de detecção. Só dado real confirma.
- **Esquerda/direita das mãos:** vêm da *handedness* do MediaPipe ("Left"/"Right").
- **Rotação do feed:** assumida como 0. Se o vídeo dos óculos vier girado, ajustar a
  rotação no `LandmarkExtractor` (ImageProcessingOptions).
- **Parâmetros do `SignBoundaryDetector` não calibrados** (`LIMIAR_VELOCIDADE`,
  `JANELA_SUSTENTACAO_MS`, etc.) — as Fases 0/1 de calibração
  (`docs/sign-boundary-detector-plano.md` §7) exigem device/dataset que não estavam
  disponíveis quando isto foi implementado. Os valores em uso são só os "pontos de
  partida sugeridos" do plano, claramente marcados como tal no código.
- **Frase da sessão é um placeholder** (`palavras.joinToString(" ")` no
  `DialogOrchestrator`) — não é a tabela `combinacoesConhecidas` real (concordância,
  ordem, elisão), que não existe implementada em lugar nenhum do repo ainda (ver
  `README.md` da raiz, checklist da trilha mobile).

Este é um **andaime de validação da integração**, não a arquitetura final: o objetivo
do projeto é on-device/offline. Ver `README.md` da raiz.

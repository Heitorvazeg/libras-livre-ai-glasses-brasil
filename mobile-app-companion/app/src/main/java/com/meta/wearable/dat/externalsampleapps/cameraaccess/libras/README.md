# Libras Livre — integração no app (extração de landmarks + API)

Este pacote é o **"onde a IA entra"** do app: liga o vídeo dos óculos ao
classificador da PoC via a API de validação (`computer-vision-model/PoC/api`).

## Fluxo

```
frames HEVC (CameraViewModel.handleVideoFrame)
  → LandmarkPipeline
      → HevcDecoder DEDICADO → ImageReader (YUV)     [o preview segue no decoder original]
      → android.media.Image → MediaPipe Pose+Hands (LandmarkExtractor)
      → acumula os frames do sinal enquanto "capturando"
      → POST /classify (LandmarkApi, landmarks crus)  → palavra
      → onRecognized(palavra)  → DialogOrchestrator fala via Speaker.speakAndAwait
```

| Arquivo | Papel |
|---|---|
| `LandmarkPipeline.kt` | orquestra decoder→ImageReader→extração→coleta→classificação |
| `LandmarkExtractor.kt` | MediaPipe Pose+Hands → `FrameLandmarks` (pontos crus 0..1) |
| `LandmarkApi.kt` | cliente HTTP de `POST /classify` (HttpURLConnection + org.json) |
| `Speaker.kt` | TextToSpeech pt-BR |
| `DialogOrchestrator.kt` | dono da sessão de diálogo (①→⑦) — decide quando capturar, falar, escutar, e liga/desliga a câmera+stream via callbacks do `CameraViewModel` |
| `WakeWordDetector.kt` | interface do gatilho da sessão (`WakeWord`, `INICIAR`/`ENCERRAR`) |
| `SpeechRecognizerWakeWordDetector.kt` | motor real — escuta contínua no mic do celular via `SpeechRecognizer` (mesma API do `SttEngine`) |
| `AudioSessionManager.kt` | troca A2DP↔HFP pra escutar a resposta do atendente pelo mic dos óculos |
| `SttEngine.kt` | transcrição da resposta do atendente |

O estado (capturando / reconhecendo / resultado / erro) fica em
`CameraUiState.libras` e é desenhado por `LibrasBanner` em `ui/CameraScreen.kt`. A
captura é acionada pelo `DialogOrchestrator` (via `LandmarkPipeline.startCollecting()`/
`stopCollectingAndClassify()`), disparada por "Libras Livre, iniciar"/"encerrar" —
detectadas continuamente por `SpeechRecognizerWakeWordDetector` (mic do celular), com
os botões "Iniciar"/"Encerrar" (`DialogControlRow`) como fallback caso o motor real
esteja sem permissão ou falhe. As mesmas duas frases também ligam/desligam a
câmera+stream dos óculos (`CameraViewModel.ensureCameraActiveForLibras`/
`deactivateCameraForLibras`) — ver `docs/orquestracao-dialogo-audio-plano.md`.

## Decisão central: o app manda landmarks **crus**, o servidor normaliza

O passo que mais afeta a acurácia é a normalização (origem nos ombros, escala pela
distância entre eles). Para não divergir do que a PoC mediu, o app **não normaliza**:
manda `x,y,z` (0..1, como saem do MediaPipe) + `width/height`, e o servidor roda o
mesmo `extract.frame_normalizado` das referências. Ver `PoC/api/README.md`.

## Pré-requisitos

### 1. Modelos do MediaPipe em `app/src/main/assets/`

Baixe os dois `.task` e coloque em `app/src/main/assets/`:

- `pose_landmarker_lite.task` — https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
- `hand_landmarker.task` — https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

Sem eles, o pipeline sobe mas a captura mostra o erro "Modelos do MediaPipe não
encontrados". (Os `.task` não entram no git — são baixáveis e pesados.)

### 2. A API rodando e alcançável

```bash
cd computer-vision-model/PoC
python -m uvicorn api.server:app --host 0.0.0.0 --port 8000
```

Precisa ter referências carregadas (`GET /health` → `clipes_referencia > 0`),
senão `/classify` responde 503. Ver `PoC/api/README.md`.

### 3. Host da API (mesmo host)

`BuildConfig.LIBRAS_API_BASE_URL` (definido em `app/build.gradle.kts`):

| Alvo | Valor | Como |
|---|---|---|
| **Emulador Android** | `http://10.0.2.2:8000` | padrão, nada a fazer |
| **Device físico** (mesma Wi-Fi) | `http://<IP-LAN>:8000` | `-PlibrasApiBaseUrl=http://192.168.x.x:8000` no build |

HTTP puro já está liberado para esses hosts em
`res/xml/network_security_config.xml`. Para um IP LAN novo, **acrescente-o lá**.

## Como testar (com o mock do DAT, sem óculos)

1. Suba a API (passo 2) e confirme `clipes_referencia > 0`.
2. Rode o app no emulador. Menu de debug → **MockDeviceKit** → parear Ray-Ban Meta
   e escolher **Video file** como *camera source*, apontando para um vídeo de Libras.
3. **Start session → Preview**. Com o preview ao vivo, toque **Iniciar** (abre a
   sessão de captura), deixe o sinal acontecer, toque **Encerrar**.
4. O banner mostra o sinal reconhecido e o TTS fala. Erros aparecem no banner.

## Ressalvas de paridade (a validar com dado real)

- **Pose+Hands vs Holistic:** as referências da PoC vieram do Holistic; aqui usamos
  os detectores separados. Índices e convenção de pontos são os mesmos e o servidor
  descarta z e normaliza por ombros — então x,y são diretamente comparáveis, mas
  pode haver pequena divergência de detecção. Só dado real confirma.
- **Esquerda/direita das mãos:** vêm da *handedness* do MediaPipe ("Left"/"Right").
- **Rotação do feed:** assumida como 0. Se o vídeo dos óculos vier girado, ajustar a
  rotação no `LandmarkExtractor` (ImageProcessingOptions).
- **Segmentação por wake word/botão:** a sessão inteira (não cada sinal) é marcada por
  "Iniciar"/"Encerrar". Onde cada sinal começa/termina dentro da sessão é trabalho do
  `SignBoundaryDetector` (`docs/sign-boundary-detector-plano.md`), ainda não integrado
  aqui — por enquanto uma sessão classifica tudo que foi capturado como um só sinal.

Este é um **andaime de validação da integração**, não a arquitetura final: o objetivo
do projeto é on-device/offline. Ver `README.md` da raiz.

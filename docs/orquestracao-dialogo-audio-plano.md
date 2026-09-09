# Orquestração de diálogo bidirecional — wake word, áudio dos óculos e handoff pro avatar

> Plano de implementação da conversa completa: óculos veem a pessoa sinalizando →
> app reconhece e fala pro atendente → atendente responde de voz, acionado por
> wake word → resposta é transcrita e vira avatar Libras pra pessoa surda ver.
> Este documento assume o pipeline de reconhecimento de sinal já existente
> (`mobile-app-companion/app/.../libras/`, ver `libras/README.md`) e a integração
> do avatar já planejada em `docs/vlibras-webview-plano.md` (branch
> `feat/Empacota-player-vlibras-em-webview-nativa`) — não reinventa nenhum dos
> dois, só desenha o que os conecta.

---

## 1. Objetivo

Hoje o app reconhece um sinal e fala a palavra (fluxo de mão única: surdo → app →
atendente). Este plano estende isso pra uma **conversa de duas mãos**: depois de
falar o sinal reconhecido, o app precisa saber quando o atendente está
respondendo, capturar essa resposta por voz, e entregar o texto resultante pro
pipeline que já existe (em outro branch) de tradução texto→glosa→avatar 3D.

O desafio central não é nenhuma peça isolada — é que **fala (TTS) e escuta
(STT) usam perfis de áudio Bluetooth mutuamente exclusivos**, e o gatilho de
início de cada etapa precisa ser hands-free (wake word), não toque na tela,
pra caber no caso de uso real (atendente ocupado, balcão).

---

## 2. Estado atual (o que já existe e não muda)

| Peça | Arquivo | Papel |
|---|---|---|
| Sessão/stream com os óculos | `camera/CameraViewModel.kt` | DAT: `DeviceSession` → `Stream` → `VideoFrame` HEVC |
| Extração de landmarks | `libras/LandmarkPipeline.kt`, `LandmarkExtractor.kt` | MediaPipe Pose+Hands sobre os frames do stream |
| Classificação | `libras/LandmarkApi.kt` | `POST /classify` na API da PoC (DTW 1-NN) |
| Fala do sinal reconhecido | `libras/Speaker.kt` | `TextToSpeech`, hoje sem `AudioAttributes` explícito (sai por A2DP, o roteamento padrão) |
| Gravação de vídeo+áudio (MP4) | `stream/VideoRecorder.kt` + `VideoCaptureHandler.kt` | Não relacionado a este plano — continua intocado |
| Gatilho de captura do sinal | `ui/CameraScreen.kt` (`LibrasCaptureRow`) | Hoje é um **toque manual** (início E fim) |

Este plano **substitui só o gatilho** (toque manual → wake word + detecção
automática de pausa) e **adiciona** a perna de resposta (escuta → STT → avatar).
Nada do pipeline de vídeo/gravação MP4 muda.

---

## 3. Conceitos técnicos fundamentais

Pra quem for implementar ou revisar isso sem ter acompanhado a discussão:

| # | Conceito | Por que importa aqui |
|---|---|---|
| 1 | **A2DP** (Advanced Audio Distribution Profile) | Perfil Bluetooth de streaming estéreo de alta qualidade. Só **saída** de áudio — sem microfone. É o roteamento padrão do TTS hoje. |
| 2 | **HFP** (Hands-Free Profile) + **SCO** (link síncrono) | Perfil Bluetooth de voz bidirecional (fala + escuta), usado em ligação. Baixa qualidade (8kHz mono narrowband, ou 16kHz se o par negociar wideband/mSBC). É o único jeito de captar o **microfone dos óculos**. |
| 3 | **A2DP e HFP são mutuamente exclusivos** | Ligar HFP suspende A2DP no mesmo dispositivo — não dá pra ter TTS em qualidade A2DP e escuta HFP ativos ao mesmo tempo, no mesmo par. É a restrição que molda toda a máquina de estados abaixo. |
| 4 | **`AudioManager.setCommunicationDevice()`** (API 31+) | API moderna pra rotear entrada/saída de áudio pra um dispositivo de comunicação (ex.: o SCO dos óculos). `minSdk` deste projeto já é 31 — não precisa de fallback pra API antiga (`startBluetoothSco()`). Troca é **assíncrona**: só confiar depois do broadcast `ACTION_COMMUNICATION_DEVICE_CHANGED_BROADCAST` (ou timeout). |
| 5 | **Wake word / keyword spotting** | Detecção contínua e leve de uma palavra-gatilho específica, rodando local (não é STT genérico, não depende de nuvem). Precisa ser **on-device**, alinhado com o objetivo geral do projeto (ver `PoC/api/README.md`: "o objetivo do projeto é on-device/offline"). |
| 6 | **STT** (Speech-to-Text) | Diferente de wake word: roda só depois do gatilho, converte a fala inteira do atendente em texto. Mais pesado, só ativa sob demanda. |
| 7 | **Glosa** | Formato intermediário PT-BR→Libras que o player VLibras consome (ver `docs/vlibras-webview-plano.md`, §5.1). O texto do STT é o insumo que alimenta essa tradução — a costura entre este plano e aquele. |
| 8 | **Endpointing (detecção de pausa)** | Decidir automaticamente "a pessoa parou de sinalizar/falar" sem gatilho manual. Aplica dos dois lados aqui: pausa no movimento das mãos (fim do sinal) e silêncio na fala (fim da resposta do atendente — não coberto em detalhe neste documento, ver §8). |

---

## 4. Decisões tomadas

Registradas aqui porque não são óbvias a partir do código — são escolhas de
produto/arquitetura, com o motivo:

1. **Wake word roda no microfone do CELULAR, não nos óculos.**
   Motivo: escuta contínua de wake word pelo mic dos óculos exigiria manter o
   HFP ativo o tempo todo, o que bloquearia A2DP permanentemente (TTS
   degradado sempre) e gasta bateria/rádio à toa. O mic do celular já está
   disponível sem esse custo (`AudioInputHandler` já mostra o padrão de uso).

2. **Uma wake word só; o estado do diálogo decide a ação.**
   Em vez de duas frases-gatilho diferentes (uma pra "começar a assistir o
   sinal", outra pra "responder"), a mesma detecção dispara ações diferentes
   dependendo do `DialogState` atual — um único modelo de wake word pra
   treinar/manter.

3. **Fim da captura do sinal por detecção automática de pausa nos landmarks —
   não por toque, não por segunda wake word.**
   Ressalva registrada: isso reintroduz uma variável que o projeto tinha
   **deliberadamente adiado** (`LandmarkPipeline.kt` e o README raiz: a
   segmentação manual existia "pra tirar essa variável da validação" enquanto
   a PoC valida generalização entre sinalizantes). Ao automatizar, a
   calibração desse detector vira uma frente de teste própria — ver §8.

4. **Resposta do atendente é capturada pelo mic dos ÓCULOS (HFP), não pelo do
   celular.** Motivo: o atendente está de óculos — o microfone fica perto da
   boca dele, melhor captação que um celular sobre o balcão. O HFP só liga
   **depois** do gatilho de wake word (não fica ligado continuamente), então o
   custo de latência de conexão (~1-3s) é pago uma vez por resposta, não o
   tempo todo.

5. **TTS continua saindo por A2DP, sem roteamento explícito.** É o padrão do
   Android quando há um dispositivo Bluetooth de mídia conectado — não precisa
   de nenhuma chamada a `setCommunicationDevice` pra isso, só evitar que o
   HFP esteja ativo no momento de falar.

6. **A geração do avatar reaproveita o plano já existente**
   (`docs/vlibras-webview-plano.md`, branch
   `feat/Empacota-player-vlibras-em-webview-nativa`): texto → API
   `vlibras-translator-api` → glosa → `vlibras-player-webjs` numa WebView. Este
   plano só define **o que entrega o texto** pra esse pipeline (a saída do
   STT) — não duplica a decisão de arquitetura do avatar.

---

## 5. Máquina de estados

```
① AGUARDANDO SINAL            ← wake word ATIVA (mic celular)
        │ wake word detectada
        ▼
② CAPTURANDO SINAL             ← vídeo + landmarks acumulando
        │ pausa detectada nos landmarks (SignBoundaryDetector)
        ▼
③ CLASSIFICANDO                ← POST /classify (API da PoC)
        │ resultado
        ▼
④ FALANDO (TTS → A2DP)         ← Speaker.speakAndAwait()
        │ TTS termina (UtteranceProgressListener.onDone)
        ▼
⑤ AGUARDANDO RESPOSTA          ← wake word ATIVA de novo (mic celular)
        │ wake word detectada
        ▼
⑥ ESCUTANDO ATENDENTE          ← AudioSessionManager troca A2DP→HFP; mic dos óculos
        │ fim da fala do atendente (ver §8 — em aberto)
        ▼
⑦ TRANSCREVENDO                ← STT converte a captura em texto
        │ texto pronto; AudioSessionManager libera HFP → volta A2DP
        ▼
⑧ GERANDO AVATAR               ← entrega o texto pro pipeline VLibras (outro plano)
        │
        └──────────────────────► volta pro ①
```

**Regra que percorre a máquina inteira:** só existe um dono do áudio por vez.
Nenhum componente decide roteamento por conta própria — todos consultam/mudam
o estado através do orquestrador central (§6).

O detector de wake word só fica **ativo** nos estados ① e ⑤ — nos demais,
pausado (evita gasto de CPU e falso-positivo fora de contexto).

---

## 6. Componentes novos

Todos em `mobile-app-companion/app/src/main/java/.../libras/`, seguindo o
padrão de classes pequenas e single-purpose já usado no pacote (`LandmarkPipeline`,
`LandmarkExtractor`, `Speaker`).

### 6.1 `AudioSessionManager.kt`

Dono exclusivo da troca de perfil A2DP↔HFP. API `suspend` pra permitir esperar
a troca terminar antes de seguir (a mudança de dispositivo de comunicação é
assíncrona).

```kotlin
class AudioSessionManager(context: Context) {
  suspend fun acquireListening(): AudioDeviceInfo?   // troca pra HFP, espera confirmação (ou timeout)
  fun releaseListening()                             // libera, volta ao roteamento padrão (A2DP)
}
```

Responsabilidades: achar o `AudioDeviceInfo` do tipo `TYPE_BLUETOOTH_SCO` em
`audioManager.availableCommunicationDevices`, chamar `setCommunicationDevice`,
registrar/desregistrar o receiver de
`ACTION_COMMUNICATION_DEVICE_CHANGED_BROADCAST`, aplicar timeout de segurança
(~3s) pra não travar se o SCO não conectar.

### 6.2 `Speaker.kt` — extensão

Adicionar uma variante que espera o TTS terminar, via
`UtteranceProgressListener`, pra permitir a transição ④→⑤ ser sequencial (sem
`delay()` arbitrário):

```kotlin
suspend fun speakAndAwait(text: String)
```

### 6.3 `WakeWordDetector.kt`

Mic do celular, escuta contínua **só quando ativo** (estados ① e ⑤). Emite um
único evento — quem decide a ação é o orquestrador:

```kotlin
class WakeWordDetector(context: Context, private val onWakeWord: () -> Unit) {
  fun start()
  fun pause()
  fun stop()
}
```

Motor ainda **em aberto** — ver §8.

### 6.4 `SignBoundaryDetector.kt`

Vive dentro (ou ao lado) do `LandmarkPipeline`, no mesmo ponto onde os frames
já são acumulados (`collected.add(fl)`). A cada frame novo, mede o
deslocamento das mãos em relação ao(s) frame(s) anterior(es); se ficar abaixo
de um limiar por uma janela sustentada, dispara o equivalente a
`stopCollectingAndClassify()` sozinho.

Precisa de três parâmetros calibráveis (ver §8):
- limiar de "parado" (magnitude de deslocamento);
- janela de sustentação (quanto tempo parado até considerar fim de sinal);
- duração mínima de captura (não classificar 2-3 frames) e teto máximo de
  segurança (não travar se a mão nunca entrar em quadro).

### 6.5 Captura da resposta do atendente (STT input)

Uma classe nova, não uma extensão do `AudioInputHandler` existente (que está
acoplado ao `VideoRecorder`/gravação MP4 — propositalmente não mexido aqui).
Usa `AudioRecord` com `AudioSource.VOICE_COMMUNICATION` (segue o roteamento do
sistema, ao contrário de `AudioSource.MIC`) e `setPreferredDevice` pro
dispositivo SCO retornado pelo `AudioSessionManager`.

### 6.6 `DialogOrchestrator` (ou extensão do `CameraViewModel`)

Dono do `DialogState` (§5) e de todas as transições. Coordena
`WakeWordDetector`, `LandmarkPipeline`/`SignBoundaryDetector`,
`Speaker.speakAndAwait`, `AudioSessionManager` e a captura de STT — nenhum
componente individual decide sozinho para onde o áudio vai.

---

## 7. Plano de implementação faseado

Seguindo a mesma lógica de isolar risco do `docs/vlibras-webview-plano.md` —
validar cada camada isolada antes de integrar.

### Fase 0 — Validar premissas de hardware
- [ ] Confirmar, no hardware real (óculos pareados), que o dispositivo SCO
  aparece em `audioManager.availableCommunicationDevices` — sem isso, todo o
  resto do plano de resposta por voz não tem base.
- [ ] Medir se o codec negociado é narrowband (8kHz) ou wideband (16kHz/mSBC)
  — define o `sampleRate` da captura em §6.5.
- **Critério de sucesso**: script isolado (fora do app, ou um botão de debug)
  que troca pra HFP e grava alguns segundos de PCM reconhecível.

### Fase 1 — `AudioSessionManager` isolado
- [ ] Implementar `acquireListening()`/`releaseListening()`.
- [ ] Testar manualmente a alternância A2DP↔HFP repetidas vezes, medindo a
  latência real de conexão do SCO no hardware.
- **Critério de sucesso**: alternar os dois perfis sem travar nem deixar o
  áudio "preso" em nenhum dos dois.

### Fase 2 — `Speaker.speakAndAwait`
- [ ] Adicionar o `UtteranceProgressListener`.
- [ ] Confirmar que `onDone`/`onError` disparam de forma confiável em
  diferentes tamanhos de frase.

### Fase 3 — `WakeWordDetector` (mic do celular, isolado)
- [ ] Escolher o motor (ver §8).
- [ ] Validar taxa de falso-positivo/falso-negativo num ambiente ruidoso
  parecido com um balcão de atendimento (não silêncio de laboratório).
- **Critério de sucesso**: detecção funcionando com o app em foreground,
  antes de integrar ao `StreamingService`.

### Fase 4 — `SignBoundaryDetector`
- [ ] Implementar sobre os landmarks já coletados no `LandmarkPipeline`.
- [ ] Calibrar limiar/janela **com dado real**, não só `MockDeviceKit` — é a
  ressalva já registrada em §4.3.
- **Critério de sucesso**: corta no fim de um sinal isolado sem cortar no
  meio (falso positivo em uma pausa natural do próprio sinal).

### Fase 5 — `DialogOrchestrator`
- [ ] Implementar o `DialogState` e as transições ①→⑧ completas.
- [ ] Trocar o gatilho hoje manual (`LibrasCaptureRow`) pelo `WakeWordDetector`
  — manter o botão manual como fallback/debug é uma opção a avaliar, não uma
  obrigação deste plano.
- **Critério de sucesso**: ciclo ①→④ (sinal → TTS) funcionando end-to-end
  via wake word, sem toque na tela.

### Fase 6 — Captura + STT da resposta
- [ ] Implementar a classe de §6.5.
- [ ] Escolher motor de STT (ver §8) e definir onde/como o silêncio de fim de
  fala do atendente é detectado (endpointing do lado da fala — não
  aprofundado neste documento).
- **Critério de sucesso**: ciclo ⑤→⑦ (wake word → resposta transcrita)
  funcionando isolado.

### Fase 7 — Handoff pro avatar
- [ ] Integrar a saída de texto de ⑦ com o pipeline de `docs/vlibras-webview-plano.md`
  (a API espera texto PT-BR; ver o fluxo `texto → glosa → player` lá descrito).
- [ ] Testar o ciclo completo ①→⑧→① pelo menos uma vez ponta a ponta.

### Fase 8 — Refinamento
- [ ] Timeout em ⑤/⑥ (se o atendente nunca responder, voltar pro `IDLE`
  sozinho).
- [ ] Tratamento de interrupção do sistema durante ⑥ (ligação chegando —
  mesmo padrão que `AudioInputHandler.wasInterrupted` já cobre pro mic do
  celular).
- [ ] Medir impacto de bateria da escuta contínua de wake word.

---

## 8. Decisões em aberto

Não bloqueiam o início da Fase 0-2, mas precisam ser fechadas antes da Fase 3
em diante:

1. **Motor de wake word.** Duas rotas discutidas:
   - **Picovoice Porcupine** — motor on-device pronto, SDK Android, treino de
     palavra customizada via console deles. Caminho mais rápido; precisa
     validar estado atual de licenciamento/custo antes de comprometer.
   - **Modelo próprio (TFLite pequeno)** — mesmo espírito do
     `sinal_classifier.tflite` já treinado neste projeto: features de áudio
     (ex. MFCC) + classificador raso. Mais controle e zero dependência
     externa, mas exige dataset de áudio da wake word (várias vozes, ruído de
     ambiente) — um projeto de coleta paralelo ao de landmarks.

2. **Motor de STT da resposta do atendente.** Não decidido neste documento.
   Precisa ser coerente com o objetivo on-device/offline do projeto (mesmo
   critério que descartou depender de nuvem pro wake word) — avaliar opções
   on-device (ex. Vosk) vs. `SpeechRecognizer` do Android (mais simples, mas
   historicamente dependente de rede em muitos aparelhos).

3. **Endpointing da fala do atendente** (fim do estado ⑥). Opções não
   exploradas em profundidade aqui: silêncio sustentado no VAD (voice activity
   detection), timeout fixo, ou nova wake word de "terminei". Fica para
   quando a Fase 6 começar.

4. **Manter ou não o botão manual como fallback.** Útil para debug/teste sem
   depender do wake word funcionando, mas é uma decisão de UX a validar com
   uso real.

---

## 9. Referências

- `mobile-app-companion/app/src/main/java/.../libras/README.md` — pipeline de
  reconhecimento de sinal já implementado.
- `computer-vision-model/PoC/api/README.md` — decisão de manter tudo
  on-device/offline como objetivo do projeto.
- `docs/vlibras-webview-plano.md` (branch
  `feat/Empacota-player-vlibras-em-webview-nativa`) — plano completo do avatar
  3D (texto → glosa → WebView).
- `docs/libras-livre-arquitetura.md` — arquitetura geral e MVP vs. Produto.
- [`AudioManager.setCommunicationDevice`](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) — API oficial de roteamento (API 31+).

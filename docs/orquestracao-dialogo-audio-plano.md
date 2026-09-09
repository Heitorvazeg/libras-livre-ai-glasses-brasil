# Orquestração de diálogo bidirecional — wake word, áudio dos óculos e handoff pro avatar

> Plano de implementação da conversa completa: óculos veem a pessoa sinalizando →
> app reconhece e fala pro atendente → atendente responde de voz, acionado por
> wake word → resposta é transcrita e vira avatar Libras pra pessoa surda ver.
> Este documento assume o pipeline de reconhecimento de sinal já existente
> (`mobile-app-companion/app/.../libras/`, ver `libras/README.md`) e a integração
> do avatar já planejada em `docs/vlibras-webview-plano.md` (branch
> `feat/Empacota-player-vlibras-em-webview-nativa`) — não reinventa nenhum dos
> dois, só desenha o que os conecta.
>
> **Início e fim de cada captura (vídeo e áudio) são controlados por duas wake
> words** ("Libras Livre, iniciar" / "Libras Livre, encerrar"), não por
> detecção automática de pausa — esse detector existe, mas evolui em branch e
> plano próprios
> (`docs/sign-boundary-detector-plano.md`, branch `feat/sign-boundary-detector`),
> desacoplado deste fluxo.

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

Este plano **substitui só o gatilho** (toque manual → duas wake words,
"Libras Livre, iniciar" / "Libras Livre, encerrar") e **adiciona** a perna de
resposta (escuta → STT → avatar). Nada do pipeline de vídeo/gravação MP4 muda.

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
| 8 | **Endpointing (detecção de fim)** | Decidir "a pessoa parou de sinalizar/falar". Neste plano, resolvido **sem** detecção automática de pausa — uma segunda wake word ("Libras Livre, encerrar") falada pelo atendente fecha tanto a captura de vídeo quanto a de áudio. Existe uma heurística de pausa por movimento (`docs/sign-boundary-detector-plano.md`), mas é um componente à parte, não usado aqui. |

---

## 4. Decisões tomadas

Registradas aqui porque não são óbvias a partir do código — são escolhas de
produto/arquitetura, com o motivo:

1. **Wake word roda no microfone do CELULAR, não nos óculos.**
   Motivo: escuta contínua de wake word pelo mic dos óculos exigiria manter o
   HFP ativo o tempo todo, o que bloquearia A2DP permanentemente (TTS
   degradado sempre) e gasta bateria/rádio à toa. O mic do celular já está
   disponível sem esse custo (`AudioInputHandler` já mostra o padrão de uso).

2. **Duas wake words: "Libras Livre, iniciar" / "Libras Livre, encerrar"; o
   estado do diálogo decide o que cada uma significa.** Em vez de uma
   detecção só combinada com heurística de pausa, são dois gatilhos verbais
   simétricos — "iniciar" começa uma captura (de vídeo em ①, de áudio em ⑤),
   "encerrar" termina a que estiver ativa no momento (vídeo em ②, áudio em
   ⑥). O `DialogState` decide qual ação cada frase dispara; o mesmo par serve
   pros dois canais, não precisa de quatro frases diferentes.

   **Por que o prefixo "Libras Livre" e não só "iniciar"/"encerrar" soltas:**
   as duas são palavras comuns do português — um atendente pode dizer
   "vamos iniciar o atendimento" ou "posso encerrar sua ficha" sem nenhuma
   intenção de acionar o app. Prefixar com o nome do produto reduz bastante
   esse risco de falso-positivo, ao custo de uma frase mais longa pra falar
   (mais sílabas até o gatilho disparar — aceitável, no mesmo patamar da
   latência de conexão do SCO que já existe em outras partes do fluxo).
   Tecnicamente é só uma frase mais longa treinada como um wake word atômico
   — não muda a arquitetura de detecção (`WakeWordDetector` continua
   reconhecendo exatamente duas frases fixas, como antes), tanto Porcupine
   quanto um modelo TFLite próprio suportam frases de várias palavras sem
   precisar de um segundo estágio de reconhecimento.

3. **Fim da captura — de vídeo E de áudio — é por wake word "encerrar",
   não por detecção automática de pausa.**
   Decisão consciente contra a heurística de movimento (que existe, mas em
   plano/branch separados — `docs/sign-boundary-detector-plano.md`): evita
   depender de um limiar calibrado, e resolve de graça o endpointing da fala
   do atendente (§3, item 8) — o mesmo mecanismo serve pros dois canais.
   Três implicações a não perder de vista:
   - **Quem fala "encerrar" na captura de vídeo é o atendente**, não a
     pessoa sinalizando (ela não fala) — ele que decide, olhando, quando o
     sinal terminou. Isso expande a janela em que o `WakeWordDetector`
     precisa estar ativo: não só em ①/⑤ (espera), mas também durante ②
     (captura de sinal) e ⑥ (escuta do atendente) — ver §5 e §6.3.
   - **Concorrência não validada**: durante ⑥, o `WakeWordDetector` (mic do
     celular) precisa continuar funcionando com o HFP simultaneamente ativo
     (mic dos óculos). São mics fisicamente diferentes, mas
     `AudioManager.mode = MODE_IN_COMMUNICATION` é uma configuração global —
     item de validação em hardware real na Fase 0 (§7), não uma premissa
     garantida.
   - **A frase de encerrar pode vazar pro texto transcrito**: o STT grava a
     fala do atendente inteira em ⑥, inclusive a wake word do final — precisa
     ser cortada do texto antes do handoff pro avatar (§6.4, §7 Fase 5).

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
① AGUARDANDO SINAL            ← wake INICIAR ativa (mic celular)
        │ "Libras Livre, iniciar"
        ▼
② CAPTURANDO SINAL             ← vídeo + landmarks acumulando; wake ENCERRAR ativa
        │ "Libras Livre, encerrar" (falado pelo atendente)
        ▼
③ CLASSIFICANDO                ← POST /classify (API da PoC) — automático
        │ resultado
        ▼
④ FALANDO (TTS → A2DP)         ← Speaker.speakAndAwait() — automático
        │ TTS termina (UtteranceProgressListener.onDone)
        ▼
⑤ AGUARDANDO RESPOSTA          ← wake INICIAR ativa de novo (mic celular)
        │ "Libras Livre, iniciar"
        ▼
⑥ ESCUTANDO ATENDENTE          ← AudioSessionManager troca A2DP→HFP; mic dos óculos; wake ENCERRAR ativa
        │ "Libras Livre, encerrar" (mesmas duas frases, mesmo mic do celular — ver §4, item 3)
        ▼
⑦ TRANSCREVENDO                ← STT converte a captura em texto; corta a wake word do final
        │ texto pronto; AudioSessionManager libera HFP → volta A2DP
        ▼
⑧ GERANDO AVATAR               ← entrega o texto pro pipeline VLibras (outro plano)
        │
        └──────────────────────► volta pro ①
```

`INICIAR`/`ENCERRAR` nos rótulos acima são os nomes internos dos dois estados
de wake word (§6.3) — a frase de fato treinada/falada é sempre "Libras Livre,
..." (§4, item 2).

**Regra que percorre a máquina inteira:** só existe um dono do áudio por vez.
Nenhum componente decide roteamento por conta própria — todos consultam/mudam
o estado através do orquestrador central (§6.5). As duas wake words são o
único evento externo — tudo mais no diagrama é automático.

O detector de wake word fica **ativo** em ①②⑤⑥ (tanto nos estados de espera
quanto durante as duas capturas, porque "encerrar" precisa ser ouvido
enquanto a captura está rolando) — só pausa em ③④⑦⑧, onde nenhuma das duas
palavras tem ação a disparar.

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

Mic do celular, escuta contínua **enquanto ativo** — agora em quatro estados
(①②⑤⑥, não só nos dois de espera; ver §5). Reconhece **duas frases fixas**
("Libras Livre, iniciar" / "Libras Livre, encerrar"), cada uma treinada como
um wake word atômico só — não é STT nem reconhecimento de frase livre. Emite
qual das duas ouviu; quem decide a ação é o orquestrador, olhando o
`DialogState` atual:

```kotlin
enum class WakeWord { INICIAR, ENCERRAR }

class WakeWordDetector(context: Context, private val onWakeWord: (WakeWord) -> Unit) {
  fun start()
  fun pause()
  fun stop()
}
```

Motor ainda **em aberto** — ver §8. Validar cedo (Fase 0, §7) se o motor
escolhido continua confiável rodando ao lado do HFP ativo (estado ⑥) — é a
concorrência levantada em §4, item 3.

> Nota: `SignBoundaryDetector` (heurística de pausa por movimento) não faz
> parte deste plano — evolui em `docs/sign-boundary-detector-plano.md`,
> branch `feat/sign-boundary-detector`, desacoplado deste fluxo.

### 6.4 Captura da resposta do atendente (STT input)

Uma classe nova, não uma extensão do `AudioInputHandler` existente (que está
acoplado ao `VideoRecorder`/gravação MP4 — propositalmente não mexido aqui).
Usa `AudioRecord` com `AudioSource.VOICE_COMMUNICATION` (segue o roteamento do
sistema, ao contrário de `AudioSource.MIC`) e `setPreferredDevice` pro
dispositivo SCO retornado pelo `AudioSessionManager`.

Responsável também por **cortar a wake word "Libras Livre, encerrar" do final
da transcrição** antes de entregar o texto pro handoff da §7 Fase 6 — ou
delega isso a quem chama o STT; qualquer uma das duas, mas precisa acontecer
em algum lugar único, não em ambos. Implementação em si fica pra §7 Fase 5.

### 6.5 `DialogOrchestrator` (ou extensão do `CameraViewModel`)

Dono do `DialogState` (§5) e de todas as transições. Coordena
`WakeWordDetector`, `LandmarkPipeline`, `Speaker.speakAndAwait`,
`AudioSessionManager` e a captura de STT — nenhum componente individual
decide sozinho para onde o áudio vai, nem o que uma wake word significa (isso
é decisão do orquestrador, não do detector).

---

## 7. Plano de implementação faseado

Seguindo a mesma lógica de isolar risco do `docs/vlibras-webview-plano.md` —
validar cada camada isolada antes de integrar.

### Fase 0 — Validar premissas de hardware
- [ ] Confirmar, no hardware real (óculos pareados), que o dispositivo SCO
  aparece em `audioManager.availableCommunicationDevices` — sem isso, todo o
  resto do plano de resposta por voz não tem base.
- [ ] Medir se o codec negociado é narrowband (8kHz) ou wideband (16kHz/mSBC)
  — define o `sampleRate` da captura em §6.4.
- [ ] Confirmar que o `WakeWordDetector` (mic do celular) continua detectando
  "encerrar" de forma confiável com o HFP ativo ao mesmo tempo (estado ⑥) —
  a concorrência levantada em §4, item 3. Sem isso, o fim da escuta do
  atendente não tem como disparar.
- **Critério de sucesso**: script isolado (fora do app, ou um botão de debug)
  que troca pra HFP, grava alguns segundos de PCM reconhecível, e confirma a
  wake word ainda disparando nesse meio tempo.

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

### Fase 3 — `WakeWordDetector` (mic do celular, isolado, duas frases)
- [ ] Escolher o motor (ver §8).
- [ ] Treinar/configurar as **duas** frases ("Libras Livre, iniciar" /
  "Libras Livre, encerrar") — dobra o trabalho de dataset/calibração em
  relação a uma frase só.
- [ ] Validar taxa de falso-positivo/falso-negativo num ambiente ruidoso
  parecido com um balcão de atendimento (não silêncio de laboratório),
  **incluindo confundir uma frase pela outra** e confundir com menções
  soltas ao nome do produto ("Libras Livre" sem o resto da frase).
- **Critério de sucesso**: as duas detecções funcionando com o app em
  foreground, antes de integrar ao `StreamingService`.

### Fase 4 — `DialogOrchestrator`
- [ ] Implementar o `DialogState` e as transições ①→⑧ completas.
- [ ] Trocar o gatilho hoje manual (`LibrasCaptureRow`) pelas duas wake words
  — manter o botão manual como fallback/debug é uma opção a avaliar, não uma
  obrigação deste plano.
- **Critério de sucesso**: ciclo ①→④ (sinal → TTS) funcionando end-to-end
  por wake word ("iniciar"/"encerrar"), sem toque na tela.

### Fase 5 — Captura + STT da resposta
- [ ] Implementar a classe de §6.4.
- [ ] Escolher motor de STT (ver §8).
- [ ] Implementar o corte do "encerrar" final da transcrição (§4, item 3;
  §6.4) — validar que sobrevive a variações de como o STT pontua/formata o
  texto (maiúscula, pontuação depois da palavra, etc.).
- **Critério de sucesso**: ciclo ⑤→⑦ (wake word → resposta transcrita, sem
  o "encerrar" no texto final) funcionando isolado.

### Fase 6 — Handoff pro avatar
- [ ] Integrar a saída de texto de ⑦ com o pipeline de `docs/vlibras-webview-plano.md`
  (a API espera texto PT-BR; ver o fluxo `texto → glosa → player` lá descrito).
- [ ] Testar o ciclo completo ①→⑧→① pelo menos uma vez ponta a ponta.

### Fase 7 — Refinamento
- [ ] Timeout em ⑤/⑥ (se o atendente nunca responder — nem falar "iniciar"
  em ⑤, nem falar "encerrar" em ⑥ — voltar pro `IDLE` sozinho).
- [ ] Tratamento de interrupção do sistema durante ⑥ (ligação chegando —
  mesmo padrão que `AudioInputHandler.wasInterrupted` já cobre pro mic do
  celular).
- [ ] Medir impacto de bateria da escuta contínua de wake word (agora em
  quatro estados, não só dois — ver §5).

---

## 8. Decisões em aberto

Não bloqueiam o início da Fase 0-2, mas precisam ser fechadas antes da Fase 3
em diante:

1. **Motor de wake word.** Duas rotas discutidas:
   - **Picovoice Porcupine** — motor on-device pronto, SDK Android, treino de
     frase customizada via console deles (suporta frases de várias
     palavras, não só uma sílaba curta). Caminho mais rápido; precisa
     validar estado atual de licenciamento/custo antes de comprometer.
   - **Modelo próprio (TFLite pequeno)** — mesmo espírito do
     `sinal_classifier.tflite` já treinado neste projeto: features de áudio
     (ex. MFCC) + classificador raso. Mais controle e zero dependência
     externa, mas exige dataset de áudio das duas frases completas (várias
     vozes, ruído de ambiente) — um projeto de coleta paralelo ao de
     landmarks. Frase mais longa ("Libras Livre, iniciar/encerrar") facilita
     diferenciar as duas classes (mais sinal temporal pro modelo aprender)
     às custas de mais dado por amostra de treino.

2. **Motor de STT da resposta do atendente.** Não decidido neste documento.
   Precisa ser coerente com o objetivo on-device/offline do projeto (mesmo
   critério que descartou depender de nuvem pro wake word) — avaliar opções
   on-device (ex. Vosk) vs. `SpeechRecognizer` do Android (mais simples, mas
   historicamente dependente de rede em muitos aparelhos).

3. **Manter ou não o botão manual como fallback.** Útil para debug/teste sem
   depender do wake word funcionando, mas é uma decisão de UX a validar com
   uso real.

---

## 9. Referências

- `mobile-app-companion/app/src/main/java/.../libras/README.md` — pipeline de
  reconhecimento de sinal já implementado.
- `computer-vision-model/PoC/api/README.md` — decisão de manter tudo
  on-device/offline como objetivo do projeto.
- `docs/sign-boundary-detector-plano.md` (branch `feat/sign-boundary-detector`)
  — heurística de pausa por movimento, evoluindo em paralelo a este plano; não
  usada no fluxo aqui descrito (§4, item 3).
- `docs/vlibras-webview-plano.md` (branch
  `feat/Empacota-player-vlibras-em-webview-nativa`) — plano completo do avatar
  3D (texto → glosa → WebView).
- `docs/libras-livre-arquitetura.md` — arquitetura geral e MVP vs. Produto.
- [`AudioManager.setCommunicationDevice`](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) — API oficial de roteamento (API 31+).

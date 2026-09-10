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
> **Início e fim de cada sessão (vídeo e áudio) são controlados por duas wake
> words** ("Libras Livre, iniciar" / "Libras Livre, encerrar"). Uma sessão de
> vídeo agora pode conter **vários sinais em sequência** — mas *como* cada
> sinal dentro da sessão é segmentado e reconhecido é responsabilidade do
> `SignBoundaryDetector`/pipeline de reconhecimento
> (`docs/sign-boundary-detector-plano.md`, branch `feat/sign-boundary-detector`),
> **não deste documento**. Este plano define só **quando** a sessão inteira
> abre e fecha, e como o áudio (TTS/STT/Bluetooth) se comporta ao redor disso.

---

## 1. Objetivo

Hoje o app reconhece um sinal e fala a palavra (fluxo de mão única: surdo → app →
atendente). Este plano estende isso em duas direções:

1. **O gatilho vira sessão, não mais um sinal isolado**: o atendente abre a
   sessão ("iniciar"), a pessoa surda sinaliza quantos sinais forem
   necessários — cada um reconhecido pelo pipeline de visão (fora do escopo
   deste documento, ver `docs/sign-boundary-detector-plano.md`) — e o
   atendente fecha a sessão ("encerrar") quando a sequência acabar.
2. **Conversa de duas mãos**: depois de falar a sequência reconhecida, o app
   precisa saber quando o atendente está respondendo, capturar essa resposta
   por voz, e entregar o texto resultante pro pipeline que já existe (em outro
   branch) de tradução texto→glosa→avatar 3D.

O desafio central não é nenhuma peça isolada — é que **fala (TTS) e escuta
(STT) usam perfis de áudio Bluetooth mutuamente exclusivos**, e o gatilho de
início de cada etapa precisa ser hands-free (wake word), não toque na tela,
pra caber no caso de uso real (atendente ocupado, balcão).

---

## 2. Estado atual (o que existe hoje, e o que este plano muda)

| Peça | Arquivo | Papel hoje |
|---|---|---|
| Sessão/stream com os óculos | `camera/CameraViewModel.kt` | DAT: `DeviceSession` → `Stream` → `VideoFrame` HEVC — **não muda** |
| Extração de landmarks | `libras/LandmarkPipeline.kt`, `LandmarkExtractor.kt` | MediaPipe Pose+Hands sobre os frames do stream — **não muda** |
| Classificação | `libras/LandmarkApi.kt` | `POST /classify` na API da PoC (DTW 1-NN) — **muda**: deixa de ser chamado por este fluxo, vira responsabilidade do pipeline de reconhecimento (`docs/sign-boundary-detector-plano.md`) |
| Fala do sinal reconhecido | `libras/Speaker.kt` | `TextToSpeech`, hoje sem `AudioAttributes` explícito (sai por A2DP, o roteamento padrão) — **não muda** |
| Gravação de vídeo+áudio (MP4) | `stream/VideoRecorder.kt` + `VideoCaptureHandler.kt` | Não relacionado a este plano — continua intocado |
| Gatilho de captura do sinal | `ui/CameraScreen.kt` (`LibrasCaptureRow`) | Hoje é um **toque manual** (início E fim), de UM sinal por vez — **muda**: wake word, sessão com vários sinais |

Este plano mexe em **duas coisas** que existiam antes: o gatilho (toque manual
→ duas wake words) e a classificação (deixa de ser chamada por este fluxo —
vira responsabilidade do pipeline de reconhecimento, detalhado em
`docs/sign-boundary-detector-plano.md`). E **adiciona** a perna de resposta
(escuta → STT → avatar). Nada do pipeline de vídeo/gravação MP4 muda.

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
| 8 | **Endpointing (detecção de fim)** | Decidir "a pessoa parou de sinalizar/falar". **Dois mecanismos diferentes, em dois planos diferentes**: do lado do vídeo, quem decide o fim de cada sinal é o pipeline de reconhecimento (`docs/sign-boundary-detector-plano.md`) — fora do escopo deste documento; do lado do áudio (fala do atendente), é a wake word "Libras Livre, encerrar" — não tem heurística de pausa pro áudio. |

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
   estado do diálogo decide o que cada uma significa.** São dois gatilhos
   verbais simétricos que agora delimitam **sessões**, não sinais individuais
   — "iniciar" abre a sessão de captura de vídeo (①→②) ou de escuta do
   atendente (④→⑤); "encerrar" fecha a que estiver ativa no momento. O
   `DialogState` decide qual ação cada frase dispara; o mesmo par serve pros
   dois canais.

   **Por que o prefixo "Libras Livre" e não só "iniciar"/"encerrar" soltas:**
   as duas são palavras comuns do português — um atendente pode dizer
   "vamos iniciar o atendimento" ou "posso encerrar sua ficha" sem nenhuma
   intenção de acionar o app. Prefixar com o nome do produto reduz bastante
   esse risco de falso-positivo, ao custo de uma frase mais longa pra falar.
   Tecnicamente é só uma frase mais longa treinada como um wake word atômico
   — tanto Porcupine quanto um modelo TFLite próprio suportam frases de
   várias palavras sem precisar de um segundo estágio de reconhecimento.

3. **[REVISADO] "Encerrar" fecha a SESSÃO inteira (pode ter vários sinais
   dentro), não mais um sinal isolado.**

   Decisão anterior (registrada aqui por transparência, não vale mais): a
   primeira versão deste plano usava a wake word "encerrar" pra fechar cada
   sinal individualmente. Isso foi revisto: **como cada sinal é segmentado e
   reconhecido dentro da sessão deixou de ser problema deste documento** — é
   inteiramente responsabilidade do pipeline de reconhecimento
   (`docs/sign-boundary-detector-plano.md`), que roda dentro do estado ②
   independente de quando o atendente fala "encerrar". O papel da wake word
   aqui é só marcar **quando a sessão inteira começa e termina**, não mais
   quando cada sinal termina — ver §5, estado ②.

   O que continua valendo, e é genuinamente sobre áudio/wake word:
   - **Quem fala "encerrar" é o atendente**, não a pessoa sinalizando (ela não
     fala) — agora ele fala uma vez por sessão/frase, não uma vez por sinal.
   - **Concorrência não validada**: o `WakeWordDetector` (mic do celular)
     precisa continuar funcionando o tempo todo durante a sessão de escuta do
     atendente (estado ⑤, com HFP ativo simultaneamente). Item de validação
     em hardware real na Fase 0 (§7).
   - **A frase de encerrar pode vazar pro texto transcrito** durante a escuta
     do atendente (estado ⑤/⑥) — precisa ser cortada do texto antes do
     handoff pro avatar (§6.4, §7 Fase 5).

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

6. **[NOVO] Wake word liga/desliga a câmera+stream dos óculos, não só a sessão lógica.**
   "Libras Livre, iniciar" em ① agora também garante câmera+stream ativos antes de abrir a
   captura (chama `startSession()`/`startStreaming()` via `CameraViewModel.ensureCameraActiveForLibras`,
   esperando o resultado); "Libras Livre, encerrar" em ② desliga o stream
   (`deactivateCameraForLibras`) assim que a sessão de sinais fecha, já que os estados seguintes
   (③-⑦) são só áudio. A `DeviceSession` (conexão Bluetooth com os óculos) não é encerrada a cada
   ciclo — só o stream — pra a próxima "iniciar" não pagar o custo de reconexão inteiro. Motivo:
   o caso de uso institucional (§1 de `docs/libras-livre-arquitetura.md`) tem os óculos ligados o
   dia todo entre atendimentos — manter a câmera transmitindo continuamente entre uma sessão de
   sinais e a próxima gastaria bateria/rádio à toa, o mesmo raciocínio já aplicado à wake word
   (item 1 acima).

7. **[NOVO] Motor de wake word escolhido (fecha a decisão em aberto do §8, item 1): `SpeechRecognizer`
   contínuo, não Porcupine nem TFLite próprio.** `SpeechRecognizerWakeWordDetector` (mic do celular)
   reaproveita a mesma API já usada em `SttEngine` — zero dependência nova, sem conta/licença
   externa, sem dataset de áudio pra coletar. Trade-off aceito conscientemente: não é
   keyword-spotting de verdade (mais custoso, historicamente depende de rede em muitos aparelhos —
   ver `PoC/api/README.md` sobre o objetivo on-device/offline do projeto) e a concorrência com o
   `SttEngine` durante ⑤ (dois `SpeechRecognizer` ativos ao mesmo tempo, mics diferentes) segue tão
   não-validada em hardware real quanto estava antes (Fase 0 do §7 continua pendente). Fica atrás
   da mesma interface `WakeWordDetector`, então pode ser substituído por Porcupine/TFLite depois
   sem tocar no `DialogOrchestrator`. Os botões de fallback (`DialogControlRow`) continuam
   funcionando incondicionalmente — chamam `DialogOrchestrator.onWakeWord` direto, não passam pelo
   motor real.

8. **A geração do avatar reaproveita o plano já existente**
   (`docs/vlibras-webview-plano.md`, branch
   `feat/Empacota-player-vlibras-em-webview-nativa`): texto → API
   `vlibras-translator-api` → glosa → `vlibras-player-webjs` numa WebView. Este
   plano só define **o que entrega o texto** pra esse pipeline — não duplica a
   decisão de arquitetura do avatar.

---

## 5. Máquina de estados

```
① AGUARDANDO SINAL            ← wake INICIAR ativa (mic celular)
        │ "Libras Livre, iniciar"
        ▼
② CAPTURANDO SINAIS            ← vídeo + landmarks acumulando; wake ENCERRAR ativa
        │
        │  Múltiplos sinais podem ser reconhecidos aqui dentro. Quantos, onde
        │  cada um começa/termina e como é classificado é decidido pelo
        │  pipeline de reconhecimento (LandmarkPipeline + SignBoundaryDetector
        │  + classificador — ver docs/sign-boundary-detector-plano.md), não
        │  por este documento. Este estado só permanece aberto até "encerrar".
        │
        │ "Libras Livre, encerrar" (falado pelo atendente, uma vez por
        │ sessão/frase — não mais uma vez por sinal)
        ▼
③ FALANDO (TTS → A2DP)         ← Speaker.speakAndAwait() sobre a frase
        │                         reconhecida na sessão (ver §6.5)
        │ TTS termina (UtteranceProgressListener.onDone)
        ▼
④ AGUARDANDO RESPOSTA          ← wake INICIAR ativa de novo (mic celular)
        │ "Libras Livre, iniciar"
        ▼
⑤ ESCUTANDO ATENDENTE          ← AudioSessionManager troca A2DP→HFP; mic dos óculos; wake ENCERRAR ativa
        │ "Libras Livre, encerrar" (mesmas duas frases, mesmo mic do celular — ver §4, item 3)
        ▼
⑥ TRANSCREVENDO                ← STT converte a captura em texto; corta a wake word do final
        │ texto pronto; AudioSessionManager libera HFP → volta A2DP
        ▼
⑦ GERANDO AVATAR               ← entrega o texto pro pipeline VLibras (outro plano)
        │
        └──────────────────────► volta pro ①
```

`INICIAR`/`ENCERRAR` nos rótulos acima são os nomes internos dos dois estados
de wake word (§6.3) — a frase de fato treinada/falada é sempre "Libras Livre,
..." (§4, item 2).

**Regra que percorre a máquina inteira:** só existe um dono do áudio por vez.
Nenhum componente decide roteamento por conta própria — todos consultam/mudam
o estado através do orquestrador central (§6.5). As duas wake words são o
único evento externo que muda de estado nesta máquina — o que acontece
*dentro* do estado ② (quantos sinais, onde cada um termina) é decidido pelo
pipeline de reconhecimento, não por este documento.

O detector de wake word fica **ativo** em ①②④⑤ (nos dois estados de espera e
durante as duas capturas, porque "encerrar" precisa ser ouvido enquanto a
sessão está rolando) — só pausa em ③⑥⑦, onde nenhuma das duas palavras tem
ação a disparar.

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
`UtteranceProgressListener`, pra permitir a transição ③→④ ser sequencial (sem
`delay()` arbitrário):

```kotlin
suspend fun speakAndAwait(text: String)
```

### 6.3 `WakeWordDetector.kt`

Mic do celular, escuta contínua **enquanto ativo** — quatro estados (①②④⑤,
não só nos dois de espera; ver §5). Reconhece **duas frases fixas** ("Libras
Livre, iniciar" / "Libras Livre, encerrar"), cada uma treinada como um wake
word atômico só — não é STT nem reconhecimento de frase livre. Emite qual das
duas ouviu; quem decide a ação é o orquestrador, olhando o `DialogState`
atual:

```kotlin
enum class WakeWord { INICIAR, ENCERRAR }

class WakeWordDetector(context: Context, private val onWakeWord: (WakeWord) -> Unit) {
  fun start()
  fun pause()
  fun stop()
}
```

Motor ainda **em aberto** — ver §8. Validar cedo (Fase 0, §7) se o motor
escolhido continua confiável rodando ao lado do HFP ativo (estado ⑤) — é a
concorrência levantada em §4, item 3.

### 6.4 Captura da resposta do atendente (STT input)

Uma classe nova, não uma extensão do `AudioInputHandler` existente (que está
acoplado ao `VideoRecorder`/gravação MP4 — propositalmente não mexido aqui).
Usa `AudioRecord` com `AudioSource.VOICE_COMMUNICATION` (segue o roteamento do
sistema, ao contrário de `AudioSource.MIC`) e `setPreferredDevice` pro
dispositivo SCO retornado pelo `AudioSessionManager`.

Responsável também por **cortar a wake word "Libras Livre, encerrar" do final
da transcrição** antes de entregar o texto pro handoff da §7 — ou delega isso
a quem chama o STT; qualquer uma das duas, mas precisa acontecer em algum
lugar único, não em ambos.

### 6.5 `DialogOrchestrator` (ou extensão do `CameraViewModel`)

Dono do `DialogState` (§5) e de todas as transições. Coordena
`WakeWordDetector`, o pipeline de reconhecimento de sinal (`LandmarkPipeline` +
`SignBoundaryDetector` + classificador — implementação e detalhes em
`docs/sign-boundary-detector-plano.md`, este documento só consome o
resultado), `Speaker.speakAndAwait`, `AudioSessionManager` e a captura de STT
— nenhum componente individual decide sozinho para onde o áudio vai, nem o
que uma wake word significa.

A frase falada em ③ vem do que o pipeline de reconhecimento acumulou durante
a sessão. Como uma sequência de sinais reconhecidos vira uma frase falável
não é detalhado aqui — reaproveita o mecanismo de combinação de sinais já
previsto no checklist da trilha mobile (`mobile-app-companion/README.md`,
tabela `combinacoesConhecidas`), não é uma decisão nova deste documento.

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
  "encerrar" de forma confiável com o HFP ativo ao mesmo tempo (estado ⑤) —
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
- [x] Escolher o motor (ver §8): `SpeechRecognizer` contínuo
  (`SpeechRecognizerWakeWordDetector.kt`).
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
- [x] Implementar o `DialogState` e as transições ①→⑦ completas.
- [x] Trocar o gatilho hoje manual (`LibrasCaptureRow`) pelas duas wake words
  — manter o botão manual como fallback/debug é uma opção a avaliar, não uma
  obrigação deste plano. (Decisão — ver §8, item 3: manter, chamando
  `DialogOrchestrator.onWakeWord` direto, sem depender do motor real.)
- [ ] Integrar com o pipeline de reconhecimento
  (`docs/sign-boundary-detector-plano.md`) pra saber o que falar em ③ — essa
  integração depende daquele plano estar pronto; a implementação do
  reconhecimento em si não é trabalho desta fase.
- **Critério de sucesso**: ciclo ①→③ (sessão de sinais → TTS da frase
  reconhecida) funcionando end-to-end por wake word, sem toque na tela.

### Fase 5 — Captura + STT da resposta
- [ ] Implementar a classe de §6.4.
- [ ] Escolher motor de STT (ver §8).
- [ ] Implementar o corte do "encerrar" final da transcrição (§4, item 3;
  §6.4) — validar que sobrevive a variações de como o STT pontua/formata o
  texto (maiúscula, pontuação depois da palavra, etc.).
- **Critério de sucesso**: ciclo ④→⑥ (wake word → resposta transcrita, sem
  o "encerrar" no texto final) funcionando isolado.

### Fase 6 — Handoff pro avatar
- [ ] Integrar a saída de texto de ⑥ com o pipeline de `docs/vlibras-webview-plano.md`
  (a API espera texto PT-BR; ver o fluxo `texto → glosa → player` lá descrito).
- [ ] Testar o ciclo completo ①→⑦→① pelo menos uma vez ponta a ponta.

### Fase 7 — Refinamento
- [ ] Timeout em ④/⑤ (se o atendente nunca responder — nem falar "iniciar"
  em ④, nem falar "encerrar" em ⑤ — voltar pro `IDLE` sozinho).
- [ ] Tratamento de interrupção do sistema durante ⑤ (ligação chegando —
  mesmo padrão que `AudioInputHandler.wasInterrupted` já cobre pro mic do
  celular).
- [ ] Medir impacto de bateria da escuta contínua de wake word (agora em
  quatro estados, não só dois — ver §5).

---

## 8. Decisões em aberto

Não bloqueiam o início da Fase 0-2, mas precisam ser fechadas antes da Fase 3
em diante:

1. **[DECIDIDO — ver §4, item 7] Motor de wake word.** Optou-se por uma terceira via, não
   listada nas duas rotas abaixo: `SpeechRecognizer` contínuo (reaproveitando a API do
   `SttEngine`), pra destravar a funcionalidade sem comprometer com custo/licença/dataset antes da
   validação em hardware (Fase 0, ainda pendente). Porcupine e o modelo TFLite próprio continuam
   opções válidas de upgrade, atrás da mesma interface `WakeWordDetector` — registradas aqui por
   completude:
   - **Picovoice Porcupine** — motor on-device pronto, SDK Android, treino de
     frase customizada via console deles (suporta frases de várias
     palavras, não só uma sílaba curta). Caminho mais rápido; precisa
     validar estado atual de licenciamento/custo antes de comprometer.
   - **Modelo próprio (TFLite pequeno)** — mesmo espírito do
     `sinal_classifier.tflite` já treinado neste projeto: features de áudio
     (ex. MFCC) + classificador raso. Mais controle e zero dependência
     externa, mas exige dataset de áudio das duas frases completas (várias
     vozes, ruído de ambiente) — um projeto de coleta paralelo ao de
     landmarks.

2. **Motor de STT da resposta do atendente.** Não decidido neste documento.
   Precisa ser coerente com o objetivo on-device/offline do projeto (mesmo
   critério que descartou depender de nuvem pro wake word) — avaliar opções
   on-device (ex. Vosk) vs. `SpeechRecognizer` do Android (mais simples, mas
   historicamente dependente de rede em muitos aparelhos).

3. **[DECIDIDO] Manter ou não o botão manual como fallback.** Mantido: os botões
   (`DialogControlRow`) chamam `DialogOrchestrator.onWakeWord` diretamente, sem depender do motor
   real — útil pra debug/teste e como contorno se a wake word real falhar em campo. Validar com
   uso real se essa dupla via (voz + toque) ainda faz sentido continua uma questão de UX em aberto.

---

## 9. Referências

- `mobile-app-companion/app/src/main/java/.../libras/README.md` — pipeline de
  reconhecimento de sinal já implementado.
- `mobile-app-companion/README.md` — checklist da trilha mobile, incluindo a
  tabela `combinacoesConhecidas` (§6.5) que este plano reaproveita pra montar
  frase a partir dos sinais reconhecidos.
- `computer-vision-model/PoC/api/README.md` — objetivo on-device/offline do
  projeto, premissa que orienta as decisões deste documento (ex.: motor de
  wake word e de STT, §8).
- `docs/sign-boundary-detector-plano.md` (branch `feat/sign-boundary-detector`)
  — **dependência direta** deste plano (§4, item 3): segmentação e
  classificação de cada sinal dentro da sessão vivem inteiramente lá, não
  aqui. A própria descrição de relação nesse outro documento (§2 dele) ainda
  reflete a versão antiga (independência mútua) e precisa ser atualizada
  quando ele for revisado.
- `docs/vlibras-webview-plano.md` (branch
  `feat/Empacota-player-vlibras-em-webview-nativa`) — plano completo do avatar
  3D (texto → glosa → WebView).
- `docs/libras-livre-arquitetura.md` — arquitetura geral e MVP vs. Produto.
- [`AudioManager.setCommunicationDevice`](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) — API oficial de roteamento (API 31+).

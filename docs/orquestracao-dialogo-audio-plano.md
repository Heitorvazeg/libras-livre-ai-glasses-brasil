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
> words** ("Libras Livre, iniciar" / "Libras Livre, encerrar"). Dentro da
> sessão de vídeo, a classificação **não espera mais o "encerrar"**: o
> `SignBoundaryDetector` (`docs/sign-boundary-detector-plano.md`, branch
> `feat/sign-boundary-detector`) roda o tempo todo, e a cada sinal que ele
> fecha, um modelo GCN local (`.tflite`) classifica na hora — permitindo
> capturar **vários sinais em sequência** numa única sessão. Os dois planos
> deixaram de ser desacoplados: este documento agora **depende** do
> `SignBoundaryDetector` pra funcionar (ver §4, item 3, revisado).

---

## 1. Objetivo

Hoje o app reconhece um sinal e fala a palavra (fluxo de mão única: surdo → app →
atendente). Este plano estende isso em duas direções:

1. **Vídeo → texto passa a suportar frases, não só um sinal isolado**: o
   atendente abre a sessão ("iniciar"), a pessoa surda sinaliza quantos sinais
   forem necessários, cada um é classificado localmente assim que termina, e o
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
| Classificação | `libras/LandmarkApi.kt` | `POST /classify` na API da PoC (DTW 1-NN) — **muda**: vira inferência local, ver §4 item 3 |
| Fala do sinal reconhecido | `libras/Speaker.kt` | `TextToSpeech`, hoje sem `AudioAttributes` explícito (sai por A2DP, o roteamento padrão) — **não muda** |
| Gravação de vídeo+áudio (MP4) | `stream/VideoRecorder.kt` + `VideoCaptureHandler.kt` | Não relacionado a este plano — continua intocado |
| Gatilho de captura do sinal | `ui/CameraScreen.kt` (`LibrasCaptureRow`) | Hoje é um **toque manual** (início E fim), de UM sinal por vez — **muda**: wake word, sessão com vários sinais |

Este plano mexe em **duas coisas** que existiam antes: o gatilho (toque manual
→ duas wake words) e a classificação (API remota DTW → modelo local GCN
`.tflite`, disparado por sinal, não por sessão). E **adiciona** a perna de
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
| 8 | **Endpointing (detecção de fim)** | Decidir "a pessoa parou de sinalizar/falar". **Dois mecanismos diferentes agora**: do lado do vídeo, é o `SignBoundaryDetector` (heurística de movimento, contínua, detecta o fim de **cada sinal** dentro da sessão); do lado do áudio (fala do atendente), continua sendo a wake word "Libras Livre, encerrar" — não tem heurística de pausa pro áudio. |
| 9 | **Boundary** | Neste documento, o momento em que o `SignBoundaryDetector` reporta a transição `SINALIZANDO → PARADO` (ver `docs/sign-boundary-detector-plano.md` §4.2) — é o gatilho de "classifica o que acumulou desde o boundary anterior". |
| 10 | **GCN + `.tflite`** | O classificador deixa de ser DTW 1-NN contra referências (via rede) e vira um modelo GCN (`computer-vision-model/treino/gcn.py`) exportado pra `.tflite`, rodando local no celular. Elimina a dependência de rede e o timeout de 30s do `/classify` atual — mas a exportação pra TFLite **ainda não foi validada** nesse repo (ver §8, item 4). |

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

3. **[REVISADO] Fim de CADA SINAL é por `SignBoundaryDetector`, não mais por
   wake word — "encerrar" agora fecha a SESSÃO inteira (a sequência de
   sinais), não um sinal isolado.**

   Decisão anterior (registrada aqui por transparência, não vale mais): a
   primeira versão deste plano rejeitava a heurística de pausa em favor de
   "encerrar" a cada sinal — simples, sem calibração. Isso foi revisto: com
   classificação local e barata (GCN `.tflite`, item 10 de §3), não faz mais
   sentido o atendente falar "encerrar" depois de **cada** sinal — é mais
   natural (e mais rápido) ele abrir a sessão uma vez, deixar a pessoa surda
   sinalizar a frase inteira, e fechar quando ela terminar. Quem decide onde
   um sinal específico acaba dentro dessa sessão é o `SignBoundaryDetector`.

   Como fica na prática (ver §5, estado ②): durante a sessão de captura, o
   `SignBoundaryDetector` roda continuamente sobre os landmarks. Toda vez que
   ele reporta um **boundary** (`SINALIZANDO → PARADO`, §3 item 9), o
   segmento acumulado desde o boundary anterior é classificado na hora pelo
   modelo GCN local, e o resultado entra num **buffer de sinais
   reconhecidos** da sessão. A sessão só termina quando "Libras Livre,
   encerrar" é ouvido — nesse momento, se houver um segmento ainda em aberto
   (o `SignBoundaryDetector` ainda em `SINALIZANDO`, atendente encerrou antes
   da pausa natural), esse resto também é classificado antes de fechar (não
   descartar silenciosamente).

   Isso resolve de quebra o problema que motivou a revisão: antes, se o
   atendente demorasse pra falar "encerrar" depois da pessoa parar de
   sinalizar, o clipe acumulado ficava com uma cauda de landmarks "parado"
   grudada nele, prejudicando a classificação (achado da revisão anterior
   deste documento). Agora isso não acontece — cada sinal já foi classificado
   no momento em que terminou, independente de quanto tempo o atendente
   demorar pra fechar a sessão depois disso.

   O que continua valendo da decisão original:
   - **Quem fala "encerrar" é o atendente**, não a pessoa sinalizando (ela não
     fala) — só que agora ele fala uma vez por sessão/frase, não uma vez por
     sinal.
   - **Concorrência não validada**: o `WakeWordDetector` (mic do celular)
     precisa continuar funcionando o tempo todo durante a sessão de escuta do
     atendente (estado ⑤, com HFP ativo simultaneamente). Item de validação
     em hardware real na Fase 0 (§7).
   - **A frase de encerrar pode vazar pro texto transcrito** durante a escuta
     do atendente (estado ⑤/⑥) — precisa ser cortada do texto antes do
     handoff pro avatar (§6.4, §7 Fase 6).

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
   plano só define **o que entrega o texto** pra esse pipeline — não duplica a
   decisão de arquitetura do avatar.

7. **Classificação vira local (GCN `.tflite`), substituindo o `POST /classify`
   da API da PoC.** Motivo: a API da PoC (DTW 1-NN) sempre foi documentada
   como "andaime de validação, não produção" (`PoC/api/README.md`) — o
   objetivo declarado do projeto sempre foi on-device/offline. Com
   classificação barata o bastante pra rodar a cada sinal (não só ao fim de
   uma sessão), o modelo GCN de `computer-vision-model/treino/gcn.py`
   (0,93-0,94 na literatura, contra 0,70 do baseline DTW — ver
   `treino/README.md`) se torna o candidato natural, desde que exportado pra
   `.tflite`. **Essa exportação ainda não existe** — é uma dependência deste
   plano, não algo já pronto (§8, item 4).

---

## 5. Máquina de estados

```
① AGUARDANDO SINAL            ← wake INICIAR ativa (mic celular)
        │ "Libras Livre, iniciar"
        ▼
② CAPTURANDO SINAIS            ← vídeo + landmarks acumulando; wake ENCERRAR ativa
        │
        │  SignBoundaryDetector roda o tempo todo aqui dentro (§3, item 9):
        │  a cada boundary (SINALIZANDO→PARADO), classifica o segmento na
        │  hora (GCN .tflite local) e guarda o resultado no buffer da sessão.
        │  Não sai deste estado por causa disso — só por "encerrar".
        │
        │ "Libras Livre, encerrar" (falado pelo atendente; se houver um
        │ segmento em aberto, classifica antes de seguir — §4, item 3)
        ▼
③ FALANDO (TTS → A2DP)         ← Speaker.speakAndAwait() sobre o buffer da
        │                         sessão (sequência de sinais → frase; ver §6.6)
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
o estado através do orquestrador central (§6.6). As duas wake words são o
único evento externo que muda de estado *nesta* máquina — o `boundary`
interno ao estado ② é outro evento externo, mas não muda de estado, só
alimenta o buffer.

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

### 6.5 `SignClassifier.kt`

Substitui o papel de classificação que `LandmarkApi.kt` tinha (esse arquivo
deixa de ser usado neste fluxo — ver §4, item 7). Carrega o modelo GCN
exportado (`computer-vision-model/treino/gcn.py` → `.tflite`) via TensorFlow
Lite `Interpreter`, roda local, sem rede:

```kotlin
class SignClassifier(context: Context) {
  fun classify(frames: List<FrameLandmarks>): String   // devolve a palavra reconhecida
  fun close()
}
```

Chamado pelo orquestrador (§6.6) a cada **boundary** que o
`SignBoundaryDetector` reportar dentro do estado ②, não uma vez só ao fim da
sessão — ver §5. Depende de `docs/sign-boundary-detector-plano.md` pra saber
**quais frames** formam cada segmento a classificar (onde um sinal começa e
termina dentro da sessão) — este componente só resolve *o que* é o sinal, não
*onde* ele está.

### 6.6 `DialogOrchestrator` (ou extensão do `CameraViewModel`)

Dono do `DialogState` (§5), do **buffer de sinais reconhecidos da sessão
atual** (lista que cresce a cada boundary classificado em ②, e é lida/limpa
ao entrar em ③), e de todas as transições. Coordena `WakeWordDetector`,
`LandmarkPipeline` + `SignBoundaryDetector`, `SignClassifier`,
`Speaker.speakAndAwait`, `AudioSessionManager` e a captura de STT — nenhum
componente individual decide sozinho para onde o áudio vai, nem o que uma
wake word significa, nem quando classificar.

Como a sequência de sinais reconhecidos vira uma frase falável (③) não é
detalhado aqui — reaproveita o mecanismo de combinação de sinais já previsto
no checklist da trilha mobile (`mobile-app-companion/README.md`, tabela
`combinacoesConhecidas`), não é uma decisão nova deste documento.

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

### Fase 4 — `SignClassifier` + integração com `SignBoundaryDetector`
- [ ] **Pré-requisito, fora deste repo/branch**: exportar o modelo GCN de
  `computer-vision-model/treino/gcn.py` pra `.tflite` — hoje não existe (§8,
  item 4). Bloqueia esta fase inteira.
- [ ] Implementar `SignClassifier.kt` (§6.5) carregando o `.tflite` via
  TensorFlow Lite `Interpreter`.
- [ ] Ligar ao evento de boundary do `SignBoundaryDetector`
  (`docs/sign-boundary-detector-plano.md`) — a cada boundary, classifica o
  segmento e adiciona ao buffer da sessão (§6.6).
- [ ] Testar com sinalização contínua real (vários sinais em sequência, sem
  falar "encerrar" entre eles) — confirmar que cada um é classificado
  separadamente e na hora certa, não só no fim.
- **Critério de sucesso**: uma sessão com 3+ sinais reconhecidos
  corretamente em sequência, sem tocar a tela, com a classificação de cada um
  acontecendo perto do momento em que o sinal terminou (não acumulada até o
  fim da sessão).

### Fase 5 — `DialogOrchestrator`
- [ ] Implementar o `DialogState`, o buffer de sinais da sessão, e as
  transições ①→⑦ completas.
- [ ] Trocar o gatilho hoje manual (`LibrasCaptureRow`) pelas duas wake words
  — manter o botão manual como fallback/debug é uma opção a avaliar, não uma
  obrigação deste plano.
- **Critério de sucesso**: ciclo ①→③ (sessão de sinais → TTS da frase
  reconhecida) funcionando end-to-end por wake word, sem toque na tela.

### Fase 6 — Captura + STT da resposta
- [ ] Implementar a classe de §6.4.
- [ ] Escolher motor de STT (ver §8).
- [ ] Implementar o corte do "encerrar" final da transcrição (§4, item 3;
  §6.4) — validar que sobrevive a variações de como o STT pontua/formata o
  texto (maiúscula, pontuação depois da palavra, etc.).
- **Critério de sucesso**: ciclo ④→⑥ (wake word → resposta transcrita, sem
  o "encerrar" no texto final) funcionando isolado.

### Fase 7 — Handoff pro avatar
- [ ] Integrar a saída de texto de ⑥ com o pipeline de `docs/vlibras-webview-plano.md`
  (a API espera texto PT-BR; ver o fluxo `texto → glosa → player` lá descrito).
- [ ] Testar o ciclo completo ①→⑦→① pelo menos uma vez ponta a ponta.

### Fase 8 — Refinamento
- [ ] Timeout em ④/⑤ (se o atendente nunca responder — nem falar "iniciar"
  em ④, nem falar "encerrar" em ⑤ — voltar pro `IDLE` sozinho).
- [ ] Tratamento de interrupção do sistema durante ⑤ (ligação chegando —
  mesmo padrão que `AudioInputHandler.wasInterrupted` já cobre pro mic do
  celular).
- [ ] Medir impacto de bateria da escuta contínua de wake word **e** do
  `SignBoundaryDetector`/`SignClassifier` rodando o tempo todo durante ②
  (agora dois processos contínuos, não só um).

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
     landmarks.

2. **Motor de STT da resposta do atendente.** Não decidido neste documento.
   Precisa ser coerente com o objetivo on-device/offline do projeto (mesmo
   critério que descartou depender de nuvem pro wake word) — avaliar opções
   on-device (ex. Vosk) vs. `SpeechRecognizer` do Android (mais simples, mas
   historicamente dependente de rede em muitos aparelhos).

3. **Manter ou não o botão manual como fallback.** Útil para debug/teste sem
   depender do wake word funcionando, mas é uma decisão de UX a validar com
   uso real.

4. **Exportação do GCN pra `.tflite` ainda não existe.** `treino/README.md`
   (`computer-vision-model`) diz explicitamente: "Exportação para TFLite e
   robustez a mudanças de ponto de vista ainda precisam ser validadas." A
   Fase 4 deste plano (§7) está bloqueada nisso — não é trabalho deste
   documento resolver, mas é uma dependência externa real, não um detalhe.

5. **Peso/latência do `SignClassifier` rodando por sinal, não por sessão.**
   Classificar a cada boundary (em vez de uma vez só, no fim) significa mais
   invocações do modelo por sessão — não medido ainda se isso é
   desprezível (típico de um GCN pequeno em `.tflite`) ou se compete por CPU
   com o `SignBoundaryDetector` e o `WakeWordDetector` rodando juntos no
   mesmo estado ②. Fica pra quando a Fase 4 tiver um `.tflite` de verdade pra
   medir.

---

## 9. Referências

- `mobile-app-companion/app/src/main/java/.../libras/README.md` — pipeline de
  reconhecimento de sinal já implementado.
- `mobile-app-companion/README.md` — checklist da trilha mobile, incluindo a
  tabela `combinacoesConhecidas` (§6.6) que este plano reaproveita pra montar
  frase a partir do buffer de sinais.
- `computer-vision-model/PoC/api/README.md` — decisão original de manter tudo
  on-device/offline como objetivo do projeto; a API DTW ali é o "andaime de
  validação" que este plano substitui na prática (§4, item 7).
- `computer-vision-model/treino/README.md`, `treino/gcn.py` — o modelo GCN
  que vira o `.tflite` consumido por `SignClassifier` (§6.5); exportação
  ainda pendente (§8, item 4).
- `docs/sign-boundary-detector-plano.md` (branch `feat/sign-boundary-detector`)
  — heurística de segmentação por sinal; **dependência direta** deste plano
  agora (§4, item 3, revisado) — não mais um componente desacoplado. A
  própria descrição de relação nesse outro documento (§2 dele) ainda reflete
  a versão antiga e precisa ser atualizada quando ele for revisado.
- `docs/vlibras-webview-plano.md` (branch
  `feat/Empacota-player-vlibras-em-webview-nativa`) — plano completo do avatar
  3D (texto → glosa → WebView).
- `docs/libras-livre-arquitetura.md` — arquitetura geral e MVP vs. Produto.
- [`AudioManager.setCommunicationDevice`](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) — API oficial de roteamento (API 31+).

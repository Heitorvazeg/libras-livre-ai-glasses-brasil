# Orquestração de diálogo bidirecional — wake word, áudio dos óculos e handoff pro avatar

> **Status (2026-09-12): implementado em grande parte.** `libras/dialogo/` tem os
> sete estados e o orquestrador; `libras/audio/` tem TTS (Piper/sherpa-onnx), STT
> (Vosk pt-BR) e a troca A2DP/HFP, todos ativos. Duas pendências: o wake word real
> (`OpenWakeWordDetector`) já tem classificadores pt-BR treinados, agora com o pool
> de negativos ACAV100M — pipeline em
> [`../wake-word-model/`](../wake-word-model/README.md), decisões em
> [`wake-word-treino-plano.md`](./wake-word-treino-plano.md). Falso-positivo caiu
> de 103–280/h pra **1,48/h (`iniciar`) e 0,00/h (`encerrar`)** — `encerrar` já bate
> o alvo do config (0,2/h), `iniciar` está a 7,4× dele — mas o recall caiu junto
> (0,63/0,40), ainda sem validação em hardware real. `SpeechRecognizerWakeWordDetector`
> continua sendo o motor ativo; e o estado ⑦ (handoff para o avatar) depende da
> branch do player VLibras.

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
| Fala do sinal reconhecido | `libras/Speaker.kt` | `TextToSpeech`, hoje sem `AudioAttributes` explícito (sai por A2DP, o roteamento padrão) — **muda**: vira motor trocável atrás de uma interface `TtsEngine`, implementação real passa a ser um modelo Piper local (ver §4 item 10, §6.6, §8 item 4) |
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

7. **[REVISADO — ver item 9] Motor de wake word `SpeechRecognizer` contínuo foi o primeiro
   destravado, não é mais o motor-alvo.** Registro histórico: `SpeechRecognizerWakeWordDetector`
   (mic do celular) reaproveitou a mesma API já usada em `SttEngine` — zero dependência nova, sem
   conta/licença externa, sem dataset de áudio pra coletar — pra destravar a funcionalidade antes de
   qualquer decisão de motor local estar pronta. Trade-off que motivou a revisão: não é
   keyword-spotting de verdade, historicamente depende de rede em muitos aparelhos (contraria o
   objetivo on-device/offline do projeto — `PoC/api/README.md`) e gasta mais bateria/CPU que um
   motor dedicado. Fica **mantido no código como implementação de fallback/referência** atrás da
   mesma interface `WakeWordDetector` — não é removido, só deixa de ser o motor principal (ver item
   9 abaixo pro motor real). Os botões de fallback (`DialogControlRow`) continuam funcionando
   incondicionalmente, independente de qual `WakeWordDetector` estiver ativo.

8. **A geração do avatar reaproveita o plano já existente**
   (`docs/vlibras-webview-plano.md`, branch
   `feat/Empacota-player-vlibras-em-webview-nativa`): texto → API
   `vlibras-translator-api` → glosa → `vlibras-player-webjs` numa WebView. Este
   plano só define **o que entrega o texto** pra esse pipeline — não duplica a
   decisão de arquitetura do avatar.

9. **[NOVO — REVISADO em 2026-09-11, ver nota de formato abaixo] Motor de wake word real:
   `openWakeWord`, treinado do zero pras duas frases pt-BR, exportado em `.onnx` (fecha §8, item 1,
   revisão do item 7 acima).** Motivo da escolha entre as opções do §8: Porcupine tem custo/licença
   externa; `openWakeWord` (Apache 2.0) é on-device, offline, roda leve (o próprio projeto cita
   várias instâncias simultâneas num Raspberry Pi 3) e não tem nenhuma dependência de conta.
   Pipeline: (a) mel-spectrogram → (b) modelo de embedding de fala pré-treinado (Apache 2.0,
   congelado, não precisa retreinar) → (c) classificador raso treinado por cima, **um por frase**.
   Nenhum modelo pronto do projeto serve — "Libras Livre, iniciar" e "Libras Livre, encerrar" não
   existem em nenhum pacote distribuído — então o item de esforço real não é a integração Android, é
   gerar um **dataset sintético em pt-BR** (TTS + augmentation de ruído/RIR via o notebook
   `automatic_model_training.ipynb`, trocando a voz sintética default (inglês) por uma voz Piper
   pt-BR — mesmo tipo de voz que o item 10 já traz pro projeto) e curar negativos/confusables
   (frases parecidas, menções soltas a "Libras Livre") na mão — não existe exemplo pronto de alguém
   tendo feito isso pra frases compostas em português. Do lado Android, zero trabalho de
   reimplementar extração de features: [`Re-MENTIA/openwakeword-android-kt`](https://github.com/Re-MENTIA/openwakeword-android-kt)
   (Apache 2.0) já embute mel-spectrogram + embedding model, só espera o `.onnx` do classificador
   treinado.

   **Correção de formato (verificada direto no código-fonte da lib ao implementar, §7 Fase 3):** a
   suposição original aqui era `.tflite` (mesmo espírito do `sinal_classifier.tflite` já usado no
   projeto). Não procede — `Re-MENTIA/openwakeword-android-kt` roda sobre **ONNX Runtime**
   (`ai.onnxruntime.OrtSession`), não TFLite; os três modelos (mel-spectrogram, embedding, e o
   classificador custom por frase) são `.onnx`. Isso não muda a escolha do motor nem o pipeline de
   treino (o `openWakeWord` já exporta `.onnx` nativamente — é o TFLite que exigiria uma conversão
   extra, não o contrário), só o formato final do arquivo pedido no notebook de treino. **Também não
   está publicada em nenhum repositório Maven/JitPack** (o próprio README manda rodar
   `./gradlew :wakeword:publishToMavenLocal` a partir de um clone) — por isso o código-fonte dela
   está vendorizado direto no projeto, em `app/src/main/java/com/rementia/openwakeword/lib/`
   (Apache 2.0, atribuição no topo de cada arquivo), em vez de entrar como dependência Gradle.
   `SpeechRecognizerWakeWordDetector` (item 7) fica no código como segunda implementação de
   `WakeWordDetector` — troca de motor é só trocar qual implementação o
   `DialogOrchestrator` instancia. Alternativa descartada por pesquisa: KWS "sem retreino" do
   `sherpa-onnx` (item 10) não serve aqui — só tem modelo pré-treinado zh/en, e forçar fonemas pt-BR
   nesse tokenizador BPE não tem precedente de funcionar (reintroduziria o mesmo custo de treino, com
   um pipeline menos maduro pra isso que o do `openWakeWord`).

10. **[NOVO] Motor de TTS real: Piper (voz pt-BR) via `sherpa-onnx`, não `.tflite`.** Motivo do
    desvio do formato originalmente cogitado: Piper roda sobre ONNX Runtime (arquitetura VITS), não
    TFLite nativamente, e não existe nenhum caso documentado de alguém convertendo um modelo
    Piper/VITS pra `.tflite` com sucesso — os ops dinâmicos do duration predictor tendem a quebrar
    essa conversão (a própria comunidade do Piper desaconselha tentar, ver issue linkada em §9).
    Caminho maduro e testado em produção: [`k2-fsa/sherpa-onnx`](https://github.com/k2-fsa/sherpa-onnx)
    (Apache 2.0) empacota Piper + ONNX Runtime Mobile + eSpeak-ng num AAR Android com API Kotlin
    pronta (`OfflineTts`) — só entram como assets o `.onnx` da voz escolhida + os dados do eSpeak-ng.
    Voz recomendada: `pt_BR-edresson-low` (~63MB) ou uma das `medium` (`cadu`/`faber`/`jeff`,
    ~60-65MB) de [`rhasspy/piper-voices`](https://huggingface.co/rhasspy/piper-voices/tree/main/pt/pt_BR)
    — decidir qualidade vs. tamanho fica pra Fase 2 (§7). Muda §2 e introduz a interface `TtsEngine`
    nova (§6.6) — `Speaker.kt` deixa de chamar `android.speech.tts.TextToSpeech` direto e passa a
    delegar pra essa interface, mantendo `speakAndAwait` (§6.2) como está pro resto do orquestrador.

11. **[NOVO] Motor de STT real: Vosk pt-BR small.** Fecha §8, item 2. Entre as opções pesquisadas
    (Whisper `tiny`/`base` em `.tflite`, Whisper `small`, Vosk), Vosk foi o escolhido:
    `vosk-model-small-pt-0.3` (~31MB, licença Apache 2.0, JNI oficial maduro e testado em produção
    Android) tem o menor risco de entrega — Whisper `tiny`/`base` multilíngue tem WER alto em pt-BR
    (~31-35%/~22-24%, dado do paper oficial), arriscado pra um app de acessibilidade; Whisper `small`
    tem WER bem melhor (~13%) mas nenhum `.tflite` quantizado pronto foi encontrado — exigiria
    conversão/otimização própria, sem tamanho final garantido. Vosk não é `.tflite` (formato Kaldi
    próprio, WFST) nem é neural moderno feito o resto do stack — trade-off consciente por robustez.
    Fica como upgrade futuro trocar por Whisper `small` (ou outro motor neural) se a qualidade do
    Vosk se mostrar insuficiente em uso real, atrás da mesma interface `SttEngine` já existente.

12. **[NOVO] Timeout de 1 minuto de inatividade nas sessões ATIVAS (② capturando sinais, ⑤
    escutando atendente) — não nos estados de espera (①④).** Adianta parte do item de Fase 7
    "Refinamento" (§7) que antes previa isso só pra ④/⑤. Comportamento: se ninguém sinalizar/falar
    por 60s dentro de uma dessas duas sessões, o `DialogOrchestrator` encerra sozinho — mesmo
    efeito de ouvir "Libras Livre, encerrar" (`DialogOrchestrator.endSignSession`/`endListening`).
    Não se aplica a ①/④ propositalmente: nesses dois só a wake word real ou o botão de fallback
    devem tirar o app da espera — inatividade ali é o estado normal (esperando alguém agir), não um
    problema a corrigir.

    Implementação assimétrica entre os dois estados, registrada porque não é óbvia lendo o código:
    em ②, o timer **reinicia a cada gesto reconhecido OU tentado** (`onSignRecognized`/
    `onSignRecognitionFailed`) — inatividade de verdade, não um teto fixo de sessão; alguém
    sinalizando por 5 minutos seguidos não é cortado. Em ⑤, o timer é **fixo desde o início da
    escuta** — `SttEngine` só entrega `onResult`/`onError` uma vez, no fim (§6.4), não expõe
    nenhum sinal de "ainda tem gente falando" no meio da captura (precisaria de VAD/resultados
    parciais, fora de escopo aqui); uma resposta genuinamente longa do atendente (>60s) seria
    cortada no meio mesmo com fala contínua — limitação conhecida, revisitar se incomodar na
    prática.

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
        │  por este documento. Este estado só permanece aberto até "encerrar"
        │  — OU até 1 min sem nenhum gesto reconhecido/tentado (§4 item 12).
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
        │ OU 1 min fixo desde o início da escuta, sem sinal de atividade (§4 item 12)
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

Motor escolhido: `openWakeWord` (§4, item 9) — nova implementação
`OpenWakeWordDetector` atrás desta mesma interface, usando
`Re-MENTIA/openwakeword-android-kt` (vendorizado — não é dependência Maven, ver
§4 item 9) por baixo, carregando o `.onnx` do classificador treinado pras duas
frases. `SpeechRecognizerWakeWordDetector`
(§4, item 7) fica como segunda implementação/fallback — o que troca é só qual
das duas o `DialogOrchestrator` instancia. Validar cedo (Fase 0, §7) se o
motor escolhido continua confiável rodando ao lado do HFP ativo (estado ⑤) —
é a concorrência levantada em §4, item 3; essa validação vale pra qualquer
implementação de `WakeWordDetector`, não é específica de uma delas.

### 6.4 Captura da resposta do atendente (STT input) + `SttEngine`

Uma classe nova, não uma extensão do `AudioInputHandler` existente (que está
acoplado ao `VideoRecorder`/gravação MP4 — propositalmente não mexido aqui).
Usa `AudioRecord` com `AudioSource.VOICE_COMMUNICATION` (segue o roteamento do
sistema, ao contrário de `AudioSource.MIC`) e `setPreferredDevice` pro
dispositivo SCO retornado pelo `AudioSessionManager`.

Responsável também por **cortar a wake word "Libras Livre, encerrar" do final
da transcrição** antes de entregar o texto pro handoff da §7 — ou delega isso
a quem chama o STT; qualquer uma das duas, mas precisa acontecer em algum
lugar único, não em ambos.

Motor escolhido pra implementar `SttEngine` (`libras/audio/SttEngine.kt`):
Vosk pt-BR small (§4, item 11), via JNI oficial (`vosk-android`), consumindo o
PCM 16kHz que esta classe captura — diferente de
`AndroidSpeechRecognizerSttEngine` (implementação-base atual, que não expõe
captura própria porque o `SpeechRecognizer` do Android gerencia o mic
internamente), a nova implementação (`VoskSttEngine`) *precisa* dessa classe
de captura porque Vosk consome PCM cru, não uma API de "grave e me devolva o
texto". `AndroidSpeechRecognizerSttEngine` fica no código como segunda
implementação/fallback, mesmo padrão do item acima.

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

Também dono do timer de inatividade de ②/⑤ (§4 item 12) — `resetIdleTimeout`/`cancelIdleTimeout`,
armado/reiniciado nos mesmos pontos que decidem entrar/sair desses dois estados, encerrando a
sessão sozinho (`endSignSession`/`endListening`) depois de 1 min sem atividade.

### 6.6 `TtsEngine.kt` — nova interface, `Speaker.kt` passa a delegar

Hoje `Speaker.kt` chama `android.speech.tts.TextToSpeech` diretamente — sem
interface, diferente do padrão já usado em `WakeWordDetector`/`SttEngine`.
Pra poder trocar de motor sem tocar no `DialogOrchestrator` (mesmo motivo das
outras duas), introduzir:

```kotlin
interface TtsEngine {
  suspend fun speakAndAwait(text: String)
  fun stop()
}
```

`Speaker.kt` (§6.2) vira uma casca fina que delega pra essa interface — o
`speakAndAwait` que o orquestrador chama continua igual, só muda o que
acontece por baixo. Implementação real: `PiperSherpaOnnxTtsEngine` (§4, item
10), carregando a voz `.onnx` + dados do eSpeak-ng dos assets do app via
`OfflineTts` do `sherpa-onnx`. Uma implementação `AndroidTextToSpeechEngine`
(o que existe hoje) fica como segunda opção/fallback, mesmo padrão dos outros
dois motores.

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

### Fase 2 — `Speaker.speakAndAwait` + motor de TTS local (Piper/`sherpa-onnx`)
- [x] Adicionar o `UtteranceProgressListener` (na implementação Android TTS
  base).
- [ ] Confirmar que `onDone`/`onError` disparam de forma confiável em
  diferentes tamanhos de frase.
- [x] Introduzir a interface `TtsEngine` (§6.6); mover a implementação atual
  pra `AndroidTextToSpeechEngine`.
- [x] Baixar/embarcar a voz Piper pt-BR escolhida (`pt_BR-edresson-low`,
  variante `int8`, ~21MB — `app/src/main/assets/tts/pt_br/`) + dados do
  eSpeak-ng como assets; dependência `sherpa-onnx` entra como `.aar` local
  pré-compilado em `app/libs/sherpa-onnx-1.13.8.aar` — variante
  **static-link-onnxruntime** da release (não a genérica), pra não colidir
  com o `libonnxruntime.so` que a dependência `onnxruntime-android` do wake
  word (item 9) também empacota (não é Maven Central — ver §4 item 10),
  referenciado em `app/build.gradle.kts`.
- [x] Implementar `PiperSherpaOnnxTtsEngine` sobre `OfflineTts` do
  `sherpa-onnx` — geração roda numa `Thread` crua (padrão do exemplo oficial
  da lib, não um dispatcher de coroutine, porque a chamada nativa não coopera
  com cancelamento) e o resultado toca via `AudioTrack`.
- [x] `CameraViewModel` já instancia `PiperSherpaOnnxTtsEngine` como motor
  padrão de `Speaker`.
- [ ] Testar em hardware real (nenhum build/emulador rodou nesta sessão — sem
  Android SDK no ambiente; só revisão de código e conferência das APIs contra
  o código-fonte oficial da lib).
- [ ] Comparar qualidade e tamanho final do APK entre a voz `low` (a que está
  embarcada) e uma `medium` (`cadu`/`faber`/`jeff`, ~21MB int8 cada) antes de
  fixar qual vai pra produção.
- **Critério de sucesso**: `PiperSherpaOnnxTtsEngine` falando qualquer texto
  de teste isoladamente (sem depender do resto do fluxo), com latência e
  naturalidade aceitáveis num celular real.

### Fase 3 — `WakeWordDetector` (mic do celular, isolado, duas frases)
- [x] Escolher o motor inicial pra destravar (ver §4, item 7):
  `SpeechRecognizer` contínuo (`SpeechRecognizerWakeWordDetector.kt`).
- [x] Escolher o motor real (ver §4, item 9; §8, item 1): `openWakeWord`.
- [x] **[REVISADO]** Gerar o dataset sintético em pt-BR pras duas frases — mas não
  via `automatic_model_training.ipynb` como este item previa originalmente: o
  `piper-sample-generator` que o notebook usa (checkpoint multi-falante) só existe
  pronto em en/de/fr/nl, não em pt-BR (verificado nos releases oficiais e na busca
  do HuggingFace — ver `docs/wake-word-treino-plano.md` §2). Substituto: 6 vozes
  Piper pt-BR de comunidade (a mesma família da Fase 2) com timbre/velocidade
  variados, mais RIR (MIT) e ruído ambiente (ESC-50) reais na augmentation —
  `wake-word-model/dados/sintetizar.py`.
- [x] Curar negativos/confusables em pt-BR na mão (frases parecidas, menções
  soltas a "Libras Livre" sem o resto, "iniciar"/"encerrar" soltos em
  contexto de atendimento, e a **frase irmã** de cada classificador como negativo)
  — `wake-word-model/dados/frases.py`.
- [x] Treinar os dois classificadores (um por frase) e exportar em `.onnx`
  (2026-09-12, `wake-word-model/treinar.sh`) — o próprio pipeline de treino do
  `openWakeWord` já exporta `.onnx` nativamente (`.tflite` é que exigiria
  conversão extra, e nem é o formato que a integração Android abaixo espera — ver
  correção em §4 item 9). Duas rodadas na mesma sessão: a primeira pulou o pool de
  negativos pré-computado do ACAV100M (~17 GB) por achar inviável sem medir; a
  segunda mediu a banda real (~14 MB/s, ~20 min pro arquivo inteiro) e baixou
  (ver `docs/wake-word-treino-plano.md` §3).
  **Resultado medido** (`wake-word-model/resultados/*/relatorio.md`):
  falso-positivo caiu de 103-280/h (Rodada 1) pra **1,48/h (`iniciar`) e 0,00/h
  (`encerrar`)** (Rodada 2, alvo: 0,2/h) — `encerrar` já bate o alvo, `iniciar`
  está a 7,4× dele. Trade-off: recall caiu de 0,84/0,56 pra 0,63/0,40. Nenhuma das
  duas rodadas colapsou o treino (compare com `max_negative_weight: 1500` sem
  ACAV100M — esse sim dava TP=0 em tudo). **Ainda sem validação em hardware
  real**; ver `wake-word-treino-plano.md` §4 pro detalhe e pros próximos passos
  (ajuste de limiar antes de mexer em `max_negative_weight` de novo).
- [x] Vendorizar `Re-MENTIA/openwakeword-android-kt` em
  `app/src/main/java/com/rementia/openwakeword/lib/` (não publicada em
  Maven/JitPack — ver §4 item 9) e implementar `OpenWakeWordDetector.kt`
  atrás da interface `WakeWordDetector` existente. Assets prontos pra copiar
  de `wake-word-model/treino/libras_livre_{iniciar,encerrar}.onnx` (mais
  `melspectrogram.onnx`/`embedding_model.onnx`, fixos, via
  `download-assets.sh`) pra `app/src/main/assets/` — mas ver o item acima antes
  de fazer isso pra testar de verdade: falta a validação em hardware do critério
  de sucesso abaixo. Não wireado como motor padrão no
  `CameraViewModel` ainda — depende da validação abaixo.
- [ ] Validar taxa de falso-positivo/falso-negativo num ambiente ruidoso
  parecido com um balcão de atendimento (não silêncio de laboratório),
  **incluindo confundir uma frase pela outra** e confundir com menções
  soltas ao nome do produto ("Libras Livre" sem o resto da frase).
- **Critério de sucesso**: as duas detecções funcionando com o app em
  foreground, antes de integrar ao `StreamingService` — comparar
  objetivamente contra `SpeechRecognizerWakeWordDetector` (falsos
  positivos/negativos, latência, funciona offline) antes de trocar o motor
  padrão no `DialogOrchestrator`.

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
- [x] Implementar a classe de captura de PCM de §6.4
  (`AttendantAudioCapture.kt` — `AudioRecord` com `AudioSource.VOICE_COMMUNICATION`,
  fixado no dispositivo `TYPE_BLUETOOTH_SCO` corrente).
- [x] Escolher motor de STT (ver §4, item 11; §8, item 2): Vosk pt-BR small.
- [x] Baixar/embarcar `vosk-model-small-pt-0.3` (~31MB,
  `app/src/main/assets/vosk-model-small-pt-0.3/`); dependências
  `com.alphacephei:vosk-android:0.3.75@aar` + `net.java.dev.jna:jna:5.18.1@aar`
  em `app/build.gradle.kts` (coordenadas confirmadas contra o
  `vosk-android-demo` oficial — não estão indexadas na busca do Maven
  Central, só no `maven-metadata.xml` do repositório).
- [x] Implementar `VoskSttEngine` atrás da interface `SttEngine` existente,
  consumindo o PCM de `AttendantAudioCapture` — `CameraViewModel` já
  instancia os dois como motor padrão.
- [x] Corte do "encerrar" final da transcrição (§4 item 3; §6.4) — já
  implementado em `DialogOrchestrator.onAttendantTranscribed`
  (`TRAILING_ENCERRAR_PATTERN`), funciona igual pra qualquer `SttEngine`
  (não é específico do Vosk). Falta validar que sobrevive às variações reais
  de como o Vosk pontua/formata o texto (o formato JSON dele não capitaliza
  nem pontua por padrão, diferente do `SpeechRecognizer`).
- [ ] Testar em hardware real (sem Android SDK neste ambiente — só revisão de
  código e conferência das APIs contra o código-fonte oficial do
  `vosk-android`).
- [ ] Medir qualidade real (WER subjetivo) em pt-BR falado por atendentes
  reais, não só leitura de frase de teste — decide se Vosk basta ou se vale
  migrar pra Whisper `small` (alternativa registrada em §4, item 11).
- **Critério de sucesso**: ciclo ④→⑥ (wake word → resposta transcrita, sem
  o "encerrar" no texto final) funcionando isolado.

### Fase 6 — Handoff pro avatar
- [ ] Integrar a saída de texto de ⑥ com o pipeline de `docs/vlibras-webview-plano.md`
  (a API espera texto PT-BR; ver o fluxo `texto → glosa → player` lá descrito).
- [ ] Testar o ciclo completo ①→⑦→① pelo menos uma vez ponta a ponta.

### Fase 7 — Refinamento
- [x] Timeout de inatividade — adiantado e implementado com escopo revisado: **② e ⑤** (sessões
  ativas), não ④/⑤ como cogitado originalmente aqui (ver §4 item 12 pro motivo e pras diferenças
  de implementação entre os dois estados). ①/④ (estados de espera) continuam sem timeout,
  propositalmente.
- [ ] Testar o timeout de 1 min em hardware real — nenhum build rodou nesta sessão (sem Android
  SDK no ambiente).
- [ ] Tratamento de interrupção do sistema durante ⑤ (ligação chegando —
  mesmo padrão que `AudioInputHandler.wasInterrupted` já cobre pro mic do
  celular).
- [ ] Medir impacto de bateria da escuta contínua de wake word (agora em
  quatro estados, não só dois — ver §5).

---

### Nota sobre binários grandes (Fases 2, 3, 5)

Os motores locais trazem ~130MB de arquivos binários que **não são gerados pelo Gradle** — precisam
existir no working tree antes de compilar/testar: `app/libs/sherpa-onnx-1.13.8.aar` (~39MB,
variante `static-link-onnxruntime` — ver §9),
`app/src/main/assets/tts/pt_br/` (~37MB), `app/src/main/assets/vosk-model-small-pt-0.3/` (~52MB), e
futuramente os `.onnx` do wake word (Fase 3, ainda pendentes). Nenhum desses arquivos foi
adicionado ao git nesta sessão — ficam só no working tree local até alguém decidir
conscientemente se entram no histórico do repo (crescimento permanente), Git LFS, ou download em
CI/primeira execução. Ver `docs/CONTEXTO.md`/README da trilha mobile se esse tipo de asset grande
já tiver um padrão definido no projeto; se não tiver, é uma decisão a tomar antes do merge.

---

## 8. Decisões em aberto

Não bloqueiam o início da Fase 0-2, mas precisam ser fechadas antes da Fase 3
em diante:

1. **[DECIDIDO — ver §4, itens 7 e 9] Motor de wake word.** Passou por duas etapas: primeiro
   `SpeechRecognizer` contínuo (item 7), pra destravar sem comprometer com custo/licença/dataset
   antes de qualquer decisão de motor local — depois **revisado** pra `openWakeWord` (item 9) como
   motor real/alvo, treinado do zero pras duas frases em pt-BR e exportado em `.onnx`, integrado
   via `Re-MENTIA/openwakeword-android-kt` (vendorizado — não é dependência Maven, ver item 9). As
   duas ficam no código atrás da mesma interface
   `WakeWordDetector` (§6.3) — não é uma substituição destrutiva, é adicionar a segunda
   implementação e trocar qual o `DialogOrchestrator` usa por padrão depois de validada (Fase 3,
   §7). Rotas descartadas, registradas por completude:
   - **Picovoice Porcupine** — descartado por depender de conta/licença externa (motivo original,
     ver item 7).
   - **KWS "sem retreino" do `sherpa-onnx`** — pesquisado como possível atalho (evitaria treinar
     do zero), descartado: só tem modelo pré-treinado zh/en, sem precedente de funcionar com
     fonemas pt-BR nesse tokenizador (ver item 9).

2. **[DECIDIDO — ver §4, item 11] Motor de STT da resposta do atendente: Vosk pt-BR small**
   (`vosk-model-small-pt-0.3`, ~31MB, Apache 2.0, JNI oficial). Escolhido entre Vosk, Whisper
   `tiny`/`base` em `.tflite` (WER alto demais em pt-BR pro caso de uso) e Whisper `small` (melhor
   WER, mas sem `.tflite` quantizado pronto — conversão própria não garantida). Fica atrás da
   mesma interface `SttEngine` (§6.4) — `AndroidSpeechRecognizerSttEngine` (implementação-base
   atual) fica como segunda opção/fallback. Upgrade futuro pra Whisper `small` fica registrado
   como opção se a qualidade do Vosk não bastar em uso real.

3. **[DECIDIDO] Manter ou não o botão manual como fallback.** Mantido: os botões
   (`DialogControlRow`) chamam `DialogOrchestrator.onWakeWord` diretamente, sem depender do motor
   real — útil pra debug/teste e como contorno se a wake word real falhar em campo. Validar com
   uso real se essa dupla via (voz + toque) ainda faz sentido continua uma questão de UX em aberto.

4. **[DECIDIDO — ver §4, item 10] Motor de TTS: Piper (voz pt-BR) via `sherpa-onnx`, não
   `.tflite`.** Diferente da intenção original (tudo em `.tflite`) — Piper/VITS não tem caminho de
   conversão pra TFLite com precedente de sucesso (ops dinâmicos do duration predictor quebram a
   conversão). `sherpa-onnx` (Apache 2.0) empacota Piper + ONNX Runtime Mobile + eSpeak-ng com API
   Kotlin pronta, é o caminho maduro/testado em produção. Introduz a interface nova `TtsEngine`
   (§6.6) — `Speaker.kt` deixa de chamar `TextToSpeech` direto; a implementação Android nativa
   atual vira `AndroidTextToSpeechEngine`, segunda opção/fallback.

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
- [`dscripka/openWakeWord`](https://github.com/dscripka/openWakeWord) — motor de wake word real
  (§4, item 9), Apache 2.0; notebook de treino em
  [`notebooks/automatic_model_training.ipynb`](https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb).
- [`Re-MENTIA/openwakeword-android-kt`](https://github.com/Re-MENTIA/openwakeword-android-kt) —
  port Android/Kotlin (ONNX Runtime, não TFLite) do runtime de inferência do openWakeWord (Apache
  2.0). Não publicado em Maven Central/JitPack — código vendorizado direto no projeto, ver §4 item
  9.
- [`alphacep/vosk-api`](https://github.com/alphacep/vosk-api) — motor de STT real (§4, item 11);
  modelos em [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)
  (`vosk-model-small-pt-0.3`). Coordenadas Gradle confirmadas contra
  [`alphacep/vosk-android-demo`](https://github.com/alphacep/vosk-android-demo) (repo próprio,
  separado de `vosk-api`) — `com.alphacephei:vosk-android:0.3.75@aar` +
  `net.java.dev.jna:jna:5.18.1@aar`.
- [`rhasspy/piper`](https://github.com/rhasspy/piper) — motor de TTS real (§4, item 10); vozes
  pt-BR em [`rhasspy/piper-voices`](https://huggingface.co/rhasspy/piper-voices/tree/main/pt/pt_BR)
  — variante `int8` embarcada (`vits-piper-pt_BR-edresson-low-int8`, ~21MB, bem menor que a fp32
  originalmente estimada em §4 item 10).
- [`k2-fsa/sherpa-onnx`](https://github.com/k2-fsa/sherpa-onnx) — runtime Android usado pra rodar o
  Piper via ONNX Runtime Mobile. Sem publicação em Maven Central — `.aar` pré-compilado da
  [release v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8), variante
  `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` (~39MB — renomeado pra
  `sherpa-onnx-1.13.8.aar` em `app/libs/`; a variante genérica do mesmo tag colide com o
  `libonnxruntime.so` do `onnxruntime-android`, ver §4 item 9/10), referenciado como dependência de
  arquivo local em `app/build.gradle.kts`.
- [`rhasspy/piper` issue #699](https://github.com/rhasspy/piper/issues/699) — discussão da
  comunidade sobre por que não converter Piper/VITS pra TFLite (base do item 10, §4/§8).
- Paper oficial do Whisper (WER em português, MLS/Common Voice 9) —
  [arxiv.org/pdf/2212.04356](https://arxiv.org/pdf/2212.04356) — dado usado pra descartar
  Whisper `tiny`/`base` no item 11, §4.

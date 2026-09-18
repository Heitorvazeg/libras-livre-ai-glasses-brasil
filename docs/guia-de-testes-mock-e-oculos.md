# Guia de testes: o app em mock e com os óculos em mãos

**Data:** 2026-09-13 · **Prazo do hackathon:** 16/09/2026
**Contexto:** [plano de prontidão da demo](prontidao-demo/README.md) ·
[mapa de riscos](riscos-demo-2026-09-13.md) ·
[pontos de teste do modelo de visão](modelo-visao-pontos-de-teste.md)

Como validar o app em três momentos:

- **Parte A:** sem óculos, com o `MockDeviceKit`.
- **Parte B:** com os óculos na cabeça de alguém.
- **Parte C:** o ensaio geral.

Cada cenário aponta o item do plano que ele verifica e quando fica disponível:

| Marca | Significa |
|---|---|
| **[hoje]** | dá para rodar com o código atual da `dev` |
| **[onda N]** | só depois da onda N do [plano](prontidao-demo/README.md#ordem-de-implementação-ondas) |

---

## 0. Preparação comum

```bash
git clone <repo> && cd libras-livre-ai-glasses-brasil/mobile-app-companion
echo "github_token=SEU_TOKEN" >> local.properties   # PAT classic, escopo read:packages
export ANDROID_HOME=~/Android/Sdk                    # se buildar pelo terminal
./download-assets.sh                                 # modelos externos (~80 MB)
./gradlew assembleDebug                              # falha listando o que faltar
```

Use sempre o **build debug**: é nele que existem o menu de debug, o `MockDeviceKit` e as
configurações de demo.

**Emulador usado nesta sessão:** AVD `Pixel_7` (API 33, x86_64). Sem janela:

> **MediaPipe neste emulador (achado e corrigido em 2026-09-13):** até a 0.10.14 o AAR do
> MediaPipe não trazia `x86_64`, e esta imagem é só x86_64: o `LandmarkExtractor` não carregava, e
> o "erro falso" do A1 era, aqui, um erro verdadeiro. Além disso, **em qualquer aparelho** o
> MediaPipe recusava o frame em YUV e nenhum landmark saía. Com a **0.10.35** e a conversão
> `YuvParaArgb` ([3.1](prontidao-demo/03-captura-e-landmarks.md#31-a-corrida-do-iniciar-defeito-a)),
> o reconhecimento roda no emulador: o `LandmarkPipelineTurnosTest` usa o `pessoa.mp4` e exige
> pose em todos os frames. Os cenários com landmarks voltam a valer no AVD, com a ressalva de que
> GPU, temperatura e desempenho só o aparelho ARM mostra.

```bash
~/Android/Sdk/emulator/emulator -avd Pixel_7 -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect &
adb wait-for-device && adb shell getprop sys.boot_completed   # "1" = pronto
```

---

## Parte A: sem óculos (`MockDeviceKit`)

### A.1 Testes automatizados

```bash
./gradlew testDebugUnitTest                  # JVM: paridade, detector, guardas, contratos dos modelos
./gradlew connectedDebugAndroidTest          # instrumentados (emulador ou aparelho)
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.audio.WakeWordModelosCarregamTest
```

**[depois da onda 4]** 161 testes de unidade (1 pulado) e 34 instrumentados no Pixel 7 API 33 x86_64
(2 pulados: o `FluxoCompletoTest`, que exige o argumento abaixo, e a queda do avatar, que exige WebGL).
Os testes dos scripts: `python3 -m unittest scripts/test_calibracao_fronteiras.py`.

#### Fluxo completo no emulador [onda 4]

O `FluxoCompletoTest` conduz a tela do app por um atendimento inteiro, sem o modelo de visão:
`sinais.mp4` (movimento sintético, 3 segmentos) → placeholder no modo roteiro → "O meu filho quer a
vacina." → escuta → transcrição → tradução do VLibras → avatar ou legenda → ①. A resposta do
atendente entra pelo microfone do emulador, injetada por gRPC. Precisa de rede (VLibras) e fica fora
da suíte normal:

```bash
# terminal 1: sintetiza a resposta com a voz do app e espera a escuta abrir
uv run --no-project --with grpcio-tools --with sherpa-onnx --with numpy \
  scripts/fluxo_completo_emulador.py --texto "Qual é a idade dele?"
# terminal 2
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.FluxoCompletoTest \
  -Pandroid.testInstrumentationRunnerArguments.fluxoCompleto=true
```

Com `adb shell am instrument` (em vez do Gradle, que desinstala o app no fim), as capturas de tela de
cada estado ficam em `/sdcard/Android/data/<pacote>/files/fluxo-completo/`. **Resultado em 13/09, Pixel 7
API 33 x86_64:** a sequência Capturando → Falando → Ouvindo → Transcrevendo → Mostrando a resposta →
Aguardando sinais sai inteira em ~20 s; o Vosk transcreve a voz sintética como "qual é a idade dele" ou
"qual era a idade de"; o avatar não carrega (sem WebGL) e a legenda cobre.

### A.2 Preparar o mock

1. Abrir o app → botão flutuante de debug → `MockDeviceKit` → parear um Ray-Ban Meta simulado.
2. Fonte de câmera: **Video file**. Levar o vídeo ao emulador com
   `adb push video.mp4 /sdcard/Movies/` e escolher no seletor.
3. **Quais vídeos usar:**
   - **hoje:** clipes de Libras que vocês tiverem, com a pessoa de tronco inteiro no quadro;
   - **depois da Parte B:** as gravações feitas **pelos óculos** (B.3), que trazem o ponto de
     vista real para o mock.
4. **Operações de hardware simuladas** no mesmo menu:
   - **tap**: toque na haste, pausa ou retoma o stream;
   - **don / doff**: pôr e tirar os óculos;
   - **fold / unfold**: dobrar e abrir;
   - **power off**: desligar.
5. **Sem o modelo de visão:** configurações de demo → placeholder no modo **roteiro** (2.6)
   [onda 2]. As glosas das 4 sequências saem em ordem, uma por segmento detectado.
6. **Configurações de demo** ficam no topo do menu de debug (toque em "Configurações de demo" para
   abrir). Os controles do sample (sessão, preview, foto, gravação) ficam em "Controles da sessão", na
   tela da câmera [onda 4].

### A.3 Cenários

| # | Cenário | Passos | Esperado | Item | Quando |
|---|---|---|---|---|---|
| A1 | Corrida do "iniciar" | sessão iniciada **sem** preview → Iniciar | **hoje:** erro falso "modelos não carregaram" (confirma o defeito). **Depois:** "pode sinalizar" e frames no CSV | 3.1 | [hoje] / [onda 1] |
| A2 | Três turnos seguidos | repetir A1 três vezes, sem tocar em preview | os três coletam frames | 3.1 | [onda 1] |
| A3 | Contextualização | placeholder em **roteiro**, as 4 sequências | frases exatas do 2.9; tempo "glosas → frase" < 1,5 s no painel | 2.9 · 6.1 · 6.2 | [onda 3] |
| A4 | "Repita" | placeholder em **baixa**, 3 frases | aviso ao atendente duas vezes; na 3ª, "tente outro meio" e volta ao ① | 2.8 | [onda 3] |
| A5 | Uma volta com um comando | placeholder em **roteiro**, só "Iniciar" | ② → ③ sozinho após 2,5 s parado; escuta abre após a fala; fecha no silêncio | 4.1 · 5.3 | [onda 3] |
| A6 | Botão principal e volume | percorrer os estados usando só a tecla de volume | o rótulo muda e a tecla faz o mesmo que o botão | 4.7 | [onda 3] |
| A7 | ⑦ sem rede | modo avião, cache vazio, uma resposta | legenda em ≤ ~7 s; fluxo volta ao ① | 9.1 | [onda 1] |
| A8 | Pular | "Pular" no meio da animação | volta ao ① na hora | 9.1 · 4.7 | [onda 3] |
| A9 | Duas respostas no mesmo atendimento | responder duas vezes | a segunda anima sem a carga de 6 a 9 s; "Fechar" não recarrega | 9.2 · 6.3 | [onda 3] / [onda 4] |
| A10 | Queda do avatar | "Simular queda do avatar" → Iniciar | legenda cobre; avatar recarrega sozinho | 9.5 | [onda 4] |
| A11 | Microfone no celular | escuta sem nenhum dispositivo Bluetooth | transcreve (fale perto do microfone do host) | 5.2 | [onda 1] |
| A12 | Wake word fora do ⑤ | falar "Libras Livre, encerrar" durante a escuta | só o Vosk ouve; a frase não dispara nada | 4.4 | [onda 1] |
| A13 | Stream pausado | **tap** no meio de um sinal; retomar; depois tap por > 30 s | não corta o sinal; mensagem na tela; > 30 s encerra com aviso | 3.2 | [onda 4] |
| A14 | Câmera não sobe | **power off**, **doff**, **fold** antes do Iniciar | mensagem com a causa certa em cada caso | 3.4 | [onda 4] |
| A15 | Enquadramento | vídeo com os ombros cortados | "afaste-se: tronco fora do quadro" | 3.5 | [onda 4] |
| A16 | Tela ligada | tempo de tela em 30 s, sessão ativa 2 min sem tocar | tela continua ligada | 7.1 | [onda 1] |
| A17 | Aquecimento | abrir o app com todos os assets; depois um build com `-PlibrasLivre.permitirAssetsFaltando=true` sem os `.task` | tudo ✓; depois ✗ no MediaPipe com o motivo | 6.4 | [onda 4] |
| A18 | Gravador CSV | ligar o gravador, uma sessão, puxar o arquivo | CSV abre no pandas, uma linha por frame processado + eventos | 1.9 | [onda 2] |
| A19 | Painel de métricas | ligar o painel durante uma sessão | overlay com fps, etapas, térmico, RAM; linhas `metrica` no CSV | 3.8 · 6.5 | [onda 2] |
| A20 | Configurações persistem | mudar um seletor, fechar e reabrir | valor mantido; "voltar ao padrão" funciona | 10.6 | [onda 4] |
| A21 | Painel de conversa | uma volta completa | sinais, frase e resposta continuam visíveis depois de o stream desligar | 10.1 | [onda 1] |

**Comandos úteis**

```bash
# CSVs das sessões (gravador, 1.9)
adb pull /sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes

# latência por etapa (6.5) — a tag não tem ':' (o filtro -s do logcat não aceita)
adb logcat -s LibrasLatencia
python scripts/latencia_por_etapa.py sessoes/<arquivo>.csv          # [onda 2]
adb logcat -d -s LibrasLatencia | python scripts/latencia_por_etapa.py -

# memória
adb shell dumpsys meminfo com.meta.wearable.dat.externalsampleapps.cameraaccess
adb shell dumpsys meminfo | head -40      # inclui o processo sandboxed da WebView

# gravar a tela (máx. 3 min por arquivo)
adb shell screenrecord --time-limit 180 /sdcard/sessao.mp4
```

### A.4 O que o mock não testa

Estes itens só aparecem com hardware real:

- **Áudio Bluetooth:** A2DP, troca para HFP, microfone dos óculos.
- **Ponto de vista** da câmera na cabeça de alguém, e **rotação** real do vídeo.
- **Compressão, fps e descarte** do stream real.
- **Toque** real na haste, bateria e temperatura dos óculos.
- **GPU, WebGL, memória e temperatura** de um celular ARM (o emulador é x86 com GPU emulada).
- **Rede** do local da apresentação.

---

## Parte B: com os óculos em mãos

### B.0 Antes de começar

- [ ] Óculos carregados, *Developer Mode* ativo, app registrado.
- [ ] Permissão de câmera dos óculos e microfone concedidas.
- [ ] Celular ligado ao notebook por `adb` (USB ou Wi-Fi).
- [ ] Gravador CSV e painel de métricas **ligados** [onda 2].
- [ ] Uma câmera externa (outro celular) filmando a pessoa que sinaliza, para gerar vídeos de
      referência.
- [ ] Pelo menos 2 pessoas; o ideal é 3 (calibrar com duas e conferir na terceira).

### B.1 Roteiro do primeiro teste (~1h30, nesta ordem)

**1. Conexão e stream** [hoje]
- Iniciar sessão e preview.
- **O vídeo vem em pé?** (3.7) Se vier girado, anotar o ângulo: o ajuste é no `LandmarkExtractor`.
- Medir "Iniciar → primeiro frame" três vezes (3.3). Com o painel: "iniciar → pode
  sinalizar" [onda 2].

**2. Enquadramento** [hoje, visual; onda 4 com o indicador]
- Achar a distância em que tronco e mãos cabem inteiros com quem usa os óculos olhando
  naturalmente para a pessoa. **Marcar no chão** (3.5, 11.4).
- **O repouso natural fica visível no quadro?** (1.6, 1.7) Se as mãos saírem ao repousar,
  anotar: o teto de oclusão (900 ms) precisa ser revisto.

**3. Gravações** [onda 2: com CSV; hoje: só vídeo]

| Id | O quê | Para que serve |
|---|---|---|
| R1 | 20 s parado com as mãos **visíveis e paradas**, depois 20 s em **repouso** | piso de ruído (1.10) |
| R2 | os 20 sinais, uma vez cada, com repouso entre eles, por pessoa | pausas internas, velocidades, recall real (M2/M9) |
| R3 | as 4 sequências do roteiro, 3 vezes | validação ponta a ponta |
| R4 | 1 min de gestos que não são sinal: coçar, ajeitar óculos e cabelo, gesticular conversando | falsos segmentos e confiança (2.8, M8) |
| R5 | o atendente com as mãos no quadro; outra pessoa passando ao fundo | filtro de mãos e de pessoa (3.6) |

- Nomear os arquivos com o Id e a pessoa: `R2-pessoa1.csv`, `R2-pessoa1.mp4`.
- Os vídeos da câmera **dos óculos** (gravação do app ou screenrecord do preview) viram
  entrada do `MockDeviceKit` (A.2).

**4. Calibração rápida do detector** [onda 2 + script da onda 4]

```bash
python scripts/calibracao_fronteiras.py sessoes/R1-*.csv sessoes/R2-*.csv   # [onda 4]
```

O script calcula:
- **Piso de ruído:** p95 da velocidade em R1.
- **Limiares sugeridos:** saída ≈ 1,5–2× o piso; entrada ≈ 2,5–3× o piso.
- **Checagem:** o p10 da velocidade **dentro** dos sinais (R2) precisa ficar acima do limiar de
  saída. Se não ficar, o problema é enquadramento ou suavização, não o limiar.
- **Pausa mínima:** a maior pausa **interna** de um sinal (inversões, *holds*), com folga.
- **Teto de oclusão:** a maior perda de detecção dentro de um sinal.

Depois:
1. Aplicar os valores nas configurações de demo (1.8), sem gerar outro APK.
2. Repetir **R3** e contar os sinais cortados em dois, os juntados e os perdidos.
3. Registrar os valores finais no código, com referência ao resultado.

**5. Áudio** [hoje, parcialmente; onda 1/4 com os seletores]

| Teste | Como | Anotar |
|---|---|---|
| Saída de voz | óculos × celular (5.1) | audível para o atendente? e para a banca? |
| Microfone da resposta | celular × óculos (5.2) | acerto do Vosk nas 4 respostas do roteiro; a troca para HFP funciona? |
| Eco | voz no celular + microfone no celular (5.3) | a transcrição captou a própria fala? |
| Ruído | as 4 respostas com conversa ao fundo | palavras trocadas |
| Voz | `edresson-low` × uma pt-BR "medium", se disponível (5.8) | vale trocar? |

**6. Wake word** [onda 4 para o seletor; hoje só SpeechRecognizer]
- **Modo avião**, cada motor (4.5): 10 tentativas de "Libras Livre, iniciar" e 10 de "encerrar".
  Anotar os acertos.
- **10 min de conversa normal** perto do celular: contar os disparos falsos.
- Anotar se o `SpeechRecognizer` toca bipe a cada reinício.

**7. Hardware real** [onda 4]
- **Toque na haste** durante a captura (3.2): mensagem e retomada.
- **Tirar os óculos e dobrar** antes do "iniciar" (3.4): mensagem.
- **Controle Bluetooth de selfie** (4.7), se houver: avança o fluxo e convive com os óculos?

**8. Avatar no celular real** [hoje, parcialmente]

| Medida | Anotar |
|---|---|
| Abre e anima? (WebGL) | sim/não |
| Tempo até ficar pronto | s |
| Primeira resposta com cache frio × aquecido | s / s |
| Modo avião com cache aquecido | anima ou legenda? |
| Memória com o avatar carregado (`dumpsys meminfo`) | MB (app + processo sandboxed) |

**9. Desempenho e resistência** [onda 2 para o painel]

Comparar as etapas do painel com as metas (6.6):

| Etapa | Meta | Mediana | Pior |
|---|---|---|---|
| "iniciar" → pode sinalizar | < 3 s | | |
| classificação de um sinal | < 100 ms | | |
| glosas → frase | < 1,5 s | | |
| frase → primeiro áudio | < 1 s | | |
| fim da fala → texto | < 1,5 s | | |
| texto → avatar sinalizando | < 3 s (cache aquecido) | | |

E mais:
- **fps recebido × processado** e ocorrências de "fila do decodificador cheia" (3.8). Se o
  processado ficar bem abaixo de 24: testar o seletor CPU/GPU (3.9) e o decodificador de
  hardware (3.8b).
- **15 a 20 min seguidos** com o roteiro em loop (7.3): temperatura, fps processado e latência
  ao longo do tempo.
- **Bateria dos óculos** (7.4): minutos de captura até o aviso de bateria.
- **Memória por estado** (8.4), em aguardando, capturando, falando, escutando e avatar:

  | Estado | App (MB) | WebView (MB) | Total |
  |---|---|---|---|
  | aguardando | | | |
  | capturando | | | |
  | falando | | | |
  | escutando | | | |
  | avatar | | | |

  Daqui sai o **requisito mínimo de RAM** do aparelho da demo.

### B.2 Registro de resultados

Um arquivo por sessão de teste, em `docs/testes/AAAA-MM-DD-<assunto>.md`, com:
- aparelho, versão do Android, versão do app Meta AI e firmware dos óculos;
- commit do app e configurações de demo usadas;
- as tabelas acima preenchidas;
- os valores de calibração escolhidos e o porquê;
- problemas encontrados, cada um com o item do plano (ou "novo").

### B.3 O que fazer com as gravações

| Material | Vai para |
|---|---|
| CSVs R1/R2 | `scripts/calibracao_fronteiras.py` → parâmetros do detector (1.8) |
| CSVs R2/R3 e vídeos dos óculos | colega do modelo: caminho do app (M2), ponto de vista (M10), MediaPipe Tasks × Holistic (M6) |
| CSV R4 | confiança dos gestos que não são sinal → limiar (2.8, M8) |
| Vídeos dos óculos | fonte do `MockDeviceKit` para os cenários da Parte A |

---

## Parte C: ensaio geral (D-1)

Checklists completos no [ponto 11](prontidao-demo/11-operacao-de-palco.md).

1. **Configuração final** no aparelho da demo, com o APK final e o aquecimento todo em ✓.
2. **Roteiro completo** com os três papéis (quem sinaliza, atendente, narrador), três vezes.
3. **Cada linha da tabela de falhas** (11.7) provocada de propósito:
   - modo avião no ⑦;
   - voz desligada no interruptor;
   - toque na haste;
   - uma frase sinalizada "errada" até o "tente outro meio";
   - troca para o vídeo do `MockDeviceKit`.
4. **15 a 20 min seguidos** com o painel ligado; guardar o CSV.
5. **Vídeo de plano B:** `adb shell screenrecord` da tela e câmera externa na pessoa e no
   atendente, de uma execução completa que deu certo.

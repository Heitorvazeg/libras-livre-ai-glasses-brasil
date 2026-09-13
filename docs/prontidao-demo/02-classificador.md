# 2. Classificador: contrato de entrada, confiança e roteiro

O que o app entrega ao `sinal_classifier.tflite` e o que faz com a resposta. O export do
ST-GCN (`computer-vision-model/treino/exportar.py --arquitetura gcn`) põe dentro do grafo a
recentragem do z, a imputação, os ossos e a reamostragem para 64 frames, e grava um sidecar
`.json` com os rótulos e o contrato exato de entrada.

Diagnóstico: [mapa de riscos §2.1 e §4](../riscos-demo-2026-09-13.md#4-classificação).

**Pré-requisito externo:** o checkpoint da configuração de entrega
(`--arquitetura gcn --ossos --com-z --z-recentrado`) ainda não existe no repositório. Tudo
abaixo é implementado e testado com o export `--smoke`, que tem pesos aleatórios e o mesmo
formato de entrada, e com o placeholder em modos de confiança. O que cabe ao modelo está em
[`modelo-visao-pontos-de-teste.md`](../modelo-visao-pontos-de-teste.md).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 2.1 | z como terceira coordenada, com a fórmula exata do treino | P0 |
| 2.2 | App reamostra o segmento **pelo tempo** para os frames do contrato (96); o modelo não muda | P0 |
| 2.3 | **Revertido:** o app imputa na linha do tempo real, antes de reamostrar; a imputação do grafo fica ociosa | P0 |
| 2.4 | Mão esquerda/direita atribuída pelo pulso da pose mais próximo; muda só a orquestração do MediaPipe | P0 |
| 2.5 | Glosa fora do léxico não é falada; frase vazia conta como "não entendi" | P1 |
| 2.6 | Classificador versionado nos assets, validado pelo sidecar ao carregar; float32 | P0 |
| 2.7 | Testes de paridade de código: logits e caminho completo | P1 |
| 2.8 | Softmax no app, temperatura opcional no sidecar, limiar configurável; fluxo "repita" por frase | P1 |
| 2.9 | Roteiro de 4 sequências; template diz "Quero ir ao banheiro." | P1 |

---

## 2.1 O z

**Decisão.** Replicar a fórmula de `computer-vision-model/PoC/src/extract.py:90-105`, conferida
contra a configuração de entrega.

**A fórmula**, com `w` e `h` = largura e altura do frame em pixels:

```
origem   = média dos ombros (11, 12) em (x·w, y·h, z·w)
escala   = distância entre ombros só em x,y (pixels)
ponto    = ((x·w, y·h, z·w) − origem) / escala
mão ausente = três zeros
```

A recentragem do z das mãos no punho **está no grafo**; o app **não** a faz.

**Mudança.**
- `libras/reconhecimento/LandmarkNormalizer.kt`: `N_CANAIS = 3` e o z calculado como acima.
- `FrameLandmarks` já traz z para pose e mãos.
- `SignBoundaryDetector` continua usando só x e y.

**Teste (JVM).** Estender `LandmarkNormalizerTest` para 3 canais (z de pose e de mão, mão
ausente). Hoje os valores de referência são calculados à mão; o fixture gerado pelo Python
entra no 2.7.

## 2.2 Frames: o que o app tem × o que o modelo aceita

**Decisão.** Sem reexportar nem retreinar: o grafo recebe `frames` (96, o padrão do export) e
reamostra internamente para 64. O app entrega exatamente esses 96.

**Por que dá certo.**
- Os óculos mandam 24 fps, e o app processa menos quando o MediaPipe atrasa.
- Com os parâmetros do ponto 1 (até 3,5 s + 0,4 s de margens), um segmento tem **≤ ~94
  frames** a 24 fps. O app só **estica** para 96, nunca comprime.

**Mudança.** Novo `libras/reconhecimento/ReamostragemTemporal.kt`:
1. Recebe o segmento **já imputado** (2.3) com os timestamps de cada frame.
2. Gera 96 instantes igualmente espaçados entre o primeiro e o último timestamp.
3. Interpola linearmente cada coordenada nesses instantes. É o equivalente, pelo tempo, ao
   `np.interp` do `gcn.para_sequencia`, e reproduz o vídeo uniforme do treino mesmo quando o
   app descarta frames.
4. O número de frames vem do sidecar (`contrato_entrada.frames_fixos`), não de uma constante.
5. Segmento com mais frames que o contrato: reamostra do mesmo jeito e **registra no log**,
   porque nesse caso a imputação do grafo pode deixar de ficar ociosa.

**Divergência medida** entre reamostrar duas vezes (N → 96 → 64) e uma vez (N → 64), com
trajetórias sintéticas e N de 24 a 94:

| Movimento | Erro médio | Pior caso (p95 do erro máximo) |
|---|---|---|
| até 3 ciclos por segmento | 0,04–0,12% da amplitude | 0,2–1,1% |
| até 6 ciclos (rápido) | 0,10–0,37% | 0,6–3,2% |

É menor que o tremor do MediaPipe. A confirmação com o modelo real é o teste do 2.7.

**Teste (JVM).**
- Timestamps uniformes: o resultado bate com o `np.interp` em valores de referência gerados
  no Python.
- Timestamps com buracos (frames descartados): o resultado segue o tempo, não o índice.

## 2.3 Imputação: o app imputa

**Decisão (revertida em relação à primeira discussão).** Se o app reamostra antes, a imputação
dentro do grafo passaria a rodar sobre os 96 frames esticados, onde "5 frames" é outro
intervalo de tempo. Imputando no app, **na mesma ordem do treino**, o grafo não encontra
lacunas curtas para preencher:
- as curtas já foram preenchidas pelo app;
- as longas só crescem ao esticar para 96.

**Mudança.**
- `HandGapImputer` (lacuna máxima de 5 frames) roda sobre o segmento recortado (1.1),
  **antes** da reamostragem.
- O `TfliteSignClassifier` lê `contrato_entrada.imputacao_embutida` só para registrar no log;
  o app imputa nos dois casos.

**A conferir no modelo:** o limite de 5 frames foi definido na taxa de quadros dos vídeos de
treino; a 24 fps ele cobre outro intervalo de tempo. Está no documento do modelo.

## 2.4 Mão esquerda/direita pelo pulso da pose

**Decisão.** Igual ao Holistic do treino: cada mão detectada vai para o bloco do pulso da pose
mais próximo. Só muda a orquestração do MediaPipe; nenhum modelo muda.

**Mudança.** `libras/reconhecimento/LandmarkExtractor.kt`:
1. Para cada mão detectada, calcula a distância do ponto 0 da mão (punho) aos pulsos da pose
   (15 = esquerdo, 16 = direito), em coordenadas de imagem.
2. Atribui a mão ao pulso mais próximo. Se as duas disputarem o mesmo pulso, fica a mais
   próxima e a outra vai para o pulso livre.
3. O rótulo de *handedness* do `HandLandmarker` deixa de ser usado para decidir o lado.

O descarte de mãos longe dos dois pulsos (as do atendente) é o [3.6](03-captura-e-landmarks.md#36-mãos-e-corpos-que-não-são-da-pessoa-surda).

**Teste (JVM).** A lógica de atribuição fica numa função pura:
- mãos com rótulo trocado são atribuídas pelo pulso;
- duas mãos perto do mesmo pulso;
- uma mão só.

## 2.5 Glosa fora do léxico

**Decisão.** Não falar a palavra. Uma frase que fica vazia por isso é "não entendi".

**Mudança.**
- Antes de contextualizar, as glosas que não existem no `lexico-glosas.json` são removidas e
  registradas no log (e no CSV, 1.9).
- Se **todas** forem removidas numa sessão com segmentos válidos, o avaliador (2.8) decide
  `PedirRepeticao`, que conta como falha.

**Consequência a registrar:** o rótulo `maca` do MINDS não está no léxico (lá é `maçã`), então
esse sinal **nunca é falado**. Ele não está no roteiro (2.9).

**Teste (JVM).** No avaliador: uma sessão só com glosas desconhecidas → `PedirRepeticao`; uma
sessão mista → fala só as conhecidas.

## 2.6 Classificador versionado e validado

**Decisão.** Mesma regra dos modelos internos da PR #19; sem quantização.

**Mudança.**
- **Assets:**
  - `sinal_classifier.tflite` (float32, ~1,9 MB);
  - `sinal_classifier.json`, o sidecar que o `exportar.py` já gera, com `sha256`, `rotulos`,
    `contrato_entrada` e `paridade_pytorch`.
- **Novo `libras/reconhecimento/TfliteSignClassifier.kt`.** Ao carregar, confere contra o
  sidecar:
  - o sha256 do `.tflite`;
  - o shape `[1, frames, 57, 3]`;
  - o dtype `float32`;
  - a ordem da pose (`layout_landmarks.pose_ordenada`) igual ao `POSE_SUBSET` do
    `LandmarkNormalizer`;
  - o número de rótulos igual à saída.

  Qualquer divergência → **recusa o modelo** com erro visível na tela
  ([10.2](10-tela.md#102-faixa-de-estado)). Um modelo errado nunca roda em silêncio.
- **Saída do `classify`:** passa a ser `Classificacao(glosa, confianca, margem)`
  (confiança = softmax, 2.8).
- **`PlaceholderSignClassifier`:** continua existindo, com modos selecionáveis nas
  configurações de demo, para testar o fluxo sem o modelo:
  - **alta**, **baixa** e **aleatória**: confiança fixa ou sorteada, com uma glosa qualquer;
  - **roteiro**: devolve as glosas das 4 sequências do 2.9 em ordem, com confiança alta. É o
    que permite ensaiar o fluxo inteiro no `MockDeviceKit` (contextualização, fala, escuta e
    avatar) antes de o modelo existir.
- **Build:** quando o modelo chegar, `sinal_classifier.tflite` entra na lista da tarefa
  `verificarAssets` (PR #19).

**Teste (JVM).**
- O sha256 do asset bate com o do sidecar.
- A ordem da pose do sidecar bate com o `POSE_SUBSET`.
- Todo rótulo do sidecar existe no léxico. Rótulos que não existem, como `maca`, ficam numa
  lista explícita de "não falados" (2.5); o teste falha se aparecer um rótulo novo fora do
  léxico que não esteja nessa lista.

**Pronto quando** o `.tflite` do `--smoke` carrega pelo app e um sidecar adulterado é recusado.

## 2.7 Testes de paridade (só código)

**Decisão.** Testes automatizados, sem teste com pessoas.

**Mudança.**
- **Python (novo, em `computer-vision-model/treino/`):** gera um fixture JSON com algumas
  sequências de referência:
  1. os landmarks crus, como o MediaPipe entregaria;
  2. os landmarks normalizados;
  3. a sequência imputada e reamostrada pelo caminho do app;
  4. os logits do PyTorch pelo caminho do treino (N → 64).
- **Instrumentado (o LiteRT não roda na JVM):**
  - o `.tflite` sobre a entrada do item 3 reproduz os logits do PyTorch dentro de uma
    tolerância;
  - um segundo teste passa os landmarks crus pelo **caminho inteiro do app** (normalização →
    atribuição de mãos → imputação → reamostragem) e exige o **mesmo top-1** do caminho do
    treino.

**Pronto quando** os dois testes passam com o modelo `--smoke` e voltam a rodar quando o
checkpoint real chegar.

**Como ficou a onda 2 (2.1–2.4, 2.6, 2.7).**
- **2.1:** `LandmarkNormalizer.N_CANAIS = 3`, z pela fórmula do `extract.py`; o detector e a
  checagem de ausência de mão seguem só com x,y, e o `HandGapImputer` interpola os três canais.
- **2.2:** `ReamostragemTemporal.kt`, com valores de referência do `np.interp`. O número de frames
  vem do sidecar.
- **2.3:** a imputação roda por segmento recortado (1.1), antes da reamostragem.
- **2.4:** `AtribuicaoMaos.kt` (atribuição gulosa pelo par mão–pulso mais próximo), usada pelo
  `LandmarkExtractor`; o rótulo de handedness não decide mais.
- **2.6:** `SidecarClassificador.kt` (leitura e `ValidacaoClassificador`: sha256, shape, dtype,
  pontos, dimensões, frames, ordem da pose, número de rótulos, temperatura) e
  `TfliteSignClassifier.kt` (reamostra para o contrato, softmax com temperatura, margem). Modelo
  recusado → `ModeloRecusado` com os motivos, erro fixo na tela e `ClassificadorRecusado` no lugar
  (nenhuma classificação sai). Sem `sinal_classifier.tflite` nos assets, o app usa o
  `PlaceholderSignClassifier` com os modos `roteiro`/`alta`/`baixa`/`aleatoria`, escolhidos nas
  configurações de demo. O teste de rótulos × léxico (com a lista de não falados) existe e é pulado
  enquanto o modelo não está nos assets.
- **Toolchain do export, medido em 2026-09-13:** o `treino/README.md` pede `torch<2.10` com
  `ai-edge-torch`, mas o pacote virou `litert-torch`, que exige torch ≥ 2.11. Funcionou com
  **torch 2.13 + litert-torch 0.9.4 + torchvision 0.28** (CPU). Não mexemos no README da trilha;
  fica para quem cuida dela.
- **2.7:** o gerador do fixture mora em **`scripts/fixture_paridade_classificador.py`** e não em
  `computer-vision-model/treino/`: é ferramenta do app e só lê o código da trilha (sem gravar nada
  lá). Ele exporta o `--smoke` com semente fixa para `androidTest/assets/smoke_sinal_classifier.*`
  e grava `paridade_classificador.json`. Testes:
  - `ParidadeCaminhoAppTest` (JVM): normalização, imputação e reamostragem em Kotlin contra o
    Python (tolerância 2e-4);
  - `ClassificadorSmokeTest` (instrumentado): o sidecar carrega; sidecar adulterado (sha256, pose
    trocada) é recusado; o `.tflite` reproduz os logits do PyTorch; o caminho inteiro do app, dos
    landmarks crus aos logits, reproduz o PyTorch (tolerância 2e-3).
- **Divergência no 2.7 (top-1):** com pesos aleatórios o GCN responde **sempre a mesma classe**, com
  margem de 0,003 a 0,024 logit, para qualquer entrada — um top-1 igual não provaria nada. O teste
  compara os logits e só confere o top-1 contra o caminho do treino quando a margem passa de 0,5,
  o que só acontece com o checkpoint real.
- **Pendente:** o checkpoint de entrega. O time aponta um modelo de ~96% cujos arquivos não estão no
  repositório; o `--smoke` usa as flags da configuração de entrega registrada (ossos + z
  recentrado, 94,6/94,9%). Quando o checkpoint chegar: exportar, copiar `sinal_classifier.*` para os
  assets do app, rodar o script do fixture com ele e os testes acima.

## 2.8 Confiança e o fluxo "repita"

**Decisão.** Softmax no app; se o modelo vier com confiança exagerada, uma **temperatura**
calculada no Python vai **no sidecar**; limiar inicial de 60%, configurável. O fluxo decidido
é por **frase**: o **atendente** ouve o aviso, e depois de três frases rejeitadas seguidas vem
"tente outro meio de comunicação" e a sessão encerra.

**Mudança.**
- `TfliteSignClassifier`: `softmax(logits / temperatura)`, com temperatura lida de
  `calibracao.temperatura` no sidecar (padrão 1,0).
- Novo `libras/dialogo/AvaliadorDeFrase.kt`, uma classe Kotlin pura. Recebe as classificações
  da sessão e o motivo do encerramento, guarda o contador e decide:

  | Situação | Decisão | Contador |
  |---|---|---|
  | todas as glosas conhecidas com confiança ≥ limiar | `Falar(glosas)` | zera |
  | alguma < limiar, falha de classificação, ou todas fora do léxico (2.5) | `PedirRepeticao` | +1 |
  | 3ª rejeição seguida | `Desistir` | zera |
  | sessão sem segmentos, encerrada por **timeout** | `Ignorar` (volta ao ①) | não muda |
  | sessão sem segmentos, encerrada **manualmente** | `PedirRepeticao` | +1 |

  O critério é o **mínimo** da confiança, não a média. O contador também zera quando o
  atendimento termina por inatividade.
- `libras/dialogo/DialogOrchestrator.kt` executa a decisão:
  - os avisos tocam no estado `FALANDO` (wake word pausada);
  - na repetição, **a sessão com os óculos continua** (o stream não desliga, porque ainda é o
    mesmo turno de captura), a nova sessão do pipeline só abre **depois** do aviso, e o
    indicador "pode sinalizar" (3.1) volta a valer;
  - em `Desistir`, o stream desliga e o estado volta ao ①.
- **Avisos**, pré-sintetizados no aquecimento (6.4):
  - "Não consegui entender. Peça para repetir, com uma pausa entre os sinais."
  - "Não foi possível entender. Tente outro meio de comunicação."
- **Pré-requisitos:** 1.1 (sem repouso diluindo a confiança) e 1.5 (espasmo não é sinal).

**Teste (JVM).** Uma linha da tabela por caso, mais: falha → acerto zera; três falhas →
`Desistir` e zera; glosas desconhecidas.

**Pronto quando**, no `MockDeviceKit` com o placeholder em "baixa", três sessões seguidas terminam
em "tente outro meio", e em "alta" a frase é falada.

## 2.9 Roteiro da demo

**Decisão.** Quatro sequências só com os 20 sinais do MINDS, sem pronomes nem interrogativos,
na estrutura **[quem] + [coisa] + [verbo ou estado]**. O template precisa de verbo ou estado
para formar frase.

**Diálogo: vacinação no posto de saúde**

| # | Pessoa surda sinaliza | App fala (template) | Atendente responde |
|---|---|---|---|
| 1 | FILHO · VACINA · VONTADE | "O meu filho quer a vacina." | "Qual a idade dele?" |
| 2 | CINCO | "Cinco." | "Tudo bem, pode aguardar." |
| 3 | FILHO · MEDO | "O meu filho está com medo." | "Não se preocupe, é rápido." |
| 4 | BANHEIRO · VONTADE | "Quero ir ao banheiro." | "Fica no fim do corredor." |

Candidatas descartadas, conferidas com um porte do template:
- FILHO VACINA → "O meu filho a vacina.";
- BANCO ESQUINA → "O banco a esquina.";
- FILHO MEDO VACINA → "O meu filho está com medo a vacina.".

O bug "ACONTECER RUIM → Aconteceu." fica **fora** das correções.

**Mudança.** `libras/contextualizacao/TemplateGlossContextualizer.kt`: `vontade`/`querer` +
`banheiro` → "quero ir ao banheiro" (e "quer ir ao banheiro" na 3ª pessoa).

**Teste (JVM).** `TemplateGlossContextualizerTest` fixa a frase exata das 4 sequências.

**Verificações (vão para o guia de testes).**
- Rodar o modelo de contextualização nas 4 sequências e registrar se ele supera o template ou
  é barrado pela guarda.
- Com o checkpoint real, conferir o recall dos sinais do roteiro (FILHO, VACINA, VONTADE, CINCO,
  MEDO, BANHEIRO) e trocar o que confundir.

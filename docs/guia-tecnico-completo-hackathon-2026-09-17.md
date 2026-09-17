# Libras Livre — guia técnico completo (para o hackathon)

> Gerado em 2026-09-17 por varredura direta do código e dos docs do repo
> `libras-livre-ai-glasses-brasil` (branch `feat/ajustes-usabilidade`, a partir de `dev`).
> Objetivo: te devolver as decisões técnicas de cada trilha — o que foi decidido, por que, e
> o estado real de cada peça — para você não ter que "confiar na memória" na hora de
> apresentar. Tudo abaixo está rastreado a arquivo/doc; onde encontrei contradição entre
> documentos, deixei marcado explicitamente (procure por **⚠️ DIVERGÊNCIA**).
>
> **Nota de data:** o `docs/CONTEXTO.md` e vários outros documentos citam "prazo do
> hackathon: 16/09/2026", e hoje é 2026-09-17 — ou seja, esse prazo já passou segundo os
> próprios docs. Se o hackathon de amanhã é outro evento (reapresentação, final, etc.),
> tudo bem; só não assuma que "16/09" nos documentos é a data de amanhã.

---

## 0. O pitch em 30 segundos

Óculos Ray-Ban Meta (câmera + microfone) + celular pareado. Uma pessoa surda sinaliza em
Libras na frente dos óculos; o celular reconhece cada sinal, monta uma frase em português e
fala em voz alta pro atendente. O atendente responde por voz; o celular transcreve e mostra
a resposta pra pessoa surda via avatar 3D em Libras (VLibras). **Tudo on-device, sem
internet** (exceto o avatar, que é a única peça online). Pensado para dispositivo
**institucional** (balcão de atendimento), não pessoal — por isso nunca há calibração por
usuário, e toda avaliação de reconhecimento é *leave-one-signer-out* (LOSO): uma pessoa
inteira fica fora do treino, porque o produto real vai encontrar alguém que o modelo nunca
viu.

Três trilhas de IA, independentes, que se encontram em dois `.tflite` consumidos pelo app:

```
computer-vision-model/        contextualization-model/       wake-word-model/         mobile-app-companion/
vídeo -> glosa                glosa -> português              áudio -> "é o gatilho?"   o app Android
(MediaPipe, PyTorch)          (PyTorch, Transformers)          (openWakeWord/ONNX)       (Kotlin, roda tudo local)
     |                                |                              |                         ^
     +-- sinal_classifier.tflite -----+-- modelo_contextualizacao ---+-- *.onnx --------------+
                                            .tflite
```

---

## 1. `computer-vision-model/` — vídeo → glosa

### 1.1 A pergunta que organiza tudo

> Qual a acurácia com uma **pessoa que o modelo nunca viu**?

Não existe calibração por usuário nos óculos institucionais, então toda avaliação usa
*leave-one-signer-out* (LOSO): 8 rodadas, cada uma deixando uma das 8 pessoas do MINDS-Libras
inteiramente fora do treino.

### 1.2 Pipeline, ponta a ponta

```
vídeo .mp4 --MediaPipe Holistic--> landmarks .npy --representação--> modelo --> sinal (glosa)
            (57 pontos por frame)   (57 pts x [x,y] ou [x,y,z])      ST-GCN
```

| Etapa | Script | Custo |
|---|---|---|
| Baixar vídeos | `datasets/ingest*.py` (lê só o índice do `.zip` remoto, sem credencial Kaggle) | minutos |
| Extrair landmarks | `PoC/src/extract.py` | **horas, CPU — não acelera em GPU** |
| Pré-treinar | `treino/pretreinar.py` | ~1,5h CPU |
| Treinar + avaliar (LOSO) | `treino/treinar.py` | ~2,5h CPU / minutos GPU |
| Exportar | `treino/exportar.py` | segundos |
| Validar o encanamento | `treino/selftest.py` (roda em segundos, com dado sintético) | segundos |

**Os 57 pontos por frame:** 21 pontos por mão (MediaPipe Hands) × 2 + 15 pontos de pose
(nariz, olhos, orelhas, boca, ombros, cotovelos, pulsos, quadris). Pernas ficam de fora
(irrelevantes pra sinalização, e comumente fora do quadro num balcão). Os 15 pontos de pose
foram ampliados de 7 para 15 em 2026-09-08 porque muitos sinais usam **âncoras faciais**
("maçã (rosto)", "medo") — com só o nariz como referência, essa informação some.

**Por que landmarks e não pixels:** o modelo não vê a imagem, só a geometria. Isso o torna
invariante a cor de pele, roupa, fundo e iluminação; cabe num `.tflite` minúsculo; e precisa
de bem menos dado, porque o MediaPipe já resolveu "achar a mão" — resta aprender forma e
movimento de cada sinal.

**Normalização (o passo que mais afeta o resultado):** origem no ponto médio dos ombros,
escala pela distância entre eles. Isso torna os landmarks invariantes a distância da câmera,
posição no quadro e resolução do vídeo. **Não cobre** ângulo de câmera (óculos de cima, de
baixo, de lado) — nenhuma base pública disponível permite medir isso (todas são estúdio
frontal). É risco real de produto; só coleta própria responde.

O app replica esse algoritmo em Kotlin (`LandmarkNormalizer.kt`), com testes de paridade
numérica contra o Python — porque uma divergência aqui **não gera erro, degrada a acurácia em
silêncio**.

### 1.3 Duas arquiteturas medidas, uma escolhida

| Modelo | LOSO signer-independent | Parâmetros | Tamanho exportado |
|---|---|---|---|
| Chance aleatória (20 sinais) | 5,0% | — | — |
| Baseline DTW 1-NN (a PoC original) | 70,0% | — | — |
| ST-GCN (x,y apenas) | 72,1% | 0,46M | — |
| ST-GCN + ossos | 91,0% / 92,5% | 0,46M | — |
| **ST-GCN + ossos + z recentrado — MODELO DE ENTREGA** | **94,6% / 94,9%** | **0,47M** | ~1,9 MB fp32 / **0,47 MB int8** |
| ResNet-18 + imputação (Skeleton-DML) | 95,1% | 11,25M | 45 MB fp32 / 11,3 MB int8 |
| Literatura (mesma base, mesmo protocolo) | 93–94% | — | — |

**Decisão tomada em 2026-09-11:** ST-GCN é o modelo de entrega. Ele **empatou** com a melhor
ResNet (94,6/94,9 vs 95,1 — dentro da variância de ruído medida, ~1,7pp) usando **24× menos
parâmetros**. Para um modelo que precisa virar `.tflite` no celular, empatar com 1/24 do
tamanho decide.

**Duas ressalvas que têm que acompanhar qualquer citação desses números:**
1. A variância entre execuções (mesma config, mesma semente) é de **~1,7 ponto**. Diferenças
   menores que ~2pp são ruído, não resultado.
2. **Todos esses números são vídeo de estúdio**, frontal, enquadramento controlado. É teto
   otimista — o número real de balcão só sai com coleta própria.

**⚠️ Retratação registrada no próprio repo** (`docs/decisao-arquitetura-modelo.md`): uma
versão anterior do documento dizia que o GCN **superou** a ResNet. Estava errado — comparava
contra uma ResNet de 93,0% (um controle de sessão) ignorando que o próprio repositório tinha
uma ResNet em 95,1% (`resultados-resnet-imputado/`). O erro é registrado de propósito: "o
número escolhido foi o que confirmava a hipótese preferida." Vale lembrar dessa disciplina se
alguém da banca perguntar "o GCN é melhor?" — a resposta correta é "empatou, com muito menos
parâmetros", não "venceu".

### 1.4 O que fez o ST-GCN sair de 72% pra 94,6%

Duas features derivadas dos landmarks que **já existiam** — nenhum dado novo foi coletado:

- **Ossos** (vetor nó→pai na árvore anatômica, não pixel-a-pixel): **+18,9 pontos**, 8 de 8
  folds. É a mesma informação de um ângulo articular, sem trigonometria — o pulso não fica
  mais "onde a câmera o vê", fica "como o antebraço está orientado". Implementado em
  `treino/gcn.py::com_ossos`.
- **z recentrado**: **+2,1 pontos**, confirmado em segunda semente. Curiosidade: **o z ajuda
  o GCN e atrapalha a ResNet** (+2,1pp no GCN vs −4,2pp na ResNet). A decisão herdada da PoC
  de DTW de descartar o z estava certa **para a arquitetura errada**.
- O que **não** ajudou: adjacência adaptativa (−2,6pp). Kernel temporal 9→5 empatou com 35%
  menos parâmetros.

### 1.5 A arquitetura ST-GCN em detalhe (`treino/gcn.py`)

ST-GCN compacto (Yan et al., 2018), ~0,4M parâmetros. Ideia central: em vez de empilhar
landmarks como imagem (onde "ponto 5 do lado do ponto 6" é acidente de ordenação, não
anatomia), trata o esqueleto como **grafo** — a vizinhança é a anatomia real (cotovelo é
vizinho de ombro e pulso), e a convolução caminha pelas arestas.

- **57 nós**: 15 pose + 21 mão esquerda + 21 mão direita, ligados pelo punho da pose ao punho
  de cada mão (senão as mãos ficariam 3 componentes desconectados do grafo).
- **Raiz da árvore de ossos = nariz** (índice 0), escolhido por ser ponto fixo do
  espelhamento — assim a augmentação de espelho não quebra a correspondência de ossos.
- **4 blocos `_BlocoSTGCN`**: cada um é uma convolução espacial no grafo (`_ConvGrafo`, K=2 —
  o próprio nó + vizinhos imediatos) seguida de convolução temporal (kernel 9, ímpar por
  necessidade — par quebraria a soma residual).
- **Importância de aresta aprendida** (`self.importancia`): o grafo anatômico é o ponto de
  partida, não a palavra final — o treino pode enfraquecer ligações inúteis.
- **T fixo = 64 frames**, por reamostragem (interpolação linear), não padding — mantém o
  sinal inteiro representado, só muda a resolução temporal.

### 1.6 Pré-treino: por que o direto falhou e o contrastivo funcionou

- **Pré-treino por classificação direta na V-LIBRASIL** (1.353 palavras, 3 clipes cada, um
  por articulador): **falhou**. Validação em 0,2% contra 0,07% de chance — não aprendeu
  quase nada. O motivo é estrutural: reconhecer uma palavra vista 2× numa 3ª pessoa, com só 3
  exemplos, é tarefa quase não-aprendível. Pior: o caminho mais fácil de reduzir a perda
  vira "memorizar quem está sinalizando" — o oposto do que o produto precisa.
- **Diagnóstico e correção:** a mesma estrutura (3 pessoas × mesma palavra) que arruína a
  classificação é **ideal** para aprendizado contrastivo. Implementado em
  `treino/contrastivo.py` com **perda SupCon** (Khosla et al. 2020) e **amostrador P×K**
  (`AmostradorPK`) que garante K exemplos da mesma classe no lote — sem isso, amostragem
  aleatória num corpus de 1.353 classes quase nunca formaria um par positivo.
  - A métrica de seleção de época é **acurácia de recuperação** (top-1: o vizinho mais
    próximo na galeria é o mesmo sinal?), não a própria perda SupCon — porque um lote de
    validação sem nenhum par positivo dá perda 0,0, que seria erroneamente escolhida como "a
    melhor época".
  - Achado de bug registrado no código: `-inf × 0 = NaN`. Mascarar a diagonal da matriz de
    similaridade com `-inf` e depois multiplicar pela máscara de positivos envenena o lote
    inteiro em silêncio; a correção foi usar `torch.where` em vez de multiplicação.

### 1.7 Exportação — **atualizado em relação ao que o próprio README da trilha diz**

**⚠️ DIVERGÊNCIA entre documentos, importante de saber antes de citar isso amanhã:**

- `computer-vision-model/README.md` (§2.4, §4.4) diz: *"Limitação atual: `treino/exportar.py`
  só cobre a ResNet-18 [...] Escrever o export do ST-GCN [...] é o maior bloqueio."*
- `docs/CONTEXTO.md` (§5) repete a mesma afirmação.
- **Mas o código de `treino/exportar.py` (lido diretamente, linhas ~195-300) já tem um
  caminho completo para exportar o GCN**: monta `CabecaGCN` (que embute z-recentragem,
  imputação, cálculo de ossos e reamostragem temporal **dentro do grafo exportado**) e
  `ClassificadorGCN`, com validações de consistência de canais entre checkpoint e cabeça.
  Última alteração desse arquivo: **2026-09-15**.
- E o **checklist do `mobile-app-companion/README.md` (§6, atualizado 2026-09-16)** já marca
  como feito: `[x] Export do ST-GCN para .tflite (treino/exportar.py --arquitetura gcn)`.

**Conclusão prática:** o export do GCN parece estar pronto (código + confirmado no checklist
mais recente do app), mas os READMEs de `computer-vision-model/` e o `docs/CONTEXTO.md` ainda
descrevem isso como pendência. Os dois READMEs da própria trilha de visão estão desatualizados
nesse ponto específico — **vale confirmar com quem mexeu nisso por último antes de afirmar
isso na apresentação**, mas a evidência de código pesa para "já resolvido".

**Atualizado no fim de 17/09:** o que este trecho dava como faltando foi resolvido no mesmo
dia. O pacote baseline de `final-s20260917-v1` está no repositório
(`experimentos-privados/app-baseline-v1`,
[escopo e limites](integracao-publicacao-artefatos-2026-09-17.md)) e **vídeo com sinais reais
já validou o classificador de ponta a ponta no app**: os seis clipes da sinalizante 08 do
MINDS viram a glosa certa pelo caminho de produção no emulador
([rodada de 17/09](integracao-video-minds-e-calibracao-2026-09-17.md)). Ressalva que não pode
sumir do pitch: **esses clipes estão no treino do baseline**, então isso mede a integração,
não generalização. O que continua faltando é hardware (óculos) e avaliação com pessoas que o
modelo nunca viu.

### 1.8 Por que 20 sinais, e por que eles não são "o produto"

O vocabulário de avaliação é o dos **20 sinais do MINDS-Libras** (`acontecer amarelo
banheiro barulho espelho filho maca medo ruim sapo aluno america aproveitar bala banco cinco
conhecer esquina vacina vontade`). Até 2026-09-08 eram só 10 (os que existem nas duas bases
públicas ao mesmo tempo); o treino passou a usar o MINDS como fonte única, porque misturar
bases sem cuidado **não somou pessoas, somou um "degrau" de condição de gravação** entre
bases — achado que custou ~15 pontos na PoC de DTW.

**Isso não é o vocabulário do produto.** É um banco de provas. O vocabulário de atendimento
real (ex.: `marcar`, `atendimento`, `senha`) está proposto em 3 camadas em
`docs/vocabulario-mvp-proposta.md`, e ~9 termos institucionais não existem em nenhuma base
pública com mais de uma pessoa — só coleta própria resolve isso.

### 1.9 Os dados

| Conjunto | Clipes | Classes | Pessoas | Papel |
|---|---|---|---|---|
| **MINDS-Libras** | 800 | 20 sinais | 8 | **treino + avaliação LOSO** |
| V-LIBRASIL (auditada) | 4.025 | 1.349 palavras | 3 (sempre os mesmos) | pré-treino |
| MALTA-LIBRAS | 6.353 | 5.958 rótulos | 8 (uma delas é 90% dos clipes) | pré-treino — **ainda não habilitado no código** |
| WLASL100 (ASL) | 1.013 | 100 | 64 | pré-treino — **ainda não habilitado no código** |
| V-LIBRASIL (curada) | 30 | 10 sinais | 3 | clipes reservados (diagnóstico, não teste de domínio) |

Vídeos **não são versionados** (~48 GB; V-LIBRASIL é CC BY-NC-ND, que não autoriza
redistribuição). O que fica no git é a receita de download. Convenção de nome, fonte de
verdade do pipeline: `pessoaXX_sinal-YYY_repNN.npy`, com prefixo de origem (`M`=MINDS,
`V`=V-LIBRASIL, `W`=WLASL, `T`=MALTA) para pessoas de bases diferentes nunca colidirem.

### 1.10 Armadilhas já pagas (não repetir)

1. **Vazamento silencioso entre corpora**: comparar por nome de arquivo deixou 9 de 30
   clipes "reservados" vazarem pro pré-treino, porque o mesmo vídeo tem rótulos diferentes
   em bases diferentes. A correção usa a origem no zip, não o nome do arquivo.
2. **Orçamento de treino ≠ arquitetura**: o ST-GCN "perdeu" por 49 pontos numa comparação
   inicial porque recebeu hiperparâmetros de *fine-tuning* (~270 atualizações) pensados pra
   uma rede pré-treinada em ImageNet. Com orçamento do zero (~2.160 atualizações), recuperou
   29 pontos.
3. `pkill -f <padrão>` casa com o próprio comando que o executa e mata o shell chamador —
   aconteceu 4 vezes, uma delas matou o orquestrador de um experimento noturno e custou ~7h
   de máquina ociosa.
4. Testes estocásticos de uma rodada só viram asserção falsa (passava 100% em CPU, falhava
   41,7% em GPU com o mesmo código) — resolvido com média de 3 rodadas.

---

## 2. `contextualization-model/` — glosa → português

### 2.1 Por que existe

O app reconhece **um sinal por vez** e acumula uma lista de glosas. Isso não é português —
Libras tem ordem e estrutura próprias:

```
glosas: [eu, não, querer, vacina]   ->  "eu não quero tomar a vacina"
glosas: [onde, banheiro]            ->  "onde fica o banheiro?"
```

Antes desta trilha, o app falava o glossário cru (`joinToString(" ")`).

### 2.2 Arquitetura do modelo

Base: **ptt5-small** (`unicamp-dl/ptt5-small-portuguese-vocab`), um T5 pré-treinado em
português, 60,5M de parâmetros no original.

**Poda de vocabulário** (`modelo/podar.py`) — **NÃO é pruning de pesos por magnitude**. É
truncar a **tabela de embeddings**: descartar as linhas dos tokens que o domínio não usa. As
camadas de encoder/decoder (onde mora o português pré-treinado) não são tocadas.

- Roda **antes** do fine-tuning, para que toda rodada de treino seja mais barata e o modelo
  avaliado seja exatamente o que embarca.
- Mantém: (1) tudo que o corpus e o léxico realmente usam; (2) uma **margem de peças curtas**
  (≤2 caracteres, escritas só no alfabeto PT) — sem isso, qualquer palavra fora do corpus
  vira impossível de gerar por composição de subpalavras; (3) descarta os sentinelas de
  span-corruption (`<extra_id_N>`), que só servem ao pré-treino genérico.
- **Resultado: vocabulário 32.128 → 1.987 tokens (6%), parâmetros 60,5M → 45,1M.**
- Gera 4 artefatos-contrato com o app: `modelo_podado/` (checkpoint fatiado),
  `remap.json` (id antigo → novo, com o hash do corpus embutido — se o corpus mudar depois da
  poda, `treinar.py` recusa rodar), `glosa_ids.json` (glosa → ids de token) e
  `destokenizar.json` (reconstrução de texto sem precisar de SentencePiece em runtime no app).

**Fine-tuning** (`modelo/treinar.py`): teacher forcing padrão HuggingFace (labels deslocados
1 posição viram `decoder_input_ids`). LR = **1e-4** (não 1e-3, que é LR de treino do zero —
com 1e-3 o modelo esqueceria o pré-treino, que é exatamente o que se está pagando para
manter). Split de treino/validação **por `seq_id`**, não por par — paráfrases da mesma
sequência de glosas nunca cruzam treino/validação, senão o número de validação mediria
memorização de frase, não generalização.

### 2.3 O corpus é sintético — e isso importa mais do que qualquer número de F1

`corpus/gerar.py` monta `pares.jsonl` a partir de duas fontes:
- `corpus/sequencias.yaml`: **285 sequências de glosas** enumeradas à mão sobre o vocabulário
  fechado.
- `corpus/parafrases.yaml`: variações de superfície em português, **escritas por um LLM**
  (`claude-opus-5`, sessão de 2026-09-11) a partir dessas sequências.

Resultado: **2.202 pares** (`corpus/pares.manifest.json`), 373 com negação, 765 dependendo de
glosas de "camada 3-proposta" (ver §2.5).

**Isto não é Libras observada.** É um mapeamento que o time (com ajuda de um LLM) inventou.
O corpus é versionado como artefato — **não é regerado a cada treino** — porque comparar
rodadas de treino exige que o corpus seja o mesmo.

Validação embutida no gerador: toda glosa da sequência precisa aparecer na frase em alguma
forma do léxico (`corpus/gerar.py::cobre`) — é a **mesma checagem** que a guarda do app faz
em produção. Rodar `python corpus/gerar.py --so-validar` é, na prática, um teste do léxico:
uma forma faltando ali vira fallback desnecessário depois.

### 2.4 Léxico de glosas (`lexico/lexico-glosas.json`)

41 glosas, 250+ formas de superfície. Contrato compartilhado por três consumidores: o
`TemplateGlossContextualizer` (baseline), a guarda `GuardedGlossContextualizer.cobreTodoConteudo`
(cobertura) e a métrica de *content-word recall*. **Marcado explicitamente como rascunho**,
pendente de validação com a Associação de Surdos de Goiânia.

**7 das 41 glosas não existem em base pública verificada**: `eu`, `você`, `querer`,
`precisar`, `onde`, `quanto`, `quando` — marcadas `camada: 3-proposta`. Boa parte dos pares
do corpus depende delas. `--sem-c3` treina/avalia sem elas. **Consequência prática séria**:
todo corpus que usa essas 7 glosas treina um mapeamento que o classificador de visão **ainda
não consegue alimentar** (elas não vieram do cruzamento com as bases públicas que produziu as
outras camadas).

### 2.5 Decodificação — por que é "restrita" e não beam search padrão

`modelo/decodificacao.py` implementa um **laço guloso próprio** (referência de porte para
Kotlin, já que o app roda o mesmo laço em `TfliteGlossContextualizer.kt`), com duas regras:

- **(a)** `</s>` (fim de sequência) fica **proibido** enquanto houver alguma glosa ainda não
  coberta pela saída.
- **(b)** se a cobertura não avança por `PACIENCIA=6` passos, o decoder **emite à força** a
  sequência de peças inteira da forma mais curta que ainda falta — em vez de empurrar
  token-a-token e torcer.

**Por que não simplesmente beam search / `force_words_ids`:** duas tentativas foram tentadas
e descartadas, e o motivo está documentado no código — vale saber caso alguém pergunte "por
que não usaram X":
1. Bônus na primeira peça das formas faltantes com `</s>` bloqueado: melhorou negação e
   fallback, mas **degenerou** — `[obrigado, ajuda]` virou "a a a a a…" (a primeira peça de
   "ajuda" é um token comum, e empurrá-lo com EOS proibido virou laço).
2. `force_words_ids` do HuggingFace: é a restrição certa, mas saiu do *core* na versão 5.x
   dos `transformers` (virou `custom_generate`, exige `trust_remote_code`) — não valia a
   dependência.

**Por que essa decodificação foi necessária:** o modelo v1 falhava por **omissão** — com dois
substantivos de conteúdo, emitia um e abandonava o outro (6 das 7 falhas analisadas). Não é
falta de capacidade, é a política de parada do decoder escolhendo `</s>` cedo demais.

### 2.6 A guarda: cobertura sozinha não basta — descoberta central do projeto

Achado registrado em `modelo/fluencia.py` (2026-09-11): com decodificação restrita, o modelo
v1 produz **lixo em 54% das combinações não vistas**, e esse lixo **passa** na guarda que só
verifica cobertura — porque a decodificação restrita **força** as glosas a aparecerem. Exemplo
real, citado no código:

```
[onde, você]  ->  'ondeJ dele dele dele dele dele você'
```

Cobre `onde` e `você`. Passaria pela checagem de cobertura sozinha. Seria falado.

**A guarda tem, portanto, duas metades:**
1. **Cobertura** — toda glosa aparece em alguma forma do léxico (`modelo/hibrido.py`).
2. **Fluência** (`modelo/fluencia.py::parece_degenerado`) — detecta: (a) saída vazia; (b)
   caractere suspeito (maiúscula no meio de palavra, dígito, símbolo fora do alfabeto PT —
   cuidado documentado: a 1ª versão marcava toda maiúscula e acusava 100% das saídas do
   template, que capitaliza a inicial; corrigido para só contar maiúscula **no meio**); (c)
   repetição (mesma palavra 3× seguidas, ou mesmo bigrama repetido); (d) comprimento
   excessivo (>max(8, n_glosas×5) palavras — sinal de EOS bloqueado e o decoder "enrolando").

Há ainda uma terceira checagem, medida mas **não usada como gate hoje** —
`modelo/invencao.py::inventadas`: toda palavra de conteúdo da saída precisa ser explicável
por alguma glosa (via as formas do léxico) ou ser funcional (artigo, preposição, verbo leve).
O que sobra é "invenção" — pior que soar robótico, porque a pessoa não disse aquilo. No v3,
0,119 das saídas têm conteúdo inventado (alvo é 0, "a guarda não pega tudo" — nota
explícita no relatório).

### 2.7 O template — o piso, e por que ele ainda ganha em F1

`modelo/template.py::contextualizar` é um sistema de regras determinístico: dicionários de
conjugação por pessoa (`VERBOS`, `ESTADOS`), regência verbal (`preciso DE`, não "preciso a"),
contrações (`de + o = do`), saudações compostas (`oi + manhã = "bom dia"`, não "olá de
manhã"), interrogativos. Existe por dois motivos: é o **portão de aceite** (um modelo que não
bate isso no conjunto humano não entraria no APK — mas o conjunto humano ainda não existe) e é
o **fallback permanente** em produção.

### 2.8 Os números — leia a ressalva antes de repetir em apresentação

| Métrica | Template (portão) | Modelo v1 | Modelo v2 | Modelo v3 | Alvo |
|---|---|---|---|---|---|
| Acerto de negação | 1,000 | 1,000 | 1,000 | 1,000 | 1,000, inegociável |
| Content-word recall | 0,976 / 0,953 / 0,971 | 1,000 | 1,000 | 1,000 | ≥ 0,980 |
| Conteúdo inventado | — | — | — | 0,048 (template) / 0,119 (modelo) | 0 |
| Taxa de fallback | 0,103 / 0,158 / 0,071 | 0,000 | 0,000 | 0,000 | medir em campo |
| Exact match (multi-ref) | 0,414 / 0,395 / 0,429 | 0,414 | 0,395 | 0,429 | > template |
| **F1 de palavras** | **0,884 / 0,877 / 0,871** | **0,856** | **0,824** | **0,836** | > template |

Corpus por rodada: v1 = 1.528 pares/191 seqs (20 épocas); v2 = 2.048 pares/256 seqs (30
épocas); v3 = 2.202 pares/285 seqs (30 épocas, o corpus final).

**Em nenhuma das três rodadas o modelo bateu o template em F1.** Por isso o app usa a cadeia
`modelo → guarda → template → passthrough`, com o template como **piso**: a guarda aceita o
modelo em **~95% das sessões**, e ele ganha justamente onde há relação gramatical entre
glosas — o exemplo citado em todo doc do projeto é `[banco, esquina]` → template "O banco a
esquina." (agramatical) vs modelo "o banco fica na esquina" (correto) — algo que uma tabela
de regras não produz sozinha.

**A análise certa não é "quem ganha na média"**, é a que `modelo/hibrido.py` calcula
explicitamente: (a) em que fração de sessões a guarda aceita o modelo? (b) *nessas* sessões,
ele é melhor que o template? Um modelo aceito em 15% das sessões não se paga mesmo sendo
ótimo nesses 15%; um modelo aceito em 70% e melhor ali se paga **mesmo perdendo na média
global** — porque a média global inclui sessões em que ele nem foi usado.

**Avisos que não podem faltar em qualquer apresentação desse número** (do próprio
`README.md` da trilha, verbatim):
1. **O corpus é sintético.** Escrito por LLM a partir de sequências também hipótese de LLM.
   Não é Libras observada. O portão real seria `avaliacao/conjunto_humano.jsonl`, que **ainda
   não existe**.
2. 7 das 41 glosas (camada 3-proposta) não existem em base pública — o classificador de
   visão ainda não consegue alimentar boa parte dos pares.
3. O portão de aceite é relativo: sem o conjunto humano, o que está no APK roda **sob
   guarda**, com template como fallback — e **o modelo vem desligado por padrão**
   (`MODELO_CONTEXTUALIZACAO_ATIVO = false` no app).

### 2.9 Exportação

`exportacao/para_tflite.py` exporta **duas assinaturas** — `encode` e `decode_step` — porque
um decoder autorregressivo não cabe numa única chamada de grafo estático. **Decisão de
desenho deliberada: sem KV-cache.** O decoder recebe o **prefixo inteiro** (T_DEC=24 fixo) a
cada passo e devolve logits de todas as posições; o app lê `logits[0, t]`. Isso é O(T²) em vez
de O(T) — aceitável porque a inferência acontece **uma vez por sessão** (não por token em
tempo real), e compra uma simplificação grande no porte para Kotlin (shapes estáticos, sem
estado entre chamadas). O motivo de não usar KV-cache: em `transformers` 5.x, o cache virou um
objeto `Cache` não traçável facilmente para export, e frágil entre versões.

`S_ENC=16` (glosas são curtas, 5 glosas ≈ 12 peças). Quantização padrão: **int8-dynamic**
(pesos int8, ativações float, sem dataset representativo necessário). O experimento em
produção é o **v2** (não o v3, apesar de v3 ser mais recente) — é o padrão do script de
export e do que os assets do app esperam. Tamanho final: **45 MiB** (o `.tflite` de entrega,
versionado no app, não aqui).

**O que entra no git:** o `.tflite` de entrega é versionado **no app** (decisão de
2026-09-13: modelos internos vão para o git), amarrado por
`modelo_contextualizacao.proveniencia.json` e checado por
`ModeloContextualizacaoProvenienciaTest`. Em `artefatos/` (dentro desta trilha) ele fica
**ignorado** — é saída de trabalho, e duas cópias no repo podem divergir.

---

## 3. `wake-word-model/` — "Libras Livre, iniciar/encerrar"

### 3.1 Por que existe

O app já tinha um motor de wake word funcionando via `SpeechRecognizerWakeWordDetector`
(`android.speech.SpeechRecognizer`), mas ele **depende de rede** na maioria dos aparelhos e
não é keyword-spotting de verdade. O motor local offline (`OpenWakeWordDetector.kt`, ONNX
Runtime) já estava implementado no app e só esperava os dois classificadores treinados — esta
pasta os treina.

### 3.2 As três rodadas de treino (2026-09-12)

| Rodada | O que mudou | Falso-positivo/h | Recall |
|---|---|---|---|
| 1 | pulou o pool de negativos ACAV100M (achou 17 GB inviável **sem medir**) | 103–280/h | — |
| 2 | mediu a banda real (~14 MB/s, ~20 min pro arquivo inteiro), baixou o ACAV100M | 1,48 / 0,00 | `encerrar` preso em ~0,40 em qualquer limiar (bimodal, não é questão de calibração) |
| 3 | + fala real em português (MLS, 600 clipes) + confusáveis manuscritos específicos de "encerrar" | **0,52 (iniciar) / 0,49 (encerrar)** | **0,69 / 0,58** (precisão 0,93/0,97) |

Duas decisões de engenharia que valem lembrar:
- **O pipeline oficial do openWakeWord não serve pt-BR "de fábrica"**: usa
  `piper-sample-generator` (VITS multi-falante), só disponível pronto em inglês, alemão,
  francês e holandês — verificado nos releases oficiais e no HuggingFace, sem checkpoint
  pt-BR publicado em lugar nenhum. Substituto usado: **6 vozes Piper pt-BR de comunidade**
  (mesmas do TTS do app) com timbre/velocidade variados por chamada. Menos diverso que um
  gerador multi-falante contínuo, mas é fala real em pt-BR.
- **Fala real em português (MLS) resolveu o recall bimodal só de `encerrar`, não de
  `iniciar`.** Testado nos dois; em `iniciar` o recall **piorou** (0,63→0,46) sem ganho
  compensador — a sobreposição semântica dos confusáveis novos ("fechar o atendimento"...)
  com `encerrar` é o que fazia diferença, e isso não existe para `iniciar`.

### 3.3 Estado — motor real pronto, mas não ativado

`OpenWakeWordDetector.kt` (motor real, ONNX) já carrega os `.onnx` treinados e passa em teste
instrumentado (`WakeWordModelosCarregamTest`). **Mas o motor ativo por padrão continua sendo
o `SpeechRecognizerWakeWordDetector`** (fallback) — falta validar recall e falso-positivo em
hardware real antes de trocar o padrão em `CameraViewModel`. Limiares já calibrados no código:
`THRESHOLD_INICIAR=0,3` / `THRESHOLD_ENCERRAR=0,4`.

**Ressalva de variância:** sem seed fixa em `sintetizar.py`/`train.py`, duas rodadas com a
mesma configuração já produziram recalls visivelmente diferentes. Tratar os números como
ordem de grandeza, não precisão de segunda casa decimal. E, como em todas as outras trilhas:
**nenhum teste com pessoa real, mic real dos óculos, ou ambiente de balcão** — só dataset
sintético.

---

## 4. `mobile-app-companion/` — o app Android

### 4.1 Base e stack

Deriva do sample oficial de *Camera Access* do Meta Wearables Device Access Toolkit (DAT),
que já resolve conectar aos óculos e consumir a câmera — reaproveitado em vez de reescrito,
porque a parte difícil (parear, manter a conexão, decodificar o stream) já vinha pronta e
testada pela Meta. 100% Kotlin + Jetpack Compose, MVVM com fluxo unidirecional (`StateFlow` +
Coroutines/`Flow`) — importa aqui porque o app tem muito estado assíncrono acontecendo ao
mesmo tempo (câmera, TTS, STT, avatar, wake word); um fluxo unidirecional é o que torna esse
tanto de estado depurável. Os óculos **não rodam o app** — são só câmera e microfone; o
celular é a borda que processa tudo, porque é onde há CPU/RAM de sobra pra rodar dois modelos
de IA **on-device e sem internet**, que é a premissa do produto (balcão sem depender de rede).

```
Óculos Ray-Ban Meta  --vídeo HEVC-->  celular  -->  voz
                     <--áudio A2DP/HFP-->
```

O ponto de plugue de tudo é **`CameraViewModel.handleVideoFrame()`**: cada frame dos óculos
passa por ali e segue simultaneamente para o decoder de preview, o gravador e o
`LandmarkPipeline`.

### 4.2 Pipeline completo, sentido surdo → ouvinte

```
frames HEVC (CameraViewModel.handleVideoFrame)
  -> LandmarkPipeline
      -> HevcDecoder DEDICADO -> ImageReader (YUV)   [preview segue no decoder original]
      -> android.media.Image -> MediaPipe Pose+Hands (LandmarkExtractor)
      -> LandmarkNormalizer (normaliza por ombros — 57 pontos x 2 canais x,y)
      -> HandGapImputer (preenche lacunas curtas de mão, no segmento em curso)
      -> SignBoundaryDetector (SINALIZANDO/PARADO -> onde cada sinal começa/termina)
      -> a cada fronteira: SignClassifier.classify() -> onRecognized(glosa)
      -> DialogOrchestrator acumula as glosas da sessão
      -> GlossContextualizer: glosas -> frase em português
      -> Speaker fala a frase ao "encerrar"
```

E o sentido inverso, ouvinte → surdo:

```
atendente responde por voz (mic dos óculos, HFP)
  -> VoskSttEngine transcreve
  -> VLibrasGlosaTranslator: português -> glosa VLibras (rede, com cache em disco)
  -> AvatarPlayer: WebView -> player VLibras -> Unity/WebGL -> avatar sinaliza
```

O `LandmarkPipeline` tem um **decoder HEVC dedicado**, separado do decoder do preview —
decodificar pra inferência e pra tela no mesmo decoder faria as duas coisas disputarem buffer
e prioridade, arriscando travar o preview quando a IA está ocupada (ou vice-versa).

Organizado em 5 subpacotes dentro de `libras/`, **por domínio**: `reconhecimento/`,
`dialogo/`, `contextualizacao/`, `avatar/`, `audio/`.

### 4.3 A máquina de estados da sessão (`dialogo/DialogState.kt`)

9 estados, disparados por "Libras Livre, iniciar/encerrar" (voz) ou botões (fallback):

```
① AGUARDANDO_SINAL -> ①.5 PEDINDO_CONSENTIMENTO -> ② CAPTURANDO_SINAIS
   -> ②.5 CONFIRMANDO_RECONHECIMENTO -> ③ FALANDO -> ④ AGUARDANDO_RESPOSTA
   -> ⑤ ESCUTANDO_ATENDENTE -> ⑥ TRANSCREVENDO -> ⑦ GERANDO_AVATAR
```

Os dois estados com `.5` são **acréscimos posteriores ao desenho original**, ambos de
feedback da banca em 2026-09-15, e ambos reaproveitam o `playAvatar()` do ⑦ (sem tela nova):

- **①.5 PEDINDO_CONSENTIMENTO**: antes de ligar a câmera, mostra pra pessoa surda o que o
  sistema faz; o **atendente** decide por ela ("Aceitar" liga a câmera, "Recusar" volta ao ①
  com aviso de bilhete/intérprete). Requisito de LGPD
  (`docs/libras-livre-arquitetura.md` §7). **O texto do consentimento é placeholder**, não
  revisado juridicamente nem pela comunidade surda.
- **②.5 CONFIRMANDO_RECONHECIMENTO**: depois de decidir a frase, mostra pra pessoa surda o
  que foi entendido **antes** de falar pro atendente — "Confirmar" segue, "Corrigir" descarta
  e reabre a captura.

Uma sessão ociosa se encerra sozinha após 1 minuto. **Modo economia de bateria**: reage a
`DeviceSessionError.BATTERY_CRITICAL`/`StreamError.BATTERY_LOW` do SDK real (confirmado por
inspeção do `.aar`, não suposição) — bloqueia "iniciar"/"corrigir" pelo mesmo caminho de
qualquer falha de câmera. **Nunca desliga sozinho**, porque o SDK não expõe "bateria
recuperada".

### 4.4 Classificação de sinal — os três modos, carregamento verificado

`reconhecimento/CarregamentoClassificador.kt` define uma política pura e testável, com um
invariante forte: **erro ao carregar o modelo real NUNCA cai silenciosamente no simulado**.

```kotlin
enum class ModoClassificador { SIMULADO, REAL_EXPERIMENTAL, RECUSADO }
```

- **SIMULADO**: quando não há pedido explícito de modelo privado E nenhum modelo está nos
  assets. Usa `PlaceholderSignClassifier` (saída sintética `[placeholder:Nf]`) — é o modo
  ativo por padrão no build normal, já que **nenhum `.tflite` de sinal está versionado**.
- **REAL_EXPERIMENTAL**: exige seleção explícita no build (`librasLivre.classificadorPrivado`)
  e passa por uma cadeia de verificação em duas camadas: (1) hash SHA-256 do arquivo de
  identidade contra o valor fixado no build; (2) o JSON de identidade precisa confirmar
  `schema==1`, `experimental==true`, `aprovado_entrega==false`; (3) hash do modelo e do
  sidecar contra os valores declarados na identidade; (4) o próprio sidecar precisa
  concordar com a origem declarada; (5) **a calibração precisa estar explicitamente ausente**
  (`"ausente_nao_calibrado"`) — o carregador **recusa** um pacote que tente trazer limiar
  pré-calibrado, porque a integração privada não valida isso.
- **RECUSADO**: qualquer falha nas checagens acima (inclusive `LinkageError` — biblioteca
  nativa/ABI ausente) vira `ClassificadorRecusado`, nunca um crash nem um fallback silencioso
  para SIMULADO.

Essa infraestrutura está **pronta e testada** (`mobile-app-companion/README.md` §4, §6), e
desde 17/09 o pacote baseline está no repositório e foi exercitado com vídeo real do MINDS
no emulador ([rodada de 17/09](integracao-video-minds-e-calibracao-2026-09-17.md)). O que
falta é hardware e generalização, não a engenharia de carregamento.

### 4.5 `SignBoundaryDetector` — fronteiras entre sinais, em detalhe

Máquina causal de 2 estados (`SINALIZANDO`/`PARADO`, só olha o passado), consumindo os 57
pontos já normalizados. Como decide:

- **Velocidade por janela, não frame a frame**: compara o frame atual com o frame mais antigo
  guardado que já tenha pelo menos `janelaVelocidadeMs` de idade — evita trocar de estado por
  causa de uma variação momentânea de fps do stream.
- **Como agrega os 21 pontos de cada mão**: média por mão (só entre frames em que a mão está
  presente nos dois lados da comparação) e depois o **máximo** entre mão esquerda, mão direita
  e pulsos — não a soma, para não acumular tremor de várias partes ao mesmo tempo num falso
  positivo de movimento.
- **Suavização por média móvel exponencial (EMA)**, com **dois limiares** — `limiarEntrada`
  (mais alto, pra começar a contar como sinal) e `limiarSaida` (mais baixo, pra parar) —
  histerese clássica, evita oscilar entre estados perto do limiar.
- **Um segmento fecha por 4 motivos**: `PAUSA` (pausa acumulada ≥ `pausaMs`), `OCLUSAO` (as
  duas mãos somem ao mesmo tempo por ≥ `tetoOclusaoMs`, hoje 900 ms), `DURACAO_MAXIMA` (limite
  de segurança) ou `FIM_DA_SESSAO`.
- **Bug corrigido, vale saber se aparecer de novo em outra forma**: a duração mínima do
  movimento é medida na velocidade **bruta**, não na suavizada pela EMA — medir na suavizada
  fazia um espasmo de 4 frames parecer ~290 ms de movimento (a janela de velocidade + a EMA
  "esticam" o sinal no tempo).

**Parâmetros não calibrados com dado real** — os valores em uso (`ParametrosSegmentacao`) são
"pontos de partida sugeridos" de `docs/sign-boundary-detector-plano.md` §7, marcados como tal
no próprio código, porque calibrar de verdade exige device físico + gravações reais, que não
existiam quando isso foi implementado. Quando houver gravação com os óculos,
`scripts/calibracao_fronteiras.py` lê os CSVs do gravador de sessão (§6 abaixo) e calcula: piso
de ruído (p95 da velocidade com as mãos paradas), limiares sugeridos (saída ≈ 1,5–2× o piso;
entrada ≈ 2,5–3×) e o teto de oclusão, a partir de gravações rotuladas.

### 4.6 Contextualização no app

A cadeia `modelo (.tflite) → guarda → template → passthrough` mora em
`contextualizacao/Contextualizadores.kt` (a ordem é decisão de produto, centralizada numa
função) — o modelo vai primeiro porque, quando a guarda aceita, ele produz frase mais natural
que o template (§2.7); o template é o **piso** porque nunca falha (é regra determinística);
o passthrough (glosas cruas) só entra se nem o léxico existir, pra nunca deixar a pessoa surda
sem nenhuma saída. `TfliteGlossContextualizer.kt` roda o mesmo laço guloso de duas regras descrito em
§2.5 (é o "porte para Kotlin" da referência Python). `Guardas.kt::GuardedGlossContextualizer`
replica cobertura + fluência. **Se o asset do modelo não estiver presente, tudo continua
funcionando com o template, sem erro — só um log.** O modelo vem **desligado por padrão**
(`MODELO_CONTEXTUALIZACAO_ATIVO = false`), com toggle de debug pra ligar.

### 4.7 TTS e STT, em detalhe

| Função | Motor ativo | Fallback |
|---|---|---|
| Síntese de voz (fala pro atendente) | `PiperSherpaOnnxTtsEngine` — Piper pt-BR int8, via `.aar` sherpa-onnx 1.13.8 | `AndroidTextToSpeechEngine` |
| Transcrição (resposta do atendente) | `VoskSttEngine` — `vosk-model-small-pt-0.3`, sobre PCM cru | `AndroidSpeechRecognizerSttEngine` |
| Wake word | `SpeechRecognizerWakeWordDetector` (depende de rede) | botões Iniciar/Encerrar |

- **TTS**: o `.aar` do sherpa-onnx é buildado com `static-link-onnxruntime` de propósito — pra
  não colidir com o ONNX Runtime que o `OpenWakeWordDetector` também carrega (dois runtimes
  ONNX no mesmo processo, cada um com seu próprio symbol table). Usa as mesmas 6 vozes Piper
  pt-BR de comunidade que treinaram o wake word (`edresson`, `faber`, `cadu`, `jeff`, `miro`,
  `dii`). O primeiro trecho de áudio sintetizado dispara `onInicioAudio` — é o que marca a
  etapa "frase → primeiro áudio" no painel de latência (§6).
- **STT**: `PcmMicCapture.kt` captura PCM cru do microfone ativo (dos óculos via HFP, ou do
  celular sem Bluetooth) e entrega ao Vosk, que roda 100% on-device — sem depender de rede,
  ao contrário do `SpeechRecognizer` usado como fallback e como motor de wake word.
- **Cadeia de reserva sem duplicar memória** (`TtsEmCadeia`): o motor Android só é
  instanciado se o Piper falhar — não fica um segundo motor de voz carregado "por garantia".
  Mesma lógica pro wake word: trocar de motor libera o anterior antes de criar o novo.

### 4.8 Avatar VLibras, em detalhe

Sentido ouvinte → surdo (⑦). `VLibrasGlosaTranslator` chama o endpoint público do VLibras
(português → glosa), com `GlosaCache` em TSV local pra não repetir a mesma tradução em
atendimentos parecidos. `AvatarPlayer.kt` é dono exclusivo do ciclo de vida da `WebView`, que
roda o player oficial `vlibras-player-webjs` — Unity compilado pra WebAssembly, desenhando por
**WebGL 2.0 dentro da WebView** (nada no app Kotlin desenha; só oferece o contexto).

**Três decisões vindas de medição, não palpite** (sonda de 2026-09-12,
`docs/vlibras-webview-plano.md`):
1. **Criar não é mostrar.** O Unity leva **6–9 s** pra ficar pronto. `prepare()` roda escondido
   assim que a sessão de sinais abre (durante ②③④⑤⑥); o estado ⑦ só **torna visível** o que já
   estava carregando — resposta quase instantânea, em vez de esperar 6–9 s na hora.
2. **`onRenderProcessGone` é obrigatório.** O Unity vive no **processo do renderer da WebView**
   (~307 MB medidos, contra 3–5 MB do heap Java do app) — sem esse tratamento, o Android mata
   esse processo sob pressão de memória e derruba o app inteiro junto.
3. **O ciclo é por atendimento, não por turno.** Destruir e recriar a cada resposta faria a
   pessoa surda esperar 6–9 s de novo a cada turno da conversa.

Outros detalhes que evitam armadilhas comuns de WebView: a base URL é virtual
(`https://appassets.androidplatform.net/assets/vlibras/...`) via `WebViewAssetLoader` —
**nunca `file://`**, que quebra o carregador do WASM; `CARGA_TIMEOUT_MS = 20.000` (o dobro do
pior caso medido) existe porque o player só emite evento de erro na *tradução*, não na
*carga* — sem esse relógio, um Unity travado deixaria o estado em "carregando" pra sempre; e
se `index.html`/`vlibras.js` faltarem ou não houver WebGL (caso do emulador), o app cai
explicitamente na legenda em vez de tela branca.

**O avatar é a única peça do fluxo que depende de rede** (tradução PT→glosa e busca de cada
sinal no dicionário VLibras) — e **nunca foi medido em GPU ARM real** (só emulador, com
passthrough pra GPU de desktop, que não representa o hardware da demo).

### 4.9 Áudio Bluetooth: A2DP e HFP são mutuamente exclusivos

O TTS sai por **A2DP** (roteamento padrão do Android, sem código específico aqui). Só a
**escuta** da resposta do atendente (microfone dos óculos) precisa de **HFP/SCO** — e os dois
perfis não convivem no mesmo par Bluetooth ao mesmo tempo: ligar HFP derruba a saída de áudio
pra 8kHz mono durante toda a sessão, então nunca se pode pedir a troca com o TTS ainda falando.

`AudioSessionManager.kt` é dono exclusivo dessa troca: fala pelo A2DP no estado ③; no estado ⑤
(escutar o atendente), `acquireListening()` chama `AudioManager.setCommunicationDevice`
(API 31+) e **espera confirmação assíncrona** via `addOnCommunicationDeviceChangedListener`,
com um **timeout de segurança de 3.000 ms** (`SWITCH_TIMEOUT_MS`) — pra não travar a sessão se
os óculos não tiverem HFP pareado naquele momento. Sem Bluetooth nenhum disponível, o app
escuta pelo microfone do próprio celular (é o que acontece nos testes em emulador/host).
Regra adicional: a wake word **não escuta durante o estado ⑤**, porque disputaria o mesmo
microfone com o Vosk.

### 4.10 Como testar sem óculos — `MockDeviceKit`

Menu de debug (botão flutuante em builds DEBUG) → `MockDeviceKit` → parear um Ray-Ban Meta
simulado → fonte de câmera "**Video file**" apontando pra um `.mp4` de Libras. O pipeline
inteiro roda no emulador (a partir do MediaPipe 0.10.35 + conversão YUV→ARGB, achado e
corrigido em 2026-09-13 — antes disso o MediaPipe recusava o frame e nenhum landmark saía).

Outras operações simuladas no mesmo menu: **tap** (toque na haste, pausa/retoma o stream),
**don/doff** (pôr/tirar óculos), **fold/unfold**, **power off**.

**O que o mock NÃO testa** (só hardware real mostra): áudio Bluetooth real (A2DP/HFP), ponto
de vista da câmera na cabeça de alguém, rotação real do vídeo, compressão/fps/descarte do
stream real, toque real na haste, bateria e temperatura dos óculos, GPU/WebGL/memória de
celular ARM real, rede do local da apresentação.

**Com o `PlaceholderSignClassifier` ativo** (modo padrão sem checkpoint real), o "sinal
reconhecido" é sempre texto sintético — serve pra confirmar que o ciclo fronteira → classificar
→ acumular → contextualizar → falar **funciona mecanicamente**, não que o reconhecimento está
correto. Isso só o `.tflite` real prova.

---

## 5. Estado geral — o que está pronto e o que não está

Tabela consolidada (fonte: `README.md` raiz + `mobile-app-companion/README.md` §6, ambos
atualizados em 2026-09-16):

| Componente | Estado |
|---|---|
| App base: conexão com óculos, câmera, gravação | pronto (herdado do sample Meta) |
| Extração de landmarks on-device (Pose+Hands, 57 pontos) | pronto |
| Normalização e imputação, paridade testada contra Python | pronto |
| Detecção de fronteiras entre sinais | implementada, **parâmetros não calibrados** |
| Classificação de sinal no app | pronta e exercitada com **vídeo real** no emulador (6 clipes do MINDS, glosa certa nos 6, pacote baseline no repositório, 3 modos com hash/identidade); **os clipes estão no treino do baseline** — não mede generalização, e o modelo segue experimental |
| Contextualização glosa → português | pronta, integrada, versionada; **modelo desligado por padrão** (não bateu template em F1 sintético) |
| Fala (Piper/sherpa-onnx) e transcrição (Vosk) | prontas |
| Avatar em Libras (VLibras/WebView) | implementado; **depende de rede**; não medido em ARM |
| Wake word offline (openWakeWord) | classificadores treinados e versionados; **motor ativo ainda é o fallback** `SpeechRecognizer` |
| Confirmação do reconhecimento (②.5) + modo economia de bateria | prontos e testados — feedback da banca 2026-09-15 |
| Consentimento por atendimento (①.5) | pronto e testado — LGPD; **texto placeholder, não revisado juridicamente** |
| Coleta própria no cenário de balcão | pendente |
| Validação de vocabulário com consultor de Libras | pendente |
| Export ST-GCN `.tflite` | código presente (`--arquitetura gcn`), checklist do app marca como feito — **mas README/CONTEXTO da trilha de visão ainda dizem que falta** (ver §1.7) |

**O maior bloqueio real, segundo o próprio README raiz**: não é a engenharia de integração —
é o **checkpoint treinado chegar a mais gente do time** e ser validado com vídeo de sinais
reais.

---

## 6. Checklist operacional pra amanhã (extraído de `docs/prontidao-demo/`)

Esse material foi escrito literalmente para um dia de demo — reaproveitável quase 1:1.

### Papéis (11.6)

| Papel | Faz |
|---|---|
| Quem sinaliza | as sequências do roteiro, com repouso natural entre sinais |
| Atendente (usa os óculos) | responde com as falas do roteiro; usa botão principal / tecla de volume quando o automático não avança |
| Narrador | explica à banca o que acontece nas esperas (stream subindo, síntese, avatar); conduz a recuperação quando algo falha — **"uma espera narrada vira explicação da arquitetura, não silêncio"** |

### Véspera (D-1)

- [ ] APK **debug** (não release) gerado numa máquina com todos os assets — é o único que tem
  menu de debug e configurações de demo.
- [ ] Instalado no aparelho da demo com antecedência; cópia no notebook — pra não depender do
  wifi do local na hora e ter reserva se o aparelho principal falhar.
- [ ] Primeira execução completa: aquecimento em ✓ (a cópia do Vosk e do Piper, ~70 MB,
  acontece aqui) — assim essa cópia não acontece ao vivo, no wifi do evento.
- [ ] Atualizações automáticas **desligadas**: app Meta AI, firmware dos óculos, Android e
  **Android System WebView** (uma atualização dele pode mudar o WebGL do avatar e quebrar a
  demo sem aviso).
- [ ] Developer Mode ativo no app Meta AI; app registrado; permissões de câmera/microfone já
  concedidas — sem isso os óculos não conectam, e pedir permissão ao vivo trava o fluxo.
- [ ] Óculos com carga cheia + estojo carregador por perto; celular carregado + power bank;
  economia de bateria **desligada** — ela reduz desempenho de CPU (§7.2) bem na hora que mais
  precisa.
- [ ] 4G próprio testado (roteador ou hotspot de outro celular) — wifi de evento é instável, e
  o avatar (única peça online) depende de rede pra não cair pra legenda.
- [ ] Ensaio geral de 15–20 min seguidos, painel de métricas ligado — é o tempo mínimo pra
  temperatura e latência chegarem no regime estável; medir logo após configurar dá número
  otimista (§7.3).
- [ ] Vídeo de plano B gravado (`adb shell screenrecord` da tela + câmera externa mostrando
  quem sinaliza e o atendente) — é o último nível da tabela de recuperação, se tudo mais falhar.

### No dia

- [ ] Celular em "não perturbe"; "Hey Meta" e leitura de notificações nos óculos
  **desligados** (hipótese de interferência no áudio).
- [ ] Cache do avatar aquecido **no local**: rodar as respostas do roteiro na rede do evento
  antes de subir ao palco — a tradução PT→glosa depende dessa rede; aquecer evita a espera de
  6–9 s (ou falha) na frente da banca.
- [ ] Marca no chão na distância certa (medida em teste prévio com os óculos); fundo sem
  pessoas; luz frontal sobre quem sinaliza — a normalização por ombros (§1.2) espera um
  enquadramento parecido com o do treino; longe/perto/mal iluminado degrada a classificação
  em silêncio, sem erro visível.
- [ ] Quem usa os óculos não toca na haste e mantém as mãos fora do campo da câmera — toque na
  haste pausa/retoma o stream sem aviso; mão de fora entra como landmark de "sinal" indevido.
- [ ] App aberto antes de subir ao palco, aquecimento em ✓, sessão já iniciada — o aquecimento
  carrega MediaPipe/classificador/Vosk/Piper/avatar (§6.4); sem isso, esse custo é pago ao
  vivo, no primeiro turno, na frente da banca.

### Tabela de recuperação em palco (11.7) — decore isto

| Se falhar… | O app faz | A equipe faz |
|---|---|---|
| Wake word | nada | botão principal ou tecla de volume |
| Sinal não reconhecido | avisa o atendente pra pedir repetição; na 3ª, "tente outro meio" | pede pra repetir com pausa entre sinais |
| Contextualização lenta/barrada | cai no template | nada — é invisível pra plateia |
| Câmera não sobe / pausada | mensagem com a causa | resolve a causa; em último caso, vídeo gravado no MockDeviceKit (**ensaiar essa troca antes**) |
| Avatar ou rede falha | legenda; botão "Pular" | narrador explica: "o avatar usa a rede do VLibras; offline é o próximo passo" |
| Voz do Piper falha | voz de reserva do Android | nada, só muda o timbre |
| Celular muito quente | aviso na tela | pausa curta narrada; tirar da capinha/luz direta |
| Tudo mais falha | — | vídeo de plano B |

### Metas de latência ponta a ponta (referência de `guia-de-testes-mock-e-oculos.md`)

| Etapa | Meta |
|---|---|
| "iniciar" → pode sinalizar | < 3 s |
| classificação de um sinal | < 100 ms |
| glosas → frase | < 1,5 s |
| frase → primeiro áudio | < 1 s |
| fim da fala → texto (Vosk) | < 1,5 s |
| texto → avatar sinalizando | < 3 s (cache aquecido) |

### Como coletar as métricas na prática

O app já tem instrumentação pronta pra isso — não precisa adivinhar nada no palco, só saber
onde olhar antes do ensaio.

**1. Painel de métricas (overlay na tela)** — liga em Configurações de demo → "Painel de
métricas". Mostra, amostrado uma vez por segundo: fps recebido dos óculos, fps decodificado,
fps processado pelo MediaPipe e % de frames descartados, quantas vezes a fila do decodificador
encheu, folga e status térmico (`PowerManager.getThermalHeadroom`/`currentThermalStatus`),
bateria do celular (`BatteryManager`), RAM do app (`Debug.getPss`) e a tabela de latência do
último turno. É o que dá pra olhar **durante** o ensaio, sem precisar de notebook do lado.

**2. Gravador de sessão (CSV)** — liga em Configurações de demo → "Gravador de sessão". Grava
um arquivo por sessão em `getExternalFilesDir(null)/sessoes/<AAAAMMDD-HHMMSS>.csv` (uma linha
por frame + linhas de evento: início/fim de segmento, classificação com confiança/margem,
decisão do avaliador, marcas de latência). Puxar pro computador sem root:

```bash
adb pull /sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes .
```

Abre direto em pandas/Excel — é o dado bruto pra qualquer análise pós-ensaio (inclusive pra
calibrar o `SignBoundaryDetector`, §4.5, com `scripts/calibracao_fronteiras.py`).

**3. Latência por etapa, agregada (`scripts/latencia_por_etapa.py`)** — lê o CSV do gravador ou
o logcat e imprime, por etapa, número de medidas, mediana, p90 e máximo (ms), marcando com `←`
qualquer etapa cuja mediana passa da meta:

```bash
python scripts/latencia_por_etapa.py sessoes/20260917-101500.csv
# ou, direto do aparelho, sem gravar CSV:
adb logcat -d -s LibrasLatencia | python scripts/latencia_por_etapa.py -
```

⚠️ A tag do logcat é **`LibrasLatencia`**, sem `:` — `adb logcat -s Libras:Latencia` não
funciona porque o filtro do logcat trata `:` como separador de prioridade.

**4. RAM por estado (`dumpsys meminfo`)** — pra saber o requisito mínimo do aparelho da demo,
rode em cada estado do fluxo (aguardando, capturando, falando, escutando, avatar visível):

```bash
adb shell dumpsys meminfo com.meta.wearable.dat.externalsampleapps.cameraaccess
```

Único número medido até agora é de **emulador**: app ~100 MB + processo Unity ~307 MB +
serviço WebView ~60 MB ≈ **455 MB**, sem os modelos de reconhecimento carregados — o hardware
real da demo ainda não tem esse número.

**5. Temperatura e bateria, em regime estável** — o painel já mostra folga térmica e status ao
vivo, mas **a leitura logo após mudar alguma configuração é otimista**: o regime térmico
estável só aparece depois de alguns minutos. Por isso o ensaio de 15–20 min seguidos (item da
véspera acima) existe — é o único jeito de saber se o celular esquenta de verdade no uso
contínuo do dia.

### Métricas que faltam ser monitoradas (achado, não implementado)

O painel/CSV hoje só mede desempenho de sistema (fps, latência, térmico, RAM) — nada sobre
**acerto real**. Isso combina mal com o fato de que a calibração externa não bateu a meta
(§1.7: 46% de acerto bruto fora do domínio MINDS) e não existe conjunto humano de teste.

**O maior buraco, achado ao ler `DialogOrchestrator.kt`:** os botões de ②.5
(Confirmar/Corrigir) e ③.5 (Pedir repetição) **não geram nenhum evento no gravador** — só
existe log pro caminho de falha de câmera (`corrigir_sem_camera`, `repetir_sem_camera`), não
pro caso normal de alguém apertar o botão. Cada "Corrigir" em uso real é, de graça, um rótulo
de "o modelo errou" — hoje esse sinal se perde. Bastaria um `onEvento("reconhecimento_confirmado"/"reconhecimento_corrigido", ...)` e um `onEvento("repeticao_pedida", ...)` pra virar
dado agregável.

Outras métricas de "acerto", não só de desempenho, que valeriam entrar:

| Métrica | Por quê | Onde já existe o dado bruto |
|---|---|---|
| Taxa Confirmar/Corrigir em ②.5, por sessão | proxy direto de acurácia de campo, sem precisar de rótulo externo | falta logar (acima) |
| Pedidos de repetição (③.5) por sinal/sessão | indica quais sinais são difíceis de reconhecer na prática | falta logar (acima) |
| Distribuição da confiança mínima por frase | hoje o limiar de "pedir repetição" é chutado (0,60 em `ConfiguracoesDemo`); a distribuição real ajudaria a escolher com dado, não palpite | já vai linha a linha pro CSV (confiança/margem por classificação); só falta agregar |
| Taxa de aceite da guarda de contextualização (modelo vs template) | é a própria análise que o `hibrido.py` recomenda (§2.8) fazer em produção, não só em validação sintética | não medido no app hoje |
| Fallback de TTS/STT/wake word (quantas vezes cada reserva entrou) | mostra se os motores principais (Piper/Vosk/OpenWakeWord) estão de fato segurando o uso real | os fallbacks já existem em código; só falta contar |
| Duração dos segmentos do `SignBoundaryDetector` | confirma (ou não) que os parâmetros "chutados" (§4.5) geram segmentos plausíveis, sem esperar a calibração formal | já está no CSV (início/fim de segmento) |
| Cache hit rate do avatar (`GlosaCache`) | o avatar é a única peça que depende de rede; saber quanto vem do cache localiza o risco | não medido hoje |
| Eventos de pressão de memória (§8.1: avatar liberado) | se disparar durante a demo, muda a leitura de "por que o avatar sumiu" | decisão implementada; contagem não exposta |

---

## 7. Perguntas prováveis da banca — respostas curtas, com a ressalva certa

**"Qual a acurácia do reconhecimento de sinal?"**
94,6–94,9% LOSO signer-independent no ST-GCN, empatado com uma ResNet-18 de 95,1% usando 24×
menos parâmetros. **Ressalva obrigatória:** é vídeo de estúdio, frontal — teto otimista; o
número de balcão real ainda depende de coleta própria. Variância entre execuções é ~1,7pp.

**"E a IA que monta a frase, funciona?"**
Está pronta e integrada, mas roda **sob guarda com o template como piso**, porque em três
rodadas de validação sintética o modelo seq2seq não bateu o template em F1 de palavras
(0,824–0,856 vs 0,871–0,884). A guarda aceita o modelo em ~95% das sessões, e ele ganha
justamente nos casos com relação gramatical entre glosas. **Ressalva obrigatória:** o corpus
de validação é sintético (gerado por LLM), não Libras observada — nenhum número dessa trilha
vale como evidência de campo.

**"O sistema já foi testado com uma pessoa surda de verdade?"**
Não. Tudo até agora é vídeo de estúdio de bases públicas ou dado sintético. É a pendência
mais citada em todo o repositório — junto com a validação do vocabulário com um consultor de
Libras.

**"O sistema funciona sem internet?"**
Sim, com uma exceção: o avatar em Libras (resposta pro surdo) depende de rede pra traduzir
português→glosa e buscar os sinais no VLibras. Todo o resto — reconhecimento, contextualização,
fala, transcrição — é 100% on-device.

**"Por que ST-GCN e não a ResNet, se a ResNet foi um pouco melhor?"**
Empataram dentro da margem de ruído (95,1% vs 94,6/94,9%, variância medida ~1,7pp), e o
ST-GCN tem 24× menos parâmetros (0,47M vs 11,25M) — decisivo pra caber num `.tflite` rodando
em tempo real no celular.

---

## 8. Onde ler mais (se precisar aprofundar algo específico)

| Assunto | Documento |
|---|---|
| Visão geral do projeto, estado consolidado | `docs/CONTEXTO.md` |
| ResNet vs ST-GCN, com a retratação registrada | `docs/decisao-arquitetura-modelo.md` |
| Datasets e licenças (V-LIBRASIL é CC BY-NC-ND) | `docs/decisao-datasets-e-licencas.md` |
| Vocabulário em 3 camadas | `docs/vocabulario-mvp-proposta.md` |
| Contextualização — decisões e guardas | `docs/contextualizacao-glosa-seq2seq-plano.md` |
| Integração privada do classificador no app | `docs/integracao-modelo-app-plano-2026-09-15.md` |
| Confirmação + economia de bateria (②.5) | `docs/confirmacao-e-modo-economia-plano.md` |
| Consentimento por atendimento (①.5, LGPD) | `docs/consentimento-por-atendimento-plano.md` |
| Avatar VLibras/WebView, as 3 decisões medidas | `docs/vlibras-webview-plano.md` |
| Wake word — pipeline pt-BR, ACAV100M, limiares | `docs/wake-word-treino-plano.md` |
| Plano de prontidão da demo, ponto a ponto | `docs/prontidao-demo/` (11 arquivos) |
| Guia de testes (mock e óculos reais) | `docs/guia-de-testes-mock-e-oculos.md` |
| Como a IA está plugada no app, arquivo por arquivo | `mobile-app-companion/app/.../libras/README.md` |

---

## 9. Divergências entre documentos que valem confirmar antes de falar em público

1. **Export do ST-GCN**: `computer-vision-model/README.md` e `docs/CONTEXTO.md` dizem que só
   a ResNet é exportável; o código de `treino/exportar.py` (15/09) e o checklist de
   `mobile-app-companion/README.md` (16/09) indicam que o export do GCN já está implementado.
   Provavelmente os dois primeiros documentos só não foram atualizados — mas confirme com
   quem mexeu nisso por último antes de declarar "já exportamos o GCN" na apresentação.
2. **Data do hackathon**: os docs internos (`CONTEXTO.md`, `prontidao-demo/`) citam prazo
   16/09/2026; hoje é 17/09/2026 pelo relógio do sistema. Se "amanhã" é um evento diferente
   do que os docs descrevem, os números/checklists ainda servem, mas o prazo citado neles já
   passou — não repita "16/09" como se fosse a data de amanhã sem checar.

# Computer Vision Model — Libras Livre

Trilha de **visão** do Libras Livre: transforma vídeos de sinais de Libras num
classificador que reconhece **um sinal isolado por vez**, destinado a rodar
on-device dentro do app ([`../mobile-app-companion`](../mobile-app-companion)).

A pergunta que organiza tudo aqui:

> Qual a acurácia com uma **pessoa que o modelo nunca viu**?

Os óculos são institucionais — atendem alguém novo a cada atendimento, sem
calibração. Por isso toda avaliação nesta pasta deixa uma pessoa **inteira** fora
do treino (*leave-one-signer-out*, LOSO). Um número medido de outra forma não
responde à pergunta do produto.

---

## 1. Onde estão as coisas

```
computer-vision-model/
├── datasets/       ingestão dos vídeos públicos (MINDS, V-LIBRASIL, MALTA, WLASL)
├── PoC/            extração de landmarks + baseline DTW (a PoC que decidiu o resto)
│   └── src/extract.py   ⟵ a extração de landmarks usada por TODO o pipeline
├── treino/         ⟵ o pipeline de produção: representação, modelos, LOSO, export
├── config.yaml     vocabulário e caminhos
├── src/ + scripts/ andaime legado — ver §6
└── models/         saída do export (não versionado)
```

O caminho real dos dados atravessa três pastas:

```
datasets/ingest*.py     ->  PoC/src/extract.py   ->  treino/treinar.py  ->  treino/exportar.py
vídeos .mp4                 landmarks .npy           checkpoint .pt         .tflite
(não versionados)           (não versionados)        (não versionado)
                                                                             |
                                             ../mobile-app-companion/app/src/main/assets/
```

---

## 2. Conceitos que decidem o resto

### 2.1 Sinais isolados, não frases

O classificador reconhece **uma palavra por vez**, não a frase inteira como classe
única. Três motivos:

- **Linguisticamente correto:** Libras é composta por sinais; uma frase é uma
  sequência deles, não um bloco atômico.
- **Escalável:** um sinal ("dor") serve em várias frases. Com N sinais você cobre
  muito mais que N frases.
- **Alinhado aos datasets reais:** V-LIBRASIL, MINDS-Libras, WLASL e MALTA são
  todos organizados por palavra isolada.

A montagem da frase acontece **depois**, e não aqui: a segmentação ("onde um sinal
acaba") é do app, e a tradução glosa → português é da trilha
[`../contextualization-model`](../contextualization-model).

### 2.2 Landmarks, não pixels

O modelo não vê a imagem. Cada frame passa pelo **MediaPipe Holistic**, que devolve
as coordenadas de:

```
21 pontos de cada mão  +  15 pontos de pose  =  57 pontos por frame
```

Os 15 pontos de pose (nariz, olhos, orelhas, boca, ombros, cotovelos, pulsos,
quadris) foram ampliados de 7 para 15 em 2026-09-08: muitos sinais são articulados
em relação a **âncoras faciais** — "maçã (rosto)", "medo" — e com só o nariz como
referência de cabeça essa informação desaparece. Pernas ficam de fora: irrelevantes
para sinalização e frequentemente fora do quadro num balcão.

Por que essa representação é uma boa ideia:

- **Invariante ao que não importa:** cor de pele, roupa, fundo e iluminação não
  chegam ao modelo. Só a geometria.
- **Modelo minúsculo:** cabe num `.tflite` que roda em tempo real no celular.
- **Menos dados necessários:** o MediaPipe já resolveu achar a mão; resta ensinar
  a forma e o movimento de cada sinal.

**A normalização é o passo que mais afeta o resultado.** Origem no ponto médio dos
ombros, escala pela distância entre eles — o que torna os landmarks invariantes à
distância da câmera, à posição da pessoa no quadro e à resolução. O app replica
exatamente esse algoritmo em Kotlin (`LandmarkNormalizer.kt`), com testes de
paridade numérica, porque uma divergência aqui não gera erro: degrada a acurácia
em silêncio.

### 2.3 Duas arquiteturas medidas, uma escolhida

| Modelo | LOSO | Parâmetros | Tamanho exportado |
|---|---|---|---|
| Chance aleatória (20 sinais) | 5,0% | — | — |
| Baseline DTW 1-NN (PoC) | 70,0% | — | — |
| ST-GCN (x, y apenas) | 72,1% | 0,46M | — |
| ST-GCN + ossos | 91,0% / 92,5% | 0,46M | — |
| **ST-GCN + ossos + z — entrega** | **94,6% / 94,9%** | **0,47M** | ~1,9 MB fp32 / 0,47 MB int8 |
| ResNet-18 + imputação | 95,1% | 11,25M | 45 MB fp32 / 11,3 MB int8 |
| Literatura (mesma base, mesmo protocolo) | 93–94% | — | — |

**O ST-GCN é o modelo de entrega**, decidido em 2026-09-11: empatou com a melhor
ResNet usando 24× menos parâmetros. O raciocínio completo, inclusive a retratação
de uma afirmação anterior errada, está em
[`../docs/decisao-arquitetura-modelo.md`](../docs/decisao-arquitetura-modelo.md).

Duas coisas precisam acompanhar qualquer citação desses números:

1. **A variância entre execuções é de ~1,7 ponto.** Diferenças menores que ~2
   pontos são ruído.
2. **São todos vídeos de estúdio**, frontais e controlados. Teto otimista. O
   número de balcão depende de coleta própria.

O que fez o ST-GCN sair de 72% para 94% foram os **ossos** (+18,9 pontos) e o **z
recentrado** (+2,1) — features derivadas dos landmarks que já existiam, sem nenhum
dado novo.

### 2.4 Por que `.tflite`

O modelo roda dentro do app, no celular, sem internet. O checkpoint PyTorch é
convertido por `ai-edge-torch`, com quantização opcional. O `.tflite` recebe
`(1, T, P, 2)` e devolve logits — a montagem da representação acontece **dentro do
grafo**, para que o app não precise reproduzi-la e errar em silêncio.

**Limitação atual:** `treino/exportar.py` só cobre a **ResNet-18**. Escrever o
export do ST-GCN — incluindo calcular os ossos dentro do grafo, o que hoje é feito
em Python — é o maior bloqueio entre a decisão de arquitetura e ter algo rodando no
aparelho. É trabalho novo, não uma flag.

---

## 3. Os dados

| Conjunto | Clipes | Classes | Pessoas | Papel |
|---|---|---|---|---|
| **MINDS-Libras** | 800 | 20 sinais | 8 | **treino + avaliação LOSO** |
| V-LIBRASIL (auditada) | 4.025 | 1.349 palavras | 3 (sempre os mesmos) | pré-treino |
| MALTA-LIBRAS | 6.353 | 5.958 rótulos | 8 | pré-treino (ainda não habilitado no código) |
| WLASL100 (ASL) | 1.013 | 100 | 64 | pré-treino (ainda não habilitado no código) |
| V-LIBRASIL (curada) | 30 | 10 sinais | 3 | clipes reservados |

O vocabulário de avaliação são os **20 sinais do MINDS-Libras**, em `config.yaml`,
`PoC/config.yaml` e `datasets/selecao.yaml` — a ingestão confere que as três listas
coincidem:

```
acontecer  amarelo  banheiro  barulho  espelho  filho  maca  medo  ruim  sapo
aluno  america  aproveitar  bala  banco  cinco  conhecer  esquina  vacina  vontade
```

Até 2026-09-08 a lista tinha 10 sinais — os que existem nas **duas** bases, para
maximizar pessoas por sinal na PoC de DTW. O treino passou a usar o MINDS como
núcleo: fonte única, sem o degrau entre bases que custou ~15 pontos na PoC.

**Estes não são os sinais do produto.** São um banco de provas. O vocabulário de
atendimento está proposto em
[`../docs/vocabulario-mvp-proposta.md`](../docs/vocabulario-mvp-proposta.md) e
depende de consultor de Libras e de coleta própria.

**Os papéis não se misturam, e isso é deliberado.** Um clipe que entra no
pré-treino não pode aparecer na avaliação, ou o número de generalização deixa de
significar o que diz. O isolamento e a proveniência estão em
[`../docs/protocolo-pretreino.md`](../docs/protocolo-pretreino.md).

Os vídeos **não são versionados** — são ~48 GB e a V-LIBRASIL é CC BY-NC-ND, que
não autoriza redistribuição. O que fica no git é a receita:
[`datasets/README.md`](./datasets/README.md).

---

## 4. Como rodar

Python **3.11** (o MediaPipe 0.10.14 não suporta 3.12+).

```bash
cd PoC && python3.11 -m venv .venv311 && source .venv311/bin/activate
pip install -r requirements.txt
```

### 4.1 Trazer os vídeos

```bash
cd ../datasets
python selftest.py                   # confere a receita, sem rede
python ingest.py --listar            # cobertura e tamanho, sem baixar nada
python ingest.py --reps 1            # 1 repetição por pessoa/sinal — primeiro teste
python ingest.py                     # seleção completa
```

Não é preciso credencial do Kaggle: `remote_zip.py` lê o índice no rodapé de cada
`.zip` remoto e baixa só os membros escolhidos. A ingestão é retomável e ordenada
por repetição, de modo que uma execução interrompida deixa um dataset balanceado.

### 4.2 Extrair landmarks

```bash
cd ../PoC
python src/extract.py                        # data/raw -> data/landmarks
python src/extract.py --particao 1/4         # paraleliza por partição
python src/extract.py --descartar-video      # apaga o .mp4 depois de extrair
```

**Esta etapa é CPU e leva horas. Não acelera em GPU** — fica na máquina local.

### 4.3 Treinar e avaliar

```bash
cd ../treino
python selftest.py                           # valida o encanamento em segundos
python treinar.py --epocas 5 --folds 1       # rodada curta, para ver de pé
# LOSO completo da configuração de entrega (ST-GCN + ossos + z recentrado):
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado
# treina com todas as pessoas e salva o checkpoint final:
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado --final
```

O treino vai para GPU pelos notebooks (`treino/notebook_gpu.ipynb`,
`notebook_gcn_variantes.ipynb`, `notebook_poc_3d.ipynb`), em Colab ou Kaggle, onde
horas viram minutos. Detalhes e o papel de cada arquivo em
[`treino/README.md`](./treino/README.md).

### 4.4 Exportar

```bash
python exportar.py --smoke                   # valida o toolchain, sem checkpoint
python exportar.py --checkpoint resultados-resnet/modelo_final.pt \
                   --saida ../models/sinal_classifier.tflite
```

Requer `pip install "torch<2.10" ai-edge-torch`. **Só a ResNet-18 é exportável
hoje** (§2.4).

### 4.5 Validar que nada quebrou

```bash
cd treino    && python selftest.py
cd datasets  && python selftest.py
cd PoC       && python src/selftest.py
```

O selftest do treino inclui um **controle negativo**: com rótulos aleatórios, a
acurácia tem de ficar na chance. Se subir, há vazamento entre treino e teste — o
erro mais caro possível aqui, porque produz um número bonito e falso.

---

## 5. A PoC — o passo zero, já respondido

Antes de investir no pipeline de treino, uma PoC respondeu se a abordagem se
sustentava, com um critério de ir/não-ir definido antes de rodar:

> MediaPipe Holistic + um classificador simples reconhecem um vocabulário fechado
> de Libras generalizando entre pessoas diferentes?

**Resposta medida:** 70,0% no dataset completo (10 sinais, 11 pessoas, 430 clipes)
e 85,7% no recorte sem o degrau entre bases e sem os rótulos ainda não validados.
Sinal verde, com uma descoberta que mudou o pipeline: clipes de bases diferentes
quase nunca são vizinhos um do outro — juntar MINDS e V-LIBRASIL não somou pessoas,
somou um degrau de condição de gravação. Foi por isso que o treino passou a usar o
MINDS como fonte única.

A pasta [`PoC/`](./PoC) continua sendo onde vive a **extração de landmarks** que
todo o pipeline consome (`PoC/src/extract.py`), além do baseline DTW e do relatório
completo em `PoC/results/relatorio.md`.

---

## 6. `src/` e `scripts/` são andaime legado

As pastas `src/` e `scripts/` vêm do commit inicial do repositório, antes da PoC, e
**não fazem parte do pipeline em uso**. Vários dos seus módulos são *stubs* que
levantam `NotImplementedError` (`src/data/dataset.py`, `src/models/temporal.py`,
`src/training/train.py`, `src/export/to_tflite.py`).

O que as substituiu:

| Andaime legado | Substituído por |
|---|---|
| `src/data/landmark_extraction.py` | `PoC/src/extract.py` (Holistic, 57 pontos) |
| `src/data/dataset.py` | `treino/dados.py` |
| `src/models/shallow.py`, `temporal.py` | `treino/modelo.py` (ResNet-18), `treino/gcn.py` (ST-GCN) |
| `src/training/train.py` | `treino/treinar.py`, `treino/pretreinar.py` |
| `src/export/to_tflite.py` | `treino/exportar.py` |
| `scripts/01..03_*.py` | os pontos de entrada de `treino/` |

Elas permanecem apenas como registro do desenho inicial. Não as use como ponto de
partida.

---

## 7. Onde ler mais

| Assunto | Documento |
|---|---|
| Visão geral do projeto | [`../docs/CONTEXTO.md`](../docs/CONTEXTO.md) |
| Procedimento de treino ponta a ponta | [`../docs/protocolo-treinamento.md`](../docs/protocolo-treinamento.md) |
| Isolamento e proveniência do pré-treino | [`../docs/protocolo-pretreino.md`](../docs/protocolo-pretreino.md) |
| ResNet vs. ST-GCN, com números e ressalvas | [`../docs/decisao-arquitetura-modelo.md`](../docs/decisao-arquitetura-modelo.md) |
| Datasets e enquadramento legal | [`../docs/decisao-datasets-e-licencas.md`](../docs/decisao-datasets-e-licencas.md) |
| Estado da arte e expansão do dataset | [`../docs/investigacao-expansao-dataset.md`](../docs/investigacao-expansao-dataset.md) |
| Como rodar o treino, arquivo por arquivo | [`treino/README.md`](./treino/README.md) |
| Como os vídeos são baixados | [`datasets/README.md`](./datasets/README.md) |
| A PoC e o baseline DTW | [`PoC/README.md`](./PoC/README.md) |

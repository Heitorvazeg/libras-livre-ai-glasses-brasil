# PoC — Reconhecimento Signer-Independent de Libras

> **Equipe 3G1B · Programa AI Glasses Brasil**
>
> Esta PoC existe para responder **uma única pergunta técnica** antes de investir
> tempo em qualquer outra parte do sistema (treino do `.tflite`, app, nuvem):

> **MediaPipe Holistic + um classificador simples conseguem reconhecer um
> vocabulário fechado de Libras, generalizando entre pessoas diferentes, na
> distância e ângulo de câmera de um atendimento de balcão?**

Todo o resto desta pasta existe para responder isso com o **mínimo de esforço** e
com um **critério de decisão definido antes de começar** (ver §6).

> ⚠️ **Relação com o resto de `computer-vision-model/`:** o pipeline principal
> (`../src`, `../scripts`, `../config.yaml`) monta o classificador de produção com
> MediaPipe **Hands** e export `.tflite`. Esta PoC é **anterior** a isso: ela
> valida a hipótese com MediaPipe **Holistic** (mãos + pose do tronco) e um
> baseline **DTW**, sem treino de rede neural. Só faz sentido investir no pipeline
> completo depois que a PoC der sinal verde (§6.3).

---

## 1. O que está (e o que não está) nesta PoC

**Está:** vocabulário fechado de 10 sinais isolados · 11 pessoas diferentes por
sinal, vindas de duas bases públicas ([`../datasets/`](../datasets)) · extração de landmarks com Holistic · baseline DTW (1-NN) · avaliação
**leave-one-signer-out** · matriz de confusão · critério de ir/não-ir · diagnóstico
de onde vem o erro (`src/diagnostico.py`). **Já rodou:** 70,0% no dataset completo,
85,7% no recorte limpo (§6.4).

**Não está** (fica para depois, ver [`docs/libras-livre-arquitetura.md`](../../docs/libras-livre-arquitetura.md)): nuvem, deploy,
API · retrain contínuo · tela para a pessoa surda · núcleo offline · consentimento
institucional formal · contextualização sinal→frase · hardware final dos óculos
(qualquer câmera de celular na posição certa serve).

**Também fora desta entrega:** o classificador treinado opcional do §5.5 do plano
(GRU/MLP). `src/nn_classifier.py` segue como stub documentado — só faz sentido
depois que o baseline DTW estiver medido com dados reais.

---

## 2. Vocabulário

**10 sinais**, definidos pela disponibilidade de vídeo público:

```
acontecer   amarelo   banheiro   barulho   espelho
filho       maca      medo       ruim      sapo
```

São exatamente os sinais que existem **nas duas** bases públicas integradas ao
repositório (MINDS-Libras e V-LIBRASIL) — e, por isso, os que chegam com **11
pessoas diferentes cada**. Ver [`../datasets/README.md`](../datasets/README.md).

Por que não é o vocabulário de atendimento (`ola`, `ajuda`, `dor`,
`marcar-consulta`…): esses sinais não existem nas bases públicas com pessoas
suficientes, e a pergunta desta PoC é sobre **generalização entre pessoas** — ela
se responde com qualquer vocabulário variado o bastante. Estes 10 têm
configurações de mão e movimentos bem diferentes entre si, que é o que interessa
para estressar o classificador. O vocabulário de produto volta quando houver
coleta própria, e aí valem os critérios originais:

- priorizar sinais do MVP de atendimento, para gerar dado reaproveitável;
- incluir de propósito configurações de mão e movimentos variados;
- evitar sinais que dependem de **expressão facial** (fora de escopo);
- **definir a lista com apoio de profissional de Libras / pessoa surda consultora
  antes de gravar** — vocabulário errado invalida o resultado.

Três dos dez (`maca`, `medo`, `sapo`) têm **rótulos diferentes entre as duas
bases** e foram pareados por julgamento — precisam de conferência de consultor
antes de valerem como uma única classe (`../datasets/README.md` §3). Para rodar
só com os 7 de rótulo idêntico: `python ingest.py --somente-validados`.

A lista fica em `config.yaml` (`vocabulario`) e é a fonte de verdade: `record.py`
recusa gravar um sinal que não esteja nela, `ingest.py` confere se ela bate com a
seleção de vídeos públicos, e `evaluate.py` avisa se algum sinal do vocabulário
ficou sem clipes.

---

## 3. De onde vêm os clipes

**Hoje, das bases públicas.** `../datasets/ingest.py` traz 430 clipes
(10 sinais × 11 pessoas; 5 repetições por pessoa na parte MINDS, 1 na
V-LIBRASIL) já nomeados na convenção do §4, sem gravar nada:

```bash
cd ../datasets && python ingest.py --reps 1   # 11 pessoas em todos os sinais, ~5 GB
```

Isso responde a pergunta da PoC **em condição favorável**: as duas bases foram
gravadas de frente, com enquadramento controlado e a V-LIBRASIL com *chroma key*
— não é o balcão. Leia as ressalvas em [`../datasets/README.md`](../datasets/README.md) §6
antes de interpretar a acurácia.

### Protocolo da coleta própria

O que confirma o número no cenário real (e continua valendo quando o vocabulário
de produto voltar):

| Item | Alvo |
|---|---|
| Participantes | **mín. 5, ideal 8** pessoas diferentes que sinalizam Libras, o mais diverso possível |
| Repetições | **5 por sinal, por pessoa** (piso). Mais **pessoas** vale mais que mais repetições |
| Volume-exemplo | 12 sinais × 5 reps × 6 pessoas ≈ **360 clipes** |
| Câmera | altura/distância dos óculos num balcão (~altura dos olhos, ~1–1,5 m do sinalizante) |
| Luz | ambiente real (sala/escritório), **não** estúdio |

Uma pessoa repetindo o sinal muitas vezes **não substitui** ter várias pessoas —
mede consistência de um sinalizante, não generalização entre pessoas.

Dois documentos operacionais acompanham esta fase:

- [`docs/consentimento.md`](docs/consentimento.md) — termo simples de participação
  em pesquisa (§4.4): para que serve o vídeo, se ele é descartado após a extração
  dos landmarks e por quanto tempo os pontos ficam retidos.
- [`docs/checklist-setup.md`](docs/checklist-setup.md) — checklist de setup físico
  e de sessão (§4.3, §9): altura/distância da câmera, enquadramento, registro do
  setup, conferência ao encerrar.

Se possível, **descarte os vídeos brutos logo após extrair os landmarks**
(`python src/extract.py --descartar-video`), mantendo só os pontos.

---

## 4. Estrutura da pasta

```
PoC/
├── config.yaml            vocabulário, metas de coleta, caminhos, parâmetros
├── requirements.txt       mediapipe, opencv, numpy, dtaidistance/fastdtw, sklearn, matplotlib
├── data/
│   ├── raw/               vídeos brutos (../datasets/ingest.py ou record.py;
│   │                      descartáveis após a extração)
│   └── landmarks/         landmarks extraídos, 1 .npy por clipe
├── docs/
│   ├── consentimento.md   §4.4 — termo de participação
│   └── checklist-setup.md §4.3/§9 — setup físico e rotina de sessão
├── src/
│   ├── config.py          leitura do config.yaml (usado por todos os scripts)
│   ├── record.py          §5.1 — captura de clipes (marcação início/fim)
│   ├── extract.py         §5.2 — extração via MediaPipe Holistic + normalização
│   ├── dtw_classifier.py  §5.3 — baseline 1-NN por DTW
│   ├── evaluate.py        §5.4 — leave-one-signer-out + matriz de confusão
│   ├── diagnostico.py     §6 — de onde vem o erro (por base, por sinal, com/sem z)
│   ├── selftest.py        validação do pipeline sem câmera (dados sintéticos)
│   └── nn_classifier.py   §5.5 — opcional, FORA do escopo desta entrega
└── results/
    ├── confusion_matrix.png
    ├── relatorio.md
    └── predicoes.csv
```

**Convenção de nome de clipe** (o nome já carrega os metadados — evita planilha
separada):

```
pessoa03_sinal-ajuda_rep02.mp4       →  pessoa=03, sinal=ajuda, repetição=02
pessoaM05_sinal-acontecer_rep03.mp4  →  pessoa=M05 (MINDS-Libras, sinalizador 05)
pessoaV02_sinal-ruim_rep01.mp4       →  pessoa=V02 (V-LIBRASIL, articulador 02)
```

O prefixo `M`/`V` nos clipes das bases públicas mantém a origem visível e impede
que o sinalizador 02 de uma base e o articulador 02 da outra sejam lidos como a
mesma pessoa — o que faria o leave-one-signer-out testar em quem ele treinou.

`extract.py` gera o `.npy` de mesmo nome-base em `data/landmarks/`.

---

## 5. Pipeline técnico, passo a passo

### 5.1 Gravação — `record.py`
Abre a câmera (`cv2.VideoCapture`). Cada clipe é gravado sob demanda: **ESPAÇO**
começa e encerra, **D** descarta o último clipe (sinal saiu errado), **Q** sai. A
numeração de repetição avança sozinha a partir dos arquivos já existentes, e a
tela mostra pessoa/sinal/repetição e o tempo do clipe em andamento.

### 5.2 Extração de landmarks — `extract.py`
Roda `mediapipe.solutions.holistic.Holistic` frame a frame. De cada frame extrai
**21 pontos de cada mão** + um **subconjunto de pose** (nariz, ombros, cotovelos,
pulsos, definido em `config.yaml`) — contexto de tronco sem inflar a
dimensionalidade. São 49 pontos × 3 coordenadas = 147 valores por frame.

> **A normalização é o passo que mais afeta o resultado e o mais fácil de
> esquecer.** As coordenadas do MediaPipe vêm normalizadas por largura/altura, o
> que distorce x contra y em vídeo 16:9 — então primeiro convertemos para pixels
> (`x·W`, `y·H`, `z·W`). Depois subtraímos o **ponto médio entre os ombros** de
> todos os pontos e dividimos pela **distância entre os ombros**. Isso torna os
> landmarks invariantes à distância da pessoa até a câmera, à posição dela no
> quadro **e à resolução da gravação** — sem isso, o modelo aprende a distinguir
> "quem está mais perto da câmera" em vez de "qual sinal".

Detalhes que mudam o número final:

- **Frame sem pose confiável** (ombro ausente ou com visibilidade abaixo de
  `normalizacao.min_visibilidade`) é descartado: sem referência estável não há
  normalização possível. `extract.py` avisa quando passa de 30% de descarte num
  clipe — é sintoma de enquadramento errado, não de limitação do modelo.
- **Mão não detectada** vira zeros **depois** da normalização. Como a origem é o
  ponto médio dos ombros, o zero é um marcador de ausência estável, que não muda
  conforme a pessoa anda pelo quadro.
- **Coordenada z:** o z das mãos é relativo ao punho e o de pose é relativo ao
  quadril — não estão no mesmo referencial. Fica ligada por padrão (é o que o
  plano pede), mas `normalizacao.usar_z: false` é o primeiro botão a testar se o
  resultado cair na zona amarela.

Saída: `data/landmarks/<nome-base>.npy`, `float32 (num_frames, 49, 3)`.

### 5.3 Baseline DTW — `dtw_classifier.py`
Biblioteca pronta de DTW (a lógica do algoritmo não é o gargalo, a qualidade dos
landmarks é). Cada frame vira um vetor de 147 valores e o clipe é classificado
pela **menor distância DTW** até **todos** os clipes de referência (1-NN).

Backend padrão: **dtaidistance** (DTW multivariado em C, multi-thread) — é o que
torna a avaliação viável, já que 360 clipes geram ~65 mil pares. **fastdtw** fica
como fallback puro-Python, correto porém ordens de grandeza mais lento. O
relatório registra qual backend foi usado, porque os valores absolutos de
distância não são comparáveis entre eles.

### 5.4 Avaliação leave-one-signer-out — `evaluate.py`
Para cada pessoa `p`: referência = todos os clipes **exceto** os de `p`; teste =
clipes de `p`. Classifica cada clipe de teste, registra acerto/erro. Ao final,
**acurácia média entre as rodadas** + **matriz de confusão agregada**.

A matriz de distâncias entre todos os pares é calculada **uma vez** e reaproveitada
por todas as rodadas (e fica em cache em `results/`, invalidado automaticamente se
os dados ou os parâmetros de DTW mudarem — `--recalcular` força de novo).

Antes de rodar, `evaluate.py` confere a coleta e avisa sobre o que muda a leitura
do resultado: participantes abaixo do mínimo, sinais sem clipes, sinais gravados
por uma única pessoa (erro garantido na rodada dela), combinações abaixo do alvo
de repetições.

### 5.5 Opcional — `nn_classifier.py`
GRU pequeno sobre os mesmos landmarks. **Fora do escopo desta entrega**; só
depois do DTW medido com dados reais, e só vale adotar se superar o baseline de
forma consistente no **mesmo** protocolo.

---

## 6. Avaliação e critério de decisão

- **Leave-one-signer-out é obrigatório** — é o cenário real de produto (o sistema
  encontra alguém que nunca viu).
- **Métricas:** acurácia top-1 média entre rodadas (principal) + matriz de
  confusão agregada. O relatório também traz a acurácia por sinal e os pares mais
  confundidos.

**Critério definido _antes_ de rodar o experimento** (limiares em `config.yaml`):

| Acurácia signer-independent | Decisão |
|---|---|
| **≥ 80%** | 🟢 Seguir para o MVP com essa abordagem |
| **60–80%** | 🟡 Revisar vocabulário (pares confundidos na matriz), mais repetições, ou testar o classificador treinado antes de decidir |
| **< 60%** | 🔴 Reconsiderar abordagem (vocabulário, distância de câmera, arquitetura) antes de investir mais |

### 6.4 Resultado medido (430 clipes, 11 pessoas)

**70,0% — 🟡 zona de atenção.** Relatório completo em
[`results/relatorio.md`](results/relatorio.md).

O número é a média de dois regimes diferentes, e `src/diagnostico.py` separa:

| cenário | pessoas | acurácia |
|---|---|---|
| dataset completo | 11 | **70,0%** 🟡 |
| só a base MINDS-Libras | 8 | 77,8% |
| só a base V-LIBRASIL | 3 | 46,7% |
| só os 7 sinais de rótulo validado | 11 | **80,5%** 🟢 |
| MINDS + só os 7 validados | 8 | **85,7%** 🟢 |

Duas leituras que mudam o que fazer a seguir:

1. **Clipes de bases diferentes quase nunca são vizinhos um do outro** (100% dos
   vizinhos da MINDS são da MINDS; 93% dos da V-LIBRASIL são da V-LIBRASIL). As
   bases não somaram pessoas — somaram um degrau de condição de gravação. O
   amarelo vem em boa parte daí, não da dificuldade do sinal.
2. **Os 3 rótulos pareados por julgamento** (`maca`, `medo`, `sapo` — §2) custam
   ~10 pontos. Validá-los com consultor de Libras é a intervenção de maior
   retorno antes de mexer em modelo.

Ressalva que não sai com ajuste nenhum: as duas bases são frontais e controladas
(a V-LIBRASIL com chroma key). **Mesmo 85,7% é teto otimista** para o balcão —
confirmar no cenário real depende da coleta própria (§3). Detalhes em
[`../datasets/README.md`](../datasets/README.md) §6.

---

## 7. Como rodar

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 0) valida o pipeline inteiro sem câmera e sem participantes (~30 s)
python src/selftest.py

# 1) traga os clipes. Das bases públicas (11 pessoas, sem gravar nada):
(cd ../datasets && python ingest.py --reps 1)
#    ou grave os seus (uma pessoa/sinal por vez, convenção de nome no §4):
python src/record.py --pessoa 03 --sinal ajuda

# 2) extraia landmarks de tudo em data/raw -> data/landmarks
python src/extract.py                    # --descartar-video apaga o .mp4 após extrair

# 3) confira o dataset carregado (clipes, pessoas, sinais, duração)
python src/dtw_classifier.py

# 4) rode a avaliação leave-one-signer-out (baseline DTW)
python src/evaluate.py

# 5) entenda de onde vem o erro (recorta o mesmo protocolo em subconjuntos)
python src/diagnostico.py            # por base de origem e por subconjunto de sinais
python src/diagnostico.py --sem-z    # mede o botão do §5.2 em vez de supor
```

O passo 4 grava em `results/`:

| Arquivo | Conteúdo |
|---|---|
| `relatorio.md` | acurácia média + veredito, acurácia por pessoa e por sinal, pares confundidos, ressalvas da coleta |
| `confusion_matrix.png` | matriz de confusão agregada |
| `predicoes.csv` | uma linha por clipe de teste (previsto, distância, clipe vizinho) para qualquer análise extra |

Ordem de grandeza no cenário do plano (≈360 clipes, ~65 mil pares): a matriz de
distâncias leva poucos segundos com dtaidistance em uma máquina comum.

### Estado de validação do código

`src/selftest.py` exercita o pipeline inteiro com dados sintéticos e cobre:
invariância da normalização (deslocamento, escala, resolução), marcação de mão
ausente, descarte de frame sem pose, convenção de nome, DTW 1-NN, o
leave-one-signer-out completo com geração dos artefatos, um **controle negativo**
(dataset de ruído puro precisa reprovar no critério §6.3) e a leitura de vídeo com
o Holistic rodando de ponta a ponta. Todos passam nesta máquina.

**O que só dado real valida:** a qualidade da detecção de landmarks em pessoas
sinalizando de verdade, no setup de balcão — e, claro, a acurácia que responde à
pergunta da PoC. O código está pronto para receber os clipes; a coleta é o
gargalo (§9 do plano), não ele.

---

## 8. Entregáveis

- Acurácia signer-independent média + matriz de confusão → `results/relatorio.md`
  e `results/confusion_matrix.png`.
- Decisão de ir/não-ir para o MVP (critério §6), impressa e registrada no relatório.
- **Dataset de landmarks próprio** (reutilizável no MVP e no pipeline de produto).
- Lista de sinais problemáticos identificados, na seção "Sinais problemáticos" do
  relatório.

---

*Plano completo: [`docs/libras-livre-poc-plano.md`](../../docs/libras-livre-poc-plano.md).
Arquitetura de longo prazo: [`docs/libras-livre-arquitetura.md`](../../docs/libras-livre-arquitetura.md).
Este scaffold é deliberadamente enxuto — qualquer
decisão de arquitetura mais ampla fica para depois que esta pergunta estiver
respondida.*

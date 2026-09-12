# `treino/` — o classificador de sinais

Esta pasta treina o modelo que reconhece os sinais. Consome os landmarks que
`../PoC/src/extract.py` produz e responde à única pergunta que importa para o
produto:

> **Qual a acurácia com uma pessoa que o modelo nunca viu?**

Os óculos são institucionais: atendem alguém novo a cada atendimento, sem
calibração. Por isso toda avaliação aqui deixa uma pessoa **inteira** fora do
treino (*leave-one-signer-out*), exatamente como o baseline DTW da PoC.

---

## O que está medido, e o que foi escolhido

| Arquitetura | LOSO | Parâmetros | Orçamento de treino |
|---|---|---|---|
| **ST-GCN + ossos + z recentrado** | **94,6% / 94,9%** | **0,47M** | 1.080 atualizações |
| ResNet-18 + imputação | 95,1% | 11,25M | 540 |
| ResNet-18 (controle da sessão de 10/09) | 93,0% | 11,25M | 270 |
| ST-GCN + ossos | 91,0% / 92,5% | 0,46M | 1.080 |
| ST-GCN (x, y apenas) | 72,1% | 0,46M | 1.080 |
| Baseline DTW 1-NN da PoC (10 sinais, 11 pessoas) | 70,0% | — | — |
| Literatura no MINDS-Libras, mesmo protocolo | 93–94% | — | — |

**O ST-GCN é o modelo de entrega**, decidido em 2026-09-11. Ele **empatou** com a
melhor ResNet — 94,6/94,9 contra 95,1 — usando **24× menos parâmetros**. Exportado,
dá ~1,9 MB em float32 e 0,47 MB em int8, contra 45 MB e 11,3 MB da ResNet. Para um
modelo que precisa virar `.tflite` no celular, empatar com 1/24 do tamanho decide.

Duas ressalvas que precisam acompanhar qualquer citação desses números:

1. **A variância entre execuções é de ~1,7 ponto.** Diferença menor que ~2 pontos é
   ruído, não resultado.
2. **É tudo vídeo de estúdio**, frontal e controlado. Teto otimista. Robustez a
   mudança de ponto de vista segue não medida — nenhuma base pública nossa tem
   vídeo fora do frontal de estúdio.

### O que fez o ST-GCN sair de 72% para 94%

Nada de dado novo: duas features derivadas dos landmarks que já existiam.

- **Ossos** (vetores entre pontos conectados no grafo do esqueleto): +18,9 pontos,
  em 8 de 8 rodadas.
- **z recentrado**: +2,1 pontos, confirmado em segunda semente.

Uma versão anterior deste README afirmava que o ST-GCN estava ~19 pontos atrás da
ResNet, com 0,739 e 0,446. **Esses números eram configuração, não arquitetura**: o
0,446 veio de hiperparâmetros de fine-tuning (~270 atualizações de peso) aplicados
a um modelo que precisa de orçamento de treino do zero, e o 0,739 ainda era sem
ossos e sem z. Ao comparar arquiteturas, confira quantas atualizações cada uma
recebeu. O histórico completo está em
[`../../docs/decisao-arquitetura-modelo.md`](../../docs/decisao-arquitetura-modelo.md).

---

## As duas representações

### ST-GCN — grafo do esqueleto

O modelo de entrega. Trata os 57 pontos como os **nós de um grafo** com a topologia
real do corpo (dedo ligado ao dedo, pulso ao cotovelo, cotovelo ao ombro), e
convolui ao mesmo tempo no espaço (vizinhos no grafo) e no tempo (frames
adjacentes). Consome coordenadas reamostradas para 64 frames; com `--ossos`, os
vetores de osso entram como canais adicionais.

Não é causal: é um modelo para sinais isolados já segmentados, não a rede de
streaming com atenção descrita na arquitetura de produto.

### Skeleton-DML + ResNet-18 — landmarks como imagem

A alternativa medida, e ainda a única exportável.

```
  .npy de landmarks            imagem Skeleton-DML            ResNet-18
  (T frames, 57 pontos, x/y)   (57 × 2·T/3 × 3) → 224²        (ImageNet)
        |                              |                          |
        +---- empilha tempo e ---------+                          v
              pontos como imagem                          sinal reconhecido
```

Com ~1.000 clipes, treinar uma rede recorrente do zero disputa com uma CNN que já
vem pré-treinada em milhões de imagens. **Skeleton-DML** é o truque que permite usar
essa CNN: empilha a matriz `pontos × frames` (x e y) como se fosse uma imagem RGB —
cada 3 frames consecutivos viram os 3 canais — e redimensiona para 224×224. De
quebra, resolve as durações diferentes sem padding nem máscara.

Detalhe contraintuitivo e medido: **modelo maior piora**. Na ablação de Alves et
al., ResNet-18 (0,93) bate ResNet-50 (0,90), EfficientNet-B6 (0,89) e MobileNetV4
(0,87). Com dataset pequeno, capacidade sobrando vira memorização.

Referências que motivaram essa escolha
([`../../docs/investigacao-expansao-dataset.md`](../../docs/investigacao-expansao-dataset.md),
Achado D):

| Trabalho | Dataset | Protocolo | Modelo | Resultado |
|---|---|---|---|---|
| dos Santos et al. 2025 | MINDS-Libras | LOSO | Skeleton-DML + ResNet-18 | 0,94 |
| Alves et al. 2024 | MINDS-Libras | LOSO | Skeleton-DML + ResNet-18 | 0,93 |

---

## Arquivos

| Arquivo | Papel |
|---|---|
| `dados.py` | carga dos `.npy`, partições leave-one-signer-out, imputação de lacunas de mão |
| `representacao.py` | landmarks → imagem Skeleton-DML; augmentação (rotação, zoom, translação, espelhamento) |
| `modelo.py` | ResNet-18 ImageNet com a cabeça trocada; salvar/carregar checkpoint |
| `gcn.py` | ST-GCN sobre o grafo de 57 pontos — **o modelo de entrega** |
| `treinar.py` | laço LOSO, relatório e checkpoint final |
| `pretreinar.py` | pré-treino num corpus grande (V-LIBRASIL), sem medir acurácia |
| `contrastivo.py` | perda SupCon e amostrador P×K, usados pelo pré-treino contrastivo |
| `entrada_poc.py` | leitura dos landmarks MINDS empacotados, para rodar em Kaggle/Colab |
| `exportar.py` | PyTorch → `.tflite` mais o JSON de contrato |
| `selftest.py` | valida o pipeline inteiro com dados sintéticos, em segundos |
| `test_entrada_poc.py`, `test_export_contrato.py`, `test_proveniencia.py` | regressões |
| `notebook_gpu.ipynb` | pré-treino + fine-tuning em GPU (Colab/Kaggle) |
| `notebook_gcn_variantes.ipynb` | ablações do ST-GCN (movimento, adjacência adaptativa, kernel) |
| `notebook_poc_3d.ipynb` | a PoC da terceira coordenada |

---

## Como rodar

```bash
source ../PoC/.venv311/bin/activate

python selftest.py                        # 1º: valida o encanamento (segundos)
python treinar.py --epocas 5 --folds 1    # 2º: uma rodada curta, para ver de pé

# 3º: LOSO completo da configuração de entrega (8 rodadas)
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado

# 4º: treina com todas as pessoas e salva o checkpoint
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado --final
```

Para a ResNet-18, basta omitir `--arquitetura` (é o padrão). Saídas em
`resultados-gcn/` ou `resultados-resnet/`, ajustável por `--saida`: `relatorio.md`
(acurácia por rodada e por sinal, pares confundidos), `matriz_confusao.npy` e
`modelo_final.pt`.

`modelo.carregar(caminho)` reconstrói a arquitetura salva sem baixar pesos ImageNet.
Checkpoints novos guardam a configuração do GCN (largura, canais, nós, dropout);
os antigos continuam aceitos.

> Os relatórios versionados em `resultados-gcn/` e `resultados-resnet/` são de
> execuções **anteriores** à configuração de entrega — `resultados-gcn/relatorio.md`
> registra os 44,6% da sessão de 09/09. Os números atuais estão em
> [`../../docs/decisao-arquitetura-modelo.md`](../../docs/decisao-arquitetura-modelo.md).

### Pré-treino

```bash
python pretreinar.py --auditar --corpus <dir>      # auditoria obrigatória, sem treinar
python pretreinar.py --corpus <dir> --objetivo contrastivo --pessoa-val V03
python treinar.py --fontes minds --inicializar <checkpoint>
```

O primeiro pré-treino por classificação **falhou** — validação em 0,2% contra 0,07%
de chance — e o motivo é estrutural: 1.353 classes com 3 clipes cada, um por
articulador, é tarefa quase não-aprendível. A mesma estrutura, porém, é ideal para
aprendizado **contrastivo**: três execuções da mesma palavra por três pessoas
diferentes formam exatamente o par que ensina *invariância a sinalizante*, que é o
requisito central do produto.

Nenhum número do pré-treino vai para lugar nenhum: o produto dele são os pesos. A
acurácia que vale sai do `treinar.py`, sobre o MINDS, com uma pessoa inteira fora.
Ver [`../../docs/protocolo-pretreino.md`](../../docs/protocolo-pretreino.md).

### Em GPU

A extração de landmarks é CPU e não acelera em GPU — fica na máquina local. O
treino vai para o Colab/Kaggle por `notebook_gpu.ipynb`, que encadeia auditoria
obrigatória, pré-treino contrastivo e fine-tuning no MINDS. A flag de auditoria e os
sidecars de proveniência são pré-requisitos: sem eles, o fluxo aborta.

São necessários dois pacotes **privados**: `landmarks-minds.tar.gz` e
`landmarks-vlibrasil.tar.gz`, este último incluindo cada `.npy` e seu
`*.npy.proveniencia.json`. A V-LIBRASIL é CC BY-NC-ND; landmarks não eliminam as
restrições da fonte. Não publique dados, sidecars, pacotes ou checkpoints derivados.

### Custo em CPU (12 núcleos, sem GPU)

~20 imagens/s no treino da ResNet → ~35 s por época, ~17 min por rodada, **~2,3 h**
para as 8 rodadas da LOSO. `--threads` deixa folga de núcleos para a máquina seguir
usável (padrão 10 de 12).

---

## Exportação para TFLite (`exportar.py`)

O caminho PyTorch → `.tflite` existe e está validado para a **ResNet-18**.

> **O ST-GCN ainda não tem export, e isso é o principal bloqueio do projeto.**
> `exportar.py` só constrói o grafo da cabeça Skeleton-DML. Portar o ST-GCN exige
> calcular os **ossos dentro do grafo** — hoje isso acontece em Python, no
> `DatasetSinais`. É trabalho novo, não uma flag.

```bash
python exportar.py --smoke                      # valida o toolchain, sem checkpoint
python exportar.py --checkpoint resultados-resnet/modelo_final.pt \
                   --saida ../models/sinal_classifier.tflite
python exportar.py --checkpoint ... --quantizacao float16
```

Requer `pip install "torch<2.10" ai-edge-torch` — com torch mais novo o pip resolve
`ai-edge-torch` para a 0.2.0, que depende de `torch_xla` e quebra com
`undefined symbol`.

### O contrato de entrada é `landmarks`, não imagem

O `.tflite` recebe `(1, T, P, 2)`, com **P derivado do checkpoint** (mapa ordenado de
pose mais 42 pontos de mãos; atualmente 15 + 21 + 21 = **57**), e devolve os logits:
a montagem do Skeleton-DML vai **dentro do grafo**. O modo `--modo imagem` existe,
mas joga para o app a tarefa de reproduzir transposição, empilhamento de 3 frames por
canal, clip em ±2,0, mapeamento para [0,1] e resize; errar qualquer um desses passos
não gera erro, só piora a classificação em silêncio.

`--pontos` é opcional e serve como conferência: se discordar do mapa do checkpoint, o
export aborta antes de converter. Checkpoints antigos com mapa de sete pontos de pose
continuam com 49, sem reinterpretação. Sem mapa, o export exige `--pontos` explícito
e marca o layout como **não verificado** — contagem não demonstra ordem.

O JSON registra a pose em uma lista ordenada, os índices das mãos, as coordenadas, o
limite de escala e o contrato temporal. O shape e o dtype efetivos do interpretador
precisam concordar com esse contrato, e a saída precisa ter um logit por rótulo.
Arquivos só devem ser entregues se o comando terminar com sucesso. O lado do app está
em [contrato de integração no companion](../../mobile-app-companion/README.md#5-contrato-do-classificador-tflite).

**T é fixo no grafo exportado** (padrão 96 frames). No treino T varia por clipe (70 a
232) e o resize para 224 absorve; na exportação o app precisa entregar exatamente T
frames. O contrato contém `temporal.dinamico=false`, `reamostragem_embutida=false` e
`frames_fixos`. Não há padding, recorte de sinais nem reamostragem embutidos no grafo;
normalização e imputação também ficam de fora. Escolher uma janela de 96 frames não
equivale a validar segmentação — a política de adaptação temporal precisa ser medida
com dado real antes do deploy.

**Exportação 3D não suportada:** checkpoints com `com_z`, `z_recentrado` ou limite de
z são recusados nos dois modos, e a cabeça rejeita diretamente entrada com três
coordenadas em vez de cortar z em silêncio. Isto vale inclusive para a configuração de
entrega do ST-GCN, que usa z — mais um item do trabalho de export pendente.

### Paridade medida com `--smoke`

Pesos aleatórios, 20 classes, comparando cada saída contra o PyTorch no mesmo tensor:

| `--quantizacao` | Tamanho | Tensores de peso | Maior diferença de logit | Top-1 discordante |
|---|---|---|---|---|
| `nenhuma` (padrão) | 45,0 MB | float32 | 1,8e-07 | 0/8 |
| `float16` | 22,5 MB | 22 em float16 | 4,1e-04 | 0/8 |
| `dinamica` | 11,3 MB | 22 em int8 | 5,5e-03 | 0/8 |

`dinamica` é int8 **só nos pesos** (ativações em float), por isso não precisa de
dataset de calibração. Os tamanhos batem com a aritmética de 11,2M parâmetros.

**Tamanho é fato; efeito na acurácia não foi medido** — `--smoke` usa pesos
aleatórios. Antes de mandar um modelo quantizado para o aparelho, rode a LOSO com ele.
Latência no aparelho também segue não medida. Os números desta tabela pertencem ao
smoke de commits anteriores, não a uma medição nova com 57 pontos.

### Duas armadilhas barradas no código

Ambas do tipo "converte, roda e classifica errado sem avisar":

1. **`--backend onnx` com `--modo landmarks`** é recusado. O `onnx2tf` converte tudo
   para NHWC e elimina o `permute` da cabeça achando que é troca de layout — a ResNet
   passa a convoluir nos eixos trocados (medido: logits divergem 3,6e-01, top-1 muda).
   A ResNet sozinha converte bem por ONNX (4,8e-07); o defeito é a cola.
2. **Flag de quantização ignorada.** Pedir float16 pela chave aninhada
   `target_spec.supported_types` não surte efeito e devolve int8 dinâmico. Por isso
   `_conferir_precisao` abre o arquivo gerado e confere os tipos dos tensores contra o
   que foi pedido.

---

## O que o self-test garante

Além das formas e faixas, ele roda um **controle negativo**: com rótulos aleatórios,
ResNet e GCN não devem obter acurácia alta. Se subir, é sinal de vazamento entre
treino e teste — o erro mais caro possível aqui, porque produz um número bonito e
falso.

Também verifica salvar e carregar GCN e ResNet, preservação das predições e dos
metadados, variantes do GCN e compatibilidade com checkpoints antigos, sem downloads
durante o carregamento.

---

## Dataset

20 sinais do MINDS-Libras, 8 pessoas, 5 repetições (800 clipes). A V-LIBRASIL fica de
fora do treino por padrão (`--fontes minds`): ela tem sempre os mesmos 3 articuladores,
então serve melhor como teste de domínio diferente do que como treino. Ver
[`../datasets/README.md`](../datasets/README.md) e
[`../../docs/vocabulario-mvp-proposta.md`](../../docs/vocabulario-mvp-proposta.md).

## Próximo passo previsto

1. **Export do ST-GCN** — o bloqueio entre a decisão de arquitetura e o aparelho.
2. **Fine-tuning no vocabulário de atendimento**, com vídeos próprios (coleta com a
   Associação de Surdos de Goiânia). O `--final` salva o checkpoint pré-treinado
   justamente para isso: se o fine-tuning não render, o modelo geral continua de pé.
   `modelo.construir(congelar_ate=N)` existe para essa etapa, quando houver pouco dado
   por classe.

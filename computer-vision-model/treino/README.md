# `treino/` — o classificador de sinais (Skeleton-DML + ResNet-18)

Esta pasta treina o modelo que reconhece os sinais. Ela consome os landmarks que
`../PoC/src/extract.py` produz e responde a única pergunta que importa para o
produto:

> **Qual a acurácia com uma pessoa que o modelo nunca viu?**

Os óculos são institucionais: atendem alguém novo a cada atendimento, sem
calibração. Por isso toda avaliação aqui deixa uma pessoa **inteira** de fora do
treino (*leave-one-signer-out*), exatamente como o baseline DTW da PoC.

---

## A ideia em uma imagem

```
  .npy de landmarks            imagem Skeleton-DML            ResNet-18
  (T frames, 57 pontos, x/y)   (57 × 2·T/3 × 3) → 224²        (ImageNet)
        │                              │                          │
        └──── empilha tempo e ─────────┘                          ▼
              pontos como imagem                          sinal reconhecido
```

## Por que imagem, e não uma rede recorrente

O plano original da PoC (§5.5) previa um GRU sobre a sequência de landmarks. A
literatura de reconhecimento de Libras aponta para outro caminho, e a diferença é
grande — ver [`../../docs/investigacao-expansao-dataset.md`](../../docs/investigacao-expansao-dataset.md), Achado D:

| Trabalho | Dataset | Protocolo | Modelo | Resultado |
|---|---|---|---|---|
| dos Santos et al. 2025 | MINDS-Libras | LOSO | Skeleton-DML + ResNet-18 | **0,94** |
| Alves et al. 2024 | MINDS-Libras | LOSO | Skeleton-DML + ResNet-18 | **0,93** |
| *nossa PoC* | MINDS + V-LIBRASIL | LOSO | DTW 1-NN | *0,70* |

### O que NÓS medimos, e em qual arquitetura

Os 0,93-0,94 acima são da **ResNet-18**, na literatura e reproduzidos aqui. Não são
do ST-GCN, e a diferença é grande demais para a atribuição errada passar batida:

| Arquitetura | LOSO medido aqui | Parâmetros | Onde |
|---|---|---|---|
| **Skeleton-DML + ResNet-18** | **0,934 / 0,935 / 0,918** | 11,2M | `resultados-resnet/relatorio.md` |
| ST-GCN (orçamento de treino justo) | 0,739 | 0,46M | [`decisao-arquitetura-modelo.md`](../../docs/decisao-arquitetura-modelo.md) |
| ST-GCN (config de fine-tuning) | 0,446 | 0,46M | subtreinado — não é veredito de arquitetura |

**A ResNet-18 é o modelo do MVP.** O ST-GCN está ~19 pontos atrás e existe para ser
medido, não como candidato de entrega.

Sobre o argumento "o GCN é o único que cabe no celular": ele economiza 24× em
parâmetros, mas isso só decide se o tamanho for o gargalo. Por aritmética, 11,2M
parâmetros dão ~11 MB em `.tflite` int8 (~22 MB em float16) — ordem de grandeza
comum em app. **O que NÃO está medido, para nenhuma das duas: se a conversão para
TFLite funciona e qual a latência no aparelho.** Nada aqui autoriza afirmar que a
ResNet roda em tempo real nos óculos; autoriza dizer que descartá-la por tamanho,
sem medir, é decidir cedo demais.

⚠️ **Variância entre execuções: ~1,7 ponto.** Diferença menor que ~2 pontos é ruído.

Com ~1.000 clipes, treinar uma recorrente do zero disputa com uma CNN que já vem
pré-treinada em milhões de imagens. **Skeleton-DML** é o truque que permite usar
essa CNN: empilha a matriz `pontos × frames` (x e y) como se fosse uma imagem RGB
— cada 3 frames consecutivos viram os 3 canais — e redimensiona para 224×224. De
quebra, resolve o problema das durações diferentes sem padding nem máscara.

Detalhe contraintuitivo, e medido: **modelo maior piora**. Na ablação de Alves et
al., ResNet-18 (0,93) bate ResNet-50 (0,90), EfficientNet-B6 (0,89) e MobileNetV4
(0,87). Com dataset pequeno, capacidade sobrando vira memorização — e a menor é
justamente a que cabe no celular depois.

## Arquivos

| Arquivo | Papel |
|---|---|
| `representacao.py` | landmarks → imagem Skeleton-DML; augmentação (rotação, zoom, translação, espelhamento) |
| `dados.py` | carga dos `.npy`, partições leave-one-signer-out |
| `modelo.py` | ResNet-18 ImageNet com a cabeça trocada; salvar/carregar checkpoint |
| `gcn.py` | alternativa ST-GCN (0,739 — **não é o modelo do MVP**), sobre o grafo de 57 pontos |
| `treinar.py` | laço LOSO, relatório e checkpoint final |
| `selftest.py` | valida o pipeline inteiro com dados sintéticos, em segundos |

## Como rodar

```bash
source ../PoC/.venv311/bin/activate

python selftest.py                       # 1º: valida o encanamento (segundos)
python treinar.py --epocas 5 --folds 1    # 2º: uma rodada curta, para ver de pé
python treinar.py                        # 3º: LOSO completo (8 rodadas)
python treinar.py --final                # 4º: treina com todos e salva o checkpoint

# Alternativa experimental, com as mesmas partições por pessoa:
python treinar.py --arquitetura gcn
python treinar.py --arquitetura gcn --final
```

Saídas em `resultados-resnet/` ou `resultados-gcn/` (ajustável por `--saida`):
`relatorio.md` (acurácia por rodada e por sinal, pares
confundidos), `matriz_confusao.npy` e `modelo_final.pt`.

`modelo.carregar(caminho)` reconstrói a arquitetura salva, sem baixar pesos
ImageNet. Checkpoints novos guardam também a configuração do GCN (largura,
canais, nós e dropout); os checkpoints antigos do treino continuam aceitos.

O ST-GCN consome coordenadas x/y reamostradas para 64 frames; com `--ossos`,
acrescenta os vetores de osso aos canais (2 → 4). Não é causal: é um experimento
para sinais isolados, não a rede de streaming com atenção descrita na arquitetura
de produto. Robustez a mudanças de ponto de vista continua não medida — nenhuma
base pública nossa tem vídeo fora do frontal de estúdio.

## Exportação para TFLite (`exportar.py`)

O caminho PyTorch → `.tflite` existe e está validado para a **ResNet-18** (o modelo
do MVP, pelos números da tabela acima). O ST-GCN ainda não tem export.

```bash
python exportar.py --checkpoint resultados-resnet/modelo_final.pt \
                   --saida ../models/sinal_classifier.tflite
python exportar.py --smoke                      # valida o toolchain, sem checkpoint
python exportar.py --checkpoint ... --quantizacao float16
```

Requer `pip install "torch<2.10" ai-edge-torch` — com torch mais novo o pip resolve
`ai-edge-torch` para a 0.2.0, que depende de `torch_xla` e quebra com
`undefined symbol`.

**O contrato de entrada é `landmarks`, não imagem.** O `.tflite` recebe
`(1, T, P, 2)`, com **P derivado do checkpoint** (mapa ordenado de pose + 42 pontos
de mãos; atualmente 15 + 21 + 21 = **57**) — e devolve
os logits: a montagem do Skeleton-DML vai **dentro do grafo**. O modo `--modo imagem`
existe, mas joga para o app a tarefa de reproduzir transposição, empilhamento de 3
frames por canal, clip em ±2,0, mapeamento para [0,1] e resize; errar qualquer um
desses passos não gera erro, só piora a classificação em silêncio.

`--pontos` é opcional e serve como conferência: se discordar do mapa do checkpoint,
o export aborta antes de converter. Checkpoints antigos com mapa de sete pontos de
pose continuam com 49, sem reinterpretá-los pela configuração atual. Sem mapa,
o export exige `--pontos` explícito e marca o layout como **não verificado**, pois
contagem não demonstra ordem. Apenas `--smoke` usa o YAML atual como referência.

O JSON registra a pose **em uma lista ordenada**, os índices das mãos, as coordenadas,
o limite de escala e o contrato temporal. O shape/dtype efetivos do interpretador
TFLite precisam concordar com esse contrato e a saída precisa ter um logit por rótulo.
Arquivos só devem ser entregues se o comando terminar com sucesso.

**Exportação 3D ainda não suportada:** checkpoints com `com_z`, `z_recentrado`
ou limite de z são recusados nos dois modos. A cabeça também rejeita diretamente
entrada com três coordenadas; não corta z silenciosamente. Os três canais da imagem
ResNet não permitem deduzir quantas coordenadas havia no treino. Quando a PoC 3D
for incorporada, será necessário portar sua escala de z e pré-processamento e
validar novamente a paridade. `normalizacao.usar_z` do DTW não decide isso.

Medido com `--smoke` (pesos aleatórios, 20 classes), comparando cada saída contra o
PyTorch no mesmo tensor de entrada:

| `--quantizacao` | Tamanho | Tensores de peso | Maior diferença de logit | Top-1 discordante |
|---|---|---|---|---|
| `nenhuma` (padrão) | 45,0 MB | float32 | 1,8e-07 | 0/8 |
| `float16` | 22,5 MB | 22 em float16 | 4,1e-04 | 0/8 |
| `dinamica` | 11,3 MB | 22 em int8 | 5,5e-03 | 0/8 |

`dinamica` é int8 **só nos pesos** (ativações em float), por isso não precisa de
dataset de calibração; a quantização inteira completa precisa, e por isso ficou de
fora. Os tamanhos batem com a aritmética de 11,2M parâmetros.

⚠️ **Tamanho é fato; efeito na acurácia não foi medido.** `--smoke` usa pesos
aleatórios. Antes de mandar um modelo quantizado para o aparelho, rode a LOSO com
ele. Latência no aparelho também segue não medida.

⚠️ **T é fixo no grafo exportado** (padrão 96 frames). No treino T varia por clipe
(70 a 232) e o resize para 224 absorve; na exportação o app precisa entregar
exatamente T frames. Se isso mexe na acurácia, é medição com dado real.

O contrato contém `temporal.dinamico=false`, `reamostragem_embutida=false` e
`frames_fixos`. O app deve conferir o shape do tensor contra o JSON e recusar
amostras com T/P/D incompatíveis. **Não há padding, recorte de sinais nem
reamostragem de clipes embutidos no grafo.** Escolher uma janela de 96 frames não
equivale a validar segmentação de sinais; a política de adaptação temporal precisa
ser medida antes do deploy. Normalização e imputação também ficam fora do grafo.
Veja o [contrato de integração no companion](../../mobile-app-companion/README.md#contrato-do-classificador-tflite).

As regressões em `test_export_contrato.py` exercitam `main()` com checkpoints reais
e backend **simulado**: defaults, legado 49, conflitos, ordem, z, T e serialização
do JSON. A exportação da cabeça via `torch.export` e sua paridade NumPy/PyTorch
são verificadas separadamente. Isso não substitui nova conversão TFLite nem avaliação
com dados reais. Os números da tabela abaixo pertencem ao smoke dos commits anteriores,
não a uma nova medição com 57 pontos.

Duas armadilhas encontradas e barradas no código, ambas do tipo "converte, roda e
classifica errado sem avisar":

1. **`--backend onnx` com `--modo landmarks`** é recusado. O `onnx2tf` converte tudo
   para NHWC e elimina o `permute` da cabeça achando que é troca de layout — a ResNet
   passa a convoluir nos eixos trocados (medido: logits divergem 3,6e-01, top-1
   muda). A ResNet sozinha converte bem por ONNX (4,8e-07); o defeito é a cola.
2. **Flag de quantização ignorada.** Pedir float16 pela chave aninhada
   `target_spec.supported_types` não surte efeito e devolve int8 dinâmico. Por isso
   `_conferir_precisao` abre o arquivo gerado e confere os tipos dos tensores contra
   o que foi pedido.

### Custo nesta máquina (CPU, 12 núcleos, sem GPU)

~20 imagens/s no treino → ~35 s por época, ~17 min por rodada, **~2,3 h** para as
8 rodadas da LOSO. `--threads` deixa folga de núcleos para a máquina seguir
usável (padrão 10 de 12).

## O que o self-test garante

Além das formas e faixas, ele roda um **controle negativo**: com rótulos
aleatórios, ResNet e GCN não devem obter acurácia alta. Se subir, é sinal de vazamento
entre treino e teste — o erro mais caro possível aqui, porque produz um número
bonito e falso.

Também verifica salvar/carregar GCN e ResNet, preservação das predições e dos
metadados, variantes do GCN e compatibilidade com checkpoints antigos, sem
downloads durante o carregamento.

## Dataset

20 sinais do MINDS-Libras, 8 pessoas, 5 repetições (800 clipes). A V-LIBRASIL
fica de fora do treino por padrão (`--fontes minds`): ela tem sempre os mesmos 3
articuladores, então serve melhor como **teste de domínio diferente** do que como
treino. Ver [`../datasets/README.md`](../datasets/README.md) e
[`../../docs/vocabulario-mvp-proposta.md`](../../docs/vocabulario-mvp-proposta.md).

## Próximo passo previsto

Fine-tuning no vocabulário de atendimento, com os vídeos próprios (coleta com a
Associação de Surdos de Goiânia). O `--final` salva o checkpoint pré-treinado
justamente para isso: se o fine-tuning não render, o modelo geral continua de pé.
`modelo.construir(congelar_ate=N)` existe para essa etapa, quando houver pouco
dado por classe.

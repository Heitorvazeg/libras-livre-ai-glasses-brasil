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
| `treinar.py` | laço LOSO, relatório e checkpoint final |
| `selftest.py` | valida o pipeline inteiro com dados sintéticos, em segundos |

## Como rodar

```bash
source ../PoC/.venv311/bin/activate

python selftest.py                       # 1º: valida o encanamento (segundos)
python treinar.py --epocas 5 --folds 1    # 2º: uma rodada curta, para ver de pé
python treinar.py                        # 3º: LOSO completo (8 rodadas)
python treinar.py --final                # 4º: treina com todos e salva o checkpoint
```

Saídas em `resultados/`: `relatorio.md` (acurácia por rodada e por sinal, pares
confundidos), `matriz_confusao.npy` e `modelo_final.pt`.

### Custo nesta máquina (CPU, 12 núcleos, sem GPU)

~20 imagens/s no treino → ~35 s por época, ~17 min por rodada, **~2,3 h** para as
8 rodadas da LOSO. `--threads` deixa folga de núcleos para a máquina seguir
usável (padrão 10 de 12).

## O que o self-test garante

Além das formas e faixas, ele roda um **controle negativo**: com rótulos
aleatórios, a acurácia tem de ficar na chance. Se subir, é sinal de vazamento
entre treino e teste — o erro mais caro possível aqui, porque produz um número
bonito e falso.

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

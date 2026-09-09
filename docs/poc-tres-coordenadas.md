# PoC — a terceira coordenada ajuda?

**Branch:** `poc/tres-coordenadas`, a partir de `claude/libras-detection-model-53kd30`
**Notebook:** [`computer-vision-model/treino/notebook_poc_3d.ipynb`](../computer-vision-model/treino/notebook_poc_3d.ipynb)
**Status:** implementado e testado; **sem resultado ainda** — falta rodar no Colab.

---

## A pergunta

`treino/dados.py` descarta a terceira coordenada dos landmarks (`arr[:, :, :2]`) desde o
primeiro commit do pipeline. A justificativa existe e é medida — mas é de **outro modelo**:

> `PoC/config.yaml`: MEDIDO (430 clipes): desligar o z melhora ~4-6 pontos
> (**64,0% → 68,7%**), porque o z da mão é relativo ao punho e o de pose ao quadril.

Isso foi medido no **DTW 1-NN**. O DTW soma distâncias cruas: um canal com escala
incoerente corrompe a métrica sem defesa nenhuma. ResNet e GCN têm peso aprendido e podem
ponderar ou ignorar o canal. **A conclusão do DTW não transfere em nenhuma das duas
direções** — nem "o z piora" (não medido nestes modelos), nem "a rede aprende a usar"
(também não medido).

## Por que agora

`docs/extracao-landmarks-plano.md` §3 item 2 decidiu que o app vai extrair **3 canais**,
explicitamente contra o dado da PoC. Como o modelo é construído com `canais_ent=2`, isso
não é diferença de acurácia — é **erro de shape** na integração. Os dois lados têm de
concordar, e a decisão pertence a `treino/`, porque é aqui que dá para medir.

## O defeito que o experimento tem de isolar

Em `PoC/src/extract.py`:

```python
origem = (a + b) / 2.0                  # 3-vetor: inclui o z do meio dos ombros
escala = norm(a[:2] - b[:2])            # distância entre ombros, só x,y
blocos = [(pose_px - origem) / escala]  # pose: z centrado no ombro — coerente
blocos.append((mao - origem) / escala)  # mão: z JÁ é relativo ao punho, e ainda
                                        # leva o z do ombro subtraído
```

O z de mão do MediaPipe já vem relativo ao punho daquela mão. Subtrair dele o z do ombro
mistura dois referenciais: o valor resultante não é profundidade em relação a nada.

Medido em 150 clipes MINDS (valores não-nulos, unidades de ombro):

| | mediana | p95 | p99,9 | máx |
|---|---|---|---|---|
| x, y | 0,44 | 1,35 | 1,63 | 2,05 |
| z cru | 0,74 | 2,15 | 4,89 | 5,47 |
| **z recentrado** | **0,18** | 2,15 | 4,90 | 5,47 |

A mediana caindo de 0,74 para 0,18 diz que **o offset espúrio era a maior parte do valor
típico do z**. As caudas não mudam porque vêm do z de pose, que não é recentrado.

**Consequência para o desenho:** testar só "z ligado vs desligado" mediria o z com o
defeito embutido, provavelmente reproduziria o resultado do DTW, e concluiríamos "o z não
ajuda" quando o correto seria "o z quebrado não ajuda".

## As três variantes

| Variante | Flags | O que isola |
|---|---|---|
| **A** | *(nenhuma)* | controle, nas mesmas condições das outras duas |
| **B** | `--com-z` | o z como está no `.npy` hoje |
| **C** | `--com-z --z-recentrado` | o z das mãos devolvido ao referencial do punho |

O controle A é obrigatório e **não** pode ser substituído pelo 93,4% histórico: aquele
número veio de outra máquina, outro batch, sem semente fixa.

### Semente fixa é parte do desenho, não detalhe

A mesma configuração oscila **~1,7 pp** entre execuções. Com efeito esperado de poucos
pontos, rodar cada variante uma vez sem semente mede ruído. `--semente` (novo em
`treinar.py`) faz as três partirem dos mesmos pesos e da mesma augmentação; o que sobra na
diferença é a representação. É comparação pareada e custa uma flag em vez de N execuções
por variante.

Semeia-se **por rodada** (`semente + i`), não uma vez só: fixar tudo no mesmo valor faria
as 8 partições compartilharem a inicialização, reduzindo a variância pelo motivo errado.

## Como ler o resultado

| Se… | Então |
|---|---|
| B e C empatam com A | o z não carrega informação aproveitável em vídeo frontal. O app extrai 2 canais e `extracao-landmarks-plano.md` §3 muda. |
| C ganha, B não | o problema era a normalização, não o z. Corrigir `extract.py` vira trabalho real, e o app extrai 3 canais **já recentrados**. |
| B e C ganham igual | o offset do ombro não atrapalhava tanto; o z cru basta. |

Diferença de média abaixo de ~1,5 pp é empate. Olhar a **consistência entre folds**: ganho
real aparece na maioria dos 8, não como +6 num fold e −4 noutro.

⚠️ **Nada aqui mede robustez a ângulo de câmera**, que é a motivação de fundo para querer
profundidade. Todas as bases são estúdio frontal. Um ganho aqui é ganho em vídeo frontal —
não licença para afirmar que o z resolve o problema dos óculos.

## O que mudou no código

| Arquivo | Mudança |
|---|---|
| `treino/dados.py` | `carregar(com_z=, z_recentrado=)`; `recentrar_z()`; `maos_ausentes` passa a decidir só por x,y |
| `treino/representacao.py` | `para_imagem` genérica em D dims; `LIMITE_Z = 5.0` separado |
| `treino/gcn.py` | `com_ossos` genérica em D dims (4 canais com x,y; 6 com x,y,z) |
| `treino/treinar.py` | flags `--com-z`, `--z-recentrado`, `--semente`; `canais_gcn()` centraliza a conta |
| `treino/selftest.py` | `teste_terceira_coordenada` |
| `treino/notebook_poc_3d.ipynb` | o experimento, em Colab |

**Nenhum `.npy` precisa ser reextraído** — a extração sempre gravou 3 dims. São horas de
MediaPipe economizadas, e é por isso que `extract.py` foi escrito assim.

### O `LIMITE_Z` separado

`para_imagem` mapeia as coordenadas para [0,1] cortando em `LIMITE = 2.0`, calibrado para
x,y. O z tem faixa quase 3× maior: 2,0 cortaria **mais de 5%** dos valores, e mediríamos a
saturação em vez do z. `LIMITE_Z = 5.0` mantém a mesma proporção de corte que x,y (~0,1%).

O z entra como **bloco de colunas**, ao lado de x e y — não como quarto canal da imagem.
Os 3 canais RGB são 3 frames consecutivos; mexer neles mudaria o significado da
representação. A imagem fica 50% mais larga e o redimensionamento para 224×224 absorve,
exatamente como já absorve clipes de durações diferentes.

## Como rodar

1. Abrir o notebook no Colab, **GPU ligada**.
2. A célula de ambiente clona `poc/tres-coordenadas` — a branch precisa estar **no
   GitHub**, senão o clone falha.
3. Subir `landmarks-minds.tar.gz` (só esse; esta PoC não pré-treina).
4. Rodar as três variantes (~minutos cada em T4) e a célula de comparação.
5. Célula opcional repete no ST-GCN, incluindo `--ossos`.

V-LIBRASIL é **CC BY-NC-ND**: não publicar dados, sidecars, pacotes ou checkpoints
derivados. Aqui só entra MINDS, mas a regra vale para os artefatos baixados.

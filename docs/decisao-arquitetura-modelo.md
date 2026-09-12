# Decisão de arquitetura do modelo de reconhecimento

**Status:** aberto para discussão do time (Walisson · Heitor · Arthur).
**Prazo que condiciona a decisão:** entrega do hackathon em 16/09/2026.

---

> ## ⚠️ REVISÃO DE 2026-09-11 — O ST-GCN ALCANÇOU A RESNET, COM 24× MENOS PARÂMETROS
>
> Tudo abaixo da seção 1 foi escrito quando o ST-GCN media 44,6% e a ResNet 93,4%.
> **Esses 44,6% eram configuração, não arquitetura**, e a distância desapareceu quando o
> modelo recebeu a representação certa.
>
> | Modelo | LOSO | Parâmetros | Orçamento |
> |---|---|---|---|
> | **ResNet-18 + imputação** | **95,1%** | 11,25M | 540 atualizações |
> | **ST-GCN + ossos + z** | **94,6% / 94,9%** | **0,47M** | 1.080 |
> | ResNet-18 (controle da sessão de 10/09) | 93,0% | 11,25M | 270 |
> | ST-GCN + ossos | 91,0% / 92,5% | 0,46M | 1.080 |
> | ST-GCN x,y | 72,1% | 0,46M | 1.080 |
>
> ### Uma versão anterior desta seção dizia que o GCN PASSOU a ResNet. Estava errado.
>
> A comparação foi feita contra a ResNet de 93,0% — o controle daquela sessão — ignorando
> que **este mesmo repositório tem uma ResNet em 95,1%** (`resultados-resnet-imputado/`),
> listada duas tabelas adiante no `protocolo-treinamento.md`. O GCN está **0,2-0,5 pp
> abaixo** dela, não acima. Registrado porque o erro é instrutivo: o número escolhido foi o
> que confirmava a hipótese preferida.
>
> ### O que o dado sustenta
>
> **Empate, com 24× menos parâmetros.** GCN 94,6/94,9 contra ResNet 95,1 é diferença
> dentro do ruído — e o piso de reprodutibilidade medido aqui é 1,5 pp (a mesma config com
> a mesma semente deu 91,0% e 92,5% em sessões diferentes). Para um modelo que precisa
> caber em `.tflite`, empatar com 1/24 do tamanho é o resultado que importa.
>
> **O orçamento NÃO é pareado, e isso pesa contra o GCN — ou a favor.** O GCN recebe 1.080
> atualizações de peso; a ResNet, 270 ou 540. A §6 deste documento registra que a ResNet
> **não convergiu** (melhor época foi a 30 de 30 em 4 das 8 rodadas). Só mudar o batch da
> ResNet de 64 para 32 moveu 93,0 → 95,1 = **+2,1 pp**, mais que a margem que se quis
> reivindicar. **Antes de qualquer veredito, rodar a ResNet com orçamento equivalente.**
>
> ### Ganhos de representação, medidos
>
> 1. **Ossos** (vetores nó→pai): **+18,9 pp**, 8 de 8 folds, por +612 parâmetros.
>    ⚠️ Medido ANTES do conserto do pareamento de RNG (commit `d48b313`); falta uma
>    rodada `x,y` pós-conserto para essa margem ter régua única.
> 2. **z recentrado**: **+2,1 pp**, confirmado numa segunda semente (+2,4), **com o
>    controle rodando junto**. Ressalva: +2,1 é próximo do valor esperado do máximo de 5
>    sorteios de ruído com dp 1,7 (≈1,98), e a confirmação usa a mesma partição de 8
>    pessoas.
>
> **O z ajuda o GCN e atrapalha a ResNet.** Comparando o mesmo tratamento (z recentrado)
> nas duas: **+2,1 pp no GCN** contra **−4,2 pp na ResNet** (2 de 8 folds acima). A decisão
> herdada da PoC do DTW, de descartar o z, estava certa para a arquitetura errada.
>
> **O que NÃO ajudou:** adjacência adaptativa (−2,6). Kernel temporal 9→5 empatou (−0,3)
> com **35% menos parâmetros** — combinação com o z ainda em teste.
> ⚠️ **Movimento (−0,6) precisa ser refeito:** a máscara de validade estava inerte durante
> o treino (a augmentação somava ruído e o bloco de mão ausente deixava de ser zero), então
> o número mediu uma implementação quebrada, não a feature. Corrigido em 11/09.
>
> **Os relatórios destas execuções ainda não estão no repositório** — vivem num `.zip`
> local. Pelo critério que este projeto aplica ao "GCN em 95,6%" de terceiros, estes
> números são **não verificados** até serem commitados.

Este documento existe porque há **duas arquiteturas candidatas** e elas não estão no
mesmo estágio de evidência: uma já foi medida no nosso dataset, a outra é a que está
desenhada no diagrama do sistema. O objetivo aqui não é declarar uma vencedora — é
separar o que sabemos do que supomos, para que a escolha seja discutida com o mesmo
material na mão.

---

## 1. As candidatas

| | **Skeleton-DML + ResNet-18** | **ST-GCN** |
|---|---|---|
| Ideia | empilha a matriz `pontos × tempo` como imagem RGB e usa CNN pré-treinada em ImageNet | trata o esqueleto como grafo; a vizinhança é a anatomia |
| Origem | literatura de ISLR em Libras (Achado D em [`investigacao-expansao-dataset.md`](investigacao-expansao-dataset.md)) | diagrama de arquitetura do time |
| Onde está | `computer-vision-model/treino/representacao.py` + `modelo.py` | `computer-vision-model/treino/gcn.py` |
| Estado | **medido** | implementado, **não medido** |

Ambas consomem exatamente o mesmo insumo (os landmarks de `extract.py`) e são avaliadas
pelo mesmo protocolo (leave-one-signer-out), então os números serão comparáveis.

---

## 2. O que está medido

**Skeleton-DML + ResNet-18, 8 rodadas leave-one-signer-out, MINDS-Libras completo
(20 sinais, 8 pessoas, 800 clipes):**

```
acurácia signer-independent média = 93,4%   (desvio entre pessoas: 3,6 pontos)
por rodada: 97 · 96 · 96 · 89 · 97 · 87 · 94 · 91
```

Contexto para ler esse número:

| Referência | Acurácia | Observação |
|---|---|---|
| Chance aleatória | 5,0% | 20 classes |
| Baseline DTW 1-NN (PoC) | 70,0% | 10 sinais, chance de 10% — tarefa **mais fácil** |
| **Nosso modelo** | **93,4%** | 20 sinais |
| Literatura (Alves 2024; dos Santos 2025) | 93-94% | mesma base, mesmo protocolo |

Dois pontos que a média sozinha esconde:

- **A dispersão entre pessoas caiu muito.** No DTW as rodadas iam de 40% a 100%; aqui, de
  87% a 97%. Para um dispositivo institucional, que encontra um desconhecido a cada
  atendimento, o pior caso importa tanto quanto a média.
- **O vocabulário dobrou** em relação à PoC, o que torna a tarefa mais difícil, não mais
  fácil.

Relatório completo, incluindo acurácia por sinal e pares confundidos:
[`computer-vision-model/treino/resultados-resnet/relatorio.md`](../computer-vision-model/treino/resultados-resnet/relatorio.md).

### Reprodutibilidade entre máquinas

O mesmo treino rodou depois numa GPU T4 (Colab) e deu **93,5%** — 0,1 ponto do valor
medido na CPU local. Mesmo código, hardware diferente, resultado igual. Isso descarta que
os 93,4% tenham sido artefato de uma configuração local.

---

## 3. O ST-GCN medido: 44,6%, com uma ressalva que muda a leitura

Primeira medição, mesmas 8 rodadas, mesmo dataset, GPU T4:

```
acurácia signer-independent média = 44,6%   (desvio entre pessoas: 9,3 pontos)
por rodada: 42 · 51 · 58 · 48 · 37 · 41 · 27 · 53
```

Relatório: [`computer-vision-model/treino/resultados-gcn/relatorio.md`](../computer-vision-model/treino/resultados-gcn/relatorio.md).

**Mas este número mede menos do que parece, e a causa é a configuração usada.** Os
hiperparâmetros vieram do paper de referência, que faz *fine-tuning* de uma ResNet
pré-treinada — não treino do zero:

```
600 clipes de treino ÷ lote 64 =  9 atualizações de peso por época
9 × 30 épocas                  = ~270 atualizações no total
```

270 passos de gradiente bastam para **ajustar** uma rede que já vem do ImageNet sabendo
enxergar. Para **treinar** um GCN do zero, é quase nada. A assimetria não é só "um tem
pré-treino e o outro não" — é que o orçamento de treino foi dimensionado para quem tem.

O padrão por sinal confirma subtreino em vez de incapacidade: o GCN acerta **87,5% em
`esquina`** e **2,5% em `aproveitar`**. Rede que aprendeu as classes fáceis e ficou sem
passos para o resto — não rede incapaz de representar o problema.

**Portanto:** 44,6% é um resultado válido para "ST-GCN, sem pré-treino, com os
hiperparâmetros da ResNet". Registrá-lo como "o GCN é pior" seria afirmar mais do que foi
medido.

### O teste justo, pendente

```bash
python treinar.py --arquitetura gcn --dispositivo auto \
    --epocas 120 --lr 1e-3 --batch 32 --agendador cosseno
```

~2.160 atualizações (8× mais), taxa de aprendizado de treino do zero e decaimento por
cosseno. Custa menos de uma hora na GPU. **Enquanto isso não rodar, a comparação entre as
duas arquiteturas continua em aberto.**

---

## 3b. Por que a comparação é difícil de fazer justa

**Não existe, até onde a pesquisa foi, número publicado de GCN no MINDS-Libras com
protocolo signer-independent.** Não há referência externa para dizer se 44,6% ou 85% seria
o esperado — por isso a medição própria importa tanto, e por isso ela precisa ser feita
com o orçamento de treino adequado.

Há também um agravante estatístico que precisa entrar na conversa: **estamos em 93,4%,
sobram 3,7 pontos de teto.** O GCN não precisa ser "melhor em tese"; precisa bater 93,4%
no nosso dataset. E GCN treinado do zero é faminto por dado — os resultados fortes de
ST-GCN na literatura vêm de bases com dezenas de milhares de amostras, enquanto temos 800.
É exatamente essa escassez que o pré-treino em ImageNet resolve para a ResNet e que o GCN
não tem de graça.

**Experimento pendente:** `python treinar.py --arquitetura gcn`, mesmas 8 rodadas.
Roda em minutos numa GPU ([`notebook_gpu.ipynb`](../computer-vision-model/treino/notebook_gpu.ipynb)).

---

## 4. Onde o GCN é melhor independentemente da acurácia

Duas vantagens não dependem do resultado do experimento:

**Tamanho.** 0,46M parâmetros contra 11,2M da ResNet-18 — **24× menor**. O destino no
diagrama é um `.tflite` int8 rodando no celular, e aí isso pesa. Mesmo que o GCN empate ou
perca por pouco em acurácia, esse fator pode decidir.

**Robustez a ponto de vista** — a lacuna que o Heitor identificou. Ângulos entre juntas e
vetores de osso são representação nativa num grafo, e são o que dá invariância a mudança
de câmera. Nossa normalização atual cobre parte do problema, mas não tudo:

| Variação | Coberta? | Como |
|---|---|---|
| Pessoa deslocada no quadro | ✅ | origem no ponto médio dos ombros |
| Pessoa mais perto/longe | ✅ | escala = distância entre ombros |
| Resolução/proporção do vídeo | ✅ | conversão para pixels antes de normalizar |
| **Câmera de cima / de baixo / de lado** | ❌ | **lacuna aberta** |

Essa lacuna é séria para o produto: os óculos ficam na cabeça do atendente, cuja altura e
postura variam a cada atendimento. E **nenhuma base pública que temos permite medi-la** —
MINDS e V-LIBRASIL são estúdio, frontal, enquadramento controlado. É a versão concreta da
ressalva de "estúdio é teto otimista": os 93,4% não dizem nada sobre ângulo de câmera.

---

## 5. Recomendação

**Para o MVP (16/09): manter Skeleton-DML + ResNet-18.** É o que está medido, entrega
93,4-93,5% em duas máquinas diferentes, e o app hoje consome o modelo por uma **API em Python** (`PoC/api/server.py`) — não
por `.tflite` —, então a vantagem de tamanho do GCN não é cobrada nesta entrega.

**Para o produto: o GCN é o candidato certo**, pelos motivos da seção 4, e o diagrama está
correto em registrá-lo como destino. A transição deve ser decidida por medição, não por
prazo.

**Como decidir entre os dois:** rodar o experimento 1b da §6 — o GCN com orçamento de
treino do zero. O 44,6% já medido NÃO serve para essa decisão, pelos motivos da §3. Se o
GCN chegar perto (digamos,
dentro de 2 pontos), a decisão passa a ser de engenharia, não de acurácia — e aí os 24× de
diferença de tamanho provavelmente decidem a favor dele.

---

## 6. Fila de experimentos

Em ordem de custo-benefício. Cada um é uma rodada de treino; na GPU, minutos.

| # | Experimento | Responde | Estado |
|---|---|---|---|
| 1 | ST-GCN, hiperparâmetros da ResNet | — | ✅ feito: **44,6%**, mas subtreinado (§3) |
| 1b | **ST-GCN com orçamento de treino do zero** | GCN bate 93,5% no nosso dataset? | **pendente — é o que decide** |
| 2 | Mais épocas na ResNet | o modelo não convergiu (melhor época foi a 30 de 30 em 4 das 8 rodadas) | pendente |
| 3 | Pré-treino na V-LIBRASIL completa (1.363 palavras) + fine-tuning | mesclar bases ajuda? | extração em andamento |
| 4 | Features de ângulo entre juntas | fecha a lacuna de ponto de vista do §4? | a implementar |
| 5 | Augmentação de ponto de vista (usando o z) | idem, por outro caminho | a implementar |
| 6 | Pré-treino em WLASL100/300 | transferência entre línguas de sinais ajuda? | só se 3 render |

Os itens 4 e 5 vêm da observação do Heitor sobre ângulos, e valem independentemente de
qual arquitetura vencer.

---

## 7. O que ainda não é decidível com o dado que temos

- **Se funciona no balcão.** Todo número deste documento é sobre gravação de estúdio. Só a
  coleta própria responde — ver [`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md).
- **O caminho de deploy.** ResNet-18 → `.tflite` passa por conversão a partir do PyTorch;
  o GCN também. Nenhum dos dois foi testado nesse trajeto ainda, e o MVP não depende disso
  porque o app usa a API.
- **Qual vocabulário final.** Os 20 sinais do MINDS são banco de provas, não produto.

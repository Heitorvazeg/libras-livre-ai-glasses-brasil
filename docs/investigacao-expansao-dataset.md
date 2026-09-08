# Investigação — expansão do dataset (Dia 1)

**Objetivo:** antes de treinar o classificador do §5.5, descobrir se dá para aumentar o
dataset da PoC (hoje 430 clipes · 11 pessoas · 10 sinais) sem inventar coleta nova, e a
que custo. A métrica que importa é **pessoas diferentes por sinal**, não clipes — é ela
que a avaliação leave-one-signer-out consome.

Duas ações foram investigadas em paralelo:

- **Ação A** — o MINDS-Libras tem mais sinalizadores do que os 8 que a PoC usa?
- **Ação B** — o dataset agregado MALTA-LIBRAS (toolkit `Malta-Lab/ISLR_LIBRAS`) serve
  como terceira fonte?

> ⚠️ **Limite do ambiente onde esta investigação rodou:** a sandbox usada bloqueia
> `kaggle.com` (403 no proxy). Tudo que dependia de ler o índice real dos `.zip` do
> Kaggle ficou **pendente de execução na máquina do time** — está marcado como tal, e
> `datasets/diagnostico_bundle.py` foi escrito exatamente para fechar essa lacuna com um
> comando. GitHub, arXiv e HuggingFace **não** estão bloqueados.

---

## Ação A — MINDS-Libras: a PoC usa 8 de 12 sinalizadores

### O que está confirmado

O `datasets/selecao.yaml` assume `20 sinais × 8 sinalizadores × 5 repetições = 800
clipes`, e o relatório da PoC lista 8 participantes MINDS (`M01, M02, M05, M06, M08,
M10, M11, M12`). **A base publicada tem 12.**

Evidência independente do Kaggle (que está bloqueado aqui): o repositório
[`Dudu197/sign-language-recognition`](https://github.com/Dudu197/sign-language-recognition),
que usa o MINDS-Libras, versiona em `99_others/minds_frames_count.csv` a **listagem
completa dos vídeos** da base — 1.158 clipes. Contando por sinalizador:

```
Sinalizador   01   02   03   04   05   06   07   08   09   10   11   12
clipes       100  100   85   95  100  100  103  100   75  100  100  100
```

São **12 sinalizadores** e 20 sinais — batendo com a descrição publicada da base
(12 sinalizadores, 5 repetições, ~1.155-1.200 vídeos, 45 perdidos na gravação).

Cobertura dos **nossos 10 sinais**, por sinalizador (clipes por combinação):

```
sinal          01   02   03   04   05   06   07   08   09   10   11   12
acontecer       5    5    5    5    5    5    5    5    5    5    5    5
amarelo         5    5    5    5    5    5    5    5    0    5    5    5
banheiro        5    5    5    5    5    5    5    5    0    5    5    5
barulho         5    5    5    5    5    5    7    5    5    5    5    5
espelho         5    5    5    5    5    5    5    5    5    5    5    5
filho           5    5    5    0    5    5    6    5    5    5    5    5
maca            5    5    5    5    5    5    5    5    5    5    5    5
medo            5    5    5    5    5    5    5    5    0    5    5    5
ruim            5    5    5    5    5    5    5    5    5    5    5    5
sapo            5    5    5    5    5    5    5    5    5    5    5    5
                                 ^^^^^^^^^^^^^^^^^^^^  não usados hoje: 03, 04, 07, 09
```

**Ganho se os 4 sinalizadores faltantes entrarem:**

| | hoje | com os 12 |
|---|---|---|
| clipes MINDS (10 sinais) | 400 | 583 (+46%) |
| dataset total da PoC | 430 | 613 |
| pessoas por sinal | 11 | **15** |
| rodadas de leave-one-signer-out | 11 | 15 |

É o dado mais barato disponível: mesma base já integrada, mesmo pipeline, mesma licença
(MIT declarada no Kaggle), zero trabalho de integração nova.

### Por que os 4 ficaram de fora — hipótese a verificar

O `ingest.py` **não** fixa 8 sinalizadores: ele descobre quem existe aplicando um regex
sobre o índice do `.zip` (`_clipes_minds`, linha 97-105). O padrão é ancorado no início
do nome do membro:

```python
_RE_MINDS = re.compile(r"^(?P<ordem>\d{2})(?P<palavra>[A-Za-z]+)Sinalizador(?P<pessoa>\d+)-(?P<rep>\d+)\.mp4$")
```

O `^` exige que o nome do membro comece com os dois dígitos da ordem — ou seja, que o
arquivo esteja na **raiz** do zip. Qualquer clipe dentro de uma subpasta
(`Sinalizador09/01Acontecer...mp4`, `parte2/...`) é **silenciosamente ignorado**, sem
aviso. Duas hipóteses, ambas testáveis com o índice do zip:

1. **O bundle do Kaggle tem os 12**, mas parte deles está aninhada em subpastas e o
   regex os descarta → correção é de uma linha (casar contra o nome-base do membro).
2. **O bundle do Kaggle é parcial** (só 8 sinalizadores, 800 vídeos) → os 4 restantes
   teriam de vir da distribuição original da base, não do mirror do Kaggle.

O comentário do `selecao.yaml` (`800 clipes`) sugere a hipótese 2, mas ele foi escrito a
partir da mesma leitura que o regex faz — não é evidência independente.

### Como fechar isso (1 comando, na máquina do time)

```bash
cd computer-vision-model/datasets
python diagnostico_bundle.py --fonte minds
```

O script lê **só o índice** do zip remoto (não baixa vídeo), e reporta: quantos membros
existem, quantos casam com o regex, quantos foram descartados e por quê, e a lista de
sinalizadores encontrados. Se aparecerem 09-12, é a hipótese 1 e o ganho é imediato.

---

## Ação B — MALTA-LIBRAS / toolkit `Malta-Lab/ISLR_LIBRAS`

Repositório: <https://github.com/Malta-Lab/ISLR_LIBRAS> (licença MIT declarada no
README). Agrega vídeos de INES V2/V3, Corpus Libras (UFSC), SignBank (UFSC_V2), Spread
the Sign, V-LIBRASIL, USP, UFV e YouTube. Tensores prontos em
<https://huggingface.co/datasets/MALTA-Lab/MALTA_LIBRAS>.

### O que serve

**Todos os nossos 10 sinais existem lá**, em 5 a 8 dicionários diferentes cada
(`dataset_intersections/MALTA_LIBRAS_raw.csv`, 24.110 linhas):

| sinal | vídeos | dicionários distintos |
|---|---|---|
| acontecer | 13 | 7 |
| banheiro | 10 | 7 |
| filho | 10 | 8 |
| ruim | 8 | 6 |
| amarelo | 7 | 7 |
| espelho | 7 | 6 |
| barulho | 6 | 6 |
| maca | 6 | 5 |
| sapo | 6 | 5 |
| medo | 5 | 5 |

Como cada dicionário é gravado por apresentador(es) próprio(s), são pessoas novas em
relação às 11 (ou 15) que já temos — exatamente o insumo que o leave-one-signer-out
consome.

### O que atrapalha (e é decisivo)

1. **O ID do sinalizante só existe para parte dos vídeos.** A coluna `actor` está no
   `MALTA_LIBRAS_original.csv` (11.092 linhas, 60 atores), não no `raw` (24.110 linhas).
   Cruzando os dois pelo nome do arquivo, dos nossos 10 sinais só `banheiro` (7 atores),
   `medo` (5), `maca` (3) e `filho` (1) têm ator identificado; os outros 6 ficam sem.
   Sem ID de pessoa **não dá para fazer leave-one-signer-out** — o clipe entraria como
   "pessoa desconhecida" e contaminaria a métrica.
   *Mitigação:* a maioria dos dicionários tem **1 apresentador só** (Acessibilidades2: 1,
   Acessibilidades3: 1, UFSC_V2: 1, USP: 2, Youtube: 3, UFV: 6, UFSC: 9,
   SpreadTheSign: 40). Para essas fontes, `dicionário` é um proxy seguro de identidade —
   o que resolve a maior parte dos casos, menos SpreadTheSign e UFSC.

2. **O pipeline deles é de vídeo RGB, não de landmarks.** `build_tensor_dataset.py`
   converte vídeo em tensor `(C, T, H, W)` com resolução pela metade e **16 frames**
   subamostrados, para treinar resnet3d/timesformer/vivit. Nossos clipes têm 77-493
   frames e o nosso modelo consome landmarks (49 pontos). Os tensores do HuggingFace,
   portanto, **não** são reaproveitáveis direto: o caminho é baixar os vídeos originais
   pelos scripts deles e passar pelo nosso `extract.py`.

3. **Um vídeo por sinal por fonte.** São dicionários: cada fonte tem tipicamente uma
   execução da palavra, não 5 repetições. Bom para diversidade de pessoas, ruim para
   volume por pessoa.

4. **Licenças heterogêneas — e é aqui que a ideia quase morre.** Levantamento das
   fontes (feito em investigação paralela; onde o termo não foi localizado está dito
   explicitamente, não presumido):

   | Fonte | Uso acadêmico | Uso comercial | Redistribuição |
   |---|---|---|---|
   | Spread the Sign | ❌ exige permissão | ❌ exige licença do European Sign Language Centre | ❌ proibido ("only for personal use") |
   | INES V2/V3 (acessibilidadebrasil) | não encontrado | não encontrado | não encontrado |
   | UFSC SignBank | indícios de Creative Commons (variante não confirmada) | depende de ser NC | idem |
   | Corpus Libras (UFSC) | não encontrado (corpus com consentimento de participantes) | não encontrado | não encontrado |
   | USP / UFV | não encontrado | não encontrado | não encontrado |
   | V-LIBRASIL (UFPE) | CC (variante não confirmada) | provável se CC BY | provável |

   O `README` do toolkit declara MIT, mas **o MIT cobre o empacotamento, não os direitos
   dos vídeos originais**. Somando: são vídeos de pessoas identificáveis — dado
   biométrico, com implicações de LGPD para um produto institucional.

5. **Os scripts de download cobrem só parte das fontes.** `video_downloads/download_videos.py`
   baixa sem login (a partir de CSVs de URLs diretas, e dá para filtrar por palavra),
   mas está quebrado por um hardcode de nome de arquivo (2 linhas a corrigir), e **não há
   CSV nem script para INES V2, Corpus Libras UFSC e YouTube** (~40% do MALTA-LIBRAS).
   O link do SharePoint prometido no README não existe no repositório.

### O que sobra na prática, depois do filtro de licença

Cruzando as fontes seguras com os nossos 10 sinais: descartando Spread the Sign (risco
alto) e tratando INES/USP/UFV/Corpus Libras como "todos os direitos reservados" até
haver autorização, o que resta é essencialmente **UFSC SignBank — que tem 1 apresentador
só**. Ou seja: o ganho real e defensável do MALTA-LIBRAS para o nosso vocabulário cai de
"5-7 pessoas novas por sinal" para **~1 pessoa nova por sinal**, ao custo de baixar de
scrapers, resolver identidade de sinalizante e reextrair landmarks. **ROI ruim para o
prazo do hackathon.**

### Escala do MALTA-LIBRAS, para contexto

`MALTA_LIBRAS_original.csv`: 4.575 rótulos, 60 atores. Rótulos com ≥3 atores: 1.055;
com ≥5 atores: 289; com ≥8: 26; **com ≥11: nenhum**. Ou seja: é uma base larga em
vocabulário e rasa em pessoas por sinal — o oposto do MINDS. Como fonte de **pré-treino**
(aprender "como o corpo se move em Libras" em geral) é valiosa; como fonte de exemplos
para as nossas 10 classes, o ganho é de ~5-7 pessoas por sinal.

---

---

## Achado C — o maior dataset disponível já está na mão: MINDS sozinho

A investigação começou perguntando "onde arrumar mais dados?" e esbarrou numa resposta
que não estava na lista: **o dataset de treino mais forte disponível hoje é o
MINDS-Libras inteiro, sem a V-LIBRASIL.**

```
sinal        01  02  03  04  05  06  07  08  09  10  11  12  total
Acontecer     5   5   5   5   5   5   5   5   5   5   5   5     60
Aluno         5   5   0   5   5   5   5   5   5   5   5   5     55
Amarelo       5   5   5   5   5   5   5   5   0   5   5   5     55
America       5   5   0   5   5   5   5   5   5   5   5   5     55
Aproveitar    5   5   5   5   5   5   5   5   5   5   5   5     60
Bala          5   5   5   5   5   5   5   5   5   5   5   5     60
Banco         5   5   5   5   5   5   5   5   5   5   5   5     60
Banheiro      5   5   5   5   5   5   5   5   0   5   5   5     55
Barulho       5   5   5   5   5   5   7   5   5   5   5   5     62
Cinco         5   5   0   5   5   5   5   5   5   5   5   5     55
Conhecer      5   5   5   5   5   5   5   5   0   5   5   5     55
Espelho       5   5   5   5   5   5   5   5   5   5   5   5     60
Esquina       5   5   5   5   5   5   5   5   0   5   5   5     55
Filho         5   5   5   0   5   5   6   5   5   5   5   5     56
Maca          5   5   5   5   5   5   5   5   5   5   5   5     60
Medo          5   5   5   5   5   5   5   5   0   5   5   5     55
Ruim          5   5   5   5   5   5   5   5   5   5   5   5     60
Sapo          5   5   5   5   5   5   5   5   5   5   5   5     60
Vacina        5   5   5   5   5   5   5   5   5   5   5   5     60
Vontade       5   5   5   5   5   5   5   5   5   5   5   5     60
TOTAL                                                         1158
```

Só 9 das 240 combinações sinal × pessoa estão vazias. Comparando com o dataset atual:

| | PoC hoje (MINDS 8 + V-LIBRASIL) | MINDS completo |
|---|---|---|
| clipes | 430 | **1.158** (2,7×) |
| sinais | 10 | **20** (2×) |
| pessoas por sinal | 11 | 12 |
| repetições por pessoa | 5 (MINDS) / 1 (V-LIB) | 5, uniforme |
| frames por clipe | 77-493 (mediana 138) | 70-232 (mediana 137) |
| degrau entre bases | **sim** | não |
| rótulos pendentes de consultor | 3 | **0** |
| licença | MIT + CC BY-NC-ND | MIT |

Três consequências que importam mais que o tamanho:

1. **Some o degrau entre bases.** O relatório da PoC mede exatamente esse custo: 70,0%
   no dataset completo contra **85,7%** no recorte sem o degrau. Misturar estúdio com
   *chroma key* cobra caro, e o classificador treinado tende a aprender a diferença entre
   as bases em vez do sinal.
2. **Some o bloqueio do consultor de Libras.** Os 3 rótulos a validar (`maca`, `medo`,
   `sapo`) só são ambíguos **porque** duas bases nomeiam diferente. Fonte única, rótulo
   único: o trabalho do consultor deixa de ser pré-requisito para treinar e volta ao que
   sempre deveria ser — definir o vocabulário de atendimento da coleta própria.
3. **Vocabulário dobra sem custo.** 20 classes em vez de 10 é um teste mais honesto
   (chance aleatória cai de 10% para 5%) e gera um modelo mais interessante de demonstrar.

O papel da V-LIBRASIL muda de "parte do treino" para algo mais útil: **conjunto de teste
de domínio diferente** — treina no MINDS, testa na V-LIBRASIL para medir quanto o modelo
cai quando muda o cenário de gravação. É a melhor aproximação disponível da pergunta
"vai funcionar no balcão?" antes da coleta própria.

---

## Achado D — o alvo realista já foi publicado: ~0,93 no MINDS, no nosso protocolo

Levantamento do estado da arte de reconhecimento de sinais isolados de Libras com
landmarks. **Dois trabalhos avaliam exatamente o MINDS-Libras com protocolo
signer-independent** (LOPO, *leave-one-person-out* — o mesmo do nosso `evaluate.py`):

| Trabalho | Dataset | Protocolo | Modelo | Resultado |
|---|---|---|---|---|
| dos Santos et al. 2025 ([arXiv 2510.24887](https://arxiv.org/abs/2510.24887)) | MINDS-Libras (1.155 clipes, 20 sinais, 12 pessoas) | nested LOPO (132 runs) | MediaPipe Holistic → subset de 80 pontos → Skeleton-DML → ResNet-18 (ImageNet) | **acc 0,94 ± 0,04** |
| idem | LIBRAS-UFOP (56 sinais, 5 pessoas) | nested LOPO | idem | F1 0,91 |
| Alves et al. 2024 ([arXiv 2404.19148](https://arxiv.org/abs/2404.19148), repo [`Dudu197`](https://github.com/Dudu197/sign-language-recognition)) | MINDS-Libras | nested LOPO | OpenPose → Skeleton-DML → ResNet-18 | **acc 0,93** |
| idem | LIBRAS-UFOP | nested LOPO | idem | acc 0,82 |

> Procedência: o arXiv está bloqueado no proxy desta sandbox. Os números vêm de snippets
> de busca **mais** a leitura direta do repositório oficial do paper
> ([`danielelvs/islr-subset`](https://github.com/danielelvs/islr-subset)) e do repo do
> Alves et al., ambos acessíveis via `raw.githubusercontent.com`. As definições de
> subconjunto de pontos e de protocolo vêm do código, não de resumo.

### O que a literatura converge (e contraria nosso plano anterior)

1. **A representação vencedora não é sequência crua nem esqueleto desenhado: é
   Skeleton-DML.** Empilha-se a matriz `landmarks × frames` (x e y) como se fosse uma
   imagem RGB, redimensiona para 224×224 e treina uma **CNN pré-treinada em ImageNet**.
   O problema de "sequências de tamanhos diferentes" some no redimensionamento, e o
   transfer learning cobre a escassez de dados.
2. **ResNet-18 é a melhor** nas duas publicações. Ablação do repo do Alves et al.
   (MINDS/UFOP, LOPO): ResNet-18 0,93/0,82 · ViT-medium 0,93/0,81 · ResNet-50 0,90/0,70 ·
   EfficientNet-B6 0,89/0,78 · MobileNetV4 0,87/0,70. Modelo maior piora.
3. **Menos landmarks é melhor.** Usar os 543 pontos do Holistic é o pior caso em todos os
   datasets — a decisão do nosso `config.yaml` de trabalhar com um subconjunto está
   alinhada com a literatura. O subconjunto "arcanjo" (75 pontos: pose + mãos, sem malha
   facial densa) fica a ~1pp do melhor; o melhor ("2nd", 80 pontos) acrescenta 18 pontos
   de lábios.
4. **Imputação temporal de landmarks faltantes** (spline cúbica em falhas de até 5
   frames) vale ≥4pp de F1 no MINDS e >15pp no UFOP. É barato e ainda não fazemos.
5. **Augmentação** citada: rotação σ=12°, zoom σ=0,1, espelhamento 30%.

### Consequência para o nosso plano de modelo

O plano anterior (GRU treinado sobre a sequência de landmarks, §5.5 do plano da PoC) **não
é o que a literatura usa nessa escala de dados**. Com ~1.000 clipes, treinar uma recorrente
do zero disputa com uma CNN que já vem pré-treinada em milhões de imagens. A rota
recomendada passa a ser:

**Skeleton-DML + ResNet-18 (ImageNet), avaliado em LOPO** — mesma métrica, mesmo dataset
e mesma escala em que 0,93-0,94 já foram publicados.

Ressalva de deploy a resolver depois: o repo mira um `.tflite` leve rodando no celular.
ResNet-18 quantizada é viável em aparelho, mas é mais pesada que o "modelo raso" prometido
no README, e a conversão parte de PyTorch (os dois trabalhos são PyTorch). MobileNetV4
seria o caminho mais leve ao custo de ~6pp (0,87), o que ainda é muito acima do baseline
atual.

### O que NÃO é comparável com os nossos 70,0%

- Números de datasets com **split fixo** (ex.: INCLUDE-50, 0,91-0,95): a mesma pessoa
  aparece em treino e teste.
- Resultados com 20 ou 56 sinais têm chance aleatória de 5% e 1,8%, contra 10% nos nossos
  10 sinais.
- O nosso próprio **85,7%**: o recorte foi definido depois de olhar o resultado. Serve
  como diagnóstico do degrau entre bases, não como métrica de entrega.

Comparáveis em espírito ao nosso 70,0%: **0,93 e 0,94 no MINDS, ambos em LOPO**. Ou seja,
há ~23 pontos de margem documentada entre onde estamos e onde a mesma tarefa já chegou.

---

## Conclusão do Dia 1

Ordem de custo-benefício, revisada com o que a investigação encontrou:

1. **Treinar no MINDS completo (Achado C).** 1.158 clipes, 20 sinais, 12 pessoas, fonte
   única, licença MIT, sem dependência de consultor. É a maior melhoria de dataset
   disponível e não exige integrar nada novo — só usar por inteiro o que já está
   integrado. **Recomendado como base de treino do classificador.**
2. **Verificar o bundle do Kaggle (Ação A).** Pré-requisito do item 1: confirmar se os
   sinalizadores 03/04/07/09 estão no zip → `python diagnostico_bundle.py --fonte minds`.
   Se não estiverem, buscar a distribuição original da base.
3. **V-LIBRASIL como teste de domínio, não como treino.** Reaproveita os 30 clipes que já
   temos para medir a queda entre cenários de gravação.
4. **MALTA-LIBRAS (Ação B): não agora.** Depois do filtro de licença sobra ~1 pessoa nova
   por sinal, com trabalho de download/extração/identidade — ROI ruim dentro do prazo.
   Continua valendo como fonte de **pré-treino** na visão de produto
   (`libras-livre-arquitetura.md` §2), com autorização das fontes resolvida antes.
5. **Trocar o modelo alvo de GRU para Skeleton-DML + ResNet-18 (Achado D).** Mesmo
   dataset, mesmo protocolo, 0,93-0,94 publicados — contra 0,70 do baseline atual.

### O plano que sai daqui

| Ordem | O quê | Depende de |
|---|---|---|
| 1 | `diagnostico_bundle.py --fonte minds` | máquina com Kaggle |
| 2 | Estender `selecao.yaml` + vocabulário para os 20 sinais do MINDS | item 1 |
| 3 | Ingerir e extrair landmarks dos 1.158 clipes | itens 1-2 |
| 4 | Rerodar o baseline DTW (`evaluate.py`) no dataset novo — referência atualizada | item 3 |
| 5 | Implementar Skeleton-DML + ResNet-18 em LOPO e comparar com o item 4 | item 3 |
| 6 | V-LIBRASIL como teste de domínio (treina MINDS, testa V-LIBRASIL) | item 5 |

Ganhos baratos a incorporar no caminho, todos vindos do Achado D: imputação spline de
landmarks faltantes, subir de 49 para ~75 pontos (pose completa) e augmentação
(rotação/zoom/espelhamento).

## Pendências

- [ ] Rodar `datasets/diagnostico_bundle.py --fonte minds` numa máquina com acesso ao
      Kaggle e decidir entre as hipóteses 1 e 2 da Ação A.
- [ ] Se o bundle do Kaggle for parcial (hipótese 2), localizar a distribuição original do
      MINDS-Libras para obter os sinalizadores 03/04/07/09.
- [ ] Decidir o caminho de deploy do modelo escolhido: ResNet-18 (PyTorch → `.tflite`) ou
      MobileNetV4 (mais leve, ~6pp abaixo).
- [ ] Confirmar as licenças marcadas como "não encontrado" antes de qualquer uso além de
      PoC acadêmica — e tratar LGPD (vídeo de pessoa identificável é dado biométrico).

---

## Atualização — Dia 2: Ação A resolvida (hipótese 2 confirmada)

Rodado em máquina com acesso ao Kaggle:

```
$ python diagnostico_bundle.py --fonte minds
[minds] membros no bundle: 800
[minds] casam com o regex atual: 800
[minds] casariam pelo nome-base: 0
[minds] pessoas encontradas (8): 01, 02, 05, 06, 08, 10, 11, 12
[minds] total de clipes do vocabulário: 400
```

**O bundle do Kaggle `j0aopsantos/minds-libras` tem mesmo só 8 sinalizadores, 800
vídeos.** Não é bug do `ingest.py` (o regex não estava descartando nada por subpasta) —
o mirror do Kaggle é parcial em relação à base publicada (12 sinalizadores, 1.158
vídeos). O CSV usado no Achado C (`Dudu197/sign-language-recognition`) reflete a base
completa, vinda de outra distribuição — não deste bundle.

**Revisão do Achado C:** "MINDS completo" como dataset de treino segue sendo a melhor
opção *se* conseguirmos a distribuição completa (12 pessoas) por outro canal (contato
direto com o grupo MINDS/UFMG, ou outro mirror). Até resolver isso, o dataset
disponível **hoje** é o de sempre: 8 sinalizadores MINDS + 3 V-LIBRASIL = 11 pessoas.

### Pendência atualizada
- [ ] Localizar a distribuição completa do MINDS-Libras (12 sinalizadores) — contato
      direto com os autores (grupo MINDS, UFMG) é o caminho mais confiável.

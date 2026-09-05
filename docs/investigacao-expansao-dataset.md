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

## Pendências

- [ ] Rodar `datasets/diagnostico_bundle.py --fonte minds` numa máquina com acesso ao
      Kaggle e decidir entre as hipóteses 1 e 2.
- [ ] Licenças por fonte do MALTA-LIBRAS e viabilidade dos scripts de download
      (investigação paralela em curso).
- [ ] Estado da arte de ISLR em Libras com landmarks: qual arquitetura e qual protocolo
      (signer-independent ou não) — define o alvo realista do classificador treinado
      (investigação paralela em curso).

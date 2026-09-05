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

4. **Licenças heterogêneas** (INES, UFSC, Spread the Sign, USP, UFV têm termos
   próprios) — em apuração por investigação paralela; ver seção de pendências.

### Escala do MALTA-LIBRAS, para contexto

`MALTA_LIBRAS_original.csv`: 4.575 rótulos, 60 atores. Rótulos com ≥3 atores: 1.055;
com ≥5 atores: 289; com ≥8: 26; **com ≥11: nenhum**. Ou seja: é uma base larga em
vocabulário e rasa em pessoas por sinal — o oposto do MINDS. Como fonte de **pré-treino**
(aprender "como o corpo se move em Libras" em geral) é valiosa; como fonte de exemplos
para as nossas 10 classes, o ganho é de ~5-7 pessoas por sinal.

---

## Conclusão parcial do Dia 1

Ordem de custo-benefício, do mais barato ao mais caro:

1. **MINDS completo (Ação A).** +183 clipes, 11 → 15 pessoas, sem integração nova.
   Bloqueado só pela verificação do índice do zip → rodar `diagnostico_bundle.py`.
2. **MALTA-LIBRAS para os 10 sinais (Ação B).** +5 a 13 vídeos por sinal, de pessoas
   novas, ao custo de: baixar dos scrapers deles, passar pelo nosso `extract.py`,
   resolver identidade de sinalizante por dicionário e checar licença por fonte.
3. **Pré-treino multi-fonte no MALTA-LIBRAS inteiro.** É a arquitetura de *produto* já
   prevista em `libras-livre-arquitetura.md` §2 — fora do orçamento de tempo do
   hackathon.

## Pendências

- [ ] Rodar `datasets/diagnostico_bundle.py --fonte minds` numa máquina com acesso ao
      Kaggle e decidir entre as hipóteses 1 e 2.
- [ ] Licenças por fonte do MALTA-LIBRAS e viabilidade dos scripts de download
      (investigação paralela em curso).
- [ ] Estado da arte de ISLR em Libras com landmarks: qual arquitetura e qual protocolo
      (signer-independent ou não) — define o alvo realista do classificador treinado
      (investigação paralela em curso).

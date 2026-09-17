# datasets/ — vídeos públicos de Libras

Esta pasta integra ao repositório os bancos públicos de vídeos de Libras e
transforma recortes deles nos conjuntos que o projeto consome. Ela existe porque a
pergunta central — *o reconhecimento generaliza entre pessoas diferentes?* — não
depende de gravarmos os vídeos: depende de ter **muitas pessoas diferentes por
sinal**, e isso as bases públicas já têm.

> **Dois fluxos distintos, e não devem se misturar.**
>
> - **Avaliação** (`ingest.py`): os 20 sinais do MINDS-Libras, que o `treino/`
>   usa para medir LOSO. É o que este README descreve em detalhe.
> - **Pré-treino** (`ingest_pretreino.py`, `ingest_malta.py`, `ingest_wlasl.py`):
>   corpus grande, com auditoria obrigatória, sidecars de proveniência por amostra
>   e exclusão por origem. O protocolo está em
>   [isolamento e proveniência](../../docs/protocolo-pretreino.md).
>
> Nunca aponte o corpus de pré-treino para a pasta de avaliação: um clipe que
> entra nos dois lados destrói o significado do número de generalização.

O que está versionado aqui é a **receita**, não os vídeos (§5):

| Arquivo | Papel |
|---|---|
| `selecao.yaml` | os sinais escolhidos e a que arquivo eles correspondem em cada base |
| `ingest.py` | baixa os clipes de avaliação e os renomeia para a convenção do projeto |
| `ingest_pretreino.py` | corpus V-LIBRASIL de pré-treino, com exclusão por origem |
| `ingest_malta.py`, `ingest_wlasl.py`, `subset_wlasl.py` | ingestão de MALTA-LIBRAS e WLASL |
| `proveniencia.py` | sidecars por amostra: origem, hash, identidade do sinalizante |
| `preparar_corpus_auditado.py` | cópia auditada e conservadora do corpus de pré-treino |
| `registrar_legado.py` | regulariza corpus antigo, sem sidecar |
| `diagnostico_bundle.py` | inspeciona um bundle remoto sem baixá-lo |
| `remote_zip.py` | lê/extrai membros de um `.zip` remoto por HTTP Range |
| `selftest.py` | validação offline: nomes, unicidade de pessoa, ordem, manifesto |
| `manifest.csv` | gerado: um registro por clipe (origem, destino, bytes, estado) |

---

## 1. As duas bases

| | **MINDS-Libras** | **V-LIBRASIL** |
|---|---|---|
| Kaggle | [`j0aopsantos/minds-libras`](https://www.kaggle.com/datasets/j0aopsantos/minds-libras) | [`davimedio01/v-librasil`](https://www.kaggle.com/datasets/davimedio01/v-librasil) |
| Origem | Núcleo MINDS / UFMG | CIn — UFPE ([libras.cin.ufpe.br](https://libras.cin.ufpe.br/)) |
| Bundle | 800 vídeos, ~47,8 GB | 4.088 vídeos + `annotations.csv`, ~10,8 GB |
| Conteúdo | 20 sinais × **8 sinalizadores** × 5 repetições | 1.364 palavras/expressões × **3 articuladores** × 1 execução |
| Gravação | ambiente controlado, vídeo pesado (~60 MB/clipe) | RGB com *chroma key*, clipes leves (~2,7 MB) |
| Licença declarada no Kaggle | MIT | CC BY-NC-ND 4.0 |

As duas se complementam exatamente onde a PoC precisa: a MINDS traz **repetição
por pessoa** (5 execuções), a V-LIBRASIL traz **pessoas a mais** — e é o número
de pessoas, não o de repetições, que a avaliação leave-one-signer-out consome.

> Cite as fontes originais em qualquer publicação e confira os termos de uso na
> página de cada dataset — em especial a V-LIBRASIL, cuja licença é
> não-comercial e sem derivados.

---

## 2. Como o vocabulário foi escolhido

O critério mudou uma vez, e a razão importa.

**Até 2026-09-08 eram 10 sinais:** os que existem nas **duas** bases. Um sinal
presente só na MINDS chega com 8 pessoas; só na V-LIBRASIL, com 3; nos dois, com
**11 pessoas diferentes e 43 clipes**. Como a métrica é acurácia deixando uma
pessoa de fora, mais pessoas por sinal é o que mais move o resultado — e era isso
que a PoC de DTW precisava.

**Hoje são os 20 sinais do MINDS-Libras.** A PoC mediu que juntar as duas bases
não somou pessoas: somou um degrau de condição de gravação, que custou ~15 pontos
(§6.1). O treino passou a usar o MINDS como núcleo — fonte única, sem esse degrau
e sem rótulo ambíguo. Os 10 sinais que também existem na V-LIBRASIL mantêm o
mapeamento, e ela passou a servir como teste de domínio diferente.

O vocabulário atual (`selecao.yaml`, replicado em `../PoC/config.yaml` e
`../config.yaml` — `ingest.py` confere e avisa se divergirem):

```
acontecer  amarelo  banheiro  barulho  espelho  filho  maca  medo  ruim  sapo
aluno  america  aproveitar  bala  banco  cinco  conhecer  esquina  vacina  vontade
```

Os 10 da segunda linha são **MINDS-only**: não têm chave `vlibrasil` no
`selecao.yaml`, e `ingest.py` simplesmente não os procura naquele bundle.

Cobertura dos 10 sinais compartilhados, conferida contra o índice real dos dois
`.zip` (é este recorte que a PoC de DTW usou):

```
sinal        pessoas  minds  vlib  clipes     GB
acontecer         11      8     3      43   2.09
amarelo           11      8     3      43   2.54
banheiro          11      8     3      43   2.50
barulho           11      8     3      43   2.56
espelho           11      8     3      43   2.48
filho             11      8     3      43   2.22
maca              11      8     3      43   2.48
medo              11      8     3      43   2.24
ruim              11      8     3      43   2.33
sapo              11      8     3      43   2.66
TOTAL             11                  430  24.10
```

**Estes não são os sinais do produto.** O vocabulário de atendimento (`ola`,
`ajuda`, `dor`, `marcar-consulta`…) não existe nessas bases com pessoas
suficientes, e volta quando houver coleta própria. A pergunta aqui é sobre
generalização entre pessoas — ela se responde com qualquer vocabulário
suficientemente variado, e estes sinais têm configurações de mão e movimentos bem
diferentes entre si. A proposta de vocabulário de produto está em
[`../../docs/vocabulario-mvp-proposta.md`](../../docs/vocabulario-mvp-proposta.md).

---

## 3. Três rótulos que precisam de conferência

Sete sinais têm o **mesmo rótulo** nas duas bases. Três não, e foram pareados por
julgamento — estão marcados `validado: false` em `selecao.yaml`:

| sinal | MINDS | V-LIBRASIL | o que conferir |
|---|---|---|---|
| `maca` | Maca (maçã) | Maçã (rosto) | a V-LIBRASIL nomeia a variante articulada no rosto; a MINDS não diz qual usa |
| `medo` | Medo | Com medo | rótulos diferentes, aparentemente o mesmo sinal |
| `sapo` | Sapo | Rã (sapo) | rã e sapo podem ter sinais distintos conforme a variante regional |

Se as variantes forem diferentes, juntar as duas bases num mesmo rótulo **cria**
erro de classificação e a PoC mede a mistura, não a generalização. Duas saídas:
validar com consultor de Libras (o mesmo que define o vocabulário, §2 do
[README da PoC](../PoC/README.md)), ou rodar sem eles:

```bash
python ingest.py --somente-validados     # só os 7 sinais de rótulo idêntico
```

---

## 4. Como rodar

Sem credencial do Kaggle e sem baixar os bundles inteiros: `remote_zip.py` lê o
índice no rodapé de cada `.zip` e baixa **só os membros escolhidos**, um pedido
HTTP por vídeo. Só a biblioteca padrão do Python — nenhuma dependência nova.

```bash
cd computer-vision-model/datasets

python selftest.py                   # confere a receita, sem rede (~1 s)
python ingest.py --listar            # cobertura e tamanho, sem baixar nada
python ingest.py --fonte vlibrasil   # 30 clipes, ~80 MB — bom para testar o caminho
python ingest.py --reps 1            # 1 repetição por pessoa/sinal — primeira medição
python ingest.py                     # seleção completa dos 20 sinais
```

Rode `--listar` antes de qualquer coisa: ele imprime a contagem e o volume reais
da seleção atual, sem baixar um byte. Os vídeos vão para `../PoC/data/raw/` já
renomeados, e o passo seguinte é a extração de landmarks
(`cd ../PoC && python src/extract.py`), consumida tanto pelo baseline DTW
(`src/evaluate.py`) quanto pelo treino (`../treino/treinar.py`).

Detalhes que importam na prática:

- **É retomável.** Clipe já em disco é pulado; download interrompido escreve em
  `.parcial` e só vira `.mp4` depois de conferir tamanho e CRC — nada de vídeo
  truncado passando por completo na execução seguinte.
- **A ordem é por repetição**, não por sinal: todas as pessoas e sinais na rep 1,
  depois na rep 2… Uma execução interrompida no meio deixa um dataset
  **balanceado**, e não completo em alguns sinais e vazio em outros.
- **Dezenas de GB levam horas** numa conexão doméstica (~3 MB/s). Os clipes da
  MINDS são pesados (~60 MB cada). `--reps 1` já dá todas as pessoas em todos os
  sinais e é o caminho recomendado para a primeira medição.
- O índice de cada `.zip` fica em `.cache/` (não versionado); `--sem-cache`
  reconsulta.

### Convenção de nome

`ingest.py` traduz o nome de origem para a convenção da PoC (§4 do README dela),
com um prefixo de base na pessoa:

```
01AcontecerSinalizador05-3.mp4                  ->  pessoaM05_sinal-acontecer_rep03.mp4
videos UFPE (V-LIBRASIL)/data/Ruim_Articulador2.mp4  ->  pessoaV02_sinal-ruim_rep01.mp4
```

O prefixo (`M`/`V`) não é decoração: sem ele o sinalizador 02 da MINDS e o
articulador 02 da V-LIBRASIL virariam **a mesma pessoa** para o
leave-one-signer-out, que passaria a treinar e testar na mesma pessoa sem avisar.

---

## 5. Por que os vídeos não vão para o git

Três motivos, e qualquer um sozinho já bastaria: são dezenas de GB; a licença da
V-LIBRASIL (CC BY-NC-ND 4.0) não autoriza redistribuição; e o próprio plano da
PoC pede para não reter vídeo além do necessário (`extract.py --descartar-video`
apaga o `.mp4` depois de extrair os landmarks).

O que reproduz o dataset é a receita versionada aqui — `selecao.yaml` +
`ingest.py` + `manifest.csv`. Quem clonar o repositório roda `python ingest.py` e
chega exatamente aos mesmos arquivos, com os mesmos nomes.

---

## 6. O que o dataset entregou — e o que ele não entrega

Esta seção registra a medição da **PoC de DTW**, feita sobre o recorte de 10
sinais e 430 clipes. Ela continua valendo como diagnóstico das bases — foi ela
que motivou a mudança de critério do §2 — mas não descreve o dataset atual.

A PoC rodou sobre esses 430 clipes (relatório completo em
[`../PoC/results/relatorio.md`](../PoC/results/relatorio.md)). O número oficial
ficou em **70,0%**, zona amarela — mas ele é a média de dois regimes bem
diferentes, e é a separação entre eles que decide o próximo passo
(`python ../PoC/src/diagnostico.py`):

| cenário | pessoas | clipes | acurácia |
|---|---|---|---|
| dataset completo | 11 | 430 | **70,0%** |
| só MINDS-Libras | 8 | 400 | 77,8% |
| só V-LIBRASIL | 3 | 30 | 46,7% |
| só os 7 sinais de rótulo validado | 11 | 301 | **80,5%** |
| MINDS + só os 7 validados | 8 | 280 | **85,7%** |

### 6.1 As duas bases não somaram pessoas — somaram um degrau de domínio

Esta era uma ressalva teórica quando a seleção foi montada; agora está medida.
Olhando de onde vem o vizinho mais próximo de cada clipe:

```
clipes MINDS      -> MINDS: 399 (100%),  V-LIBRASIL: 1 (0%)
clipes V-LIBRASIL -> V-LIBRASIL: 28 (93%),  MINDS: 2 (7%)
```

**Os vizinhos praticamente nunca cruzam a fronteira entre as bases.** Um clipe da
V-LIBRASIL não é reconhecido por parecer com o mesmo sinal feito por outra
pessoa: ele é reconhecido (ou não) por parecer com outro clipe da V-LIBRASIL. A
condição de gravação — chroma key, enquadramento, ritmo — domina a geometria dos
landmarks mais que a identidade do sinal.

A consequência prática: **não são 11 pessoas por sinal, são 8 + 3 em dois mundos
separados**. Nas rodadas das pessoas `V*`, o modelo tem só 2 outras pessoas do
mesmo domínio como referência — daí os 46,7%. O ganho de juntar as bases foi
menor que o esperado; o que elas dão de melhor é justamente a evidência de que
essa fronteira existe, algo que dado de uma base só esconderia.

### 6.2 Os 3 rótulos não validados custam ~10 pontos

Tirar `maca`, `medo` e `sapo` (§3) leva o dataset completo de 70,0% para
**80,5%** — acima do limiar verde, mesmo mantendo as duas bases. Os pares mais
confundidos do relatório reforçam a suspeita (`filho`→`medo` 15 ocorrências,
`maca`→`amarelo` 10): parte do erro é rótulo pareado por julgamento, não
limitação do reconhecimento. **Validar esses três com consultor de Libras é a
intervenção de maior retorno** antes de qualquer mudança de modelo.

### 6.3 Os parâmetros de DTW valiam 6 pontos, de graça

`diagnostico.py --varredura` combinou os botões que o `config.yaml` expõe e
mediu cada combinação (16 no total, ~3 min):

| configuração | dataset completo |
|---|---|
| z ligado, sem janela (o padrão original) | 64,0% |
| z desligado, sem janela | 68,7% |
| **z desligado, janela 20** (aplicado) | **70,0%** |
| qualquer uma + normalizar por comprimento | pior em todos os casos |

O z das mãos é relativo ao punho e o de pose ao quadril — referenciais diferentes
somados no mesmo vetor entram como ruído. A janela de Sakoe-Chiba impede que o
alinhamento estique um trecho curto sobre um sinal inteiro. Os dois estão
aplicados no `../PoC/config.yaml`, com o número medido no comentário.

### 6.4 O que continua valendo

1. **Não é o setup de balcão.** As duas bases são frontais e controladas. Mesmo o
   número bom (85,7%) é um **teto otimista** do que se vê nos óculos.
2. **O dataset é desbalanceado por base:** 5 repetições por pessoa na MINDS
   contra 1 na V-LIBRASIL.
3. **O vocabulário não é o do produto** (§2) — o dataset de landmarks valida a
   abordagem, mas não é reaproveitável direto no MVP de atendimento.

Nada disso invalida a PoC: a pergunta era se MediaPipe + classificador simples
generalizam entre pessoas, e a resposta medida é **sim, dentro de um mesmo
domínio de gravação** (77,8% com 8 pessoas e 10 sinais; 85,7% com os rótulos
confiáveis), **não entre domínios diferentes** (46,7%). Isso é informação de
produto, não só de laboratório: os óculos vão encontrar pessoas novas *e*
condições novas, e a segunda parte é a que este dataset mostrou ser mais difícil.

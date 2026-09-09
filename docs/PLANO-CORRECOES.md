# Plano de correções — duas revisões, duas frentes

**Criado em:** 2026-09-09 · **Prazo do hackathon:** 16/09/2026
**Branch:** `claude/libras-detection-model-53kd30`

Este documento consolida duas revisões independentes do repositório e distribui o
trabalho entre duas frentes que vão atuar em paralelo. Ele existe para que ninguém
refaça o que já está feito, ninguém mexa no mesmo arquivo ao mesmo tempo, e cada item
tenha um critério objetivo de "pronto".

| Frente | Responsável | Escopo |
|---|---|---|
| **A** | **Astra** | Achados da própria auditoria: isolamento, rastreabilidade, endurecimento |
| **B** | **Claude** | Achados da revisão de ST-GCN: qualidade do sinal, representação, arquitetura |

**Regra de convivência:** cada frente só edita os arquivos da sua coluna em §4. O
`selftest.py` é compartilhado — quem for mexer, **acrescenta função nova e registra em
`TESTES`**, sem reordenar as existentes, para o merge ser trivial.

---

## 1. O que JÁ foi corrigido (não refazer)

Registrado para evitar retrabalho. Tudo abaixo está commitado e com teste passando.

| Achado | Correção | Onde |
|---|---|---|
| Teste contrastivo nunca rodava (`NameError`) | import adicionado + teste que roda `pretreinar.py` inteiro por subprocess | `treino/selftest.py` |
| Validação contrastiva elegia lote vazio | validação passou a reservar um articulador inteiro e medir **recuperação top-1**; perda 0.0 de lote sem par não decide mais nada | `treino/pretreinar.py`, `treino/contrastivo.py` |
| `AmostradorPK` prometia mais índices do que emitia | `P = min(P, nº classes elegíveis)`; falha explícita se não houver par | `treino/contrastivo.py` |
| `-inf × 0 = NaN` na perda SupCon | `torch.where` no lugar da multiplicação por máscara | `treino/contrastivo.py` |
| Vazamento dos 30 clipes de avaliação no pré-treino | exclusão pela **origem no zip**, não pelo nome (3 sinais têm rótulos diferentes entre bases) | `datasets/ingest_pretreino.py` |

**Números medidos que motivaram as correções**, para quem quiser reproduzir:

```
validação contrastiva (antes):  12 de 406 clipes (3,0%) tinham par positivo
                                1 lote em 7 com ZERO pares → perda 0.0 → "melhor época"
amostrador (antes):             2 classes com P=32 → len()=64, emitidos=4
```

---

## 2. FRENTE A — Astra

Itens levantados pela própria auditoria e ainda em aberto.

### A1. Auditoria obrigatória na entrada do pré-treino
**Problema:** `carregar_corpora()` concatena diretórios sem verificar nada. É possível
passar o MINDS inteiro como corpus e contaminar todas as rodadas de LOSO seguintes, sem
nenhum aviso.

**Fazer:** antes de treinar, abortar se — (a) qualquer clipe do corpus coincidir com o
conjunto de avaliação (por origem, não por nome); (b) o mesmo diretório for passado duas
vezes; (c) houver landmarks duplicados por hash; (d) aparecer pessoa com prefixo `M`
(MINDS) num corpus de pré-treino.

**Pronto quando:** um teste passa o MINDS como `--corpus` e o processo **falha com
mensagem explícita** em vez de treinar.

**Arquivos:** `treino/pretreinar.py`

---

### A2. Manifesto ausente não pode virar exclusão vazia
**Problema:** `origens_da_avaliacao()` devolve `set()` se o `manifest.csv` não existir — e
o pré-treino segue sem nenhuma proteção, silenciosamente.

**Fazer:** falhar alto quando o manifesto não existir, ou exigir `--sem-manifesto`
explícito para prosseguir.

**Pronto quando:** renomear o `manifest.csv` faz a ingestão abortar.

**Arquivos:** `datasets/ingest_pretreino.py`

---

### A3. Rastreabilidade por amostra
**Problema:** um `.npy` não sabe de onde veio. Não dá para auditar depois se um clipe
entrou onde não devia.

**Fazer:** registrar por amostra — fonte, caminho de origem no zip, articulador, classe,
hash do vídeo e configuração de extração (nº de pontos, dims). Os landmarks devem herdar
essa identidade.

**Pronto quando:** dado um `.npy`, é possível dizer de qual vídeo e de qual bundle ele veio.

**Arquivos:** `datasets/*.py`, formato de manifesto

---

### A4. Proveniência no checkpoint
**Fazer:** gravar no `.pt` — hash do manifesto, partição usada, commit do código e
configuração completa. Hoje o `meta` guarda parte disso.

**Pronto quando:** um checkpoint permite reconstruir exatamente com que dados foi treinado.

**Arquivos:** `treino/modelo.py`, `treino/pretreinar.py`

---

### A5. Notebook não executa o estágio de pré-treino
**Problema:** `notebook_gpu.ipynb` só roda `treinar.py`. O `--objetivo` continua com
padrão `classificacao`, então quem seguir o notebook não roda o contrastivo.

**Fazer:** acrescentar célula de pré-treino contrastivo + fine-tuning, com o `--objetivo`
explícito.

**Pronto quando:** rodar o notebook de cima a baixo executa o experimento completo.

**Arquivos:** `treino/notebook_gpu.ipynb`

---

## 3. FRENTE B — Claude

Itens da revisão de ST-GCN. Três dos quatro pontos daquela revisão **já estão
implementados** e não entram aqui: nós de mão (21×2) e âncoras faciais, reamostragem
temporal por interpolação, e normalização por ombros. O que falta:

### B1. Imputação de lacunas nos landmarks ⚠️ PRIORIDADE MÁXIMA
**Problema, medido:**

```
frames sem NENHUMA mão detectada:   51,7%
frames sem mão esquerda:            88,8%
frames sem mão direita:             52,1%
salto quando a mão some/volta:      1,09 unidades de ombro (mediana)
faixa total dos dados:              ~3,4 unidades
```

Mão não detectada vira **zeros**, que é a origem (meio dos ombros). Cada
aparecimento/desaparecimento injeta um salto de ~30% da amplitude total dos dados. Nós
criamos essa descontinuidade na extração.

**Já descartado por medição:** `model_complexity=2` (44,7% vs 47,4%), `Hands` isolado, e
recorte ao redor do pulso (+5,3 pp apenas). O problema não se resolve melhorando detecção.

**Fazer:** interpolação (spline cúbica) sobre lacunas curtas — a literatura reporta ≥4pp
de F1 no MINDS e >15pp no UFOP com exatamente isso. Lacunas longas continuam marcadas
como ausência, não inventadas.

**Decisão de projeto:** imputar **na leitura**, não na extração. O `.npy` guarda a verdade
crua (zeros = ausência); o carregador preenche. Assim não é preciso reextrair 830 clipes
(horas), a mudança é reversível e testável, e o artefato caro permanece intacto.
Detecção de ausência: os 21 pontos de uma mão **exatamente** zerados.

**Pronto quando:** (a) teste mostra lacuna curta preenchida com continuidade e lacuna
longa preservada como ausência; (b) o salto mediano nas transições cai de 1,09 para perto
de zero; (c) LOSO da ResNet roda com e sem imputação, na mesma configuração, e a
diferença é reportada com a ressalva de variância (±1,7 pp).

**Arquivos:** `treino/dados.py`, `treino/selftest.py`

---

### B2. Two-stream: juntas + ossos
**Problema:** hoje o modelo consome só coordenadas (x, y). Os modelos de referência usam
dois fluxos — juntas e **ossos** (vetores entre juntas conectadas) — e combinam na
classificação.

**Por que importa além da acurácia:** vetor de osso é invariante a translação e muito mais
estável a mudança de ponto de vista que a coordenada absoluta. É a lacuna de ângulo de
câmera que já está registrada em `decisao-arquitetura-modelo.md` §4 e que **nenhuma base
pública nossa permite medir** (são todas estúdio frontal).

**Fazer:** derivar o fluxo de ossos a partir das arestas do grafo, treinar os dois e
combinar. Vale para as duas arquiteturas.

**Pronto quando:** LOSO com e sem o segundo fluxo, mesma configuração, diferença acima do
ruído de ±1,7 pp.

**Arquivos:** `treino/representacao.py`, `treino/gcn.py`

---

### B3. Adjacência adaptativa (2s-AGCN)
**Problema:** a máscara de importância de aresta atual **só pondera arestas existentes**.
Onde a adjacência é zero, qualquer peso continua zero — então o modelo **não consegue
criar uma conexão mão↔rosto**, por mais útil que ela fosse. Hoje o caminho da mão até o
nariz tem 4 saltos.

**Fazer:** matriz aprendida somada à anatômica, permitindo conexões novas.

**Prioridade:** baixa. É específico do GCN, que hoje está 18 pontos atrás da ResNet e
**não é o modelo do MVP**. Fazer só se B1 e B2 fecharem antes do prazo.

**Arquivos:** `treino/gcn.py`

---

### B4. Calibrar o campo receptivo temporal
Kernel temporal 9, 4 blocos, 2 com passo 2 — o campo receptivo cobre aproximadamente o
clipe inteiro. Não está errado, está **não calibrado**: nunca varremos esse parâmetro.

**Prioridade:** baixa, mesma justificativa de B3.

**Arquivos:** `treino/gcn.py`

---

## 4. Mapa de arquivos — quem edita o quê

| Arquivo | Frente A (Astra) | Frente B (Claude) |
|---|---|---|
| `treino/pretreinar.py` | ✅ A1, A4 | — |
| `datasets/ingest_pretreino.py` | ✅ A2 | — |
| `datasets/*.py` (manifesto) | ✅ A3 | — |
| `treino/modelo.py` | ✅ A4 | — |
| `treino/notebook_gpu.ipynb` | ✅ A5 | — |
| `treino/dados.py` | — | ✅ B1 |
| `treino/representacao.py` | — | ✅ B2 |
| `treino/gcn.py` | — | ✅ B2, B3, B4 |
| `treino/contrastivo.py` | congelado — já corrigido | congelado |
| `treino/selftest.py` | **compartilhado** | **compartilhado** |
| `treino/treinar.py` | **compartilhado** — avisar antes | **compartilhado** — avisar antes |

**No `selftest.py`:** acrescente função nova ao final e registre em `TESTES`. Não reordene
nem edite testes existentes.

---

## 5. Correção de documentação — pendente, sem dono definido

A auditoria apontou, com razão, um erro de afirmação nossa (não de código):

> Os 30 clipes da V-LIBRASIL reservados como "teste de domínio" **deixam de sê-lo** depois
> de pré-treinar no restante da V-LIBRASIL. Excluímos os 30 vídeos exatos, mas os **mesmos
> 3 articuladores** e o mesmo domínio visual aparecem nos outros 4.023 clipes do corpus.
> Eles não representam mais nem pessoas nem domínio inéditos.

**Fazer:** corrigir `CONTEXTO.md`, `investigacao-expansao-dataset.md` e
`vocabulario-mvp-proposta.md`, rebaixando aquele conjunto de "teste de domínio" para
"conjunto reservado de clipes, com a ressalva de que os articuladores aparecem no
pré-treino". O teste de domínio de verdade só existe com a coleta própria.

Também vale registrar que **compartilhar classes entre bases não é contaminação** em
transferência supervisionada — não é preciso excluir automaticamente palavras que existem
no MINDS.

---

## 6. Ordem sugerida e o que não fazer

**Ordem:** A1/A2 (isolamento) e B1 (imputação) primeiro, em paralelo. São os dois que
protegem ou melhoram **todos** os experimentos seguintes. Só depois rodar o pré-treino
contrastivo — rodá-lo antes de B1 significaria refazer com landmarks limpos.

**O que não fazer agora:**
- Não incluir a malha facial densa (468 pontos). Está fora do escopo por decisão de
  arquitetura, exigiria reextração completa e mudaria o vocabulário, que foi escolhido
  justamente para evitar sinais dependentes de expressão facial.
- Não trocar a arquitetura do MVP. ResNet-18 está decidida (92-93,5% contra 73,9% do GCN).
- Não comparar números com diferença menor que ~2 pontos: a mesma configuração oscila
  ±1,7 pp entre execuções. Medido em três execuções: 93,4 / 93,5 / 91,8.

---

## 7. Como validar que nada quebrou

```bash
cd computer-vision-model/treino   && python selftest.py    # 12 testes
cd computer-vision-model/datasets && python selftest.py
cd computer-vision-model/PoC      && python src/selftest.py
```

O selftest do treino inclui **controle negativo**: com rótulos aleatórios, a acurácia tem
de ficar na chance. Se subir, há vazamento entre treino e teste.

⚠️ **`ast.parse` não substitui rodar o teste.** Foi assim que um teste com `NameError`
entrou no repositório: a sintaxe estava válida e o nome não existia. Rode o arquivo.

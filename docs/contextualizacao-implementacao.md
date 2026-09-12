# Implementação do modelo de contextualização — do corpus ao `.tflite`

> **Status (2026-09-12): executado.** Corpus, poda, três rodadas de fine-tuning
> (`resultados-v1`, `-v2`, `-v3`), export e integração no app estão feitos. O
> experimento em produção é o **v2** (padrão de `exportacao/para_tflite.py`).

> **Runbook.** O *porquê* de cada decisão está em
> [`contextualizacao-glosa-seq2seq-plano.md`](./contextualizacao-glosa-seq2seq-plano.md);
> aqui é o *como*: pastas, scripts, artefatos, ordem de execução e o que
> entra no git. Referências a `§N` sem outro qualificador apontam para
> aquele documento.
>
> **Escopo:** da existência do corpus de fine-tuning até o `.tflite`
> entregue em `mobile-app-companion/app/src/main/assets/`. O consumo pelo
> app (`GlossContextualizer`) é §3 do plano, não se repete aqui.

---

## 0. Comece pelo fim: o spike de export

**Antes de gerar corpus, antes de treinar qualquer coisa**, faça o passo
7 com um `ptt5-small` **não treinado**. É a mesma lógica de isolamento de
risco que `computer-vision-model/README.md` aplica à PoC ("antes de
treinar o `.tflite`, uma PoC decide se vale a pena").

A razão: o passo de maior risco técnico deste pipeline não é o treino —
é converter um encoder-decoder PyTorch para um `.tflite` com **duas
assinaturas e KV cache** (§6.3). Se esse caminho não fechar, muda o
modelo, muda o plano, e não adianta ter corpus perfeito.

```bash
# spike descartável, ~1 dia
python -c "…carrega ptt5-small, exporta encode/decode_step, roda 1 inferência…"
```

**Critério de sucesso:** um `.tflite` que aceita `runSignature("encode")`
e `runSignature("decode_step")` e devolve logits com o shape esperado.
Qualidade não importa — o modelo nem foi treinado.

**Se falhar**, as saídas em ordem: (a) reescrever o decoder em Keras e
carregar os pesos do PyTorch; (b) laço de tamanho fixo sem KV cache
(mais lento, mais simples); (c) reconsiderar a arquitetura. Nenhuma é
barata — por isso este passo vem primeiro.

---

## 1. Onde isto mora

### 1.1 Não dentro de `computer-vision-model/`

A pasta se chama assim porque é isso que ela é. Este modelo não tem visão
computacional nenhuma: entra texto (glosas), sai texto (português). Mas o
nome é o argumento mais fraco — os três que pesam:

| | `computer-vision-model/` | contextualização |
|---|---|---|
| **Stack** | mediapipe, opencv, tensorflow | torch, transformers, sentencepiece |
| **Política de dados** | vídeos **não** versionados (24 GB, licença da V-LIBRASIL proíbe redistribuir); versiona-se a *receita* (`datasets/selecao.yaml`, `ingest.py`) | corpus **versionado** (poucos MB, sintético, e §5.1.2 exige corpus congelado para treinos comparáveis) |
| **Avaliação** | leave-one-signer-out sobre pessoas | conjunto humano de §5.3 |

A política de dados é a incompatibilidade real: as duas pastas têm regras
**opostas** sobre o que entra no git. Juntá-las cria uma pasta onde
"dados não são versionados, exceto quando são" — e essa é exatamente a
classe de confusão que `datasets/README.md` se esforça pra evitar.

Somado a isso, um `requirements.txt` único instalaria MediaPipe e OpenCV
pra treinar um modelo de texto, e PyTorch pra extrair landmarks.

### 1.2 A proposta: `contextualization-model/`, irmã

```
libras-livre-ai-glasses-brasil/
├── computer-vision-model/      ← sinais → glosa     (.tflite #1)
├── contextualization-model/    ← glosa → português  (.tflite #2)   ◄ NOVA
├── mobile-app-companion/       ← consome os dois
└── docs/
```

O nome espelha `computer-vision-model/` na forma e no papel: uma pasta,
um modelo, um `.tflite`. O README raiz já descreve o projeto como "duas
trilhas que se encontram em um único arquivo" — isso vira **duas trilhas
de IA e uma de app, encontrando-se em dois arquivos**, e a tabela
"Pergunta / Quem responde" ganha uma linha:

| Pergunta | Quem responde |
|---|---|
| *Que sinal é este?* | `computer-vision-model` (Python) |
| *Onde um sinal termina?* | o app (Kotlin) |
| **_Como isso vira uma frase em português?_** | **`contextualization-model` (Python)** |
| *Como virar fala?* | o app (Kotlin) |

### 1.3 Alternativas consideradas

| Opção | Veredito |
|---|---|
| Subpasta `computer-vision-model/contextualizacao/` | Funciona, e é o caminho se você preferir um ambiente Python só. Custa herdar um nome errado e misturar as duas políticas de versionamento de dados (§1.1). |
| Renomear `computer-vision-model/` → `ai-models/` com duas subpastas | Estruturalmente o mais correto. **Não recomendado agora:** quebra caminhos em todos os docs, READMEs e nos `sys.path.insert` dos scripts. Fica registrado como refactor futuro, se um terceiro modelo aparecer. |
| **Pasta irmã `contextualization-model/`** | **Recomendada.** Custo de adoção quase zero, nenhuma migração, e a simetria com a pasta existente explica sozinha o que ela é. |

### 1.4 O que se reaproveita (convenções, não código)

De `computer-vision-model/`, o que vale copiar:

1. **A regra de `.gitignore` do `treino/`** — a melhor convenção do repo:
   ```
   resultados-*/*
   !resultados-*/relatorio.md
   ```
   *Pesos são regeneráveis; o relatório é entregável.*
2. **Sidecars de proveniência.** `datasets/proveniencia.py` tem
   `hash_arquivo()` e `escrever()` (gravação atômica) — ~20 linhas.
   **Copiar, não importar entre pastas:** extrair um pacote compartilhado
   por 20 linhas custa mais do que duplica.
3. **`selecao.yaml` como receita escrita à mão**, `manifest.csv` como
   índice gerado. Mesmo par aqui: `sequencias.yaml` / `pares.manifest.json`.
4. **Nomes de módulo em português** (`dados.py`, `treinar.py`,
   `modelo.py`), como em `treino/`.
5. **`relatorio.md` com data, protocolo e a linha de baseline no topo** —
   o formato de `treino/resultados-gcn/relatorio.md`.

---

## 2. Estrutura de arquivos

```
contextualization-model/
├── README.md
├── requirements.txt
├── config.yaml                     # hiperparâmetros e caminhos
├── .gitignore
│
├── corpus/
│   ├── sequencias.yaml             # ✅ receita: sequências de glosas enumeradas à mão
│   ├── gerar.py                    #    Rota B — LLM escreve as paráfrases PT
│   ├── verificar.py                #    Rota C — ida-e-volta pelo text-core do VLibras
│   ├── ruido.py                    #    §5.2 — dropout/troca/permutação de glosas
│   ├── pares.jsonl                 # ✅ GERADO, mas VERSIONADO (§5.1.2)
│   └── pares.manifest.json         # ✅ hash, contagens, modelo gerador, data
│
├── lexico/
│   └── lexico-glosas.json          # ✅ §5.4 — glosa → formas aceitáveis em PT
│
├── modelo/
│   ├── podar.py                    #    §6.1.2 — poda de vocabulário
│   ├── dados.py                    #    Dataset/collate, teacher forcing
│   ├── treinar.py                  #    fine-tuning
│   └── avaliar.py                  #    §10 — as cinco métricas
│
├── exportacao/
│   ├── para_tflite.py              #    §6.3 — duas assinaturas + quantização
│   ├── paridade.py                 #    Python ↔ Kotlin, mesmas saídas
│   └── entregar.py                 #    copia p/ mobile-app-companion/…/assets/
│
├── avaliacao/
│   └── conjunto_humano.jsonl       # ✅ §5.3 — pequeno, caro, é a única evidência
│
├── artefatos/                      # ❌ .gitkeep só
│   ├── tokenizer_podado/
│   ├── glosa_ids.json              # ✅ exceção — é contrato com o app (§5)
│   ├── destokenizar.json           # ✅ exceção — idem
│   ├── modelo.tflite               # ❌ regenerável
│   └── modelo.tflite.proveniencia.json   # ✅ carimbo que amarra tudo (§6)
│
└── resultados-<experimento>/
    └── relatorio.md                # ✅ único arquivo versionado da pasta
```

---

## 3. O pipeline, em ordem

```
 corpus/pares.jsonl                                    ← passo 1
        │
 ptt5-small (HF)  ──┐
        │           ▼
        └────► PODA de vocabulário (32k → ~4k)         ← passo 2   [antes do treino]
                    │
                    ▼
              FINE-TUNING                               ← passo 3
                    │
                    ▼
              avaliação em PyTorch  ──── portão 1 (§10)  ← passo 4
                    │
                    ▼
        CONVERSÃO .tflite (2 assinaturas) + INT8        ← passo 5
                    │
                    ▼
        avaliação do .tflite ──── portão 2 (§10)         ← passo 6
                    │
                    ▼
        paridade Python ↔ Kotlin + entrega              ← passo 7
```

**Dois pontos que se erra com frequência:**

- **A poda vem antes do fine-tuning**, não depois (§6.1.2, decisão 1).
  Assim o modelo avaliado no portão 1 é exatamente o que vai embarcar.
- **O §10 é verificado duas vezes.** Quantização não é transformação
  neutra: um modelo que bate o template em PyTorch pode não bater
  quantizado. Quem decide a entrada no APK é o portão 2.

---

## 4. Passo 1 — Corpus

Pré-requisito: `lexico/lexico-glosas.json` (§5.4) e o vocabulário fechado
já validado com o consultor.

```bash
python corpus/gerar.py       --sequencias corpus/sequencias.yaml --n-parafrases 8
python corpus/verificar.py   --entrada corpus/pares.brutos.jsonl   # Rota C, opcional
python corpus/ruido.py       --entrada corpus/pares.jsonl --taxa-dropout 0.15
```

`sequencias.yaml` segue a forma de `datasets/selecao.yaml` — receita
escrita à mão, revisada por pessoa:

```yaml
sequencias:
  - glosas: [eu, dor, cabeça]
    contexto: queixa
  - glosas: [banheiro, onde]
    contexto: direcao
```

`pares.jsonl`, uma linha por exemplo:

```json
{"glosas": ["eu","dor","cabeça"], "pt": "estou com dor de cabeça", "origem": "llm", "seq_id": 12}
```

**O gerador precisa ser determinístico o suficiente para ser auditável:**
registre em `pares.manifest.json` o modelo usado (ex.: `claude-opus-5`),
o prompt, a data, a contagem por `seq_id` e o SHA-256 de `pares.jsonl`.
Sem isso, "regerar o corpus" produz outro corpus e nenhum treino é
comparável com o anterior (§5.1.2, ressalva 1).

---

## 5. Passo 2 — Poda de vocabulário

```bash
python modelo/podar.py --checkpoint unicamp-dl/ptt5-small --vocab-alvo 4000
```

Produz, em `artefatos/`:

| Arquivo | Conteúdo | Consumidor |
|---|---|---|
| `tokenizer_podado/` | tokenizer + `remap.json` (`id_antigo → id_novo`) | treino, avaliação |
| `modelo_podado/` | checkpoint com `shared.weight` fatiado | treino |
| `glosa_ids.json` | `{"banheiro": [412], …}` — ~60 entradas | **o app** |
| `destokenizar.json` | `["<pad>", "</s>", "▁estou", …]` — ~4k strings | **o app** |

Os dois últimos são §6.1.3: como a entrada é vocabulário fechado e a
saída só precisa de destokenização, **o app não precisa de SentencePiece
em runtime** — só de duas tabelas. É o mesmo script que gera as duas, o
que elimina a maior fonte de descompasso treino/inferência.

Checklist do §6.1.2: podar antes do treino; manter margem (tokens
frequentes do PT além dos observados, e o `<unk>`); descartar os
`<extra_id_*>`.

---

## 6. Passo 3 — Fine-tuning

```bash
python modelo/treinar.py --config config.yaml --experimento v1
```

`config.yaml`, o que importa:

```yaml
modelo:
  checkpoint: artefatos/modelo_podado
  max_tokens_saida: 24          # fixa o KV cache no export — §6.3
treino:
  lr: 1.0e-4                    # fine-tuning, NÃO 1e-3 (§6.1.4)
  otimizador: adafactor
  epocas: 10
  batch: 32
  semente: 42
```

`dados.py` monta `labels` e deixa a HuggingFace derivar
`decoder_input_ids` (teacher forcing — §6.1.4).

Grava em `resultados-v1/`: pesos (ignorados pelo git) e `relatorio.md`
(versionado), no formato de `treino/resultados-gcn/relatorio.md` — data,
protocolo, baseline na primeira linha.

---

## 7. Passo 4 — Portão 1

```bash
python modelo/avaliar.py --modelo resultados-v1 --conjunto avaliacao/conjunto_humano.jsonl
```

As cinco métricas de §10, nesta ordem de importância:

| Métrica | Alvo |
|---|---|
| Acerto de negação | **1,00 — inegociável** |
| Content-word recall | ≥ 0,98 |
| Taxa de fallback | medir |
| Exact match | > template |
| Avaliação humana | > template |

**O portão é relativo:** o comparativo obrigatório é o
`TemplateGlossContextualizer` (§4) rodando no mesmo conjunto. Um modelo
que não bate 200 linhas de regras não segue para o passo 5.

---

## 8. Passo 5 — Export e quantização

```bash
python exportacao/para_tflite.py --modelo resultados-v1 --quantizacao int8-dynamic
```

Duas assinaturas (§6.3):

```
encode:       input_ids [1,S]                                    → encoder_hidden [1,S,512]
decode_step:  decoder_input_ids [1,1], encoder_hidden, kv_cache  → logits [1,1,V], kv_cache
```

`max_tokens_saida` fixa o shape do KV cache **no export** — não é
parâmetro de runtime.

Quantização acontece **durante** a conversão, não depois: int8
dynamic-range (pesos int8, ativações float), que não exige dataset
representativo (§6.2). Se o portão 2 reprovar, a saída é *quantization-aware
training* — e aí a quantização volta pra dentro do passo 3.

Caminho de conversão a validar no spike do §0: `ai-edge-torch`
(PyTorch → TFLite, com suporte a múltiplas assinaturas) como primeira
opção, ONNX como alternativa. **Fixar a versão exata da ferramenta no
`requirements.txt`** — conversores mudam comportamento entre versões
menores.

---

## 9. Passo 6 e 7 — Portão 2, paridade e entrega

```bash
python modelo/avaliar.py --tflite artefatos/modelo.tflite --conjunto avaliacao/conjunto_humano.jsonl
python exportacao/paridade.py --n 100
python exportacao/entregar.py
```

**Portão 2:** as mesmas métricas do §7, agora sobre o artefato final.
Registrar a queda em relação ao portão 1 no `relatorio.md` — ela é
informação, não ruído.

**Paridade:** ≥ 100 glossários produzindo **a mesma sequência de tokens**
em Python e em Kotlin. É o teste que pega erro de tabela de
destokenização, de `remap`, de token especial — exatamente a classe de
falha que o header do `LandmarkNormalizer.kt` documenta: não quebra com
erro, só degrada em silêncio.

**Entrega:** copia para `mobile-app-companion/app/src/main/assets/`:

```
modelo_contextualizacao.tflite
glosa_ids.json
destokenizar.json
```

E lembre do `noCompress += "tflite"` no `app/build.gradle.kts` (§7.3).

---

## 10. Versionamento — o que entra no git

| Artefato | Git | Motivo |
|---|---|---|
| `corpus/sequencias.yaml` | ✅ | receita escrita à mão |
| `corpus/pares.jsonl` | ✅ | §5.1.2 — sem corpus congelado, treinos não são comparáveis |
| `corpus/pares.manifest.json` | ✅ | hash, gerador, data |
| `lexico/lexico-glosas.json` | ✅ | usado em 3 lugares (§5.4) |
| `avaliacao/conjunto_humano.jsonl` | ✅ | pequeno, caro, única evidência real |
| `artefatos/glosa_ids.json` | ✅ | **contrato com o app** |
| `artefatos/destokenizar.json` | ✅ | **contrato com o app** |
| `artefatos/*.proveniencia.json` | ✅ | carimbo (§11) |
| `resultados-*/relatorio.md` | ✅ | entregável |
| `artefatos/*.tflite` | ❌ | regenerável; distribuir por release |
| `artefatos/modelo_podado/` | ❌ | centenas de MB |
| `resultados-*/` (resto) | ❌ | pesos regeneráveis |

```gitignore
# contextualization-model/.gitignore
resultados-*/*
!resultados-*/relatorio.md
artefatos/*
!artefatos/.gitkeep
!artefatos/glosa_ids.json
!artefatos/destokenizar.json
!artefatos/*.proveniencia.json
```

**A assimetria que exige atenção:** o `.tflite` **não** é versionado, mas
as tabelas que ele exige **são**. Elas podem dessincronizar sem nenhum
erro visível — o modelo roda, produz ids, a tabela traduz errado, e sai
português plausível e incorreto. É §8.1 entrando por outra porta. O §11
existe pra fechar isso.

---

## 11. Proveniência — o carimbo que amarra tudo

Mesma ideia dos sidecars de `datasets/proveniencia.py`, aplicada ao
artefato final. `artefatos/modelo.tflite.proveniencia.json`, versionado:

```json
{
  "gerado_em": "2026-09-11T14:02:00",
  "git_commit": "0c5f255",
  "corpus_sha256": "…",
  "corpus_pares": 24817,
  "checkpoint_base": "unicamp-dl/ptt5-small",
  "vocab_podado": 4000,
  "tflite_sha256": "…",
  "glosa_ids_sha256": "…",
  "destokenizar_sha256": "…",
  "metricas_portao_2": { "negacao": 1.0, "content_recall": 0.985, "exact_match": 0.62 }
}
```

Com isso, qualquer `.tflite` que apareça é rastreável até o corpus e o
commit que o produziram, e `entregar.py` pode **recusar a cópia** se os
hashes das tabelas não baterem com os do sidecar — que é o ponto: a
guarda é executável, não uma convenção que alguém precisa lembrar.

---

## 12. Checklist de execução

- [ ] **Passo 0** — spike de export com modelo não treinado (§0). *Bloqueia tudo.*
- [ ] Criar `contextualization-model/` com a estrutura de §2 e o `.gitignore` de §10.
- [ ] `lexico/lexico-glosas.json` — também destrava a Fase 1 do plano (template).
- [ ] `corpus/sequencias.yaml` revisado por consultor.
- [ ] Passo 1 — corpus + manifesto.
- [ ] Passo 2 — poda, com as 4 saídas de §5.
- [ ] Passo 3 — fine-tuning.
- [ ] Passo 4 — portão 1 **contra o template**.
- [ ] Passo 5 — export + int8.
- [ ] Passo 6 — portão 2 + paridade.
- [ ] Passo 7 — entrega + sidecar de proveniência.
- [ ] Atualizar o README raiz (diagrama das trilhas e tabela "Pergunta / Quem responde", §1.2).

---

## 13. Referências

- [`contextualizacao-glosa-seq2seq-plano.md`](./contextualizacao-glosa-seq2seq-plano.md) — o porquê de tudo aqui.
- `computer-vision-model/datasets/README.md` — a convenção de receita-versionada/dados-não.
- `computer-vision-model/datasets/proveniencia.py` — sidecars e hashes; a origem de §11.
- `computer-vision-model/treino/.gitignore` — a regra `resultados-*/relatorio.md`.
- `computer-vision-model/treino/resultados-gcn/relatorio.md` — formato de relatório.
- `README.md` (raiz) — diagrama das trilhas, a atualizar (§1.2).

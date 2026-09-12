# Contextualization Model — Libras Livre

Segunda trilha de **IA** do projeto. Transforma a lista de **glosas** de sinais
reconhecidos numa **frase em português** falável, num `.tflite` que roda on-device
no app ([`../mobile-app-companion`](../mobile-app-companion)).

Irmã de [`../computer-vision-model`](../computer-vision-model), não parte dela: lá
entra vídeo e sai glosa; aqui entra glosa e sai português. As stacks são diferentes
(torch/transformers contra mediapipe/tensorflow) e as **políticas de versionamento
de dados são opostas**.

```
  computer-vision-model/          contextualization-model/          mobile-app-companion/
  vídeo -> glosa                  glosa -> português                fala
  sinal_classifier.tflite    +    modelo_contextualizacao.tflite  -->  TTS -> A2DP
```

## Por que existe

O app reconhece **um sinal por vez** e acumula uma lista. Essa lista não é
português — Libras tem ordem e estrutura próprias:

```
glosas: [eu, não, querer, vacina]   ->  "eu não quero tomar a vacina"
glosas: [onde, banheiro]            ->  "onde fica o banheiro?"
```

Antes desta trilha, o app falava o glossário cru (`joinToString(" ")` no
`DialogOrchestrator`). Esta pasta produziu o modelo que substituiu esse placeholder.

---

## Estado

| Etapa | Estado |
|---|---|
| Léxico de glosas (41 glosas, 250+ formas) | pronto — **não revisado por consultor** |
| Sequências de glosas (285) | prontas — **não revisadas por consultor** |
| Corpus sintético (2.202 pares) | gerado por `claude-opus-5`, 2026-09-11 |
| Poda de vocabulário (ptt5-small → 45,1M, vocab 1.987) | pronta |
| Fine-tuning | três rodadas: `resultados-v1`, `-v2`, `-v3` |
| Export `.tflite` (duas assinaturas: encode + decode_step) | pronto |
| Integração no app, sob guarda, com template como piso | pronta |
| Conjunto humano de teste | **pendente** — Associação de Surdos de Goiânia |

**O experimento em produção é o `v2`** — é o padrão de
`exportacao/para_tflite.py` e `exportacao/paridade.py`, e o que o
`app/src/main/assets/.gitignore` manda gerar.

### O que as três rodadas mediram

Validação **sintética**, com as referências saindo do mesmo gerador que produziu o
treino. Isto mede se o modelo aprendeu o mapeamento que nós inventamos — não se ele
traduz Libras.

| Métrica | Template (portão) | Modelo (v3) | Alvo |
|---|---|---|---|
| Acerto de negação | 1,000 | 1,000 | 1,000, inegociável |
| Content-word recall | 0,971 | 1,000 | ≥ 0,980 |
| Conteúdo inventado | 0,048 | 0,119 | 0 — a guarda não pega tudo |
| Taxa de fallback | 0,071 | 0,000 | medir em campo |
| Exact match (multi-ref) | 0,429 | 0,429 | > template |
| F1 de palavras | 0,871 | 0,836 | > template |

**Em nenhuma das três rodadas o modelo bateu o template em F1** (0,826 a 0,837
contra 0,871 a 0,884). Foi por isso que o app usa a cadeia
`modelo → guarda → template → passthrough`, com o **template como piso**: a guarda
aceita o modelo em ~95% das sessões, e ele ganha justamente onde há relação
gramatical entre glosas ("banco esquina" → "o banco fica na esquina"), que é o que
uma tabela de regras não faz.

Relatórios completos em `resultados-v{1,2,3}/relatorio.md`.

---

## Como rodar

Ambiente próprio, separado da trilha de visão:

```bash
python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

### 1. Corpus

```bash
python corpus/gerar.py --so-validar    # valida léxico × sequências × paráfrases
python corpus/gerar.py                 # escreve pares.jsonl + manifesto
```

`--so-validar` é, na prática, **um teste do léxico**: aplica a mesma checagem de
cobertura que o `GuardedGlossContextualizer` faz no app. Uma forma faltando aqui
vira fallback desnecessário em produção.

### 2. Poda e fine-tuning

```bash
python modelo/podar.py                             # ptt5-small -> vocabulário do domínio
python modelo/treinar.py --experimento v2 --epocas 30
python modelo/treinar.py --experimento v2 --sem-c3  # sem as glosas de camada 3
python modelo/avaliar.py --experimento v2
```

A poda de vocabulário é o que torna o modelo pequeno o bastante para o celular:
o ptt5-small sai com vocabulário completo e volta com 1.987 tokens, 45,1M de
parâmetros.

### 3. Export e paridade

```bash
python exportacao/para_tflite.py --experimento v2 [--quantizar]
python exportacao/paridade.py --experimento v2      # compara PyTorch × TFLite
cp artefatos/modelo_contextualizacao.tflite \
   ../mobile-app-companion/app/src/main/assets/
```

O `.tflite` exporta **duas assinaturas** — `encode` e `decode_step` — porque um
decoder autorregressivo não cabe numa única chamada de grafo. O app roda o laço de
decodificação em Kotlin (`TfliteGlossContextualizer.kt`).

---

## O que entra no git

O arquivo de 46 MB **não** é versionado; três artefatos pequenos sim, porque são
**contrato com o app** e não podem sair de sincronia com o modelo:

| Arquivo | Papel |
|---|---|
| `artefatos/glosa_ids.json` | mapa glosa → id de token, na ordem que o modelo espera |
| `artefatos/destokenizar.json` | reconstrução do texto a partir dos ids |
| `lexico/lexico-glosas.json` | léxico de glosas e suas formas, também copiado para os assets |

Os diretórios `resultados-*/` seguem a mesma regra do treino de visão: o
**relatório é entregável**, os pesos são regeneráveis.

---

## Estrutura

```
contextualization-model/
├── lexico/lexico-glosas.json     41 glosas, 250+ formas — contrato com o app
├── corpus/
│   ├── sequencias.yaml           285 sequências de glosas (hipótese de LLM)
│   ├── parafrases.yaml           variações de superfície por sequência
│   ├── gerar.py                  escreve pares.jsonl + manifesto de proveniência
│   └── limpar.py                 normalização e deduplicação
├── modelo/
│   ├── podar.py                  poda de vocabulário do ptt5-small
│   ├── treinar.py                fine-tuning, split por seq_id
│   ├── avaliar.py, relatorio.py  métricas e relatório
│   ├── template.py               o contextualizador por regras (o piso)
│   ├── hibrido.py                combinação modelo + template
│   ├── decodificacao.py          busca na decodificação
│   ├── fluencia.py, invencao.py  métricas de fluência e de conteúdo inventado
│   └── dados.py                  carga do corpus
├── exportacao/
│   ├── para_tflite.py            encode + decode_step
│   └── paridade.py               PyTorch × TFLite
├── avaliacao/
│   ├── gerar_combinacoes.py      combinações de glosas fora do treino
│   └── combinacoes_nao_vistas.jsonl
└── resultados-v{1,2,3}/relatorio.md
```

---

## Avisos que não podem ser esquecidos

1. **O corpus é sintético.** Foi escrito por um LLM a partir de sequências de
   glosas que também foram hipótese de um LLM. **Não é Libras observada.** Número
   medido nele não é evidência — só `avaliacao/conjunto_humano.jsonl` seria, e ele
   ainda não existe. Nenhum número deste diretório deve aparecer em apresentação
   sem esta ressalva.
2. **7 das 41 glosas não existem em base pública verificada.** `eu`, `você`,
   `querer`, `precisar`, `onde`, `quanto` e `quando` estão marcadas
   `camada: 3-proposta` no léxico. Boa parte dos pares depende delas — o campo
   `depende_c3` em cada par permite treinar e avaliar com e sem (`--sem-c3`).
3. **O portão de aceite é relativo.** Um modelo que não bate o
   `TemplateGlossContextualizer` no conjunto humano não entra no APK. Como o
   conjunto humano não existe, o que está no APK hoje roda **sob guarda**, com o
   template como fallback.

---

## Documentação

| Documento | O quê |
|---|---|
| [`../docs/contextualizacao-glosa-seq2seq-plano.md`](../docs/contextualizacao-glosa-seq2seq-plano.md) | **o porquê** — decisões, riscos, guardas, critérios de aceite |
| [`../docs/contextualizacao-implementacao.md`](../docs/contextualizacao-implementacao.md) | **o como** — runbook do corpus ao `.tflite`, e o que entra no git |
| [`.../libras/contextualizacao/`](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/README.md) | como o app consome o modelo |

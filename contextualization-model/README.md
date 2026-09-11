# Contextualization Model — Libras Livre

> Segunda trilha de **IA** do projeto. Transforma o **glossário** de sinais
> reconhecidos numa **frase em português** falável, num `.tflite` que roda
> on-device no app (`../mobile-app-companion`).
>
> Irmã de [`../computer-vision-model`](../computer-vision-model), não parte
> dela: lá entra vídeo e sai glosa; aqui entra glosa e sai português. Stacks
> diferentes (torch/transformers vs. mediapipe/tensorflow) e **políticas de
> versionamento de dados opostas** — ver `docs/contextualizacao-implementacao.md` §1.

```
  computer-vision-model/          contextualization-model/        mobile-app-companion/
  vídeo → glosa                   glosa → português               fala
  sinal_classifier.tflite    +    modelo_contextualizacao.tflite   ──►  TTS → A2DP
```

## Por que existe

O app reconhece **um sinal por vez** e acumula uma lista. Essa lista não é
português — Libras tem ordem e estrutura próprias:

```
glosas: [eu, não, querer, vacina]   →  "eu não quero tomar a vacina"
glosas: [onde, banheiro]            →  "onde fica o banheiro?"
```

Hoje o app fala o glossário cru (`DialogOrchestrator.kt:131`,
`joinToString(" ")`). Esta pasta produz o modelo que substitui esse placeholder.

## Documentação

| Documento | O quê |
|---|---|
| [`docs/contextualizacao-glosa-seq2seq-plano.md`](../docs/contextualizacao-glosa-seq2seq-plano.md) | **o porquê** — decisões, riscos, guardas, critérios de aceite |
| [`docs/contextualizacao-implementacao.md`](../docs/contextualizacao-implementacao.md) | **o como** — runbook do corpus ao `.tflite`, e o que entra no git |

## Estado

| Etapa | Estado |
|---|---|
| Léxico de glosas (41 glosas, 250+ formas) | ✅ rascunho, **não revisado por consultor** |
| Sequências de glosas (191) | ✅ rascunho, **não revisadas por consultor** |
| Corpus sintético (1.528 pares) | ✅ gerado por `claude-opus-5`, 2026-09-11 |
| Conjunto humano de teste | ❌ **pendente** — Associação de Surdos de Goiânia |
| Spike de export (§0 do runbook) | ❌ **não feito — bloqueia o resto** |
| Poda + fine-tuning | ❌ |
| `.tflite` | ❌ |

## Começando

```bash
pip install -r requirements.txt
python corpus/gerar.py --so-validar    # valida léxico × sequências × paráfrases
python corpus/gerar.py                 # escreve pares.jsonl + manifesto
```

`gerar.py --so-validar` é, na prática, **um teste do léxico**: ele aplica a mesma
checagem de cobertura que `GuardedGlossContextualizer` faz no app. Uma forma
faltando aqui vira fallback desnecessário em produção.

## Avisos que não podem ser esquecidos

1. **O corpus é sintético.** Foi escrito por um LLM a partir de sequências de
   glosas que também foram hipótese de um LLM. Não é Libras observada. Número
   medido nele não é evidência — só `avaliacao/conjunto_humano.jsonl` é.
2. **7 das 41 glosas não existem em base pública verificada.** `eu`, `você`,
   `querer`, `precisar`, `onde`, `quanto`, `quando` estão marcadas
   `camada: 3-proposta` no léxico. 1.056 dos 1.528 pares dependem delas — o
   campo `depende_c3` em cada par permite treinar/avaliar com e sem.
3. **O portão de aceite é relativo.** Um modelo que não bate o
   `TemplateGlossContextualizer` no conjunto humano não entra no APK.

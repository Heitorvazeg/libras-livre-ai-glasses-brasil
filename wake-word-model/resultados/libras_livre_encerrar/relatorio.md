# Relatório — libras_livre_encerrar

Gerado em 2026-09-12.

## Split de teste sintético (mesmo gerador do treino)

- Positivos: 100, negativos: 170
- Acurácia: 0.837
- Recall (taxa de detecção): 0.580
- Precisão: 0.967
- TP=58 FP=2 FN=42 TN=168

## Falso-positivo em áudio genérico (conjunto de validação do openWakeWord)

- ~2.0 h de fala/ruído/música (majoritariamente inglês — não é pt-BR nem cenário de balcão, só um proxy geral de "áudio comum do dia a dia")
- Falsos positivos: 1
- Falsos positivos por hora: 0.49 (alvo do config: 0.2)

## Curva de limiar (sem retreinar)

Os mesmos scores acima, recalculados em vários limiares de decisão — `OpenWakeWordDetector.kt` usa `threshold` por `WakeWordModel` (ver `DEFAULT_THRESHOLD` em `OpenWakeWordDetector.kt`), então isto é só trocar um número, sem exportar `.onnx` de novo. `0.5` é o ponto usado nas seções acima.

| Limiar | Recall | Precisão | FP/hora (genérico) |
|---|---|---|---|
| 0.1 | 0.610 | 0.924 | 3.45 |
| 0.2 | 0.600 | 0.938 | 1.97 |
| 0.3 | 0.580 | 0.935 | 1.97 |
| 0.4 | 0.580 | 0.935 | 1.48 |
| 0.5 **(atual)** | 0.580 | 0.967 | 0.49 |
| 0.6 | 0.580 | 0.967 | 0.49 |
| 0.7 | 0.560 | 0.982 | 0.49 |
| 0.8 | 0.520 | 0.981 | 0.00 |
| 0.9 | 0.460 | 0.979 | 0.00 |

## O que este número NÃO mede

- Generalização pra vozes/sotaques fora das 6 vozes Piper pt-BR usadas no treino.
- Ambiente real de balcão (ruído de fala cruzada, distância variável do mic dos óculos/celular).
- Confusão com fala pt-BR genérica fora dos confusáveis que escrevemos à mão em dados/frases.py.
- O pool de negativos pré-computado do ACAV100M foi usado neste treino (ver config/*.yaml) — os números de falso-positivo acima já refletem isso.

**Critério de aceite real continua sendo o da Fase 3 do plano** (`docs/orquestracao-dialogo-audio-plano.md`): testar em hardware, com o app em foreground, comparando objetivamente contra o `SpeechRecognizerWakeWordDetector` atual — falso-positivo, falso-negativo, latência, funciona offline — antes de trocar o motor padrão no `DialogOrchestrator`.

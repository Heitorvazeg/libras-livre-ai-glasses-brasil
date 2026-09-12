# Relatório — libras_livre_iniciar

Gerado em 2026-09-12.

## Split de teste sintético (mesmo gerador do treino)

- Positivos: 100, negativos: 110
- Acurácia: 0.810
- Recall (taxa de detecção): 0.630
- Precisão: 0.955
- TP=63 FP=3 FN=37 TN=107

## Falso-positivo em áudio genérico (conjunto de validação do openWakeWord)

- ~2.0 h de fala/ruído/música (majoritariamente inglês — não é pt-BR nem cenário de balcão, só um proxy geral de "áudio comum do dia a dia")
- Falsos positivos: 3
- Falsos positivos por hora: 1.48 (alvo do config: 0.2)

## Curva de limiar (sem retreinar)

Os mesmos scores acima, recalculados em vários limiares de decisão — `OpenWakeWordDetector.kt` usa `threshold` por `WakeWordModel` (ver `DEFAULT_THRESHOLD` em `OpenWakeWordDetector.kt`), então isto é só trocar um número, sem exportar `.onnx` de novo. `0.5` é o ponto usado nas seções acima.

| Limiar | Recall | Precisão | FP/hora (genérico) |
|---|---|---|---|
| 0.1 | 0.740 | 0.902 | 4.43 |
| 0.2 | 0.720 | 0.923 | 3.45 |
| 0.3 | 0.710 | 0.947 | 1.97 |
| 0.4 | 0.680 | 0.958 | 1.48 |
| 0.5 **(atual)** | 0.630 | 0.955 | 1.48 |
| 0.6 | 0.610 | 0.968 | 0.98 |
| 0.7 | 0.570 | 0.966 | 0.98 |
| 0.8 | 0.530 | 0.964 | 0.49 |
| 0.9 | 0.420 | 0.955 | 0.00 |

## O que este número NÃO mede

- Generalização pra vozes/sotaques fora das 6 vozes Piper pt-BR usadas no treino.
- Ambiente real de balcão (ruído de fala cruzada, distância variável do mic dos óculos/celular).
- Confusão com fala pt-BR genérica fora dos confusáveis que escrevemos à mão em dados/frases.py.
- O pool de negativos pré-computado do ACAV100M foi usado neste treino (ver config/*.yaml) — os números de falso-positivo acima já refletem isso.

**Critério de aceite real continua sendo o da Fase 3 do plano** (`docs/orquestracao-dialogo-audio-plano.md`): testar em hardware, com o app em foreground, comparando objetivamente contra o `SpeechRecognizerWakeWordDetector` atual — falso-positivo, falso-negativo, latência, funciona offline — antes de trocar o motor padrão no `DialogOrchestrator`.

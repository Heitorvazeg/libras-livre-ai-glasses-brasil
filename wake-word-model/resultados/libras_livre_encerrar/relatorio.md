# Relatório — libras_livre_encerrar

Gerado em 2026-09-12.

## Split de teste sintético (mesmo gerador do treino)

- Positivos: 100, negativos: 110
- Acurácia: 0.710
- Recall (taxa de detecção): 0.560
- Precisão: 0.767
- TP=56 FP=17 FN=44 TN=93

## Falso-positivo em áudio genérico (conjunto de validação do openWakeWord)

- ~2.0 h de fala/ruído/música (majoritariamente inglês — não é pt-BR nem cenário de balcão, só um proxy geral de "áudio comum do dia a dia")
- Falsos positivos: 569
- Falsos positivos por hora: 280.07 (alvo do config: 0.2)

## O que este número NÃO mede

- Generalização pra vozes/sotaques fora das 6 vozes Piper pt-BR usadas no treino.
- Ambiente real de balcão (ruído de fala cruzada, distância variável do mic dos óculos/celular).
- Confusão com fala pt-BR genérica fora dos confusáveis que escrevemos à mão em dados/frases.py.
- O pool de negativos pré-computado do ACAV100M (~17 GB) foi deliberadamente pulado nesta rodada — ver docs/wake-word-treino-plano.md §3.

**Critério de aceite real continua sendo o da Fase 3 do plano** (`docs/orquestracao-dialogo-audio-plano.md`): testar em hardware, com o app em foreground, comparando objetivamente contra o `SpeechRecognizerWakeWordDetector` atual — falso-positivo, falso-negativo, latência, funciona offline — antes de trocar o motor padrão no `DialogOrchestrator`.

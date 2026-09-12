# Resultado do treino — Skeleton-DML + ResNet-18 (ImageNet)

Gerado em 2026-09-08 15:28 · protocolo leave-one-signer-out (8 rodadas) · 178 min de treino.

## Resultado

**Acurácia signer-independent média = 93.4%** (desvio entre rodadas: 3.6%)

- Chance aleatória com 20 sinais = 5.0%.
- Baseline DTW 1-NN da PoC, mesma métrica: 70,0% (10 sinais, 11 pessoas) — ver `../PoC/results/relatorio.md`.
- Referência publicada no MINDS-Libras com este protocolo: 0,93-0,94 (Alves et al. 2024; dos Santos et al. 2025).

## Acurácia por rodada (pessoa deixada de fora)

| Pessoa | Acurácia |
|---|---|
| M01 | 97.0% |
| M02 | 96.0% |
| M05 | 96.0% |
| M06 | 89.0% |
| M08 | 97.0% |
| M10 | 87.0% |
| M11 | 94.0% |
| M12 | 91.0% |
| **média** | **93.4%** |

## Acurácia por sinal (recall agregado)

| Sinal | Acertos / Clipes | Recall |
|---|---|---|
| medo | 30 / 40 | 75.0% |
| filho | 33 / 40 | 82.5% |
| aproveitar | 35 / 40 | 87.5% |
| banheiro | 35 / 40 | 87.5% |
| conhecer | 36 / 40 | 90.0% |
| ruim | 37 / 40 | 92.5% |
| bala | 37 / 40 | 92.5% |
| barulho | 37 / 40 | 92.5% |
| vacina | 37 / 40 | 92.5% |
| aluno | 38 / 40 | 95.0% |
| banco | 38 / 40 | 95.0% |
| maca | 38 / 40 | 95.0% |
| acontecer | 39 / 40 | 97.5% |
| sapo | 39 / 40 | 97.5% |
| espelho | 39 / 40 | 97.5% |
| esquina | 39 / 40 | 97.5% |
| cinco | 40 / 40 | 100.0% |
| amarelo | 40 / 40 | 100.0% |
| america | 40 / 40 | 100.0% |
| vontade | 40 / 40 | 100.0% |

## Pares mais confundidos

| Verdadeiro | Previsto como | Ocorrências |
|---|---|---|
| medo | ruim | 5 |
| banheiro | sapo | 3 |
| filho | aproveitar | 3 |
| medo | banco | 3 |
| ruim | medo | 3 |
| aluno | vacina | 2 |
| aproveitar | cinco | 2 |
| aproveitar | filho | 2 |
| banco | maca | 2 |
| barulho | bala | 2 |

## Reprodutibilidade

```json
{
  "fontes": "minds",
  "epocas": 30,
  "lr": 0.0001,
  "wd": 0.0001,
  "batch": 32,
  "workers": 2,
  "threads": 10,
  "folds": 0,
  "final": false,
  "saida": "resultados"
}
```

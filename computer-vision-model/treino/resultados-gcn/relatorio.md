# Resultado do treino — ST-GCN (grafo do esqueleto)

Gerado em 2026-09-09 00:22 · protocolo leave-one-signer-out (8 rodadas) · 15 min de treino.

## Resultado

**Acurácia signer-independent média = 44.6%** (desvio entre rodadas: 9.3%)

- Chance aleatória com 20 sinais = 5.0%.
- Baseline DTW 1-NN da PoC, mesma métrica: 70,0% (10 sinais, 11 pessoas) — ver `../PoC/results/relatorio.md`.
- Referência publicada no MINDS-Libras com este protocolo: 0,93-0,94 (Alves et al. 2024; dos Santos et al. 2025).

## Acurácia por rodada (pessoa deixada de fora)

| Pessoa | Acurácia |
|---|---|
| M01 | 42.0% |
| M02 | 51.0% |
| M05 | 58.0% |
| M06 | 48.0% |
| M08 | 37.0% |
| M10 | 41.0% |
| M11 | 27.0% |
| M12 | 53.0% |
| **média** | **44.6%** |

## Acurácia por sinal (recall agregado)

| Sinal | Acertos / Clipes | Recall |
|---|---|---|
| aproveitar | 1 / 40 | 2.5% |
| cinco | 2 / 40 | 5.0% |
| medo | 3 / 40 | 7.5% |
| bala | 5 / 40 | 12.5% |
| banheiro | 5 / 40 | 12.5% |
| acontecer | 14 / 40 | 35.0% |
| ruim | 14 / 40 | 35.0% |
| vacina | 17 / 40 | 42.5% |
| vontade | 17 / 40 | 42.5% |
| sapo | 18 / 40 | 45.0% |
| conhecer | 18 / 40 | 45.0% |
| maca | 20 / 40 | 50.0% |
| filho | 22 / 40 | 55.0% |
| aluno | 22 / 40 | 55.0% |
| espelho | 26 / 40 | 65.0% |
| amarelo | 28 / 40 | 70.0% |
| banco | 28 / 40 | 70.0% |
| barulho | 28 / 40 | 70.0% |
| america | 34 / 40 | 85.0% |
| esquina | 35 / 40 | 87.5% |

## Pares mais confundidos

| Verdadeiro | Previsto como | Ocorrências |
|---|---|---|
| vacina | aluno | 22 |
| bala | banco | 19 |
| aluno | vacina | 18 |
| banheiro | esquina | 16 |
| aproveitar | filho | 14 |
| vontade | conhecer | 14 |
| medo | filho | 13 |
| acontecer | america | 12 |
| amarelo | maca | 11 |
| barulho | espelho | 11 |

## Reprodutibilidade

```json
{
  "arquitetura": "gcn",
  "fontes": "minds",
  "epocas": 30,
  "lr": 0.0001,
  "wd": 0.0001,
  "batch": 64,
  "workers": 2,
  "threads": 10,
  "dispositivo": "cuda",
  "folds": 0,
  "final": false,
  "saida": "resultados-gcn"
}
```

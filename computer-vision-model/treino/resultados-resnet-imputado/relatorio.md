# Resultado do treino — Skeleton-DML + ResNet-18 (ImageNet)

Gerado em 2026-09-09 13:54 · protocolo leave-one-signer-out (8 rodadas) · 276 min de treino.

Orçamento de treino por rodada: ~540 atualizações de peso (30 épocas × lotes de 32). Número relevante quando se compara fine-tuning com treino do zero: a segunda opção precisa de muito mais.

## Resultado

**Acurácia signer-independent média = 95.1%** (desvio entre rodadas: 3.2%)

- Chance aleatória com 20 sinais = 5.0%.
- Baseline DTW 1-NN da PoC, mesma métrica: 70,0% (10 sinais, 11 pessoas) — ver `../PoC/results/relatorio.md`.
- Referência publicada no MINDS-Libras com este protocolo: 0,93-0,94 (Alves et al. 2024; dos Santos et al. 2025).
- Inicializado do ImageNet (sem pré-treino em Libras).
- Lacunas curtas de mão preenchidas por interpolação (<=5 frames).

## Acurácia por rodada (pessoa deixada de fora)

| Pessoa | Acurácia |
|---|---|
| M01 | 99.0% |
| M02 | 97.0% |
| M05 | 98.0% |
| M06 | 93.0% |
| M08 | 98.0% |
| M10 | 89.0% |
| M11 | 94.0% |
| M12 | 93.0% |
| **média** | **95.1%** |

## Acurácia por sinal (recall agregado)

| Sinal | Acertos / Clipes | Recall |
|---|---|---|
| filho | 29 / 40 | 72.5% |
| banheiro | 35 / 40 | 87.5% |
| vontade | 36 / 40 | 90.0% |
| conhecer | 36 / 40 | 90.0% |
| vacina | 36 / 40 | 90.0% |
| medo | 37 / 40 | 92.5% |
| aproveitar | 38 / 40 | 95.0% |
| banco | 38 / 40 | 95.0% |
| maca | 39 / 40 | 97.5% |
| acontecer | 39 / 40 | 97.5% |
| aluno | 39 / 40 | 97.5% |
| barulho | 39 / 40 | 97.5% |
| bala | 40 / 40 | 100.0% |
| espelho | 40 / 40 | 100.0% |
| esquina | 40 / 40 | 100.0% |
| america | 40 / 40 | 100.0% |
| amarelo | 40 / 40 | 100.0% |
| ruim | 40 / 40 | 100.0% |
| sapo | 40 / 40 | 100.0% |
| cinco | 40 / 40 | 100.0% |

## Pares mais confundidos

| Verdadeiro | Previsto como | Ocorrências |
|---|---|---|
| filho | medo | 6 |
| filho | aproveitar | 5 |
| banheiro | acontecer | 3 |
| aproveitar | filho | 2 |
| banco | barulho | 2 |
| banheiro | sapo | 2 |
| conhecer | maca | 2 |
| vacina | conhecer | 2 |
| vontade | amarelo | 2 |
| vontade | filho | 2 |

## Reprodutibilidade

```json
{
  "arquitetura": "resnet",
  "fontes": "minds",
  "sem_imputacao": false,
  "epocas": 30,
  "lr": 0.0001,
  "wd": 0.0001,
  "batch": 32,
  "workers": 1,
  "threads": 3,
  "dispositivo": "cpu",
  "folds": 0,
  "agendador": "nenhum",
  "inicializar": null,
  "final": false,
  "saida": "resultados-resnet-imputado"
}
```

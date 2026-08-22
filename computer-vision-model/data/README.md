# data/

Dados da trilha de IA. **Não versionados** (ver `.gitignore`).

- `raw/` — vídeos brutos. **Um sinal isolado por arquivo** (guia §1.1).
- `landmarks/` — sequências extraídas com MediaPipe, salvas como `.npy` e
  rotuladas pelo nome do sinal (saída de `scripts/01_extract_landmarks.py`).

## De onde vêm os vídeos

Duas origens, e as duas usam a mesma convenção de nome:

1. **Bases públicas** (MINDS-Libras + V-LIBRASIL) — `../datasets/ingest.py` baixa
   os clipes dos 10 sinais do `config.yaml` e os grava já renomeados. É o caminho
   padrão hoje: 11 pessoas diferentes por sinal, sem gravar nada.
   O destino dele é `../PoC/data/raw` (a PoC é quem consome o dataset agora);
   para trazer os mesmos clipes para cá: `python ingest.py --destino ../data/raw`.
2. **Coleta própria** — `../PoC/src/record.py`, seguindo o protocolo do
   [README da PoC](../PoC/README.md) §3: câmera na altura dos olhos, ~1–1,5 m,
   luz de ambiente real.

## Convenção de nome

A mesma da PoC — o nome carrega os metadados, o que dispensa planilha à parte:

```
pessoaM05_sinal-acontecer_rep03.mp4   →  pessoa=M05, sinal=acontecer, repetição=03
```

`pessoa` precisa ser **única por pessoa real**: o prefixo `M`/`V` dos clipes
públicos existe para que o sinalizador 05 de uma base não se confunda com o
articulador 05 da outra. Sem isso, qualquer avaliação leave-one-signer-out passa
a treinar e testar na mesma pessoa sem avisar.

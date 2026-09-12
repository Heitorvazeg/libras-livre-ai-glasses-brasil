# data/

Diretório de dados do andaime legado `../src` + `../scripts` (ver
[`../README.md`](../README.md) §6). **Nada é versionado aqui** (ver `.gitignore`).

- `raw/` — vídeos brutos, um sinal isolado por arquivo.
- `landmarks/` — sequências extraídas, salvas como `.npy`.

> **O pipeline em uso não passa por aqui.** A ingestão escreve em
> `../PoC/data/raw/` e a extração de landmarks em `../PoC/data/landmarks/`, que é
> de onde `../treino/` carrega os dados. Este diretório só é preenchido se você
> apontar a ingestão para cá explicitamente:
>
> ```bash
> cd ../datasets && python ingest.py --destino ../data/raw
> ```

## De onde vêm os vídeos

Duas origens, ambas na mesma convenção de nome:

1. **Bases públicas** — `../datasets/ingest.py` baixa os clipes dos sinais do
   `selecao.yaml` e os grava já renomeados. É o caminho padrão: várias pessoas
   diferentes por sinal, sem gravar nada. Ver
   [`../datasets/README.md`](../datasets/README.md).
2. **Coleta própria** — `../PoC/src/record.py`, seguindo o protocolo do
   [README da PoC](../PoC/README.md) §3: câmera na altura dos olhos, a ~1–1,5 m,
   luz de ambiente real.

## Convenção de nome

O nome carrega os metadados, o que dispensa planilha à parte:

```
pessoaM05_sinal-acontecer_rep03.mp4   ->  pessoa=M05, sinal=acontecer, repetição=03
```

`pessoa` precisa ser **única por pessoa real**. O prefixo de base — `M` (MINDS),
`V` (V-LIBRASIL), `W` (WLASL), `T` (MALTA) — existe para que o sinalizador 05 de
uma base não se confunda com o articulador 05 da outra. Sem isso, qualquer
avaliação *leave-one-signer-out* passa a treinar e testar na mesma pessoa sem
avisar.

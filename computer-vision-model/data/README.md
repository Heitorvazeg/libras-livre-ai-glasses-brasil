# data/

Dados da trilha de IA. **Não versionados** (ver `.gitignore`).

- `raw/` — vídeos brutos. **Um sinal isolado por arquivo** (guia §1.1). Câmera
  frontal, ~1 m, 15–20 repetições por sinal variando pessoa/luz/velocidade.
- `landmarks/` — sequências extraídas com MediaPipe, salvas como `.npy` e
  rotuladas pelo nome do sinal (saída de `scripts/01_extract_landmarks.py`).

Convenção de rótulo (a definir): subpasta por sinal, ex. `raw/dor/*.mp4`, ou
prefixo no nome do arquivo, ex. `dor_01.mp4`.

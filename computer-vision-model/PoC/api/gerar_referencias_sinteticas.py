"""Gera referências SINTÉTICAS em data/landmarks/ — SÓ PARA TESTAR O ENCANAMENTO.

⚠️  ATENÇÃO: estes .npy NÃO são dados reais de Libras. Servem apenas para a API
    ter referências carregadas (GET /health > 0) e /classify responder 200,
    permitindo testar o loop app → API → voz ANTES de processar o dataset real.
    A classificação resultante é ARBITRÁRIA e não significa nada.

    Para dados de verdade, apague estes arquivos e rode o pipeline real:
        cd computer-vision-model/datasets && python ingest.py --reps 1
        cd ../PoC && python src/extract.py

Cada clipe segue a convenção de nome do pipeline (pessoaXX_sinal-YYY_repNN) e o
formato que extract.py produz: float32 (num_frames, num_pontos, 3), já
"normalizado" (valores na escala de ombros). O prefixo de pessoa é 'S' (sintético),
distinto do 'M'/'V' das bases reais.

Uso (a partir de PoC/):
    python api/gerar_referencias_sinteticas.py            # 3 pessoas × 2 reps por sinal
    python api/gerar_referencias_sinteticas.py --limpar   # apaga os sintéticos antes
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

_SRC = Path(__file__).resolve().parent.parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from config import load_config  # noqa: E402


def clipe_sintetico(idx_sinal: int, semente: int, n_frames: int, num_pontos: int) -> np.ndarray:
    """Trajetória determinística e distinta por sinal (+ ruído leve por pessoa/rep)."""
    rng = np.random.default_rng(semente)
    t = np.linspace(0, 2 * np.pi, n_frames)
    # Frequência/fase específicas do sinal deixam as classes separáveis pelo 1-NN.
    freq = 1.0 + idx_sinal * 0.35
    fase = idx_sinal * 0.6
    seq = np.zeros((n_frames, num_pontos, 3), dtype=np.float32)
    for p in range(num_pontos):
        amp = 0.5 + (p % 7) * 0.1
        seq[:, p, 0] = amp * np.sin(freq * t + fase + p * 0.05)
        seq[:, p, 1] = amp * np.cos(freq * t + fase + p * 0.05)
        seq[:, p, 2] = 0.1 * np.sin(freq * t + p * 0.1)
    seq += rng.normal(0, 0.02, seq.shape).astype(np.float32)  # variação entre pessoas/reps
    return seq


def main() -> None:
    ap = argparse.ArgumentParser(description="Gera referências SINTÉTICAS (só encanamento).")
    ap.add_argument("--pessoas", type=int, default=3, help="nº de 'pessoas' sintéticas (padrão 3)")
    ap.add_argument("--reps", type=int, default=2, help="repetições por pessoa/sinal (padrão 2)")
    ap.add_argument("--frames", type=int, default=30, help="frames por clipe (padrão 30)")
    ap.add_argument("--limpar", action="store_true", help="apaga clipes sintéticos (pessoaS*) antes")
    args = ap.parse_args()

    cfg = load_config()
    lm_dir = cfg.path("landmarks")
    lm_dir.mkdir(parents=True, exist_ok=True)

    if args.limpar:
        for f in lm_dir.glob("pessoaS*.npy"):
            f.unlink()
        print(f"[sintetico] removidos os clipes sintéticos anteriores em {lm_dir}")

    print("⚠️  GERANDO DADOS SINTÉTICOS — não são Libras real, só para testar o encanamento.")
    n = 0
    for idx_sinal, sinal in enumerate(cfg.vocabulario):
        for pessoa in range(args.pessoas):
            for rep in range(1, args.reps + 1):
                semente = idx_sinal * 1000 + pessoa * 10 + rep
                seq = clipe_sintetico(idx_sinal, semente, args.frames, cfg.num_pontos)
                nome = f"pessoaS{pessoa:02d}_sinal-{sinal}_rep{rep:02d}.npy"
                np.save(lm_dir / nome, seq)
                n += 1
    print(f"[sintetico] {n} clipes escritos em {lm_dir}")
    print("   Suba a API e confira: curl http://127.0.0.1:8000/health")
    print("   Para dados reais: apague pessoaS*.npy e rode ingest.py + extract.py.")


if __name__ == "__main__":
    main()

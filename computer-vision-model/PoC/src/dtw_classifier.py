"""§5.3 — Baseline DTW (vizinho mais próximo, 1-NN).

Classifica um clipe novo pela MENOR distância DTW até todos os clipes de
referência disponíveis, atribuindo a classe (sinal) do vizinho mais próximo.
Cada frame é achatado num vetor 1D (49×3 -> 147) e a sequência de vetores é
comparada com fastdtw usando distância euclidiana.

A qualidade dos landmarks (extract.py, normalização) importa muito mais que a
sofisticação do algoritmo — por isso usamos uma lib pronta em vez de implementar
DTW do zero.

Este módulo também expõe utilitários (parse do nome, carga do dataset) usados por
evaluate.py.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np

try:
    from fastdtw import fastdtw
    from scipy.spatial.distance import euclidean
except ImportError as e:  # pragma: no cover
    raise SystemExit("fastdtw/scipy não instalados — rode: pip install -r requirements.txt") from e

BASE = Path(__file__).resolve().parent.parent
LM_DIR = BASE / "data" / "landmarks"

# pessoa03_sinal-ajuda_rep02  ->  pessoa=03, sinal=ajuda, rep=02
_NAME_RE = re.compile(r"pessoa(?P<pessoa>[^_]+)_sinal-(?P<sinal>.+)_rep(?P<rep>\d+)$")


@dataclass
class Clip:
    pessoa: str
    sinal: str
    rep: str
    seq: np.ndarray  # (num_frames, 147) já achatado

    @property
    def nome(self) -> str:
        return f"pessoa{self.pessoa}_sinal-{self.sinal}_rep{self.rep}"


def parse_nome(stem: str) -> tuple[str, str, str]:
    m = _NAME_RE.match(stem)
    if not m:
        raise ValueError(
            f"nome fora da convenção 'pessoaNN_sinal-XXX_repNN': {stem!r}")
    return m.group("pessoa"), m.group("sinal"), m.group("rep")


def carregar_dataset(lm_dir: Path = LM_DIR) -> list[Clip]:
    """Carrega todos os .npy de landmarks, achatando cada frame para 1D."""
    clips: list[Clip] = []
    for f in sorted(lm_dir.glob("*.npy")):
        pessoa, sinal, rep = parse_nome(f.stem)
        arr = np.load(f)  # (num_frames, 49, 3)
        seq = arr.reshape(arr.shape[0], -1)  # (num_frames, 147)
        clips.append(Clip(pessoa, sinal, rep, seq.astype(np.float64)))
    return clips


def dtw_dist(a: np.ndarray, b: np.ndarray) -> float:
    d, _ = fastdtw(a, b, dist=euclidean)
    return float(d)


class DTWClassifier:
    """1-NN usando DTW como métrica de distância."""

    def __init__(self, referencia: list[Clip]):
        if not referencia:
            raise ValueError("conjunto de referência vazio")
        self.referencia = referencia

    def prever(self, seq: np.ndarray) -> tuple[str, float]:
        """Retorna (sinal previsto, distância do vizinho mais próximo)."""
        melhor_sinal, melhor_d = None, float("inf")
        for ref in self.referencia:
            d = dtw_dist(seq, ref.seq)
            if d < melhor_d:
                melhor_d, melhor_sinal = d, ref.sinal
        return melhor_sinal, melhor_d


def main() -> None:
    """Sanidade rápida: carrega o dataset e imprime um resumo."""
    clips = carregar_dataset()
    if not clips:
        print(f"[dtw] nenhum landmark em {LM_DIR} — rode extract.py primeiro.")
        return
    pessoas = sorted({c.pessoa for c in clips})
    sinais = sorted({c.sinal for c in clips})
    print(f"[dtw] {len(clips)} clipes | {len(pessoas)} pessoas | {len(sinais)} sinais")
    print(f"[dtw] pessoas: {pessoas}")
    print(f"[dtw] sinais:  {sinais}")
    print("[dtw] use evaluate.py para o protocolo leave-one-signer-out completo.")


if __name__ == "__main__":
    main()

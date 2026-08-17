"""Montagem do dataset de treino a partir dos landmarks extraídos.

Converte as sequências .npy (data/landmarks) em X, y prontos para o Keras.
- Fase A: reduz cada sequência à MÉDIA dos landmarks -> vetor fixo (ex.: 63). (§1.3)
- Fase B: mantém a sequência temporal completa (padding/janela). (§1.3)
"""
from __future__ import annotations

from pathlib import Path

import numpy as np


def load_sequences(landmarks_dir: Path, vocabulario: list[str]) -> tuple[list[np.ndarray], list[int]]:
    """Carrega os .npy e devolve (sequências, rótulos-índice segundo o vocabulário)."""
    raise NotImplementedError("Ler .npy de landmarks_dir e mapear cada um para o índice da classe.")


def build_phase_a(sequences: list[np.ndarray], labels: list[int]) -> tuple[np.ndarray, np.ndarray]:
    """Fase A: X = média temporal de cada sequência (n_amostras, feature_size)."""
    raise NotImplementedError("np.mean(seq, axis=0) por sequência; empilhar em X; y = labels.")


def build_phase_b(sequences: list[np.ndarray], labels: list[int], max_len: int) -> tuple[np.ndarray, np.ndarray]:
    """Fase B: X = sequências com padding/truncamento para max_len frames."""
    raise NotImplementedError("Padronizar comprimento temporal (pad/trunc) e empilhar.")

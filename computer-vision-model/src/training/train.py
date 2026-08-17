"""Treino do classificador de sinais (Fase A ou B, conforme config).

Orquestra: dataset -> modelo -> fit -> salvar .keras em models/.
"""
from __future__ import annotations

from pathlib import Path

import tensorflow as tf

from ..config import Config


def train(cfg: Config) -> Path:
    """Treina segundo cfg.fase e devolve o caminho do modelo Keras salvo.

    Passos (a implementar):
      1. carregar sequências (src.data.dataset.load_sequences)
      2. montar X, y (build_phase_a ou build_phase_b)
      3. construir modelo (src.models.shallow ou temporal)
      4. model.fit(...) com cfg.treino
      5. model.save(models/<nome>.keras)
    """
    raise NotImplementedError("Implementar orquestração do treino usando os módulos de data e models.")

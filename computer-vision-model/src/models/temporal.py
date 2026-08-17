"""Fase B — modelo temporal para a sequência completa de landmarks (guia §1.3).

Opções: 1D-CNN ou recorrente pequeno (GRU/LSTM). Considerar transfer learning
a partir de um backbone pré-treinado em outra língua de sinais (ex.: SAM-SLR),
substituindo apenas a cabeça de classificação (§1.3).
"""
from __future__ import annotations

import tensorflow as tf


def build_temporal_model(max_len: int, feature_size: int, num_classes: int) -> tf.keras.Model:
    """Entrada (max_len, feature_size) -> saída softmax(num_classes).

    TODO: definir arquitetura (Conv1D/GRU) e opção de carregar pesos pré-treinados.
    """
    raise NotImplementedError("Definir backbone temporal (Fase B) + cabeça de classificação.")

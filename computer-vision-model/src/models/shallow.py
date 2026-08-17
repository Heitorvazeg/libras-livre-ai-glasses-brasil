"""Fase A — classificador raso sobre o vetor médio de landmarks (guia §1.3).

Entrada: vetor fixo (feature_size, ex.: 63). Saída: softmax sobre num_classes.
"""
from __future__ import annotations

import tensorflow as tf


def build_shallow_model(feature_size: int, num_classes: int) -> tf.keras.Model:
    """Dense(32, relu) -> Dense(num_classes, softmax). Ver §1.3."""
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(feature_size,)),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dense(num_classes, activation="softmax"),
        ]
    )
    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model

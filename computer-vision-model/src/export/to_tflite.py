"""Export para TFLite e quantização (guia §1.4).

Saída: models/sinal_classifier.tflite, que é copiado para o app Android em
../mobile-app-companion/app/src/main/assets/.
"""
from __future__ import annotations

from pathlib import Path
from typing import Callable, Iterable

import tensorflow as tf


def export_tflite(keras_model_path: Path, out_path: Path) -> Path:
    """Converte um modelo Keras salvo para .tflite sem quantização. Ver §1.4."""
    model = tf.keras.models.load_model(keras_model_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(tflite_model)
    return out_path


def export_tflite_quantized(
    keras_model_path: Path,
    out_path: Path,
    representative_dataset_gen: Callable[[], Iterable],
) -> Path:
    """Export com quantização INT8 (a partir da Fase B, guia §1.4).

    `representative_dataset_gen` deve iterar amostras representativas do dataset.
    """
    raise NotImplementedError(
        "Configurar converter.optimizations, representative_dataset e supported_ops INT8 (§1.4)."
    )

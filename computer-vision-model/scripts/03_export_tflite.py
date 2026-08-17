"""Etapa 3 — exporta o modelo treinado para .tflite.

Uso: python scripts/03_export_tflite.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src.config import load_config  # noqa: E402
from src.export.to_tflite import export_tflite  # noqa: E402


def main() -> None:
    cfg = load_config()
    # TODO: selecionar o .keras mais recente em models/ (ou parametrizar).
    keras_model_path = cfg.path("models") / "sinal_classifier.keras"
    out_path = cfg.path("tflite_out")
    result = export_tflite(keras_model_path, out_path)
    print(f"TFLite gerado em: {result}")


if __name__ == "__main__":
    main()

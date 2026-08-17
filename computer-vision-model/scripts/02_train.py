"""Etapa 2 — treina o classificador a partir dos landmarks.

Uso: python scripts/02_train.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src.config import load_config  # noqa: E402
from src.training.train import train  # noqa: E402


def main() -> None:
    cfg = load_config()
    model_path = train(cfg)
    print(f"Modelo salvo em: {model_path}")


if __name__ == "__main__":
    main()

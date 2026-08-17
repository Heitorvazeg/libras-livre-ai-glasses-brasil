"""Etapa 1 — extrai landmarks dos vídeos em data/raw para data/landmarks.

Uso: python scripts/01_extract_landmarks.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src.config import load_config  # noqa: E402
from src.data.landmark_extraction import extract_dir  # noqa: E402


def main() -> None:
    cfg = load_config()
    extract_dir(
        raw_dir=cfg.path("raw_videos"),
        out_dir=cfg.path("landmarks"),
        max_num_hands=cfg.landmarks["max_num_hands"],
    )


if __name__ == "__main__":
    main()

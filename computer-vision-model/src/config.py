"""Carrega o config.yaml num objeto simples de acesso.

Mantém vocabulário, caminhos e hiperparâmetros num só lugar para que scripts e
módulos não repitam constantes.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG_PATH = PROJECT_ROOT / "config.yaml"


@dataclass
class Config:
    """Espelho tipado do config.yaml. Campos crus ficam em `raw`."""

    vocabulario: list[str]
    paths: dict[str, str]
    landmarks: dict[str, Any]
    treino: dict[str, Any]
    export: dict[str, Any]
    fase: str
    raw: dict[str, Any] = field(default_factory=dict)

    @property
    def num_classes(self) -> int:
        return len(self.vocabulario)

    @property
    def feature_size(self) -> int:
        """Tamanho do vetor de entrada da Fase A: pontos x dims x mãos (ex.: 63)."""
        lm = self.landmarks
        return lm["num_pontos_por_mao"] * lm["dims"] * lm["max_num_hands"]

    def path(self, key: str) -> Path:
        return PROJECT_ROOT / self.paths[key]


def load_config(path: str | Path = DEFAULT_CONFIG_PATH) -> Config:
    """Lê o YAML e devolve um Config. TODO: validar campos obrigatórios."""
    with open(path, "r", encoding="utf-8") as fh:
        raw = yaml.safe_load(fh)
    return Config(
        vocabulario=raw["vocabulario"],
        paths=raw["paths"],
        landmarks=raw["landmarks"],
        treino=raw["treino"],
        export=raw["export"],
        fase=raw.get("fase", "A"),
        raw=raw,
    )

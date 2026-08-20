"""Carrega o config.yaml da PoC num objeto simples de acesso.

Vocabulário, metas de coleta, caminhos e parâmetros do pipeline ficam num só
lugar (config.yaml) para que record/extract/dtw/evaluate não repitam constantes
— em especial os índices de pose e a referência de normalização, que precisam
ser idênticos entre a extração e qualquer análise posterior.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

POC_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG_PATH = POC_ROOT / "config.yaml"


@dataclass
class Config:
    """Espelho tipado do config.yaml. Campos crus ficam em `raw`."""

    vocabulario: list[str]
    participantes_esperados: int
    repeticoes_por_sinal: int
    paths: dict[str, str]
    pose_indices: dict[str, int]
    normalizacao: dict[str, Any]
    holistic: dict[str, Any]
    gravacao: dict[str, Any]
    dtw: dict[str, Any]
    avaliacao: dict[str, Any]
    raw: dict[str, Any] = field(default_factory=dict)

    @property
    def pose_subset(self) -> list[int]:
        """Índices de pose na ordem em que entram no vetor do frame."""
        return list(self.pose_indices.values())

    @property
    def num_pontos(self) -> int:
        """Pontos por frame: subconjunto de pose + 21 de cada mão."""
        return len(self.pose_indices) + 2 * 21

    @property
    def dims(self) -> int:
        """3 (x, y, z) ou 2 (x, y), conforme normalizacao.usar_z."""
        return 3 if self.normalizacao.get("usar_z", True) else 2

    def path(self, key: str) -> Path:
        return POC_ROOT / self.paths[key]


def load_config(path: str | Path = DEFAULT_CONFIG_PATH) -> Config:
    """Lê o YAML, valida o mínimo que quebraria o pipeline e devolve um Config."""
    with open(path, "r", encoding="utf-8") as fh:
        raw = yaml.safe_load(fh)

    faltando = [k for k in ("vocabulario", "paths", "pose_indices", "normalizacao")
                if k not in raw]
    if faltando:
        raise ValueError(f"config.yaml sem as chaves obrigatórias: {faltando}")

    vocab = raw["vocabulario"]
    if not vocab:
        raise ValueError("config.yaml: 'vocabulario' vazio — defina os sinais da PoC (§3).")

    norm = raw["normalizacao"]
    pose_idx = raw["pose_indices"]
    for chave in ("ref_a", "ref_b"):
        if norm[chave] not in pose_idx.values():
            raise ValueError(
                f"config.yaml: normalizacao.{chave}={norm[chave]} não está em pose_indices — "
                "a referência de normalização precisa ser um dos pontos extraídos.")

    return Config(
        vocabulario=vocab,
        participantes_esperados=raw.get("participantes_esperados", 5),
        repeticoes_por_sinal=raw.get("repeticoes_por_sinal", 5),
        paths=raw["paths"],
        pose_indices=pose_idx,
        normalizacao=norm,
        holistic=raw.get("holistic", {}),
        gravacao=raw.get("gravacao", {}),
        dtw=raw.get("dtw", {}),
        avaliacao=raw.get("avaliacao", {}),
        raw=raw,
    )

"""§5.3 — Baseline DTW (vizinho mais próximo, 1-NN).

Classifica um clipe novo pela MENOR distância DTW até todos os clipes de
referência disponíveis, atribuindo a classe (sinal) do vizinho mais próximo.
Cada frame é achatado num vetor 1D (49×3 -> 147) e a sequência de vetores é
comparada com uma biblioteca pronta de DTW — a qualidade dos landmarks
(extract.py, normalização) importa muito mais que a sofisticação do algoritmo.

Backends (§5.3 do plano cita as duas libs):
  - dtaidistance (padrão): DTW multivariado com implementação em C, single ou
    multi-thread. É o que torna a avaliação viável — a matriz de distâncias de
    ~360 clipes tem ~65 mil pares.
  - fastdtw: fallback puro-Python, correto porém ordens de grandeza mais lento;
    usado só se o backend C não estiver disponível.
Os dois definem a distância de forma diferente (dtaidistance soma quadrados e
tira a raiz no fim; fastdtw soma distâncias euclidianas passo a passo), então os
valores absolutos não são comparáveis entre backends — por isso o relatório
registra qual foi usado.

Este módulo também expõe utilitários (parse do nome, carga do dataset, matriz de
distâncias) usados por evaluate.py.
"""
from __future__ import annotations

import hashlib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from config import Config, load_config

# pessoa03_sinal-ajuda_rep02  ->  pessoa=03, sinal=ajuda, rep=02
_NAME_RE = re.compile(r"pessoa(?P<pessoa>[^_]+)_sinal-(?P<sinal>.+)_rep(?P<rep>\d+)$")

_DTAI_ERRO: str | None = None
try:
    from dtaidistance import dtw_ndim
    _TEM_DTAI = True
except ImportError as e:  # pragma: no cover - depende do ambiente
    _TEM_DTAI, _DTAI_ERRO = False, str(e)

try:
    from fastdtw import fastdtw
    from scipy.spatial.distance import euclidean
    _TEM_FASTDTW = True
except ImportError:  # pragma: no cover - depende do ambiente
    _TEM_FASTDTW = False


@dataclass
class Clip:
    pessoa: str
    sinal: str
    rep: str
    seq: np.ndarray  # (num_frames, num_pontos*dims), float64 contíguo

    @property
    def nome(self) -> str:
        return f"pessoa{self.pessoa}_sinal-{self.sinal}_rep{self.rep}"


@dataclass
class Vizinho:
    """Resultado de uma classificação 1-NN."""

    sinal: str
    distancia: float
    clipe: str


def parse_nome(stem: str) -> tuple[str, str, str]:
    m = _NAME_RE.match(stem)
    if not m:
        raise ValueError(f"nome fora da convenção 'pessoaNN_sinal-XXX_repNN': {stem!r}")
    return m.group("pessoa"), m.group("sinal"), m.group("rep")


def carregar_dataset(lm_dir: Path | None = None, cfg: Config | None = None) -> list[Clip]:
    """Carrega todos os .npy de landmarks, achatando cada frame para 1D."""
    cfg = cfg or load_config()
    lm_dir = lm_dir or cfg.path("landmarks")
    clips: list[Clip] = []
    for f in sorted(lm_dir.glob("*.npy")):
        pessoa, sinal, rep = parse_nome(f.stem)
        arr = np.load(f)  # (num_frames, num_pontos, dims)
        if arr.ndim != 3:
            raise ValueError(f"{f.name}: esperava (frames, pontos, dims), veio {arr.shape}")
        seq = arr.reshape(arr.shape[0], -1)
        # dtaidistance exige double contíguo; fastdtw também trabalha em float.
        clips.append(Clip(pessoa, sinal, rep, np.ascontiguousarray(seq, dtype=np.double)))
    return clips


def escolher_backend(cfg: Config) -> str:
    """Resolve dtw.backend='auto' para o melhor backend disponível."""
    pedido = (cfg.dtw.get("backend") or "auto").lower()
    if pedido == "dtaidistance" and not _TEM_DTAI:
        raise SystemExit(f"backend dtaidistance indisponível ({_DTAI_ERRO}) — "
                         "instale-o ou use dtw.backend: fastdtw no config.yaml")
    if pedido == "fastdtw" and not _TEM_FASTDTW:
        raise SystemExit("backend fastdtw indisponível — pip install -r requirements.txt")
    if pedido != "auto":
        return pedido
    if _TEM_DTAI:
        return "dtaidistance"
    if _TEM_FASTDTW:
        print("[dtw] ⚠ dtaidistance indisponível; usando fastdtw (bem mais lento).")
        return "fastdtw"
    raise SystemExit("nenhum backend de DTW instalado — pip install -r requirements.txt")


def _fator_comprimento(a: np.ndarray, b: np.ndarray, cfg: Config) -> float:
    """Divisor opcional que compensa sequências de durações diferentes.

    O caminho de alinhamento tem entre max(T1,T2) e T1+T2 passos; usamos a média
    dos comprimentos como aproximação barata (obter o caminho exato custaria
    outra passada do DTW).
    """
    if not cfg.dtw.get("normalizar_por_comprimento", False):
        return 1.0
    return (len(a) + len(b)) / 2.0


def dtw_dist(a: np.ndarray, b: np.ndarray, cfg: Config, backend: str | None = None,
             max_dist: float | None = None) -> float:
    """Distância DTW entre duas sequências (frames × features).

    `max_dist` é um corte de poda: se a distância já passou desse valor, o
    backend pode abortar e devolver inf. Como só interessa o vizinho MAIS
    próximo, podar com a melhor distância encontrada até agora não muda o
    resultado do 1-NN.
    """
    backend = backend or escolher_backend(cfg)
    janela = cfg.dtw.get("janela")
    if backend == "dtaidistance":
        d = dtw_ndim.distance_fast(a, b, window=janela, max_dist=max_dist, use_pruning=True)
    else:
        d, _ = fastdtw(a, b, radius=janela if janela else 1, dist=euclidean)
    return float(d) / _fator_comprimento(a, b, cfg)


def matriz_distancias(clips: list[Clip], cfg: Config, verboso: bool = True) -> np.ndarray:
    """Matriz simétrica N×N com a distância DTW entre todos os pares de clipes.

    Calcular tudo de uma vez é o que viabiliza o leave-one-signer-out: cada
    rodada vira consulta a uma tabela, em vez de recalcular DTW por rodada.
    """
    backend = escolher_backend(cfg)
    n = len(clips)
    series = [c.seq for c in clips]

    if backend == "dtaidistance":
        if verboso:
            paralelo = bool(cfg.dtw.get("paralelo", True))
            print(f"[dtw] backend=dtaidistance paralelo={paralelo} "
                  f"pares={n * (n - 1) // 2}")
        m = dtw_ndim.distance_matrix_fast(series, window=cfg.dtw.get("janela"),
                                          parallel=bool(cfg.dtw.get("paralelo", True)))
        m = np.asarray(m, dtype=np.float64)
    else:
        if verboso:
            print(f"[dtw] backend=fastdtw (lento) pares={n * (n - 1) // 2}")
        m = np.full((n, n), np.inf)
        for i in range(n):
            for j in range(i + 1, n):
                m[i, j] = dtw_dist(series[i], series[j], cfg, backend=backend)
            if verboso and (i + 1) % 10 == 0:
                print(f"[dtw]   {i + 1}/{n} linhas", flush=True)

    # dtaidistance devolve só o triângulo superior (inf no resto): espelha.
    m = np.where(np.isfinite(m), m, np.transpose(m))
    if cfg.dtw.get("normalizar_por_comprimento", False) and backend == "dtaidistance":
        comprimentos = np.array([len(s) for s in series], dtype=np.float64)
        m = m / ((comprimentos[:, None] + comprimentos[None, :]) / 2.0)
    np.fill_diagonal(m, 0.0)
    return m


def assinatura_dataset(clips: list[Clip], cfg: Config) -> str:
    """Hash do dataset + parâmetros de DTW, para invalidar cache de forma segura."""
    h = hashlib.sha1()
    for c in clips:
        h.update(f"{c.nome}:{c.seq.shape}".encode())
        h.update(np.ascontiguousarray(c.seq[:: max(1, len(c.seq) // 8)]).tobytes())
    h.update(repr(sorted(cfg.dtw.items())).encode())
    h.update(escolher_backend(cfg).encode())
    return h.hexdigest()[:16]


class DTWClassifier:
    """1-NN usando DTW como métrica de distância (classifica um clipe novo)."""

    def __init__(self, referencia: list[Clip], cfg: Config | None = None):
        if not referencia:
            raise ValueError("conjunto de referência vazio")
        self.referencia = referencia
        self.cfg = cfg or load_config()
        self.backend = escolher_backend(self.cfg)

    def prever(self, seq: np.ndarray) -> Vizinho:
        """Sinal do clipe de referência mais próximo, com a distância e o nome dele."""
        seq = np.ascontiguousarray(seq, dtype=np.double)
        melhor = Vizinho(sinal=self.referencia[0].sinal, distancia=float("inf"), clipe="")
        for ref in self.referencia:
            corte = melhor.distancia if np.isfinite(melhor.distancia) else None
            d = dtw_dist(seq, ref.seq, self.cfg, backend=self.backend, max_dist=corte)
            if d < melhor.distancia:
                melhor = Vizinho(sinal=ref.sinal, distancia=d, clipe=ref.nome)
        return melhor


def main() -> None:
    """Sanidade rápida: carrega o dataset e imprime um resumo."""
    cfg = load_config()
    clips = carregar_dataset(cfg=cfg)
    if not clips:
        print(f"[dtw] nenhum landmark em {cfg.path('landmarks')} — rode extract.py primeiro.")
        return
    pessoas = sorted({c.pessoa for c in clips})
    sinais = sorted({c.sinal for c in clips})
    frames = [len(c.seq) for c in clips]
    print(f"[dtw] {len(clips)} clipes | {len(pessoas)} pessoas | {len(sinais)} sinais")
    print(f"[dtw] pessoas: {pessoas}")
    print(f"[dtw] sinais:  {sinais}")
    print(f"[dtw] frames por clipe: min={min(frames)} mediana={int(np.median(frames))} max={max(frames)}")
    print(f"[dtw] backend: {escolher_backend(cfg)}")
    print("[dtw] use evaluate.py para o protocolo leave-one-signer-out completo.")


if __name__ == "__main__":
    sys.exit(main())

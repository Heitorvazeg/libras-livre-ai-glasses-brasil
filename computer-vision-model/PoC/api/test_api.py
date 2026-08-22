"""Testes da API de validação — foco no invariante que mais importa: PARIDADE.

O risco número um do caminho por API é a normalização no servidor divergir da que
gerou os .npy de referência. Estes testes fixam que o parsing do payload cru
(list -> objeto que imita o MediaPipe -> frame_normalizado) produz EXATAMENTE o
mesmo vetor que chamar extract.frame_normalizado direto — mais o comportamento de
descarte de frame sem pose confiável, e as respostas de erro do endpoint.

Não dependem de clipes de referência (data/landmarks pode estar vazio): validam o
encanamento, não a acurácia (essa é a coleta real, §9 do plano).

Rode a partir de PoC/:
    python -m pytest api/test_api.py -v
    # ou, sem pytest instalado:
    python api/test_api.py
"""
from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np

_SRC = Path(__file__).resolve().parent.parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from config import load_config          # noqa: E402
from extract import frame_normalizado   # noqa: E402

from api.server import (                 # noqa: E402
    ClipPayload, Frame, sequencia_do_payload, _duck_pose, _duck_hand,
)

CFG = load_config()


def _pose_sintetica(vis_ombros: float = 0.9) -> list[list[float]]:
    """33 pontos [x, y, z, vis]; ombros (11,12) separados e visíveis o bastante."""
    rng = np.random.default_rng(42)
    pontos = [[float(x), float(y), float(z), 0.9]
              for x, y, z in rng.uniform(0.2, 0.8, size=(33, 3))]
    pontos[11] = [0.40, 0.30, 0.0, vis_ombros]  # ombro esquerdo
    pontos[12] = [0.60, 0.30, 0.0, vis_ombros]  # ombro direito
    return pontos


def _mao_sintetica(seed: int) -> list[list[float]]:
    rng = np.random.default_rng(seed)
    return [[float(x), float(y), float(z)] for x, y, z in rng.uniform(0.2, 0.8, size=(21, 3))]


def _results_duck(pose, left, right):
    return SimpleNamespace(
        pose_landmarks=_duck_pose(pose),
        left_hand_landmarks=_duck_hand(left),
        right_hand_landmarks=_duck_hand(right),
    )


def test_paridade_com_extract():
    """O vetor do servidor == o de extract.frame_normalizado, ponto a ponto."""
    W, H = 1280, 720
    pose, left, right = _pose_sintetica(), _mao_sintetica(1), _mao_sintetica(2)

    esperado = frame_normalizado(_results_duck(pose, left, right), W, H, CFG)
    assert esperado is not None

    clip = ClipPayload(width=W, height=H,
                       frames=[Frame(pose=pose, left_hand=left, right_hand=right)])
    seq, descartados = sequencia_do_payload(clip, CFG)

    assert descartados == 0
    assert seq.shape == (1, CFG.num_pontos, CFG.dims)
    np.testing.assert_allclose(seq[0], esperado, rtol=0, atol=0)
    print("[ok] paridade servidor <-> extract.frame_normalizado")


def test_mao_ausente_vira_zeros():
    """Mão não detectada (null) vira zeros após a normalização, como na PoC."""
    W, H = 1280, 720
    clip = ClipPayload(width=W, height=H,
                       frames=[Frame(pose=_pose_sintetica(), left_hand=None,
                                     right_hand=_mao_sintetica(3))])
    seq, _ = sequencia_do_payload(clip, CFG)
    n_pose = len(CFG.pose_indices)
    mao_esq = seq[0, n_pose:n_pose + 21]      # bloco da mão esquerda
    assert np.allclose(mao_esq, 0.0)
    print("[ok] mão ausente -> zeros")


def test_frame_sem_pose_confiavel_e_descartado():
    """Ombro abaixo de min_visibilidade -> frame descartado (não normalizável)."""
    W, H = 1280, 720
    baixa = CFG.normalizacao["min_visibilidade"] - 0.1
    clip = ClipPayload(width=W, height=H,
                       frames=[Frame(pose=_pose_sintetica(vis_ombros=baixa),
                                     left_hand=_mao_sintetica(1), right_hand=_mao_sintetica(2))])
    seq, descartados = sequencia_do_payload(clip, CFG)
    assert seq.shape[0] == 0 and descartados == 1
    print("[ok] frame sem pose confiável é descartado")


def test_endpoint_health_e_erros():
    """Smoke via TestClient: /health responde, /classify valida payload e ausência de refs."""
    try:
        from fastapi.testclient import TestClient
    except ImportError:
        print("[skip] fastapi não instalado — pulei o teste de endpoint")
        return
    from api.server import app

    with TestClient(app) as client:
        h = client.get("/health")
        assert h.status_code == 200 and "status" in h.json()

        # pose com nº de pontos errado -> 422 (contrato)
        ruim = {"width": 1280, "height": 720,
                "frames": [{"pose": [[0.5, 0.5, 0.0, 0.9]] * 10}]}
        assert client.post("/classify", json=ruim).status_code == 422

        # payload válido: 200 se há referências, 503 se data/landmarks está vazio
        ok = {"width": 1280, "height": 720,
              "frames": [{"pose": _pose_sintetica(),
                          "left_hand": _mao_sintetica(1), "right_hand": _mao_sintetica(2)}]}
        r = client.post("/classify", json=ok)
        assert r.status_code in (200, 503)
        if r.status_code == 200:
            assert r.json()["sinal"] in CFG.vocabulario
    print("[ok] endpoint /health e validações de /classify")


if __name__ == "__main__":
    test_paridade_com_extract()
    test_mao_ausente_vira_zeros()
    test_frame_sem_pose_confiavel_e_descartado()
    test_endpoint_health_e_erros()
    print("\ntodos os testes da API passaram.")

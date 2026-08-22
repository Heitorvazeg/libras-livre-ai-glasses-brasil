"""API de validação — classifica um sinal isolado a partir de landmarks crus.

Este servidor é um ANDAIME DE VALIDAÇÃO (não é arquitetura de produção). Ele
existe para fechar o loop `app → landmarks → sinal → voz` reusando, sem
reimplementar nada, o classificador DTW 1-NN já medido pela PoC. O objetivo do
projeto é on-device/offline (ver README da raiz); esta API é o caminho mais
curto para validar a integração com o app antes desse investimento.

DECISÃO CENTRAL DE DESENHO — o app manda landmarks CRUS, o servidor normaliza:
  O passo que mais afeta a acurácia é a normalização (extract.py §5.2). Se o app
  normalizasse por conta própria, qualquer divergência de 1mm em relação ao que a
  PoC mediu derrubaria a acurácia sem aviso. Então o app só roda o MediaPipe e
  encaminha os pontos como saem do detector; TODA a normalização acontece aqui,
  chamando o MESMO `extract.frame_normalizado` usado para gerar os .npy de
  referência. Um único lugar com a lógica sensível.

Fluxo de uma requisição:
  POST /classify {width, height, frames:[{pose, left_hand, right_hand}, ...]}
    → reconstrói, por frame, um objeto que imita a saída do MediaPipe
    → extract.frame_normalizado (normalização idêntica à da PoC)
    → empilha os frames válidos → achata cada frame → DTW 1-NN
    → {sinal, distancia, clipe_vizinho, topk, frames_usados, frames_descartados}

Como rodar:
    pip install -r requirements.txt            # deps da PoC (mediapipe etc.)
    pip install -r api/requirements.txt        # fastapi + uvicorn
    python -m uvicorn api.server:app --host 0.0.0.0 --port 8000
    # (rode a partir de PoC/, para os caminhos relativos baterem)

--host 0.0.0.0 deixa a API acessível na rede local:
  - emulador Android  → http://10.0.2.2:8000   (10.0.2.2 = a máquina host, de
    dentro do emulador; 'localhost' lá seria o próprio emulador)
  - celular físico na mesma Wi-Fi → http://<IP-LAN-da-máquina>:8000
Docs interativas (Swagger) em /docs. Contrato do payload: ver api/README.md.
"""
from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, field_validator

# O código da PoC vive em ../src e é importado por nome de módulo (config,
# extract, dtw_classifier) — o mesmo estilo que os scripts de src/ já usam entre si.
_SRC = Path(__file__).resolve().parent.parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from config import load_config                                    # noqa: E402
from extract import frame_normalizado, N_HAND                     # noqa: E402
from dtw_classifier import (                                      # noqa: E402
    DTWClassifier, carregar_dataset, dtw_dist,
)

N_POSE_MEDIAPIPE = 33  # MediaPipe Pose/Holistic sempre devolve 33 pontos de pose


# --------------------------------------------------------------------------- #
# Contrato do payload (o app envia landmarks CRUS, como saem do MediaPipe).
# --------------------------------------------------------------------------- #
class Frame(BaseModel):
    """Landmarks crus de um frame, nas coordenadas normalizadas do MediaPipe (0..1)."""

    pose: list[list[float]] = Field(
        ...,
        description="33 pontos de pose, cada um [x, y, z, visibility]. "
                    "A ordem/índices são os do MediaPipe Pose (ombros=11,12).",
    )
    left_hand: list[list[float]] | None = Field(
        None, description="21 pontos [x, y, z] da mão esquerda, ou null se não detectada.")
    right_hand: list[list[float]] | None = Field(
        None, description="21 pontos [x, y, z] da mão direita, ou null se não detectada.")

    # Validação estrutural no parsing (422), independente de haver referências
    # carregadas — um payload malformado tem que falhar igual com ou sem dataset.
    @field_validator("pose")
    @classmethod
    def _checar_pose(cls, v: list[list[float]]) -> list[list[float]]:
        if len(v) != N_POSE_MEDIAPIPE:
            raise ValueError(f"'pose' tem {len(v)} pontos, esperado {N_POSE_MEDIAPIPE} "
                             "(MediaPipe Pose/Holistic) — mande todos os 33, os índices importam.")
        return v

    @field_validator("left_hand", "right_hand")
    @classmethod
    def _checar_mao(cls, v: list[list[float]] | None) -> list[list[float]] | None:
        if v is not None and len(v) != N_HAND:
            raise ValueError(f"mão com {len(v)} pontos, esperado {N_HAND} ou null.")
        return v


class ClipPayload(BaseModel):
    """Um sinal isolado já segmentado pelo app: a sequência de frames do gesto."""

    width: int = Field(..., gt=0, description="Largura do frame em pixels.")
    height: int = Field(..., gt=0, description="Altura do frame em pixels.")
    frames: list[Frame] = Field(..., min_length=1)


class SinalScore(BaseModel):
    sinal: str
    distancia: float
    clipe: str


class Resposta(BaseModel):
    sinal: str
    distancia: float
    clipe_vizinho: str
    topk: list[SinalScore]
    frames_usados: int
    frames_descartados: int


# --------------------------------------------------------------------------- #
# Parsing: payload cru -> objeto que imita a saída do MediaPipe -> normalização.
# Isolado em função para ser testável (test_api.py confere paridade com extract).
# --------------------------------------------------------------------------- #
def _duck_pose(pontos: list[list[float]]):
    """Imita results.pose_landmarks: um objeto com .landmark[i].x/.y/.z/.visibility."""
    lms = [SimpleNamespace(x=p[0], y=p[1], z=p[2], visibility=(p[3] if len(p) > 3 else 1.0))
           for p in pontos]
    return SimpleNamespace(landmark=lms)


def _duck_hand(pontos: list[list[float]] | None):
    """Imita results.left/right_hand_landmarks (ou None se a mão não veio)."""
    if pontos is None:
        return None
    return SimpleNamespace(landmark=[SimpleNamespace(x=p[0], y=p[1], z=p[2]) for p in pontos])


def sequencia_do_payload(clip: ClipPayload, cfg) -> tuple[np.ndarray, int]:
    """Payload cru -> (sequência normalizada (T, num_pontos, dims), frames descartados).

    Reusa extract.frame_normalizado: a normalização é EXATAMENTE a da PoC.
    """
    validos: list[np.ndarray] = []
    descartados = 0
    for fr in clip.frames:  # tamanhos de pose/mãos já validados por Frame (422 no parsing)
        results = SimpleNamespace(
            pose_landmarks=_duck_pose(fr.pose),
            left_hand_landmarks=_duck_hand(fr.left_hand),
            right_hand_landmarks=_duck_hand(fr.right_hand),
        )
        vec = frame_normalizado(results, clip.width, clip.height, cfg)
        if vec is None:            # sem pose confiável: mesmo critério de descarte da PoC
            descartados += 1
        else:
            validos.append(vec)

    if not validos:
        return np.empty((0, cfg.num_pontos, cfg.dims), dtype=np.float32), descartados
    return np.stack(validos).astype(np.float32), descartados


# --------------------------------------------------------------------------- #
# App
# --------------------------------------------------------------------------- #
app = FastAPI(
    title="Libras Livre — API de validação (DTW 1-NN)",
    description="Andaime de validação: classifica um sinal isolado a partir de "
                "landmarks crus, reusando o classificador da PoC. Não é produção.",
    version="0.1.0",
)

_estado: dict = {"cfg": None, "classifier": None, "topk": 3}


@app.on_event("startup")
def _carregar() -> None:
    """Carrega config e referências uma única vez (as referências não mudam por request)."""
    cfg = load_config()
    clips = carregar_dataset(cfg=cfg)
    _estado["cfg"] = cfg
    _estado["topk"] = int(cfg.avaliacao.get("topk_api", 3))
    if clips:
        _estado["classifier"] = DTWClassifier(clips, cfg)
    else:
        _estado["classifier"] = None  # /classify responde 503 até haver referências
    print(f"[api] pronto — {len(clips)} clipes de referência | "
          f"{cfg.num_pontos} pontos × {cfg.dims} dims | vocab: {cfg.vocabulario}")


@app.get("/health")
def health() -> dict:
    """Sinaliza se o servidor subiu e quantas referências estão carregadas."""
    clf: DTWClassifier | None = _estado["classifier"]
    cfg = _estado["cfg"]
    n_ref = len(clf.referencia) if clf else 0
    return {
        "status": "ok" if clf else "sem_referencias",
        "clipes_referencia": n_ref,
        "vocabulario": cfg.vocabulario if cfg else [],
        "backend_dtw": clf.backend if clf else None,
        "dims": cfg.dims if cfg else None,
    }


@app.post("/classify", response_model=Resposta)
def classify(clip: ClipPayload) -> Resposta:
    """Classifica um sinal isolado (1-NN DTW) a partir dos landmarks crus do app."""
    clf: DTWClassifier | None = _estado["classifier"]
    cfg = _estado["cfg"]
    if clf is None:
        raise HTTPException(
            503, "sem clipes de referência em data/landmarks — rode ../datasets/ingest.py "
                 "e src/extract.py, depois reinicie a API.")

    seq, descartados = sequencia_do_payload(clip, cfg)
    if seq.shape[0] == 0:
        raise HTTPException(
            422, f"nenhum frame com pose confiável ({descartados} descartados) — os ombros "
                 "precisam estar visíveis no quadro (min_visibilidade). Confira enquadramento.")

    # Achata cada frame (num_pontos × dims -> vetor 1D), como carregar_dataset faz.
    consulta = np.ascontiguousarray(seq.reshape(seq.shape[0], -1), dtype=np.double)

    # Distância a TODAS as referências: dá o 1-NN e o top-k de uma vez. Sem poda,
    # porque queremos a ordenação completa (uma requisição por vez, ~centenas de
    # pares — segundos com dtaidistance).
    scores = sorted(
        (SinalScore(sinal=ref.sinal, distancia=dtw_dist(consulta, ref.seq, cfg, backend=clf.backend),
                    clipe=ref.nome)
         for ref in clf.referencia),
        key=lambda s: s.distancia,
    )
    melhor = scores[0]
    return Resposta(
        sinal=melhor.sinal,
        distancia=melhor.distancia,
        clipe_vizinho=melhor.clipe,
        topk=scores[: _estado["topk"]],
        frames_usados=int(seq.shape[0]),
        frames_descartados=descartados,
    )

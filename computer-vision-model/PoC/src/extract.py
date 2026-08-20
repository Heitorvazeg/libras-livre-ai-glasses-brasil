"""§5.2 — Extração de landmarks com MediaPipe Holistic + normalização.

Para cada vídeo em data/raw/, roda o Holistic frame a frame e monta, por frame,
um vetor de pontos:

    [ pose_subset (7 pontos) | mão esquerda (21) | mão direita (21) ] × (x, y, z)
    = 49 pontos × 3 = 147 valores por frame

Pose_subset = nariz, ombros, cotovelos, pulsos (contexto de tronco sem inflar a
dimensionalidade). Mão ausente no frame -> zeros (marca ausência).

NORMALIZAÇÃO (o passo que mais afeta o resultado, §5.2):
  - referência = ponto médio entre os ombros (pose 11 e 12);
  - escala     = distância entre os ombros;
  - cada ponto do frame vira (ponto - referência) / escala.
Isso torna os landmarks invariantes à distância da pessoa até a câmera e à sua
posição no quadro. Sem ombros detectados, o frame é descartado (não dá para
normalizar de forma estável).

Saída: data/landmarks/<mesmo-nome-base>.npy, array float32 (num_frames, 49, 3).

Uso:
    python src/extract.py                 # processa tudo que falta
    python src/extract.py --overwrite     # reprocessa mesmo o que já existe
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

try:
    import mediapipe as mp
except ImportError as e:  # pragma: no cover
    raise SystemExit("mediapipe não instalado — rode: pip install -r requirements.txt") from e

BASE = Path(__file__).resolve().parent.parent
RAW_DIR = BASE / "data" / "raw"
LM_DIR = BASE / "data" / "landmarks"

# Subconjunto de pose (índices MediaPipe Pose). Mantém em sincronia com config.yaml.
POSE_SUBSET = [0, 11, 12, 13, 14, 15, 16]  # nariz, ombros, cotovelos, pulsos
SHOULDER_L, SHOULDER_R = 11, 12
N_HAND = 21
N_POINTS = len(POSE_SUBSET) + 2 * N_HAND  # 7 + 42 = 49


def _hand_array(hand_landmarks) -> np.ndarray:
    """21×3 dos pontos da mão, ou zeros se a mão não foi detectada."""
    if hand_landmarks is None:
        return np.zeros((N_HAND, 3), dtype=np.float32)
    return np.array([[lm.x, lm.y, lm.z] for lm in hand_landmarks.landmark],
                    dtype=np.float32)


def _frame_vector(results) -> np.ndarray | None:
    """Monta os 49×3 pontos do frame já normalizados, ou None se não dá para normalizar."""
    pose = results.pose_landmarks
    if pose is None:
        return None  # sem tronco não há referência estável de normalização

    lms = pose.landmark
    ref = np.array([(lms[SHOULDER_L].x + lms[SHOULDER_R].x) / 2.0,
                    (lms[SHOULDER_L].y + lms[SHOULDER_R].y) / 2.0,
                    (lms[SHOULDER_L].z + lms[SHOULDER_R].z) / 2.0], dtype=np.float32)
    escala = float(np.linalg.norm(
        np.array([lms[SHOULDER_L].x, lms[SHOULDER_L].y, lms[SHOULDER_L].z]) -
        np.array([lms[SHOULDER_R].x, lms[SHOULDER_R].y, lms[SHOULDER_R].z])))
    if escala < 1e-6:
        return None  # ombros colados/instáveis: descarta o frame

    pose_pts = np.array([[lms[i].x, lms[i].y, lms[i].z] for i in POSE_SUBSET],
                        dtype=np.float32)
    left = _hand_array(results.left_hand_landmarks)
    right = _hand_array(results.right_hand_landmarks)

    pontos = np.concatenate([pose_pts, left, right], axis=0)  # (49, 3)
    return (pontos - ref) / escala


def extrair_video(caminho: Path, holistic) -> np.ndarray:
    """Devolve a sequência normalizada (num_frames, 49, 3) de um vídeo."""
    cap = cv2.VideoCapture(str(caminho))
    frames: list[np.ndarray] = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        rgb.flags.writeable = False
        results = holistic.process(rgb)
        vec = _frame_vector(results)
        if vec is not None:
            frames.append(vec)
    cap.release()
    if not frames:
        return np.empty((0, N_POINTS, 3), dtype=np.float32)
    return np.stack(frames).astype(np.float32)


def main() -> None:
    ap = argparse.ArgumentParser(description="Extração Holistic + normalização (§5.2).")
    ap.add_argument("--overwrite", action="store_true", help="reprocessa .npy já existentes")
    args = ap.parse_args()

    LM_DIR.mkdir(parents=True, exist_ok=True)
    videos = sorted(RAW_DIR.glob("*.mp4"))
    if not videos:
        print(f"[extract] nenhum vídeo em {RAW_DIR} — grave clipes com record.py primeiro.")
        return

    mp_holistic = mp.solutions.holistic
    with mp_holistic.Holistic(model_complexity=1,
                              min_detection_confidence=0.5,
                              min_tracking_confidence=0.5) as holistic:
        for v in videos:
            destino = LM_DIR / (v.stem + ".npy")
            if destino.exists() and not args.overwrite:
                print(f"[extract] pulando {v.name} (já extraído)")
                continue
            seq = extrair_video(v, holistic)
            if seq.shape[0] == 0:
                print(f"[extract] ⚠ {v.name}: nenhum frame com pose detectada — verifique enquadramento")
                continue
            np.save(destino, seq)
            print(f"[extract] {v.name} -> {destino.name}  ({seq.shape[0]} frames)")


if __name__ == "__main__":
    main()

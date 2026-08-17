"""Extração de landmarks de mão com MediaPipe Hands (guia §1.2).

Cada vídeo em data/raw representa UM sinal isolado. A extração produz uma
sequência (frames x features) salva como .npy em data/landmarks, rotulada pelo
nome do sinal (subpasta ou prefixo do arquivo).
"""
from __future__ import annotations

from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np

mp_hands = mp.solutions.hands


def extract_landmarks(video_path: str | Path, max_num_hands: int = 1) -> np.ndarray:
    """Roda MediaPipe Hands em cada frame e devolve (num_frames, features).

    features = 21 pontos x (x, y, z) por mão. Frames sem mão detectada são
    ignorados. Ver guia §1.2.
    """
    hands = mp_hands.Hands(static_image_mode=False, max_num_hands=max_num_hands)
    cap = cv2.VideoCapture(str(video_path))
    sequence: list[list[float]] = []
    try:
        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break
            results = hands.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            if results.multi_hand_landmarks:
                frame_points: list[float] = []
                for hand in results.multi_hand_landmarks:
                    for lm in hand.landmark:
                        frame_points.extend([lm.x, lm.y, lm.z])
                sequence.append(frame_points)
    finally:
        cap.release()
        hands.close()
    return np.array(sequence, dtype=np.float32)


def extract_dir(raw_dir: Path, out_dir: Path, max_num_hands: int = 1) -> None:
    """Extrai todos os vídeos de raw_dir e salva .npy em out_dir.

    Convenção de rótulo: TODO definir (subpasta por sinal ou prefixo do nome).
    """
    raise NotImplementedError("Percorrer raw_dir, chamar extract_landmarks e salvar .npy rotulado.")

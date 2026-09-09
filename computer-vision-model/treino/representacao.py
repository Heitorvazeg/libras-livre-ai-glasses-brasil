"""Skeleton-DML — a sequência de landmarks virando imagem para uma CNN.

POR QUE ISSO E NÃO UMA REDE RECORRENTE (docs/investigacao-expansao-dataset.md,
Achado D): com ~1.000 clipes, treinar uma recorrente do zero disputa com uma CNN
que já vem pré-treinada em milhões de imagens. Os dois trabalhos que avaliam
Libras no MESMO protocolo que usamos (leave-one-signer-out sobre o MINDS-Libras)
chegam a 0,93-0,94 empilhando os landmarks como imagem e usando uma ResNet-18
ImageNet — contra 0,70 do nosso baseline DTW.

A REPRESENTAÇÃO. Um clipe é (T frames, P pontos, x/y). Skeleton-DML monta:

    x -> (P, T)  ->  (P, T/3, 3)      cada 3 frames consecutivos viram os 3 canais
    y -> (P, T)  ->  (P, T/3, 3)
    imagem = concat([x, y], eixo das colunas)  ->  (P, 2*(T/3), 3)

Altura = pontos, largura = tempo (metade x, metade y), canais = 3 frames. Depois
disso é uma imagem RGB comum: redimensiona para 224×224 e entra na CNN.

Repare no efeito colateral útil: sequências de durações diferentes (nossos clipes
vão de 70 a 232 frames) viram imagens de larguras diferentes, e o
redimensionamento resolve — sem padding, sem máscara, sem reamostragem explícita.

ESCALA. Os landmarks saem do extract.py em unidades de ombro (origem no meio dos
ombros, escala = distância entre eles). Medido sobre o dataset real, x e y ficam
em [-1,56; +1,84], então mapear [-2, +2] -> [0, 1] não corta nada e usa bem a
faixa dinâmica da imagem. Constante fixa de propósito: nada de estatística
estimada do treino, que teria de ser recarregada igual na inferência.

Referência: Alves et al. 2024 (arXiv 2404.19148) e dos Santos et al. 2025
(arXiv 2510.24887).
"""
from __future__ import annotations

import numpy as np

# Faixa (em unidades de ombro) mapeada para [0, 1]. Ver docstring.
LIMITE = 2.0
# Frames por canal — o "3" do RGB.
FRAMES_POR_CANAL = 3


def para_imagem(seq: np.ndarray, frames_por_canal: int = FRAMES_POR_CANAL,
                limite: float = LIMITE) -> np.ndarray:
    """(T, P, 2) de landmarks -> imagem (P, 2*(T//n), n) em [0, 1].

    Frames sobrando no fim (T não múltiplo de n) são descartados, como na
    implementação de referência: n-1 frames no máximo, irrelevante num clipe de
    ~140.
    """
    if seq.ndim != 3 or seq.shape[2] < 2:
        raise ValueError(f"esperava (T, P, >=2), veio {seq.shape}")
    n = frames_por_canal
    t_util = (seq.shape[0] // n) * n
    if t_util == 0:
        raise ValueError(f"clipe curto demais: {seq.shape[0]} frame(s), mínimo {n}")

    x = seq[:t_util, :, 0].T  # (P, t_util)
    y = seq[:t_util, :, 1].T
    p = x.shape[0]
    x = x.reshape(p, -1, n)
    y = y.reshape(p, -1, n)
    imagem = np.concatenate([x, y], axis=1)

    imagem = np.clip(imagem, -limite, limite)
    return ((imagem + limite) / (2 * limite)).astype(np.float32)


# ------------------------------------------------------------------ augmentação
# Aplicada sobre os LANDMARKS, antes de virar imagem: rotacionar o esqueleto é
# diferente (e mais fiel) do que rotacionar a imagem já montada, onde os eixos
# são "ponto" e "tempo", não espaço. Parâmetros vêm da literatura (Achado D).

ROTACAO_SIGMA = 12.0    # graus
ZOOM_SIGMA = 0.1
TRANSLACAO_SIGMA = 0.06  # em unidades de ombro
CHANCE_ESPELHO = 0.3


def _rotacionar(seq: np.ndarray, graus: float) -> np.ndarray:
    rad = np.radians(graus)
    c, s = np.cos(rad), np.sin(rad)
    rot = np.array([[c, -s], [s, c]], dtype=np.float32)
    fora = seq.copy()
    fora[:, :, :2] = seq[:, :, :2] @ rot.T
    return fora


def espelhar(seq: np.ndarray, permutacao: np.ndarray) -> np.ndarray:
    """Espelha horizontalmente: nega x E troca os pontos esquerda/direita.

    Só negar o x produziria um esqueleto impossível — a mão esquerda apareceria
    onde a direita deveria estar, mas ainda rotulada como esquerda. `permutacao`
    (de `permutacao_espelho`) faz a troca dos pares simétricos, o que torna a
    augmentação equivalente a filmar um sinalizante canhoto.
    """
    fora = seq[:, permutacao, :].copy()
    fora[:, :, 0] *= -1.0
    return fora


def aumentar(seq: np.ndarray, rng: np.random.Generator,
             permutacao: np.ndarray | None = None) -> np.ndarray:
    """Rotação + zoom + translação + espelhamento, com os sigmas da literatura."""
    fora = _rotacionar(seq, rng.normal(0, ROTACAO_SIGMA))
    fora = fora * (1.0 + rng.normal(0, ZOOM_SIGMA))
    fora[:, :, 0] += rng.normal(0, TRANSLACAO_SIGMA)
    fora[:, :, 1] += rng.normal(0, TRANSLACAO_SIGMA)
    if permutacao is not None and rng.random() < CHANCE_ESPELHO:
        fora = espelhar(fora, permutacao)
    return fora


def permutacao_espelho(nomes_pose: list[str], n_mao: int = 21) -> np.ndarray:
    """Índices que trocam esquerda<->direita no vetor de pontos do frame.

    O vetor é [pose (nomes_pose) | mão esquerda (21) | mão direita (21)]. Os
    pares de pose são descobertos pelo sufixo `_esq`/`_dir` do config, em vez de
    ficarem escritos à mão aqui — assim mudar `pose_indices` não deixa um mapa
    de espelhamento silenciosamente errado para trás.
    """
    idx = {nome: i for i, nome in enumerate(nomes_pose)}
    perm = list(range(len(nomes_pose)))
    for nome, i in idx.items():
        if nome.endswith("_esq"):
            par = idx.get(nome[: -len("_esq")] + "_dir")
            if par is None:
                raise ValueError(f"pose_indices tem {nome!r} sem o par _dir correspondente")
            perm[i], perm[par] = par, i

    base = len(nomes_pose)
    esquerda = list(range(base, base + n_mao))
    direita = list(range(base + n_mao, base + 2 * n_mao))
    return np.array(perm + direita + esquerda, dtype=np.intp)

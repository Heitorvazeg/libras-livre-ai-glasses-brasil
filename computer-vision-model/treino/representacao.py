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
# O z tem faixa MUITO maior que x,y e precisa do seu próprio limite. Medido em
# 150 clipes MINDS (valores não-nulos, em unidades de ombro):
#
#            mediana    p95    p99,9    máx
#   x,y         0,44   1,35     1,63   2,05   -> LIMITE 2,0 corta ~0,1%
#   z           0,74   2,15     4,89   5,47   -> LIMITE 2,0 cortaria >5%
#
# 5,0 mantém o z na mesma proporção de corte que x,y (~0,1%). Usar 2,0 para os
# dois saturaria a metade superior do z e mediria a saturação, não o z.
LIMITE_Z = 5.0
# Frames por canal — o "3" do RGB.
FRAMES_POR_CANAL = 3


def para_imagem(seq: np.ndarray, frames_por_canal: int = FRAMES_POR_CANAL,
                limite: float = LIMITE, limite_z: float = LIMITE_Z) -> np.ndarray:
    """(T, P, D) de landmarks -> imagem (P, D*(T//n), n) em [0, 1].

    Frames sobrando no fim (T não múltiplo de n) são descartados, como na
    implementação de referência: n-1 frames no máximo, irrelevante num clipe de
    ~140.

    D é 2 (x,y) por padrão e 3 com `--com-z`. Note onde o z entra: NÃO como um
    quarto canal da imagem — os 3 canais RGB já são 3 frames consecutivos, e
    mexer neles mudaria o significado da representação. O z vira mais um bloco de
    colunas, ao lado de x e y. A imagem fica 50% mais larga e o
    redimensionamento para 224x224 absorve a diferença, exatamente como já
    absorve clipes de durações diferentes.
    """
    if seq.ndim != 3 or seq.shape[2] < 2:
        raise ValueError(f"esperava (T, P, >=2), veio {seq.shape}")
    n = frames_por_canal
    t_util = (seq.shape[0] // n) * n
    if t_util == 0:
        raise ValueError(f"clipe curto demais: {seq.shape[0]} frame(s), mínimo {n}")

    p = seq.shape[1]
    blocos = []
    for d in range(seq.shape[2]):
        # Cada dimensão é escalada pelo SEU limite antes de virar coluna: x,y e z
        # têm faixas diferentes por quase 3x, e um limite único ou satura o z ou
        # desperdiça a faixa de x,y.
        lim = limite_z if d == 2 else limite
        bloco = np.clip(seq[:t_util, :, d].T.reshape(p, -1, n), -lim, lim)
        blocos.append((bloco + lim) / (2 * lim))
    return np.concatenate(blocos, axis=1).astype(np.float32)


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


CHANCE_AMPLITUDE = 0.5
FAIXA_AMPLITUDE = (0.60, 1.05)
CHANCE_REPOUSO = 0.5
# (pulso, cotovelo, bloco da mão) no vetor [pose 15 | mão esq 21 | mão dir 21].
_BRACOS = ((11, 9, (15, 36)), (12, 10, (36, 57)))


def frames_parados(seq: np.ndarray) -> tuple[int, int]:
    """Frames parados no início e no fim: pulsos abaixo de 20% da velocidade máxima."""
    v = np.maximum(np.linalg.norm(np.diff(seq[:, 11, :2], axis=0), axis=1),
                   np.linalg.norm(np.diff(seq[:, 12, :2], axis=0), axis=1))
    if len(v) >= 5:
        v = np.convolve(v, np.ones(3) / 3, mode="same")
    if len(v) == 0 or v.max() <= 0:
        return 0, 0
    ativos = np.flatnonzero(v > 0.2 * v.max())
    return int(ativos[0]), int(len(v) - 1 - ativos[-1])


def escalar_amplitude(seq: np.ndarray, k: float) -> np.ndarray:
    """Encolhe ou amplia a trajetória dos braços em torno do pulso médio do clipe.

    O cotovelo desloca metade do pulso e a mão detectada acompanha o pulso sem
    mudar de forma; blocos de mão ausentes continuam exatamente zerados.
    """
    fora = seq.copy()
    for pulso, cotovelo, (a, b) in _BRACOS:
        centro = fora[:, pulso, :2].mean(axis=0)
        delta = (k - 1.0) * (fora[:, pulso, :2] - centro)
        fora[:, pulso, :2] += delta
        fora[:, cotovelo, :2] += 0.5 * delta
        presente = np.abs(seq[:, a:b, :2]).sum(axis=(1, 2)) > 0
        fora[presente, a:b, :2] += delta[presente, None, :]
    return fora


def aumentar_dominio(seq: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Variações medidas entre MINDS e corpora externos: repouso e amplitude."""
    if seq.shape[1] != 57:
        raise ValueError(f"aumentar_dominio exige 57 pontos por frame, veio {seq.shape[1]}")
    fora = seq
    if rng.random() < CHANCE_REPOUSO:
        ini, fim = frames_parados(fora)
        corte_ini = int(rng.integers(0, ini + 1))
        corte_fim = int(rng.integers(0, fim + 1))
        if len(fora) - corte_ini - corte_fim >= 3:
            fora = fora[corte_ini:len(fora) - corte_fim]
    if rng.random() < CHANCE_AMPLITUDE:
        fora = escalar_amplitude(fora, rng.uniform(*FAIXA_AMPLITUDE))
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

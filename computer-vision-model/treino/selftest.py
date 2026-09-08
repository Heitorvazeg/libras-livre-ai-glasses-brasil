"""Validação do pipeline de treino com dados sintéticos — sem dataset real.

O treino real custa horas de CPU. Este self-test exercita o caminho inteiro em
segundos, com clipes sintéticos, para que um erro de encanamento apareça agora e
não depois de duas horas de ResNet:

  1. Skeleton-DML: forma da imagem, faixa [0,1], descarte dos frames sobrando;
  2. espelhamento: troca de fato os pares esquerda/direita e é involução;
  3. augmentação: preserva a forma e não gera NaN;
  4. partições leave-one-signer-out: teste, validação e treino sempre disjuntos;
  5. treino de ponta a ponta numa rodada: o modelo aprende classes separáveis;
  6. controle negativo: com rótulos aleatórios, a acurácia não sai da chance —
     garante que o item 5 não está passando por vazamento de dados.

Uso:
    python selftest.py
"""
from __future__ import annotations

import argparse
import sys
import tempfile
import traceback
from pathlib import Path

import numpy as np
import torch

import dados as dd
import representacao as rp
import treinar as tr

POSE = ["nariz", "olho_esq", "olho_dir", "orelha_esq", "orelha_dir", "boca_esq",
        "boca_dir", "ombro_esq", "ombro_dir", "cotovelo_esq", "cotovelo_dir",
        "pulso_esq", "pulso_dir", "quadril_esq", "quadril_dir"]
N_PONTOS = len(POSE) + 42


def _ok(msg: str) -> None:
    print(f"  ✓ {msg}")


def _clipe_sintetico(rng, classe: int, n_classes: int, pessoa: int, n_frames: int) -> np.ndarray:
    """Trajetória senoidal com frequência/fase próprias da classe (+ estilo por pessoa)."""
    t = np.linspace(0, 1, n_frames)[:, None, None]
    base = rng.normal(0, 0.05, size=(1, N_PONTOS, 2))
    direcao = np.zeros((1, N_PONTOS, 2))
    direcao[0, :, 0] = np.cos(np.linspace(0, np.pi, N_PONTOS)) * (1 + classe)
    direcao[0, :, 1] = np.sin(np.linspace(0, np.pi, N_PONTOS)) * (1 + classe)
    freq = 1.0 + classe * (2.0 / max(n_classes, 1))
    fase = pessoa * 0.15
    onda = np.sin(2 * np.pi * freq * t + fase)
    ruido = rng.normal(0, 0.02, size=(n_frames, N_PONTOS, 2))
    return (base + onda * direcao * 0.4 + ruido).astype(np.float32)


def _dataset_sintetico(destino: Path, n_pessoas=4, n_classes=3, reps=3, seed=7,
                       rotulo_aleatorio=False) -> None:
    rng = np.random.default_rng(seed)
    destino.mkdir(parents=True, exist_ok=True)
    for p in range(1, n_pessoas + 1):
        for c in range(n_classes):
            for r in range(1, reps + 1):
                n_frames = int(rng.integers(30, 45))
                classe_geradora = int(rng.integers(0, n_classes)) if rotulo_aleatorio else c
                seq = _clipe_sintetico(rng, classe_geradora, n_classes, p, n_frames)
                # .npy real tem 3 dims; dados.carregar corta para x,y
                seq3 = np.concatenate([seq, np.zeros_like(seq[:, :, :1])], axis=2)
                np.save(destino / f"pessoaM{p:02d}_sinal-classe{c}_rep{r:02d}.npy", seq3)


# ------------------------------------------------------------------- testes

def teste_representacao() -> None:
    seq = np.random.uniform(-1.5, 1.5, size=(100, N_PONTOS, 2)).astype(np.float32)
    img = rp.para_imagem(seq)
    assert img.shape == (N_PONTOS, 2 * (100 // 3), 3), f"forma inesperada: {img.shape}"
    assert 0.0 <= img.min() and img.max() <= 1.0, "imagem fora de [0,1]"

    # valores acima do limite são cortados, não estouram a faixa
    extremo = np.full((6, N_PONTOS, 2), 99.0, dtype=np.float32)
    assert rp.para_imagem(extremo).max() <= 1.0, "clip do limite falhou"

    # clipe curto demais precisa falhar explicitamente, não gerar imagem vazia
    try:
        rp.para_imagem(np.zeros((2, N_PONTOS, 2), dtype=np.float32))
        raise AssertionError("clipe de 2 frames deveria ter sido rejeitado")
    except ValueError:
        pass
    _ok("Skeleton-DML: forma, faixa [0,1] e rejeição de clipe curto")


def teste_espelho() -> None:
    perm = rp.permutacao_espelho(POSE)
    assert len(perm) == N_PONTOS
    i_e, i_d = POSE.index("ombro_esq"), POSE.index("ombro_dir")
    assert perm[i_e] == i_d and perm[i_d] == i_e, "ombros não trocaram"
    assert perm[POSE.index("nariz")] == POSE.index("nariz"), "ponto central deveria ficar"
    base = len(POSE)
    assert list(perm[base:base + 21]) == list(range(base + 21, base + 42)), \
        "bloco da mão esquerda deveria virar o da direita"

    seq = np.random.uniform(-1, 1, size=(10, N_PONTOS, 2)).astype(np.float32)
    espelhado = rp.espelhar(seq, perm)
    assert np.allclose(espelhado[:, i_d, 0], -seq[:, i_e, 0]), \
        "x do ombro esquerdo deveria virar -x na posição do direito"
    assert np.allclose(rp.espelhar(espelhado, perm), seq), "espelhar 2x não voltou ao original"

    # pose_indices sem par _dir tem de falhar alto, não gerar mapa errado em silêncio
    try:
        rp.permutacao_espelho(["nariz", "ombro_esq"])
        raise AssertionError("faltando o par _dir, deveria ter levantado ValueError")
    except ValueError:
        pass
    _ok("espelhamento troca os pares e é involução")


def teste_augmentacao() -> None:
    rng = np.random.default_rng(0)
    perm = rp.permutacao_espelho(POSE)
    seq = np.random.uniform(-1, 1, size=(40, N_PONTOS, 2)).astype(np.float32)
    for _ in range(20):
        aug = rp.aumentar(seq, rng, perm)
        assert aug.shape == seq.shape, f"augmentação mudou a forma: {aug.shape}"
        assert np.isfinite(aug).all(), "augmentação gerou NaN/inf"
    _ok("augmentação preserva forma e não gera NaN")


def teste_particoes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        destino = Path(tmp)
        _dataset_sintetico(destino, n_pessoas=4, n_classes=3, reps=2)
        clipes = dd.carregar(destino, fontes="minds")
        assert len(clipes) == 4 * 3 * 2, f"esperava 24 clipes, veio {len(clipes)}"
        assert clipes[0].seq.shape[2] == 2, "carregar deveria devolver só x,y"

        parts = dd.particoes(clipes)
        assert len(parts) == 4, f"esperava 4 rodadas, veio {len(parts)}"
        for p in parts:
            assert p.teste != p.validacao, "teste e validação não podem ser a mesma pessoa"
            assert p.teste not in p.treino and p.validacao not in p.treino, \
                "pessoa de teste/validação vazou para o treino"
            assert len(p.treino) == 2, f"treino deveria ter 2 pessoas, veio {p.treino}"
        assert {p.teste for p in parts} == set(dd.pessoas(clipes)), \
            "toda pessoa precisa ser testada uma vez"

        # com menos de 3 pessoas não há como separar teste E validação
        poucos = [c for c in clipes if c.pessoa in ("M01", "M02")]
        try:
            dd.particoes(poucos)
            raise AssertionError("com 2 pessoas deveria ter falhado")
        except SystemExit:
            pass
    _ok("partições LOSO: teste, validação e treino disjuntos")


def _rodada_sintetica(rotulo_aleatorio: bool, epocas: int = 6) -> float:
    with tempfile.TemporaryDirectory() as tmp:
        destino = Path(tmp)
        _dataset_sintetico(destino, n_pessoas=4, n_classes=3, reps=4,
                           rotulo_aleatorio=rotulo_aleatorio)
        clipes = dd.carregar(destino, fontes="minds")
        rotulos = dd.rotulos(clipes)
        perm = rp.permutacao_espelho(POSE)
        part = dd.particoes(clipes)[0]

        args = argparse.Namespace(epocas=epocas, lr=1e-3, wd=1e-4, batch=8, workers=0)
        treino = [c for c in clipes if c.pessoa in part.treino]
        val = [c for c in clipes if c.pessoa == part.validacao]
        teste = [c for c in clipes if c.pessoa == part.teste]
        acc, _, _, _, _ = tr.treinar_rodada(treino, val, teste, rotulos, perm,
                                            args, torch.device("cpu"))
        return acc


def teste_treino_ponta_a_ponta() -> None:
    acc = _rodada_sintetica(rotulo_aleatorio=False)
    assert acc > 0.60, f"classes separáveis deveriam passar de 60%, veio {acc:.1%}"
    _ok(f"treino de ponta a ponta aprende classes separáveis ({acc:.0%})")


def teste_controle_negativo() -> None:
    """Rótulo aleatório tem de ficar na chance — senão há vazamento em algum lugar."""
    acc = _rodada_sintetica(rotulo_aleatorio=True)
    assert acc < 0.70, (f"com rótulos aleatórios a acurácia foi {acc:.1%} — alta demais "
                        "para 3 classes; suspeite de vazamento entre treino e teste")
    _ok(f"controle negativo: rótulo aleatório fica perto da chance ({acc:.0%})")


TESTES = [
    ("Skeleton-DML (representação)", teste_representacao),
    ("espelhamento esquerda/direita", teste_espelho),
    ("augmentação", teste_augmentacao),
    ("partições leave-one-signer-out", teste_particoes),
    ("treino de ponta a ponta", teste_treino_ponta_a_ponta),
    ("controle negativo (rótulo aleatório)", teste_controle_negativo),
]


def main() -> int:
    torch.set_num_threads(4)
    torch.manual_seed(0)
    print("[selftest] pipeline de treino — dados sintéticos, sem dataset real")
    falhas = 0
    for nome, fn in TESTES:
        try:
            fn()
        except Exception:
            falhas += 1
            print(f"  ✗ {nome}")
            for linha in traceback.format_exc().splitlines():
                print(f"    {linha}")
    if falhas:
        print(f"\n[selftest] {falhas} de {len(TESTES)} testes falharam.")
        return 1
    print("\n[selftest] tudo OK — o pipeline de treino está coerente.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

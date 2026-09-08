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
from unittest.mock import patch

import numpy as np
import torch

import dados as dd
import gcn
import modelo as mm
import representacao as rp
import treinar as tr

POSE = ["nariz", "olho_esq", "olho_dir", "orelha_esq", "orelha_dir", "boca_esq",
        "boca_dir", "ombro_esq", "ombro_dir", "cotovelo_esq", "cotovelo_dir",
        "pulso_esq", "pulso_dir", "quadril_esq", "quadril_dir"]
N_PONTOS = len(POSE) + 42


def _ok(msg: str) -> None:
    print(f"  ✓ {msg}")


def _clipe_sintetico(rng, classe: int, n_classes: int, pessoa: int, n_frames: int) -> np.ndarray:
    """Trajetória senoidal com frequência e padrão espacial próprios da classe.

    O padrão espacial por classe é ALEATÓRIO-mas-fixo (semeado pela classe), não
    uma função suave do índice do ponto. Isso importa: um padrão suave no índice
    é trivial para a representação em imagem (índices viram linhas vizinhas) e
    quase invisível para o GCN, cuja vizinhança é anatômica — o teste passaria a
    medir a representação em vez do encanamento. Assim as duas arquiteturas
    enxergam a mesma dificuldade.
    """
    t = np.linspace(0, 1, n_frames)[:, None, None]
    rng_classe = np.random.default_rng(1000 + classe)
    direcao = rng_classe.normal(0, 1.0, size=(1, N_PONTOS, 2))
    base = rng.normal(0, 0.05, size=(1, N_PONTOS, 2))
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


def _rodada_sintetica(rotulo_aleatorio: bool, epocas: int = 6,
                      arquitetura: str = "resnet") -> float:
    with tempfile.TemporaryDirectory() as tmp:
        destino = Path(tmp)
        _dataset_sintetico(destino, n_pessoas=6, n_classes=3, reps=8,
                           rotulo_aleatorio=rotulo_aleatorio)
        clipes = dd.carregar(destino, fontes="minds")
        rotulos = dd.rotulos(clipes)
        perm = rp.permutacao_espelho(POSE)
        part = dd.particoes(clipes)[0]

        args = argparse.Namespace(epocas=epocas, lr=1e-3, wd=1e-4, batch=8, workers=0,
                                  arquitetura=arquitetura)
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


def teste_grafo_conectado() -> None:
    """Mãos precisam alcançar o tronco: grafo desconectado isola os dedos."""
    import numpy as _np
    v = gcn.N_POSE + 2 * gcn.N_MAO
    adj = _np.zeros((v, v), dtype=bool)
    for i, j in gcn.arestas():
        adj[i, j] = adj[j, i] = True
    visto, fila = {0}, [0]
    while fila:
        u = fila.pop()
        for w in _np.where(adj[u])[0]:
            if int(w) not in visto:
                visto.add(int(w))
                fila.append(int(w))
    assert len(visto) == v, f"grafo desconectado: {sorted(set(range(v)) - visto)} inalcançáveis"

    a = gcn.adjacencia(v, gcn.arestas())
    assert a.shape == (2, v, v), a.shape
    assert torch.allclose(a[1].sum(0), torch.ones(v), atol=1e-5), \
        "adjacência dos vizinhos não está normalizada por grau"
    _ok(f"grafo do esqueleto conectado ({v} nós, {len(gcn.arestas())} arestas)")


def teste_gcn_ponta_a_ponta() -> None:
    acc = _rodada_sintetica(rotulo_aleatorio=False, arquitetura="gcn")
    assert acc > 0.60, f"GCN em classes separáveis deveria passar de 60%, veio {acc:.1%}"
    _ok(f"treino de ponta a ponta com ST-GCN ({acc:.0%})")


def teste_controle_negativo(arquitetura: str = "resnet") -> None:
    """Rótulo aleatório tem de ficar na chance — senão há vazamento em algum lugar."""
    acc = _rodada_sintetica(rotulo_aleatorio=True, arquitetura=arquitetura)
    assert acc < 0.70, (f"com rótulos aleatórios a acurácia foi {acc:.1%} — alta demais "
                        "para 3 classes; suspeite de vazamento entre treino e teste")
    _ok(f"controle negativo ({arquitetura}): rótulo aleatório ({acc:.0%})")


def teste_gcn_controle_negativo() -> None:
    teste_controle_negativo(arquitetura="gcn")


def teste_checkpoints() -> None:
    """Salvar/carregar preserva arquitetura, configuração, rótulos e predições."""
    rotulos = ["classe0", "classe1", "classe2"]
    with tempfile.TemporaryDirectory() as tmp:
        caminho = Path(tmp) / "modelo.pt"
        casos = [
            ("gcn", gcn.construir(3), torch.randn(2, 2, gcn.T_FIXO, N_PONTOS)),
            ("gcn", gcn.construir(3, largura=16, canais_ent=3, dropout=0.1),
             torch.randn(2, 3, 16, N_PONTOS)),
            ("resnet", mm.construir(3, pretreinado=False), torch.randn(2, 3, 64, 64)),
        ]
        resnet18_original = mm.resnet18

        def resnet_sem_download(**kwargs):
            assert kwargs.get("weights") is None, "carregar não deve baixar pesos ImageNet"
            return resnet18_original(**kwargs)

        for arquitetura, original, entrada in casos:
            original.eval()
            meta = {"origem": "selftest"}
            mm.salvar(original, caminho, rotulos, meta)
            with patch.object(mm, "resnet18", side_effect=resnet_sem_download):
                restaurado, classes, info = mm.carregar(caminho)
            assert type(restaurado) is type(original), "arquitetura alterada ao carregar"
            assert not restaurado.training, "carregar deve devolver modelo em modo eval"
            assert classes == rotulos and info == meta, "rótulos/metadados alterados"
            if arquitetura == "gcn":
                assert restaurado.config == original.config, "configuração GCN perdida"
            with torch.no_grad():
                torch.testing.assert_close(restaurado(entrada), original(entrada))

        # Formato antigo: GCN padrão com arquitetura em meta.args; ResNet sem ela.
        for arquitetura, original, entrada in (casos[0], casos[2]):
            meta = {"args": {"arquitetura": arquitetura}} if arquitetura == "gcn" else {}
            torch.save({"state_dict": original.state_dict(), "rotulos": rotulos,
                        "meta": meta}, caminho)
            with patch.object(mm, "resnet18", side_effect=resnet_sem_download):
                restaurado, classes, info = mm.carregar(caminho)
            assert classes == rotulos and info == meta
            with torch.no_grad():
                torch.testing.assert_close(restaurado(entrada), original(entrada))

        torch.save({"arquitetura": "desconhecida", "rotulos": rotulos}, caminho)
        try:
            mm.carregar(caminho)
        except ValueError as exc:
            assert "arquitetura" in str(exc)
        else:
            raise AssertionError("arquitetura desconhecida deveria falhar explicitamente")
    _ok("checkpoints GCN/ResNet: round-trip, variantes, legado e carga sem download")


TESTES = [
    ("Skeleton-DML (representação)", teste_representacao),
    ("espelhamento esquerda/direita", teste_espelho),
    ("augmentação", teste_augmentacao),
    ("partições leave-one-signer-out", teste_particoes),
    ("treino de ponta a ponta", teste_treino_ponta_a_ponta),
    ("grafo do esqueleto (ST-GCN)", teste_grafo_conectado),
    ("treino de ponta a ponta com ST-GCN", teste_gcn_ponta_a_ponta),
    ("controle negativo (rótulo aleatório)", teste_controle_negativo),
    ("controle negativo ST-GCN", teste_gcn_controle_negativo),
    ("checkpoints GCN e ResNet", teste_checkpoints),
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

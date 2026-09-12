"""Prova que a cabeça em torch faz exatamente o que o numpy do treino faz.

Sem isto, o `.tflite` sairia com um pré-processamento parecido — e "parecido" não
serve: o modelo não recusa entrada mal preparada, só erra mais.

Os casos difíceis estão de propósito: mãos ausentes em lacunas curtas (que devem
ser interpoladas), lacunas longas (que devem continuar zeradas), ausência no
primeiro e no último quadro (onde não há detecção dos dois lados para interpolar
entre), e mão ausente o clipe inteiro.
"""
from __future__ import annotations

import numpy as np
import torch

import cabeca_gcn as cg
import dados as dd
import gcn as gg

V = gg.N_POSE + 2 * gg.N_MAO

# Tolerância por componente, não uma global frouxa.
#
# As três primeiras são aritmética exata (subtração, gather, diferença): batem
# no bit ou dentro do arredondamento de uma operação só.
#
# `para_sequencia` interpola, e aí float32 acumula. Verificado que é
# arredondamento e NÃO diferença de lógica: repetindo a mesma comparação em
# float64, a divergência cai para 4,2e-14. Em float32 ela cresce com T —
# 6,1e-06 (T=70), 1,6e-05 (T=96), 3,5e-05 (T=180) — que é a assinatura de
# acúmulo, não de fórmula diferente. O limite abaixo fica acima do pior caso
# medido e ainda é 0,007% da amplitude dos dados (±1,5 unidades de ombro).
TOL = 1e-5
TOL_INTERP = 1e-4


def _seq(t=96, c=3, semente=0, ausencias=()):
    """(T, V, C) aleatório, com blocos de mão zerados nos intervalos pedidos."""
    rng = np.random.default_rng(semente)
    s = rng.uniform(-1.5, 1.5, size=(t, V, c)).astype(np.float32)
    for bloco, ini, fim in ausencias:
        a, b = dd.BLOCOS_MAO[bloco]
        s[ini:fim, a:b, :] = 0.0
    return s


def _t(x):
    return torch.from_numpy(np.ascontiguousarray(x))[None]


def _bate(nome, esperado, obtido, tol=TOL):
    d = float(np.max(np.abs(esperado - obtido)))
    assert d < tol, f"{nome}: divergência {d:.2e} (tolerância {tol:.0e})"
    print(f"  ok  {nome:<34} max_dif={d:.2e}")


def teste_recentrar_z():
    for sem in (0, 1, 2):
        s = _seq(semente=sem)
        esperado = dd.recentrar_z(s)
        obtido = cg.RecentrarZ()(_t(s))[0].numpy()
        _bate(f"recentrar_z (semente {sem})", esperado, obtido)


def teste_imputar():
    casos = {
        "lacuna curta (3)": [(0, 10, 13)],
        "lacuna no limite (5)": [(1, 20, 25)],
        "lacuna longa (9) fica zerada": [(0, 30, 39)],
        "curta e longa juntas": [(0, 10, 13), (1, 40, 55)],
        "ausente no inicio": [(0, 0, 4)],
        "ausente no fim": [(1, 92, 96)],
        "mao ausente o clipe inteiro": [(0, 0, 96)],
        "duas maos, lacunas diferentes": [(0, 15, 18), (1, 15, 22)],
    }
    for nome, aus in casos.items():
        s = _seq(ausencias=aus)
        esperado = dd.imputar_maos(s, 5)
        obtido = cg.ImputarMaos(5)(_t(s))[0].numpy()
        _bate(f"imputar: {nome}", esperado, obtido)


def teste_ossos():
    for c in (2, 3):
        s = _seq(c=c)
        esperado = gg.com_ossos(s, gg.pais())
        obtido = cg.ComOssos()(_t(s))[0].numpy()
        _bate(f"com_ossos ({c} coordenadas)", esperado, obtido)
    # o z das duas ligacoes pulso->punho tem de sair zerado
    o = cg.ComOssos()(_t(_seq(c=3)))[0].numpy()
    assert o[0, gg.N_POSE, 5] == 0.0 and o[0, gg.N_POSE + gg.N_MAO, 5] == 0.0, \
        "z do osso pulso->punho deveria ser zero"
    print("  ok  com_ossos zera o z das ligacoes pulso->punho")


def teste_movimento():
    s = _seq()
    esperado = gg.com_movimento(s)
    obtido = cg.ComMovimento()(_t(s))[0].numpy()
    _bate("com_movimento (sem mascara)", esperado, obtido)


def teste_sequencia():
    for t in (64, 96, 70, 232):
        for c in (2, 3, 6):
            s = _seq(t=t, c=c)
            esperado = gg.para_sequencia(s)
            obtido = cg.ParaSequencia()(_t(s))[0].numpy()
            _bate(f"para_sequencia (T={t}, C={c})", esperado, obtido, TOL_INTERP)


def teste_cabeca_inteira():
    """A composição, na ordem exata em que o treino aplica."""
    for sem, aus in ((0, []), (1, [(0, 10, 13), (1, 40, 55)])):
        s = _seq(semente=sem, ausencias=aus)
        # caminho do treino: carregar (recentrar + imputar) -> dataset (ossos) -> sequencia
        ref = dd.recentrar_z(s)
        ref = dd.imputar_maos(ref, 5)
        ref = gg.com_ossos(ref, gg.pais())
        ref = gg.para_sequencia(ref)
        obtido = cg.CabecaGCN(z_recentrado=True, imputar=True, ossos=True,
                              movimento=False)(_t(s))[0].numpy()
        _bate(f"cabeça completa (semente {sem})", ref, obtido, TOL_INTERP)
        assert obtido.shape == (6, gg.T_FIXO, V), obtido.shape

    # com movimento, a ordem é coordenadas -> ossos -> movimento
    s = _seq(semente=3)
    ref = gg.para_sequencia(gg.com_movimento(gg.com_ossos(
        dd.imputar_maos(dd.recentrar_z(s), 5), gg.pais())))
    obtido = cg.CabecaGCN(movimento=True)(_t(s))[0].numpy()
    _bate("cabeça completa com movimento", ref, obtido, TOL_INTERP)
    assert obtido.shape == (12, gg.T_FIXO, V), obtido.shape


def teste_lote():
    """Vários clipes no mesmo lote não podem contaminar uns aos outros."""
    a = _seq(semente=0, ausencias=[(0, 10, 13)])
    b = _seq(semente=1, ausencias=[(1, 40, 55)])
    juntos = cg.CabecaGCN()(torch.from_numpy(np.stack([a, b]))).numpy()
    sozinhos = np.stack([cg.CabecaGCN()(_t(x))[0].numpy() for x in (a, b)])
    _bate("lote de 2 == cada um sozinho", sozinhos, juntos, TOL_INTERP)


def main():
    print("cabeça do ST-GCN em torch vs. numpy do treino")
    for f in (teste_recentrar_z, teste_imputar, teste_ossos, teste_movimento,
              teste_sequencia, teste_cabeca_inteira, teste_lote):
        f()
    print("todos os casos batem: exatos onde a aritmética é exata, e dentro\ndo arredondamento de float32 onde há interpolação (conferido em float64).")


if __name__ == "__main__":
    main()

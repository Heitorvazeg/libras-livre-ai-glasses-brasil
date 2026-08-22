"""Diagnóstico do resultado do §6 — por que a acurácia deu o que deu.

`evaluate.py` responde *quanto*; este script responde *de onde vem o erro*. Ele
não substitui a avaliação oficial: recorta o MESMO protocolo
leave-one-signer-out em subconjuntos do dataset, para separar efeitos que o
número único mistura:

  - **por base de origem** — clipes de bases diferentes vivem em regiões
    diferentes do espaço de landmarks (condição de gravação, enquadramento,
    ritmo). Deixar uma pessoa de fora numa base pequena testa também um estúdio
    novo, não só uma pessoa nova. O relatório mostra de onde vem o vizinho mais
    próximo de cada clipe — se os vizinhos nunca cruzam a fronteira entre bases,
    juntá-las não somou pessoas, somou um degrau de domínio.
  - **por subconjunto de sinais** — rótulos pareados por julgamento entre bases
    (`validado: false` em ../datasets/selecao.yaml) podem ser variantes
    diferentes do sinal, e aí o erro é do rótulo, não do modelo.
  - **varredura dos parâmetros de DTW** (`--varredura`) — janela de Sakoe-Chiba,
    normalização por comprimento e z ligado/desligado, todos combinados. São
    botões que o `config.yaml` expõe e que ninguém tinha medido; a varredura diz
    qual combinação sustenta o melhor baseline antes de trocar de modelo.
  - **com e sem a coordenada z** (`--sem-z`) — o z das mãos é relativo ao punho
    e o de pose ao quadril; não estão no mesmo referencial. O config.yaml aponta
    `normalizacao.usar_z: false` como o primeiro botão a testar na zona amarela,
    e aqui ele é medido em vez de suposto.

A matriz de distâncias sai do cache de `evaluate.py` quando existe (rode-o
antes); o modo `--sem-z` recalcula uma matriz própria (~1 min para 430 clipes).

Uso:
    python src/evaluate.py        # gera a matriz oficial (uma vez)
    python src/diagnostico.py
    python src/diagnostico.py --sem-z
"""
from __future__ import annotations

import argparse
import time
from collections import defaultdict
from dataclasses import replace
from pathlib import Path

import numpy as np

from config import Config, load_config
from dtw_classifier import (Clip, assinatura_dataset, carregar_dataset,
                            matriz_distancias)

# Sinais cujo rótulo é idêntico nas duas bases (../datasets/selecao.yaml,
# validado: true). Os demais foram pareados por julgamento e aguardam consultor.
VALIDADOS = {"acontecer", "amarelo", "banheiro", "barulho", "espelho", "filho", "ruim"}


def acuracia_loso(clips: list[Clip], dist: np.ndarray, indices: list[int]) -> tuple[float, int]:
    """Acurácia média entre rodadas (§6.2) restrita a `indices`."""
    pessoas = sorted({clips[i].pessoa for i in indices})
    accs = []
    for p in pessoas:
        teste = [i for i in indices if clips[i].pessoa == p]
        referencia = [i for i in indices if clips[i].pessoa != p]
        if not teste or not referencia:
            continue
        acertos = sum(clips[i].sinal == clips[min(referencia, key=lambda k: dist[i, k])].sinal
                      for i in teste)
        accs.append(acertos / len(teste))
    return (float(np.mean(accs)) if accs else float("nan")), len(pessoas)


def grupo(clip: Clip) -> str:
    """Base de origem pelo prefixo da pessoa (M01 -> M, V02 -> V, 03 -> próprio)."""
    return clip.pessoa[0] if clip.pessoa[:1].isalpha() else "coleta própria"


def cenarios(clips: list[Clip], dist: np.ndarray) -> None:
    todos = list(range(len(clips)))
    grupos = sorted({grupo(c) for c in clips})
    validados = [i for i in todos if clips[i].sinal in VALIDADOS]

    linhas = [("dataset completo", todos)]
    if len(grupos) > 1:
        linhas += [(f"só a base {g}", [i for i in todos if grupo(clips[i]) == g]) for g in grupos]
    if len(validados) < len(todos):
        linhas.append(("só os sinais de rótulo validado", validados))
        for g in grupos if len(grupos) > 1 else []:
            linhas.append((f"base {g} + só validados",
                           [i for i in validados if grupo(clips[i]) == g]))

    print(f"{'cenário':<38}{'pessoas':>8}{'clipes':>8}{'acurácia':>10}")
    print("-" * 64)
    for nome, idx in linhas:
        acc, n_pessoas = acuracia_loso(clips, dist, idx)
        if n_pessoas < 2:
            print(f"{nome:<38}{n_pessoas:>8}{len(idx):>8}{'—':>10}  (precisa de ≥2 pessoas)")
            continue
        print(f"{nome:<38}{n_pessoas:>8}{len(idx):>8}{acc:>9.1%}")


def vizinhos_por_grupo(clips: list[Clip], dist: np.ndarray) -> None:
    """De qual base vem o vizinho mais próximo de cada clipe (evidência de domínio)."""
    grupos = sorted({grupo(c) for c in clips})
    if len(grupos) < 2:
        return
    print("\nvizinho mais próximo, por base de origem:")
    todos = list(range(len(clips)))
    for g in grupos:
        conta: dict[str, list[int]] = defaultdict(lambda: [0, 0])  # grupo -> [acertos, total]
        for i in (k for k in todos if grupo(clips[k]) == g):
            referencia = [k for k in todos if clips[k].pessoa != clips[i].pessoa]
            j = min(referencia, key=lambda k: dist[i, k])
            alvo = conta[grupo(clips[j])]
            alvo[0] += clips[i].sinal == clips[j].sinal
            alvo[1] += 1
        total = sum(v[1] for v in conta.values())
        partes = ", ".join(f"{k}: {v[1]} ({v[1] / total:.0%}, acerta {v[0] / v[1]:.0%})"
                           for k, v in sorted(conta.items()))
        print(f"  clipes da base {g:<3} -> {partes}")
    print("  (vizinhos que nunca cruzam a fronteira entre bases = as bases não se\n"
          "   somaram como 'mais pessoas'; cada uma é um domínio à parte)")


def sem_z(clips: list[Clip], cfg: Config) -> list[Clip]:
    """Mesmos clipes sem a coordenada z (fatia os arrays já extraídos)."""
    novos = []
    for c in clips:
        pontos = c.seq.reshape(c.seq.shape[0], cfg.num_pontos, cfg.dims)[:, :, :2]
        novos.append(Clip(c.pessoa, c.sinal, c.rep,
                          np.ascontiguousarray(pontos.reshape(pontos.shape[0], -1),
                                               dtype=np.double)))
    return novos


def matriz(clips: list[Clip], cfg: Config, usar_cache: bool = True) -> np.ndarray:
    cache = cfg.path("results") / f"dtw_matrix_{assinatura_dataset(clips, cfg)}.npy"
    if usar_cache and cache.exists():
        return np.load(cache)
    print("[diag] calculando matriz de distâncias (não está em cache)...", flush=True)
    m = matriz_distancias(clips, cfg, verboso=False)
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.save(cache, m)
    return m


JANELAS = (None, 10, 20, 40)  # banda de Sakoe-Chiba, em frames


def varredura(clips: list[Clip], cfg: Config) -> None:
    """Combina os botões de DTW do config.yaml e mede cada combinação.

    A janela muda o cálculo do DTW (uma matriz por valor); a normalização por
    comprimento é um divisor aplicado à matriz pronta, então sai de graça.
    """
    todos = list(range(len(clips)))
    validados = [i for i in todos if clips[i].sinal in VALIDADOS]
    limpo = [i for i in validados if grupo(clips[i]) == "M"]
    comprimentos = np.array([c.seq.shape[0] for c in clips], dtype=np.float64)
    divisor = (comprimentos[:, None] + comprimentos[None, :]) / 2.0

    variantes_z = [(True, clips)] if cfg.dims >= 3 else []
    variantes_z.append((False, sem_z(clips, cfg) if cfg.dims >= 3 else clips))

    print(f"{'z':<4}{'janela':>8}{'norm.compr.':>13}{'completo':>11}{'M+validados':>14}")
    print("-" * 50)
    resultados = []
    for usar_z, base in variantes_z:
        for janela in JANELAS:
            cfg_j = replace(cfg, dtw={**cfg.dtw, "janela": janela,
                                      "normalizar_por_comprimento": False})
            m = matriz(base, cfg_j)
            for normalizar in (False, True):
                d = m / divisor if normalizar else m
                acc_todos, _ = acuracia_loso(base, d, todos)
                acc_limpo, _ = acuracia_loso(base, d, limpo) if limpo else (float("nan"), 0)
                resultados.append((acc_todos, usar_z, janela, normalizar, acc_limpo))
                print(f"{'sim' if usar_z else 'não':<4}{str(janela or '—'):>8}"
                      f"{('sim' if normalizar else 'não'):>13}"
                      f"{acc_todos:>10.1%}{acc_limpo:>14.1%}", flush=True)

    melhor = max(resultados)
    print(f"\nmelhor no dataset completo: usar_z={'true' if melhor[1] else 'false'}, "
          f"janela={melhor[2] if melhor[2] else 'null'}, "
          f"normalizar_por_comprimento={'true' if melhor[3] else 'false'} "
          f"-> {melhor[0]:.1%} (M+validados {melhor[4]:.1%})")
    print("Aplique em config.yaml (normalizacao.usar_z / dtw.janela / "
          "dtw.normalizar_por_comprimento) e rode evaluate.py de novo.")


def main() -> None:
    ap = argparse.ArgumentParser(description="Diagnóstico do resultado da avaliação (§6).")
    ap.add_argument("--sem-z", action="store_true",
                    help="repete tudo descartando a coordenada z (recalcula a matriz)")
    ap.add_argument("--varredura", action="store_true",
                    help="combina os botões de DTW do config.yaml e mede cada combinação "
                         "(recalcula uma matriz por janela; alguns minutos)")
    args = ap.parse_args()

    cfg = load_config()
    clips = carregar_dataset(cfg=cfg)
    if not clips:
        raise SystemExit("[diag] nenhum landmark em data/landmarks — rode src/extract.py antes.")
    print(f"[diag] {len(clips)} clipes | {len({c.pessoa for c in clips})} pessoas | "
          f"{len({c.sinal for c in clips})} sinais | {cfg.dims} dims por ponto\n")

    if args.varredura:
        inicio = time.time()
        varredura(clips, cfg)
        print(f"[diag] varredura em {(time.time() - inicio) / 60:.1f} min")
        return

    dist = matriz(clips, cfg)
    cenarios(clips, dist)
    vizinhos_por_grupo(clips, dist)

    if args.sem_z:
        if cfg.dims < 3:
            print("\n[diag] --sem-z: o config já está sem z (normalizacao.usar_z: false).")
            return
        print("\n=== mesmos cenários, descartando a coordenada z ===")
        clips2 = sem_z(clips, cfg)
        cenarios(clips2, matriz(clips2, cfg))


if __name__ == "__main__":
    main()

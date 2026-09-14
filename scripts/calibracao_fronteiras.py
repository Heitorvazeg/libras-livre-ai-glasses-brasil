#!/usr/bin/env python3
"""Calibração rápida do detector de fronteiras de sinal, a partir dos CSVs do gravador do app.

docs/prontidao-demo/01-segmentacao.md §1.10 e guia de testes, parte B.1, item 4. Com os óculos,
grava-se (gravador de sessão ligado, §1.9):
  - R1: 20 s com as mãos visíveis e paradas, depois 20 s em repouso  -> piso de ruído;
  - R2: os sinais, um de cada vez, com repouso entre eles            -> velocidade dentro dos sinais.

Uso:
    python scripts/calibracao_fronteiras.py sessoes/R1-*.csv sessoes/R2-*.csv
    python scripts/calibracao_fronteiras.py --repouso a.csv --sinais b.csv c.csv

Sem --repouso/--sinais, o arquivo cujo nome começa com R1 é repouso e com R2 é sinais.

Calcula, com a mesma velocidade que o detector usa (coluna v_suavizada, em larguras de ombro/s):
  - piso de ruído: p95 da velocidade em R1 (frames com pose);
  - limiares sugeridos: saída ≈ 1,5–2× o piso, entrada ≈ 2,5–3× o piso (imprime o meio da faixa);
  - checagem: o p10 da velocidade DENTRO dos sinais de R2 (estado SINALIZANDO) tem de ficar acima do
    limiar de saída. Se não ficar, o problema é enquadramento ou suavização, não o limiar;
  - pausa interna: a maior sequência abaixo do limiar de saída dentro de um sinal (inversões, holds);
  - teto de oclusão: a maior sequência sem as duas mãos dentro de um sinal.

Os valores vão para as configurações de demo do app (Segmentação), sem gerar outro APK. Só usa a
biblioteca padrão.
"""
from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Frame:
    ts: int
    estado: str
    v: float | None
    pose: bool
    mao_esq: bool
    mao_dir: bool


def ler_frames(caminho: Path) -> list[Frame]:
    frames = []
    with caminho.open(encoding="utf-8", newline="") as arquivo:
        for linha in csv.DictReader(arquivo):
            if linha.get("tipo") != "frame":
                continue
            v = linha.get("v_suavizada") or ""
            frames.append(Frame(
                ts=int(linha["ts_ms"]),
                estado=linha.get("estado", ""),
                v=float(v) if v else None,
                pose=linha.get("pose") == "1",
                mao_esq=linha.get("mao_esq") == "1",
                mao_dir=linha.get("mao_dir") == "1",
            ))
    return frames


def percentil(valores: list[float], p: float) -> float:
    ordenados = sorted(valores)
    if not ordenados:
        raise ValueError("sem valores")
    posicao = (len(ordenados) - 1) * p
    baixo = int(posicao)
    alto = min(baixo + 1, len(ordenados) - 1)
    return ordenados[baixo] + (ordenados[alto] - ordenados[baixo]) * (posicao - baixo)


def trechos_sinalizando(frames: list[Frame]) -> list[list[Frame]]:
    """Sequências contíguas de frames em SINALIZANDO (um sinal cada)."""
    trechos, atual = [], []
    for f in frames:
        if f.estado == "SINALIZANDO":
            atual.append(f)
        elif atual:
            trechos.append(atual)
            atual = []
    if atual:
        trechos.append(atual)
    return trechos


def maior_sequencia_ms(trecho: list[Frame], condicao) -> int:
    """Maior duração (ms) de frames consecutivos que satisfazem a condição."""
    maior, inicio = 0, None
    for f in trecho:
        if condicao(f):
            inicio = f.ts if inicio is None else inicio
            maior = max(maior, f.ts - inicio)
        else:
            inicio = None
    return maior


def calibrar(repouso: list[Frame], sinais: list[Frame]) -> dict:
    ruido = [f.v for f in repouso if f.pose and f.v is not None]
    if not ruido:
        raise ValueError("R1 sem frames com pose e velocidade")
    piso = percentil(ruido, 0.95)
    saida = piso * 1.75
    entrada = piso * 2.75

    trechos = trechos_sinalizando(sinais)
    dentro = [f.v for t in trechos for f in t if f.v is not None]
    p10 = percentil(dentro, 0.10) if dentro else None
    pausa = max((maior_sequencia_ms(t, lambda f: f.v is not None and f.v < saida) for t in trechos), default=0)
    oclusao = max((maior_sequencia_ms(t, lambda f: not f.mao_esq and not f.mao_dir) for t in trechos), default=0)
    return {
        "frames_repouso": len(ruido),
        "sinais": len(trechos),
        "piso_ruido": piso,
        "limiar_saida": saida,
        "limiar_saida_faixa": (piso * 1.5, piso * 2.0),
        "limiar_entrada": entrada,
        "limiar_entrada_faixa": (piso * 2.5, piso * 3.0),
        "p10_dentro_dos_sinais": p10,
        "checagem_ok": p10 is not None and p10 > saida,
        "maior_pausa_interna_ms": pausa,
        "maior_oclusao_ms": oclusao,
    }


def separar_por_nome(arquivos: list[Path]) -> tuple[list[Path], list[Path]]:
    repouso = [a for a in arquivos if a.name.upper().startswith("R1")]
    sinais = [a for a in arquivos if a.name.upper().startswith("R2")]
    return repouso, sinais


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("arquivos", nargs="*", type=Path, help="CSVs com nome R1-* (repouso) e R2-* (sinais)")
    ap.add_argument("--repouso", nargs="+", type=Path, default=[])
    ap.add_argument("--sinais", nargs="+", type=Path, default=[])
    args = ap.parse_args(argv)

    repouso_arq, sinais_arq = list(args.repouso), list(args.sinais)
    if args.arquivos:
        r, s = separar_por_nome(args.arquivos)
        repouso_arq += r
        sinais_arq += s
    if not repouso_arq or not sinais_arq:
        print("preciso de pelo menos um CSV de repouso (R1) e um de sinais (R2)", file=sys.stderr)
        return 2

    repouso = [f for a in repouso_arq for f in ler_frames(a)]
    sinais = [f for a in sinais_arq for f in ler_frames(a)]
    r = calibrar(repouso, sinais)

    print(f"frames de repouso: {r['frames_repouso']} · sinais em R2: {r['sinais']}")
    print(f"piso de ruído (p95 em R1):   {r['piso_ruido']:.3f} ombros/s")
    print(f"limiar de saída sugerido:    {r['limiar_saida']:.3f}  (faixa {r['limiar_saida_faixa'][0]:.3f}–{r['limiar_saida_faixa'][1]:.3f})")
    print(f"limiar de entrada sugerido:  {r['limiar_entrada']:.3f}  (faixa {r['limiar_entrada_faixa'][0]:.3f}–{r['limiar_entrada_faixa'][1]:.3f})")
    if r["p10_dentro_dos_sinais"] is None:
        print("checagem: R2 sem frames em SINALIZANDO — o detector não entrou em nenhum sinal")
    else:
        veredito = "ok" if r["checagem_ok"] else "FALHOU: revise enquadramento ou suavização, não o limiar"
        print(f"p10 dentro dos sinais (R2):  {r['p10_dentro_dos_sinais']:.3f}  -> {veredito}")
    print(f"maior pausa interna:         {r['maior_pausa_interna_ms']} ms  (pausaMs precisa ficar acima, com folga)")
    print(f"maior perda de mãos:         {r['maior_oclusao_ms']} ms  (tetoOclusaoMs precisa ficar acima)")
    return 0 if r["checagem_ok"] else 1


if __name__ == "__main__":
    sys.exit(main())

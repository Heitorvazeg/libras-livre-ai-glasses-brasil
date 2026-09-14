#!/usr/bin/env python3
"""Latência por etapa do app, a partir do CSV do gravador ou do logcat.

docs/prontidao-demo/06-latencia.md §6.5: cada turno do app marca o tempo de cada etapa
("iniciar -> pode sinalizar", classificação, contextualização, frase -> primeiro áudio...).
As marcas saem em dois lugares, com o mesmo texto:

  - no CSV da sessão (gravador, §1.9): linhas tipo=evento, nome=latencia, detalhe="turno=.. etapa=.. ms=..";
  - no logcat, com a tag LibrasLatencia (sem ':' — o filtro -s do logcat não aceita):
        adb logcat -s LibrasLatencia > latencia.txt

Uso:
    python scripts/latencia_por_etapa.py sessoes/20260915-101500.csv
    python scripts/latencia_por_etapa.py latencia.txt
    adb logcat -d -s LibrasLatencia | python scripts/latencia_por_etapa.py -

Imprime, por etapa, o número de medidas, a mediana, o p90 e o máximo (ms), e a meta do §6.6.
Só usa a biblioteca padrão.
"""
from __future__ import annotations

import csv
import re
import statistics
import sys

MARCA = re.compile(r"turno=(\d+)\s+etapa=(\S+)\s+ms=(-?\d+)")

# Metas do §6.6, em ms.
METAS = {
    "iniciar_pode_sinalizar": 3000,
    "classificacao": 100,
    "contextualizacao": 1500,
    "frase_primeiro_audio": 1000,
    "fim_fala_texto": 1500,
    "texto_avatar": 3000,
}

ORDEM = [
    "iniciar_pode_sinalizar", "fim_movimento_segmento", "classificacao", "contextualizacao",
    "frase_primeiro_audio", "fim_fala_texto", "texto_avatar",
]


def ler_linhas(caminho: str) -> list[str]:
    if caminho == "-":
        return sys.stdin.read().splitlines()
    with open(caminho, encoding="utf-8", newline="") as arquivo:
        primeira = arquivo.readline()
        arquivo.seek(0)
        if primeira.startswith("tipo,"):
            return [linha["detalhe"] for linha in csv.DictReader(arquivo)
                    if linha.get("tipo") == "evento" and linha.get("nome") == "latencia"]
        return arquivo.read().splitlines()


def percentil(valores: list[int], p: float) -> float:
    ordenados = sorted(valores)
    if len(ordenados) == 1:
        return float(ordenados[0])
    posicao = (len(ordenados) - 1) * p
    baixo = int(posicao)
    alto = min(baixo + 1, len(ordenados) - 1)
    return ordenados[baixo] + (ordenados[alto] - ordenados[baixo]) * (posicao - baixo)


def agregar(linhas: list[str]) -> dict[str, list[int]]:
    por_etapa: dict[str, list[int]] = {}
    for linha in linhas:
        m = MARCA.search(linha)
        if m:
            por_etapa.setdefault(m.group(2), []).append(int(m.group(3)))
    return por_etapa


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    por_etapa = agregar(ler_linhas(sys.argv[1]))
    if not por_etapa:
        print("nenhuma marca de latência encontrada", file=sys.stderr)
        return 1
    etapas = [e for e in ORDEM if e in por_etapa] + sorted(set(por_etapa) - set(ORDEM))
    print(f"{'etapa':<24} {'n':>4} {'mediana':>8} {'p90':>8} {'máx':>8} {'meta':>8}")
    for etapa in etapas:
        v = por_etapa[etapa]
        meta = METAS.get(etapa)
        acima = " ←" if meta is not None and statistics.median(v) > meta else ""
        print(f"{etapa:<24} {len(v):>4} {statistics.median(v):>8.0f} {percentil(v, 0.9):>8.0f} "
              f"{max(v):>8} {meta if meta is not None else '—':>8}{acima}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

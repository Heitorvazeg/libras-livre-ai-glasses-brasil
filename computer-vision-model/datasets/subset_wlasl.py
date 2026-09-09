"""Monta o diretório de um subconjunto do WLASL (WLASL100/300/1000) por links.

POR QUE LINKS E NÃO CÓPIA. `ingest_wlasl.py` baixa os 11.980 clipes de uma vez,
porque download é rede barata; a extração de landmarks é que custa horas de CPU e
por isso deve ser feita por partes. Este script monta o recorte a ser extraído
como links simbólicos — nada é duplicado em disco, e trocar de subconjunto é
instantâneo.

`extract.py` enxerga os links como arquivos normais, então:

    python subset_wlasl.py --subset 100
    cd ../PoC && python src/extract.py \\
        --entrada data/raw-wlasl-100 --saida data/landmarks-wlasl

Uso:
    python subset_wlasl.py --subset 100     # ~1.013 clipes, 100 classes
    python subset_wlasl.py --subset 300     # ~2.660 clipes
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

from ingest_wlasl import DESTINO, KAGGLE, coletar
from remote_zip import zip_do_kaggle

AQUI = Path(__file__).resolve().parent


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--subset", type=int, required=True, choices=[100, 300, 1000, 2000])
    ap.add_argument("--saida", help="diretório do recorte (padrão: raw-wlasl-<subset>)")
    args = ap.parse_args()

    destino = Path(args.saida) if args.saida else DESTINO.parent / f"raw-wlasl-{args.subset}"
    destino.mkdir(parents=True, exist_ok=True)

    z = zip_do_kaggle(KAGGLE, cache_dir=AQUI / ".cache")
    with tempfile.TemporaryDirectory() as t:
        clipes = coletar(z, args.subset, Path(t))

    ligados = ausentes = 0
    for c in clipes:
        origem = DESTINO / c.destino
        link = destino / c.destino
        if not origem.exists():
            ausentes += 1          # ainda não baixado
            continue
        if not link.exists():
            link.symlink_to(origem.resolve())
        ligados += 1

    print(f"[subset] WLASL{args.subset}: {ligados} clipe(s) ligados em {destino}")
    if ausentes:
        print(f"[subset] ⚠ {ausentes} clipe(s) do subconjunto ainda não estão em "
              f"{DESTINO} — rode ingest_wlasl.py ou espere o download terminar")


if __name__ == "__main__":
    sys.exit(main())

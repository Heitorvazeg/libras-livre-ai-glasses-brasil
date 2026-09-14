"""Diagnóstico do índice dos bundles do Kaggle — quem o ingest.py enxerga, e quem perde.

Motivação (docs/investigacao-expansao-dataset.md, Ação A): a PoC roda com 8
sinalizadores do MINDS-Libras, mas a base publicada tem 12 — são ~183 clipes a mais
nos nossos 10 sinais, e 11 -> 15 pessoas na avaliação leave-one-signer-out. Falta
saber se os 4 que faltam estão ausentes do bundle do Kaggle ou se o `ingest.py` os
descarta: `_RE_MINDS` é ancorado em `^`, então qualquer membro dentro de uma subpasta
é ignorado **em silêncio**.

Este script não baixa vídeo nenhum: lê só o diretório central do zip (o mesmo rodapé
que o ingest.py já usa) e responde:

  - quantos membros o bundle tem, quantos casam com o regex e quantos não casam;
  - se casar contra o NOME-BASE (ignorando subpastas) encontraria mais alguém;
  - a lista de sinalizadores/articuladores por sinal do nosso vocabulário.

Uso:
    python diagnostico_bundle.py                 # as duas fontes
    python diagnostico_bundle.py --fonte minds   # só o MINDS-Libras
"""
from __future__ import annotations

import argparse
import collections
import posixpath

from ingest import (AQUI, _RE_MINDS, _RE_VLIBRASIL, carregar_selecao)
from remote_zip import zip_do_kaggle

_REGEX = {"minds": _RE_MINDS, "vlibrasil": _RE_VLIBRASIL}


def _diagnosticar(fonte: str, sel: dict, cache: bool) -> None:
    slug = sel["fontes"][fonte]["kaggle"]
    regex = _REGEX[fonte]
    campo = "minds" if fonte == "minds" else "vlibrasil"
    palavras = {s[campo]: s["sinal"] for s in sel["sinais"] if s.get(campo)}

    print(f"\n{'=' * 72}\n[{fonte}] {slug}")
    z = zip_do_kaggle(slug, cache_dir=AQUI / ".cache" if cache else None)
    print(f"[{fonte}] membros no bundle: {len(z)}")

    casam, casam_pelo_base, nao_casam = [], [], []
    for nome in z.membros:
        if regex.match(nome):
            casam.append(nome)
        elif regex.match(posixpath.basename(nome)):
            casam_pelo_base.append(nome)
        elif nome.lower().endswith((".mp4", ".avi", ".wmv", ".mkv")):
            nao_casam.append(nome)

    print(f"[{fonte}] casam com o regex atual:        {len(casam)}")
    print(f"[{fonte}] casariam pelo nome-base:        {len(casam_pelo_base)}"
          f"{'   <-- ESTÃO SENDO PERDIDOS' if casam_pelo_base else ''}")
    print(f"[{fonte}] vídeos que não casam de jeito nenhum: {len(nao_casam)}")
    for exemplo in nao_casam[:5]:
        print(f"[{fonte}]     ex.: {exemplo}")
    for exemplo in casam_pelo_base[:5]:
        print(f"[{fonte}]     perdido: {exemplo}")

    # Cobertura do nosso vocabulário: pessoa x sinal, contando os dois modos de casar.
    cobertura: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    pessoas: set[str] = set()
    for nome in casam + casam_pelo_base:
        m = regex.match(nome) or regex.match(posixpath.basename(nome))
        sinal = palavras.get(m["palavra"])
        if not sinal:
            continue
        pessoa = f"{int(m['pessoa']):02d}"
        cobertura[sinal][pessoa] += 1
        pessoas.add(pessoa)

    if not pessoas:
        print(f"[{fonte}] nenhum clipe do nosso vocabulário encontrado — confira selecao.yaml")
        return

    ordenadas = sorted(pessoas)
    print(f"\n[{fonte}] pessoas encontradas ({len(ordenadas)}): {', '.join(ordenadas)}")
    print(f"[{fonte}] cobertura por sinal (clipes por pessoa):\n")
    print("  " + f"{'sinal':<12}" + " ".join(f"{p:>4s}" for p in ordenadas))
    for sinal in sorted(cobertura):
        linha = " ".join(f"{cobertura[sinal].get(p, 0):>4d}" for p in ordenadas)
        print("  " + f"{sinal:<12}" + linha)
    total = sum(sum(c.values()) for c in cobertura.values())
    print(f"\n[{fonte}] total de clipes do vocabulário: {total}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--fonte", choices=sorted(_REGEX), action="append",
                    help="fonte a diagnosticar (padrão: todas)")
    ap.add_argument("--sem-cache", action="store_true",
                    help="relê o índice do zip em vez de usar .cache/")
    args = ap.parse_args()

    sel = carregar_selecao()
    for fonte in args.fonte or sorted(_REGEX):
        _diagnosticar(fonte, sel, cache=not args.sem_cache)


if __name__ == "__main__":
    main()

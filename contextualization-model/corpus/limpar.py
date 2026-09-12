"""Remove paráfrases que INVENTAM conteúdo temporal/eventivo (§8.1).

Medido no v2: o modelo inventa em 13,2% das sessões e as referências do corpus
inventam em 13,2% — ele aprendeu comigo. A correção é no corpus.

DISTINÇÃO que a lista abaixo codifica: verbo leve não é invenção. "eu quero TOMAR
a vacina" para [eu, querer, vacina] não afirma nada além; "eu volto AMANHÃ" para
[eu, voltar] afirma um dia que ninguém sinalizou. São só os segundos que saem.

Uso: python corpus/limpar.py [--aplicar]
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import yaml

AQUI = Path(__file__).resolve().parent

# Conteúdo que o sinalizante NÃO sinalizou e que muda o que foi dito: tempo,
# lugar, evento, cortesia acrescentada. Verbo leve (tomar/trazer/pegar/levar)
# fica de fora de propósito.
INVENCAO = re.compile(r"""\b(
    amanh[ãa]|ontem|hoje|cheguei|chegou|chegar|depois|daqui|dali|agora\smesmo|
    atender|atendimento|momento|instante|completo|completa|
    outro\sdia|mais\starde|come[çc]a|come[çc]ou|semana|m[êe]s|ano|
    tudo\sbem|prazer|assim|algo
)\b""", re.IGNORECASE | re.VERBOSE)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--aplicar", action="store_true")
    args = ap.parse_args()

    caminho = AQUI / "parafrases.yaml"
    dados = yaml.safe_load(caminho.read_text("utf-8"))
    total = sum(len(v) for v in dados.values())
    limpo, removidas = {}, []
    for i, frases in dados.items():
        mantidas = [f for f in frases if not INVENCAO.search(f)]
        removidas += [(i, f) for f in frases if INVENCAO.search(f)]
        if len(mantidas) < 3:      # nunca deixa uma sequência quase sem exemplo
            mantidas = frases
            removidas = [r for r in removidas if r[0] != i]
        limpo[i] = mantidas

    restam = sum(len(v) for v in limpo.values())
    print(f"paráfrases: {total} -> {restam}  (removidas {total-restam} = {(total-restam)/total:.1%})")
    print(f"sequências que ficariam com < 3 exemplos foram PRESERVADAS inteiras")
    for i, f in removidas[:15]:
        print(f"  #{i}: {f!r}")
    if args.aplicar:
        linhas = ["# Paráfrases em PT-BR por sequência (índice = posição em sequencias.yaml).",
                  "# Escritas por claude-opus-5; limpas por corpus/limpar.py (§8.1 — invenção).",
                  ""]
        for i in sorted(limpo):
            linhas.append(f"{i}: " + json.dumps(limpo[i], ensure_ascii=False))
        caminho.write_text("\n".join(linhas) + "\n", "utf-8")
        print(f"\naplicado em {caminho}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Taxa de sucesso POR FRASE do roteiro, simulando a decisão do app sobre LOSO.

O app decide por frase, não por sinal (`AvaliadorDeFrase`): se qualquer sinal
ficar abaixo do corte de confiança, a frase inteira vira "não entendi". Este
script usa os logits de teste de uma execução LOSO com `--salvar-evidencias`
(pessoas que o modelo não viu) e conta, para cada corte:

  - falada certa: todos os sinais acima do corte E todos corretos;
  - pedido de repetição: algum sinal abaixo do corte;
  - FALADA ERRADA: todos acima do corte, mas algum sinal errado (o pior caso).

Para cada pessoa e cada frase, combina exaustivamente as repetições disponíveis
(5 por sinal no MINDS), então cada frase de 3 sinais vira 125 tentativas. Não
treina, não ajusta nada e não escolhe corte: só descreve.

Uso:
    python3 scripts/simular_frase_roteiro.py <pasta-loso> [--temperatura T]
        [--cortes 0.0,0.6,0.9] [--json saida.json]

<pasta-loso> é a saída do treino LOSO: contém `rodadas/NN-Pessoa.json` e
`rodadas/artefatos/NN-Pessoa.evidencias.json`.
"""
from __future__ import annotations

import argparse
import itertools
import json
import re
from collections import defaultdict
from pathlib import Path

import numpy as np

# Roteiro da demo (mesma ordem de PlaceholderSignClassifier.ROTEIRO no app).
FRASES = (("filho", "vacina", "vontade"), ("cinco",), ("filho", "medo"), ("banheiro", "vontade"))
CORTES_PADRAO = (0.0, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95)
NOME = re.compile(r"^pessoa(?P<pessoa>[A-Za-z0-9]+)_sinal-(?P<sinal>.+)_rep(?P<rep>\d+)\.npy$")


def softmax(z: np.ndarray, temperatura: float) -> np.ndarray:
    z = np.asarray(z, dtype=np.float64) / temperatura
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def ler_folds(pasta: Path, temperatura: float) -> dict:
    """Por pessoa de teste: {sinal: [(confianca, acertou), ...]} dos clipes dela."""
    marcadores = sorted((pasta / "rodadas").glob("*.json"))
    if not marcadores:
        raise SystemExit(f"sem rodadas em {pasta / 'rodadas'}")
    pessoas, rotulos_ref = {}, None
    for marcador in marcadores:
        registro = json.loads(marcador.read_text(encoding="utf-8"))
        if "evidencias" not in registro:
            raise SystemExit(f"{marcador.name} sem evidências: rode o LOSO com --salvar-evidencias")
        caminho = marcador.parent / registro["evidencias"]["saidas"]["arquivo"]
        evidencias = json.loads(caminho.read_text(encoding="utf-8"))
        rotulos = evidencias["rotulos"]
        if rotulos_ref is None:
            rotulos_ref = rotulos
        elif rotulos != rotulos_ref:
            raise SystemExit(f"{caminho.name}: rótulos divergem entre rodadas")
        teste = evidencias["teste"]
        probs = softmax(np.array(teste["logits"], dtype=np.float64), temperatura)
        por_sinal: dict[str, list] = defaultdict(list)
        for identificador, p, verdadeiro in zip(teste["ids"], probs, teste["verdadeiros"]):
            m = NOME.match(identificador)
            if not m:
                raise SystemExit(f"id fora da convenção: {identificador}")
            if m["pessoa"] != registro["teste"]:
                raise SystemExit(f"{caminho.name}: clipe de {m['pessoa']} no teste de {registro['teste']}")
            if rotulos[verdadeiro] != m["sinal"]:
                raise SystemExit(f"{identificador}: rótulo {rotulos[verdadeiro]} não bate com o nome")
            por_sinal[m["sinal"]].append((float(p.max()), int(p.argmax()) == verdadeiro))
        pessoas[registro["teste"]] = dict(por_sinal)
    return pessoas


def simular(pessoas: dict, cortes) -> dict:
    """Para cada corte: contagem de frases faladas certas, repetidas e faladas erradas."""
    resultado = {c: {"certa": 0, "repeticao": 0, "errada": 0, "total": 0,
                     "por_frase": defaultdict(lambda: [0, 0, 0, 0]),
                     "por_pessoa": defaultdict(lambda: [0, 0, 0, 0])} for c in cortes}
    for pessoa, por_sinal in sorted(pessoas.items()):
        for frase in FRASES:
            faltando = [s for s in frase if s not in por_sinal]
            if faltando:
                raise SystemExit(f"{pessoa}: sem clipes de {faltando} para a frase {frase}")
            for tentativa in itertools.product(*(por_sinal[s] for s in frase)):
                confiancas = [c for c, _ in tentativa]
                todos_certos = all(ok for _, ok in tentativa)
                for corte in cortes:
                    aceita = min(confiancas) >= corte
                    chave = "certa" if (aceita and todos_certos) else "repeticao" if not aceita else "errada"
                    r = resultado[corte]
                    r[chave] += 1
                    r["total"] += 1
                    indice = {"certa": 0, "repeticao": 1, "errada": 2}[chave]
                    r["por_frase"][" ".join(frase)][indice] += 1
                    r["por_frase"][" ".join(frase)][3] += 1
                    r["por_pessoa"][pessoa][indice] += 1
                    r["por_pessoa"][pessoa][3] += 1
    return resultado


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("pasta", type=Path)
    ap.add_argument("--temperatura", type=float, default=1.0)
    ap.add_argument("--cortes", default=",".join(str(c) for c in CORTES_PADRAO))
    ap.add_argument("--json", type=Path, help="grava o resumo também em JSON")
    args = ap.parse_args(argv)
    if not (args.temperatura > 0 and np.isfinite(args.temperatura)):
        ap.error("--temperatura precisa ser positiva e finita")
    cortes = tuple(sorted({float(c) for c in args.cortes.split(",")}))
    if any(not 0.0 <= c <= 1.0 for c in cortes):
        ap.error("--cortes precisa estar entre 0 e 1")

    pessoas = ler_folds(args.pasta, args.temperatura)
    resultado = simular(pessoas, cortes)
    n_frases = sum(len(f) for f in FRASES)
    print(f"{len(pessoas)} pessoa(s) de teste: {', '.join(sorted(pessoas))} | "
          f"{len(FRASES)} frases ({n_frases} sinais) | temperatura {args.temperatura:g}")
    print(f"\n{'corte':>6} {'falada certa':>14} {'pede repetição':>16} {'FALADA ERRADA':>15}")
    for corte in cortes:
        r = resultado[corte]
        t = r["total"]
        print(f"{corte:>6.2f} {r['certa'] / t:>13.1%} {r['repeticao'] / t:>16.1%} {r['errada'] / t:>15.1%}")

    pior = max(cortes)
    print(f"\npor frase, no corte {pior:.2f} (certa / repetição / errada):")
    for frase, (certa, rep, errada, total) in sorted(resultado[pior]["por_frase"].items()):
        print(f"  {frase:26s} {certa / total:>6.1%} {rep / total:>8.1%} {errada / total:>8.1%}")
    if args.json:
        args.json.write_text(json.dumps({
            "temperatura": args.temperatura, "pessoas": sorted(pessoas), "frases": [list(f) for f in FRASES],
            "cortes": {f"{c:.2f}": {k: v for k, v in resultado[c].items() if k != "por_frase" and k != "por_pessoa"}
                       | {"por_frase": dict(resultado[c]["por_frase"]), "por_pessoa": dict(resultado[c]["por_pessoa"])}
                       for c in cortes}}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"\nresumo em {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

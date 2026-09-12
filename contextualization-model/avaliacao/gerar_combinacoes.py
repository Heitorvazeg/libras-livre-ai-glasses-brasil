"""Gera avaliacao/combinacoes_nao_vistas.jsonl — o teste que faltava.

A validação de dados.py sorteia sequências das MESMAS 250 escritas à mão, que se
agrupam em torno de poucos padrões: ela mede generalização entre paráfrases
vizinhas, não entre COMBINAÇÕES. Foi por isso que o v1 marcou F1 0,856 na
validação e degenerou em 54% das combinações novas.

Este conjunto não tem referência em PT de propósito: ele não mede tradução, mede
COLAPSO. Um modelo que não sabe compor não deve embarcar, mesmo com métrica boa
na validação.

Determinístico (semente fixa) e versionado, para comparar v1/v2/vN entre si.
"""
from __future__ import annotations

import json
import random
from pathlib import Path

import yaml

RAIZ = Path(__file__).resolve().parent.parent
# Glosas com >= 10 ocorrências no corpus: se falhar AQUI, não é falta de exemplo
# da glosa, é falta de composicionalidade.
FREQUENTES = ["eu", "não", "filho", "querer", "documento", "precisar", "vacina",
              "ajuda", "esperar", "dor", "manhã", "número", "voltar", "você",
              "ruim", "nome", "onde", "conhecer", "medo", "banco", "banheiro",
              "aluno", "oi", "obrigado", "noite", "por-favor"]


def main(n=120, semente=7):
    vistas = {tuple(s["glosas"]) for s in
              yaml.safe_load((RAIZ / "corpus" / "sequencias.yaml").read_text("utf-8"))["sequencias"]}
    rng = random.Random(semente)
    saida, feitas = [], set()
    while len(saida) < n:
        k = rng.choice([2, 3, 3, 4, 4])
        g = rng.sample(FREQUENTES, k)
        t = tuple(g)
        if t in vistas or t in feitas:
            continue
        feitas.add(t)
        saida.append({"glosas": g, "n": k})
    destino = RAIZ / "avaliacao" / "combinacoes_nao_vistas.jsonl"
    destino.write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in saida), "utf-8")
    print(f"{len(saida)} combinações não vistas -> {destino}")


if __name__ == "__main__":
    main()

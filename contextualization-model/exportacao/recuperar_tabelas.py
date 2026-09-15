"""Recupera as tabelas de contrato (glosa_ids, destokenizar, remap) a partir do checkpoint.

POR QUE EXISTE (docs/prontidao-demo/06-latencia.md §6.1): o `podar.py` foi rodado de novo DEPOIS
do treino do v2, sobre um corpus/léxico já alterado. Saíram 1.985 peças; o checkpoint foi
treinado com 1.996. As tabelas versionadas passaram a descrever um vocabulário que o modelo não
conhece, os ids ficaram deslocados e o modelo "gerava lixo" em Python e no app.

COMO: a poda não treina nada, só fatia linhas da matriz de embeddings do ptt5 original, em ordem
crescente de id. Cada linha do checkpoint é casada com a linha mais parecida (cosseno) da matriz
original. O script EXIGE que o casamento seja exato (similaridade ≈ 1, ids únicos e crescentes,
especiais em 0..2) — senão recusa, porque aí o fine-tuning mexeu nos embeddings e o casamento
deixaria de ser prova.

O remap recuperado grava `corpus_sha256` = "recuperado-do-checkpoint": o `dados.carregar()`
recusa treinar com ele, que é o certo — um treino novo precisa de uma poda nova, feita antes.

Uso: python exportacao/recuperar_tabelas.py [--checkpoint resultados-v2/modelo]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch
from transformers import AutoTokenizer, T5ForConditionalGeneration

RAIZ = Path(__file__).resolve().parent.parent
ARTEFATOS = RAIZ / "artefatos"
BASE = "unicamp-dl/ptt5-small-portuguese-vocab"
SIMILARIDADE_MINIMA = 0.999


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", type=Path, default=RAIZ / "resultados-v2" / "modelo")
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(BASE, legacy=False)
    original = T5ForConditionalGeneration.from_pretrained(BASE).shared.weight.data
    treinado = T5ForConditionalGeneration.from_pretrained(args.checkpoint).shared.weight.data

    sims = torch.nn.functional.normalize(treinado, dim=1) @ torch.nn.functional.normalize(original, dim=1).T
    conf, melhor = sims.max(dim=1)
    manter = melhor.tolist()

    problemas = []
    if manter[:3] != [0, 1, 2]:
        problemas.append(f"especiais fora do lugar: {manter[:3]}")
    if any(a >= b for a, b in zip(manter, manter[1:])):
        problemas.append("ids não estritamente crescentes")
    if conf.min().item() < SIMILARIDADE_MINIMA:
        problemas.append(f"similaridade mínima {conf.min().item():.4f} < {SIMILARIDADE_MINIMA}")
    if problemas:
        raise SystemExit("casamento não é exato, tabelas NÃO gravadas: " + "; ".join(problemas))

    remap = {antigo: novo for novo, antigo in enumerate(manter)}
    lexico = json.loads((RAIZ / "lexico" / "lexico-glosas.json").read_text("utf-8"))["glosas"]
    glosa_ids, fora = {}, []
    for glosa in lexico:
        ids = tok(glosa, add_special_tokens=False).input_ids
        if any(i not in remap for i in ids):
            fora.append(glosa)
            continue
        glosa_ids[glosa] = [remap[i] for i in ids]
    if fora:
        raise SystemExit(f"glosas do léxico sem peça no vocabulário do checkpoint: {fora}")

    inv = {i: p for p, i in tok.get_vocab().items()}
    (ARTEFATOS / "remap.json").write_text(json.dumps(
        {"antigo_para_novo": {str(k): v for k, v in remap.items()},
         "novo_para_antigo": manter,
         "corpus_sha256": "recuperado-do-checkpoint"}, ensure_ascii=False), "utf-8")
    (ARTEFATOS / "destokenizar.json").write_text(
        json.dumps([inv.get(a, "<?>") for a in manter], ensure_ascii=False), "utf-8")
    (ARTEFATOS / "glosa_ids.json").write_text(
        json.dumps(glosa_ids, ensure_ascii=False, indent=2), "utf-8")
    print(f"vocabulário do checkpoint: {len(manter)} peças | similaridade mínima {conf.min().item():.4f}")
    print(f"glosas mapeadas: {len(glosa_ids)}/{len(lexico)} | tabelas gravadas em {ARTEFATOS}/")


if __name__ == "__main__":
    main()

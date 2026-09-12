"""Mede o híbrido guarda(modelo) -> template, que é o desenho real do §3.2.

A pergunta que decide se o .tflite vale os ~45MB NÃO é "o modelo bate o template
na média" — é:

    (a) em que fração das sessões a guarda ACEITA a saída do modelo?
    (b) NESSAS sessões, ela é melhor que o template?

Um modelo aceito em 15% das sessões não se paga, mesmo sendo ótimo nesses 15%.
Um modelo aceito em 70% e melhor nesses 70% se paga, mesmo perdendo na média
global — porque na média global entram as sessões em que ele nem é usado.

A guarda aqui tem as DUAS metades (a descoberta de 2026-09-11):
  - cobertura  (§3.2)  — toda glosa aparece em alguma forma do léxico
  - fluência   (novo)  — a saída não é degenerada (modelo/fluencia.py)
Cobertura sozinha é cega quando a decodificação é restrita: ela força as glosas
a aparecerem e o lixo passa.

DECODIFICAÇÃO LIVRE de propósito: com restrição, omissão (detectável) vira
incoerência (que passava na guarda antiga). Melhor deixar o modelo omitir e a
guarda pegar.
"""
from __future__ import annotations

import json
from pathlib import Path

from fluencia import parece_degenerado

RAIZ = Path(__file__).resolve().parent.parent
LEXICO = json.loads((RAIZ / "lexico" / "lexico-glosas.json").read_text("utf-8"))["glosas"]


def guarda_aceita(saida: str, glosas: list[str], cobre, tem_negacao) -> tuple[bool, str]:
    if not all(cobre(saida, g) for g in glosas):
        return False, "cobertura"
    if "não" in glosas and not tem_negacao(saida):
        return False, "negação"
    degenerado, motivo = parece_degenerado(saida, len(glosas))
    if degenerado:
        return False, f"fluência ({motivo})"
    return True, ""


def medir(casos, do_modelo, do_template, cobre, tem_negacao, f1):
    """casos: [(glosas, referências)]. Devolve o que decide o §10 para o híbrido."""
    aceitos, motivos = [], {}
    ganhos = perdas = empates = 0
    for glosas, refs in casos:
        sm, st = do_modelo(glosas), do_template(glosas)
        ok, motivo = guarda_aceita(sm, glosas, cobre, tem_negacao)
        if not ok:
            motivos[motivo] = motivos.get(motivo, 0) + 1
            continue
        fm = max(f1(sm, r) for r in refs)
        ft = max(f1(st, r) for r in refs)
        aceitos.append((glosas, sm, st, fm, ft))
        ganhos += fm > ft + 1e-9
        perdas += ft > fm + 1e-9
        empates += abs(fm - ft) <= 1e-9

    n = len(casos)
    taxa = len(aceitos) / n if n else 0.0
    print(f"\n=== HÍBRIDO guarda(modelo) -> template — {n} casos ===")
    print(f"  (a) guarda ACEITA o modelo : {len(aceitos)}/{n} = {taxa:.1%}")
    for m, c in sorted(motivos.items(), key=lambda x: -x[1]):
        print(f"        rejeitado por {m}: {c}")
    if aceitos:
        fm = sum(a[3] for a in aceitos) / len(aceitos)
        ft = sum(a[4] for a in aceitos) / len(aceitos)
        print(f"  (b) NAS ACEITAS, F1 modelo : {fm:.3f}  vs template {ft:.3f}"
              f"  ({'modelo ganha' if fm > ft else 'template ganha'})")
        print(f"      modelo melhor em {ganhos}, pior em {perdas}, empate em {empates}")
    return {"taxa_aceitacao": taxa, "n_aceitos": len(aceitos),
            "f1_modelo_aceitas": (sum(a[3] for a in aceitos)/len(aceitos)) if aceitos else 0.0,
            "f1_template_aceitas": (sum(a[4] for a in aceitos)/len(aceitos)) if aceitos else 0.0,
            "ganhos": ganhos, "perdas": perdas, "motivos": motivos}

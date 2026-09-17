"""Portão do §10 — as métricas, modelo contra o baseline por regras.

Duas escolhas de método que mudam o número e precisam estar explícitas:

1. MULTI-REFERÊNCIA. Cada sequência de glosas tem 8 traduções aceitáveis no
   corpus. Avaliar contra uma só penalizaria uma saída correta que calhou de
   parafrasear diferente. Exact match e F1 são o MELHOR sobre as referências.
2. AGREGAÇÃO POR SEQUÊNCIA, não por par. O modelo vê uma sequência de glosas e
   produz UMA saída — avaliar 8 vezes a mesma saída só inflaria a amostra.

BLEU não entra: frases de 4-10 palavras tornam n-gramas de ordem 4 ruído (§10).

Uso: python modelo/avaliar.py --modelo ../resultados-v1/modelo
"""
from __future__ import annotations

import argparse
import json
import unicodedata
from collections import defaultdict
from pathlib import Path

import torch
from transformers import AutoTokenizer, T5ForConditionalGeneration

from dados import carregar
from decodificacao import gerar_restrito
from fluencia import parece_degenerado
from hibrido import medir
from invencao import inventadas
from relatorio import escrever
from template import contextualizar

RAIZ = Path(__file__).resolve().parent.parent
LEXICO = json.loads((RAIZ / "lexico" / "lexico-glosas.json").read_text("utf-8"))["glosas"]
NEGACOES = set(LEXICO["não"]["formas"])


def norm(t: str) -> str:
    d = unicodedata.normalize("NFD", t.lower())
    d = "".join(c for c in d if unicodedata.category(c) != "Mn")
    return " ".join("".join(c if c.isalnum() or c.isspace() else " " for c in d).split())


def cobre(frase: str, glosa: str) -> bool:
    a = norm(frase)
    return any(norm(f) in a for f in LEXICO[glosa]["formas"])


def tem_negacao(frase: str) -> bool:
    return any(norm(f) in norm(frase) for f in NEGACOES)


def f1(saida: str, ref: str) -> float:
    a, b = norm(saida).split(), norm(ref).split()
    if not a or not b:
        return 0.0
    comuns = sum(min(a.count(w), b.count(w)) for w in set(a))
    if not comuns:
        return 0.0
    p, r = comuns / len(a), comuns / len(b)
    return 2 * p * r / (p + r)


def avaliar(nome, produzir, casos):
    n = len(casos)
    neg_ok = neg_tot = neg_inventada = 0
    com_invencao, exemplos_inv = 0, []
    recall_soma = em = 0
    f1_soma = 0.0
    rejeitados, exemplos = 0, []

    for glosas, refs in casos:
        saida = produzir(glosas)
        cobertas = [g for g in glosas if cobre(saida, g)]
        recall_soma += len(cobertas) / len(glosas)

        if "não" in glosas:
            neg_tot += 1
            neg_ok += tem_negacao(saida)
        elif tem_negacao(saida):
            neg_inventada += 1

        inv = inventadas(saida, glosas)
        if inv:
            com_invencao += 1
            exemplos_inv.append((glosas, saida, inv))

        guarda_ok = len(cobertas) == len(glosas) and (("não" not in glosas) or tem_negacao(saida))
        rejeitados += not guarda_ok
        em += any(norm(saida) == norm(r) for r in refs)
        f1_soma += max(f1(saida, r) for r in refs)
        exemplos.append((glosas, saida, guarda_ok))

    print(f"\n=== {nome} ===")
    print(f"  acerto de negação   : {neg_ok}/{neg_tot} = {neg_ok/max(neg_tot,1):.3f}   [alvo 1,000 — inegociável]")
    print(f"  negação inventada   : {neg_inventada}/{n-neg_tot}   [alvo 0]")
    print(f"  content-word recall : {recall_soma/n:.3f}   [alvo >= 0,980]")
    print(f"  conteúdo INVENTADO  : {com_invencao}/{n} = {com_invencao/n:.3f}   [§8.1 — a guarda NÃO pega]")
    for g, s, i in exemplos_inv[:3]:
        print(f"        {' '.join(g):<30} {s!r} inventou {i}")
    print(f"  taxa de fallback    : {rejeitados}/{n} = {rejeitados/n:.3f}   [medir]")
    print(f"  exact match (multi) : {em}/{n} = {em/n:.3f}")
    print(f"  F1 de palavras      : {f1_soma/n:.3f}")
    return exemplos, {"negacao": neg_ok/max(neg_tot,1), "recall": recall_soma/n,
                      "invencao": com_invencao/n,
                      "fallback": rejeitados/n, "em": em/n, "f1": f1_soma/n}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--experimento", default="v1")
    args = ap.parse_args()

    _, val, remap = carregar()
    refs = defaultdict(list)
    glosas_de = {}
    for p in val:
        refs[p["seq_id"]].append(p["pt"])
        glosas_de[p["seq_id"]] = p["glosas"]
    casos = [(glosas_de[s], refs[s]) for s in sorted(refs)]
    print(f"conjunto: {len(casos)} sequências de validação ({sum(len(r) for r in refs.values())} referências)")

    tok = AutoTokenizer.from_pretrained("unicamp-dl/ptt5-small-portuguese-vocab", legacy=False)
    modelo = T5ForConditionalGeneration.from_pretrained(
        RAIZ / f"resultados-{args.experimento}" / "modelo").eval()
    destok = json.loads((RAIZ / "artefatos" / "destokenizar.json").read_text("utf-8"))

    def entrada(glosas):
        return [remap[i] for i in tok(" ".join(glosas)).input_ids if i in remap]

    def tokenizar(texto):
        return [remap[i] for i in tok(texto, add_special_tokens=False).input_ids if i in remap]

    def do_modelo(glosas):
        with torch.no_grad():
            s = modelo.generate(torch.tensor([entrada(glosas)]), max_new_tokens=32, num_beams=1)[0]
        return "".join(destok[int(i)] for i in s if int(i) >= 3).replace("▁", " ").strip()

    def do_modelo_restrito(glosas):
        return gerar_restrito(modelo, glosas, entrada(glosas), LEXICO, destok, tokenizar)

    ex_t, m_t = avaliar("TEMPLATE (baseline / portão)", contextualizar, casos)
    _,    m_l = avaliar("MODELO — decodificação livre", do_modelo, casos)
    ex_m, m_m = avaliar("MODELO — decodificação restrita (§6.3)", do_modelo_restrito, casos)

    print("\n=== lado a lado (validação) ===")
    for (g, st, ot), (_, sm, om) in list(zip(ex_t, ex_m))[:15]:
        print(f"  {str(g):<40}\n    template {'  ' if ot else '✗ '}{st!r}\n    modelo   {'  ' if om else '✗ '}{sm!r}")

    # --- combinações NUNCA VISTAS: mede COLAPSO, não tradução ---
    combos = [json.loads(l) for l in
              (RAIZ / "avaliacao" / "combinacoes_nao_vistas.jsonl").read_text("utf-8").splitlines()]
    print(f"\n=== COMBINAÇÕES NÃO VISTAS ({len(combos)}) — só glosas com >= 10 ocorrências ===")
    for nome, fn in (("livre", do_modelo), ("restrita", do_modelo_restrito)):
        ruins = {}
        for c in combos:
            deg, motivo = parece_degenerado(fn(c["glosas"]), len(c["glosas"]))
            if deg:
                ruins[motivo] = ruins.get(motivo, 0) + 1
        tot = sum(ruins.values())
        print(f"  decodificação {nome:<9}: degeneradas {tot}/{len(combos)} = {tot/len(combos):.1%}  {ruins}")
    deg_t = sum(parece_degenerado(contextualizar(c["glosas"]), len(c["glosas"]))[0] for c in combos)
    print(f"  template (referência) : degeneradas {deg_t}/{len(combos)} = {deg_t/len(combos):.1%}")

    # --- híbrido: a pergunta que decide se o .tflite vale os 45MB ---
    m_h = medir(casos, do_modelo, contextualizar, cobre, tem_negacao, f1)

    hist = json.loads((RAIZ / f"resultados-{args.experimento}" / "historico.json").read_text("utf-8"))
    caminho = escrever(args.experimento, m_m, m_t,
                       [(g, st, sm) for (g, st, _), (_, sm, _) in zip(ex_t, ex_m)], hist)
    print(f"\nrelatório em {caminho}")

    venceu = sum(m_m[k] > m_t[k] for k in ("negacao", "recall", "em", "f1")) + (m_m["fallback"] < m_t["fallback"])
    print(f"\nPORTÃO §10: o modelo bate o template em {venceu}/5 métricas.")
    print("Lembrete: isto é VALIDAÇÃO SINTÉTICA. O portão de verdade é o conjunto humano (§5.3),"
          "\nque ainda não existe — nenhum número aqui é evidência de qualidade em Libras real.")


if __name__ == "__main__":
    main()

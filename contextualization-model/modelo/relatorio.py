"""Gera resultados-<exp>/relatorio.md — o entregável.

Formato espelha ../computer-vision-model/treino/resultados-gcn/relatorio.md: data,
protocolo e a linha de baseline no topo, antes de qualquer número do modelo.
É o único arquivo de resultados-*/ que o .gitignore deixa passar.
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent


def escrever(exp: str, m_modelo: dict, m_template: dict, exemplos: list, hist: list) -> Path:
    saida = RAIZ / f"resultados-{exp}" / "relatorio.md"
    manif = json.loads((RAIZ / "corpus" / "pares.manifest.json").read_text("utf-8"))
    linhas = [
        f"# Resultado do fine-tuning — {exp} (glosa → PT)",
        "",
        f"Gerado em {datetime.now():%Y-%m-%d %H:%M} · ptt5-small podado (45,1M, vocab 1.987) · "
        f"{len(hist)} épocas · protocolo: split **por seq_id** (paráfrases da mesma sequência "
        "nunca cruzam treino/validação).",
        "",
        "## ⚠️ O que este número NÃO é",
        "",
        "Validação **sintética**: as referências saíram do mesmo gerador (`claude-opus-5`) que "
        "produziu o treino. Isto mede se o modelo aprendeu o mapeamento que nós inventamos — "
        "não se ele traduz Libras. O portão real é `avaliacao/conjunto_humano.jsonl` (§5.3), "
        "que ainda não existe. Nenhum número daqui deve aparecer em apresentação sem esta ressalva.",
        "",
        "## Resultado",
        "",
        "| Métrica | Template (portão) | Modelo | Alvo §10 |",
        "|---|---|---|---|",
        f"| Acerto de negação | {m_template['negacao']:.3f} | **{m_modelo['negacao']:.3f}** | 1,000 inegociável |",
        f"| Content-word recall | {m_template['recall']:.3f} | **{m_modelo['recall']:.3f}** | ≥ 0,980 |",
        f"| Conteúdo inventado (§8.1) | {m_template['invencao']:.3f} | **{m_modelo['invencao']:.3f}** | 0 — a guarda não pega |",
        f"| Taxa de fallback | {m_template['fallback']:.3f} | **{m_modelo['fallback']:.3f}** | medir |",
        f"| Exact match (multi-ref) | {m_template['em']:.3f} | **{m_modelo['em']:.3f}** | > template |",
        f"| F1 de palavras | {m_template['f1']:.3f} | **{m_modelo['f1']:.3f}** | > template |",
        "",
        f"Corpus: {manif['pares']} pares / {manif['sequencias']} sequências "
        f"({manif['pares_com_negacao']} com negação; {manif['pares_sem_c3']} sem depender das "
        "glosas `camada: 3-proposta`).",
        "",
        "## Curva de perda",
        "",
        "| Época | Treino | Validação |",
        "|---|---|---|",
    ]
    linhas += [f"| {h['epoca']} | {h['treino']:.4f} | {h['val']:.4f} |" for h in hist]
    linhas += ["", "## Saídas lado a lado (validação)", "", "| Glosas | Template | Modelo |", "|---|---|---|"]
    linhas += [f"| `{' '.join(g)}` | {t} | {mo} |" for g, t, mo in exemplos[:25]]
    saida.write_text("\n".join(linhas) + "\n", "utf-8")
    return saida

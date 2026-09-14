"""Monta corpus/pares.jsonl a partir de sequencias.yaml + parafrases.yaml.

Rota B de docs/contextualizacao-glosa-seq2seq-plano.md §5.1.2: as sequências de
glosas são enumeradas à mão sobre o vocabulário fechado (sequencias.yaml) e um
LLM escreve as paráfrases em PT (parafrases.yaml). Este script só junta, valida
e carimba — a geração em si não roda aqui, por decisão: o corpus é artefato
VERSIONADO (§10 do runbook), não algo regenerado a cada treino.

Validação mais importante: `cobertura`. Toda glosa da sequência precisa aparecer
na frase em alguma forma do léxico — é exatamente a checagem que
GuardedGlossContextualizer.cobreTodoConteudo faz no app (§3.2). Rodar aqui
garante que o modelo não é TREINADO em exemplos que a guarda rejeitaria em
produção.

Uso:
    python corpus/gerar.py                 # valida e escreve pares.jsonl
    python corpus/gerar.py --so-validar    # não escreve, só reporta
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import unicodedata
from datetime import date
from pathlib import Path

import yaml

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parent
LEXICO = RAIZ / "lexico" / "lexico-glosas.json"

# Quem escreveu as paráfrases. §5.1.2, ressalva 1: sem isso registrado, "regerar
# o corpus" produz outro corpus e nenhum treino é comparável com o anterior.
GERADOR = {"modelo": "claude-opus-5", "quando": "2026-09-11", "como": "sessão interativa"}


def normalizar(texto: str) -> str:
    """Minúsculas sem acento — comparação tolerante, igual à do app."""
    sem_acento = unicodedata.normalize("NFD", texto.lower())
    return "".join(c for c in sem_acento if unicodedata.category(c) != "Mn")


def cobre(frase: str, glosa: str, lexico: dict) -> bool:
    alvo = normalizar(frase)
    return any(normalizar(f) in alvo for f in lexico[glosa]["formas"])


def commit_atual() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=RAIZ,
                              capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "desconhecido"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--so-validar", action="store_true")
    args = ap.parse_args()

    lexico = json.loads(LEXICO.read_text(encoding="utf-8"))["glosas"]
    c3 = {g for g, v in lexico.items() if v.get("camada") == "3-proposta"}
    seqs = yaml.safe_load((AQUI / "sequencias.yaml").read_text(encoding="utf-8"))["sequencias"]
    parafrases = yaml.safe_load((AQUI / "parafrases.yaml").read_text(encoding="utf-8"))

    # Sequências de glosas IDÊNTICAS (o lote 2 repropõe de propósito algumas que o
    # v1 errou, para ganharem mais paráfrases) recebem o MESMO seq_id. Sem isso
    # elas virariam exemplos distintos e poderiam cair uma no treino e outra na
    # validação — vazamento, já que dados.py separa por seq_id.
    canonico: dict[tuple, int] = {}
    pares, faltando, descobertas = [], [], []
    for i, s in enumerate(seqs):
        chave = tuple(s["glosas"])
        sid = canonico.setdefault(chave, i)
        frases = parafrases.get(i)
        if not frases:
            faltando.append((i, s["glosas"]))
            continue
        for frase in frases:
            ausentes = [g for g in s["glosas"] if not cobre(frase, g, lexico)]
            if ausentes:
                descobertas.append((i, frase, ausentes))
                continue
            pares.append({
                "seq_id": sid,
                "glosas": s["glosas"],
                "pt": frase,
                "contexto": s["contexto"],
                "depende_c3": bool(c3 & set(s["glosas"])),
            })

    print(f"sequências: {len(seqs)} | pares válidos: {len(pares)}")
    if faltando:
        print(f"\n[!] {len(faltando)} sequências SEM paráfrase:")
        for i, g in faltando[:20]:
            print(f"    #{i}: {g}")
    if descobertas:
        print(f"\n[!] {len(descobertas)} paráfrases REJEITADAS (glosa não coberta — a guarda "
              f"do app também as rejeitaria):")
        for i, frase, aus in descobertas[:20]:
            print(f"    #{i}: {frase!r} não cobre {aus}")
    if faltando or descobertas:
        print("\nCorrija antes de treinar: treinar em exemplos que a guarda rejeita ensina o "
              "modelo a produzir saídas que nunca vão ser faladas.")

    if args.so_validar:
        return

    saida = AQUI / "pares.jsonl"
    saida.write_text("".join(json.dumps(p, ensure_ascii=False) + "\n" for p in pares),
                     encoding="utf-8")

    manifesto = {
        "gerado_em": date.today().isoformat(),
        "git_commit": commit_atual(),
        "gerador": GERADOR,
        "vocabulario": sorted(lexico),
        "glosas_camada_3_proposta": sorted(c3),
        "sequencias": len(seqs),
        "sequencias_unicas": len(canonico),
        "pares": len(pares),
        "pares_sem_c3": sum(1 for p in pares if not p["depende_c3"]),
        "pares_com_negacao": sum(1 for p in pares if "não" in p["glosas"]),
        "rejeitados_por_cobertura": len(descobertas),
        "pares_sha256": hashlib.sha256(saida.read_bytes()).hexdigest(),
        "lexico_sha256": hashlib.sha256(LEXICO.read_bytes()).hexdigest(),
    }
    (AQUI / "pares.manifest.json").write_text(
        json.dumps(manifesto, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\npares.jsonl ({len(pares)}) e pares.manifest.json escritos.")


if __name__ == "__main__":
    main()

"""Audita M9 nos pacotes LOSO legados; não treina, extrai arquivos ou infere IDs.

Lê JSON/NPY de tar.gz, confere o backbone por hash sem desserializar PyTorch,
reconstrói matrizes e resume por pessoa. Saída nova privada; não mede frases.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
import json
import math
from pathlib import Path
import tarfile

import numpy as np

PESSOAS = ["M01", "M02", "M05", "M06", "M08", "M10", "M11", "M12"]
ROTEIRO = ["filho", "vacina", "vontade", "cinco", "medo", "banheiro"]
RAIZ = Path(__file__).resolve().parents[1]
FT = {"arquitetura": "gcn", "ossos": True, "com_z": True,
      "z_recentrado": True, "movimento": False, "sem_imputacao": False,
      "adjacencia_adaptativa": False, "kernel_temporal": 9, "fontes": "minds",
      "epocas": 120, "lr": 0.001, "wd": 0.0001, "batch": 64,
      "agendador": "cosseno", "final": False}


def sha(b):
    return hashlib.sha256(b).hexdigest()


def conferir_args(args, esperado):
    for k, v in esperado.items():
        if k not in args or args[k] != v:
            raise ValueError(f"receita divergente: {k}")


def resumir_folds(folds):
    if len(folds) != 8 or sorted(f["teste"] for f in folds) != PESSOAS:
        raise ValueError("exige oito pessoas distintas, não piloto de um fold")
    folds = sorted(folds, key=lambda f: f["teste"])
    rotulos = folds[0]["rotulos"]
    if len(rotulos) != 20 or len(set(rotulos)) != 20 or not set(ROTEIRO) <= set(rotulos):
        raise ValueError("vocabulário inválido")
    args = folds[0]["args"]
    if type(args.get("semente")) is not int:
        raise ValueError("semente ausente")
    total = np.zeros((20, 20), dtype=np.int64)
    pessoas = {}
    for i, f in enumerate(folds):
        conferir_args(f["args"], FT)
        if f["args"] != args or f["rotulos"] != rotulos:
            raise ValueError("folds misturam argumentos/rótulos")
        if f["rodada"] != i + 1 or f["validacao"] != PESSOAS[(i + 1) % 8]:
            raise ValueError("partição/ordem de folds divergente")
        if not 1 <= f["melhor_epoca"] <= args["epocas"]:
            raise ValueError("época selecionada inválida")
        y, p = f["verdadeiros"], f["predicoes"]
        if len(y) != 100 or len(p) != 100 or Counter(y) != Counter({j: 5 for j in range(20)}):
            raise ValueError("denominador por pessoa/sinal inválido")
        if any(type(v) is not int or not 0 <= v < 20 for v in y + p):
            raise ValueError("índice de rótulo inválido")
        cm = np.zeros_like(total)
        np.add.at(cm, (y, p), 1)
        acertos = int(np.trace(cm))
        if not math.isclose(f["acuracia"], acertos / 100, abs_tol=1e-12):
            raise ValueError("acurácia não corresponde às predições")
        pessoas[f["teste"]] = {"acertos": acertos, "total": 100,
            "melhor_epoca": f["melhor_epoca"],
            "roteiro": {s: int(cm[rotulos.index(s), rotulos.index(s)]) for s in ROTEIRO},
            "confusoes_roteiro": [{"verdadeiro": rotulos[a], "predito": rotulos[b], "n": int(cm[a, b])}
                for a, b in zip(*np.nonzero(cm)) if a != b and rotulos[a] in ROTEIRO]}
        total += cm
    sinais = {r: {"acertos": int(total[i, i]), "total": int(total[i].sum()),
                  "recall": float(total[i, i] / total[i].sum())} for i, r in enumerate(rotulos)}
    confusoes = sorted([{"verdadeiro": rotulos[a], "predito": rotulos[b], "n": int(total[a, b])}
                       for a, b in zip(*np.nonzero(total)) if a != b],
                      key=lambda c: (-c["n"], c["verdadeiro"], c["predito"]))
    return {"semente": args["semente"], "args": args, "rotulos": rotulos,
            "acertos": int(np.trace(total)), "total": 800, "sinais": sinais,
            "pessoas": pessoas, "confusoes": confusoes, "matriz": total.tolist(),
            "roteiro_acertos": sum(sinais[s]["acertos"] for s in ROTEIRO), "roteiro_total": 240}


def ler_pacote(pacote):
    hashes, dados = {}, {}
    with tarfile.open(pacote, "r:gz") as tar:
        nomes = set()
        for m in tar:
            if not m.isfile():
                continue
            if m.name in nomes:
                raise ValueError("membro duplicado no pacote")
            nomes.add(m.name)
            rel = Path(m.name)
            if rel.is_absolute() or ".." in rel.parts or len(rel.parts) < 2:
                raise ValueError("caminho inesperado no pacote")
            chave = "/".join(rel.parts[1:])
            if chave in dados or chave in hashes:
                raise ValueError("mais de uma raiz para um artefato")
            if chave.endswith("backbone_gcn.pt"):
                with tar.extractfile(m) as f:
                    hashes[chave] = hashlib.file_digest(f, "sha256").hexdigest()
            elif chave.endswith((".json", "matriz_confusao.npy")):
                if m.size > 64 * 1024 * 1024:
                    raise ValueError("metadados inesperadamente grandes")
                with tar.extractfile(m) as f:
                    b = f.read()
                hashes[chave] = sha(b)
                dados[chave] = b
    def doc(nome):
        return json.loads(dados[nome])
    pre = "pretreino-vlibrasil-malta-contrastivo/"
    usado = doc("backbone-usado.json")
    if usado["checkpoint_sha256"] != hashes[pre + "backbone_gcn.pt"] or usado["metadados_sha256"] != hashes[pre + "backbone_gcn.json"]:
        raise ValueError("backbone usado não corresponde aos bytes")
    meta = doc(pre + "backbone_gcn.json")
    a = meta["args"]
    conferir_args(a, {k: FT[k] for k in ("arquitetura", "ossos", "com_z", "z_recentrado", "movimento", "sem_imputacao", "adjacencia_adaptativa", "kernel_temporal")})
    conferir_args(a, {"objetivo": "contrastivo", "fontes": "malta,vlibrasil", "pessoa_val": "V03",
                     "epocas": 15, "lr": 0.0001, "p_classes": 32, "k_exemplos": 2, "semente": 0})
    if a.get("negativos_extras", 0) != 0:
        raise ValueError("negativos extras fora da receita padrão")
    fontes = Counter(s["registro"]["fonte"] for s in meta["proveniencia"]["dados"]["amostras"])
    if set(fontes) != {"malta", "vlibrasil"}:
        raise ValueError("fontes do inventário divergem da receita")
    prefixo = "finetuning-minds-loso-com-pretreino/"
    folds = [json.loads(b) for n, b in dados.items() if n.startswith(prefixo + "rodadas/") and n.endswith(".json")]
    resumo = resumir_folds(folds)
    matriz = np.load(io.BytesIO(dados[prefixo + "matriz_confusao.npy"]), allow_pickle=False)
    if not np.array_equal(matriz, resumo["matriz"]):
        raise ValueError("matriz salva difere das predições")
    with pacote.open("rb") as f:
        pacote_sha = hashlib.file_digest(f, "sha256").hexdigest()
    return resumo | {"pacote": str(pacote.resolve()), "pacote_sha256": pacote_sha,
        "membros_sha256": hashes, "backbone": usado, "fontes_pre": dict(fontes),
        "execucao": doc("execucao.json"), "pre_args": a,
        "limites": ["predições legadas sem IDs/logits/checkpoints por fold",
                    "não permite parear erros por clipe entre execuções nem calibrar confiança",
                    "não mede frase contínua; não multiplicar recalls como probabilidade da demo"]}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pacotes", type=Path, nargs="+", required=True)
    ap.add_argument("--saida", type=Path, required=True)
    a = ap.parse_args()
    out = a.saida.resolve()
    if out.exists() or (out.is_relative_to(RAIZ) and not out.is_relative_to(RAIZ / "experimentos-privados")):
        ap.error("saída deve ser nova e privada")
    resultados = [ler_pacote(p) for p in a.pacotes]
    if len({r["semente"] for r in resultados}) != len(resultados):
        raise ValueError("sementes repetidas: não tratar duplicatas como novas execuções")
    doc = {"schema": 1, "roteiro": ROTEIRO, "script_sha256": sha(Path(__file__).read_bytes()),
           "execucoes": resultados, "avaliacao_frase": False, "calibracao": False}
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("x", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2, allow_nan=False)
    for r in resultados:
        print(r["semente"], r["acertos"], "/800; roteiro", r["roteiro_acertos"], "/240")


if __name__ == "__main__":
    main()
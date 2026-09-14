#!/usr/bin/env python3
"""Deduplica a coleta e compara cada candidato contra os corpora já usados no treino.

POR QUE ESTE ARQUIVO EXISTE. A triagem (`triagem.csv`) separa o que tem formato de
sinal isolado do que é aula. Ela não responde duas perguntas que decidem se o clipe
serve:

  1. É conteúdo novo? Canal distinto NÃO é sinalizante distinto — a série
     "Dicionário de Libras" está espelhada entre canais, e dois "canais" viram um
     clipe só. Contar canal como pessoa infla a diversidade, que é justamente a
     métrica que falta ao projeto.
  2. A execução bate com a que já treinamos? MALTA e V-LIBRASIL são dicionários
     rotulados. Quando a palavra existe lá, o candidato pode ser comparado contra
     uma referência em vez de julgado do zero.

Nenhuma das duas aprova um clipe. Elas reduzem o que o consultor precisa julgar, e
anexam evidência ao que sobra. Rótulo, variante regional e identidade do sinalizante
continuam decisão humana.

DUAS ASSINATURAS, E POR QUE AS DUAS. O pHash de quadros absolutos pega vinheta,
fundo e marca d'água — então dois vídeos DIFERENTES do mesmo canal, com o mesmo
cenário, aparecem como duplicata. Medido nesta coleta: de 6 pares abaixo de 12/64,
3 eram só template do canal (`esperar` x `por-favor` do "Min e as mãozinhas",
`nao`/`mostrar` x `precisar` do "LibrasLab").

O conserto é assinar o MOVIMENTO: o hash da diferença entre quadros consecutivos
descarta o que é estático (cenário, logo, legenda fixa) e mantém o que é gesto.
Duplicata real precisa bater nas duas; só na absoluta é template compartilhado.

Uso:
    python comparar_referencia_libras.py                  # coleta padrão
    python comparar_referencia_libras.py --sem-imagens    # só a deduplicação
"""
from __future__ import annotations

import argparse
import csv
import itertools
import json
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

RAIZ = Path(__file__).resolve().parents[1]
BASE_PADRAO = RAIZ / "external-data" / "libras-gap"
CORPORA = RAIZ / "computer-vision-model" / "PoC" / "data"
REFERENCIAS = (("raw-pretreino", "V-LIBRASIL"), ("raw-malta", "MALTA"))
CAMPOS = ["palavra", "id", "dur_s", "canal", "titulo", "licenca", "veredito",
          "alertas", "revisao_manual"]

# Limiares em bits de 64. O absoluto é frouxo de propósito: ele só levanta suspeita,
# quem confirma é o de movimento. Calibrados nesta coleta contra os pares conhecidos
# (dois espelhamentos reais em 0,0 e 5,4; três templates de canal entre 3,8 e 11,4).
LIMIAR_ABSOLUTO = 12.0
LIMIAR_MOVIMENTO = 8.0
N_QUADROS = 10


def _hash_dct(cinza: np.ndarray) -> np.ndarray:
    d = cv2.dct(cv2.resize(cinza, (32, 32)).astype(np.float32))[:8, :8]
    return (d > np.median(d)).flatten()


def assinaturas(video: Path, n: int = N_QUADROS) -> tuple[list, list]:
    """(absoluta, movimento): quadros amostrados e a diferença entre consecutivos."""
    cap = cv2.VideoCapture(str(video))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    cinzas = []
    for i in range(n):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(total * (i + 0.5) / n))
        ok, quadro = cap.read()
        if ok:
            cinzas.append(cv2.cvtColor(quadro, cv2.COLOR_BGR2GRAY))
    cap.release()
    if not cinzas:
        return [], []
    absoluta = [_hash_dct(c) for c in cinzas]
    lado = min(c.shape for c in cinzas)
    red = [cv2.resize(c, (lado[1], lado[0])).astype(np.int16) for c in cinzas]
    # A diferença remove tudo que não se move: cenário, logo, legenda fixa.
    movimento = [_hash_dct(np.abs(red[i] - red[i - 1]).astype(np.uint8))
                 for i in range(1, len(red))]
    return absoluta, movimento


def distancia(a: list, b: list) -> float:
    if not a or not b:
        return 64.0
    n = min(len(a), len(b))
    return float(np.mean([np.sum(a[i] != b[i]) for i in range(n)]))


def ler_triagem(base: Path) -> list[dict]:
    with (base / "triagem.csv").open(newline="", encoding="utf-8-sig") as f:
        leitor = csv.DictReader(f)
        if leitor.fieldnames != CAMPOS:
            raise SystemExit("cabeçalho de triagem.csv inesperado")
        return list(leitor)


def arquivo_de(base: Path, linha: dict) -> Path | None:
    return next((base / linha["palavra"]).glob(f'*{linha["id"]}*.mp4'), None)


def deduplicar(base: Path, linhas: list[dict]) -> tuple[list, dict]:
    sig = {}
    for linha in linhas:
        caminho = arquivo_de(base, linha)
        if caminho:
            sig[(linha["palavra"], linha["id"])] = (assinaturas(caminho), linha)

    pares = []
    for (k1, (s1, r1)), (k2, (s2, r2)) in itertools.combinations(sig.items(), 2):
        d_abs = distancia(s1[0], s2[0])
        if d_abs >= LIMIAR_ABSOLUTO:
            continue
        d_mov = distancia(s1[1], s2[1])
        duplicata = d_mov < LIMIAR_MOVIMENTO
        pares.append({"distancia_absoluta": round(d_abs, 1),
                      "distancia_movimento": round(d_mov, 1),
                      "classificacao": "duplicata" if duplicata else "mesmo_template",
                      "palavra_a": k1[0], "id_a": k1[1], "canal_a": r1["canal"],
                      "palavra_b": k2[0], "id_b": k2[1], "canal_b": r2["canal"]})

    # Só duplicata real funde grupos; template compartilhado não é conteúdo repetido.
    pai = {k: k for k in sig}

    def raiz(x):
        while pai[x] != x:
            pai[x] = pai[pai[x]]
            x = pai[x]
        return x

    for p in pares:
        if p["classificacao"] == "duplicata":
            pai[raiz((p["palavra_a"], p["id_a"]))] = raiz((p["palavra_b"], p["id_b"]))
    grupos = {k: ":".join(raiz(k)) for k in sig}
    return sorted(pares, key=lambda p: p["distancia_movimento"]), grupos


def tira(video: Path, n: int = 4, larg: int = 300) -> np.ndarray | None:
    cap = cv2.VideoCapture(str(video))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    quadros = []
    for i in range(n):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(total * (i + 0.5) / n))
        ok, f = cap.read()
        if ok:
            alt = int(f.shape[0] * larg / f.shape[1])
            quadros.append(cv2.resize(f, (larg, alt)))
    cap.release()
    if not quadros:
        return None
    alt = min(q.shape[0] for q in quadros)
    return np.hstack([q[:alt] for q in quadros])


def rotular(faixa: np.ndarray, texto: str) -> np.ndarray:
    barra = np.full((26, faixa.shape[1], 3), 24, np.uint8)
    cv2.putText(barra, texto, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                (235, 235, 235), 1, cv2.LINE_AA)
    return np.vstack([barra, faixa])


def comparar(base: dict, candidatos: list[dict], saida: Path) -> dict:
    """Uma imagem por candidato: ele em cima, as referências embaixo."""
    saida.mkdir(parents=True, exist_ok=True)
    out = {}
    for linha in candidatos:
        palavra, ident = linha["palavra"], linha["id"]
        alvo = arquivo_de(base, linha)
        if alvo is None:
            continue
        faixas, fontes = [], []
        f = tira(alvo)
        if f is None:
            continue
        faixas.append(rotular(f, f'CANDIDATO  {palavra}  {ident}  [{linha["canal"]}]'))
        for pasta, tag in REFERENCIAS:
            ref = next(CORPORA.glob(f"{pasta}/*_sinal-{palavra}_rep*.mp4"), None)
            if ref is None:
                continue
            fr = tira(ref)
            if fr is None:
                continue
            faixas.append(rotular(fr, f"REFERENCIA {tag}  {ref.name}"))
            fontes.append({"corpus": tag, "arquivo": ref.name})
        largura = max(x.shape[1] for x in faixas)
        img = np.vstack([np.pad(x, ((0, 0), (0, largura - x.shape[1]), (0, 0)))
                         for x in faixas])
        nome = f"{palavra}-{ident}.jpg"
        cv2.imwrite(str(saida / nome), img, [cv2.IMWRITE_JPEG_QUALITY, 72])
        out[f"{palavra}:{ident}"] = {
            "imagem": f"referencia/{nome}", "referencias": fontes,
            # Sem referência não há o que comparar; com referência, quem decide é
            # o consultor. O script nunca escreve "bate" — ele só junta as provas.
            "convergencia": "pendente" if fontes else "sem_referencia"}
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", type=Path, default=BASE_PADRAO)
    ap.add_argument("--sem-imagens", action="store_true")
    args = ap.parse_args(argv)
    base = args.base.resolve()
    linhas = ler_triagem(base)

    pares, grupos = deduplicar(base, linhas)
    with (base / "duplicatas.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(pares[0]) if pares else
                           ["distancia_absoluta", "distancia_movimento", "classificacao",
                            "palavra_a", "id_a", "canal_a", "palavra_b", "id_b", "canal_b"])
        w.writeheader()
        w.writerows(pares)

    dups = [p for p in pares if p["classificacao"] == "duplicata"]
    candidatos = [l for l in linhas if l["veredito"] == "candidato"]
    distintos = len({grupos.get((l["palavra"], l["id"]), l["id"]) for l in candidatos})

    ref = {}
    if not args.sem_imagens:
        ref = comparar(base, candidatos, base / "referencia")
    (base / "referencia.json").write_text(json.dumps(
        {"schema_version": 1, "grupos_conteudo": {f"{k[0]}:{k[1]}": v for k, v in grupos.items()},
         "duplicatas": dups, "por_candidato": ref}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")

    sem_ref = [k for k, v in ref.items() if v["convergencia"] == "sem_referencia"]
    print(json.dumps({"clipes": len(linhas), "pares_suspeitos": len(pares),
                      "duplicatas_reais": len(dups),
                      "mesmo_template_nao_duplicata": len(pares) - len(dups),
                      "candidatos": len(candidatos),
                      "candidatos_conteudo_distinto": distintos,
                      "comparacoes_geradas": len(ref),
                      "candidatos_sem_referencia": sem_ref}, ensure_ascii=False, indent=2))
    print("Convergência fica 'pendente': o script junta prova, não aprova sinal.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

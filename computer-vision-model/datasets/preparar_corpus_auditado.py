"""Copia corpus sem grupos duplicados, preservando integralmente a entrada.

Não escolhe rótulo ou pessoa "corretos" entre vídeos iguais. Exclui TODOS os
membros dos grupos e registra a decisão. A auditoria oficial de pré-treino
continua obrigatória: esta preparação não é uma autorização para treinar.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
import shutil
import tempfile

import proveniencia as pv


def planejar(entrada: Path) -> dict:
    arquivos = sorted(entrada.glob("*.npy"))
    if not arquivos:
        raise ValueError("corpus de entrada vazio")
    registros = [pv.ler(p) for p in arquivos]
    if any(r["fonte"] != "vlibrasil" for r in registros):
        raise ValueError("preparação exclusiva para V-LIBRASIL")
    grupos = []
    excluidos: set[str] = set()
    for criterio in ("origem", "video_sha256", "landmarks_sha256"):
        por_chave = defaultdict(list)
        for r in registros:
            chave = (r["fonte"], r["origem"]) if criterio == "origem" else (
                r[criterio.removesuffix("_sha256")]["sha256"])
            por_chave[chave].append(r["landmarks"]["arquivo"])
        for chave, nomes in por_chave.items():
            if len(nomes) > 1:
                grupos.append({"criterio": criterio, "chave": chave, "arquivos": nomes})
                excluidos.update(nomes)
    inventario = [{"arquivo": r["landmarks"]["arquivo"],
                   "landmarks_sha256": r["landmarks"]["sha256"],
                   "video_sha256": r["video"]["sha256"],
                   "origem": r["origem"], "pessoa": r["pessoa"], "sinal": r["sinal"],
                   "registro_sha256": pv.hash_json(r)} for r in registros]
    return {
        "schema": 1,
        "politica": "excluir_todos_os_membros_de_grupos_duplicados_sem_relabeling",
        "entrada_inventario_sha256": pv.hash_json(inventario),
        "inventario": inventario,
        "grupos_duplicados": grupos,
        "excluidos": sorted(excluidos),
        "mantidos": [p.name for p in arquivos if p.name not in excluidos],
    }


def preparar(entrada: Path, saida: Path, plano: dict) -> None:
    entrada, saida = entrada.resolve(), saida.absolute()
    if saida.exists() or saida.is_symlink():
        raise ValueError("destino já existe; nenhum arquivo será sobrescrito")
    if saida.resolve().is_relative_to(entrada):
        raise ValueError("destino não pode ficar dentro do corpus original")
    if not plano["mantidos"]:
        raise ValueError("nenhuma amostra restou após excluir grupos duplicados")
    if planejar(entrada) != plano:
        raise ValueError("entrada mudou desde o planejamento")
    saida.parent.mkdir(parents=True, exist_ok=True)
    por_nome = {r["arquivo"]: r for r in plano["inventario"]}
    with tempfile.TemporaryDirectory(dir=saida.parent, prefix=".corpus-auditado-") as tmp:
        staging = Path(tmp) / "corpus"
        staging.mkdir()
        for nome in plano["mantidos"]:
            origem, destino = entrada / nome, staging / nome
            shutil.copyfile(origem, destino)
            shutil.copyfile(pv.sidecar(origem), pv.sidecar(destino))
            r = pv.ler(destino)
            if pv.hash_json(r) != por_nome[nome]["registro_sha256"]:
                raise ValueError(f"registro mudou durante a cópia: {nome}")
        pv.escrever(staging / "preparacao.json", plano)
        staging.rename(saida)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--entrada", required=True, type=Path)
    ap.add_argument("--saida", type=Path)
    ap.add_argument("--aplicar", action="store_true", help="copia para destino novo; sem isso só planeja")
    args = ap.parse_args()
    if args.aplicar and args.saida is None:
        ap.error("--aplicar exige --saida")
    plano = planejar(args.entrada)
    print(f"[preparação] {len(plano['inventario'])} entradas; "
          f"{len(plano['grupos_duplicados'])} grupos duplicados; "
          f"{len(plano['excluidos'])} excluídas; {len(plano['mantidos'])} mantidas")
    if args.aplicar:
        preparar(args.entrada, args.saida, plano)
        print(f"[preparação] cópia em {args.saida}; origem intacta. Execute pretreinar.py --auditar.")


if __name__ == "__main__":
    main()
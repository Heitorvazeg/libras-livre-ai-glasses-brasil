"""Baixa um subconjunto do WLASL (língua de sinais americana) para pré-treino.

POR QUE UMA BASE DE OUTRA LÍNGUA. Não é pelo vocabulário — nenhum sinal do WLASL
vai para o produto. É por duas propriedades que as nossas bases de Libras não
têm:

  1. **119 sinalizantes diferentes** (contra 8 do MINDS e 3 da V-LIBRASIL, onde
     os 3 são sempre os mesmos em todas as palavras);
  2. **gravação em condições variadas** — os vídeos vêm do YouTube, com ângulos
     de câmera, iluminação e fundos diferentes. As nossas bases são estúdio
     frontal, e é justamente a variação de ponto de vista que falta para o
     cenário de balcão (ver docs/decisao-arquitetura-modelo.md §4).

A aposta é que configurações de mão e primitivas de movimento transferem entre
línguas de sinais, mesmo com léxicos distintos. É aposta: precisa ser medida
contra o pré-treino na V-LIBRASIL, não presumida melhor.

METADE DOS VÍDEOS NÃO EXISTE. O WLASL é distribuído como links do YouTube e
9.103 já morreram; o mirror do Kaggle tem 11.980 dos ~21 mil. No WLASL100, dos
2.038 vídeos do split, 1.013 sobrevivem (50%). O script conta o que realmente
existe antes de baixar — número inflado aqui vira expectativa errada depois.

Uso:
    python ingest_wlasl.py --listar              # cobertura dos subconjuntos
    python ingest_wlasl.py --subset 100          # WLASL100 (~1.013 clipes, 0,5 GB)
    python ingest_wlasl.py --subset 300
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sys
import tempfile
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path

from remote_zip import ZipRemoto, zip_do_kaggle

AQUI = Path(__file__).resolve().parent
DESTINO = AQUI.parent / "PoC" / "data" / "raw-wlasl"
KAGGLE = "risangbaskoro/wlasl-processed"
PREFIXO_PESSOA = "W"  # distingue dos M (MINDS) e V (V-LIBRASIL)


@dataclass
class Clipe:
    sinal: str
    pessoa: str
    rep: int
    origem: str
    bytes: int

    @property
    def destino(self) -> str:
        return f"pessoa{self.pessoa}_sinal-{self.sinal}_rep{self.rep:02d}.mp4"


def slug(palavra: str) -> str:
    s = unicodedata.normalize("NFKD", palavra).encode("ascii", "ignore").decode()
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s).strip("-").lower()
    return re.sub(r"-{2,}", "-", s)


def _ler_json(z: ZipRemoto, nome: str, destino: Path) -> object:
    alvo = [n for n in z.membros if n.endswith(nome)]
    if not alvo:
        raise SystemExit(f"{nome} não encontrado no bundle {KAGGLE}")
    z.extrair(alvo[0], destino / nome)
    return json.loads((destino / nome).read_text(encoding="utf-8"))


def coletar(z: ZipRemoto, subset: int | None, tmp: Path) -> list[Clipe]:
    anotacoes = _ler_json(z, "WLASL_v0.3.json", tmp)
    por_video = {n.rsplit("/", 1)[-1].removesuffix(".mp4"): (n, m)
                 for n, m in z.membros.items() if n.endswith(".mp4")}

    ids_permitidos: set[str] | None = None
    if subset:
        nslt = _ler_json(z, f"nslt_{subset}.json", tmp)
        ids_permitidos = set(nslt)

    contador: collections.Counter = collections.Counter()
    clipes: list[Clipe] = []
    for entrada in anotacoes:
        rotulo = slug(entrada["gloss"])
        for inst in entrada["instances"]:
            vid = str(inst["video_id"])
            if ids_permitidos is not None and vid not in ids_permitidos:
                continue
            achado = por_video.get(vid)
            if achado is None:
                continue  # link morto: o vídeo não está no mirror
            pessoa = f"{PREFIXO_PESSOA}{int(inst['signer_id']):03d}"
            contador[(pessoa, rotulo)] += 1
            nome, membro = achado
            clipes.append(Clipe(sinal=rotulo, pessoa=pessoa, rep=contador[(pessoa, rotulo)],
                                origem=nome, bytes=membro.tamanho))
    return sorted(clipes, key=lambda c: (c.sinal, c.pessoa, c.rep))


def resumo(clipes: list[Clipe], rotulo: str) -> str:
    if not clipes:
        return f"{rotulo}: nenhum clipe disponível"
    pessoas = {c.pessoa for c in clipes}
    sinais = {c.sinal for c in clipes}
    por_sinal = collections.Counter(c.sinal for c in clipes)
    tam = sum(c.bytes for c in clipes) / 1e9
    valores = sorted(por_sinal.values())
    return (f"{rotulo}: {len(clipes)} clipes | {len(sinais)} classes | "
            f"{len(pessoas)} sinalizantes | {tam:.2f} GB | "
            f"clipes por classe: min={valores[0]} mediana={valores[len(valores)//2]} "
            f"max={valores[-1]}")


def baixar(clipes: list[Clipe], z: ZipRemoto, destino: Path) -> int:
    destino.mkdir(parents=True, exist_ok=True)
    pendentes = [c for c in clipes if not (destino / c.destino).exists()]
    if ja := len(clipes) - len(pendentes):
        print(f"[wlasl] {ja} clipe(s) já em disco — pulando")
    if not pendentes:
        print("[wlasl] nada a baixar.")
        return 0
    total = sum(c.bytes for c in pendentes)
    print(f"[wlasl] baixando {len(pendentes)} clipe(s), {total / 1e9:.2f} GB -> {destino}",
          flush=True)
    inicio, feitos = time.time(), 0
    for i, c in enumerate(pendentes, 1):
        z.extrair(c.origem, destino / c.destino)
        feitos += c.bytes
        if i % 50 == 0 or i == len(pendentes):
            passado = time.time() - inicio
            resta = (total - feitos) / (feitos / passado) / 60 if feitos else 0
            print(f"[wlasl] {i:>5}/{len(pendentes)}  {feitos / passado / 1e6:.1f} MB/s  "
                  f"faltam ~{resta:.0f} min", flush=True)
    return len(pendentes)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--subset", type=int, choices=[100, 300, 1000, 2000],
                    help="subconjunto canônico do WLASL (padrão: todos os disponíveis)")
    ap.add_argument("--listar", action="store_true", help="mostra a cobertura sem baixar")
    args = ap.parse_args()

    print(f"[wlasl] lendo o índice de {KAGGLE} (só o rodapé do zip)...", flush=True)
    z = zip_do_kaggle(KAGGLE, cache_dir=AQUI / ".cache")

    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        if args.listar:
            for s in (100, 300, 1000, 2000):
                print(resumo(coletar(z, s, tmp), f"WLASL{s}"))
            print(resumo(coletar(z, None, tmp), "todos disponíveis"))
            return
        clipes = coletar(z, args.subset, tmp)
        print(resumo(clipes, f"WLASL{args.subset or ' (completo disponível)'}"))
        n = baixar(clipes, z, DESTINO)

    print(f"[wlasl] concluído: {n} clipe(s) em {DESTINO}")
    print("[wlasl] próximo passo (depois que a V-LIBRASIL terminar de extrair):")
    print("[wlasl]   cd ../PoC && python src/extract.py "
          "--entrada data/raw-wlasl --saida data/landmarks-wlasl")


if __name__ == "__main__":
    sys.exit(main())

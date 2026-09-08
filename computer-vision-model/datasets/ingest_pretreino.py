"""Baixa a V-LIBRASIL INTEIRA — corpus de pré-treino, não vocabulário de produto.

POR QUE UM SCRIPT SEPARADO DO ingest.py. Aquele monta o dataset de avaliação: um
vocabulário curado, pareado entre duas bases, com rótulo conferido. Este faz o
oposto — traz as 1.363 palavras da V-LIBRASIL sem seleção nenhuma, porque o
objetivo aqui não é medir acurácia, é dar ao modelo variedade suficiente para
aprender "como um corpo se move sinalizando em Libras" antes de especializar.

O QUE ESTE CORPUS NÃO É. A V-LIBRASIL tem sempre os MESMOS 3 articuladores em
todas as palavras (docs/vocabulario-mvp-proposta.md) — não são 3 pessoas novas
por palavra, é o mesmo trio repetido 1.363 vezes. Isso o desqualifica como
conjunto de avaliação signer-independent, e é justamente por isso que ele fica
num diretório separado: se estes clipes entrarem no data/landmarks/ da avaliação,
o número da LOSO deixa de significar o que diz significar.

Saída: data/raw-pretreino/, na mesma convenção de nome do resto do pipeline
(pessoaVNN_sinal-<slug>_rep01.mp4), para que extract.py funcione sem mudança.

Uso:
    python ingest_pretreino.py --listar        # cobertura e tamanho, sem baixar
    python ingest_pretreino.py --limite 20     # amostra, para testar o caminho
    python ingest_pretreino.py                 # tudo (~4.088 clipes, ~11 GB)
"""
from __future__ import annotations

import argparse
import re
import sys
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path

from remote_zip import ZipRemoto, zip_do_kaggle

AQUI = Path(__file__).resolve().parent
DESTINO = AQUI.parent / "PoC" / "data" / "raw-pretreino"
KAGGLE = "davimedio01/v-librasil"
PREFIXO_PESSOA = "V"

_RE_MEMBRO = re.compile(r"^.*/data/(?P<palavra>.+)_Articulador(?P<pessoa>\d+)\.mp4$")


@dataclass
class Clipe:
    sinal: str
    pessoa: str
    origem: str
    bytes: int

    @property
    def destino(self) -> str:
        return f"pessoa{self.pessoa}_sinal-{self.sinal}_rep01.mp4"


def slug(palavra: str) -> str:
    """'Maçã (rosto)' -> 'maca-rosto'.

    O nome do arquivo é a fonte de verdade do rótulo em todo o pipeline, e o
    parser dele espera `pessoaXX_sinal-YYY_repNN`. Acentos, espaços e parênteses
    viram hífen para que a ida e a volta sejam sempre a mesma string — sem isso,
    duas grafias da mesma palavra viram duas classes diferentes em silêncio.
    """
    s = unicodedata.normalize("NFKD", palavra).encode("ascii", "ignore").decode()
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s).strip("-").lower()
    return re.sub(r"-{2,}", "-", s)


def coletar(z: ZipRemoto) -> list[Clipe]:
    clipes: list[Clipe] = []
    vistos: dict[str, str] = {}
    for nome, membro in z.membros.items():
        m = _RE_MEMBRO.match(nome)
        if not m:
            continue
        rotulo = slug(m["palavra"])
        if not rotulo:
            continue
        anterior = vistos.setdefault(rotulo, m["palavra"])
        if anterior != m["palavra"]:
            print(f"[pretreino] ⚠ '{m['palavra']}' e '{anterior}' geram o mesmo rótulo "
                  f"'{rotulo}' — serão tratados como a MESMA classe")
        clipes.append(Clipe(sinal=rotulo, pessoa=f"{PREFIXO_PESSOA}{int(m['pessoa']):02d}",
                            origem=nome, bytes=membro.tamanho))
    return sorted(clipes, key=lambda c: (c.sinal, c.pessoa))


def resumo(clipes: list[Clipe]) -> str:
    sinais = {c.sinal for c in clipes}
    pessoas = sorted({c.pessoa for c in clipes})
    por_sinal: dict[str, int] = {}
    for c in clipes:
        por_sinal[c.sinal] = por_sinal.get(c.sinal, 0) + 1
    distrib: dict[int, int] = {}
    for n in por_sinal.values():
        distrib[n] = distrib.get(n, 0) + 1
    linhas = [f"{len(clipes)} clipes | {len(sinais)} palavras | pessoas: {', '.join(pessoas)}",
              f"tamanho total: {sum(c.bytes for c in clipes) / 1e9:.1f} GB",
              "clipes por palavra: " + ", ".join(f"{k} clipe(s): {v} palavras"
                                                 for k, v in sorted(distrib.items()))]
    return "\n".join(linhas)


def baixar(clipes: list[Clipe], z: ZipRemoto, destino: Path) -> int:
    destino.mkdir(parents=True, exist_ok=True)
    pendentes = [c for c in clipes if not (destino / c.destino).exists()]
    if ja := len(clipes) - len(pendentes):
        print(f"[pretreino] {ja} clipe(s) já em disco — pulando")
    if not pendentes:
        print("[pretreino] nada a baixar.")
        return 0

    total = sum(c.bytes for c in pendentes)
    print(f"[pretreino] baixando {len(pendentes)} clipe(s), {total / 1e9:.2f} GB -> {destino}",
          flush=True)
    inicio, feitos = time.time(), 0
    for i, c in enumerate(pendentes, 1):
        z.extrair(c.origem, destino / c.destino)
        feitos += c.bytes
        if i % 25 == 0 or i == len(pendentes):
            passado = time.time() - inicio
            taxa = feitos / passado / 1e6
            resta = (total - feitos) / (feitos / passado) / 60 if feitos else 0
            print(f"[pretreino] {i:>5}/{len(pendentes)}  {taxa:.1f} MB/s  "
                  f"faltam ~{resta:.0f} min", flush=True)
    return len(pendentes)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--listar", action="store_true", help="mostra a cobertura sem baixar")
    ap.add_argument("--limite", type=int, help="baixa só os N primeiros (teste)")
    ap.add_argument("--sem-cache", action="store_true")
    args = ap.parse_args()

    print(f"[pretreino] lendo o índice de {KAGGLE} (só o rodapé do zip)...", flush=True)
    z = zip_do_kaggle(KAGGLE, cache_dir=None if args.sem_cache else AQUI / ".cache")
    clipes = coletar(z)
    if not clipes:
        raise SystemExit("nenhum clipe reconhecido no bundle — o layout do zip mudou?")
    print(resumo(clipes))

    if args.listar:
        return
    if args.limite:
        clipes = clipes[: args.limite]
        print(f"[pretreino] --limite: só os {len(clipes)} primeiros")

    n = baixar(clipes, z, DESTINO)
    print(f"[pretreino] concluído: {n} clipe(s) em {DESTINO}")
    print("[pretreino] próximo passo: extrair os landmarks deste corpus com")
    print("[pretreino]   cd ../PoC && python src/extract.py "
          "--entrada data/raw-pretreino --saida data/landmarks-pretreino")


if __name__ == "__main__":
    sys.exit(main())

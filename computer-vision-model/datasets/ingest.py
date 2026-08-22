"""Traz para o repositório os clipes das bases públicas escolhidos em selecao.yaml.

O que este script faz, em uma frase: lê a seleção de sinais, encontra os vídeos
correspondentes dentro dos .zip do Kaggle **sem baixar os bundles inteiros**
(remote_zip.py) e grava cada clipe em ../PoC/data/raw já com o nome que o
pipeline da PoC espera:

    01AcontecerSinalizador05-3.mp4              -> pessoaM05_sinal-acontecer_rep03.mp4
    .../data/Ruim_Articulador2.mp4              -> pessoaV02_sinal-ruim_rep01.mp4

O prefixo da pessoa (M/V) mantém a origem visível e evita que o sinalizador 02
da MINDS e o articulador 02 da V-LIBRASIL sejam lidos como a mesma pessoa — o
que arruinaria a avaliação leave-one-signer-out.

Os vídeos NÃO são versionados (ver README §5): o que fica no git é a seleção
(selecao.yaml), este script e o manifesto (manifest.csv). Quem clonar o repo
reproduz o dataset rodando `python ingest.py`.

Ordem de download: repetição a repetição (todas as pessoas e sinais na rep 1,
depois rep 2...). Assim, uma execução interrompida no meio deixa um dataset
BALANCEADO em vez de completo em alguns sinais e vazio em outros.

Uso:
    python ingest.py --listar          # cobertura e tamanho, sem baixar nada
    python ingest.py --reps 1          # 1 repetição por pessoa/sinal (~5 GB)
    python ingest.py                   # seleção completa (~24 GB)
    python ingest.py --fonte vlibrasil # só a V-LIBRASIL (~80 MB, bom p/ testar)
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from remote_zip import ZipRemoto, zip_do_kaggle  # noqa: E402

AQUI = Path(__file__).resolve().parent
SELECAO = AQUI / "selecao.yaml"
MANIFESTO = AQUI / "manifest.csv"
CONFIG_POC = AQUI.parent / "PoC" / "config.yaml"

# 01AcontecerSinalizador05-3.mp4 -> ordem 01, palavra Acontecer, pessoa 05, rep 3
_RE_MINDS = re.compile(r"^(?P<ordem>\d{2})(?P<palavra>[A-Za-z]+)Sinalizador(?P<pessoa>\d+)-(?P<rep>\d+)\.mp4$")
# videos UFPE (V-LIBRASIL)/data/Ruim_Articulador2.mp4
_RE_VLIBRASIL = re.compile(r"^.*/data/(?P<palavra>.+)_Articulador(?P<pessoa>\d+)\.mp4$")


@dataclass(frozen=True)
class Clipe:
    """Um vídeo selecionado: de onde vem e com que nome entra no dataset."""

    sinal: str
    fonte: str
    pessoa: str      # já com prefixo da fonte, ex.: M05, V02
    rep: int
    origem: str      # caminho dentro do zip
    bytes: int
    validado: bool

    @property
    def destino(self) -> str:
        return f"pessoa{self.pessoa}_sinal-{self.sinal}_rep{self.rep:02d}.mp4"


def carregar_selecao(caminho: Path = SELECAO) -> dict:
    with open(caminho, "r", encoding="utf-8") as fh:
        sel = yaml.safe_load(fh)
    if not sel.get("sinais"):
        raise SystemExit(f"{caminho.name}: nenhum sinal selecionado.")
    return sel


def conferir_vocabulario(sel: dict) -> None:
    """Avisa se o vocabulário da PoC não bate com a seleção — são a mesma lista."""
    if not CONFIG_POC.exists():
        return
    with open(CONFIG_POC, "r", encoding="utf-8") as fh:
        vocab = set(yaml.safe_load(fh).get("vocabulario") or [])
    selecionados = {s["sinal"] for s in sel["sinais"]}
    if faltando := selecionados - vocab:
        print(f"[ingest] ⚠ sinais da seleção fora do vocabulário da PoC: {sorted(faltando)}")
    if sobrando := vocab - selecionados:
        print(f"[ingest] ⚠ sinais no vocabulário da PoC sem clipes nesta seleção: {sorted(sobrando)}")


def _clipes_minds(zip_remoto: ZipRemoto, sel: dict) -> list[Clipe]:
    prefixo = sel["fontes"]["minds"].get("prefixo_pessoa", "M")
    por_palavra = {s["minds"]: s for s in sel["sinais"] if s.get("minds")}
    clipes: list[Clipe] = []
    for nome, membro in zip_remoto.membros.items():
        m = _RE_MINDS.match(nome)
        if not m or m["palavra"] not in por_palavra:
            continue
        s = por_palavra[m["palavra"]]
        clipes.append(Clipe(sinal=s["sinal"], fonte="minds",
                            pessoa=f"{prefixo}{int(m['pessoa']):02d}", rep=int(m["rep"]),
                            origem=nome, bytes=membro.tamanho,
                            validado=bool(s.get("validado", False))))
    return clipes


def _clipes_vlibrasil(zip_remoto: ZipRemoto, sel: dict) -> list[Clipe]:
    prefixo = sel["fontes"]["vlibrasil"].get("prefixo_pessoa", "V")
    por_palavra = {s["vlibrasil"]: s for s in sel["sinais"] if s.get("vlibrasil")}
    clipes: list[Clipe] = []
    for nome, membro in zip_remoto.membros.items():
        m = _RE_VLIBRASIL.match(nome)
        if not m or m["palavra"] not in por_palavra:
            continue
        s = por_palavra[m["palavra"]]
        # A V-LIBRASIL tem uma execução por articulador: é sempre a repetição 01.
        clipes.append(Clipe(sinal=s["sinal"], fonte="vlibrasil",
                            pessoa=f"{prefixo}{int(m['pessoa']):02d}", rep=1,
                            origem=nome, bytes=membro.tamanho,
                            validado=bool(s.get("validado", False))))
    return clipes


_COLETORES = {"minds": _clipes_minds, "vlibrasil": _clipes_vlibrasil}


def resolver(sel: dict, fontes: list[str], cache: bool = True) -> tuple[list[Clipe], dict[str, ZipRemoto]]:
    """Abre os zips remotos e devolve os clipes selecionados de cada fonte."""
    clipes: list[Clipe] = []
    zips: dict[str, ZipRemoto] = {}
    for fonte in fontes:
        slug = sel["fontes"][fonte]["kaggle"]
        print(f"[ingest] lendo o índice de {slug} (só o rodapé do zip)...", flush=True)
        z = zip_do_kaggle(slug, cache_dir=AQUI / ".cache" if cache else None)
        zips[fonte] = z
        achados = _COLETORES[fonte](z, sel)
        print(f"[ingest] {fonte}: {len(achados)} clipe(s) selecionado(s) "
              f"de {len(z)} no bundle ({sum(c.bytes for c in achados) / 1e9:.1f} GB)")
        clipes += achados

    esperado = {s["sinal"] for s in sel["sinais"]}
    if vazios := esperado - {c.sinal for c in clipes}:
        print(f"[ingest] ⚠ sem nenhum clipe encontrado para: {sorted(vazios)} "
              "— confira os rótulos em selecao.yaml")
    return clipes, zips


def filtrar(clipes: list[Clipe], sinais: set[str] | None, reps: int | None,
            somente_validados: bool) -> list[Clipe]:
    if sinais:
        clipes = [c for c in clipes if c.sinal in sinais]
    if reps:
        clipes = [c for c in clipes if c.rep <= reps]
    if somente_validados:
        clipes = [c for c in clipes if c.validado]
    # Repetição primeiro: uma execução interrompida deixa o dataset balanceado.
    return sorted(clipes, key=lambda c: (c.rep, c.sinal, c.fonte, c.pessoa))


def tabela_cobertura(clipes: list[Clipe]) -> str:
    """Cobertura por sinal: quantas pessoas de cada base, quantos clipes, quantos GB."""
    sinais = sorted({c.sinal for c in clipes})
    linhas = [f"{'sinal':<12} {'pessoas':>7} {'minds':>6} {'vlib':>5} {'clipes':>7} {'GB':>6}",
              "-" * 48]
    for s in sinais:
        do_sinal = [c for c in clipes if c.sinal == s]
        pessoas = {c.pessoa for c in do_sinal}
        n_m = len({c.pessoa for c in do_sinal if c.fonte == "minds"})
        n_v = len({c.pessoa for c in do_sinal if c.fonte == "vlibrasil"})
        linhas.append(f"{s:<12} {len(pessoas):>7} {n_m:>6} {n_v:>5} {len(do_sinal):>7} "
                      f"{sum(c.bytes for c in do_sinal) / 1e9:>6.2f}")
    linhas += ["-" * 48,
               f"{'TOTAL':<12} {len({c.pessoa for c in clipes}):>7} "
               f"{'':>6} {'':>5} {len(clipes):>7} {sum(c.bytes for c in clipes) / 1e9:>6.2f}"]
    return "\n".join(linhas)


def escrever_manifesto(clipes: list[Clipe], destino: Path, caminho: Path = MANIFESTO) -> None:
    """Registra a seleção inteira e o que já está em disco — este arquivo VAI para o git."""
    with open(caminho, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["arquivo", "sinal", "pessoa", "rep", "fonte", "origem", "bytes",
                    "validado", "estado"])
        for c in sorted(clipes, key=lambda c: (c.sinal, c.pessoa, c.rep)):
            baixado = (destino / c.destino).exists()
            w.writerow([c.destino, c.sinal, c.pessoa, f"{c.rep:02d}", c.fonte, c.origem,
                        c.bytes, "sim" if c.validado else "conferir",
                        "baixado" if baixado else "pendente"])
    try:
        mostrar = caminho.relative_to(AQUI.parent.parent)
    except ValueError:  # manifesto fora do repositório (--destino, testes)
        mostrar = caminho
    print(f"[ingest] manifesto atualizado: {mostrar}")


def baixar(clipes: list[Clipe], zips: dict[str, ZipRemoto], destino: Path) -> int:
    pendentes = [c for c in clipes if not (destino / c.destino).exists()]
    ja_tem = len(clipes) - len(pendentes)
    if ja_tem:
        print(f"[ingest] {ja_tem} clipe(s) já em disco — pulando")
    if not pendentes:
        print("[ingest] nada a baixar; dataset completo para esta seleção.")
        return 0

    total_bytes = sum(c.bytes for c in pendentes)
    print(f"[ingest] baixando {len(pendentes)} clipe(s), {total_bytes / 1e9:.2f} GB "
          f"-> {destino}", flush=True)
    inicio = time.time()
    feitos_bytes = 0
    for i, c in enumerate(pendentes, 1):
        zips[c.fonte].extrair(c.origem, destino / c.destino)
        feitos_bytes += c.bytes
        decorrido = time.time() - inicio
        taxa = feitos_bytes / decorrido if decorrido else 0
        restante = (total_bytes - feitos_bytes) / taxa if taxa else 0
        print(f"[ingest] {i:>4}/{len(pendentes)}  {c.destino:<40} "
              f"{c.bytes / 1e6:>6.1f} MB  |  {taxa / 1e6:.1f} MB/s  "
              f"faltam ~{restante / 60:.0f} min", flush=True)
    print(f"[ingest] concluído em {(time.time() - inicio) / 60:.1f} min")
    return len(pendentes)


def main() -> None:
    ap = argparse.ArgumentParser(description="Ingestão dos clipes públicos selecionados.")
    ap.add_argument("--listar", action="store_true",
                    help="mostra a cobertura da seleção e sai, sem baixar nada")
    ap.add_argument("--fonte", default="todas", choices=["todas", "minds", "vlibrasil"],
                    help="limita a ingestão a uma das bases (default: todas)")
    ap.add_argument("--sinais", help="subconjunto de sinais, separados por vírgula")
    ap.add_argument("--reps", type=int,
                    help="máximo de repetições por pessoa/sinal (default: todas)")
    ap.add_argument("--somente-validados", action="store_true",
                    help="ignora os sinais marcados validado: false em selecao.yaml")
    ap.add_argument("--destino", type=Path, help="sobrescreve o destino de selecao.yaml")
    ap.add_argument("--simular", action="store_true",
                    help="resolve e escreve o manifesto, mas não baixa vídeo nenhum")
    ap.add_argument("--sem-cache", action="store_true",
                    help="reconsulta o índice dos zips em vez de usar .cache/")
    args = ap.parse_args()

    sel = carregar_selecao()
    conferir_vocabulario(sel)
    destino = (args.destino or (AQUI / sel["destino"])).resolve()

    fontes = list(sel["fontes"]) if args.fonte == "todas" else [args.fonte]
    clipes, zips = resolver(sel, fontes, cache=not args.sem_cache)
    sinais = {s.strip() for s in args.sinais.split(",")} if args.sinais else None
    if sinais and (desconhecidos := sinais - {c.sinal for c in clipes}):
        raise SystemExit(f"--sinais: rótulo(s) fora da seleção: {sorted(desconhecidos)}")
    clipes = filtrar(clipes, sinais, args.reps, args.somente_validados)
    if not clipes:
        raise SystemExit("[ingest] nenhum clipe após os filtros — nada a fazer.")

    print()
    print(tabela_cobertura(clipes))
    print()

    if args.listar:
        return
    destino.mkdir(parents=True, exist_ok=True)
    # Escrito antes e depois: um download de horas não deve deixar o repositório
    # sem o registro do que foi selecionado caso seja interrompido no meio.
    escrever_manifesto(clipes, destino)
    if not args.simular:
        baixar(clipes, zips, destino)
        escrever_manifesto(clipes, destino)
    if args.simular:
        print("[ingest] --simular: nenhum vídeo baixado.")
    else:
        print("[ingest] próximo passo: cd ../PoC && python src/extract.py")


if __name__ == "__main__":
    main()

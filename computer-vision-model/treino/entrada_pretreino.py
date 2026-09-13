"""Entrada Kaggle privada: tar/pastas, validação, deduplicação e retomada.

Não altera Input. Exclui TODOS os membros de grupos duplicados do pré-treino,
sem escolher rótulos/pessoas. A auditoria de isolamento continua obrigatória.
"""
from __future__ import annotations

import json
import shutil
import sys
import tarfile
import tempfile
from collections import defaultdict
from pathlib import Path, PurePosixPath

import numpy as np
import entrada_poc

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "datasets"))
import proveniencia as pv

FONTES = {
    "minds": ("landmarks-minds.tar.gz", "landmarks"),
    "vlibrasil": ("landmarks-vlibrasil.tar.gz", "landmarks-pretreino-auditado"),
    "malta": ("landmarks-malta.tar.gz", "landmarks-malta"),
}


def localizar(raiz: Path, fonte: str, explicita: str | Path | None = None) -> Path:
    pacote, pasta = FONTES[fonte]
    if explicita:
        p = Path(explicita).expanduser().absolute()
        if p.is_symlink() or not (p.is_file() or p.is_dir()):
            raise FileNotFoundError(f"ORIGENS[{fonte!r}] inválida: {p}")
        return p
    if not raiz.is_dir():
        raise FileNotFoundError(f"Input ausente: {raiz}. Anexe datasets privados em Add Input.")
    candidatos = sorted(set(raiz.rglob(pacote)) | {
        p for p in raiz.rglob(pasta) if p.is_dir()
    })
    if len(candidatos) != 1:
        raise ValueError(
            f"Esperada uma entrada de {fonte}; encontradas {len(candidatos)}. "
            f"Anexe {pacote} ou a pasta {pasta}, ou defina ORIGENS[{fonte!r}] "
            "explicitamente. Candidatos: " + ", ".join(map(str, candidatos)))
    return candidatos[0]


def _arquivos(pasta: Path, fonte: str, pontos: int) -> list[Path]:
    if fonte == "minds":
        return entrada_poc._validar_pasta(pasta, pontos)
    if pasta.is_symlink() or not pasta.is_dir():
        raise ValueError(f"Pasta regular obrigatória: {pasta}")
    arquivos = sorted(pasta.iterdir())
    for p in arquivos:
        if p.is_symlink() or not p.is_file():
            raise ValueError(f"Somente arquivos regulares: {p}")
        if p.name == ".gitkeep":
            continue
        if p.name == "preparacao.json":
            if not isinstance(json.loads(p.read_text(encoding="utf-8")), dict):
                raise ValueError(f"Relatório de preparação inválido: {p}")
        elif p.name.endswith(".npy.proveniencia.json"):
            if not p.with_name(p.name.removesuffix(".proveniencia.json")).is_file():
                raise ValueError(f"Sidecar sem landmark: {p}")
        elif p.suffix != ".npy":
            raise ValueError(f"Arquivo inesperado: {p}")
    npys = [p for p in arquivos if p.suffix == ".npy"]
    if not npys:
        raise ValueError(f"Corpus vazio: {pasta}")
    for p in npys:
        registro = pv.ler(p)
        if registro["fonte"] != fonte:
            raise ValueError(f"Fonte incorreta: {p.name}; esperado {fonte}")
        arr = np.load(p, mmap_mode="r", allow_pickle=False)
        if (arr.ndim != 3 or arr.shape[1:] != (pontos, 3) or arr.shape[0] < 1
                or not np.issubdtype(arr.dtype, np.floating) or not np.isfinite(arr).all()):
            raise ValueError(f"{p.name}: esperado (T>=1, {pontos}, 3) finito em float")
    return arquivos


def _copiar(origem: Path, staging: Path, fonte: str, pontos: int) -> Path:
    _, pasta = FONTES[fonte]
    destino = staging / pasta
    if origem.is_symlink():
        raise ValueError(f"Entrada simbólica não permitida: {origem}")
    if origem.is_dir():
        arquivos = _arquivos(origem, fonte, pontos)
        destino.mkdir()
        for p in arquivos:
            shutil.copyfile(p, destino / p.name)
    elif origem.is_file():
        if not hasattr(tarfile, "data_filter"):
            raise RuntimeError("Python precisa oferecer tarfile.data_filter")
        with tarfile.open(origem, "r:gz") as tar:
            membros, vistos = tar.getmembers(), set()
            for m in membros:
                p = PurePosixPath(m.name)
                if (p.is_absolute() or ".." in p.parts or not p.parts
                        or p.parts[0] != pasta or p in vistos):
                    raise ValueError(f"Caminho inválido/duplicado: {m.name}")
                vistos.add(p)
                if m.isdir() and len(p.parts) == 1:
                    continue
                if not (m.isfile() and len(p.parts) == 2 and (
                        p.name in (".gitkeep", "preparacao.json")
                        or p.name.endswith((".npy", ".npy.proveniencia.json")))):
                    raise ValueError(f"Membro não permitido: {m.name}")
            tar.extractall(staging, members=membros, filter="data")
    else:
        raise FileNotFoundError(origem)
    _arquivos(destino, fonte, pontos)
    return destino


def inventario(pasta: Path) -> dict[str, str]:
    """Fingerprint dos bytes, incluindo sidecars e relatório anterior."""
    resultado = {}
    if pasta.is_symlink():
        raise ValueError(f"Pasta simbólica não permitida: {pasta}")
    for p in sorted(pasta.iterdir()):
        if p.is_symlink() or not p.is_file():
            raise ValueError(f"Arquivo irregular no corpus: {p}")
        if p.name != ".gitkeep":
            resultado[p.name] = pv.hash_arquivo(p)
    return resultado


def preparar(origens: dict[str, Path], destino: Path, pontos: int = 57,
             validar_minds_completo: bool = True) -> dict:
    """Instala em destino exclusivo; repetir a mesma entrada é idempotente.

    A identidade da entrada e da saída é verificada em toda retomada.
    validar_minds_completo=False é reservado aos testes sintéticos.
    """
    if set(origens) != set(FONTES):
        raise ValueError("Informe minds, vlibrasil e malta separadamente")
    destino = destino.absolute()
    if destino.is_symlink():
        raise ValueError("Destino não pode ser link simbólico")
    for origem in origens.values():
        if destino.resolve().is_relative_to(Path(origem).resolve()):
            raise ValueError("Destino não pode ficar dentro da entrada")
    destino.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destino.parent, prefix=".entrada-") as tmp:
        staging = Path(tmp) / "dados"
        staging.mkdir()
        pastas = {f: _copiar(Path(origens[f]), staging, f, pontos) for f in FONTES}
        entradas = {f: inventario(p) for f, p in pastas.items()}
        minds = sorted(pastas["minds"].glob("*.npy"))
        if validar_minds_completo:
            identidades = [pv.identidade_nome(p) for p in minds]
            pessoas = {p for p, _, _ in identidades}
            sinais = {s for _, s, _ in identidades}
            contagens = defaultdict(int)
            for p, s, _ in identidades:
                contagens[p, s] += 1
            if (len(minds) != 800 or len(pessoas) != 8 or len(sinais) != 20
                    or len(contagens) != 160 or set(contagens.values()) != {5}):
                raise ValueError("Comparação baseline exige MINDS completo: 800 clipes, "
                                 "8 pessoas, 20 sinais, 5 repetições por pessoa/sinal")
        registros = {f"{f}/{p.name}": pv.ler(p)
                     for f in ("vlibrasil", "malta") for p in sorted(pastas[f].glob("*.npy"))}
        grupos, excluidos = [], set()
        for criterio in ("origem", "video", "landmarks"):
            chaves = defaultdict(list)
            for nome, r in registros.items():
                chave = (r["fonte"], r["origem"]) if criterio == "origem" else r[criterio]["sha256"]
                chaves[chave].append(nome)
            for chave, nomes in chaves.items():
                if len(nomes) > 1:
                    grupos.append({"criterio": criterio, "chave": chave, "arquivos": nomes})
                    excluidos.update(nomes)
        for nome in sorted(excluidos):
            fonte, arquivo = nome.split("/", 1)
            p = pastas[fonte] / arquivo
            p.unlink()
            pv.sidecar(p).unlink()
        for fonte in ("vlibrasil", "malta"):
            if not list(pastas[fonte].glob("*.npy")):
                raise ValueError(f"Nenhum clipe de {fonte} restou após excluir duplicatas")
        if not list(pastas["vlibrasil"].glob("pessoaV03_*.npy")):
            raise ValueError("V03 ausente após preparação; validação não pode prosseguir")
        plano = {
            "schema": 1, "politica": "excluir_todos_os_membros_de_grupos_duplicados_sem_relabeling",
            "entradas": entradas, "entrada_sha256": pv.hash_json(entradas),
            "grupos": grupos, "excluidos": sorted(excluidos),
            "saidas": {f: inventario(p) for f, p in pastas.items()},
            "contagens": {f: len(list(p.glob("*.npy"))) for f, p in pastas.items()},
        }
        plano = json.loads(json.dumps(plano))
        registro = destino / "preparacao.json"
        if destino.exists():
            if not registro.is_file() or json.loads(registro.read_text()) != plano:
                raise ValueError("Destino já preenchido por outra entrada/preparação; use outro diretório")
            for f, (_, pasta) in FONTES.items():
                if inventario(destino / pasta) != plano["saidas"][f]:
                    raise ValueError(f"Saída preparada foi alterada: {f}; não retomar")
        else:
            pv.escrever(staging / "preparacao.json", plano)
            staging.rename(destino)
    print("Corpus preparado:", plano["contagens"], "| excluídos:", len(plano["excluidos"]))
    return plano

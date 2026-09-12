"""Entrada MINDS da PoC: arquivo tar.gz ou pasta já extraída pelo Kaggle.

Não reextrai landmarks, não modifica /kaggle/input e não mistura fontes.
Não é o carregador do pré-treino V-LIBRASIL (que exige proveniência própria).
"""
from __future__ import annotations

import json
import re
import shutil
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

import numpy as np

PACOTE = "landmarks-minds.tar.gz"
_NOME = re.compile(r"pessoaM\d+_sinal-.+_rep\d+\.npy")


def localizar_minds(raiz: Path, explicita: str | Path | None = None) -> Path:
    """Procura em qualquer slug/profundidade e recusa escolha ambígua."""
    if explicita:
        p = Path(explicita).expanduser()
        if not (p.is_file() or p.is_dir()):
            raise FileNotFoundError(f"ORIGEM_MINDS não existe: {p}")
        return p
    if not raiz.is_dir():
        raise FileNotFoundError(f"Entrada indisponível: {raiz}. Anexe o dataset em Input / Add Input.")
    encontrados = sorted(set(raiz.rglob(PACOTE)) | {
        p for p in raiz.rglob("landmarks") if p.is_dir()
    })
    if not encontrados:
        entradas = [p.name for p in sorted(raiz.iterdir())[:20]]
        raise FileNotFoundError(
            f"Não achei {PACOTE} nem pasta landmarks em {raiz}. "
            f"Entradas visíveis: {entradas}. Anexe o MINDS em Input / Add Input "
            "ou defina ORIGEM_MINDS com o caminho completo.")
    if len(encontrados) != 1:
        raise ValueError("Mais de uma entrada possível para MINDS; defina ORIGEM_MINDS "
                         "explicitamente, sem juntar datasets:\n" +
                         "\n".join(str(p) for p in encontrados))
    return encontrados[0]


def _validar_pasta(pasta: Path, pontos: int) -> list[Path]:
    if pasta.is_symlink() or not pasta.is_dir():
        raise ValueError(f"Pasta landmarks ausente ou simbólica: {pasta}")
    arquivos = sorted(pasta.iterdir())
    for p in arquivos:
        if p.is_symlink() or not p.is_file():
            raise ValueError(f"Só arquivos regulares são permitidos: {p.name}")
        if p.name == ".gitkeep":
            continue
        if p.name.endswith(".npy.proveniencia.json"):
            alvo = p.with_name(p.name.removesuffix(".proveniencia.json"))
            if not alvo.is_file() or not _NOME.fullmatch(alvo.name):
                raise ValueError(f"Sidecar sem landmark MINDS correspondente: {p.name}")
            meta = json.loads(p.read_text(encoding="utf-8"))
            if not isinstance(meta, dict) or not meta:
                raise ValueError(f"Sidecar JSON inválido: {p.name}")
        elif not _NOME.fullmatch(p.name):
            raise ValueError(f"Arquivo fora da convenção MINDS (pessoaM...): {p.name}")
    npys = [p for p in arquivos if p.suffix == ".npy"]
    if not npys:
        raise ValueError(f"Pasta landmarks vazia: {pasta}")
    for p in npys:
        arr = np.load(p, mmap_mode="r", allow_pickle=False)
        if (arr.ndim != 3 or arr.shape[1:] != (pontos, 3)
                or arr.shape[0] < 1 or not np.issubdtype(arr.dtype, np.floating)
                or not np.isfinite(arr).all()):
            raise ValueError(f"{p.name}: PoC 3D exige (T>=1, {pontos}, 3) finito "
                             f"em float; encontrado {arr.shape}/{arr.dtype}. "
                             "Não é seguro inventar a coordenada z.")
    return arquivos


def preparar_minds(origem: Path, destino: Path, pontos: int = 57) -> list[Path]:
    """Valida em staging e só instala em destino vazio; entrada permanece intacta."""
    if origem.is_symlink():
        raise ValueError(f"Entrada simbólica não permitida: {origem}")
    if destino.is_symlink() or (destino.exists() and (
            not destino.is_dir() or any(p.name != ".gitkeep" or not p.is_file()
                                       or p.is_symlink() for p in destino.iterdir()))):
        raise ValueError(f"Destino já preenchido: {destino}. Use um runtime limpo; "
                         "nenhum dado será sobrescrito ou misturado.")
    destino.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destino.parent) as tmp:
        staging = Path(tmp)
        pasta = staging / "landmarks"
        if origem.is_dir():
            arquivos = _validar_pasta(origem, pontos)
            pasta.mkdir()
            for p in arquivos:
                shutil.copyfile(p, pasta / p.name)
        elif origem.is_file():
            if not hasattr(tarfile, "data_filter"):
                raise RuntimeError("Extração exige tarfile com filter='data', sem fallback inseguro")
            with tarfile.open(origem, "r:gz") as tar:
                membros = tar.getmembers()
                vistos = set()
                for m in membros:
                    p = PurePosixPath(m.name)
                    if (p.is_absolute() or ".." in p.parts or not p.parts
                            or p.parts[0] != "landmarks" or p in vistos):
                        raise ValueError(f"Caminho inválido/duplicado no pacote: {m.name}")
                    vistos.add(p)
                    if m.isdir() and len(p.parts) == 1:
                        continue
                    if not (m.isfile() and len(p.parts) == 2 and (
                            p.name == ".gitkeep" or p.name.endswith(
                                (".npy", ".npy.proveniencia.json")))):
                        raise ValueError(f"Membro não permitido no pacote: {m.name}")
                tar.extractall(staging, members=membros, filter="data")
        else:
            raise FileNotFoundError(f"Entrada MINDS não encontrada: {origem}")
        # Mesmas verificações para o arquivo e para a pasta já descompactada.
        _validar_pasta(pasta, pontos)
        if destino.exists():
            for p in pasta.iterdir():
                if p.name != ".gitkeep":
                    shutil.move(str(p), str(destino / p.name))
        else:
            pasta.rename(destino)
    npys = sorted(destino.glob("*.npy"))
    pessoas = sorted({p.name.split("_")[0] for p in npys})
    print(f"MINDS: {len(npys)} clipes | {pontos} pontos × 3 coordenadas | "
          f"{len(pessoas)} pessoas: {', '.join(pessoas)}")
    return npys
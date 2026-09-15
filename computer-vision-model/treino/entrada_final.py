"""Guarda standalone da entrada MINDS da receita final v1 (NumPy + stdlib).

Não treina, não extrai landmarks de vídeo e não importa o pipeline de treino.
A pasta de origem deve conter diretamente os arquivos; o tar deve conter apenas
``landmarks/`` e seus arquivos imediatos. O destino deve ser novo ou uma preparação
íntegra desta MESMA origem, neste MESMO caminho. Nunca há merge nem reparo implícito.

O manifesto de referência opcional é o dict retornado por ``validar_minds`` ou
um Path para seu JSON. Sua comparação é integral, incluindo sidecars e .gitkeep.
Sem referência confiável, a primeira preparação verifica estrutura e bytes, NÃO
autenticidade, extração Holistic/Tasks, qualidade linguística ou proveniência
legada. Mesmo sidecars presentes não são certificados por este módulo.

Hashes são SHA-256 dos bytes originais. ``corpus_sha256`` resume o JSON canônico
da lista ordenada ``arquivos`` (nome, hash e tamanho de TODOS os arquivos).
O registro local detecta alterações acidentais; não é uma assinatura digital.
Não executar contra entradas/diretórios alterados concorrentemente por terceiros;
não há sandbox nem limites de descompressão/memória para pacotes hostis enormes.
Não há CLI nem opção para reduzir a cobertura exigida em produção.
"""
from __future__ import annotations

from collections import Counter
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import stat
import tarfile
import tempfile
from typing import Iterable, Iterator, BinaryIO

import numpy as np


PESSOAS_MINDS = ("M01", "M02", "M05", "M06", "M08", "M10", "M11", "M12")
# Lista explícita da receita v1, verificada em PoC/config.yaml. O config pode
# futuramente conter outras fontes/classes; não ampliar esta receita por acidente.
ROTULOS_MINDS = (
    "acontecer", "aluno", "amarelo", "america", "aproveitar", "bala", "banco",
    "banheiro", "barulho", "cinco", "conhecer", "espelho", "esquina", "filho",
    "maca", "medo", "ruim", "sapo", "vacina", "vontade",
)
REPETICOES_MINDS = ("01", "02", "03", "04", "05")
_ESPERADOS = {
    f"pessoa{pessoa}_sinal-{sinal}_rep{rep}.npy": (pessoa, sinal, rep)
    for pessoa in PESSOAS_MINDS
    for sinal in ROTULOS_MINDS
    for rep in REPETICOES_MINDS
}
_SIDECAR = ".proveniencia.json"
_PERMITIDOS = set(_ESPERADOS) | {nome + _SIDECAR for nome in _ESPERADOS} | {".gitkeep"}


def _json_bytes(valor: object) -> bytes:
    return json.dumps(valor, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def _caminho_seguro(caminho: Path) -> Path:
    """Recusa symlinks inclusive nos ancestrais, antes de normalizar o caminho."""
    caminho = Path(caminho).expanduser().absolute()
    if ".." in caminho.parts:
        raise ValueError(f"Caminho com '..' não permitido: {caminho}")
    for parte in reversed((caminho, *caminho.parents)):
        try:
            modo = parte.lstat().st_mode
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(modo):
            raise ValueError(f"Link simbólico não permitido: {parte}")
        if parte != caminho and not stat.S_ISDIR(modo):
            raise ValueError(f"Ancestral não é diretório: {parte}")
    return caminho


@contextmanager
def _abrir_regular(arquivo: Path) -> Iterator[BinaryIO]:
    # O_NONBLOCK evita bloquear em FIFO caso o arquivo seja trocado entre lstat
    # e open; O_NOFOLLOW reforça a recusa de links no último componente.
    if not stat.S_ISREG(arquivo.lstat().st_mode):
        raise ValueError(f"Só arquivo regular é permitido: {arquivo}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    fd = os.open(arquivo, flags)
    with os.fdopen(fd, "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError(f"Só arquivo regular é permitido: {arquivo}")
        yield stream


def _ler_bytes(arquivo: Path) -> bytes:
    with _abrir_regular(arquivo) as stream:
        return stream.read()


def _hash_arquivo(arquivo: Path) -> str:
    digest = hashlib.sha256()
    with _abrir_regular(arquivo) as stream:
        for bloco in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(bloco)
    return digest.hexdigest()


def _copiar(arquivo: Path, destino: Path) -> None:
    with _abrir_regular(arquivo) as entrada, destino.open("xb") as saida:
        shutil.copyfileobj(entrada, saida)


def _conferir_referencia(inventario: dict, referencia: dict | Path | None) -> None:
    if referencia is None:
        return
    if isinstance(referencia, Path):
        referencia = json.loads(_ler_bytes(_caminho_seguro(referencia)))
    if not isinstance(referencia, dict) or referencia != inventario:
        raise ValueError("Manifesto de referência diverge do inventário exato (nomes/bytes)")


def validar_minds(pasta: Path, *, manifesto_referencia: dict | Path | None = None) -> dict:
    """Valida exatamente 8 pessoas × 20 sinais × 5 reps, sem alterar a pasta.

    Retorna amostras {arquivo, sha256, pessoa, sinal, rep}, n_clipes, pessoas,
    rotulos, arquivos {arquivo, sha256, tamanho}, corpus_sha256, versao e
    proveniencia_certificada=False. Arrays: float32 finitos (T>=3, 57, 3),
    leitura allow_pickle=False. Sidecars opcionais: objetos JSON não vazios,
    vinculados pelo nome; sua presença NÃO atesta conteúdo/proveniência.
    """
    pasta = _caminho_seguro(pasta)
    if not pasta.is_dir():
        raise ValueError(f"Pasta MINDS inexistente ou irregular: {pasta}")
    entradas = sorted(pasta.iterdir())
    for arquivo in entradas:
        if not stat.S_ISREG(arquivo.lstat().st_mode):
            raise ValueError(f"Só arquivo regular é permitido: {arquivo.name}")
        if arquivo.name not in _PERMITIDOS:
            raise ValueError(f"Arquivo extra/nome inválido na receita MINDS v1: {arquivo.name}")
    nomes = {p.name for p in entradas}
    faltantes = set(_ESPERADOS) - nomes
    if faltantes:
        raise ValueError(f"MINDS exige exatamente 800 clipes; faltantes ({len(faltantes)}): "
                         f"{sorted(faltantes)[:5]}")

    arquivos, amostras = [], []
    for arquivo in entradas:
        bruto = _ler_bytes(arquivo)
        sha256 = hashlib.sha256(bruto).hexdigest()
        arquivos.append({"arquivo": arquivo.name, "sha256": sha256, "tamanho": len(bruto)})
        if arquivo.name.endswith(_SIDECAR):
            alvo = arquivo.name.removesuffix(_SIDECAR)
            if alvo not in nomes or alvo not in _ESPERADOS:
                raise ValueError(f"Sidecar sem clipe correspondente: {arquivo.name}")
            try:
                meta = json.loads(bruto)
            except (ValueError, UnicodeError) as exc:
                raise ValueError(f"Sidecar JSON inválido: {arquivo.name}") from exc
            if not isinstance(meta, dict) or not meta:
                raise ValueError(f"Sidecar deve ser objeto JSON não vazio: {arquivo.name}")
        elif arquivo.name in _ESPERADOS:
            try:
                stream = io.BytesIO(bruto)
                if not bruto.startswith(b"\x93NUMPY"):
                    raise ValueError("não é um arquivo NPY")
                arr = np.load(stream, allow_pickle=False)
                if (not isinstance(arr, np.ndarray) or arr.dtype != np.dtype("float32")
                        or arr.ndim != 3 or arr.shape[1:] != (57, 3)
                        or arr.shape[0] < 3 or not np.isfinite(arr).all()
                        or stream.tell() != len(bruto)):
                    raise ValueError("esperado float32 finito (T>=3, 57, 3), sem bytes extras")
            except (ValueError, TypeError, EOFError, OSError) as exc:
                raise ValueError(f"Clipe inválido {arquivo.name}: {exc}") from exc
            pessoa, sinal, rep = _ESPERADOS[arquivo.name]
            amostras.append({"arquivo": arquivo.name, "sha256": sha256,
                             "pessoa": pessoa, "sinal": sinal, "rep": rep})
    inventario = {
        "versao": 1, "proveniencia_certificada": False,
        "amostras": amostras, "n_clipes": len(amostras),
        "pessoas": list(PESSOAS_MINDS), "rotulos": list(ROTULOS_MINDS),
        "arquivos": arquivos,
        "corpus_sha256": hashlib.sha256(_json_bytes(arquivos)).hexdigest(),
    }
    _conferir_referencia(inventario, manifesto_referencia)
    return inventario


def _extrair_tar(pacote: Path, staging: Path) -> None:
    if not hasattr(tarfile, "data_filter"):
        raise RuntimeError("Extração exige tarfile com filter='data'; sem fallback inseguro")
    try:
        with tarfile.open(pacote, "r:*") as tar:
            membros = tar.getmembers()
            vistos: set[str] = set()
            for membro in membros:
                nome = membro.name
                raiz = nome in ("landmarks", "landmarks/")
                chave = "landmarks" if raiz else nome
                if chave in vistos:
                    raise ValueError(f"Membro duplicado no tar: {nome}")
                vistos.add(chave)
                if raiz and membro.isdir():
                    continue
                if (not nome.startswith("landmarks/")
                        or nome[len("landmarks/"):] not in _PERMITIDOS
                        or membro.type not in (tarfile.REGTYPE, tarfile.AREGTYPE)
                        or membro.issparse()):
                    raise ValueError(f"Membro não permitido no tar: {nome}")
            tar.extractall(staging, members=membros, filter="data")
    except tarfile.TarError as exc:
        raise ValueError(f"Pacote tar inválido: {pacote.name}") from exc


def preparar_minds(origem: Path, destino: Path, *,
                   manifesto_referencia: dict | Path | None = None) -> Path:
    """Instala atomicamente destino/{landmarks,preparacao.json}; retorna landmarks.

    Origem: pasta direta ou tar (compressões reconhecidas pela stdlib).
    Destino ausente: staging irmão + rename do diretório inteiro. Destino
    existente (mesmo vazio): só reusa se registro canônico, origem, caminho e
    inventário completo coincidirem. Alteração nunca é reparada/sobrescrita.
    Origem dentro de destino, destino dentro de origem e symlinks são recusados.
    Caminhos são absolutos no registro; mover/copiar uma instalação não a reusa.
    """
    origem = _caminho_seguro(origem)
    destino = _caminho_seguro(destino)
    if origem == destino or origem in destino.parents or destino in origem.parents:
        raise ValueError("Origem e destino não podem coincidir nem conter um ao outro")
    if not origem.exists():
        raise FileNotFoundError(origem)
    if not (origem.is_dir() or stat.S_ISREG(origem.lstat().st_mode)):
        raise ValueError(f"Origem irregular: {origem}")
    if destino.exists() and not destino.is_dir():
        raise ValueError(f"Destino não é diretório: {destino}")
    destino.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".minds-v1-", dir=destino.parent) as tmp:
        temporario = Path(tmp)
        instalacao = temporario / "instalacao"
        instalacao.mkdir()
        pasta = instalacao / "landmarks"
        if origem.is_dir():
            original = validar_minds(origem)
            identidade = {"caminho": str(origem), "tipo": "pasta",
                          "sha256": original["corpus_sha256"]}
            pasta.mkdir()
            for item in original["arquivos"]:
                _copiar(origem / item["arquivo"], pasta / item["arquivo"])
        else:
            pacote = temporario / "origem.tar"
            _copiar(origem, pacote)
            identidade = {"caminho": str(origem), "tipo": "tar",
                          "sha256": _hash_arquivo(pacote)}
            _extrair_tar(pacote, instalacao)
            original = None
        inventario = validar_minds(pasta, manifesto_referencia=manifesto_referencia)
        if original is not None and inventario != original:
            raise ValueError("Origem mudou durante a cópia para staging")
        registro = {"versao": 1, "receita": "minds-final-v1", "origem": identidade,
                    "destino": str(destino), "inventario": inventario}
        registro_bytes = _json_bytes(registro) + b"\n"

        # Segunda leitura da origem: mudanças durante cópia/extração não são
        # silenciosamente aceitas como se fossem a entrada inicialmente validada.
        if original is not None:
            if validar_minds(origem) != original:
                raise ValueError("Origem mudou durante a preparação")
        elif _hash_arquivo(origem) != identidade["sha256"]:
            raise ValueError("Tar de origem mudou durante a preparação")

        _caminho_seguro(destino)
        if destino.exists():
            if not destino.is_dir() or {p.name for p in destino.iterdir()} != {
                    "landmarks", "preparacao.json"}:
                raise ValueError("Destino existente sem estrutura/registro exatos; não será alterado")
            if _ler_bytes(destino / "preparacao.json") != registro_bytes:
                raise ValueError("Registro diverge: origem, destino ou inventário alterado")
            if validar_minds(destino / "landmarks") != inventario:
                raise ValueError("Destino adulterado: inventário/bytes divergem do registro")
            return destino / "landmarks"

        (instalacao / "preparacao.json").write_bytes(registro_bytes)
        instalacao.rename(destino)
    return destino / "landmarks"


def conferir_carregados(inventario: dict, clipes: Iterable[object]) -> None:
    """Exige as mesmas 800 identidades (pessoa,sinal,rep), sem perda/duplicação.

    Aceita objetos como dados.Clipe, sem importar dados. Ordem é irrelevante;
    não compara seq/bytes, pois o loader pode imputar mãos ou recentrar z.
    Deve ser chamada imediatamente após carregar e ANTES de particionar/treinar.
    """
    esperadas = Counter(_ESPERADOS.values())
    try:
        declaradas = Counter((a["pessoa"], a["sinal"], a["rep"])
                             for a in inventario["amostras"])
        nomes_validos = all(_ESPERADOS.get(a["arquivo"]) == (a["pessoa"], a["sinal"], a["rep"])
                            for a in inventario["amostras"])
    except (KeyError, TypeError) as exc:
        raise ValueError("Inventário de amostras inválido") from exc
    if inventario.get("n_clipes") != 800 or declaradas != esperadas or not nomes_validos:
        raise ValueError("Inventário não representa as 800 identidades da receita MINDS v1")
    carregadas: Counter = Counter()
    for clipe in clipes:
        identidade = tuple(getattr(clipe, campo, None) for campo in ("pessoa", "sinal", "rep"))
        if not all(isinstance(valor, str) for valor in identidade):
            raise ValueError("Clipe carregado sem identidade textual (pessoa, sinal, rep)")
        carregadas[identidade] += 1
    if carregadas != esperadas:
        faltantes = list((esperadas - carregadas).elements())
        extras = list((carregadas - esperadas).elements())
        raise ValueError(f"Loader alterou identidades: faltantes={faltantes[:5]}, "
                         f"extras/duplicadas={extras[:5]}; carregados={sum(carregadas.values())}")
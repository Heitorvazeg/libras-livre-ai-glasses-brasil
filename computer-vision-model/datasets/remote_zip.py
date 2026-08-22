"""Leitura seletiva de um .zip remoto por HTTP Range — sem baixar o arquivo inteiro.

Existe por um motivo prático: o bundle da MINDS-Libras no Kaggle tem ~47 GB, e a
seleção de sinais que interessa a este projeto ocupa uma fração disso. Um .zip
guarda o índice (diretório central) no FIM do arquivo, e cada membro num trecho
contíguo — então, com servidor que aceite `Range` (o Kaggle redireciona para o
Google Cloud Storage, que aceita), dá para:

  1. ler só o rodapé e montar a lista de arquivos;
  2. baixar só os bytes dos membros escolhidos, um pedido HTTP por vídeo.

O diretório central é lido com o `zipfile` da biblioteca padrão sobre um
file-like que faz Range (`_HttpFile`); a extração NÃO usa `zipfile`, porque ele
leria o membro em blocos pequenos — muitos pedidos HTTP por vídeo. Em vez disso,
`extrair()` faz um único GET do intervalo do membro e descomprime em fluxo.

Tudo passa por uma conexão HTTPS **persistente** (`_Sessao`): abrir uma conexão
nova por arquivo é o que mais falha numa ingestão de centenas de clipes — cada
handshake é uma chance de travar em SYN-SENT e esperar o timeout inteiro à toa.

Sem dependências externas: só a biblioteca padrão.
"""
from __future__ import annotations

import http.client
import io
import json
import socket
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator

UA = "libras-livre-dataset-ingest/1.0"
TIMEOUT = 45          # segundos, vale para conectar e para cada leitura de socket
_TENTATIVAS = 6
_BLOCO = 1 << 20      # 1 MiB por leitura de socket
_REDE = (urllib.error.URLError, http.client.HTTPException, socket.timeout, OSError)


class _Sessao:
    """Conexão HTTPS persistente para uma URL fixa, com pedidos por faixa de bytes.

    O redirect do Kaggle para o Google Cloud Storage é resolvido UMA vez, no
    início: a URL assinada resultante é a que fica sendo pedida daí em diante.
    """

    def __init__(self, url: str):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-0"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            faixa = resp.headers.get("Content-Range")
            if not faixa:
                raise RuntimeError(
                    "o servidor não respondeu com Content-Range — sem suporte a Range "
                    "não dá para ler o zip seletivamente; baixe o dataset inteiro.")
            self.url = resp.geturl()
            self.tamanho = int(faixa.split("/")[1])

        partes = urllib.parse.urlsplit(self.url)
        self._host = partes.netloc
        self._caminho = partes.path + (f"?{partes.query}" if partes.query else "")
        self._con: http.client.HTTPSConnection | None = None

    def _conexao(self) -> http.client.HTTPSConnection:
        if self._con is None:
            self._con = http.client.HTTPSConnection(self._host, timeout=TIMEOUT)
        return self._con

    def fechar(self) -> None:
        if self._con is not None:
            try:
                self._con.close()
            finally:
                self._con = None

    def resposta(self, inicio: int, fim: int) -> http.client.HTTPResponse:
        """Abre um GET com Range fechado. O chamador PRECISA ler a resposta inteira
        (ou chamar `fechar()`), senão a conexão persistente fica dessincronizada."""
        con = self._conexao()
        con.request("GET", self._caminho, headers={
            "User-Agent": UA,
            "Range": f"bytes={inicio}-{fim}",
            "Accept-Encoding": "identity",  # o conteúdo já é um zip; não recomprimir
        })
        resp = con.getresponse()
        if resp.status not in (200, 206):
            corpo = resp.read(512)
            raise http.client.HTTPException(f"HTTP {resp.status} ao pedir {inicio}-{fim}: {corpo[:200]!r}")
        return resp

    def tentar(self, fn: Callable[[], object], descricao: str):
        """Executa `fn`, refazendo a conexão e esperando um pouco a cada falha."""
        ultimo: Exception | None = None
        for tentativa in range(_TENTATIVAS):
            try:
                return fn()
            except _REDE as e:
                ultimo = e
                self.fechar()  # conexão suspeita: a próxima tentativa abre outra
                if tentativa < _TENTATIVAS - 1:
                    time.sleep(min(2 ** tentativa, 15))
        raise RuntimeError(f"falhou após {_TENTATIVAS} tentativas: {descricao} ({ultimo})")

    def bytes(self, inicio: int, fim: int) -> bytes:
        def _ler() -> bytes:
            with self.resposta(inicio, fim) as resp:
                return resp.read()

        return self.tentar(_ler, f"ler bytes {inicio}-{fim}")


class _HttpFile(io.RawIOBase):
    """File-like somente-leitura e posicionável sobre uma `_Sessao`.

    Guarda o TRECHO FINAL do arquivo em memória: o diretório central do zip está
    lá, e o `zipfile` o percorre com muitos seeks curtos para trás — servi-los da
    rede levaria dezenas de segundos e centenas de pedidos.
    """

    CAUDA = 8 << 20  # 8 MiB finais em cache — cabe o diretório central dos dois datasets

    def __init__(self, sessao: _Sessao):
        self.sessao = sessao
        self.size = sessao.tamanho
        self.pos = 0
        self._cauda_inicio = max(0, self.size - self.CAUDA)
        self._cauda: bytes | None = None  # buscada só quando alguém lê o rodapé

    def seekable(self) -> bool: return True
    def readable(self) -> bool: return True

    def seek(self, offset: int, whence: int = io.SEEK_SET) -> int:
        base = {io.SEEK_SET: 0, io.SEEK_CUR: self.pos, io.SEEK_END: self.size}[whence]
        self.pos = max(0, base + offset)
        return self.pos

    def tell(self) -> int: return self.pos

    def read(self, n: int = -1) -> bytes:
        if n < 0:
            n = self.size - self.pos
        if n <= 0 or self.pos >= self.size:
            return b""
        # Devolver menos bytes que o pedido é legítimo em RawIOBase, e evita que
        # um read(-1) desavisado tente puxar dezenas de GB para a memória.
        n = min(n, 16 << 20)
        inicio, fim = self.pos, min(self.pos + n, self.size) - 1
        if inicio >= self._cauda_inicio:
            if self._cauda is None:
                self._cauda = self.sessao.bytes(self._cauda_inicio, self.size - 1)
            dados = self._cauda[inicio - self._cauda_inicio : fim + 1 - self._cauda_inicio]
        else:
            dados = self.sessao.bytes(inicio, fim)
        self.pos += len(dados)
        return dados

    def readinto(self, b) -> int:  # type: ignore[override]
        dados = self.read(len(b))
        b[: len(dados)] = dados
        return len(dados)


@dataclass(frozen=True)
class Membro:
    """Uma entrada do zip remoto, com o que basta para baixá-la sozinha."""

    nome: str
    tamanho: int          # descomprimido
    tamanho_comprimido: int
    metodo: int           # ZIP_STORED ou ZIP_DEFLATED
    offset_cabecalho: int
    crc: int


class ZipRemoto:
    """Índice de um .zip remoto + extração membro a membro.

    O índice pode ser guardado em `cache` (JSON): ele não muda enquanto o zip for
    o mesmo (a validade é conferida pelo tamanho total do arquivo), e relê-lo a
    cada execução custa alguns segundos de rede à toa.
    """

    def __init__(self, url: str, cache: Path | None = None):
        self.sessao = _Sessao(url)
        self.url = self.sessao.url
        self.tamanho_total = self.sessao.tamanho

        if cache and (membros := self._ler_cache(cache)) is not None:
            self.membros = membros
            return
        self.membros = self._ler_diretorio_central()
        if cache:
            self._gravar_cache(cache)

    def __len__(self) -> int:
        return len(self.membros)

    def _ler_diretorio_central(self) -> dict[str, Membro]:
        arquivo = io.BufferedReader(_HttpFile(self.sessao), buffer_size=1 << 20)
        with zipfile.ZipFile(arquivo) as zf:
            return {
                info.filename: Membro(
                    nome=info.filename,
                    tamanho=info.file_size,
                    tamanho_comprimido=info.compress_size,
                    metodo=info.compress_type,
                    offset_cabecalho=info.header_offset,
                    crc=info.CRC,
                )
                for info in zf.infolist()
                if not info.is_dir()
            }

    def _ler_cache(self, cache: Path) -> dict[str, Membro] | None:
        try:
            dados = json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if dados.get("tamanho_total") != self.tamanho_total:
            return None  # o dataset foi republicado: o índice antigo não vale mais
        return {m["nome"]: Membro(**m) for m in dados["membros"]}

    def _gravar_cache(self, cache: Path) -> None:
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps({
            "tamanho_total": self.tamanho_total,
            "membros": [vars(m) for m in self.membros.values()],
        }), encoding="utf-8")

    def _inicio_dados(self, m: Membro) -> int:
        """Offset do primeiro byte de dados: pula o cabeçalho local (tamanho variável)."""
        cab = self.sessao.bytes(m.offset_cabecalho, m.offset_cabecalho + 29)
        assinatura, *_, n_nome, n_extra = struct.unpack("<IHHHHHIIIHH", cab)
        if assinatura != 0x04034B50:
            raise RuntimeError(f"cabeçalho local inválido para {m.nome!r} — o zip mudou?")
        return m.offset_cabecalho + 30 + n_nome + n_extra

    def _fluxo(self, m: Membro, inicio: int) -> Iterator[bytes]:
        """Bytes descomprimidos do membro, em pedaços, com UM pedido HTTP."""
        if m.metodo not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise RuntimeError(f"{m.nome}: método de compressão {m.metodo} não suportado")
        descompressor = (zlib.decompressobj(-zlib.MAX_WBITS)
                         if m.metodo == zipfile.ZIP_DEFLATED else None)

        with self.sessao.resposta(inicio, inicio + m.tamanho_comprimido - 1) as resp:
            while True:
                bloco = resp.read(_BLOCO)
                if not bloco:
                    break
                yield descompressor.decompress(bloco) if descompressor else bloco
        if descompressor:
            resto = descompressor.flush()
            if resto:
                yield resto

    def extrair(self, nome: str, destino: Path,
                progresso: Callable[[int], None] | None = None) -> Path:
        """Baixa um membro para `destino`, verificando CRC e tamanho.

        Escreve num arquivo `.parcial` e só renomeia no fim: uma queda de rede no
        meio nunca deixa um vídeo truncado passando por completo na próxima
        execução (a ingestão pula o que já existe).
        """
        m = self.membros.get(nome)
        if m is None:
            raise KeyError(f"membro ausente no zip remoto: {nome!r}")

        destino.parent.mkdir(parents=True, exist_ok=True)
        parcial = destino.with_suffix(destino.suffix + ".parcial")
        inicio = self.sessao.tentar(lambda: self._inicio_dados(m), f"cabeçalho de {nome}")

        def _baixar() -> None:
            crc = 0
            escrito = 0
            with open(parcial, "wb") as fh:
                for pedaco in self._fluxo(m, inicio):
                    fh.write(pedaco)
                    crc = zlib.crc32(pedaco, crc)
                    escrito += len(pedaco)
                    if progresso:
                        progresso(len(pedaco))
            if escrito != m.tamanho:
                raise OSError(f"{nome}: {escrito} bytes recebidos, {m.tamanho} esperados")
            if m.crc and crc != m.crc:
                raise OSError(f"{nome}: CRC não confere — download corrompido")

        try:
            self.sessao.tentar(_baixar, f"baixar {nome}")
        except Exception:
            parcial.unlink(missing_ok=True)
            raise
        parcial.replace(destino)
        return destino


def zip_do_kaggle(slug: str, cache_dir: Path | None = None) -> ZipRemoto:
    """Abre o bundle .zip de um dataset público do Kaggle (`usuario/dataset`).

    Datasets públicos não pedem credencial: o Kaggle redireciona para uma URL
    assinada do Google Cloud Storage, que aceita Range. Essa assinatura tem
    validade (alguns dias) — se um download muito longo começar a falhar, basta
    rodar de novo, que uma URL nova é resolvida e os clipes já baixados são
    pulados.
    """
    cache = cache_dir / f"{slug.replace('/', '_')}.json" if cache_dir else None
    return ZipRemoto(f"https://www.kaggle.com/api/v1/datasets/download/{slug}", cache=cache)

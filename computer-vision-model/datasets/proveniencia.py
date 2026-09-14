"""Identidade verificável por vídeo/landmark, sem alterar o formato .npy.

Sidecars são manifestos por amostra: <arquivo>.proveniencia.json. Não guardam
URLs assinadas, credenciais nem caminhos absolutos da máquina de ingestão.
Hashes detectam alteração/duplicação; não autenticam uma fonte maliciosa.
"""
from __future__ import annotations

import csv
import hashlib
import json
import re
import tempfile
import zlib
from pathlib import Path

AQUI = Path(__file__).resolve().parent
MANIFESTO = AQUI / "manifest.csv"
BUNDLES = {"vlibrasil": "davimedio01/v-librasil", "minds": "j0aopsantos/minds-libras",
           "wlasl": "risangbaskoro/wlasl-processed",
           # MALTA não é um bundle do Kaggle: é um agregador de dicionários
           # baixados por HTTP direto. O "índice" equivalente são os CSVs de
           # links do toolkit, e o sha256 deles pina exatamente qual lista de
           # URLs produziu estes arquivos.
           "malta": "Malta-Lab/ISLR_LIBRAS"}
PREFIXOS = {"vlibrasil": "V", "minds": "M", "wlasl": "W", "malta": "T"}
# Fontes sem checksum publicado na origem. Para bundle do Kaggle conferimos o
# CRC do membro do ZIP e sabemos que o byte que temos é o byte que o publicador
# tinha. Em HTTP direto isso não existe: o sha256 que gravamos prova apenas que
# o arquivo não mudou DEPOIS de chegar aqui. É garantia mais fraca, e o sidecar
# diz isso em vez de deixar parecer equivalente.
SEM_CHECKSUM_DE_ORIGEM = {"malta"}


def hash_json(objeto) -> str:
    return hashlib.sha256(json.dumps(objeto, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":"), default=str).encode()).hexdigest()


def hash_arquivo(caminho: Path) -> str:
    h = hashlib.sha256()
    with caminho.open("rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
    return h.hexdigest()


def sidecar(caminho: Path) -> Path:
    return caminho.with_name(caminho.name + ".proveniencia.json")


def escrever(caminho: Path, registro: dict) -> None:
    """Substituição atômica, inclusive com extração em processos paralelos."""
    caminho.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=caminho.parent,
                                     prefix=caminho.name, suffix=".tmp", delete=False) as f:
        temporario = Path(f.name)
        json.dump(registro, f, ensure_ascii=False, sort_keys=True, indent=2)
        f.write("\n")
    try:
        temporario.replace(caminho)
    finally:
        temporario.unlink(missing_ok=True)


def ler_reservas(caminho: Path = MANIFESTO) -> list[dict]:
    if not caminho.is_file():
        raise ValueError(f"manifesto de avaliação ausente: {caminho}; restaure-o antes de prosseguir")
    with caminho.open(newline="", encoding="utf-8") as f:
        leitor = csv.DictReader(f)
        if not {"fonte", "origem", "arquivo"} <= set(leitor.fieldnames or []):
            raise ValueError(f"manifesto de avaliação inválido: {caminho}")
        linhas = list(leitor)
    if not linhas or any(not r.get("origem") or not r.get("fonte") for r in linhas):
        raise ValueError(f"manifesto de avaliação vazio ou incompleto: {caminho}")
    return linhas


def identidade_nome(caminho: Path) -> tuple[str, str, str]:
    m = re.fullmatch(r"pessoa([^_]+)_sinal-(.+)_rep(\d+)", caminho.stem)
    if not m:
        raise ValueError(f"nome de amostra inválido: {caminho.name}")
    return m[1], m[2], m[3]


def descrever_bundle(z, fonte: str) -> dict:
    indice = [(n, m.tamanho, m.crc) for n, m in sorted(z.membros.items())]
    return {"id": BUNDLES[fonte], "indice_sha256": hash_json(indice)}


def registrar_clipe(c, z, destino: Path, fonte: str, bundle: dict) -> dict:
    membro = z.membros[c.origem]
    return registrar_video(destino / c.destino, fonte=fonte, origem=c.origem,
                           bundle=bundle, tamanho=membro.tamanho, crc32=membro.crc)


def registrar_video(caminho: Path, *, fonte: str, origem: str, bundle: dict,
                    tamanho: int, crc32: int) -> dict:
    """Confere também arquivos reaproveitados de downloads anteriores."""
    pessoa, sinal, rep = identidade_nome(caminho)
    if not pessoa.startswith(PREFIXOS[fonte]) or bundle.get("id") != BUNDLES[fonte]:
        raise ValueError(f"fonte/bundle incompatível com {caminho.name}")
    h, crc, total = hashlib.sha256(), 0, 0
    with caminho.open("rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
            crc = zlib.crc32(bloco, crc)
            total += len(bloco)
    if total != tamanho or crc != crc32:
        raise ValueError(f"vídeo diverge do membro do bundle (tamanho/CRC): {caminho.name}")
    registro = {"schema": 1, "tipo": "video", "fonte": fonte, "bundle": bundle,
                "origem": origem, "pessoa": pessoa, "sinal": sinal, "rep": rep,
                "video": {"arquivo": caminho.name, "sha256": h.hexdigest(),
                          "bytes": total, "crc32": crc}}
    if sidecar(caminho).exists():
        anterior = json.loads(sidecar(caminho).read_text(encoding="utf-8"))
        if any(anterior.get(k) != registro[k] for k in ("fonte", "origem", "video", "bundle")):
            raise ValueError(f"proveniência anterior incompatível: {caminho.name}; não sobrescrita")
    escrever(sidecar(caminho), registro)
    return registro


def registrar_video_http(caminho: Path, *, fonte: str, origem: str,
                        indice_sha256: str) -> dict:
    """Proveniência de vídeo baixado por HTTP direto, sem checksum de origem.

    Diferença que importa em relação a `registrar_video`: lá o tamanho e o CRC
    vêm do índice do ZIP publicado, então a conferência prova que recebemos o
    mesmo byte que o publicador tinha. Aqui não há com o que comparar — o
    servidor devolve o arquivo e pronto. O sha256 gravado prova que nada mudou
    depois da chegada, e é só isso que ele prova.

    Registrar essa diferença é o ponto. Um sidecar que não a declarasse deixaria
    alguém, meses depois, tratar dado de dicionário universitário baixado por
    HTTP como se tivesse a mesma cadeia de custódia de um bundle versionado.
    """
    if fonte not in PREFIXOS:
        raise ValueError(f"fonte desconhecida: {fonte}")
    pessoa, sinal, rep = identidade_nome(caminho)
    if not pessoa.startswith(PREFIXOS[fonte]):
        raise ValueError(f"prefixo de pessoa não é de {fonte}: {caminho.name}")
    if not re.fullmatch(r"[0-9a-f]{64}", indice_sha256):
        raise ValueError("indice_sha256 precisa ser sha256 hexadecimal")
    if not origem:
        raise ValueError("origem (URL) obrigatória")
    h, total = hashlib.sha256(), 0
    with caminho.open("rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
            total += len(bloco)
    if total == 0:
        raise ValueError(f"arquivo vazio: {caminho.name}")
    registro = {"schema": 1, "tipo": "video", "fonte": fonte,
                "bundle": {"id": BUNDLES[fonte], "indice_sha256": indice_sha256},
                "origem": origem, "pessoa": pessoa, "sinal": sinal, "rep": rep,
                "video": {"arquivo": caminho.name, "sha256": h.hexdigest(),
                          "bytes": total,
                          "verificacao": "sha256 local; origem sem checksum publicado"}}
    escrever(sidecar(caminho), registro)
    return registro


def ler(caminho: Path) -> dict:
    """Falha fechada: sidecar ausente, renomeação ou alteração não passam."""
    meta = sidecar(caminho)
    if not meta.is_file():
        raise ValueError(f"proveniência ausente: {meta.name}; registre a origem antes do pré-treino")
    try:
        r = json.loads(meta.read_text(encoding="utf-8"))
        pessoa, sinal, rep = identidade_nome(caminho)
        fonte = r["fonte"]
        if (r["schema"] != 1 or fonte not in PREFIXOS
                or r["bundle"]["id"] != BUNDLES[fonte]
                or not re.fullmatch(r"[0-9a-f]{64}", r["bundle"]["indice_sha256"])
                or not r["origem"] or not pessoa.startswith(PREFIXOS[fonte])
                or (r["pessoa"], r["sinal"], r["rep"]) != (pessoa, sinal, rep)
                or not re.fullmatch(r"[0-9a-f]{64}", r["video"]["sha256"])):
            raise ValueError("identidade incompatível")
        tipo = "landmarks" if caminho.suffix == ".npy" else "video"
        if r["tipo"] != tipo or r[tipo]["arquivo"] != caminho.name:
            raise ValueError("tipo/nome incompatível")
        if r[tipo]["sha256"] != hash_arquivo(caminho):
            raise ValueError("hash divergente")
        if tipo == "landmarks":
            import numpy as np
            arr = np.load(caminho, mmap_mode="r", allow_pickle=False)
            cfg = r["extracao"]["config"]
            if (arr.ndim != 3 or list(arr.shape) != r[tipo]["shape"]
                    or str(arr.dtype) != r[tipo]["dtype"]
                    or arr.shape[1] != r["extracao"]["num_pontos"]
                    or arr.shape[2] != r["extracao"]["dims"]
                    or arr.shape[1] != len(cfg["pose_indices"]) + 42
                    or not isinstance(cfg["normalizacao"], dict)
                    or not isinstance(cfg["holistic"], dict)):
                raise ValueError("configuração de extração incompatível com array")
    except (KeyError, TypeError, ValueError) as e:
        raise ValueError(f"proveniência inválida em {meta.name}: {e}") from e
    return r


def registrar_landmarks(video: Path, destino: Path, configuracao: dict, *,
                        legado: bool = False) -> dict:
    import numpy as np
    registro = ler(video)
    arr = np.load(destino, mmap_mode="r", allow_pickle=False)
    if arr.ndim != 3 or arr.shape[1:] != (len(configuracao["pose_indices"]) + 42, 3):
        raise ValueError(f"configuração não corresponde aos landmarks: {destino.name}")
    if identidade_nome(video) != identidade_nome(destino):
        raise ValueError("vídeo e landmarks têm identidades diferentes")
    registro.update(tipo="landmarks", landmarks={"arquivo": destino.name,
                    "sha256": hash_arquivo(destino), "shape": list(arr.shape),
                    "dtype": str(arr.dtype)}, extracao={"num_pontos": arr.shape[1],
                    "dims": arr.shape[2], "config": configuracao,
                    "config_declarada_para_legado": legado})
    escrever(sidecar(destino), registro)
    return registro
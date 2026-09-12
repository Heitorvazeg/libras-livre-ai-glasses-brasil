"""Vincula arquivos existentes ao índice LOCAL do ZIP, sem rede ou reextração.

Vídeos são conferidos por tamanho/CRC e recebem SHA-256. Para landmarks antigos,
exige declaração explícita da configuração histórica; não presume que o YAML
atual foi usado no passado. A declaração fica identificada nos metadados.
"""
from __future__ import annotations

import argparse
import json
import zlib
from pathlib import Path
from types import SimpleNamespace

import proveniencia as pv
from ingest_pretreino import _RE_MEMBRO, slug
from remote_zip import Membro


def resolver_origem(video: Path, candidatos: set[str], membros: dict[str, Membro]) -> str:
    """Colisão de slug legado: só aceita um membro compatível em tamanho/CRC.

    Avó/Avô perderam o acento na ingestão antiga. A ordem do índice ou o nome
    local não provam qual vídeo ficou em disco. Empate continua sendo erro.
    O registrador confere novamente o membro escolhido e calcula SHA-256.
    """
    if len(candidatos) == 1:
        return next(iter(candidatos))
    crc, tamanho = 0, 0
    with video.open("rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            crc = zlib.crc32(bloco, crc)
            tamanho += len(bloco)
    compativeis = [n for n in sorted(candidatos)
                  if membros[n].tamanho == tamanho and membros[n].crc == crc]
    if not compativeis:
        raise ValueError(f"nenhuma origem compatível em tamanho/CRC para {video.name}")
    if len(compativeis) != 1:
        raise ValueError(f"origem ambígua para {video.name}, mesmo após tamanho/CRC")
    return compativeis[0]


def registrar(videos: Path, indice: Path, fonte: str, landmarks: Path | None = None,
              config: dict | None = None) -> int:
    dados = json.loads(indice.read_text(encoding="utf-8"))
    z = SimpleNamespace(membros={m["nome"]: Membro(**m) for m in dados["membros"]})
    bundle = pv.descrever_bundle(z, fonte)
    mapa: dict[str, set[str]] = {}
    for r in pv.ler_reservas():
        if r["fonte"] == fonte and r["origem"] in z.membros:
            mapa.setdefault(r["arquivo"], set()).add(r["origem"])
    if fonte == "vlibrasil":
        for origem in z.membros:
            m = _RE_MEMBRO.match(origem)
            if not m:
                continue
            nome = f"pessoaV{int(m['pessoa']):02d}_sinal-{slug(m['palavra'])}_rep01.mp4"
            mapa.setdefault(nome, set()).add(origem)
    if landmarks is not None:
        arquivos = [videos / (p.stem + ".mp4") for p in sorted(landmarks.glob("*.npy"))]
        if config is None:
            raise ValueError("configuração histórica obrigatória para landmarks legados")
    else:
        arquivos = sorted(videos.glob("*.mp4"))
    if not arquivos:
        raise ValueError("nenhum arquivo encontrado para registrar")
    for v in arquivos:
        if not v.is_file() or v.name not in mapa:
            raise ValueError(f"vídeo/origem indisponível para {v.name}; não é seguro inferir")
    # Resolver somente os arquivos solicitados e antes de gravar sidecars.
    origens = {v.name: resolver_origem(v, mapa[v.name], z.membros) for v in arquivos}
    for v in arquivos:
        origem = origens[v.name]
        m = z.membros[origem]
        pv.registrar_video(v, fonte=fonte, origem=origem, bundle=bundle,
                           tamanho=m.tamanho, crc32=m.crc)
        if landmarks is not None:
            destino = landmarks / (v.stem + ".npy")
            if pv.sidecar(destino).exists():
                anterior = pv.ler(destino)
                if (anterior["video"] != pv.ler(v)["video"]
                        or anterior["extracao"]["config"] != config):
                    raise ValueError(f"registro existente incompatível: {destino.name}")
            else:
                pv.registrar_landmarks(v, destino, config, legado=True)
    return len(arquivos)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--videos", required=True, type=Path)
    ap.add_argument("--indice", required=True, type=Path, help="cache JSON do remote_zip")
    ap.add_argument("--fonte", required=True, choices=["minds", "vlibrasil"])
    ap.add_argument("--landmarks", type=Path)
    ap.add_argument("--config-extracao", type=Path)
    ap.add_argument("--confirmar-config-legada", action="store_true")
    args = ap.parse_args()
    cfg = None
    if args.landmarks:
        if not args.config_extracao or not args.confirmar_config_legada:
            ap.error("landmarks legados exigem --config-extracao e --confirmar-config-legada")
        import yaml
        cfg = yaml.safe_load(args.config_extracao.read_text(encoding="utf-8"))
    try:
        n = registrar(args.videos, args.indice, args.fonte, args.landmarks, cfg)
    except (ValueError, OSError, KeyError) as e:
        raise SystemExit(f"[proveniência] ABORTADO: {e}") from e
    print(f"[proveniência] {n} amostras vinculadas; arrays e vídeos não foram modificados")


if __name__ == "__main__":
    main()
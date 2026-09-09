"""Vincula arquivos existentes ao índice LOCAL do ZIP, sem rede ou reextração.

Vídeos são conferidos por tamanho/CRC e recebem SHA-256. Para landmarks antigos,
exige declaração explícita da configuração histórica; não presume que o YAML
atual foi usado no passado. A declaração fica identificada nos metadados.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from types import SimpleNamespace

import proveniencia as pv
from ingest_pretreino import _RE_MEMBRO, slug
from remote_zip import Membro


def registrar(videos: Path, indice: Path, fonte: str, landmarks: Path | None = None,
              config: dict | None = None) -> int:
    dados = json.loads(indice.read_text(encoding="utf-8"))
    z = SimpleNamespace(membros={m["nome"]: Membro(**m) for m in dados["membros"]})
    bundle = pv.descrever_bundle(z, fonte)
    mapa: dict[str, str] = {}
    for r in pv.ler_reservas():
        if r["fonte"] == fonte and r["origem"] in z.membros:
            mapa[r["arquivo"]] = r["origem"]
    if fonte == "vlibrasil":
        for origem in z.membros:
            m = _RE_MEMBRO.match(origem)
            if not m:
                continue
            nome = f"pessoaV{int(m['pessoa']):02d}_sinal-{slug(m['palavra'])}_rep01.mp4"
            if nome in mapa and mapa[nome] != origem:
                raise ValueError(f"origem ambígua para {nome}")
            mapa[nome] = origem
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
    for v in arquivos:
        origem = mapa[v.name]
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
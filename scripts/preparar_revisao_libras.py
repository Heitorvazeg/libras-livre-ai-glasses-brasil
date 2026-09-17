#!/usr/bin/env python3
"""Build an offline consultant queue from the existing audit, without reclassifying it."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASE = ROOT / "external-data" / "libras-gap"
FIELDS = ["palavra", "id", "dur_s", "canal", "titulo", "licenca", "veredito", "alertas", "revisao_manual"]
MEDIA = {".mp4", ".webm", ".mkv", ".mov"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect_video(path):
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=codec_type,width,height:format=duration", "-of", "json", str(path)],
        capture_output=True, text=True, check=True, timeout=20,
    )
    info = json.loads(result.stdout)
    duration = float(info.get("format", {}).get("duration", 0))
    streams = info.get("streams", [])
    if not streams or not math.isfinite(duration) or duration <= 0:
        raise ValueError("Vídeo sem stream ou duração válida")
    return {"duration": duration, "width": streams[0].get("width"), "height": streams[0].get("height")}


def _referencia_relativa(ref, base, output):
    """A imagem é gravada relativa à coleta; a página vive numa subpasta dela."""
    if not ref:
        return None
    alvo = (base / ref["imagem"]).resolve()
    if not alvo.is_file() or not alvo.is_relative_to(base):
        return {**ref, "imagem": None}
    return {**ref, "imagem": quote(os.path.relpath(alvo, output), safe="/.-_")}


def build_queue(base, audit, output):
    base, audit, output = base.resolve(), audit.resolve(), output.resolve()
    # Generated outputs must never replace the input audit or original media.
    if output == base or not output.is_relative_to(base) or audit.is_relative_to(output):
        raise ValueError("Use uma subpasta separada da coleta para os relatórios")
    with audit.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != FIELDS:
            raise ValueError("Cabeçalho de triagem inesperado")
        rows = list(reader)
    # Opcional: gerado por comparar_referencia_libras.py. Ausente, a fila continua
    # válida — só chega ao consultor sem a comparação ao lado.
    ref_path = base / "referencia.json"
    referencias = {}
    if ref_path.is_file():
        dados = json.loads(ref_path.read_text(encoding="utf-8"))
        if dados.get("schema_version") != 1:
            raise ValueError("referencia.json de versão desconhecida")
        referencias = dados.get("por_candidato", {})
    # Idem para a medição de detecção: opcional, e o que ela traz de mais útil é
    # a janela de recorte pronta, para o consultor não procurá-la no vídeo.
    det_path = base / "deteccao.json"
    deteccoes = {}
    if det_path.is_file():
        dados = json.loads(det_path.read_text(encoding="utf-8"))
        if dados.get("schema_version") != 1:
            raise ValueError("deteccao.json de versão desconhecida")
        deteccoes = dados.get("por_candidato", {})
    seen = set()
    candidates, exclusions = [], []
    for row in rows:
        if not re.fullmatch(r"[\w-]{11}", row["id"]) or not re.fullmatch(r"[\w-]+", row["palavra"]):
            raise ValueError("ID ou pasta da palavra inválidos")
        key = row["palavra"] + ":" + row["id"]
        if key in seen or row["veredito"] not in {"candidato", "descartar"}:
            raise ValueError("Registro repetido ou veredito inesperado")
        seen.add(key)
        if row["veredito"] != "candidato":
            exclusions.append(dict(row))
            continue
        item = {"key": key, "audit": dict(row), "source_url": "https://www.youtube.com/watch?v=" + row["id"],
                "media_url": None, "sha256": None, "technical": None, "file_issue": None,
                "linguistic_status": "pendente", "permission_status": "pendente", "training_ready": False,
                # Comparação contra MALTA/V-LIBRASIL, quando a palavra existe lá. É
                # prova anexada, não veredito: `convergencia` fica "pendente" e quem
                # decide continua sendo o consultor.
                "reference": _referencia_relativa(referencias.get(key), base, output),
                "detection": deteccoes.get(key)}
        folder = base / row["palavra"]
        matches = [p for p in folder.glob("*") if p.suffix.lower() in MEDIA
                   and p.stem.endswith("-" + row["id"]) and p.is_file()]
        if len(matches) != 1:
            item["file_issue"] = f"Esperado 1 arquivo correspondente; encontrados {len(matches)}"
        else:
            path = matches[0].resolve()
            if not path.is_relative_to(base):
                item["file_issue"] = "Arquivo fora da coleta (link simbólico)"
            else:
                try:
                    item["sha256"] = digest(path)
                    item["technical"] = inspect_video(path)
                    item["media_url"] = quote(os.path.relpath(path, output), safe="/.-_")
                except (OSError, ValueError, subprocess.SubprocessError) as exc:
                    item["file_issue"] = str(exc)
        candidates.append(item)
    # Preserve selection; sorting is only presentation, not a new duration filter.
    candidates.sort(key=lambda item: (item["audit"]["palavra"], float(item["audit"]["dur_s"]), item["key"]))
    hashes = {}
    for item in candidates:
        if item["sha256"]:
            hashes.setdefault(item["sha256"], []).append(item["key"])
    for item in candidates:
        item["identical_files"] = [key for key in hashes.get(item["sha256"], []) if key != item["key"]]
    snapshot = {"audit_sha256": digest(audit), "candidates": candidates, "exclusions": exclusions}
    queue_id = hashlib.sha256(json.dumps(snapshot, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return {"schema_version": 1, "queue_id": queue_id, "created_at": datetime.now(timezone.utc).isoformat(),
            **snapshot, "summary": {"audit_rows": len(rows), "candidates": len(candidates),
                                    "excluded": len(exclusions), "words": len({r["audit"]["palavra"] for r in candidates}),
                                    "files_ready": sum(r["media_url"] is not None for r in candidates),
                                    "candidates_per_word": dict(Counter(r["audit"]["palavra"] for r in candidates))},
            "training_ready": False}


def write_queue(queue, output):
    output.mkdir(parents=True, exist_ok=True)
    script_dir = Path(__file__).resolve().parent
    template = (script_dir / "revisao_libras.html").read_text(encoding="utf-8")
    js = (script_dir / "revisao_libras.js").read_text(encoding="utf-8")
    # Escape inline JSON even when an untrusted video title includes HTML.
    data = json.dumps(queue, ensure_ascii=False).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    html = template.replace("/* REVIEW_APPLICATION */", js).replace("/* QUEUE_DATA */", data)
    (output / "index.html").write_text(html, encoding="utf-8")
    (output / "fila.json").write_text(json.dumps(queue, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output / "exclusoes.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(queue["exclusions"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=DEFAULT_BASE)
    parser.add_argument("--audit", type=Path)
    args = parser.parse_args(argv)
    output = args.base / "revisao-consultor"
    try:
        queue = build_queue(args.base, args.audit or args.base / "triagem.csv", output)
        write_queue(queue, output)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        parser.error(str(exc))
    print(json.dumps(queue["summary"], ensure_ascii=False, indent=2))
    print(f"Página local: {output / 'index.html'}")
    print("Sem downloads, sem recortes e sem aprovação automática para treino.")
    return int(queue["summary"]["files_ready"] != queue["summary"]["candidates"])


if __name__ == "__main__":
    raise SystemExit(main())
#!/usr/bin/env python3
"""Bounded YouTube discovery in quarantine; never feeds the reviewed-source catalog."""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import shutil
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import yt_dlp

from download_libras_gap_videos import common_options, file_sha256, normalize, safe_filename
from preparar_revisao_libras import FIELDS, MEDIA, build_queue, inspect_video, write_queue

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "external-data" / "libras-gap"
BATCHES = {
    "a": ["ruim", "dor", "precisar", "mostrar", "voltar", "buscar", "repetir",
          "perguntar", "ajudar", "esperar", "documento", "por favor"],
    "b": ["atendimento", "consulta", "protocolo", "senha", "agendar", "marcar"],
}
CONTEXT = {
    "ruim": '"ruim" "Libras" "sinal isolado"',
    "dor": '"dor" "Libras" "saúde"',
    "precisar": '"necessidade" "Libras" "sinal"',
    "mostrar": '"mostrar" "Libras" "documento"',
    "voltar": '"retornar" "Libras" "sinal"',
    "buscar": '"buscar" "Libras" "verbo"',
    "repetir": '"repetir" "Libras" "Goiás"',
    "perguntar": '"perguntar" "Libras" "verbo"',
    "ajudar": '"ajuda" "Libras" "sinal"',
    "esperar": '"aguardar" "Libras" "sinal"',
    "documento": '"documento" "Libras" "Goiás"',
    "por favor": '"por favor" "Libras" "Goiás"',
    "atendimento": '"atendimento ao público" "Libras" "sinal"',
    "consulta": '"consulta médica" "Libras" "sinal"',
    "protocolo": '"protocolo" "Libras" "atendimento"',
    "senha": '"senha" "Libras" "fila"',
    "agendar": '"agendar" "Libras" "consulta"',
    "marcar": '"marcar consulta" "Libras" "sinal"',
}
ALIASES = {"precisar": ["necessidade"], "voltar": ["retornar", "regressar"],
           "esperar": ["aguardar"], "ajudar": ["ajuda"]}


def now():
    return datetime.now(timezone.utc).isoformat()


def append(path, row):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"at": now(), **row}, ensure_ascii=False) + "\n")


def rows(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def duration_band(value):
    if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value <= 0:
        return "unknown"
    return "short" if value <= 6 else "medium" if value <= 20 else "long"


def title_gate(word, title):
    """Retrieval filter only: no inferred human/isolated/linguistic approval."""
    text = normalize(title or "")
    terms = [word, *ALIASES.get(word, [])]
    if not any(re.search(r"(?<!\w)" + re.escape(term) + r"(?!\w)", text) for term in terms):
        return "unmatched_title", []
    if re.search(r"\b(frase|frases|musica|musical|clipe musical|compilacao)\b", text):
        return "phrase_or_compilation", []
    compound = {
        "dor": r"\bdor(?:es)?\s+(?:de|na|no|nas|nos)\b",
        "buscar": r"\blupa\b|\bbuscar[- ](?:nos|me|voce)\b",
        "perguntar": r"\bperguntar[- ](?:me|nos|lhe)\b",
        "por favor": r"\bcom licenca\b",
        "documento": r"\bconta\b",
    }
    if word in compound and re.search(compound[word], text):
        return "compound_or_other_sense_separate_review", []
    if re.search(r"\s(?:x|versus|e|ou)\s|/|,", text):
        return "ambiguous_title_separate_review", []
    flags = ["titulo_nao_valida_rotulo", "humano_e_sinal_isolado_nao_verificados"]
    if not re.search(r"(?<!\w)" + re.escape(word) + r"(?!\w)", text):
        flags.append("termo_auxiliar_nao_equivalencia_confirmada")
    if word in BATCHES["b"]:
        flags.append("confirmar_sentido_de_atendimento")
    return "eligible_metadata", flags


def inventory(base):
    """Keep audit exclusions, media IDs, previous attempts and byte hashes globally."""
    ids, hashes, channels = set(), {}, Counter()
    audit = base / "triagem.csv"
    if audit.exists():
        with audit.open(newline="", encoding="utf-8-sig") as stream:
            for row in csv.DictReader(stream):
                ids.add(row["id"])
                channels[row["canal"]] += 1
    for media in base.rglob("*"):
        if media.is_file() and media.suffix.lower() in MEDIA:
            match = re.search(r"-([A-Za-z0-9_-]{11})$", media.stem)
            if match:
                ids.add(match[1])
            hashes.setdefault(file_sha256(media), str(media.relative_to(base)))
    for manifest in (base / "candidatos").glob("*/manifest.jsonl"):
        for row in rows(manifest):
            ids.add(row["id"])
            if row.get("status") == "candidate_downloaded":
                channels[row.get("channel", "")] += 1
    return ids, hashes, channels


def search(word, output, known, channels):
    queries = [f'"{word}" "Libras" "{kind}"' for kind in ("sinal", "dicionário", "sinalário")]
    queries.append(CONTEXT[word])
    candidates, seen = [], set()
    options = {**common_options(), "extract_flat": True, "skip_download": True}
    cache = output / "search" / (safe_filename(word) + ".jsonl")
    cached = {row["query"]: row for row in rows(cache) if row["status"] == "ok"}
    for i, query in enumerate(queries):
        if i == 3 and len(candidates) >= 3:
            break
        print(f"BUSCA {word}: {query}", flush=True)
        try:
            if query in cached:
                entries = cached[query]["entries"]
            else:
                with yt_dlp.YoutubeDL(options) as ydl:
                    info = ydl.extract_info("ytsearch10:" + query, download=False)
                entries = [{key: entry.get(key) for key in
                            ("id", "title", "duration", "channel", "channel_id", "live_status")}
                           for entry in (info or {}).get("entries", []) if entry]
                append(cache, {"query": query, "status": "ok", "entries": entries})
            for entry in entries:
                vid = entry.get("id")
                if not isinstance(vid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{11}", vid) or vid in seen:
                    continue
                seen.add(vid)
                status, flags = title_gate(word, entry.get("title"))
                band = duration_band(entry.get("duration"))
                if vid in known:
                    status = "already_known_keep_original_audit"
                elif entry.get("live_status") in {"is_live", "is_upcoming", "post_live"}:
                    status = "live_or_upcoming"
                elif band == "long":
                    status = "over_20_seconds"
                row = {**entry, "word": word, "query": query, "band": band, "flags": flags,
                       "status": status, "source_url": f"https://www.youtube.com/watch?v={vid}"}
                append(output / "discovery.jsonl", row)
                if status == "eligible_metadata":
                    candidates.append(row)
        except Exception as exc:
            append(cache, {"query": query, "status": "error", "error": str(exc)})
            print(f"FALHA BUSCA {word}: {exc}", flush=True)
    return sorted(candidates, key=lambda r: (
        {"short": 0, "medium": 1, "unknown": 2}[r["band"]],
        channels[r.get("channel") or ""], r.get("duration") or 999))


def download(candidate, output, hashes, medium_count=0):
    word, vid = candidate["word"], candidate["id"]
    folder = output / safe_filename(word)
    folder.mkdir(parents=True, exist_ok=True)
    row = {**candidate, "linguistic_status": "pendente", "permission_status": "pendente",
           "training_ready": False, "status": "failed"}
    options = {**common_options(), "format": "bv*[height<=720]+ba/b[height<=720]/bv*[height<=720]",
               "outtmpl": str(folder / (safe_filename(word) + "-" + vid + ".%(ext)s")),
               "merge_output_format": "mp4", "restrictfilenames": True}
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(row["source_url"], download=False)
            if not info or info.get("_type", "video") != "video":
                raise ValueError("Not an individual video")
            status, flags = title_gate(word, info.get("title"))
            band = duration_band(info.get("duration"))
            if status != "eligible_metadata" or band not in {"short", "medium"} or info.get("is_live"):
                raise ValueError(f"Metadata rejected: {status}, duration band={band}")
            if band == "medium" and medium_count >= 2:
                raise ValueError("Per-word medium quota reached")
            row.update(title=info.get("title"), channel=info.get("channel"),
                       channel_id=info.get("channel_id"), uploader_id=info.get("uploader_id"),
                       duration=info.get("duration"), license=info.get("license"),
                       description=info.get("description"), flags=flags, band=band)
            ydl.process_info(info)
        matches = [p for p in folder.glob("*" + vid + ".*") if p.suffix.lower() in MEDIA]
        if len(matches) != 1:
            raise ValueError(f"Expected one completed media file, got {len(matches)}")
        media = matches[0]
        technical = inspect_video(media)
        row.update(download_path=str(media.relative_to(output)), sha256=file_sha256(media), technical=technical)
        band = duration_band(technical["duration"])
        row["band"] = band
        if band not in {"short", "medium"} or (band == "medium" and medium_count >= 2):
            row["status"] = "technical_duration_rejected"
        elif row["sha256"] in hashes:
            row.update(status="duplicate_bytes", duplicate_of=hashes[row["sha256"]])
        else:
            row["status"] = "candidate_downloaded"
        hashes.setdefault(row["sha256"], str(media))
    except Exception as exc:
        row["error"] = str(exc)
    append(output / "manifest.jsonl", row)
    print(f"{word}: {vid} -> {row['status']}", flush=True)
    return row


def latest_records(output):
    return {row["id"]: row for row in rows(output / "manifest.jsonl")}


def refresh_selection(output):
    """Append metadata corrections, preserving the acquisition and original audit."""
    for row in latest_records(output).values():
        if row["status"] == "candidate_downloaded":
            status, _ = title_gate(row["word"], row["title"])
            if status != "eligible_metadata":
                append(output / "manifest.jsonl", {**row, "at": now(), "status": "metadata_rejected",
                       "previous_status": row["status"], "reason": status})


def completed(output):
    found = {}
    for row in latest_records(output).values():
        if row["status"] != "candidate_downloaded":
            continue
        media = (output / row["download_path"]).resolve()
        if not media.is_relative_to(output.resolve()) or not media.is_file() or file_sha256(media) != row["sha256"]:
            raise ValueError("Completed media missing or changed; refusing unsafe resume")
        found[row["id"]] = row
    return list(found.values())


def report(output, words, selected):
    # This is a NEW intake CSV, never a rewrite of the original 121-row audit.
    with (output / "triagem.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        for row in selected:
            writer.writerow(dict(zip(FIELDS, [safe_filename(row["word"]), row["id"],
                row["technical"]["duration"], row.get("channel") or "", row["title"],
                row.get("license") or "(nenhuma declarada)", "candidato",
                "|".join([row["band"], *row["flags"]]), "Nova prospecção; rótulo e permissão pendentes"])))
        for row in latest_records(output).values():
            if row["status"] in {"metadata_rejected", "technical_duration_rejected", "duplicate_bytes"}:
                writer.writerow(dict(zip(FIELDS, [safe_filename(row["word"]), row["id"],
                    row.get("technical", {}).get("duration", row.get("duration", 0)),
                    row.get("channel") or "", row["title"], row.get("license") or "(nenhuma declarada)",
                    "descartar", row["status"], row.get("reason", row.get("duplicate_of", ""))])))
    queue = build_queue(output, output / "triagem.csv", output / "revisao-consultor")
    write_queue(queue, output / "revisao-consultor")
    summary = {"created_at": now(), "candidates": len(selected),
               "per_word": {word: sum(r["word"] == word for r in selected) for word in words},
               "bands": dict(Counter(r["band"] for r in selected)),
               "attempt_statuses": dict(Counter(r["status"] for r in latest_records(output).values())),
               "training_ready": False, "permission_status": "pendente"}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch", choices=BATCHES, default="a")
    parser.add_argument("--search", action="store_true", help="Public metadata discovery only")
    parser.add_argument("--download", action="store_true", help="Discover and acquire candidates for review")
    parser.add_argument("--refresh", action="store_true", help="Offline metadata recheck and report; no new download")
    args = parser.parse_args(argv)
    words = BATCHES[args.batch]
    if not (args.search or args.download or args.refresh):
        print(json.dumps({"mode": "offline_plan", "words": words, "training_ready": False}, ensure_ascii=False))
        return 0
    if args.download and not all(shutil.which(cmd) for cmd in ("ffmpeg", "ffprobe")):
        parser.error("ffmpeg and ffprobe are required")
    output = BASE / "candidatos" / ("2026-09-12-lote-" + args.batch)
    output.mkdir(parents=True, exist_ok=True)
    # Exclusive lock prevents simultaneous invocations of this batch.
    lock = output / ".running"
    with lock.open("x") as stream:
        stream.write(now())
    try:
        refresh_selection(output)
        if args.refresh:
            report(output, words, completed(output))
            return 0
        known, hashes, channels = inventory(BASE)
        selected = completed(output)
        limit = 20 if args.batch == "a" else 10
        pools = {word: search(word, output, known, channels) for word in words
                 if sum(r["word"] == word for r in selected) < 3}
        if not args.download:
            print(json.dumps({word: len(pool) for word, pool in pools.items()}, ensure_ascii=False))
            return 0
        # Round-robin prevents early words consuming the entire batch.
        attempts = Counter(r["word"] for r in latest_records(output).values())
        while len(selected) < limit:
            progressed = False
            for word in words:
                count = sum(r["word"] == word for r in selected)
                if count >= 3 or len(selected) >= limit or attempts[word] >= 4:
                    continue
                medium_count = sum(r["word"] == word and r["band"] == "medium" for r in selected)
                pool = pools.get(word, [])
                pool.sort(key=lambda r: ({"short": 0, "medium": 1, "unknown": 2}[r["band"]],
                                          channels[r.get("channel") or ""]))
                while pool and (pool[0]["id"] in known or (pool[0]["band"] == "medium" and medium_count >= 2)):
                    pool.pop(0)
                if not pool:
                    continue
                candidate = pool.pop(0)
                known.add(candidate["id"])
                attempts[word] += 1
                progressed = True
                result = download(candidate, output, hashes, medium_count)
                if result["status"] == "candidate_downloaded":
                    selected.append(result)
                    channels[result.get("channel") or ""] += 1
                report(output, words, selected)
            if not progressed:
                break
        report(output, words, selected)
        return 0 if selected else 1
    finally:
        lock.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
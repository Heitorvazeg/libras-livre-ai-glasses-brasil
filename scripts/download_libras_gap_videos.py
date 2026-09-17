#!/usr/bin/env python3
"""Plan/download isolated LIBRAS clips from a reviewed catalog, never from search.

Default: offline plan. Use --download for explicit acquisition. Reviewer attestations
are required; this tool cannot prove linguistic correctness or legal permission.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import yt_dlp

ROOT = Path(__file__).resolve().parents[1]
OUT_BASE = ROOT / "external-data" / "libras-gap" / "curated"
MANIFEST = OUT_BASE / "manifest.jsonl"
CATALOG = ROOT / "scripts" / "libras_gap_sources.json"
WORDS = [
    "por favor", "obrigado", "esperar", "ajudar", "voltar", "nome", "documento",
    "mostrar", "precisar", "entrar", "sair", "buscar", "levar", "trazer", "escrever",
    "ler", "dor", "ruim", "porque", "quando", "onde", "como", "senha", "atendimento",
    "consulta", "protocolo", "agendar", "marcar", "sim", "nao", "ajuda", "medo",
    "urgente", "quero", "preciso", "sentar", "levantar", "cansado", "nervoso", "perguntar",
]


def normalize(value: str) -> str:
    return " ".join("".join(c for c in unicodedata.normalize("NFKD", value.lower())
                           if not unicodedata.combining(c)).split())


def safe_filename(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", normalize(value)).strip("-") or "item"


def https_url(value) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parsed = urlparse(value)
        return parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username and not parsed.password
    except ValueError:
        return False


def source_key(url: str) -> str:
    """Canonicalize YouTube aliases; retain other direct URLs unchanged."""
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    video_id = ""
    if host in {"youtube.com", "www.youtube.com", "m.youtube.com"}:
        video_id = (parse_qs(parsed.query).get("v") or [""])[0]
        if parsed.path.startswith(("/shorts/", "/embed/")):
            video_id = parsed.path.split("/")[2]
    elif host in {"youtu.be", "www.youtu.be"}:
        video_id = parsed.path.strip("/")
    if re.fullmatch(r"[\w-]{11}", video_id):
        return f"https://www.youtube.com/watch?v={video_id}"
    return url


class DownloadLogger:
    def debug(self, message):
        pass

    def warning(self, message):
        print(f"[WARN] {message}", file=sys.stderr)

    def error(self, message):
        pass


def common_options():
    options = {
        "quiet": True, "noprogress": True, "noplaylist": True,
        "socket_timeout": 15, "retries": 1, "fragment_retries": 1,
        "extractor_retries": 1, "logger": DownloadLogger(),
        "skip_unavailable_fragments": False,
    }
    if shutil.which("node"):
        options["js_runtimes"] = {"node": {"path": shutil.which("node")}}
    return options


def record_error(stage: str, source: str, error: Exception):
    print(f"[WARN] {stage}: {source}: {error}", file=sys.stderr)
    OUT_BASE.mkdir(parents=True, exist_ok=True)
    with (OUT_BASE / "errors.jsonl").open("a", encoding="utf-8") as fh:
        fh.write(json.dumps({"time": datetime.now(timezone.utc).isoformat(),
                             "stage": stage, "source": source, "error": str(error)},
                            ensure_ascii=False) + "\n")


def valid_video(path: Path, max_duration: int = 180) -> bool:
    if path.suffix.lower() not in {".mp4", ".mkv", ".webm", ".mov"}:
        return False
    if not path.is_file() or path.stat().st_size == 0:
        return False
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_type:format=duration", "-of", "json", str(path)],
            capture_output=True, text=True, timeout=20, check=True,
        )
        probe = json.loads(result.stdout)
        return bool(probe.get("streams")) and 0 < float(probe.get("format", {}).get("duration", 0)) <= max_duration
    except (OSError, subprocess.SubprocessError, ValueError):
        return False


def download_best_video(word: str, source_url: str, out_dir: Path, max_duration: int = 180) -> str:
    """Transport only: caller must enforce catalog eligibility before invoking."""
    if not https_url(source_url):
        return ""
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = safe_filename(word) + "-" + hashlib.sha256(source_key(source_url).encode()).hexdigest()[:16]
    options = {
        **common_options(),
        "format": "bv*[height<=720]+ba/b[height<=720]/bv*[height<=720]",
        "outtmpl": str(out_dir / f"{stem}.%(ext)s"),
        "merge_output_format": "mp4", "restrictfilenames": True, "writeinfojson": True,
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(source_url, download=False)
            if not info or info.get("_type", "video") != "video":
                raise ValueError("No individual video metadata returned")
            duration = info.get("duration")
            if info.get("is_live") or (duration is not None and not 0 < duration <= max_duration):
                raise ValueError("Live video or duration exceeds limit")
            ydl.process_info(info)
            filename = Path(ydl.prepare_filename(info))
        for candidate in dict.fromkeys([filename.with_suffix(".mp4"), filename]):
            if valid_video(candidate, max_duration):
                return str(candidate)
        raise ValueError("No completed video within duration limit")
    except Exception as exc:
        record_error("download", source_url, exc)
        return ""


def eligibility_errors(entry: dict, usage_scope: str) -> list[str]:
    """Require documented attestations; never infer them from public access."""
    errors = []
    for field in ("id", "word", "source_name", "source_label", "license", "conditions",
                  "reviewed_by", "reviewed_at"):
        if not isinstance(entry.get(field), str) or not entry[field].strip():
            errors.append(f"missing {field}")
    for field in ("source_url", "catalog_url", "label_evidence_url", "permission_evidence_url",
                  "format_evidence_url"):
        if not https_url(entry.get(field)):
            errors.append(f"invalid or missing {field}")
    for field, expected in (("review_status", "approved"), ("language", "Libras"),
                            ("clip_type", "isolated_sign"), ("performer_type", "human")):
        if entry.get(field) != expected:
            errors.append(f"{field} must be {expected}")
    for field in ("label_verified", "format_verified", "download_permitted", "training_permitted"):
        if entry.get(field) is not True:
            errors.append(f"{field} must be true")
    scopes = entry.get("allowed_usage_scopes")
    if not isinstance(scopes, list) or usage_scope not in scopes:
        errors.append(f"permission does not cover {usage_scope}")
    if entry.get("reviewed_at"):
        try:
            datetime.strptime(entry["reviewed_at"], "%Y-%m-%d")
        except (ValueError, TypeError):
            errors.append("reviewed_at must be YYYY-MM-DD")
    return errors


def load_catalog(path: Path, usage_scope: str):
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("schema_version") != 1 or not isinstance(data.get("entries"), list):
        raise ValueError("Expected schema_version=1 and entries list")
    eligible, rejected = [], []
    ids, urls = set(), {}
    known = {normalize(word) for word in WORDS}
    for index, entry in enumerate(data["entries"]):
        if not isinstance(entry, dict):
            raise ValueError(f"Entry {index} must be an object")
        errors = eligibility_errors(entry, usage_scope)
        word = entry.get("word")
        if not isinstance(word, str) or normalize(word) not in known:
            errors.append("word outside selected vocabulary")
        if errors:
            rejected.append({"index": index, "id": entry.get("id"), "word": word, "reasons": errors})
            continue
        if entry["id"] in ids:
            raise ValueError(f"Duplicate catalog id: {entry['id']}")
        ids.add(entry["id"])
        url = source_key(entry["source_url"])
        if url in urls:
            if urls[url] != normalize(word):
                raise ValueError("One media URL assigned to conflicting words")
            rejected.append({"index": index, "id": entry["id"], "word": word, "reasons": ["duplicate media URL"]})
            continue
        urls[url] = normalize(word)
        eligible.append(entry)
    return eligible, rejected


def fingerprint(entry: dict, usage_scope: str) -> str:
    return hashlib.sha256(json.dumps([entry, usage_scope], sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def file_sha256(path: Path) -> str:
    with path.open("rb") as fh:
        return hashlib.file_digest(fh, "sha256").hexdigest()


def completed_downloads(entries: list[dict], usage_scope: str, max_duration: int) -> set[str]:
    """Resume only current attestations with matching bytes, never the legacy CSV."""
    allowed = {fingerprint(entry, usage_scope) for entry in entries}
    completed = set()
    if not MANIFEST.exists():
        return completed
    with MANIFEST.open(encoding="utf-8") as fh:
        for line in fh:
            try:
                row = json.loads(line)
                key = row["catalog_fingerprint"]
                path = Path(row["download_path"])
                if (row["status"] == "downloaded_from_reviewed_source" and key in allowed
                        and path.resolve().is_relative_to(OUT_BASE.resolve())
                        and valid_video(path, max_duration) and file_sha256(path) == row["sha256"]):
                    completed.add(key)
            except (OSError, ValueError, KeyError, TypeError):
                continue
    return completed


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--words", nargs="+", help="Only these vocabulary words")
    parser.add_argument("--per-word", type=positive_int, default=5, help="Target documented clips, not signers")
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--usage-scope", choices=["research_noncommercial", "product"], default="research_noncommercial")
    parser.add_argument("--download", action="store_true", help="Download; default only plans offline")
    parser.add_argument("--max-attempts", type=positive_int, default=5, help="Per word")
    parser.add_argument("--max-duration", type=positive_int, default=180, help="Seconds")
    args = parser.parse_args(argv)
    if args.download and (not shutil.which("ffmpeg") or not shutil.which("ffprobe")):
        parser.error("ffmpeg and ffprobe are required")
    known = {normalize(word) for word in WORDS}
    requested = {normalize(word) for word in args.words} if args.words else known
    if requested - known:
        parser.error(f"Unknown words: {sorted(requested - known)}")
    try:
        entries, rejected = load_catalog(args.catalog, args.usage_scope)
    except (OSError, ValueError) as exc:
        parser.error(f"Invalid catalog: {exc}")
    completed = completed_downloads(entries, args.usage_scope, args.max_duration) if args.download else set()
    OUT_BASE.mkdir(parents=True, exist_ok=True)
    report = {"mode": "download" if args.download else "plan", "usage_scope": args.usage_scope,
              "created_at": datetime.now(timezone.utc).isoformat(), "rejected_entries": rejected,
              "words": [], "training_ready": False}
    successes = failures = 0
    for word in WORDS:
        if normalize(word) not in requested:
            continue
        candidates = [entry for entry in entries if normalize(entry["word"]) == normalize(word)]
        count = sum(fingerprint(entry, args.usage_scope) in completed for entry in candidates)
        attempts = 0
        for entry in candidates if args.download else []:
            if count >= args.per_word or attempts >= args.max_attempts:
                break
            key = fingerprint(entry, args.usage_scope)
            if key in completed:
                continue
            attempts += 1
            print(f"- {word}: documented source {entry['id']}", flush=True)
            downloaded = download_best_video(word, source_key(entry["source_url"]),
                                             OUT_BASE / safe_filename(word) / key[:16], args.max_duration)
            row = {"word": word, "catalog_entry": entry, "catalog_fingerprint": key,
                   "usage_scope": args.usage_scope, "download_path": downloaded,
                   "status": "downloaded_from_reviewed_source" if downloaded else "failed",
                   "sha256": file_sha256(Path(downloaded)) if downloaded else "",
                   "created_at": datetime.now(timezone.utc).isoformat(), "training_ready": False}
            with MANIFEST.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            if downloaded:
                count += 1
                successes += 1
        available = count if args.download else len(candidates)
        missing = max(0, args.per_word - available)
        failures += bool(missing)
        report["words"].append({"word": word, "target": args.per_word,
                                "eligible_sources": len(candidates), "source_ids": [e["id"] for e in candidates],
                                "downloaded": count if args.download else None, "missing": missing,
                                "status": "gap" if missing else "target_met"})
        print(f"- {word}: {'downloaded' if args.download else 'eligible'}={available}/{args.per_word}; gap={missing}")
    report_path = OUT_BASE / ("download-report.json" if args.download else "plan.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\nSummary: downloaded={successes}, below_target={failures}, rejected_entries={len(rejected)}")
    print(f"Report: {report_path}; no automatic training integration.")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

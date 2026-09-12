#!/usr/bin/env python3
"""Download a small set of public LIBRAS videos for the highest-priority lexical gaps.

This script searches public YouTube results for a curated list of gaps identified in the
project documentation, and downloads the best available MP4 for each word.

It is intentionally narrow and selective: it does not download whole datasets, only
public clips relevant to the current deficit in connected/action words and support terms.
"""

from __future__ import annotations

import csv
import os
import re
import sys
from pathlib import Path
from typing import Iterable, List, Tuple

import yt_dlp

ROOT = Path(__file__).resolve().parents[1]
OUT_BASE = ROOT / "external-data" / "libras-gap"
MANIFEST = OUT_BASE / "manifest.csv"

WORDS: List[Tuple[str, str, str, int]] = [
    ("por favor", "conectivo/solicitação", "alto", 1),
    ("obrigado", "conectivo/agradecimento", "alto", 1),
    ("esperar", "verbo operacional", "alto", 1),
    ("ajudar", "verbo assistencial", "alto", 1),
    ("voltar", "verbo de deslocamento", "alto", 1),
    ("nome", "institutional", "alto", 1),
    ("documento", "institutional", "alto", 1),
    ("mostrar", "verbo de instrução", "alto", 1),
    ("precisar", "verbo de necessidade", "alto", 1),
    ("entrar", "verbo de movimento", "alto", 1),
    ("sair", "verbo de movimento", "alto", 1),
    ("buscar", "verbo operacional", "alto", 1),
    ("levar", "verbo operacional", "alto", 1),
    ("trazer", "verbo operacional", "alto", 1),
    ("escrever", "verbo de documentação", "alto", 1),
    ("ler", "verbo de leitura", "alto", 1),
    ("dor", "estado/urgência", "alto", 1),
    ("ruim", "estado/urgência", "alto", 1),
    ("porque", "conectivo causal", "alto", 1),
    ("quando", "conectivo temporal", "alto", 1),
    ("onde", "conectivo de localidade", "alto", 1),
    ("como", "conectivo de modo", "alto", 1),
    ("senha", "institutional", "alto", 1),
    ("atendimento", "institutional", "alto", 1),
    ("consulta", "institutional", "alto", 1),
    ("protocolo", "institutional", "alto", 1),
    ("agendar", "institutional", "alto", 1),
    ("marcar", "institutional", "alto", 1),
    ("sim", "resposta simples", "alto", 1),
    ("nao", "resposta simples", "alto", 1),
    ("ajuda", "apoio/solicitação", "alto", 1),
    ("medo", "estado emocional", "alto", 1),
    ("urgente", "estado/urgência", "alto", 1),
    ("quero", "verbo de desejo", "médio", 1),
    ("preciso", "verbo de necessidade", "médio", 1),
    ("mostrar", "verbo de instrução", "médio", 1),
    ("sentar", "verbo de ação", "médio", 1),
    ("levantar", "verbo de ação", "médio", 1),
    ("cansado", "estado", "médio", 1),
    ("nervoso", "estado", "médio", 1),
    ("perguntar", "verbo comunicativo", "médio", 1),
]


def safe_filename(value: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return cleaned or "item"


def youtube_search(query: str, max_results: int = 5):
    ydl_opts = {
        "quiet": True,
        "noplaylist": True,
        "skip_download": True,
        "extract_flat": False,
        "no_warnings": True,
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)
    entries = info.get("entries", []) if isinstance(info, dict) else []
    return [
        {
            "title": e.get("title"),
            "url": e.get("webpage_url") or e.get("url"),
            "id": e.get("id"),
            "uploader": e.get("uploader"),
            "duration": e.get("duration") or 0,
        }
        for e in entries
        if e
    ]


def download_best_video(word: str, source_url: str, out_dir: Path) -> str:
    out_dir.mkdir(parents=True, exist_ok=True)
    file_stem = safe_filename(word)
    ydl_opts = {
        "quiet": True,
        "noplaylist": True,
        "format": "best[ext=mp4]/best",
        "outtmpl": str(out_dir / f"{file_stem}.%(ext)s"),
        "merge_output_format": "mp4",
        "restrictfilenames": True,
        "no_warnings": True,
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(source_url, download=True)
        title = info.get("title") if isinstance(info, dict) else word
        pathname = out_dir / f"{file_stem}.mp4"
        if not pathname.exists():
            # Sometimes the YDL output may be named differently depending on metadata.
            candidates = list(out_dir.glob(f"{file_stem}.*"))
            if candidates:
                pathname = candidates[0]
        return str(pathname)
    except Exception as exc:  # noqa: BLE001
        print(f"[WARN] Failed to download {word}: {exc}", file=sys.stderr)
        return ""


def ensure_manifest():
    OUT_BASE.mkdir(parents=True, exist_ok=True)
    if not MANIFEST.exists():
        with MANIFEST.open("w", newline="", encoding="utf-8") as fh:
            writer = csv.writer(fh)
            writer.writerow([
                "word",
                "category",
                "priority",
                "query",
                "title",
                "source_url",
                "download_path",
                "status",
            ])


def append_manifest(row: dict):
    with MANIFEST.open("a", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow([
            row["word"],
            row["category"],
            row["priority"],
            row["query"],
            row["title"],
            row["source_url"],
            row["download_path"],
            row["status"],
        ])


def main() -> int:
    ensure_manifest()
    for word, category, priority, _ in WORDS:
        print(f"\n=== {word} ({category}, {priority}) ===")
        queries = [
            f"Libras {word}",
            f"sinal de {word} em Libras",
            f"dicionario Libras {word}",
            f"Libras {word} palavra",
        ]
        found_any = False
        for query in queries:
            results = youtube_search(query, max_results=3)
            if not results:
                continue
            found_any = True
            result = results[0]
            if not result.get("url"):
                continue
            out_dir = OUT_BASE / safe_filename(word)
            downloaded = download_best_video(word, result["url"], out_dir)
            title = result.get("title") or ""
            if downloaded:
                append_manifest({
                    "word": word,
                    "category": category,
                    "priority": priority,
                    "query": query,
                    "title": title,
                    "source_url": result["url"],
                    "download_path": downloaded,
                    "status": "downloaded",
                })
                print(f"- OK: {word} -> {downloaded}")
                break
            else:
                append_manifest({
                    "word": word,
                    "category": category,
                    "priority": priority,
                    "query": query,
                    "title": title,
                    "source_url": result["url"],
                    "download_path": "",
                    "status": "failed",
                })
        if not found_any:
            print(f"- no YouTube result found for {word}")
            append_manifest({
                "word": word,
                "category": category,
                "priority": priority,
                "query": "; ".join(q for q in queries),
                "title": "",
                "source_url": "",
                "download_path": "",
                "status": "not_found",
            })
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

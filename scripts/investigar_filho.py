"""Inspeção descritiva FILHO/MINDS: não infere classes, não treina nem reextrai.

Lê 160 pares locais (8 pessoas × 4 sinais × 5 repetições). Preserva hashes,
mede ausência bruta e após imputação existente, decodifica vídeos e prepara
galeria privada para revisão HUMANA. Não alinha frames NPY a frames do vídeo:
o extrator legado descarta poses sem guardar índices. Não pareia predições LOSO.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import html
import json
import os
from pathlib import Path
import platform
import sys
from urllib.parse import quote

import numpy as np

RAIZ = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RAIZ / "computer-vision-model/treino"))
from dados import imputar_maos, maos_ausentes, recentrar_z

PESSOAS = ("M01", "M02", "M05", "M06", "M08", "M10", "M11", "M12")
SINAIS = ("filho", "medo", "aproveitar", "vacina")


def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def lacunas(ausente):
    """Conta ausência fora/dentro da primeira e última detecção, sem semântica."""
    a = np.asarray(ausente, dtype=bool)
    presentes = np.flatnonzero(~a)
    runs = np.diff(np.flatnonzero(np.diff(np.r_[False, a, False]))).tolist()[::2]
    if presentes.size:
        ini, fim = int(presentes[0]), int(presentes[-1])
        interna = a[ini:fim + 1]
        r = np.diff(np.flatnonzero(np.diff(np.r_[False, interna, False]))).tolist()[::2]
        return {"ausentes": int(a.sum()), "total": len(a), "inicio": ini,
                "fim": len(a) - fim - 1, "internos": int(interna.sum()),
                "maior_interna": max(r, default=0), "maior": max(runs, default=0)}
    return {"ausentes": len(a), "total": len(a), "inicio": None, "fim": None,
            "internos": None, "maior_interna": None, "maior": len(a)}


def quantis(x):
    x = np.asarray(x)
    return [float(v) for v in np.percentile(x, [5, 50, 95])] if x.size else None


def medir(seq):
    if seq.ndim != 3 or seq.shape[1:] != (57, 3) or not len(seq):
        raise ValueError("exige NPY não vazio (T,57,3)")
    if seq.dtype != np.float32 or not np.isfinite(seq).all():
        raise ValueError("exige float32 finito")
    orig = seq.copy()
    masks = maos_ausentes(seq)
    # Mesma ordem e limite da leitura do candidato; não inclui a segunda
    # imputação/reamostragem do grafo e não mede efeito em logits.
    pos = imputar_maos(recentrar_z(seq), 5)
    depois = maos_ausentes(pos)
    maos = {}
    for i, (nome, start, pose) in enumerate((("esquerda", 15, 11), ("direita", 36, 12))):
        valid = ~masks[i]
        pares = valid[1:] & valid[:-1]
        wrist = seq[:, start, :2]
        passos = np.linalg.norm(np.diff(wrist, axis=0)[pares], axis=1)
        maos[nome] = {"bruta": lacunas(masks[i]), "apos_imputacao_leitura": lacunas(depois[i]),
            "preenchidos": int((masks[i] & ~depois[i]).sum()),
            "punho_xy_q05_q50_q95": [quantis(wrist[valid, j]) for j in (0, 1)],
            "passo_xy_pares_presentes_q05_q50_q95": quantis(passos),
            "pares_presentes": int(pares.sum()),
            "distancia_punho_mao_pose_q05_q50_q95": quantis(
                np.linalg.norm(wrist[valid] - seq[valid, pose, :2], axis=1))}
    assert np.array_equal(seq, orig)
    return {"frames": len(seq), "maos": maos,
            "nenhuma_bruta": lacunas(masks[0] & masks[1]),
            "nenhuma_apos_imputacao_leitura": lacunas(depois[0] & depois[1]),
            "distancia_ombros_xy_q05_q50_q95": quantis(np.linalg.norm(seq[:, 7, :2] - seq[:, 8, :2], axis=1))}


def inventario(videos, landmarks):
    items = []
    for sinal in SINAIS:
        for pessoa in PESSOAS:
            for rep in range(1, 6):
                stem = f"pessoa{pessoa}_sinal-{sinal}_rep{rep:02d}"
                v, a = videos / (stem + ".mp4"), landmarks / (stem + ".npy")
                if not v.is_file() or not a.is_file():
                    raise ValueError(f"par ausente: {stem}")
                items.append((pessoa, sinal, stem, v, a))
    return items


def video_e_contato(path, destino):
    import cv2
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise ValueError(f"vídeo ilegível: {path}")
    fps = float(cap.get(cv2.CAP_PROP_FPS))
    header = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    thumbs, dims = [], set()
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            h, w = frame.shape[:2]
            dims.add((w, h))
            small = cv2.resize(frame, (240, max(1, round(h * 240 / w))))
            thumbs.append(small)
    finally:
        cap.release()
    if not thumbs or len(dims) != 1 or not np.isfinite(fps) or fps <= 0:
        raise ValueError(f"vídeo vazio/geometria variável/fps inválido: {path}")
    indices = np.linspace(0, len(thumbs) - 1, 6).round().astype(int)
    tiles = []
    for ix in indices:
        tile = cv2.copyMakeBorder(thumbs[ix], 24, 0, 0, 0, cv2.BORDER_CONSTANT)
        cv2.putText(tile, f"frame {ix}", (6, 17), cv2.FONT_HERSHEY_SIMPLEX, .45, (255, 255, 255), 1)
        tiles.append(tile)
    if not cv2.imwrite(str(destino), np.concatenate(tiles, axis=1)):
        raise ValueError("falha ao gravar contato")
    return {"frames_decodificados": len(thumbs), "frames_header": header, "fps_header": fps,
            "dimensoes": list(next(iter(dims))), "duracao_estimada_s": len(thumbs) / fps,
            "indices_contato_video": indices.tolist()}


def resumir(rows):
    grupos = []
    for sinal in SINAIS:
        for pessoa in PESSOAS:
            rs = [r for r in rows if r["pessoa"] == pessoa and r["sinal"] == sinal]
            n = sum(r["medidas"]["frames"] for r in rs)
            g = {"pessoa": pessoa, "sinal": sinal, "clipes": len(rs), "frames_npy": n,
                 "frames_video": sum(r["video"]["frames_decodificados"] for r in rs),
                 "duracao_mediana_s": float(np.median([r["video"]["duracao_estimada_s"] for r in rs]))}
            for key in ("nenhuma_bruta", "nenhuma_apos_imputacao_leitura"):
                g[key + "_pct"] = 100 * sum(r["medidas"][key]["ausentes"] for r in rs) / n
            for side in ("esquerda", "direita"):
                ms = [r["medidas"]["maos"][side] for r in rs]
                g[side + "_presente_pct"] = 100 * (1 - sum(m["bruta"]["ausentes"] for m in ms) / n)
                g[side + "_internos"] = sum(m["bruta"]["internos"] or 0 for m in ms)
                g[side + "_preenchidos"] = sum(m["preenchidos"] for m in ms)
            grupos.append(g)
    return grupos


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--videos", type=Path, default=RAIZ / "computer-vision-model/PoC/data/raw")
    ap.add_argument("--landmarks", type=Path, default=RAIZ / "computer-vision-model/PoC/data/landmarks")
    ap.add_argument("--saida", required=True, type=Path)
    args = ap.parse_args()
    out = args.saida.resolve()
    if out.exists() or not out.is_relative_to(RAIZ / "experimentos-privados"):
        ap.error("saída deve ser nova dentro de experimentos-privados")
    items = inventario(args.videos.resolve(), args.landmarks.resolve())
    out.mkdir(parents=True)
    (out / "contatos").mkdir()
    rows, cards = [], []
    for pessoa, sinal, stem, v, a in items:
        hv, ha = sha(v), sha(a)
        medidas = medir(np.load(a, allow_pickle=False))
        contato = Path("contatos") / (stem + ".jpg")
        video = video_e_contato(v, out / contato)
        if hv != sha(v) or ha != sha(a):
            raise ValueError(f"insumo alterado durante leitura: {stem}")
        row = {"id": stem, "pessoa": pessoa, "sinal": sinal,
               "video_path": str(v), "video_sha256": hv, "npy_path": str(a), "npy_sha256": ha,
               "sidecar_video_presente": v.with_suffix(v.suffix + ".proveniencia.json").exists(),
               "sidecar_npy_presente": a.with_suffix(a.suffix + ".proveniencia.json").exists(),
               "medidas": medidas, "video": video,
               "diferenca_contagens_video_npy": video["frames_decodificados"] - medidas["frames"]}
        rows.append(row)
        href = html.escape(quote(os.path.relpath(v, out), safe="/"), quote=True)
        cards.append(f'<section><h2>{stem}</h2><a href="{href}">Abrir vídeo original</a>'
                     f'<img loading="lazy" src="{contato.as_posix()}" alt="Seis frames uniformes de {stem}">'
                     f'<p>NPY: {medidas["frames"]} frames; vídeo: {video["frames_decodificados"]}.'
                     ' Sem sobreposição de landmarks ou julgamento linguístico.</p></section>')
    groups = resumir(rows)
    import cv2
    report = {"schema": 1, "script_sha256": sha(Path(__file__)),
              "dados_py_sha256": sha(RAIZ / "computer-vision-model/treino/dados.py"),
              "ambiente": {"python": platform.python_version(), "numpy": np.__version__, "opencv": cv2.__version__},
              "limites": ["Inspeção local, sem pareamento com erros históricos por repetição.",
                  "57 pontos interpretados pelo layout atual; sidecars ausentes não são reconstruídos.",
                  "Ausência de bloco não distingue repouso, oclusão, enquadramento ou falha do detector.",
                  "Contagem vídeo-NPY não prova quais frames foram descartados nem identidade da extração.",
                  "Passos em XY são por frame NPY consecutivo, não velocidade física; sem PTS legado.",
                  "Punho mão-pose é concordância entre detectores, não erro contra verdade de referência.",
                  "Galeria com amostragem uniforme não é revisão visual/linguística concluída.",
                  "Sem inferência, retreino, ajuste de limiar ou avaliação de frases."],
              "grupos": groups, "clipes": rows}
    (out / "relatorio.json").write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    with (out / "grupos.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(groups[0])); w.writeheader(); w.writerows(groups)
    fields = ["id", "video_sha256", "revisor", "data", "rotulo_revisado", "variante_observada",
              "mao_fora_quadro", "oclusao", "inicio_fim_sinal", "observacoes"]
    with (out / "revisao-humana.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields); w.writeheader()
        w.writerows({"id": r["id"], "video_sha256": r["video_sha256"]} for r in rows)
    (out / "galeria.html").write_text('<!doctype html><html lang="pt-BR"><meta charset="utf-8">'
        '<title>FILHO — revisão privada</title><style>body{font:16px sans-serif;background:#101827;'
        'color:#e5e7eb;max-width:1500px;margin:32px auto;padding:16px}a{color:#7dd3fc}'
        'img{display:block;width:100%;margin-top:12px}section{padding:20px;border-bottom:1px solid #475569}</style>'
        '<h1>FILHO — material privado para revisão humana</h1><p>Não são predições do modelo.'
        ' Contatos amostrados uniformemente; abrir os vídeos completos. Campos de revisão estão vazios.'
        ' Não publicar imagens nem inferir variante linguística das métricas.</p>' + ''.join(cards) + '</html>', encoding="utf-8")
    print(f"{len(rows)} clipes inspecionados; relatório e galeria privados: {out}")
    for g in groups:
        if g["sinal"] == "filho":
            print(json.dumps(g, ensure_ascii=False))


if __name__ == "__main__":
    main()
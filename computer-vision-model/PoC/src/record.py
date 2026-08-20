"""§5.1 — Gravação de clipes da PoC.

Cada clipe é UM sinal isolado, gravado sob demanda:
  - a câmera abre em pré-visualização;
  - ESPAÇO começa a gravar; ESPAÇO de novo encerra e salva;
  - o arquivo é salvo como data/raw/pessoaNN_sinal-XXX_repNN.mp4, com a
    numeração de repetição avançando sozinha a partir dos arquivos já existentes.

O nome do arquivo carrega pessoa/sinal/repetição — é a única fonte de metadados
(não há planilha separada). Ver README §4.

Uso:
    python src/record.py --pessoa 03 --sinal ajuda
    python src/record.py --pessoa 03 --sinal ajuda --camera 1
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

import cv2

RAW_DIR = Path(__file__).resolve().parent.parent / "data" / "raw"


def _next_rep(raw_dir: Path, pessoa: str, sinal: str) -> int:
    """Descobre a próxima repetição olhando os clipes já gravados dessa pessoa/sinal."""
    padrao = re.compile(rf"pessoa{pessoa}_sinal-{re.escape(sinal)}_rep(\d+)\.mp4$")
    maior = 0
    for f in raw_dir.glob(f"pessoa{pessoa}_sinal-{sinal}_rep*.mp4"):
        m = padrao.search(f.name)
        if m:
            maior = max(maior, int(m.group(1)))
    return maior + 1


def gravar(pessoa: str, sinal: str, camera: int = 0) -> None:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    cap = cv2.VideoCapture(camera)
    if not cap.isOpened():
        raise RuntimeError(f"Não consegui abrir a câmera {camera}.")

    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")

    gravando = False
    writer = None
    rep = _next_rep(RAW_DIR, pessoa, sinal)

    print(f"[record] pessoa={pessoa} sinal={sinal} — próxima repetição: {rep:02d}")
    print("[record] ESPAÇO = iniciar/parar gravação | q = sair")

    while True:
        ok, frame = cap.read()
        if not ok:
            break

        vis = frame.copy()
        estado = f"REC rep{rep:02d}" if gravando else f"pronto (rep{rep:02d})"
        cor = (0, 0, 255) if gravando else (0, 200, 0)
        cv2.putText(vis, f"{pessoa}/{sinal}  {estado}", (12, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, cor, 2)
        cv2.imshow("record — ESPACO grava/para, q sai", vis)

        if gravando and writer is not None:
            writer.write(frame)

        tecla = cv2.waitKey(1) & 0xFF
        if tecla == ord(" "):
            if not gravando:
                destino = RAW_DIR / f"pessoa{pessoa}_sinal-{sinal}_rep{rep:02d}.mp4"
                writer = cv2.VideoWriter(str(destino), fourcc, fps, (w, h))
                gravando = True
                print(f"[record] gravando -> {destino.name}")
            else:
                gravando = False
                if writer is not None:
                    writer.release()
                    writer = None
                print(f"[record] salvo rep{rep:02d}")
                rep += 1
        elif tecla == ord("q"):
            break

    if writer is not None:
        writer.release()
    cap.release()
    cv2.destroyAllWindows()


def main() -> None:
    ap = argparse.ArgumentParser(description="Gravação de clipes da PoC (§5.1).")
    ap.add_argument("--pessoa", required=True, help="id da pessoa, ex.: 03")
    ap.add_argument("--sinal", required=True, help="rótulo do sinal, ex.: ajuda")
    ap.add_argument("--camera", type=int, default=0, help="índice da câmera (default 0)")
    args = ap.parse_args()
    # normaliza para 2 dígitos quando for número puro
    pessoa = args.pessoa.zfill(2) if args.pessoa.isdigit() else args.pessoa
    gravar(pessoa, args.sinal, args.camera)


if __name__ == "__main__":
    main()

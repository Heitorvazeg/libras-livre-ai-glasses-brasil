"""§5.1 — Gravação de clipes da PoC.

Cada clipe é UM sinal isolado, gravado sob demanda:
  - a câmera abre em pré-visualização;
  - ESPAÇO começa a gravar; ESPAÇO de novo encerra e salva;
  - D descarta o último clipe salvo (erro de execução, sinal saiu errado);
  - Q encerra a sessão.

O arquivo é salvo como data/raw/pessoaNN_sinal-XXX_repNN.mp4, com a numeração de
repetição avançando sozinha a partir dos arquivos já existentes. O nome do
arquivo carrega pessoa/sinal/repetição — é a única fonte de metadados (não há
planilha separada). Ver README §4.

Uso:
    python src/record.py --pessoa 03 --sinal ajuda
    python src/record.py --pessoa 03 --sinal ajuda --camera 1
"""
from __future__ import annotations

import argparse
import re
import time
from pathlib import Path

import cv2

from config import load_config

TECLA_GRAVAR, TECLA_DESCARTAR, TECLA_SAIR = ord(" "), ord("d"), ord("q")


def _next_rep(raw_dir: Path, pessoa: str, sinal: str) -> int:
    """Descobre a próxima repetição olhando os clipes já gravados dessa pessoa/sinal."""
    padrao = re.compile(rf"pessoa{re.escape(pessoa)}_sinal-{re.escape(sinal)}_rep(\d+)\.mp4$")
    maior = 0
    for f in raw_dir.glob(f"pessoa{pessoa}_sinal-{sinal}_rep*.mp4"):
        m = padrao.search(f.name)
        if m:
            maior = max(maior, int(m.group(1)))
    return maior + 1


def gravar(pessoa: str, sinal: str, camera: int, cfg) -> None:
    raw_dir = cfg.path("raw_videos")
    raw_dir.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(camera)
    if not cap.isOpened():
        raise SystemExit(f"Não consegui abrir a câmera {camera}. Tente outro --camera.")

    fps_cam = cap.get(cv2.CAP_PROP_FPS)
    fps = fps_cam if 5.0 <= fps_cam <= 120.0 else float(cfg.gravacao.get("fps_fallback", 30))
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    dur_min = float(cfg.gravacao.get("duracao_minima_s", 0.5))
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")

    alvo = cfg.repeticoes_por_sinal
    rep = _next_rep(raw_dir, pessoa, sinal)
    gravando, writer, destino, t0 = False, None, None, 0.0
    ultimo_salvo: Path | None = None

    print(f"[record] pessoa={pessoa} sinal={sinal} — próxima repetição: {rep:02d} (alvo: {alvo})")
    print(f"[record] câmera {camera}: {w}x{h} @ {fps:.0f} fps -> {raw_dir}")
    print("[record] confirme o consentimento do participante antes de gravar "
          "(docs/consentimento.md).")
    print("[record] ESPAÇO = iniciar/parar | D = descartar último | Q = sair")

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                print("[record] a câmera parou de entregar frames.")
                break

            if gravando and writer is not None:
                writer.write(frame)

            vis = frame.copy()
            if gravando:
                estado, cor = f"REC rep{rep:02d}  {time.perf_counter() - t0:4.1f}s", (0, 0, 255)
            else:
                estado, cor = f"pronto (rep{rep:02d} de {alvo})", (0, 200, 0)
            cv2.putText(vis, f"{pessoa} / {sinal}   {estado}", (12, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, cor, 2)
            cv2.putText(vis, "ESPACO grava/para   D descarta   Q sai", (12, h - 16),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (220, 220, 220), 1)
            cv2.imshow("record — PoC Libras", vis)

            tecla = cv2.waitKey(1) & 0xFF
            if tecla == TECLA_GRAVAR:
                if not gravando:
                    destino = raw_dir / f"pessoa{pessoa}_sinal-{sinal}_rep{rep:02d}.mp4"
                    writer = cv2.VideoWriter(str(destino), fourcc, fps, (w, h))
                    if not writer.isOpened():
                        raise SystemExit(f"não consegui abrir o arquivo de saída {destino}")
                    gravando, t0 = True, time.perf_counter()
                    print(f"[record] gravando -> {destino.name}")
                else:
                    duracao = time.perf_counter() - t0
                    gravando = False
                    if writer is not None:
                        writer.release()
                        writer = None
                    if duracao < dur_min:
                        print(f"[record] ⚠ clipe de {duracao:.1f}s (< {dur_min}s) — "
                              "provavelmente curto demais; use D para descartar e refazer.")
                    print(f"[record] salvo rep{rep:02d} ({duracao:.1f}s)")
                    ultimo_salvo, rep = destino, rep + 1
                    if rep > alvo:
                        print(f"[record] alvo de {alvo} repetições atingido para {sinal}.")
            elif tecla == TECLA_DESCARTAR and not gravando:
                if ultimo_salvo is not None and ultimo_salvo.exists():
                    ultimo_salvo.unlink()
                    print(f"[record] descartado {ultimo_salvo.name}")
                    rep, ultimo_salvo = rep - 1, None
                else:
                    print("[record] nada para descartar.")
            elif tecla == TECLA_SAIR:
                break
    finally:
        # garante liberar câmera/arquivo mesmo em Ctrl+C ou erro no meio da gravação —
        # sem isso a câmera fica presa para o próximo processo e o .mp4 pode ficar
        # sem o rodapé do container (moov atom), tornando-o ilegível.
        if writer is not None:
            writer.release()
            print("[record] ⚠ interrompido durante uma gravação — o último clipe pode estar incompleto.")
        cap.release()
        cv2.destroyAllWindows()

    total = len(list(raw_dir.glob(f"pessoa{pessoa}_sinal-{sinal}_rep*.mp4")))
    print(f"[record] fim: {total} clipe(s) de {pessoa}/{sinal} em {raw_dir}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Gravação de clipes da PoC (§5.1).")
    ap.add_argument("--pessoa", required=True, help="id da pessoa, ex.: 03")
    ap.add_argument("--sinal", required=True, help="rótulo do sinal, ex.: ajuda")
    ap.add_argument("--camera", type=int, default=0, help="índice da câmera (default 0)")
    ap.add_argument("--forcar", action="store_true",
                    help="grava mesmo se o sinal não estiver no vocabulário do config.yaml")
    args = ap.parse_args()

    cfg = load_config()
    # normaliza para 2 dígitos quando for número puro
    pessoa = args.pessoa.zfill(2) if args.pessoa.isdigit() else args.pessoa
    if "_" in pessoa or "_" in args.sinal:
        raise SystemExit("pessoa/sinal não podem conter '_': o caractere separa os campos do nome.")
    if args.sinal not in cfg.vocabulario and not args.forcar:
        raise SystemExit(
            f"sinal {args.sinal!r} não está no vocabulário do config.yaml.\n"
            f"Vocabulário: {', '.join(cfg.vocabulario)}\n"
            "Corrija o nome, acrescente o sinal ao config.yaml, ou use --forcar.")
    gravar(pessoa, args.sinal, args.camera, cfg)


if __name__ == "__main__":
    main()

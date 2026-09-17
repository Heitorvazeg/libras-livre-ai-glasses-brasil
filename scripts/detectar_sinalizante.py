#!/usr/bin/env python3
"""Mede, por clipe, quanto dele tem de fato uma pessoa sinalizando.

POR QUE ISTO EXISTE. A triagem responde "tem formato de sinal isolado?" pela
duração e pelo título. Nenhum dos dois vê o conteúdo: um clipe de 5 s pode ser
cinco segundos de cartela com o logo da instituição, e um de 14 s pode ter o
sinal inteiro entre 6,5 s e 9,3 s. Esta medição responde a pergunta que o
`manifest.jsonl` do coletor deixa explicitamente em aberto na flag
`humano_e_sinal_isolado_nao_verificados`.

O QUE ELA DECIDE, E O QUE NÃO DECIDE. Ela decide se há pessoa enquadrada e mão
visível, e ONDE no clipe isso acontece — ou seja, entrega a janela de recorte
pronta em vez de deixar o consultor procurá-la. Ela **não** decide se o sinal é
o sinal certo: para isso não há automação, e a fila de revisão existe por causa
disso.

USA O MESMO DETECTOR DO PIPELINE. MediaPipe Holistic com a mesma configuração de
`PoC/config.yaml`. Medir com um detector e extrair com outro produziria uma
janela útil que não corresponde ao que o `extract.py` vai conseguir aproveitar.

A JANELA É O MAIOR TRECHO CONTÍNUO com pose E pelo menos uma mão. Contínuo, e
não a soma dos frames válidos, porque um clipe com 60% de frames bons espalhados
em três pedaços não tem um sinal aproveitável em lugar nenhum.

Uso:
    python detectar_sinalizante.py --base external-data/libras-gap/candidatos/2026-09-12-lote-a
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import cv2
import mediapipe as mp

CAMPOS = ["palavra", "id", "dur_s", "canal", "titulo", "licenca", "veredito",
          "alertas", "revisao_manual"]
AMOSTRAS = 30


def medir(video: Path, holistic, amostras: int = AMOSTRAS) -> dict:
    cap = cv2.VideoCapture(str(video))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    dur = total / fps
    n = min(amostras, total)
    pose = mao = duas = 0
    validos = []
    for i in range(n):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(total * i / n))
        ok, frame = cap.read()
        if not ok:
            validos.append(False)
            continue
        r = holistic.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        p = r.pose_landmarks is not None
        e = r.left_hand_landmarks is not None
        d = r.right_hand_landmarks is not None
        pose += p
        mao += e or d
        duas += e and d
        validos.append(bool(p and (e or d)))
    cap.release()

    melhor = atual = inicio = melhor_ini = 0
    for i, v in enumerate(validos + [False]):
        if v:
            if atual == 0:
                inicio = i
            atual += 1
        else:
            if atual > melhor:
                melhor, melhor_ini = atual, inicio
            atual = 0
    return {"duracao_s": round(dur, 1), "amostras": n,
            "pose_pct": round(100 * pose / n), "mao_pct": round(100 * mao / n),
            "duas_maos_pct": round(100 * duas / n),
            "cobertura_pct": round(100 * melhor / n),
            "janela_util_s": [round(melhor_ini / n * dur, 1),
                              round((melhor_ini + melhor) / n * dur, 1)]}


def avaliar(d: dict) -> list[str]:
    """Alertas derivados da medição — fatos, não vereditos."""
    a = []
    if d["pose_pct"] < 90:
        a.append("cartela/abertura sem pessoa")
    if d["cobertura_pct"] < 60:
        a.append(f"só {d['cobertura_pct']}% do clipe é útil — recorte obrigatório")
    # NÃO é alerta por si só. Medido em 12/09: quatro clipes com 0% de duas mãos
    # continuaram em 0% com model_complexity=2, upscale 2× e limiares em 0,3 —
    # inclusive um em 1280x720. São sinais de uma mão (RUIM, REPETIR, BUSCAR), e
    # tratar isso como defeito descartaria dado bom. Vira alerta só quando a
    # resolução é baixa o bastante para a dúvida ser legítima.
    if d["duas_maos_pct"] == 0 and d["mao_pct"] > 60:
        a.append("uma mão só em todo o clipe — provável sinal monomanual, conferir")
    if d["mao_pct"] < 50:
        a.append("mão detectada em menos de metade dos frames")
    return a


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", type=Path, required=True)
    ap.add_argument("--amostras", type=int, default=AMOSTRAS)
    args = ap.parse_args(argv)
    base = args.base.resolve()

    with (base / "triagem.csv").open(newline="", encoding="utf-8-sig") as f:
        leitor = csv.DictReader(f)
        if leitor.fieldnames != CAMPOS:
            raise SystemExit("cabeçalho de triagem.csv inesperado")
        linhas = [l for l in leitor if l["veredito"] == "candidato"]

    holistic = mp.solutions.holistic.Holistic(
        model_complexity=1, min_detection_confidence=0.5, min_tracking_confidence=0.5)
    saida = {}
    for l in linhas:
        v = next((base / l["palavra"]).glob(f'*{l["id"]}*.mp4'), None)
        if v is None:
            continue
        d = medir(v, holistic, args.amostras)
        d["alertas"] = avaliar(d)
        saida[f'{l["palavra"]}:{l["id"]}'] = d
        print(f'  {l["palavra"]:<13}{d["duracao_s"]:>5.1f}s  pose {d["pose_pct"]:>3}%  '
              f'mão {d["mao_pct"]:>3}%  útil {d["janela_util_s"][0]}-{d["janela_util_s"][1]}s'
              + ("  ⚠ " + "; ".join(d["alertas"]) if d["alertas"] else ""))
    holistic.close()
    (base / "deteccao.json").write_text(json.dumps(
        {"schema_version": 1, "por_candidato": saida}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print(f"\n{len(saida)} candidato(s) medido(s) -> {base / 'deteccao.json'}")
    print("Janela útil é onde há pessoa e mão; não diz que o sinal está certo.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

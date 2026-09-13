#!/usr/bin/env python3
"""Mede o classificador exportado (.onnx) no split de teste sintético e num conjunto
de validação de falso-positivo genérico, e escreve resultados/<modelo>/relatorio.md.

Os números do split sintético vêm do MESMO gerador que produziu o treino
(dados/sintetizar.py) — medem se o classificador aprendeu a distinguir a frase-alvo
dos negativos que nós definimos, não se funciona com pessoas de verdade num ambiente
de balcão real. Ver docs/wake-word-treino-plano.md §4 e
docs/orquestracao-dialogo-audio-plano.md Fase 3 ("Validar taxa de falso-positivo/
negativo num ambiente ruidoso...") pro critério de aceite de verdade.
"""

from __future__ import annotations

import argparse
import datetime as dt
from pathlib import Path

import numpy as np
import onnxruntime as ort

RAIZ = Path(__file__).resolve().parent


def prever(sess: ort.InferenceSession, features: np.ndarray) -> np.ndarray:
    """Roda um exemplo de cada vez: o .onnx exportado por train.py fixa batch=1
    (`torch.onnx.export(..., torch.rand(input_shape)[None, ])`, sem `dynamic_axes`)
    — é o formato certo pro app (processa uma janela por vez, tempo real), mas exige
    isto aqui pra avaliar um lote inteiro do split de teste."""
    entrada = sess.get_inputs()[0].name
    saidas = [sess.run(None, {entrada: janela[None, ...].astype(np.float32)})[0] for janela in features]
    return np.concatenate(saidas).reshape(-1)


def metricas_binarias(scores: np.ndarray, labels: np.ndarray, limiar: float = 0.5) -> dict:
    preds = (scores >= limiar).astype(int)
    tp = int(((preds == 1) & (labels == 1)).sum())
    fp = int(((preds == 1) & (labels == 0)).sum())
    fn = int(((preds == 0) & (labels == 1)).sum())
    tn = int(((preds == 0) & (labels == 0)).sum())
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "tn": tn,
        "acuracia": (tp + tn) / max(len(labels), 1),
        "recall": tp / max(tp + fn, 1),
        "precisao": tp / max(tp + fp, 1),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--modelo", required=True, choices=["libras_livre_iniciar", "libras_livre_encerrar"])
    args = ap.parse_args()
    modelo = args.modelo

    treino_dir = RAIZ / "treino"
    onnx_path = treino_dir / f"{modelo}.onnx"
    if not onnx_path.exists():
        raise SystemExit(f"{onnx_path} não existe — rode treinar.sh primeiro.")
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])

    pos = np.load(treino_dir / modelo / "positive_features_test.npy")
    neg = np.load(treino_dir / modelo / "negative_features_test.npy")
    scores_pos = prever(sess, pos)
    scores_neg = prever(sess, neg)
    scores = np.concatenate([scores_pos, scores_neg])
    labels = np.concatenate([np.ones(len(scores_pos)), np.zeros(len(scores_neg))])
    m = metricas_binarias(scores, labels)

    fp_geral = None
    scores_val = None
    horas_val = 0.0
    val_path = RAIZ / "ruido" / "validation_set_features.npy"
    if val_path.exists():
        val = np.load(val_path)
        n = pos.shape[1]
        janelas = np.array([val[i : i + n] for i in range(0, val.shape[0] - n, n)])
        scores_val = prever(sess, janelas)
        horas_val = (janelas.shape[0] * 1280) / 1000 / 3600  # 1280 ms por passo de feature, ver train.py
        falsos = int((scores_val >= 0.5).sum())
        fp_geral = {"horas": horas_val, "falsos": falsos, "fp_por_hora": falsos / max(horas_val, 1e-6)}

    linhas = [
        f"# Relatório — {modelo}",
        "",
        f"Gerado em {dt.date.today().isoformat()}.",
        "",
        "## Split de teste sintético (mesmo gerador do treino)",
        "",
        f"- Positivos: {len(scores_pos)}, negativos: {len(scores_neg)}",
        f"- Acurácia: {m['acuracia']:.3f}",
        f"- Recall (taxa de detecção): {m['recall']:.3f}",
        f"- Precisão: {m['precisao']:.3f}",
        f"- TP={m['tp']} FP={m['fp']} FN={m['fn']} TN={m['tn']}",
        "",
    ]
    if fp_geral:
        linhas += [
            "## Falso-positivo em áudio genérico (conjunto de validação do openWakeWord)",
            "",
            f"- ~{fp_geral['horas']:.1f} h de fala/ruído/música (majoritariamente inglês — não é "
            "pt-BR nem cenário de balcão, só um proxy geral de \"áudio comum do dia a dia\")",
            f"- Falsos positivos: {fp_geral['falsos']}",
            f"- Falsos positivos por hora: {fp_geral['fp_por_hora']:.2f} (alvo do config: 0.2)",
            "",
        ]

    linhas += [
        "## Curva de limiar (sem retreinar)",
        "",
        "Os mesmos scores acima, recalculados em vários limiares de decisão — "
        "`OpenWakeWordDetector.kt` usa `threshold` por `WakeWordModel` (ver "
        "`DEFAULT_THRESHOLD` em `OpenWakeWordDetector.kt`), então isto é só trocar um "
        "número, sem exportar `.onnx` de novo. `0.5` é o ponto usado nas seções acima.",
        "",
        "| Limiar | Recall | Precisão | FP/hora (genérico) |",
        "|---|---|---|---|",
    ]
    for limiar in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9):
        m_lim = metricas_binarias(scores, labels, limiar=limiar)
        if scores_val is not None:
            fp_lim = int((scores_val >= limiar).sum()) / max(horas_val, 1e-6)
            fp_txt = f"{fp_lim:.2f}"
        else:
            fp_txt = "n/d"
        marca = " **(atual)**" if limiar == 0.5 else ""
        linhas.append(f"| {limiar:.1f}{marca} | {m_lim['recall']:.3f} | {m_lim['precisao']:.3f} | {fp_txt} |")
    linhas.append("")
    acav100m_usado = (RAIZ / "ruido" / "openwakeword_features_ACAV100M_2000_hrs_16bit.npy").exists()
    linhas += [
        "## O que este número NÃO mede",
        "",
        "- Generalização pra vozes/sotaques fora das 6 vozes Piper pt-BR usadas no treino.",
        "- Ambiente real de balcão (ruído de fala cruzada, distância variável do mic dos óculos/celular).",
        "- Confusão com fala pt-BR genérica fora dos confusáveis que escrevemos à mão em dados/frases.py.",
        (
            "- O pool de negativos pré-computado do ACAV100M foi usado neste treino (ver "
            "config/*.yaml) — os números de falso-positivo acima já refletem isso."
            if acav100m_usado
            else "- O pool de negativos pré-computado do ACAV100M (~17 GB) foi deliberadamente pulado "
            "nesta rodada — ver docs/wake-word-treino-plano.md §3."
        ),
        "",
        "**Critério de aceite real continua sendo o da Fase 3 do plano** "
        "(`docs/orquestracao-dialogo-audio-plano.md`): testar em hardware, com o app em foreground, "
        "comparando objetivamente contra o `SpeechRecognizerWakeWordDetector` atual — falso-positivo, "
        "falso-negativo, latência, funciona offline — antes de trocar o motor padrão no "
        "`DialogOrchestrator`.",
    ]

    destino = RAIZ / "resultados" / modelo
    destino.mkdir(parents=True, exist_ok=True)
    (destino / "relatorio.md").write_text("\n".join(linhas) + "\n", encoding="utf-8")
    print(f"Relatório escrito em {destino / 'relatorio.md'}")


if __name__ == "__main__":
    main()

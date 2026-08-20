"""§5.4 — Avaliação leave-one-signer-out + matriz de confusão.

Protocolo (obrigatório, §6.1):
  - Para cada pessoa p:
      referência = todos os clipes de todas as pessoas EXCETO p;
      teste      = todos os clipes de p;
      classifica cada clipe de p contra a referência (1-NN DTW).
  - Acurácia reportada = média das acurácias por rodada.
  - Matriz de confusão agregada de todas as rodadas -> results/confusion_matrix.png.

Essa é a métrica que representa a realidade de produto: o sistema encontrando
alguém que nunca viu. Ao final imprime o veredito segundo o critério do §6.3.

Uso:
    python src/evaluate.py
"""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

import numpy as np

from dtw_classifier import Clip, DTWClassifier, carregar_dataset

BASE = Path(__file__).resolve().parent.parent
RESULTS = BASE / "results"


def _veredito(acc: float) -> str:
    if acc >= 0.80:
        return "🟢 SINAL VERDE (>=80%) — seguir para o MVP com esta abordagem."
    if acc >= 0.60:
        return "🟡 ZONA DE ATENÇÃO (60-80%) — revisar vocabulário / mais repetições / testar NN."
    return "🔴 SINAL VERMELHO (<60%) — reconsiderar abordagem antes de investir mais."


def leave_one_signer_out(clips: list[Clip]) -> tuple[float, list[tuple[str, str]], list[str]]:
    """Retorna (acurácia média entre rodadas, pares (verdadeiro, previsto), rótulos)."""
    pessoas = sorted({c.pessoa for c in clips})
    if len(pessoas) < 2:
        raise SystemExit(
            f"leave-one-signer-out exige >=2 pessoas, encontrei {len(pessoas)}. "
            "Grave clipes de mais participantes (README §3).")

    accs: list[float] = []
    pares: list[tuple[str, str]] = []  # (verdadeiro, previsto), agregado

    for p in pessoas:
        ref = [c for c in clips if c.pessoa != p]
        teste = [c for c in clips if c.pessoa == p]
        if not teste:
            continue
        clf = DTWClassifier(ref)
        acertos = 0
        for c in teste:
            previsto, _ = clf.prever(c.seq)
            pares.append((c.sinal, previsto))
            acertos += int(previsto == c.sinal)
        acc_p = acertos / len(teste)
        accs.append(acc_p)
        print(f"[eval] pessoa {p}: {acertos}/{len(teste)} = {acc_p:.1%}")

    rotulos = sorted({s for c in clips for s in [c.sinal]})
    return float(np.mean(accs)), pares, rotulos


def plotar_confusao(pares: list[tuple[str, str]], rotulos: list[str], destino: Path) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from sklearn.metrics import ConfusionMatrixDisplay, confusion_matrix
    except ImportError:
        print("[eval] matplotlib/scikit-learn ausentes — pulei o plot da matriz de confusão.")
        return

    y_true = [t for t, _ in pares]
    y_pred = [p for _, p in pares]
    cm = confusion_matrix(y_true, y_pred, labels=rotulos)
    disp = ConfusionMatrixDisplay(cm, display_labels=rotulos)
    fig, ax = plt.subplots(figsize=(max(6, len(rotulos)), max(5, len(rotulos))))
    disp.plot(ax=ax, xticks_rotation=45, colorbar=False)
    ax.set_title("Matriz de confusão — leave-one-signer-out (agregada)")
    fig.tight_layout()
    destino.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(destino, dpi=150)
    print(f"[eval] matriz de confusão salva em {destino}")


def main() -> None:
    clips = carregar_dataset()
    if not clips:
        raise SystemExit("nenhum landmark carregado — rode extract.py primeiro.")

    pessoas = sorted({c.pessoa for c in clips})
    sinais = sorted({c.sinal for c in clips})
    print(f"[eval] {len(clips)} clipes | {len(pessoas)} pessoas | {len(sinais)} sinais")

    acc, pares, rotulos = leave_one_signer_out(clips)
    print(f"\n[eval] ACURÁCIA signer-independent média = {acc:.1%}")
    print(f"[eval] {_veredito(acc)}")

    plotar_confusao(pares, rotulos, RESULTS / "confusion_matrix.png")


if __name__ == "__main__":
    main()

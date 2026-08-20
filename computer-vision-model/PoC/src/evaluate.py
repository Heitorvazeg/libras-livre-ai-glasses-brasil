"""§5.4 — Avaliação leave-one-signer-out + matriz de confusão.

Protocolo (obrigatório, §6.1):
  - Para cada pessoa p:
      referência = todos os clipes de todas as pessoas EXCETO p;
      teste      = todos os clipes de p;
      classifica cada clipe de p contra a referência (1-NN DTW).
  - Métrica principal = média das acurácias por rodada (§6.2).
  - Matriz de confusão agregada de todas as rodadas.

Essa é a métrica que representa a realidade de produto: o sistema encontrando
alguém que nunca viu. Ao final imprime o veredito segundo o critério do §6.3 e
grava os entregáveis do §10 em results/:

    confusion_matrix.png   matriz de confusão agregada
    relatorio.md           acurácia por pessoa/sinal, pares confundidos, veredito
    predicoes.csv          uma linha por clipe de teste (para qualquer análise extra)

Uso:
    python src/evaluate.py
    python src/evaluate.py --recalcular    # ignora o cache da matriz de distâncias
"""
from __future__ import annotations

import argparse
import csv
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import numpy as np

from config import Config, load_config
from dtw_classifier import (Clip, assinatura_dataset, carregar_dataset,
                            escolher_backend, matriz_distancias)


@dataclass
class Predicao:
    pessoa: str
    verdadeiro: str
    previsto: str
    distancia: float
    vizinho: str
    rep: str
    frames: int

    @property
    def acerto(self) -> bool:
        return self.verdadeiro == self.previsto


def veredito(acc: float, cfg: Config) -> str:
    verde = cfg.avaliacao.get("limiar_verde", 0.80)
    atencao = cfg.avaliacao.get("limiar_atencao", 0.60)
    if acc >= verde:
        return f"🟢 SINAL VERDE (>={verde:.0%}) — seguir para o MVP com esta abordagem."
    if acc >= atencao:
        return (f"🟡 ZONA DE ATENÇÃO ({atencao:.0%}-{verde:.0%}) — revisar vocabulário "
                "(pares confundidos abaixo), aumentar repetições ou testar o classificador treinado.")
    return f"🔴 SINAL VERMELHO (<{atencao:.0%}) — reconsiderar abordagem antes de investir mais."


def conferir_coleta(clips: list[Clip], cfg: Config) -> list[str]:
    """Avisos sobre o dataset que mudam a leitura do resultado (§4, §9)."""
    avisos: list[str] = []
    pessoas = sorted({c.pessoa for c in clips})
    sinais = sorted({c.sinal for c in clips})

    fora = [s for s in sinais if s not in cfg.vocabulario]
    if fora:
        avisos.append(f"sinais fora do vocabulário do config.yaml: {fora}")
    ausentes = [s for s in cfg.vocabulario if s not in sinais]
    if ausentes:
        avisos.append(f"sinais do vocabulário sem nenhum clipe: {ausentes}")
    if len(pessoas) < 5:
        avisos.append(f"{len(pessoas)} participante(s) — o plano pede mín. 5, ideal 8 (§4.1). "
                      "Com poucos sinalizantes a acurácia média fica instável.")
    if len(pessoas) < cfg.participantes_esperados:
        avisos.append(f"{len(pessoas)} de {cfg.participantes_esperados} participantes esperados.")

    por_sinal_pessoas = defaultdict(set)
    contagem = Counter()
    for c in clips:
        por_sinal_pessoas[c.sinal].add(c.pessoa)
        contagem[(c.pessoa, c.sinal)] += 1

    solitarios = [s for s, ps in por_sinal_pessoas.items() if len(ps) < 2]
    if solitarios:
        avisos.append(f"sinais gravados por uma única pessoa: {solitarios} — na rodada dela não há "
                      "referência dessa classe, e o erro é garantido.")

    faltantes = [(p, s, contagem[(p, s)]) for p in pessoas for s in sinais
                 if contagem[(p, s)] < cfg.repeticoes_por_sinal]
    if faltantes:
        resumo = ", ".join(f"{p}/{s}={n}" for p, s, n in faltantes[:8])
        extra = f" (+{len(faltantes) - 8})" if len(faltantes) > 8 else ""
        avisos.append(f"combinações abaixo de {cfg.repeticoes_por_sinal} repetições: {resumo}{extra}")
    return avisos


def leave_one_signer_out(clips: list[Clip], dist: np.ndarray) -> tuple[list[float], list[Predicao]]:
    """Roda o protocolo do §6.1 sobre a matriz de distâncias pré-computada."""
    pessoas = sorted({c.pessoa for c in clips})
    if len(pessoas) < 2:
        raise SystemExit(
            f"leave-one-signer-out exige >=2 pessoas, encontrei {len(pessoas)}. "
            "Grave clipes de mais participantes (README §3).")

    idx_por_pessoa = defaultdict(list)
    for i, c in enumerate(clips):
        idx_por_pessoa[c.pessoa].append(i)

    accs: list[float] = []
    predicoes: list[Predicao] = []
    for p in pessoas:
        teste = idx_por_pessoa[p]
        referencia = np.array([i for i, c in enumerate(clips) if c.pessoa != p])
        acertos = 0
        for i in teste:
            linha = dist[i, referencia]
            vencedor = referencia[int(np.argmin(linha))]
            pred = Predicao(pessoa=p, verdadeiro=clips[i].sinal, previsto=clips[vencedor].sinal,
                            distancia=float(dist[i, vencedor]), vizinho=clips[vencedor].nome,
                            rep=clips[i].rep, frames=len(clips[i].seq))
            predicoes.append(pred)
            acertos += int(pred.acerto)
        acc_p = acertos / len(teste)
        accs.append(acc_p)
        print(f"[eval] pessoa {p}: {acertos}/{len(teste)} = {acc_p:.1%}")
    return accs, predicoes


def matriz_confusao(predicoes: list[Predicao], rotulos: list[str]) -> np.ndarray:
    idx = {r: i for i, r in enumerate(rotulos)}
    cm = np.zeros((len(rotulos), len(rotulos)), dtype=int)
    for p in predicoes:
        cm[idx[p.verdadeiro], idx[p.previsto]] += 1
    return cm


def plotar_confusao(cm: np.ndarray, rotulos: list[str], destino: Path) -> bool:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from sklearn.metrics import ConfusionMatrixDisplay
    except ImportError:
        print("[eval] matplotlib/scikit-learn ausentes — pulei o plot da matriz de confusão.")
        return False

    disp = ConfusionMatrixDisplay(cm, display_labels=rotulos)
    lado = max(6, 0.8 * len(rotulos) + 3)
    fig, ax = plt.subplots(figsize=(lado, lado))
    disp.plot(ax=ax, xticks_rotation=45, colorbar=False, cmap="Blues")
    ax.set_xlabel("previsto")
    ax.set_ylabel("verdadeiro")
    ax.set_title("Matriz de confusão — leave-one-signer-out (agregada)")
    fig.tight_layout()
    destino.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(destino, dpi=150)
    plt.close(fig)
    print(f"[eval] matriz de confusão salva em {destino}")
    return True


def pares_confundidos(cm: np.ndarray, rotulos: list[str], limite: int = 10):
    pares = [(rotulos[i], rotulos[j], int(cm[i, j]))
             for i in range(len(rotulos)) for j in range(len(rotulos))
             if i != j and cm[i, j] > 0]
    pares.sort(key=lambda t: -t[2])
    return pares[:limite]


def escrever_csv(predicoes: list[Predicao], destino: Path) -> None:
    destino.parent.mkdir(parents=True, exist_ok=True)
    with open(destino, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["pessoa", "rep", "sinal_verdadeiro", "previsto", "acerto",
                    "distancia_dtw", "clipe_vizinho", "frames"])
        for p in predicoes:
            w.writerow([p.pessoa, p.rep, p.verdadeiro, p.previsto, int(p.acerto),
                        f"{p.distancia:.4f}", p.vizinho, p.frames])
    print(f"[eval] predições salvas em {destino}")


def escrever_relatorio(destino: Path, cfg: Config, clips: list[Clip], accs: list[float],
                       predicoes: list[Predicao], cm: np.ndarray, rotulos: list[str],
                       avisos: list[str], segundos: float, tem_plot: bool) -> None:
    pessoas = sorted({c.pessoa for c in clips})
    acc_media = float(np.mean(accs))
    acc_micro = sum(p.acerto for p in predicoes) / len(predicoes)
    frames = [len(c.seq) for c in clips]
    recall = cm.diagonal() / np.maximum(cm.sum(axis=1), 1)
    ordem = np.argsort(recall)

    linhas = [
        "# Resultado da PoC — reconhecimento signer-independent de Libras",
        "",
        f"Gerado em {datetime.now():%Y-%m-%d %H:%M} · protocolo leave-one-signer-out (§6.1) "
        f"· baseline 1-NN DTW (§5.3) · backend `{escolher_backend(cfg)}` · {segundos:.1f}s de avaliação.",
        "",
        "## Veredito (§6.3)",
        "",
        f"**Acurácia signer-independent média = {acc_media:.1%}**  ",
        f"{veredito(acc_media, cfg)}",
        "",
        f"- Acurácia agregada por clipe (micro) = {acc_micro:.1%} — reportada só como conferência; "
        "a métrica do plano é a média entre rodadas.",
        f"- Chance aleatória com {len(rotulos)} sinais = {1 / len(rotulos):.1%}.",
        "",
        "## Dataset",
        "",
        f"- {len(clips)} clipes · {len(pessoas)} pessoas · {len(rotulos)} sinais",
        f"- Participantes: {', '.join(pessoas)}",
        f"- Frames por clipe: min {min(frames)} · mediana {int(np.median(frames))} · max {max(frames)}",
        f"- Normalização: origem no ponto médio dos ombros, escala = distância entre ombros, "
        f"z {'incluído' if cfg.dims == 3 else 'descartado'} ({cfg.num_pontos} pontos × {cfg.dims} dims)",
        "",
    ]

    if avisos:
        linhas += ["> ⚠️ **Ressalvas sobre a coleta** (afetam a leitura do número acima):", ">"]
        linhas += [f"> - {a}" for a in avisos] + [""]

    linhas += ["## Acurácia por rodada (pessoa deixada de fora)", "",
               "| Pessoa | Clipes | Acurácia |", "|---|---|---|"]
    for p, acc in zip(pessoas, accs):
        n = sum(1 for x in predicoes if x.pessoa == p)
        linhas.append(f"| {p} | {n} | {acc:.1%} |")
    linhas += [f"| **média** | {len(predicoes)} | **{acc_media:.1%}** |", ""]

    linhas += ["## Acurácia por sinal (recall agregado)", "",
               "| Sinal | Acertos / Clipes | Recall |", "|---|---|---|"]
    for i in ordem:
        linhas.append(f"| {rotulos[i]} | {cm[i, i]} / {cm[i].sum()} | {recall[i]:.1%} |")
    linhas.append("")

    pares = pares_confundidos(cm, rotulos)
    linhas += ["## Sinais problemáticos (§10)", ""]
    if pares:
        linhas += ["Pares mais confundidos — candidatos a revisão de vocabulário:", "",
                   "| Verdadeiro | Previsto como | Ocorrências |", "|---|---|---|"]
        linhas += [f"| {v} | {p} | {n} |" for v, p, n in pares]
    else:
        linhas.append("Nenhuma confusão registrada.")
    linhas.append("")

    if tem_plot:
        linhas += ["## Matriz de confusão", "", "![Matriz de confusão](confusion_matrix.png)", ""]

    linhas += ["## Reprodutibilidade", "",
               "```yaml",
               f"dtw: {cfg.dtw}",
               f"normalizacao: {cfg.normalizacao}",
               f"holistic: {cfg.holistic}",
               "```",
               "",
               "Predições clipe a clipe em [`predicoes.csv`](predicoes.csv).",
               ""]

    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text("\n".join(linhas), encoding="utf-8")
    print(f"[eval] relatório salvo em {destino}")


def carregar_ou_calcular_matriz(clips: list[Clip], cfg: Config, recalcular: bool) -> np.ndarray:
    """Matriz de distâncias com cache em results/ (o cálculo é a parte cara)."""
    cache = cfg.path("results") / f"dtw_matrix_{assinatura_dataset(clips, cfg)}.npy"
    if cache.exists() and not recalcular:
        print(f"[eval] reaproveitando matriz de distâncias em cache ({cache.name})")
        return np.load(cache)
    inicio = time.perf_counter()
    m = matriz_distancias(clips, cfg)
    print(f"[eval] matriz de distâncias calculada em {time.perf_counter() - inicio:.1f}s")
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.save(cache, m)
    return m


def main() -> None:
    ap = argparse.ArgumentParser(description="Leave-one-signer-out + matriz de confusão (§5.4).")
    ap.add_argument("--recalcular", action="store_true",
                    help="ignora o cache da matriz de distâncias DTW")
    ap.add_argument("--sem-plot", action="store_true", help="não gera o PNG da matriz de confusão")
    args = ap.parse_args()

    cfg = load_config()
    clips = carregar_dataset(cfg=cfg)
    if not clips:
        raise SystemExit(f"nenhum landmark em {cfg.path('landmarks')} — rode extract.py primeiro.")

    pessoas = sorted({c.pessoa for c in clips})
    rotulos = sorted({c.sinal for c in clips})
    print(f"[eval] {len(clips)} clipes | {len(pessoas)} pessoas | {len(rotulos)} sinais")

    avisos = conferir_coleta(clips, cfg)
    for a in avisos:
        print(f"[eval] ⚠ {a}")

    inicio = time.perf_counter()
    dist = carregar_ou_calcular_matriz(clips, cfg, args.recalcular)
    accs, predicoes = leave_one_signer_out(clips, dist)
    segundos = time.perf_counter() - inicio

    acc_media = float(np.mean(accs))
    cm = matriz_confusao(predicoes, rotulos)
    print(f"\n[eval] ACURÁCIA signer-independent média = {acc_media:.1%}")
    print(f"[eval] {veredito(acc_media, cfg)}")

    results = cfg.path("results")
    tem_plot = False if args.sem_plot else plotar_confusao(cm, rotulos, results / "confusion_matrix.png")
    escrever_csv(predicoes, results / "predicoes.csv")
    escrever_relatorio(results / "relatorio.md", cfg, clips, accs, predicoes, cm, rotulos,
                       avisos, segundos, tem_plot)


if __name__ == "__main__":
    main()

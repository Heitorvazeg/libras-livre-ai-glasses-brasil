"""Treino do classificador de sinais — Skeleton-DML + ResNet-18, protocolo LOSO.

O que este script responde: **qual a acurácia com uma pessoa que o modelo nunca
viu?** Toda rodada deixa uma pessoa inteira de fora do treino e testa nela; o
número reportado é a média entre rodadas. É a mesma métrica do baseline DTW da
PoC (`PoC/results/relatorio.md`, 70,0%), para que a comparação seja direta.

Uso:
    python treinar.py                      # LOSO completo no MINDS (8 rodadas)
    python treinar.py --epocas 5 --folds 1 # rodada única, para testar o encanamento
    python treinar.py --final              # treina com TODOS e salva o checkpoint

Referências: Alves et al. 2024 (arXiv 2404.19148), dos Santos et al. 2025
(arXiv 2510.24887). Ver docs/investigacao-expansao-dataset.md, Achado D.
"""
from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
import yaml
from torch import nn
from torch.utils.data import DataLoader, Dataset

import dados as dd
import modelo as mm
import representacao as rp

AQUI = Path(__file__).resolve().parent
POC = AQUI.parent / "PoC"
CONFIG = POC / "config.yaml"
LADO = 224


class DatasetSinais(Dataset):
    """Clipes -> imagens Skeleton-DML 224×224, com augmentação só no treino."""

    def __init__(self, clipes: list[dd.Clipe], rotulos: list[str],
                 permutacao: np.ndarray | None, aumentar: bool, semente: int = 0):
        self.clipes = clipes
        self.indice = {r: i for i, r in enumerate(rotulos)}
        self.permutacao = permutacao
        self.aumentar = aumentar
        self.semente = semente

    def __len__(self) -> int:
        return len(self.clipes)

    def __getitem__(self, i: int):
        clipe = self.clipes[i]
        seq = clipe.seq
        if self.aumentar:
            # Semente por (época, índice) via torch: cada worker recebe um estado
            # diferente, e a augmentação não repete igual toda época.
            rng = np.random.default_rng(abs(hash((self.semente, i, torch.initial_seed()))) % 2**32)
            seq = rp.aumentar(seq, rng, self.permutacao)

        img = rp.para_imagem(seq)                      # (P, W, 3) em [0,1]
        t = torch.from_numpy(img).permute(2, 0, 1)     # (3, P, W)
        t = F.interpolate(t.unsqueeze(0), size=(LADO, LADO),
                          mode="bilinear", align_corners=False).squeeze(0)
        return t, self.indice[clipe.sinal]


def _loader(clipes, rotulos, permutacao, aumentar, batch, workers, embaralhar):
    ds = DatasetSinais(clipes, rotulos, permutacao, aumentar)
    return DataLoader(ds, batch_size=batch, shuffle=embaralhar, num_workers=workers,
                      drop_last=embaralhar and len(ds) > batch)


def _avaliar(modelo, loader, criterio, dispositivo):
    modelo.eval()
    perda, certos, total = 0.0, 0, 0
    preds, reais = [], []
    with torch.no_grad():
        for x, y in loader:
            x, y = x.to(dispositivo), y.to(dispositivo)
            saida = modelo(x)
            perda += criterio(saida, y).item() * y.size(0)
            p = saida.argmax(1)
            certos += (p == y).sum().item()
            total += y.size(0)
            preds += p.cpu().tolist()
            reais += y.cpu().tolist()
    return perda / max(total, 1), certos / max(total, 1), preds, reais


def treinar_rodada(treino, validacao, teste, rotulos, permutacao, args, dispositivo):
    """Treina uma rodada e devolve (acurácia no teste, predições, verdadeiros)."""
    modelo = mm.construir(len(rotulos)).to(dispositivo)
    criterio = nn.CrossEntropyLoss()
    otim = torch.optim.Adam(modelo.parameters(), lr=args.lr, weight_decay=args.wd)

    l_treino = _loader(treino, rotulos, permutacao, True, args.batch, args.workers, True)
    l_val = _loader(validacao, rotulos, None, False, args.batch, args.workers, False)
    l_teste = _loader(teste, rotulos, None, False, args.batch, args.workers, False)

    melhor_perda, melhores_pesos, melhor_epoca = float("inf"), None, -1
    for epoca in range(args.epocas):
        modelo.train()
        soma, certos, total = 0.0, 0, 0
        for x, y in l_treino:
            x, y = x.to(dispositivo), y.to(dispositivo)
            otim.zero_grad()
            saida = modelo(x)
            perda = criterio(saida, y)
            perda.backward()
            otim.step()
            soma += perda.item() * y.size(0)
            certos += (saida.argmax(1) == y).sum().item()
            total += y.size(0)

        val_perda, val_acc, _, _ = _avaliar(modelo, l_val, criterio, dispositivo)
        if val_perda < melhor_perda:
            melhor_perda, melhor_epoca = val_perda, epoca
            melhores_pesos = {k: v.detach().clone() for k, v in modelo.state_dict().items()}
        print(f"      época {epoca + 1:>2}/{args.epocas}  treino {soma / max(total,1):.3f}"
              f"/{certos / max(total,1):.1%}  val {val_perda:.3f}/{val_acc:.1%}"
              f"{'  <- melhor' if melhor_epoca == epoca else ''}", flush=True)

    # A escolha do epoch usa a VALIDAÇÃO; o teste só é tocado aqui, uma vez.
    if melhores_pesos is not None:
        modelo.load_state_dict(melhores_pesos)
    _, acc, preds, reais = _avaliar(modelo, l_teste, criterio, dispositivo)
    return acc, preds, reais, melhor_epoca, modelo


def matriz_confusao(preds, reais, n):
    m = np.zeros((n, n), dtype=int)
    for r, p in zip(reais, preds):
        m[r, p] += 1
    return m


def escrever_relatorio(destino: Path, rotulos, accs, nomes_fold, cm, args, segundos, avisos):
    acc_media = float(np.mean(accs))
    recall = cm.diagonal() / np.maximum(cm.sum(axis=1), 1)
    ordem = np.argsort(recall)
    pares = sorted(((rotulos[i], rotulos[j], int(cm[i, j]))
                    for i in range(len(rotulos)) for j in range(len(rotulos))
                    if i != j and cm[i, j] > 0), key=lambda t: -t[2])[:10]

    linhas = [
        "# Resultado do treino — Skeleton-DML + ResNet-18 (ImageNet)",
        "",
        f"Gerado em {datetime.now():%Y-%m-%d %H:%M} · protocolo leave-one-signer-out "
        f"({len(accs)} rodadas) · {segundos / 60:.0f} min de treino.",
        "",
        "## Resultado",
        "",
        f"**Acurácia signer-independent média = {acc_media:.1%}** "
        f"(desvio entre rodadas: {np.std(accs):.1%})",
        "",
        f"- Chance aleatória com {len(rotulos)} sinais = {1 / len(rotulos):.1%}.",
        "- Baseline DTW 1-NN da PoC, mesma métrica: 70,0% (10 sinais, 11 pessoas) — "
        "ver `../PoC/results/relatorio.md`.",
        "- Referência publicada no MINDS-Libras com este protocolo: 0,93-0,94 "
        "(Alves et al. 2024; dos Santos et al. 2025).",
        "",
        "## Acurácia por rodada (pessoa deixada de fora)",
        "",
        "| Pessoa | Acurácia |", "|---|---|",
    ]
    linhas += [f"| {p} | {a:.1%} |" for p, a in zip(nomes_fold, accs)]
    linhas += [f"| **média** | **{acc_media:.1%}** |", ""]

    linhas += ["## Acurácia por sinal (recall agregado)", "",
               "| Sinal | Acertos / Clipes | Recall |", "|---|---|---|"]
    linhas += [f"| {rotulos[i]} | {cm[i, i]} / {cm[i].sum()} | {recall[i]:.1%} |" for i in ordem]
    linhas.append("")

    linhas += ["## Pares mais confundidos", ""]
    if pares:
        linhas += ["| Verdadeiro | Previsto como | Ocorrências |", "|---|---|---|"]
        linhas += [f"| {v} | {p} | {n} |" for v, p, n in pares]
    else:
        linhas.append("Nenhuma confusão registrada.")
    linhas.append("")

    if avisos:
        linhas += ["## Ressalvas sobre o dataset", ""] + [f"- {a}" for a in avisos] + [""]

    linhas += ["## Reprodutibilidade", "", "```json",
               json.dumps(vars(args), indent=2, ensure_ascii=False, default=str), "```", ""]
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text("\n".join(linhas), encoding="utf-8")
    print(f"[treino] relatório salvo em {destino}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--fontes", default="minds", choices=["minds", "vlibrasil", "todas"])
    ap.add_argument("--epocas", type=int, default=30)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--wd", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--threads", type=int, default=10,
                    help="threads de CPU do torch (deixa folga para a máquina seguir usável)")
    ap.add_argument("--folds", type=int, default=0,
                    help="limita o nº de rodadas (0 = todas). Use 1 para testar o encanamento.")
    ap.add_argument("--final", action="store_true",
                    help="treina com TODAS as pessoas e salva o checkpoint (sem avaliação)")
    ap.add_argument("--saida", default="resultados")
    args = ap.parse_args()

    torch.set_num_threads(args.threads)
    dispositivo = torch.device("cpu")

    cfg = yaml.safe_load(CONFIG.read_text(encoding="utf-8"))
    lm_dir = POC / cfg["paths"]["landmarks"]
    clipes = dd.carregar(lm_dir, fontes=args.fontes)
    if not clipes:
        raise SystemExit(f"nenhum landmark em {lm_dir} — rode ../PoC/src/extract.py primeiro.")

    rotulos = dd.rotulos(clipes)
    permutacao = rp.permutacao_espelho(list(cfg["pose_indices"].keys()))
    avisos = dd.conferir(clipes, cfg["vocabulario"])
    for a in avisos:
        print(f"[treino] ⚠ {a}")
    print(f"[treino] {len(clipes)} clipes | {len(dd.pessoas(clipes))} pessoas | "
          f"{len(rotulos)} sinais | dispositivo={dispositivo} threads={args.threads}")

    saida = AQUI / args.saida
    saida.mkdir(parents=True, exist_ok=True)
    inicio = time.perf_counter()

    if args.final:
        # Sem holdout: é o modelo de entrega, e a estimativa de qualidade dele é
        # o número da LOSO rodada antes — não um teste que ele já viu.
        print("[treino] modo final: treinando com todas as pessoas")
        todas = dd.pessoas(clipes)
        val = [c for c in clipes if c.pessoa == todas[-1]]
        treino = clipes
        _, _, _, _, modelo_final = treinar_rodada(treino, val, val, rotulos, permutacao,
                                                  args, dispositivo)
        destino = saida / "modelo_final.pt"
        mm.salvar(modelo_final, destino, rotulos,
                  {"fontes": args.fontes, "pessoas": todas, "args": vars(args),
                   "pontos": cfg["pose_indices"], "limite_escala": rp.LIMITE})
        print(f"[treino] checkpoint salvo em {destino}")
        return

    particoes = dd.particoes(clipes)
    if args.folds:
        particoes = particoes[: args.folds]

    accs, nomes, todos_preds, todos_reais = [], [], [], []
    for i, part in enumerate(particoes, 1):
        treino = [c for c in clipes if c.pessoa in part.treino]
        validacao = [c for c in clipes if c.pessoa == part.validacao]
        teste = [c for c in clipes if c.pessoa == part.teste]
        print(f"\n[treino] rodada {i}/{len(particoes)} — teste={part.teste} "
              f"val={part.validacao} treino={len(treino)} clipes")
        acc, preds, reais, melhor, _ = treinar_rodada(treino, validacao, teste, rotulos,
                                                      permutacao, args, dispositivo)
        print(f"   -> acurácia em {part.teste}: {acc:.1%} (melhor época {melhor + 1})")
        accs.append(acc)
        nomes.append(part.teste)
        todos_preds += preds
        todos_reais += reais

    segundos = time.perf_counter() - inicio
    cm = matriz_confusao(todos_preds, todos_reais, len(rotulos))
    print(f"\n[treino] ACURÁCIA signer-independent média = {np.mean(accs):.1%}")
    escrever_relatorio(saida / "relatorio.md", rotulos, accs, nomes, cm, args, segundos, avisos)
    np.save(saida / "matriz_confusao.npy", cm)


if __name__ == "__main__":
    main()

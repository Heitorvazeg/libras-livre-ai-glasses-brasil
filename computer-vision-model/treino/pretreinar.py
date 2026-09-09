"""Pré-treino: aprende representação num corpus grande, sem medir acurácia.

A IDEIA (docs/decisao-arquitetura-modelo.md, e a proposta do time em 2026-09-08):
com ~800 clipes no vocabulário de avaliação, o que limita o modelo não é a
arquitetura, é quanto ele já viu. A ResNet resolve parte disso vindo do ImageNet
— mas ImageNet são fotos, não Libras. Este script insere um estágio intermediário:

    ImageNet  ->  Libras em geral  ->  os sinais que interessam
    (fotos)       (este script)        (treinar.py, LOSO)

O corpus daqui é grande em vocabulário e pobre em pessoas (V-LIBRASIL: 1.353
palavras, 3 articuladores; WLASL: 2.000 classes, 78 sinalizantes). Isso o
inutiliza como conjunto de AVALIAÇÃO, mas serve bem ao propósito de aprender
"como um corpo se move sinalizando".

POR QUE NÃO HÁ LEAVE-ONE-SIGNER-OUT AQUI. Nenhum número deste script vai para
lugar nenhum: o produto dele são os pesos. A acurácia que vale continua saindo
do treinar.py, sobre o MINDS, com uma pessoa inteira fora. A separação é o que
mantém a medição honesta — e é por isso que os 30 clipes da V-LIBRASIL que
servem de teste de domínio foram excluídos deste corpus (ver
datasets/ingest_pretreino.py).

A divisão interna aqui (90/10 aleatório) serve só para escolher a época; não é
resultado.

Uso:
    python pretreinar.py --corpus ../PoC/data/landmarks-pretreino
    python pretreinar.py --corpus ../PoC/data/landmarks-pretreino \\
                         --corpus ../PoC/data/landmarks-wlasl --arquitetura gcn

Depois:
    python treinar.py --inicializar resultados-pretreino/backbone_resnet.pt
"""
from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader

import contrastivo as ct
import dados as dd
import gcn as gg
import modelo as mm
import representacao as rp
from treinar import DatasetSinais

AQUI = Path(__file__).resolve().parent


def carregar_corpora(dirs: list[Path], min_clipes_por_classe: int) -> list[dd.Clipe]:
    """Junta um ou mais diretórios de landmarks num corpus só.

    Classes com pouquíssimos exemplos são descartadas: elas não ensinam
    representação (o modelo decora), inflam a camada de saída e desequilibram o
    treino. O corte é explícito para que a perda apareça no log, não em silêncio.
    """
    clipes: list[dd.Clipe] = []
    for d in dirs:
        antes = len(clipes)
        clipes += dd.carregar(d, fontes="todas")
        print(f"[pretreino] {d.name}: {len(clipes) - antes} clipes")

    contagem: dict[str, int] = {}
    for c in clipes:
        contagem[c.sinal] = contagem.get(c.sinal, 0) + 1
    mantidos = [c for c in clipes if contagem[c.sinal] >= min_clipes_por_classe]
    cortadas = len(contagem) - len({c.sinal for c in mantidos})
    if cortadas:
        print(f"[pretreino] {cortadas} classe(s) descartada(s) por ter < "
              f"{min_clipes_por_classe} clipes ({len(clipes) - len(mantidos)} clipes)")
    return mantidos


def separar(clipes: list[dd.Clipe], fracao_val: float, semente: int):
    """Divisão aleatória. NÃO é por pessoa — de propósito: aqui não se mede
    generalização entre sinalizantes, só se escolhe a época."""
    rng = np.random.default_rng(semente)
    idx = rng.permutation(len(clipes))
    corte = int(len(clipes) * (1 - fracao_val))
    return ([clipes[i] for i in idx[:corte]], [clipes[i] for i in idx[corte:]])


def salvar_backbone(modelo: nn.Module, caminho: Path, arquitetura: str, meta: dict) -> None:
    """Grava tudo MENOS a cabeça de classificação.

    A cabeça é específica das 1.353 classes daqui e não serve para os 20 sinais
    do alvo — quem transfere é o corpo da rede. Guardá-la só criaria confusão de
    formato no carregamento.
    """
    pesos = {k: v for k, v in modelo.state_dict().items() if not k.startswith("fc.")}
    caminho.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"backbone": pesos, "arquitetura": arquitetura, "meta": meta}, caminho)
    print(f"[pretreino] backbone salvo em {caminho} ({len(pesos)} tensores)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--corpus", action="append", required=True, type=Path,
                    help="diretório de landmarks (repita para juntar vários)")
    ap.add_argument("--arquitetura", default="resnet", choices=["resnet", "gcn"])
    ap.add_argument("--objetivo", default="classificacao",
                    choices=["classificacao", "contrastivo"],
                    help="contrastivo aproxima clipes do MESMO sinal feitos por PESSOAS "
                         "diferentes — ensina invariância a sinalizante e funciona com "
                         "poucos exemplos por classe, ao contrário da classificação.")
    ap.add_argument("--p-classes", type=int, default=32,
                    help="contrastivo: classes por lote")
    ap.add_argument("--k-exemplos", type=int, default=2,
                    help="contrastivo: exemplos por classe no lote (>=2 para haver par)")
    ap.add_argument("--temperatura", type=float, default=0.07)
    ap.add_argument("--epocas", type=int, default=15)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--wd", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--threads", type=int, default=10)
    ap.add_argument("--dispositivo", default="auto", choices=["auto", "cpu", "cuda"])
    ap.add_argument("--min-clipes-por-classe", type=int, default=2)
    ap.add_argument("--fracao-val", type=float, default=0.1)
    ap.add_argument("--semente", type=int, default=0)
    ap.add_argument("--saida", default="resultados-pretreino")
    args = ap.parse_args()

    torch.set_num_threads(args.threads)
    if args.dispositivo == "auto":
        args.dispositivo = "cuda" if torch.cuda.is_available() else "cpu"
    disp = torch.device(args.dispositivo)
    torch.manual_seed(args.semente)

    clipes = carregar_corpora(args.corpus, args.min_clipes_por_classe)
    if not clipes:
        raise SystemExit("corpus vazio — confira os diretórios passados em --corpus")
    rotulos = dd.rotulos(clipes)
    treino, val = separar(clipes, args.fracao_val, args.semente)
    print(f"[pretreino] {len(clipes)} clipes | {len(rotulos)} classes | "
          f"{len(dd.pessoas(clipes))} pessoas | treino {len(treino)} / val {len(val)} | "
          f"arquitetura={args.arquitetura} dispositivo={disp}")

    import yaml
    cfg = yaml.safe_load((AQUI.parent / "PoC" / "config.yaml").read_text(encoding="utf-8"))
    perm = rp.permutacao_espelho(list(cfg["pose_indices"].keys()))

    construtor = gg.construir if args.arquitetura == "gcn" else mm.construir
    modelo = construtor(len(rotulos)).to(disp)
    criterio = nn.CrossEntropyLoss()

    cabeca = None
    if args.objetivo == "contrastivo":
        # A cabeça de classificação não é usada aqui; a perda vive num espaço
        # projetado próprio, que é descartado ao salvar o backbone.
        n_feat = modelo.fc[1].in_features if args.arquitetura == "resnet" \
            else modelo.fc.in_features
        modelo.fc = nn.Identity()
        cabeca = ct.CabecaProjecao(entrada=n_feat).to(disp)

    params = list(modelo.parameters()) + (list(cabeca.parameters()) if cabeca else [])
    otim = torch.optim.Adam(params, lr=args.lr, weight_decay=args.wd)

    def loader(cl, aug, shuffle):
        ds = DatasetSinais(cl, rotulos, perm if aug else None, aug,
                           arquitetura=args.arquitetura)
        if args.objetivo == "contrastivo" and shuffle:
            # Lotes P×K: sem eles, um corpus de 1.353 classes quase nunca colocaria
            # dois clipes da mesma palavra no mesmo lote, e não haveria par positivo.
            amostrador = ct.AmostradorPK([c.sinal for c in cl], args.p_classes,
                                         args.k_exemplos, args.semente)
            return DataLoader(ds, batch_size=args.p_classes * args.k_exemplos,
                              sampler=amostrador, num_workers=args.workers, drop_last=True)
        return DataLoader(ds, batch_size=args.batch, shuffle=shuffle,
                          num_workers=args.workers, drop_last=shuffle and len(ds) > args.batch)

    l_treino, l_val = loader(treino, True, True), loader(val, False, False)

    melhor, melhores_pesos, melhor_epoca = float("inf"), None, -1
    inicio = time.perf_counter()
    for epoca in range(args.epocas):
        modelo.train()
        soma = certos = total = 0
        for x, y in l_treino:
            x, y = x.to(disp), y.to(disp)
            otim.zero_grad()
            if cabeca is not None:
                perda = ct.perda_supcon(cabeca(modelo(x)), y, args.temperatura)
            else:
                saida = modelo(x)
                perda = criterio(saida, y)
                certos += (saida.argmax(1) == y).sum().item()
            perda.backward()
            otim.step()
            soma += perda.item() * y.size(0)
            total += y.size(0)

        modelo.eval()
        vperda = vcertos = vtotal = 0
        with torch.no_grad():
            for x, y in l_val:
                x, y = x.to(disp), y.to(disp)
                if cabeca is not None:
                    vperda += ct.perda_supcon(cabeca(modelo(x)), y,
                                              args.temperatura).item() * y.size(0)
                else:
                    s = modelo(x)
                    vperda += criterio(s, y).item() * y.size(0)
                    vcertos += (s.argmax(1) == y).sum().item()
                vtotal += y.size(0)
        vperda /= max(vtotal, 1)
        if vperda < melhor:
            melhor, melhor_epoca = vperda, epoca
            melhores_pesos = {k: v.detach().clone() for k, v in modelo.state_dict().items()}
        print(f"  época {epoca + 1:>2}/{args.epocas}  treino {soma / max(total,1):.3f}"
              f"/{certos / max(total,1):.1%}  val {vperda:.3f}/{vcertos / max(vtotal,1):.1%}"
              f"{'  <- melhor' if melhor_epoca == epoca else ''}", flush=True)

    if melhores_pesos is not None:
        modelo.load_state_dict(melhores_pesos)

    saida = AQUI / args.saida
    meta = {"corpora": [str(d) for d in args.corpus], "objetivo": args.objetivo,
            "classes": len(rotulos),
            "clipes": len(clipes), "pessoas": dd.pessoas(clipes),
            "melhor_epoca": melhor_epoca + 1, "args": vars(args),
            "gerado_em": f"{datetime.now():%Y-%m-%d %H:%M}"}
    salvar_backbone(modelo, saida / f"backbone_{args.arquitetura}.pt", args.arquitetura, meta)
    (saida / f"backbone_{args.arquitetura}.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    print(f"[pretreino] concluído em {(time.perf_counter() - inicio) / 60:.0f} min")
    print(f"[pretreino] próximo passo: python treinar.py --arquitetura {args.arquitetura} "
          f"--inicializar {args.saida}/backbone_{args.arquitetura}.pt")


if __name__ == "__main__":
    main()

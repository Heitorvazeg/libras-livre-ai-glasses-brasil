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
import zlib
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
import gcn as gg
import modelo as mm
import representacao as rp

AQUI = Path(__file__).resolve().parent
POC = AQUI.parent / "PoC"
CONFIG = POC / "config.yaml"
LADO = 224


class DatasetSinais(Dataset):
    """Clipes -> imagens Skeleton-DML 224×224, com augmentação só no treino."""

    def __init__(self, clipes: list[dd.Clipe], rotulos: list[str],
                 permutacao: np.ndarray | None, aumentar: bool, semente: int = 0,
                 arquitetura: str = "resnet", ossos: bool = False,
                 movimento: bool = False, rodada: int = 0):
        self.arquitetura = arquitetura
        self.rodada = rodada
        self.epoca = 0
        # Árvore calculada uma vez, não por item: é a mesma para todos os clipes.
        self.pais = gg.pais() if (ossos and arquitetura == "gcn") else None
        self.movimento = movimento and arquitetura == "gcn"
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
        # Validade vem do clipe CRU, antes de qualquer augmentação. `maos_ausentes`
        # detecta ausência por "bloco exatamente zerado", e `rp.aumentar` soma ruído
        # gaussiano em x,y — depois dela nenhum bloco é exatamente zero e a máscara
        # vira inerte. O efeito era pior que inútil: máscara ativa na validação e no
        # teste (sem augmentação) e desligada no treino, ou seja, discrepância
        # treino/teste. Ausência é propriedade do clipe, não da amostra aumentada.
        valido = None
        if self.movimento:
            valido = np.ones(seq.shape[:2], dtype=bool)
            for (a, b), ausente in zip(dd.BLOCOS_MAO, dd.maos_ausentes(seq)):
                valido[:, a:b] = ~ausente[:, None]
        if self.aumentar:
            # Semente EXPLÍCITA por (semente, rodada, época, índice). A versão
            # anterior usava torch.initial_seed(), que depende do estado global —
            # e o estado global já foi consumido pela construção do modelo, cuja
            # quantidade de parâmetros muda entre variantes. Resultado: "mesma
            # semente" dava augmentação diferente para cada variante, e a
            # comparação pareada que eu prometi não existia. Também não incluía a
            # época, então com workers=0 o mesmo clipe repetia a mesma augmentação
            # a treino inteiro.
            rng = np.random.default_rng([self.semente, self.rodada, self.epoca, i])
            seq = rp.aumentar(seq, rng, self.permutacao)

        if self.arquitetura == "gcn":
            # O GCN consome o esqueleto direto: (canais, tempo, nós).
            # Os ossos entram DEPOIS da augmentação: rotacionar/espelhar o
            # esqueleto e derivar os ossos do resultado mantém os dois fluxos
            # coerentes. Derivar antes daria ossos da pose original colados numa
            # pose transformada.
            if self.pais is not None:
                seq = gg.com_ossos(seq, self.pais)
            if self.movimento:
                # Depois dos ossos: assim a velocidade cobre juntas E ossos.
                seq = gg.com_movimento(seq, valido)
            t = torch.from_numpy(gg.para_sequencia(seq))
        else:
            img = rp.para_imagem(seq)                      # (P, W, 3) em [0,1]
            t = torch.from_numpy(img).permute(2, 0, 1)     # (3, P, W)
            t = F.interpolate(t.unsqueeze(0), size=(LADO, LADO),
                              mode="bilinear", align_corners=False).squeeze(0)
        return t, self.indice[clipe.sinal]


def _loader(clipes, rotulos, permutacao, aumentar, batch, workers, embaralhar,
            arquitetura="resnet", ossos=False, movimento=False,
            semente=0, rodada=0):
    ds = DatasetSinais(clipes, rotulos, permutacao, aumentar, semente=semente,
                       arquitetura=arquitetura, ossos=ossos, movimento=movimento,
                       rodada=rodada)
    # Gerador PRÓPRIO, não o RNG global. O global é consumido pela construção do
    # modelo, e variantes com contagens de parâmetros diferentes o deixam em
    # estados diferentes — a ordem dos lotes deixaria de ser comparável entre
    # elas. Aqui a ordem depende só de (semente, rodada).
    g = torch.Generator()
    g.manual_seed(int(semente) * 1000 + rodada)
    return DataLoader(ds, batch_size=batch, shuffle=embaralhar, num_workers=workers,
                      drop_last=embaralhar and len(ds) > batch,
                      generator=g if embaralhar else None)


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


def aplicar_backbone(modelo, caminho: Path, arquitetura: str, args=None) -> int:
    """Carrega pesos de pré-treino no corpo da rede, preservando a cabeça nova.

    A cabeça (`fc`) do backbone previa as classes do corpus de pré-treino — 1.353
    palavras da V-LIBRASIL, por exemplo — e não serve para os 20 sinais daqui. Só
    o corpo transfere. `strict=False` é intencional e a contagem devolvida serve
    de conferência: se o número de tensores carregados vier baixo, o backbone é
    de outra arquitetura e o "pré-treino" seria silenciosamente nenhum.

    A REPRESENTAÇÃO PRECISA BATER, NÃO SÓ A ARQUITETURA E O SHAPE. `--ossos` e
    `--movimento` dobram os canais do MESMO jeito (2→4): um backbone
    pré-treinado com um carregaria, por contagem de canal, dentro de um
    fine-tuning que pediu o outro — pesos aprendidos para "vetor de osso"
    aplicados a "diferença temporal entre quadros", sem erro nenhum.
    `z_recentrado` é pior: mesma contagem de canais, MESMO canal, referencial
    diferente. `load_state_dict` não enxerga nada disso — só compara shape.
    Por isso a checagem abaixo compara as flags de representação salvas no
    backbone (`meta["args"]`) contra as do fine-tuning, e aborta alto em
    qualquer divergência, em vez de deixar o operador confiar num carregamento
    que "funcionou".
    """
    dados = torch.load(caminho, map_location="cpu", weights_only=False)
    if dados.get("arquitetura") != arquitetura:
        raise SystemExit(f"backbone é de '{dados.get('arquitetura')}' mas o treino é "
                         f"'{arquitetura}' — arquiteturas não são intercambiáveis")
    if args is not None and arquitetura == "gcn":
        pre = (dados.get("meta") or {}).get("args") or {}
        CHAVES_REPRESENTACAO = ("com_z", "z_recentrado", "ossos", "movimento")
        diffs = [(k, bool(pre.get(k)), bool(getattr(args, k, False))) for k in CHAVES_REPRESENTACAO
                if bool(pre.get(k)) != bool(getattr(args, k, False))]
        if diffs:
            detalhe = "; ".join(f"{k}: pré-treino={a} fine-tuning={b}" for k, a, b in diffs)
            raise SystemExit(
                f"representação diverge entre pré-treino e fine-tuning ({detalhe}). "
                "Os shapes podem até bater — --ossos e --movimento dobram os canais "
                "igual — mas os pesos foram aprendidos para um referencial diferente "
                "do que vão receber agora. Use exatamente as mesmas flags de "
                "representação (--com-z/--z-recentrado/--ossos/--movimento) nas duas "
                "chamadas, ou refaça o pré-treino com a representação do fine-tuning.")
    pesos = dados["backbone"]
    faltando, inesperados = modelo.load_state_dict(pesos, strict=False)
    carregados = len(pesos) - len(inesperados)
    if inesperados:
        raise SystemExit(f"backbone tem {len(inesperados)} tensores que o modelo não "
                         f"reconhece (ex.: {inesperados[:3]}) — formatos incompatíveis")
    if carregados == 0:
        raise SystemExit("nenhum peso do backbone foi aplicado — o pré-treino seria inócuo")
    return carregados


def canais_gcn(args) -> dict:
    """Config do ST-GCN derivada das flags de representação.

    Um lugar só: espalhar essa conta faria a construção do modelo e a contagem
    de parâmetros divergirem em silêncio, e o erro só apareceria como shape
    mismatch no meio do treino.

    Cada transformação DOBRA os canais, e elas compõem na ordem em que o dataset
    as aplica: coordenadas -> ossos -> movimento. Com x,y: 2 -> 4 -> 8. Com
    x,y,z: 3 -> 6 -> 12.
    """
    c = 3 if getattr(args, "com_z", False) else 2
    if getattr(args, "ossos", False):
        c *= 2
    if getattr(args, "movimento", False):
        c *= 2
    return {"canais_ent": c,
            "adjacencia_adaptativa": bool(getattr(args, "adjacencia_adaptativa", False)),
            "kernel_t": int(getattr(args, "kernel_temporal", 9))}


# Campos que NÃO definem o experimento: mudar só estes pode reaproveitar rodadas.
IGNORAR_NA_RETOMADA = {"saida", "dispositivo", "threads", "workers", "folds"}


def semear(args, rodada: int) -> None:
    """Fixa o acaso da rodada, quando `--semente` é passada.

    POR QUE ISSO EXISTE. A mesma configuração oscila ~1,7 ponto entre execuções
    (docs/CONTEXTO.md §2). Ao comparar duas representações — x,y contra x,y,z,
    digamos — rodar cada uma uma vez mede a diferença entre elas SOMADA ao ruído
    de inicialização, e o ruído é da mesma ordem do efeito esperado. Não dá para
    concluir nada assim.

    Com a semente fixa, as duas variantes partem dos mesmos pesos e recebem a
    mesma sequência de augmentação; o que sobra na diferença é a representação.
    É comparação pareada, e custa uma linha em vez de N execuções por variante.

    Semear por rodada (e não uma vez só) mantém as rodadas diferentes entre si —
    fixar tudo no mesmo valor faria as 8 partições compartilharem a inicialização,
    o que reduz a variância pelo motivo errado.
    """
    s = getattr(args, "semente", None)
    if s is None:
        return
    torch.manual_seed(s + rodada)
    np.random.seed((s + rodada) % 2**32)


def semear_pesos(modelo, args, rodada: int) -> None:
    """Reinicializa cada módulo com semente derivada do NOME dele.

    POR QUE. `semear` fixa o RNG global, mas a construção do modelo consome
    sorteios em ordem, e a primeira convolução consome um número que depende de
    `canais_ent`. Resultado: variantes com contagens de parâmetros diferentes
    recebem pesos diferentes em TODAS as camadas seguintes, inclusive nas de
    forma idêntica. A docstring de `semear` afirmava que "as duas variantes
    partem dos mesmos pesos" — era falso, e inicialização é a maior fonte do
    ruído de ~1,7 pp que separa as variantes que queremos comparar.

    Semeando por nome, camadas de mesma forma recebem os mesmos pesos entre
    variantes; só as que realmente mudaram de forma diferem. O hash é `crc32`,
    não `hash()`, porque `hash()` de string é aleatorizado por processo e a
    semente deixaria de reproduzir entre execuções.

    Só para o ST-GCN: a ResNet nasce com pesos do ImageNet, e reinicializar
    apagaria justamente o pré-treino que a torna competitiva.
    """
    if getattr(args, "semente", None) is None or getattr(args, "arquitetura", "") != "gcn":
        return
    base = int(args.semente) + rodada
    for nome, m in modelo.named_modules():
        if hasattr(m, "reset_parameters"):
            torch.manual_seed((base + zlib.crc32(nome.encode())) % 2**31)
            m.reset_parameters()


def treinar_rodada(treino, validacao, teste, rotulos, permutacao, args, dispositivo,
                   rodada: int = 0):
    """Treina uma rodada e devolve (acurácia no teste, predições, verdadeiros)."""
    semear(args, rodada)
    arq = getattr(args, "arquitetura", "resnet")
    ossos = bool(getattr(args, "ossos", False)) and arq == "gcn"
    construtor = gg.construir if arq == "gcn" else mm.construir
    # canais_ent acompanha a representação: 2 (x,y) ou 3 (x,y,z), e o dobro com
    # ossos, que acrescentam um vetor por dimensão. O checkpoint guarda esse
    # config, então recarregar já vem com o valor certo.
    modelo = construtor(len(rotulos), **(canais_gcn(args) if arq == "gcn" else {}))
    semear_pesos(modelo, args, rodada)
    if getattr(args, "inicializar", None):
        n = aplicar_backbone(modelo, Path(args.inicializar), arq, args)
        print(f"      backbone de pré-treino aplicado ({n} tensores)")
    modelo = modelo.to(dispositivo)
    criterio = nn.CrossEntropyLoss()
    otim = torch.optim.Adam(modelo.parameters(), lr=args.lr, weight_decay=args.wd)
    # Agendador é opcional e desligado por padrão para não alterar o resultado já
    # medido da ResNet (93,5%). Ele existe para o treino DO ZERO, onde a taxa fixa
    # de fine-tuning é inadequada: no começo é lenta demais para sair do nada, e no
    # fim é alta demais para assentar.
    agendador = None
    if getattr(args, "agendador", "nenhum") == "cosseno":
        agendador = torch.optim.lr_scheduler.CosineAnnealingLR(otim, T_max=args.epocas)

    mov = bool(getattr(args, "movimento", False)) and arq == "gcn"
    sem = int(getattr(args, "semente", None) or 0)
    l_treino = _loader(treino, rotulos, permutacao, True, args.batch, args.workers, True,
                       arq, ossos, mov, sem, rodada)
    l_val = _loader(validacao, rotulos, None, False, args.batch, args.workers, False,
                    arq, ossos, mov, sem, rodada)
    l_teste = _loader(teste, rotulos, None, False, args.batch, args.workers, False,
                      arq, ossos, mov, sem, rodada)

    melhor_perda, melhores_pesos, melhor_epoca = float("inf"), None, -1
    for epoca in range(args.epocas):
        # A augmentação depende da época explicitamente; sem isto o mesmo clipe
        # recebe a mesma transformação em todas as épocas quando workers=0.
        l_treino.dataset.epoca = epoca
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

        if agendador is not None:
            agendador.step()
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


def escrever_relatorio(destino: Path, rotulos, accs, nomes_fold, cm, args, segundos, avisos,
                       passos_por_rodada: int = 0):
    acc_media = float(np.mean(accs))
    recall = cm.diagonal() / np.maximum(cm.sum(axis=1), 1)
    ordem = np.argsort(recall)
    pares = sorted(((rotulos[i], rotulos[j], int(cm[i, j]))
                    for i in range(len(rotulos)) for j in range(len(rotulos))
                    if i != j and cm[i, j] > 0), key=lambda t: -t[2])[:10]

    linhas = [
        f"# Resultado do treino — {'ST-GCN (grafo do esqueleto)' if args.arquitetura == 'gcn' else 'Skeleton-DML + ResNet-18 (ImageNet)'}",
        "",
        f"Gerado em {datetime.now():%Y-%m-%d %H:%M} · protocolo leave-one-signer-out "
        f"({len(accs)} rodadas) · {segundos / 60:.0f} min de treino.",
        "",
        f"Orçamento de treino por rodada: ~{passos_por_rodada} atualizações de peso "
        f"({args.epocas} épocas × lotes de {args.batch}). Número relevante quando se "
        "compara fine-tuning com treino do zero: a segunda opção precisa de muito mais.",
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
        (f"- Inicializado a partir do backbone `{args.inicializar}` (pré-treino), "
         "não do ImageNet puro." if getattr(args, "inicializar", None) else
         "- Inicializado do ImageNet (sem pré-treino em Libras)."),
        ("- Landmarks SEM imputação de lacunas." if getattr(args, "sem_imputacao", False)
         else "- Lacunas curtas de mão preenchidas por interpolação (<=5 frames)."),
        (f"- Coordenadas: x, y, z{' (z recentrado no punho)' if args.z_recentrado else ''}."
         if getattr(args, "com_z", False) else "- Coordenadas: x, y (z descartado)."),
        *([f"- ST-GCN: {'ossos, ' if args.ossos else ''}"
           f"{'movimento, ' if args.movimento else ''}"
           f"{'adjacência adaptativa, ' if args.adjacencia_adaptativa else ''}"
           f"kernel temporal {args.kernel_temporal}."]
          if args.arquitetura == "gcn" else []),
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
    ap.add_argument("--arquitetura", default="resnet", choices=["resnet", "gcn"],
                    help="resnet: Skeleton-DML + ResNet-18 (imagem). "
                         "gcn: ST-GCN sobre o grafo do esqueleto.")
    ap.add_argument("--ossos", action="store_true",
                    help="GCN: acrescenta os vetores de osso aos canais de entrada "
                         "(dobra: 2->4, ou 3->6 com --com-z). Ignorado com "
                         "--arquitetura resnet, cuja representação em imagem não "
                         "tem onde encaixá-los.")
    ap.add_argument("--movimento", action="store_true",
                    help="GCN: acrescenta a variação temporal dos canais (dobra). "
                         "Compõe com --ossos: x,y -> 4 -> 8 canais.")
    ap.add_argument("--adjacencia-adaptativa", action="store_true",
                    help="GCN: matriz de adjacência aprendida somada à anatômica, "
                         "permitindo ligações que a anatomia não tem (mão<->rosto). "
                         "Inicia em zero, então parte do modelo atual.")
    ap.add_argument("--kernel-temporal", type=int, default=9,
                    help="GCN: tamanho do kernel da convolução temporal. É onde estão "
                         "~80%% dos parâmetros — reduzir encolhe muito o modelo.")
    ap.add_argument("--com-z", action="store_true",
                    help="usa a terceira coordenada dos landmarks. O .npy sempre tem "
                         "3 dims — não precisa reextrair. Ver PLANO-CORRECOES.md (B5).")
    ap.add_argument("--z-recentrado", action="store_true",
                    help="com --com-z: devolve o z de cada mão ao referencial do "
                         "próprio punho, desfazendo o offset do ombro que extract.py "
                         "aplica indevidamente aos blocos de mão")
    ap.add_argument("--fontes", default="minds", choices=["minds", "vlibrasil", "todas"])
    ap.add_argument("--sem-imputacao", action="store_true",
                    help="desliga o preenchimento de lacunas curtas de mão — existe para "
                         "medir o efeito da imputação contra o mesmo pipeline sem ela")
    ap.add_argument("--epocas", type=int, default=30)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--wd", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--threads", type=int, default=10,
                    help="threads de CPU do torch (deixa folga para a máquina seguir usável)")
    ap.add_argument("--dispositivo", default="auto", choices=["auto", "cpu", "cuda"],
                    help="auto usa GPU quando houver — o mesmo código roda no notebook "
                         "e no Colab/Kaggle sem edição")
    ap.add_argument("--folds", type=int, default=0,
                    help="limita o nº de rodadas (0 = todas). Use 1 para testar o encanamento.")
    ap.add_argument("--semente", type=int, default=None,
                    help="fixa a inicialização e a augmentação. Sem isso, execuções da "
                         "MESMA configuração variam ~1,7 pp e comparar duas variantes "
                         "uma vez cada mede ruído. Use o mesmo valor nas variantes.")
    ap.add_argument("--agendador", default="nenhum", choices=["nenhum", "cosseno"],
                    help="cosseno decai a taxa de aprendizado ao longo das épocas. "
                         "Recomendado para treino do zero (GCN sem pré-treino); "
                         "desnecessário para fine-tuning a partir do ImageNet.")
    ap.add_argument("--inicializar", metavar="CHECKPOINT",
                    help="parte de um backbone de pretreinar.py em vez do ImageNet")
    ap.add_argument("--final", action="store_true",
                    help="treina com TODAS as pessoas e salva o checkpoint (sem avaliação)")
    ap.add_argument("--saida", default=None,
                    help="diretório de saída (padrão: resultados-<arquitetura>)")
    args = ap.parse_args()
    if args.saida is None:
        args.saida = f"resultados-{args.arquitetura}"

    torch.set_num_threads(args.threads)
    if args.dispositivo == "auto":
        args.dispositivo = "cuda" if torch.cuda.is_available() else "cpu"
    dispositivo = torch.device(args.dispositivo)

    cfg = yaml.safe_load(CONFIG.read_text(encoding="utf-8"))
    lm_dir = POC / cfg["paths"]["landmarks"]
    clipes = dd.carregar(lm_dir, fontes=args.fontes, imputar=not args.sem_imputacao,
                         com_z=args.com_z, z_recentrado=args.z_recentrado)
    if not clipes:
        raise SystemExit(f"nenhum landmark em {lm_dir} — rode ../PoC/src/extract.py primeiro.")

    rotulos = dd.rotulos(clipes)
    permutacao = rp.permutacao_espelho(list(cfg["pose_indices"].keys()))
    avisos = dd.conferir(clipes, cfg["vocabulario"])
    for a in avisos:
        print(f"[treino] ⚠ {a}")
    if args.ossos and args.arquitetura != "gcn":
        print("[treino] ⚠ --ossos só vale para --arquitetura gcn; ignorando.")
        args.ossos = False
    if args.z_recentrado and not args.com_z:
        raise SystemExit("--z-recentrado exige --com-z: não há z para recentrar sem ele.")
    extra = canais_gcn(args) if args.arquitetura == "gcn" else {}
    n_par = sum(p.numel() for p in (gg.construir if args.arquitetura == "gcn"
                                    else mm.construir)(len(rotulos), **extra).parameters())
    print(f"[treino] {len(clipes)} clipes | {len(dd.pessoas(clipes))} pessoas | "
          f"{len(rotulos)} sinais | arquitetura={args.arquitetura} ({n_par/1e6:.2f}M par.) "
          f"| repr={'xyz' + ('*' if args.z_recentrado else '') if args.com_z else 'xy'}"
          f"{'+ossos' if args.ossos else ''}{'+mov' if args.movimento else ''}"
          f"{'+adapt' if args.adjacencia_adaptativa else ''}"
          f"{'' if args.kernel_temporal == 9 else f'+k{args.kernel_temporal}'} "
          f"| dispositivo={dispositivo} threads={args.threads}")

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
        # Snapshot antes de treinar. Este modo NÃO é uma avaliação independente:
        # validação reutiliza parte do treino; registrar isso sem disfarçar.
        procedencia = mm.proveniencia_execucao(
            cfg, mm.inventario_final(lm_dir, clipes),
            {"metodo": "final_sem_holdout", "treino_pessoas": todas,
             "validacao_pessoas": [todas[-1]], "teste": [],
             "validacao_sobrepoe_treino": True}, vars(args))
        _, _, _, _, modelo_final = treinar_rodada(treino, val, val, rotulos, permutacao,
                                                  args, dispositivo)
        destino = saida / "modelo_final.pt"
        mm.salvar(modelo_final, destino, rotulos,
                  {"fontes": args.fontes, "pessoas": todas, "args": vars(args),
                   "proveniencia": procedencia,
                   "pontos": cfg["pose_indices"], "limite_escala": rp.LIMITE,
                   "limite_escala_z": rp.LIMITE_Z if args.com_z else None})
        print(f"[treino] checkpoint salvo em {destino}")
        return

    particoes = dd.particoes(clipes)
    if args.folds:
        particoes = particoes[: args.folds]

    # Uma rodada por vez, gravada assim que fecha. Uma execução LOSO do ST-GCN leva
    # ~55 min; oito rodadas passam de 7 h. Guardar tudo só no fim significa que uma
    # sessão morta na rodada 7 perde as sete — e sessão de nuvem morre por limite de
    # tempo, não por erro nosso. Com o parcial em disco, reexecutar retoma de onde
    # parou em vez de recomeçar.
    parciais = saida / "rodadas"
    parciais.mkdir(parents=True, exist_ok=True)

    accs, nomes, todos_preds, todos_reais = [], [], [], []
    for i, part in enumerate(particoes, 1):
        arquivo = parciais / f"{i:02d}-{part.teste}.json"
        if arquivo.is_file():
            r = json.loads(arquivo.read_text(encoding="utf-8"))
            # CONFERIR A CONFIGURAÇÃO antes de reaproveitar. Sem isto, rodar duas
            # variantes no mesmo --saida (e o padrão é resultados-<arquitetura>,
            # igual para todas) faz a segunda herdar as rodadas da primeira em
            # silêncio — e o relatorio.md sai bem-formado, com bloco de
            # reprodutibilidade completo, descrevendo uma configuração que nunca
            # rodou. É o pior modo de falha possível: número plausível e falso.
            iguais = {k: v for k, v in r.get("args", {}).items() if k not in IGNORAR_NA_RETOMADA}
            atuais = {k: v for k, v in vars(args).items() if k not in IGNORAR_NA_RETOMADA}
            if iguais != atuais:
                difs = {k: (iguais.get(k), atuais.get(k))
                        for k in set(iguais) | set(atuais) if iguais.get(k) != atuais.get(k)}
                raise SystemExit(
                    f"[treino] ✗ {arquivo} é de OUTRA configuração (gravado vs atual): "
                    f"{difs}. Use um --saida diferente ou apague {parciais}.")
            print(f"[treino] rodada {i}/{len(particoes)} — {part.teste} já feita "
                  f"({r['acuracia']:.1%}), reaproveitando")
        else:
            treino = [c for c in clipes if c.pessoa in part.treino]
            validacao = [c for c in clipes if c.pessoa == part.validacao]
            teste = [c for c in clipes if c.pessoa == part.teste]
            print(f"\n[treino] rodada {i}/{len(particoes)} — teste={part.teste} "
                  f"val={part.validacao} treino={len(treino)} clipes")
            acc, preds, reais, melhor, _ = treinar_rodada(treino, validacao, teste, rotulos,
                                                          permutacao, args, dispositivo, i)
            print(f"   -> acurácia em {part.teste}: {acc:.1%} (melhor época {melhor + 1})")
            r = {"rodada": i, "teste": part.teste, "validacao": part.validacao,
                 "acuracia": acc, "melhor_epoca": melhor + 1,
                 "predicoes": preds, "verdadeiros": reais,
                 "rotulos": rotulos, "args": vars(args)}
            arquivo.write_text(json.dumps(r, ensure_ascii=False), encoding="utf-8")
        accs.append(r["acuracia"])
        nomes.append(r["teste"])
        todos_preds += r["predicoes"]
        todos_reais += r["verdadeiros"]

    segundos = time.perf_counter() - inicio
    cm = matriz_confusao(todos_preds, todos_reais, len(rotulos))
    print(f"\n[treino] ACURÁCIA signer-independent média = {np.mean(accs):.1%}")
    n_treino = len(particoes[0].treino) * (len(clipes) // max(len(dd.pessoas(clipes)), 1))
    passos = max(n_treino // args.batch, 1) * args.epocas
    escrever_relatorio(saida / "relatorio.md", rotulos, accs, nomes, cm, args, segundos, avisos,
                       passos)
    np.save(saida / "matriz_confusao.npy", cm)


if __name__ == "__main__":
    main()

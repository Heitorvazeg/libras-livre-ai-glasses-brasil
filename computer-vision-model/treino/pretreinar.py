"""Pré-treino: aprende representação num corpus grande, sem medir acurácia.

A IDEIA (docs/decisao-arquitetura-modelo.md, e a proposta do time em 2026-09-08):
com ~800 clipes no vocabulário de avaliação, o que limita o modelo não é a
arquitetura, é quanto ele já viu. A ResNet resolve parte disso vindo do ImageNet
— mas ImageNet são fotos, não Libras. Este script insere um estágio intermediário:

    ImageNet  ->  Libras em geral  ->  os sinais que interessam
    (fotos)       (este script)        (treinar.py, LOSO)

O corpus habilitado aqui é a V-LIBRASIL: grande em vocabulário e pobre em
pessoas (1.353 palavras, 3 articuladores). A ingestão de WLASL existe, mas seu
carregamento e protocolo multi-fonte ainda precisam de um experimento separado.

POR QUE NÃO HÁ LEAVE-ONE-SIGNER-OUT AQUI. Nenhum número deste script vai para
lugar nenhum: o produto dele são os pesos. A acurácia que vale continua saindo
do treinar.py, sobre o MINDS, com uma pessoa inteira fora. A separação é o que
mantém a medição honesta. Os 30 clipes reservados da V-LIBRASIL são excluídos
por origem, mas seus articuladores/domínio aparecem no restante da base:
eles NÃO constituem teste de domínio inédito após este pré-treino.

A classificação usa divisão aleatória 90/10; o contrastivo reserva uma pessoa
e escolhe a época por recuperação top-1 contra a galeria de treino. São métricas
de seleção interna, não resultados de teste externo.

Uso:
    python pretreinar.py --corpus ../PoC/data/landmarks-pretreino --auditar
    python pretreinar.py --corpus ../PoC/data/landmarks-pretreino \\
                         --objetivo contrastivo --pessoa-val V03

Depois:
    python treinar.py --inicializar resultados-pretreino/backbone_resnet.pt
"""
from __future__ import annotations

import argparse
import json
import sys
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
sys.path.insert(0, str(AQUI.parent / "datasets"))
import proveniencia as pv


# Fontes que PODEM entrar no pré-treino. `M` está fora por construção e tem
# guarda própria; WLASL entra aqui porque a auditoria só valida isolamento e
# proveniência — se ele participa de um pré-treino concreto é decisão do comando,
# não desta lista.
PREFIXOS_PRETREINO = ("V", "T", "W")


def auditar_corpora(dirs: list[Path], manifesto: Path = pv.MANIFESTO,
                    avaliacao: Path | None = None) -> dict:
    """Valida TODOS os arquivos, antes de filtros de classe/frames ou modelo.

    Não confundir hash com identidade: origem barra renomeações de um vídeo;
    hash barra cópias com outra origem declarada. Os dois são necessários.
    """
    resolvidos = [d.resolve() for d in dirs]
    if len(set(resolvidos)) != len(resolvidos):
        raise ValueError("diretório de corpus repetido (incluindo symlinks)")
    arquivos = []
    for i, d in enumerate(resolvidos):
        if not d.is_dir() or not list(d.glob("*.npy")):
            raise ValueError(f"diretório de corpus inexistente ou vazio: {d}")
        for p in sorted(d.glob("*.npy")):
            pessoa, _, _ = dd.parse_nome(p.stem)
            # O BLOQUEIO DO MINDS É DENY-LIST, E CONTINUA SENDO. Ele é a única
            # coisa que impede o conjunto de avaliação de entrar no pré-treino, e
            # errar aqui não dá erro: dá um número de LOSO bom demais. Por isso
            # ele é uma condição própria e explícita, e não uma consequência de
            # `M` faltar numa lista de permitidos — onde acrescentá-lo por engano
            # passaria despercebido.
            if pessoa.startswith("M"):
                raise ValueError(f"MINDS (prefixo M) proibido no pré-treino: {p.name}")
            # Prefixo desconhecido também é erro: um arquivo fora da convenção
            # entraria como classe fantasma sem ninguém notar.
            if not pessoa.startswith(PREFIXOS_PRETREINO):
                raise ValueError(
                    f"prefixo não reconhecido no pré-treino: {p.name}; "
                    f"esperado um de {PREFIXOS_PRETREINO} (V-LIBRASIL, MALTA, WLASL)")
            arquivos.append((i, p))
    reservas = pv.ler_reservas(manifesto)
    if not any(r["fonte"] == "vlibrasil" for r in reservas):
        raise ValueError("manifesto sem reservas V-LIBRASIL")
    origens_reservadas = {(r["fonte"], r["origem"]) for r in reservas}
    if avaliacao is None:
        import yaml
        cfg = yaml.safe_load((AQUI.parent / "PoC" / "config.yaml").read_text(encoding="utf-8"))
        avaliacao = AQUI.parent / "PoC" / cfg["paths"]["landmarks"]
    hashes_avaliacao = {pv.hash_arquivo(p) for p in avaliacao.glob("*.npy")}
    videos_avaliacao = set()
    for p in avaliacao.glob("*.npy"):
        if pv.sidecar(p).exists():
            videos_avaliacao.add(pv.ler(p)["video"]["sha256"])
    amostras, origens, hashes, videos = [], set(), set(), set()
    for i, p in arquivos:
        r = pv.ler(p)
        origem = (r["fonte"], r["origem"])
        h, hv = r["landmarks"]["sha256"], r["video"]["sha256"]
        if origem in origens_reservadas or h in hashes_avaliacao or hv in videos_avaliacao:
            raise ValueError(f"clipe reservado para avaliação no corpus: {p.name} ({r['origem']})")
        if origem in origens or h in hashes or hv in videos:
            raise ValueError(f"amostra duplicada por origem/hash: {p.name}")
        origens.add(origem)
        hashes.add(h)
        videos.add(hv)
        amostras.append({"corpus": i, "arquivo": p.name, "id": pv.hash_json(origem),
                         "registro": r})
    return {"schema": 1, "manifesto_avaliacao_sha256": pv.hash_arquivo(manifesto),
            "reservas": reservas, "manifesto_corpus_sha256": pv.hash_json(amostras),
            "amostras": amostras}


def carregar_corpora(dirs: list[Path], min_clipes_por_classe: int,
                     *, manifesto: Path = pv.MANIFESTO,
                     auditoria: dict | None = None,
                     fontes: str = "vlibrasil,malta") -> list[dd.Clipe]:
    """Junta um ou mais diretórios de landmarks num corpus só.

    Classes com pouquíssimos exemplos são descartadas: elas não ensinam
    representação (o modelo decora), inflam a camada de saída e desequilibram o
    treino. O corte é explícito para que a perda apareça no log, não em silêncio.
    """
    verificado = auditar_corpora(dirs, manifesto)
    if auditoria is not None:
        auditoria.update(verificado)
    registros = {(r["corpus"], r["arquivo"]): r for r in verificado["amostras"]}
    clipes: list[dd.Clipe] = []
    for i, d in enumerate(dirs):
        antes = len(clipes)
        # Tinha `fontes="vlibrasil"` fixo aqui. Depois de a auditoria passar a
        # aceitar MALTA, isso virava perda SILENCIOSA: os clipes eram auditados,
        # aprovados, e sumiam na leitura — o pré-treino rodava com menos dado do
        # que o log de auditoria dizia ter.
        novos = dd.carregar(d, fontes=fontes)
        for c in novos:
            nome = f"pessoa{c.pessoa}_sinal-{c.sinal}_rep{c.rep}.npy"
            c.proveniencia = registros[i, nome]
        clipes += novos
        print(f"[pretreino] {d.name}: {len(clipes) - antes} clipes")

    # A AUDITORIA E A LEITURA PRECISAM CONCORDAR. Elas têm listas de fontes
    # independentes: a auditoria aceita V, T e W; `fontes` decide o que é lido.
    # Quando divergem, o arquivo é auditado, aprovado, contado no log — e some na
    # leitura. O operador lê "auditoria OK: 24 amostras" e treina com 16.
    # Já aconteceu duas vezes neste arquivo (MALTA, depois WLASL), então aqui a
    # divergência aborta em vez de virar uma linha de log que ninguém cruza.
    auditados = {dd.parse_nome(Path(r["arquivo"]).stem)[0][:1]
                 for r in verificado["amostras"]}
    lidos = {c.pessoa[:1] for c in clipes}
    if auditados - lidos:
        faltam = ", ".join(sorted(auditados - lidos))
        raise ValueError(
            f"a auditoria aprovou clipes com prefixo {faltam}, mas fontes={fontes!r} "
            f"não os lê — seriam descartados em silêncio. Inclua a fonte "
            f"correspondente em --fontes ou retire o corpus do comando.")

    contagem: dict[str, int] = {}
    for c in clipes:
        contagem[c.sinal] = contagem.get(c.sinal, 0) + 1
    mantidos = [c for c in clipes if contagem[c.sinal] >= min_clipes_por_classe]
    cortadas = len(contagem) - len({c.sinal for c in mantidos})
    if cortadas:
        print(f"[pretreino] {cortadas} classe(s) descartada(s) por ter < "
              f"{min_clipes_por_classe} clipes ({len(clipes) - len(mantidos)} clipes)")
    return mantidos


def separar_por_pessoa(clipes: list[dd.Clipe], pessoa_val: str):
    """Reserva um articulador inteiro para a validação.

    Divisão aleatória por clipe não serve ao contrastivo: com 3 clipes por classe
    ela deixa quase nenhum par positivo na validação (medido: 3% dos clipes). E,
    mais importante, reservar uma PESSOA faz a validação medir exatamente o que o
    pré-treino promete ensinar — reconhecer o sinal num corpo que não foi visto.
    """
    treino = [c for c in clipes if c.pessoa != pessoa_val]
    val = [c for c in clipes if c.pessoa == pessoa_val]
    return treino, val


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


def _embutir(modelo, loader, disp):
    """Embeddings L2-normalizados do backbone (sem a cabeça de projeção)."""
    vetores, rotulos = [], []
    for x, y in loader:
        v = modelo(x.to(disp))
        vetores.append(nn.functional.normalize(v, dim=1).cpu())
        rotulos += [int(i) for i in y]
    if not vetores:
        return torch.empty(0), []
    return torch.cat(vetores), rotulos


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--corpus", action="append", required=True, type=Path,
                    help="diretório de landmarks (repita para juntar vários)")
    ap.add_argument("--manifesto-avaliacao", type=Path, default=pv.MANIFESTO)
    ap.add_argument("--auditar", action="store_true",
                    help="confere isolamento/proveniência e sai sem construir ou treinar modelo")
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
    ap.add_argument("--pessoa-val",
                    help="contrastivo: articulador reservado para validação "
                         "(padrão: o último em ordem alfabética)")
    ap.add_argument("--epocas", type=int, default=15)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--wd", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--threads", type=int, default=10)
    ap.add_argument("--dispositivo", default="auto", choices=["auto", "cpu", "cuda"])
    ap.add_argument("--min-clipes-por-classe", type=int, default=2)
    ap.add_argument("--fontes", default="vlibrasil,malta",
                    help="corpora a LER, separados por vírgula: vlibrasil, malta, "
                         "wlasl. O padrão cobre Libras; WLASL é ASL e entra só "
                         "quando pedido, porque é experimento separado. A auditoria "
                         "aborta se aprovar um prefixo que esta lista não lê.")
    ap.add_argument("--fracao-val", type=float, default=0.1)
    ap.add_argument("--semente", type=int, default=0)
    ap.add_argument("--saida", default="resultados-pretreino")
    args = ap.parse_args()

    torch.set_num_threads(args.threads)
    if args.dispositivo == "auto":
        args.dispositivo = "cuda" if torch.cuda.is_available() else "cpu"
    disp = torch.device(args.dispositivo)
    torch.manual_seed(args.semente)

    auditoria: dict = {}
    try:
        clipes = carregar_corpora(args.corpus, args.min_clipes_por_classe,
                                  fontes=args.fontes,
                                  manifesto=args.manifesto_avaliacao, auditoria=auditoria)
    except (ValueError, OSError) as e:
        raise SystemExit(f"[auditoria] ABORTADO: {e}") from e
    if not clipes:
        raise SystemExit("corpus vazio — confira os diretórios passados em --corpus")
    print(f"[auditoria] OK: {len(auditoria['amostras'])} amostras sem sobreposição; "
          f"manifesto {auditoria['manifesto_corpus_sha256']}")
    if args.auditar:
        return
    if args.epocas < 1 or args.batch < 2 or not 0 < args.fracao_val < 1:
        raise SystemExit("epocas >= 1, batch >= 2 e 0 < fracao-val < 1 são obrigatórios")
    rotulos = dd.rotulos(clipes)
    if args.objetivo == "contrastivo":
        pessoa_val = args.pessoa_val or sorted(dd.pessoas(clipes))[-1]
        treino, val = separar_por_pessoa(clipes, pessoa_val)
        if not val:
            raise SystemExit(f"pessoa de validação {pessoa_val!r} não existe no corpus")
        print(f"[pretreino] validação = articulador {pessoa_val} inteiro "
              f"(recuperação entre pessoas), não divisão aleatória")
    else:
        treino, val = separar(clipes, args.fracao_val, args.semente)
    if not treino or not val:
        raise SystemExit("partição de treino/validação vazia")
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
            return DataLoader(ds, batch_size=amostrador.p * amostrador.k,
                              sampler=amostrador, num_workers=args.workers, drop_last=True)
        return DataLoader(ds, batch_size=args.batch, shuffle=shuffle,
                          num_workers=args.workers, drop_last=shuffle and len(ds) > args.batch)

    l_treino, l_val = loader(treino, True, True), loader(val, False, False)
    if len(l_treino) == 0:
        raise SystemExit("nenhum lote de treino válido após amostragem")
    # Galeria: os clipes de treino SEM augmentação, para a métrica de recuperação.
    l_galeria = loader(treino, False, False) if args.objetivo == "contrastivo" else None

    def ids(grupo):
        return [c.proveniencia["id"] for c in grupo]

    elegiveis = (set(l_treino.sampler.classes) if args.objetivo == "contrastivo"
                 else set(rotulos))
    particao = {"metodo": "por_pessoa" if args.objetivo == "contrastivo" else "aleatoria",
                "treino": ids(treino), "validacao": ids(val), "teste": [],
                "galeria": ids(treino) if l_galeria is not None else [],
                "otimizacao_elegiveis": ids([c for c in treino if c.sinal in elegiveis]),
                "descartadas": [r["id"] for r in auditoria["amostras"]
                                if r["id"] not in set(ids(clipes))]}
    procedencia = mm.proveniencia_execucao(cfg, auditoria, particao, vars(args))

    melhor = -1.0 if args.objetivo == "contrastivo" else float("inf")
    melhores_pesos, melhor_epoca = None, -1
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
            if cabeca is not None:
                # Recuperação: consulta = articulador reservado, galeria = os de
                # treino. Mede "reconheço este sinal num corpo que não vi?".
                emb_q, rot_q = _embutir(modelo, l_val, disp)
                emb_g, rot_g = _embutir(modelo, l_galeria, disp)
                recuperacao = ct.acuracia_recuperacao(emb_q, rot_q, emb_g, rot_g)
                metrica, melhor_e_maior = recuperacao, True
            else:
                for x, y in l_val:
                    x, y = x.to(disp), y.to(disp)
                    s = modelo(x)
                    vperda += criterio(s, y).item() * y.size(0)
                    vcertos += (s.argmax(1) == y).sum().item()
                    vtotal += y.size(0)
                vperda /= max(vtotal, 1)
                metrica, melhor_e_maior = vperda, False

        if (metrica > melhor) if melhor_e_maior else (metrica < melhor):
            melhor, melhor_epoca = metrica, epoca
            melhores_pesos = {k: v.detach().clone() for k, v in modelo.state_dict().items()}
        if cabeca is not None:
            print(f"  época {epoca + 1:>2}/{args.epocas}  perda {soma / max(total,1):.3f}"
                  f"  recuperação(pessoa nova) {metrica:.1%}"
                  f"{'  <- melhor' if melhor_epoca == epoca else ''}", flush=True)
        else:
            print(f"  época {epoca + 1:>2}/{args.epocas}  treino {soma / max(total,1):.3f}"
                  f"/{certos / max(total,1):.1%}  val {vperda:.3f}/{vcertos / max(vtotal,1):.1%}"
                  f"{'  <- melhor' if melhor_epoca == epoca else ''}", flush=True)

    if melhores_pesos is not None:
        modelo.load_state_dict(melhores_pesos)

    saida = AQUI / args.saida
    meta = {"corpora": [str(d) for d in args.corpus], "objetivo": args.objetivo,
            "classes": len(rotulos),
            "clipes": len(clipes), "pessoas": dd.pessoas(clipes),
            "proveniencia": procedencia,
            "melhor_metrica": melhor,
            "metrica": "recuperacao_top1" if args.objetivo == "contrastivo" else "entropia_cruzada",
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

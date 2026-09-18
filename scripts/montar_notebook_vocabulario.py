#!/usr/bin/env python3
"""Gera um notebook PRIVADO com vocabulário reduzido, a partir do notebook final.

Menos classes = menos chance de confusão. Este gerador produz duas variantes da
MESMA receita de entrega, mudando só o conjunto de palavras:

    --modo final   checkpoint de entrega (todas as pessoas, última época)
    --modo loso    8 rodadas com evidências, para medir o que a redução rendeu

O corpus reduzido não é um upload novo: o notebook valida o MINDS completo (800
clipes, guarda de sempre) e só então copia os clipes das palavras escolhidas,
conferindo o sha256 de cada arquivo contra o inventário. Assim o corpus de
treino é rastreável até o corpus completo, e `inventario-vocabulario.json`
registra exatamente o que entrou.

Uso:
    python3 scripts/montar_notebook_vocabulario.py --sha <40 hex> \\
        --saida <pasta> --modo final [--palavras a,b,c]
"""
from __future__ import annotations

import argparse
import ast
import json
import re
import subprocess
from pathlib import Path

from montar_notebook_loso import (DATASETS, IMAGEM, NOTEBOOK, RAIZ,
                                  trocar_nome_experimento)

# Palavras com alguma relação com atendimento, escolhidas pelo usuário.
PALAVRAS_PADRAO = ("banco", "banheiro", "cinco", "conhecer", "esquina", "filho",
                   "medo", "ruim", "vacina", "vontade")

REVALIDA = '''# Revalidar imediatamente antes da chamada longa, inclusive se células foram
# executadas fora de ordem.
codigo_final.conferir_codigo(REPO, COMMIT_APROVADO)
entrada_final.validar_minds(MINDS, manifesto_referencia=INVENTARIO_CAMINHO)
if entrada.pv.hash_arquivo(BACKBONE) != HASH_BACKBONE_APROVADO:
    raise RuntimeError("Backbone mudou após o preflight.")

'''

# Só o recorte, sem as guardas acima: assim o teste executa este trecho contra um
# corpus fictício, que é a única parte da receita que não existe no notebook original.
RECORTE = '''import hashlib

# Vocabulário reduzido. A guarda dos 800 clipes continua valendo acima: o
# recorte sai do corpus JÁ validado e cada clipe é conferido pelo sha256 do
# inventário, então o corpus reduzido é rastreável ao completo.
PALAVRAS = __PALAVRAS__
if len(set(PALAVRAS)) != len(PALAVRAS) or not set(PALAVRAS) <= set(INVENTARIO_MINDS["rotulos"]):
    raise RuntimeError("Vocabulário inválido: palavra repetida ou fora dos rótulos do MINDS.")
MINDS_VOCAB = DESTINO / "minds-vocabulario"
if MINDS_VOCAB.exists():
    raise RuntimeError(f"{MINDS_VOCAB} já existe; use destino novo, sem sobrescrever.")
MINDS_VOCAB.mkdir(parents=True)
selecionadas = [a for a in INVENTARIO_MINDS["amostras"] if a["sinal"] in PALAVRAS]
esperado = len(PALAVRAS) * len(INVENTARIO_MINDS["pessoas"]) * 5
if len(selecionadas) != esperado:
    raise RuntimeError(f"{len(selecionadas)} clipes no recorte; esperado {esperado}.")
for amostra in selecionadas:
    bruto = (MINDS / amostra["arquivo"]).read_bytes()
    if hashlib.sha256(bruto).hexdigest() != amostra["sha256"]:
        raise RuntimeError(f"Clipe {amostra['arquivo']} diverge do inventário validado.")
    (MINDS_VOCAB / amostra["arquivo"]).write_bytes(bruto)
INVENTARIO_VOCAB = {
    "versao": 1, "palavras": sorted(PALAVRAS), "n_clipes": len(selecionadas),
    "pessoas": list(INVENTARIO_MINDS["pessoas"]), "amostras": selecionadas,
    "origem_corpus_sha256": INVENTARIO_MINDS["corpus_sha256"],
}
registrar("inventario-vocabulario.json", INVENTARIO_VOCAB)
print(f"vocabulário reduzido: {len(PALAVRAS)} palavras | {len(selecionadas)} clipes | "
      f"origem {INVENTARIO_MINDS['corpus_sha256'][:12]}")
'''

FINAL = '''
SAIDA_FINAL = EXP / "modelo_final"
FINAL_ARGS = [
    "--arquitetura", "gcn", "--ossos", "--com-z", "--z-recentrado",
    "--kernel-temporal", "9", "--fontes", "minds", "--landmarks", str(MINDS_VOCAB),
    "--inicializar", str(BACKBONE),
    # Sem --inventario-final de propósito: essa guarda exige as 800 identidades do
    # MINDS completo e recusaria o recorte. A validação das 800 já rodou acima, e o
    # recorte é conferido clipe a clipe pelo sha256 do inventário.
    "--epocas", "120", "--lr", "1e-3", "--wd", "1e-4", "--batch", "64",
    "--agendador", "cosseno",
    "--final", "--politica-final", "ultima", "--semente", "20260917",
    "--dispositivo", "cuda", "--threads", "4", "--workers", "2", "--saida", str(SAIDA_FINAL),
]
registrar("comando-final.json", FINAL_ARGS)
executar("treinar.py", FINAL_ARGS, "treino-final.log")

destino = SAIDA_FINAL / "modelo_final.pt"
if not destino.is_file():
    raise RuntimeError("treinar.py terminou sem gerar modelo_final.pt.")
# Somente checkpoint próprio, gerado nesta run. Não carregar pickle de terceiros.
checkpoint = torch.load(destino, map_location="cpu", weights_only=False)
meta = checkpoint["meta"]
if (meta["epoca_salva"] != 120 or meta["politica_selecao"] != "ultima"
        or meta["avaliacao_independente"] is not False
        or meta["backbone_sha256"] != HASH_BACKBONE_APROVADO
        or checkpoint["rotulos"] != sorted(PALAVRAS)
        or sorted(meta["pessoas"]) != sorted(INVENTARIO_MINDS["pessoas"])
        or meta.get("extras") is not None
        or bool(meta["args"].get("aug_dominio"))
        or meta["proveniencia"]["codigo"]["commit"] != COMMIT_APROVADO
        or any(not torch.isfinite(v).all() for v in checkpoint["state_dict"].values())):
    raise RuntimeError("Checkpoint não corresponde à receita desta variante; não exportar.")
registrar("checkpoint-final.json", {"sha256": entrada.pv.hash_arquivo(destino),
                                    "epoca": 120, "aprovado_entrega": False})
rotulos_entregues = list(checkpoint["rotulos"])
del checkpoint
print("Checkpoint final:", destino, "|", entrada.pv.hash_arquivo(destino))
print("rótulos:", rotulos_entregues)
'''

LOSO = '''
SAIDA_LOSO = EXP / "loso"
LOSO_ARGS = [
    "--arquitetura", "gcn", "--ossos", "--com-z", "--z-recentrado",
    "--kernel-temporal", "9", "--fontes", "minds", "--landmarks", str(MINDS_VOCAB),
    "--inicializar", str(BACKBONE),
    "--epocas", "120", "--lr", "1e-3", "--wd", "1e-4", "--batch", "64",
    "--agendador", "cosseno", "--semente", "20260917",
    "--dispositivo", "cuda", "--threads", "4", "--workers", "2", "--saida", str(SAIDA_LOSO),
    "--salvar-evidencias",
]
registrar("comando-loso.json", LOSO_ARGS)
executar("treinar.py", LOSO_ARGS, "treino-loso.log")

rodadas = sorted((SAIDA_LOSO / "rodadas").glob("*.json"))
if len(rodadas) != 8 or not (SAIDA_LOSO / "relatorio.md").is_file():
    raise RuntimeError(f"LOSO incompleto: {len(rodadas)} rodadas.")
resumo = []
for arquivo in rodadas:
    r = json.loads(arquivo.read_text(encoding="utf-8"))
    if (r["args"].get("semente") != 20260917 or r["args"].get("epocas") != 120
            or r.get("extras") is not None
            or not r["args"].get("salvar_evidencias") or "evidencias" not in r):
        raise RuntimeError(f"Rodada {arquivo.name} não corresponde à receita desta verificação.")
    resumo.append({k: r[k] for k in ("rodada", "teste", "validacao", "acuracia", "melhor_epoca")})
media = sum(x["acuracia"] for x in resumo) / len(resumo)
registrar("loso-resumo.json", {"palavras": sorted(PALAVRAS), "media": media, "rodadas": resumo,
                               "commit": COMMIT_APROVADO})
for x in resumo:
    print(f"{x['rodada']} {x['teste']} val={x['validacao']} {x['acuracia']:.1%} (época {x['melhor_epoca']})")
print(f"LOSO média ({len(PALAVRAS)} palavras): {media:.2%}")
'''


def gerar(sha: str, modo: str, palavras: tuple[str, ...], nome: str) -> dict:
    fonte = subprocess.run(["git", "-C", str(RAIZ), "show", f"{sha}:{NOTEBOOK}"],
                           capture_output=True, text=True, check=True).stdout
    nb = json.loads(fonte)

    def trocar(antes: str, depois: str) -> None:
        alvos = [c for c in nb["cells"] if c["cell_type"] == "code" and antes in "".join(c["source"])]
        if len(alvos) != 1 or "".join(alvos[0]["source"]).count(antes) != 1:
            raise SystemExit(f"trecho esperado não encontrado uma única vez: {antes[:60]!r}")
        alvos[0]["source"] = "".join(alvos[0]["source"]).replace(antes, depois).splitlines(keepends=True)

    trocar('os.environ.get("LIBRAS_COMMIT_FINAL", "")', f'os.environ.get("LIBRAS_COMMIT_FINAL", "{sha}")')
    trocar('AUG_DOMINIO = os.environ.get("LIBRAS_AUG_DOMINIO", "0") == "1"', "AUG_DOMINIO = False")
    trocar('EXTRAS_EXTERNOS = os.environ.get("LIBRAS_EXTRAS_EXTERNOS", "0") == "1"',
           "EXTRAS_EXTERNOS = False")

    alvo = [c for c in nb["cells"] if c["cell_type"] == "code"
            and 'executar("treinar.py", FINAL_ARGS' in "".join(c["source"])]
    if len(alvo) != 1:
        raise SystemExit("célula do treino final não encontrada")
    lista = "[" + ", ".join(f'"{p}"' for p in palavras) + "]"
    corpo = REVALIDA + RECORTE.replace("__PALAVRAS__", lista) + (FINAL if modo == "final" else LOSO)
    alvo[0]["source"] = corpo.splitlines(keepends=True)

    nomeada = [c for c in nb["cells"] if c["cell_type"] == "code" and "NOME_EXPERIMENTO" in "".join(c["source"])]
    if len(nomeada) != 1:
        raise SystemExit("célula do nome do experimento não encontrada")
    nomeada[0]["source"] = trocar_nome_experimento("".join(nomeada[0]["source"]), nome).splitlines(keepends=True)

    for celula in nb["cells"]:
        if celula["cell_type"] == "code":
            celula["outputs"], celula["execution_count"] = [], None
            ast.parse("".join(celula["source"]))

    mds = [c for c in nb["cells"] if c["cell_type"] == "markdown"]
    quais = ", ".join(sorted(palavras))
    mds[0]["source"] = (
        f"# Vocabulário reduzido — `{nome}`\n\nDerivado do notebook final do commit `{sha[:7]}` por "
        f"`scripts/montar_notebook_vocabulario.py --modo {modo}`. Receita de entrega inalterada; muda "
        f"só o conjunto de palavras ({len(palavras)}): {quais}.\n\nO MINDS completo continua sendo "
        "validado (800 clipes) antes do recorte, e cada clipe do recorte é conferido pelo sha256 do "
        "inventário.\n"
    ).splitlines(keepends=True)
    for c in mds[1:]:
        texto = "".join(c["source"])
        for antes in ("O experimento vira `final-s20260917-augdom-v1`.",
                      "O experimento vira `final-s20260917-extras-v1`."):
            texto = texto.replace(antes, "Não usada neste notebook.")
        if texto.lstrip().startswith("## 4."):
            titulo = "O treino final" if modo == "final" else "LOSO"
            texto = (f"## 4. {titulo} — vocabulário reduzido\n\nA célula recorta o MINDS validado para "
                     f"{len(palavras)} palavras, registra `inventario-vocabulario.json` e roda a receita "
                     "de entrega sobre esse recorte.\n")
        c["source"] = texto.splitlines(keepends=True)
    return nb


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--sha", required=True, help="commit publicado que o Kaggle vai clonar")
    ap.add_argument("--saida", type=Path, required=True)
    ap.add_argument("--modo", choices=["final", "loso"], required=True)
    ap.add_argument("--palavras", default=",".join(PALAVRAS_PADRAO))
    ap.add_argument("--nome")
    ap.add_argument("--kernel")
    args = ap.parse_args(argv)
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        ap.error("--sha precisa ser o SHA completo (40 hex) de um commit já publicado")
    palavras = tuple(p.strip().lower() for p in args.palavras.split(",") if p.strip())
    if len(palavras) < 2 or len(set(palavras)) != len(palavras):
        ap.error("--palavras precisa ter pelo menos duas palavras distintas")
    if not all(re.fullmatch(r"[a-z]+", p) for p in palavras):
        ap.error("--palavras aceita só letras minúsculas sem acento, como nos arquivos do MINDS")

    nome = args.nome or f"{args.modo}-s20260917-vocab{len(palavras)}-v1"
    kernel = args.kernel or f"{args.modo}-vocab{len(palavras)}"
    nb = gerar(args.sha, args.modo, palavras, nome)
    args.saida.mkdir(parents=True, exist_ok=True)
    arquivo = args.saida / f"notebook_{nome.replace('-', '_')}.ipynb"
    arquivo.write_text(json.dumps(nb, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (args.saida / "kernel-metadata.json").write_text(json.dumps({
        "id": f"walissonfagundes/{kernel}", "title": kernel, "code_file": arquivo.name,
        "language": "python", "kernel_type": "notebook", "is_private": True,
        "enable_gpu": True, "enable_tpu": False, "enable_internet": True, "keywords": ["gpu"],
        "dataset_sources": DATASETS, "kernel_sources": [], "competition_sources": [],
        "model_sources": [], "docker_image": IMAGEM, "machine_shape": "NvidiaTeslaT4",
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"notebook: {arquivo}\nkernel:   walissonfagundes/{kernel}\nexperimento: {nome}\n"
          f"palavras ({len(palavras)}): {', '.join(sorted(palavras))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

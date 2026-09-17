#!/usr/bin/env python3
"""Gera um notebook PRIVADO de LOSO a partir do notebook final versionado.

O notebook final (`computer-vision-model/treino/notebook_treino_final.ipynb`)
treina com todas as pessoas e não avalia. Este gerador troca só a célula de
treino por uma de LOSO, preservando todas as guardas: commit fixo e conferido,
validação dos 800 clipes MINDS, hash do backbone, preflight, selftest e
empacotamento privado no fim.

O notebook resultante NÃO vai para o Git (roda no Kaggle, a partir do clone do
commit informado). Uso:

    python3 scripts/montar_notebook_loso.py --sha <40 hex> --saida <pasta> \\
        [--evidencias] [--nome loso-s20260917-evid-v1] [--kernel etapa3-loso-evid]

`--evidencias` acrescenta `--salvar-evidencias`, que preserva por rodada o
melhor checkpoint e os logits de validação/teste — necessário para simular a
decisão do app por frase (scripts/simular_frase_roteiro.py).
"""
from __future__ import annotations

import argparse
import ast
import json
import re
import subprocess
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[1]
NOTEBOOK = "computer-vision-model/treino/notebook_treino_final.ipynb"
IMAGEM = ("gcr.io/kaggle-private-byod/python@sha256:"
          "37c64f7dd9c54116ecd1bcc88817c5469b88387388fade02bfa8bf3fc647d461")
DATASETS = ["walissonfagundes/backbone-gcn-aprovado", "walissonfagundes/landmarks-pretreino-pacotes"]

CELULA = '''# Revalidar imediatamente antes da chamada longa, inclusive se células foram
# executadas fora de ordem.
codigo_final.conferir_codigo(REPO, COMMIT_APROVADO)
entrada_final.validar_minds(MINDS, manifesto_referencia=INVENTARIO_CAMINHO)
if entrada.pv.hash_arquivo(BACKBONE) != HASH_BACKBONE_APROVADO:
    raise RuntimeError("Backbone mudou após o preflight.")
SAIDA_LOSO = EXP / "loso"
LOSO_ARGS = [
    "--arquitetura", "gcn", "--ossos", "--com-z", "--z-recentrado",
    "--kernel-temporal", "9", "--fontes", "minds", "--landmarks", str(MINDS),
    "--inicializar", str(BACKBONE),
    "--epocas", "120", "--lr", "1e-3", "--wd", "1e-4", "--batch", "64",
    "--agendador", "cosseno", "--semente", "20260917",
    "--dispositivo", "cuda", "--threads", "4", "--workers", "2", "--saida", str(SAIDA_LOSO),
]__EVIDENCIAS__
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
            or bool(r["args"].get("salvar_evidencias")) != COM_EVIDENCIAS
            or (COM_EVIDENCIAS and "evidencias" not in r)):
        raise RuntimeError(f"Rodada {arquivo.name} não corresponde à receita desta verificação.")
    resumo.append({k: r[k] for k in ("rodada", "teste", "validacao", "acuracia", "melhor_epoca")})
media = sum(x["acuracia"] for x in resumo) / len(resumo)
registrar("loso-resumo.json", {"evidencias": COM_EVIDENCIAS, "media": media, "rodadas": resumo,
                               "commit": COMMIT_APROVADO})
for x in resumo:
    print(f"{x['rodada']} {x['teste']} val={x['validacao']} {x['acuracia']:.1%} (época {x['melhor_epoca']})")
print(f"LOSO média: {media:.2%} | evidências={COM_EVIDENCIAS}")
'''


def trocar_nome_experimento(fonte: str, nome: str) -> str:
    """Substitui a atribuição inteira de NOME_EXPERIMENTO, que ocupa mais de uma linha.

    A versão anterior cortava só até a primeira quebra de linha e deixava o resto
    da expressão órfão — o notebook quebrava no Kaggle com IndentationError.
    """
    novo, trocas = re.subn(r"NOME_EXPERIMENTO = \((?:[^()]*)\)|NOME_EXPERIMENTO = [^\n]*",
                           f'NOME_EXPERIMENTO = "{nome}"', fonte, count=1)
    if trocas != 1:
        raise SystemExit("atribuição de NOME_EXPERIMENTO não encontrada")
    return novo


def gerar(sha: str, evidencias: bool, nome: str) -> dict:
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
           f"EXTRAS_EXTERNOS = False\nCOM_EVIDENCIAS = {evidencias}")
    alvo = [c for c in nb["cells"] if c["cell_type"] == "code"
            and 'executar("treinar.py", FINAL_ARGS' in "".join(c["source"])]
    if len(alvo) != 1:
        raise SystemExit("célula do treino final não encontrada")
    extra = '\nLOSO_ARGS.append("--salvar-evidencias")' if evidencias else ""
    alvo[0]["source"] = CELULA.replace("__EVIDENCIAS__", extra).splitlines(keepends=True)

    nomeada = [c for c in nb["cells"] if c["cell_type"] == "code" and "NOME_EXPERIMENTO" in "".join(c["source"])]
    if len(nomeada) != 1:
        raise SystemExit("célula do nome do experimento não encontrada")
    nomeada[0]["source"] = trocar_nome_experimento("".join(nomeada[0]["source"]), nome).splitlines(keepends=True)

    for celula in nb["cells"]:
        if celula["cell_type"] == "code":
            celula["outputs"], celula["execution_count"] = [], None
            ast.parse("".join(celula["source"]))

    mds = [c for c in nb["cells"] if c["cell_type"] == "markdown"]
    mds[0]["source"] = (
        f"# LOSO — `{nome}`\n\nDerivado do notebook final do commit `{sha[:7]}` por "
        "`scripts/montar_notebook_loso.py`. Roda LOSO completo (8 rodadas: cada pessoa do MINDS é "
        "teste uma vez, a seguinte valida a escolha de época), com a receita de entrega e sem "
        f"clipes externos. Evidências por rodada: {evidencias}. Não gera checkpoint de entrega."
    ).splitlines(keepends=True)
    for c in mds[1:]:
        texto = "".join(c["source"])
        for antes in ("O experimento vira `final-s20260917-augdom-v1`.",
                      "O experimento vira `final-s20260917-extras-v1`."):
            texto = texto.replace(antes, "Não usada neste notebook.")
        if texto.lstrip().startswith("## 4."):
            texto = ("## 4. LOSO\n\n8 rodadas, com a época escolhida pela pessoa de validação. A célula "
                     "falha se faltar rodada ou relatório, confere a receita de cada rodada e registra o "
                     "resumo em `loso-resumo.json`.\n")
        c["source"] = texto.splitlines(keepends=True)
    return nb


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--sha", required=True, help="commit publicado que o Kaggle vai clonar")
    ap.add_argument("--saida", type=Path, required=True, help="pasta do notebook + kernel-metadata.json")
    ap.add_argument("--evidencias", action="store_true", help="acrescenta --salvar-evidencias")
    ap.add_argument("--nome", help="nome do experimento (padrão: loso-s20260917-{evid,ref}-v1)")
    ap.add_argument("--kernel", help="slug do kernel (padrão: etapa3-<nome>)")
    args = ap.parse_args(argv)
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        ap.error("--sha precisa ser o SHA completo (40 hex) de um commit já publicado")
    nome = args.nome or f"loso-s20260917-{'evid' if args.evidencias else 'ref'}-v1"
    kernel = args.kernel or f"etapa3-{nome.replace('loso-s20260917-', 'loso-').replace('-v1', '')}"

    nb = gerar(args.sha, args.evidencias, nome)
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
    print(f"notebook: {arquivo}\nkernel:   walissonfagundes/{kernel}\nexperimento: {nome}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

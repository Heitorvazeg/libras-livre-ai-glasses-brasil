"""Calibração EXPerimental do checkpoint final, sem alegar pessoas inéditas.

Fluxo explícito: protocolo -> inferir -> ajustar -> exportar --calibracao.
Protocolo é fixado antes da inferência; não há split de teste neste fluxo.
Schema 3 não altera/relaxa o schema 2 LOSO. Os arquivos originais precisam estar
acessíveis para revalidação na exportação. Hashes detectam mudanças acidentais,
não autenticam autoria, qualidade linguística ou independência das fontes.
Somente o produtor de logits desserializa o checkpoint (próprio/confiável).
O leitor/exportador confere bytes e JSON, nunca executa pickle das evidências.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath

import numpy as np
import torch

import calibracao as cb
import dados as dd
import modelo as mm
from entrada_final import _caminho_seguro

ESCOPO = "checkpoint_final_experimental"
EXPOSICAO = "pessoas_expostas_ao_pre_treino_ou_selecao"
LIMITES = [
    "Pessoas expostas ao pré-treino supervisionado contrastivo ou à seleção do backbone.",
    "Mesmos dados para ajustar temperatura/limiar e medir métricas; sem teste independente.",
    "Poucos clipes e outro corpus; não valida a distribuição da câmera dos óculos.",
    "Não aprova entrega, não certifica rótulos nem promete confiança probabilística em campo.",
]


def hash_arquivo(path):
    return mm.pv.hash_arquivo(Path(path))


def _escrever_novo(path: Path, valor: dict) -> None:
    texto = json.dumps(valor, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as f:
        f.write(texto)


def protocolo(acc_minima: float, cobertura_minima: float) -> dict:
    """Critérios operacionais explícitos, não garantias estatísticas de aceite."""
    for nome, valor in (("acc_minima", acc_minima), ("cobertura_minima", cobertura_minima)):
        if (type(valor) not in (int, float) or not np.isfinite(valor)
                or not 0 <= valor <= 1 or (nome == "acc_minima" and valor == 0)):
            raise ValueError(f"{nome}: valor finito em [0,1], acurácia > 0 exigida")
    return {"schema": 1, "finalidade": "calibracao_experimental",
            "acc_minima": float(acc_minima), "cobertura_minima": float(cobertura_minima),
            "criterio": "maior_cobertura_com_acuracia_empirica_minima",
            "metricas_medidas_em": "mesmos_dados_do_ajuste",
            "avaliacao_independente": False, "aprovado_entrega": False}


def ler_protocolo(path: Path) -> dict:
    d = cb.ler_json(path)
    try:
        esperado = protocolo(d["acc_minima"], d["cobertura_minima"])
        if (d != esperado or d.get("avaliacao_independente") is not False
                or d.get("aprovado_entrega") is not False):
            raise ValueError("Protocolo experimental inválido ou com campos divergentes")
    except KeyError as exc:
        raise ValueError("Protocolo exige acc_minima e cobertura_minima explícitas") from exc
    return d


def ler_manifesto(path: Path, raiz: Path):
    """Confere todos os clipes, em ordem; nunca varre a pasta inteira pelo loader."""
    d = cb.ler_json(path)
    raiz = _caminho_seguro(raiz)
    try:
        if (d["schema"] != 1 or d["finalidade"] != "calibracao_experimental"
                or d["avaliacao_independente"] is not False or d["aprovado_entrega"] is not False
                or d["exposicao_previa"] != EXPOSICAO):
            raise ValueError("Manifesto deve declarar exposição prévia e finalidade experimental")
        itens = d["itens"]
        if not isinstance(itens, list) or not itens:
            raise ValueError("Manifesto vazio")
        ids, hashes, pessoas, classes, arrays = set(), set(), set(), set(), []
        for item in itens:
            nome = item["arquivo"]
            rel = PurePosixPath(nome)
            if (rel.is_absolute() or ".." in rel.parts or str(rel) != nome
                    or rel.suffix != ".npy" or "\\" in nome):
                raise ValueError("Caminho relativo NPY canônico exigido")
            p = _caminho_seguro(raiz / nome)
            if not p.is_file() or not p.is_relative_to(raiz):
                raise ValueError("Clipe ausente/irregular ou fora da raiz")
            pessoa, sinal, _ = dd.parse_nome(p.stem)
            prefixo = {"vlibrasil": "V", "malta": "T"}.get(item["corpus"])
            if (prefixo is None or not pessoa.startswith(prefixo)
                    or pessoa != item["pessoa"] or sinal != item["classe"]):
                raise ValueError("Nome/pessoa/classe/corpus divergem no manifesto")
            bruto = p.read_bytes()
            sha = hashlib.sha256(bruto).hexdigest()
            if sha != item["sha256"]:
                raise ValueError(f"Hash do clipe diverge: {nome}")
            if nome in ids or sha in hashes:
                raise ValueError("Clipe duplicado por caminho ou bytes")
            ids.add(nome); hashes.add(sha); pessoas.add(pessoa); classes.add(sinal)
            arr = np.load(io.BytesIO(bruto), allow_pickle=False)
            if (not isinstance(arr, np.ndarray) or arr.dtype != np.dtype("float32")
                    or arr.ndim != 3 or arr.shape[1:] != (57, 3) or arr.shape[0] < 3
                    or not np.isfinite(arr).all()):
                raise ValueError("Landmarks exigem float32 finito (T>=3,57,3)")
            arrays.append(arr)
        por_classe = {c: sorted({i["pessoa"] for i in itens if i["classe"] == c})
                      for c in sorted(classes)}
        if (type(d["n_clipes"]) is not int or d["n_clipes"] != len(itens)
                or type(d["n_pessoas"]) is not int or d["n_pessoas"] != len(pessoas)
                or d["pessoas"] != sorted(pessoas) or d["classes_cobertas"] != sorted(classes)
                or d["pessoas_por_classe"] != por_classe):
            raise ValueError("Contagens/cobertura divergem dos itens do manifesto")
        return d, arrays
    except (KeyError, TypeError, IndexError, OSError) as exc:
        raise ValueError(f"Manifesto inválido/inacessível: {exc}") from exc


def _contrato_final(modelo, rotulos, meta):
    """Esta primeira versão aceita somente ST-GCN ossos/xyz/recentrado/imputado."""
    import gcn
    from exportar import resolver_layout, _config_cabeca_gcn
    args = meta.get("args", {})
    if (not isinstance(modelo, gcn.STGCN) or args.get("final") is not True
            or meta.get("politica_selecao") != "ultima"
            or args.get("politica_final") != "ultima"
            or type(args.get("epocas")) is not int or args["epocas"] < 1
            or meta.get("epoca_salva") != args["epocas"]
            or meta.get("avaliacao_independente") is not False):
        raise ValueError("Exige checkpoint ST-GCN final com política ultima e metadados completos")
    if (any(args.get(k) is not True for k in ("ossos", "com_z", "z_recentrado"))
            or any(args.get(k) is not False for k in ("movimento", "sem_imputacao", "adjacencia_adaptativa"))
            or args.get("kernel_temporal") != 9
            or modelo.config["canais_ent"] != 6 or modelo.config["n_nos"] != 57
            or modelo.config["kernel_t"] != 9 or modelo.config["adjacencia_adaptativa"]):
        raise ValueError("Representação incompatível: exige ossos+xyz recentrado, imputação, kernel9")
    origem = {"meta": meta, "arquitetura": "gcn", "config_modelo": modelo.config}
    origem["cabeca"] = _config_cabeca_gcn(origem)
    try:
        layout = resolver_layout(origem, None)
    except SystemExit as exc:
        raise ValueError(f"Layout de checkpoint incompatível: {exc}") from exc
    if layout["pontos"] != 57:
        raise ValueError("Layout incompatível")
    if (len(rotulos) < 2 or len(set(rotulos)) != len(rotulos)
            or any(not isinstance(r, str) or not r for r in rotulos)):
        raise ValueError("Rótulos únicos não vazios exigidos")
    if any(not torch.isfinite(v).all() for v in modelo.state_dict().values()):
        raise ValueError("Checkpoint não finito")
    amostras = meta.get("proveniencia", {}).get("dados", {}).get("amostras", [])
    if not amostras or not meta.get("pessoas"):
        raise ValueError("Checkpoint sem inventário de fine-tuning")
    return {"arquitetura": "gcn", "config_modelo": modelo.config, "layout": layout,
            "preprocessamento": "dados.recentrar_z -> dados.imputar_maos -> DatasetSinais sem augmentacao",
            "pessoas_fine_tuning": meta["pessoas"], "amostras_fine_tuning": amostras}


def _sem_sobreposicao(manifesto, contrato):
    # Só comprova disjunção do inventário de FINE-TUNING declarado. Nunca do
    # pré-treino/seleção, que seguem explicitamente expostos neste experimento.
    usadas = contrato["amostras_fine_tuning"]
    if (set(manifesto["pessoas"]) & set(contrato["pessoas_fine_tuning"])
            or {i["sha256"] for i in manifesto["itens"]} & {i["sha256"] for i in usadas}):
        raise ValueError("Manifesto sobrepõe pessoas/bytes do fine-tuning")


def gerar_evidencias(checkpoint: Path, manifesto: Path, raiz: Path,
                     caminho_protocolo: Path, saida: Path) -> dict:
    """Inferência CPU determinística sem augmentação; não ajusta pesos nem limiar."""
    if Path(saida).exists():
        raise ValueError("Saída já existe; não sobrescrever evidências")
    checkpoint, manifesto, raiz, caminho_protocolo = (
        Path(p).resolve() for p in (checkpoint, manifesto, raiz, caminho_protocolo))
    politica = ler_protocolo(caminho_protocolo)  # ANTES da inferência
    hashes = {"checkpoint": hash_arquivo(checkpoint), "manifesto": hash_arquivo(manifesto),
              "protocolo": hash_arquivo(caminho_protocolo)}
    man, arrays = ler_manifesto(manifesto, raiz)
    modelo, rotulos, meta = mm.carregar(checkpoint, map_location="cpu")
    contrato = _contrato_final(modelo, rotulos, meta)
    if set(man["classes_cobertas"]) != set(rotulos):
        raise ValueError("Manifesto deve cobrir exatamente os rótulos do checkpoint")
    _sem_sobreposicao(man, contrato)
    from treinar import DatasetSinais
    clipes = []
    for item, arr in zip(man["itens"], arrays):
        pessoa, sinal, rep = dd.parse_nome(Path(item["arquivo"]).stem)
        seq = dd.imputar_maos(dd.recentrar_z(arr.copy()))
        clipes.append(dd.Clipe(pessoa, sinal, rep, seq))
    ds = DatasetSinais(clipes, rotulos, None, False, arquitetura="gcn", ossos=True)
    logits, alvos = [], []
    modelo.eval()
    with torch.inference_mode():
        for x, y in ds:
            logits.append(modelo(x.unsqueeze(0)).squeeze(0).cpu().tolist())
            alvos.append(y)
    z = cb._matriz(logits)
    cb._alvos(alvos, z)
    codigo = mm.codigo_atual()
    evidencia = {"schema": 1, "tipo": "logits_final_experimental", "escopo": ESCOPO,
        "avaliacao_independente": False, "aprovado_entrega": False,
        "exposicao_previa": EXPOSICAO, "rotulos": rotulos, "contrato": contrato,
        "checkpoint_sha256": hashes["checkpoint"], "manifesto_sha256": hashes["manifesto"],
        "protocolo_sha256": hashes["protocolo"], "protocolo": politica,
        "caminhos": {"checkpoint": str(checkpoint), "manifesto": str(manifesto),
                     "protocolo": str(caminho_protocolo), "raiz_dados": str(raiz)},
        "codigo": {k: codigo[k] for k in ("commit", "fontes_sha256", "python", "pacotes")},
        "ids": [i["arquivo"] for i in man["itens"]], "logits": logits, "verdadeiros": alvos,
        "limites": LIMITES}
    _validar_evidencia(evidencia)  # Reabre entradas; mudanças durante inferência abortam.
    _escrever_novo(Path(saida), evidencia)
    return evidencia


def _validar_evidencia(d):
    try:
        if (d["schema"] != 1 or d["tipo"] != "logits_final_experimental" or d["escopo"] != ESCOPO
                or d["avaliacao_independente"] is not False or d["aprovado_entrega"] is not False
                or d["exposicao_previa"] != EXPOSICAO or d["limites"] != LIMITES):
            raise ValueError("Evidência deve permanecer experimental e declarar limites")
        caminhos = d["caminhos"]
        for nome in ("checkpoint", "manifesto", "protocolo"):
            if hash_arquivo(caminhos[nome]) != d[nome + "_sha256"]:
                raise ValueError(f"Fonte alterada: {nome}")
        politica = ler_protocolo(Path(caminhos["protocolo"]))
        if politica != d["protocolo"]:
            raise ValueError("Protocolo diverge da inferência")
        man, _ = ler_manifesto(Path(caminhos["manifesto"]), Path(caminhos["raiz_dados"]))
        _sem_sobreposicao(man, d["contrato"])
        rotulos = d["rotulos"]
        if (len(rotulos) < 2 or len(rotulos) != len(set(rotulos))
                or set(rotulos) != set(man["classes_cobertas"])
                or d["ids"] != [i["arquivo"] for i in man["itens"]]):
            raise ValueError("IDs/rótulos divergem do manifesto")
        z = cb._matriz(d["logits"])
        y = cb._alvos(d["verdadeiros"], z)
        if (z.shape != (man["n_clipes"], len(rotulos))
                or [rotulos[i] for i in y] != [i["classe"] for i in man["itens"]]):
            raise ValueError("Logits/alvos incompatíveis com as identidades")
        return z, y, man
    except (KeyError, TypeError, IndexError, OSError) as exc:
        raise ValueError(f"Evidência externa incompleta/inacessível: {exc}") from exc


def _resultado(d, caminho, temperatura):
    z, y, man = _validar_evidencia(d)
    temperatura = cb.validar_temperatura(temperatura)
    antes, depois = cb.probabilidades(z), cb.probabilidades(z, temperatura)
    politica = d["protocolo"]
    limiar = cb.escolher_limiar(depois, y, politica["acc_minima"])
    meta_nao_atingida = limiar["meta_nao_atingida"] or limiar["cobertura_esperada"] < politica["cobertura_minima"]
    return {"schema": 3, "escopo": ESCOPO, "metodo": "temperature scaling externo experimental",
        "avaliacao_independente": False, "aprovado_entrega": False,
        "exposicao_previa": EXPOSICAO, "metricas_medidas_em": "mesmos_dados_do_ajuste",
        "checkpoint_sha256": d["checkpoint_sha256"], "rotulos": d["rotulos"],
        "evidencias": str(Path(caminho).resolve()), "evidencias_sha256": hash_arquivo(caminho),
        "manifesto_sha256": d["manifesto_sha256"], "protocolo_sha256": d["protocolo_sha256"],
        "protocolo": politica, "temperatura": temperatura, **limiar,
        "meta_nao_atingida": bool(meta_nao_atingida),
        "acc_minima_alvo": politica["acc_minima"], "cobertura_minima": politica["cobertura_minima"],
        "ece_sem_temperatura": cb.ece(antes, y), "ece_com_temperatura": cb.ece(depois, y),
        "n_amostras_ajuste": len(y), "n_pessoas_ajuste": man["n_pessoas"],
        "n_erros_ajuste": int((z.argmax(1) != y).sum()), "limites": LIMITES}


def ajustar(evidencias: Path, saida: Path) -> dict:
    if Path(saida).exists():
        raise ValueError("Saída já existe; não sobrescrever calibração")
    d = cb.ler_json(evidencias)
    z, y, _ = _validar_evidencia(d)
    t = cb.ajustar_temperatura(z, y)
    bloco = _resultado(d, evidencias, t)
    _escrever_novo(Path(saida), bloco)
    return bloco


def validar_exportacao(bloco: dict, checkpoint_sha256: str, rotulos: list[str]) -> None:
    """Reabre evidências/protocolo/manifesto/clipes/checkpoint; não desserializa pesos."""
    try:
        json.dumps(bloco, allow_nan=False)
        if bloco.get("schema") != 3 or bloco.get("escopo") != ESCOPO:
            raise ValueError("Schema/escopo externo inválido")
        if (bloco.get("avaliacao_independente") is not False
                or bloco.get("aprovado_entrega") is not False):
            raise ValueError("Calibração externa deve declarar explicitamente ausência de aprovação/independência")
        if bloco["checkpoint_sha256"] != checkpoint_sha256 or bloco["rotulos"] != rotulos:
            raise ValueError("Checkpoint/rótulos não correspondem à calibração externa")
        path = Path(bloco["evidencias"])
        if hash_arquivo(path) != bloco["evidencias_sha256"]:
            raise ValueError("Evidências externas alteradas")
        esperado = _resultado(cb.ler_json(path), path, bloco["temperatura"])
        if bloco != esperado:
            raise ValueError("Calibração externa diverge das fontes/política/limites")
        if bloco["meta_nao_atingida"] is not False or bloco["limiar_sugerido"] is None:
            raise ValueError("Calibração externa sem política viável de acurácia/cobertura")
    except (KeyError, TypeError, OSError) as exc:
        raise ValueError(f"Calibração externa incompleta/inacessível: {exc}") from exc


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    comandos = ap.add_subparsers(dest="comando", required=True)
    p = comandos.add_parser("protocolo", help="fixar critérios ANTES da inferência")
    p.add_argument("--acc-minima", type=float, required=True)
    p.add_argument("--cobertura-minima", type=float, required=True)
    p.add_argument("--saida", type=Path, required=True)
    p = comandos.add_parser("inferir", help="somente checkpoint próprio/confiável")
    for nome in ("checkpoint", "manifesto", "raiz-dados", "protocolo", "saida"):
        p.add_argument("--" + nome, type=Path, required=True)
    p.add_argument("--threads", type=int, default=2)
    p = comandos.add_parser("ajustar")
    p.add_argument("--evidencias", type=Path, required=True)
    p.add_argument("--saida", type=Path, required=True)
    args = ap.parse_args()
    try:
        if args.comando == "protocolo":
            _escrever_novo(args.saida, protocolo(args.acc_minima, args.cobertura_minima))
        elif args.comando == "inferir":
            if args.threads < 1:
                raise ValueError("threads deve ser positivo")
            torch.set_num_threads(args.threads)
            gerar_evidencias(args.checkpoint, args.manifesto, args.raiz_dados, args.protocolo, args.saida)
        else:
            r = ajustar(args.evidencias, args.saida)
            print(f"T={r['temperatura']:.6g}; limiar={r['limiar_sugerido']}; "
                  f"cobertura_ajuste={r['cobertura_esperada']:.1%}; meta_nao_atingida={r['meta_nao_atingida']}")
    except (ValueError, OSError) as exc:
        ap.error(str(exc))
    print("EXPERIMENTAL: exposição prévia; métricas de ajuste; sem teste independente ou aprovação de entrega.")


if __name__ == "__main__":
    main()
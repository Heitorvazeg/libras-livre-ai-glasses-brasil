"""Clipes externos (V-LIBRASIL/MALTA) para o treino final, por manifesto fechado.

Só aceita um manifesto `finalidade=treino_externo` derivado do manifesto de
calibração: cada arquivo tem hash conferido e nenhuma pessoa do grupo de
avaliação pode aparecer. Os clipes passam pelo mesmo tratamento de
`dados.carregar` (recorte de dims, recentragem do z, imputação).
"""
from __future__ import annotations

import hashlib
import io
import json
import re
from pathlib import Path, PurePosixPath

import numpy as np

import dados as dd
from entrada_final import _caminho_seguro

FINALIDADE = "treino_externo"
GRUPO = "treino_externo"
PREFIXOS = {"vlibrasil": "V", "malta": "T"}


def _sha(dados: bytes) -> str:
    return hashlib.sha256(dados).hexdigest()


def gerar(manifesto_pai: Path) -> dict:
    """Manifesto de treino com só o grupo `treino_externo` da divisão fixada."""
    bruto = Path(manifesto_pai).read_bytes()
    pai = json.loads(bruto)
    divisao = pai["divisao_pessoas"]
    pessoas = set(divisao[GRUPO]["pessoas"])
    excluidas = sorted(divisao["avaliacao"]["pessoas"])
    if pessoas & set(excluidas):
        raise ValueError("Divisão do manifesto de origem sobrepõe treino e avaliação")
    itens = [{k: i[k] for k in ("classe", "pessoa", "corpus", "arquivo", "sha256")}
             for i in pai["itens"] if i["pessoa"] in pessoas]
    return _com_contagens({
        "schema": 1, "finalidade": FINALIDADE,
        "origem": {"manifesto": Path(manifesto_pai).name, "manifesto_sha256": _sha(bruto),
                   "grupo": GRUPO, "divisao_fixada_em": divisao["fixada_em"],
                   "pessoas_excluidas": excluidas},
        "itens": sorted(itens, key=lambda i: (i["classe"], i["pessoa"])),
    })


def _com_contagens(d: dict) -> dict:
    itens = d["itens"]
    d["n_clipes"] = len(itens)
    d["pessoas"] = sorted({i["pessoa"] for i in itens})
    d["classes"] = sorted({i["classe"] for i in itens})
    return d


def ler(manifesto: Path, raiz: Path) -> tuple[dict, list[np.ndarray]]:
    """Valida o manifesto e devolve os arrays crus, na ordem dos itens."""
    bruto = Path(manifesto).read_bytes()
    d = json.loads(bruto)
    raiz = _caminho_seguro(Path(raiz))
    try:
        if type(d["schema"]) is not int or d["schema"] != 1 or d["finalidade"] != FINALIDADE or d["origem"]["grupo"] != GRUPO:
            raise ValueError("Manifesto não é de treino externo")
        origem = d["origem"]
        if (not isinstance(origem["manifesto_sha256"], str)
                or not re.fullmatch(r"[0-9a-f]{64}", origem["manifesto_sha256"])
                or not isinstance(origem["manifesto"], str) or not origem["manifesto"]
                or not isinstance(origem["divisao_fixada_em"], str) or not origem["divisao_fixada_em"]
                or not isinstance(origem["pessoas_excluidas"], list)
                or any(not isinstance(p, str) or not p for p in origem["pessoas_excluidas"])):
            raise ValueError("Origem do manifesto de treino externo inválida")
        itens, excluidas = d["itens"], set(d["origem"]["pessoas_excluidas"])
        if not isinstance(itens, list) or not itens or not excluidas:
            raise ValueError("Manifesto de treino externo vazio ou sem pessoas excluídas")
        vistos, hashes, arrays = set(), set(), []
        for item in itens:
            nome = item["arquivo"]
            rel = PurePosixPath(nome)
            if (rel.is_absolute() or ".." in rel.parts or str(rel) != nome
                    or rel.suffix != ".npy" or "\\" in nome):
                raise ValueError(f"Caminho relativo NPY canônico exigido: {nome!r}")
            p = _caminho_seguro(raiz / nome)
            if not p.is_file() or not p.is_relative_to(raiz):
                raise ValueError(f"Clipe ausente ou fora da raiz: {nome}")
            pessoa, sinal, _ = dd.parse_nome(p.stem)
            prefixo = PREFIXOS.get(item["corpus"])
            if (prefixo is None or not pessoa.startswith(prefixo)
                    or pessoa != item["pessoa"] or sinal != item["classe"]):
                raise ValueError(f"Nome/pessoa/classe/corpus divergem: {nome}")
            if pessoa in excluidas:
                raise ValueError(f"Pessoa do grupo de avaliação no treino externo: {pessoa}")
            conteudo = p.read_bytes()
            sha = _sha(conteudo)
            if sha != item["sha256"]:
                raise ValueError(f"Hash do clipe diverge: {nome}")
            if nome in vistos or sha in hashes:
                raise ValueError(f"Clipe duplicado por caminho ou bytes: {nome}")
            vistos.add(nome)
            hashes.add(sha)
            with io.BytesIO(conteudo) as buffer:
                arr = np.load(buffer, allow_pickle=False)
                if not isinstance(arr, np.ndarray):
                    arr.close()
                    raise ValueError(f"Exige arquivo NPY, não pacote NPZ: {nome}")
                if buffer.read(1):
                    raise ValueError(f"Bytes adicionais após o array NPY: {nome}")
            if (arr.dtype != np.float32 or arr.ndim != 3 or arr.shape[1:] != (57, 3)
                    or arr.shape[0] < 3 or not np.isfinite(arr).all()):
                raise ValueError(f"Landmarks exigem float32 finito (T>=3,57,3): {nome}")
            arrays.append(arr)
        esperado = _com_contagens({"itens": itens})
        if (type(d["n_clipes"]) is not int
            or any(d[k] != esperado[k] for k in ("n_clipes", "pessoas", "classes"))):
            raise ValueError("Contagens do manifesto divergem dos itens")
    except (KeyError, TypeError) as exc:
        raise ValueError(f"Manifesto de treino externo inválido: {exc}") from exc
    return d, arrays


def carregar(manifesto: Path, raiz: Path, *, rotulos: list[str], pessoas_minds: list[str],
             com_z: bool, z_recentrado: bool, imputar: bool,
             lacuna_maxima: int = 5) -> tuple[list[dd.Clipe], list[dict], dict]:
    """Clipes prontos para o treino, amostras para o inventário e resumo para o checkpoint."""
    d, arrays = ler(manifesto, raiz)
    fora = sorted(set(d["classes"]) - set(rotulos))
    if fora:
        raise ValueError(f"Classes externas fora do vocabulário do treino: {fora}")
    comuns = sorted(set(d["pessoas"]) & set(pessoas_minds))
    if comuns:
        raise ValueError(f"Pessoas externas coincidem com pessoas do MINDS: {comuns}")
    clipes, amostras = [], []
    for item, arr in zip(d["itens"], arrays):
        pessoa, sinal, rep = dd.parse_nome(Path(item["arquivo"]).stem)
        seq = arr[:, :, :3 if com_z else 2].astype(np.float32)
        if com_z and z_recentrado:
            seq = dd.recentrar_z(seq)
        if imputar:
            seq = dd.imputar_maos(seq, lacuna_maxima)
        clipes.append(dd.Clipe(pessoa, sinal, rep, seq))
        amostras.append({"arquivo": item["arquivo"], "sha256": item["sha256"], "pessoa": pessoa,
                         "sinal": sinal, "rep": rep, "origem_status": "externo_manifesto",
                         "corpus": item["corpus"]})
    resumo = {"manifesto_sha256": _sha(Path(manifesto).read_bytes()),
              "manifesto_origem_sha256": d["origem"]["manifesto_sha256"],
              "pessoas": d["pessoas"], "pessoas_excluidas": d["origem"]["pessoas_excluidas"],
              "n_clipes": d["n_clipes"], "classes": d["classes"]}
    return clipes, amostras, resumo

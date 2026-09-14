"""Artefatos privados de folds concluídos; JSON de rodada é o marcador de commit.

Não retoma otimização no meio de uma época. Só reutiliza uma rodada inteira
quando identidade da execução, partição e hashes dos dois artefatos conferem.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

import modelo as mm


def id_clipe(c) -> str:
    return f"pessoa{c.pessoa}_sinal-{c.sinal}_rep{c.rep}.npy"


def artefatos_fold(arquivo: Path) -> tuple[Path, Path]:
    # Leitores legados usam rodadas/*.json. Manter apenas marcadores nesse nível.
    pasta = arquivo.parent / "artefatos"
    return pasta / arquivo.with_suffix(".pt").name, pasta / arquivo.with_suffix(".evidencias.json").name


def escrever_json(destino: Path, conteudo: dict) -> None:
    """Publica arquivo completo por replace no mesmo filesystem."""
    temporario = destino.with_name(destino.name + ".tmp")
    temporario.write_text(json.dumps(conteudo, ensure_ascii=False, allow_nan=False,
                                      default=str), encoding="utf-8")
    temporario.replace(destino)


def contexto_execucao(cfg, lm_dir, clipes, args) -> dict:
    dados = mm.inventario_final(lm_dir, clipes)
    codigo = mm.codigo_atual()
    inicializar = getattr(args, "inicializar", None)
    # Caminhos podem mudar ao mover uma execução; identidade vem do conteúdo.
    parametros = {k: v for k, v in vars(args).items()
                  if k not in {"saida", "folds", "landmarks", "inicializar"}}
    identidade = {
        "schema": 1, "args": parametros, "config": cfg,
        "dados_sha256": mm.pv.hash_json(dados),
        "codigo_sha256": codigo["fontes_sha256"],
        "python": codigo["python"], "pacotes": codigo["pacotes"],
        "backbone_sha256": mm.pv.hash_arquivo(Path(inicializar)) if inicializar else None,
    }
    return {"identidade": identidade, "sha256": mm.pv.hash_json(identidade),
            "codigo": codigo, "dados": dados, "config": cfg}


def particao_fold(treino, validacao, teste) -> dict:
    grupos = {"treino": treino, "validacao": validacao, "teste": teste}
    pessoas = {k: sorted({c.pessoa for c in cs}) for k, cs in grupos.items()}
    ids = {k: [id_clipe(c) for c in cs] for k, cs in grupos.items()}
    for k, cs in grupos.items():
        if not cs or len(ids[k]) != len(set(ids[k])):
            raise ValueError(f"partição {k} vazia ou com IDs duplicados")
    for a, b in (("treino", "validacao"), ("treino", "teste"), ("validacao", "teste")):
        if set(pessoas[a]) & set(pessoas[b]):
            raise ValueError(f"LOSO exige pessoas disjuntas: {a}/{b}")
    return {"metodo": "loso", "pessoas": pessoas, "ids": ids}


def conferir_saidas(saidas, rotulos, particao) -> None:
    for grupo in ("validacao", "teste"):
        s = saidas[grupo]
        logits = np.asarray(s["logits"], dtype=np.float64)
        reais = np.asarray(s["verdadeiros"])
        preds = np.asarray(s["predicoes"])
        n = len(particao["ids"][grupo])
        if (logits.shape != (n, len(rotulos)) or not np.isfinite(logits).all()
                or reais.shape != (n,) or preds.shape != (n,)
                or not np.issubdtype(reais.dtype, np.integer)
                or not ((reais >= 0) & (reais < len(rotulos))).all()
                or not np.array_equal(logits.argmax(1), preds)):
            raise ValueError(f"saídas inconsistentes em {grupo}")
        if s["ids"] != particao["ids"][grupo]:
            raise ValueError(f"ordem dos IDs diverge em {grupo}")


def salvar_fold(arquivo, modelo, registro, saidas, contexto, particao, cfg) -> None:
    conferir_saidas(saidas, registro["rotulos"], particao)
    if (saidas["teste"]["predicoes"] != registro["predicoes"]
            or saidas["teste"]["verdadeiros"] != registro["verdadeiros"]):
        raise ValueError("predições do relatório divergem das evidências")
    checkpoint, evidencias = artefatos_fold(arquivo)
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    procedencia = {"schema": 1, "codigo": contexto["codigo"],
                  "dados": contexto["dados"], "config": cfg,
                  "particao": particao, "args": registro["args"]}
    meta = {"args": registro["args"], "pontos": cfg["pose_indices"],
            "rodada": registro["rodada"], "melhor_epoca": registro["melhor_epoca"],
            "proveniencia": procedencia,
            "contexto_sha256": contexto["sha256"]}
    # Mesmos limites usados pelo treino/export; não duplicar valores de z.
    import representacao as rp
    meta["limite_escala"] = rp.LIMITE
    meta["limite_escala_z"] = rp.LIMITE_Z if registro["args"].get("com_z") else None
    temporario = checkpoint.with_suffix(".pt.tmp")
    mm.salvar(modelo, temporario, registro["rotulos"], meta)
    temporario.replace(checkpoint)
    escrever_json(evidencias, {"schema": 1, "rotulos": registro["rotulos"],
                               "particao": particao, "contexto": contexto["identidade"],
                               "melhor_epoca": registro["melhor_epoca"], **saidas})
    registro["evidencias"] = {
        "schema": 1, "contexto_sha256": contexto["sha256"], "particao": particao,
        "checkpoint": {"arquivo": checkpoint.relative_to(arquivo.parent).as_posix(),
                   "sha256": mm.pv.hash_arquivo(checkpoint)},
        "saidas": {"arquivo": evidencias.relative_to(arquivo.parent).as_posix(),
               "sha256": mm.pv.hash_arquivo(evidencias)},
    }
    # Último arquivo publicado: sem ele a rodada não é considerada concluída.
    escrever_json(arquivo, registro)


def conferir_retomada(arquivo, registro, contexto, particao, rotulos) -> None:
    try:
        e = registro["evidencias"]
        if (e["schema"] != 1 or e["contexto_sha256"] != contexto["sha256"]
                or e["particao"] != particao or registro["rotulos"] != rotulos):
            raise ValueError("código, dados, ambiente, backbone ou partição diferentes")
        for chave, caminho in zip(("checkpoint", "saidas"), artefatos_fold(arquivo)):
            nome = caminho.relative_to(arquivo.parent).as_posix()
            item = e[chave]
            if item["arquivo"] != nome or mm.pv.hash_arquivo(arquivo.parent / nome) != item["sha256"]:
                raise ValueError(f"hash/nome de {chave} diverge")
        s = json.loads((arquivo.parent / e["saidas"]["arquivo"]).read_text(encoding="utf-8"))
        if (s["schema"] != 1 or s["rotulos"] != rotulos or s["particao"] != particao
                or s["melhor_epoca"] != registro["melhor_epoca"]
                or mm.pv.hash_json(s["contexto"]) != contexto["sha256"]):
            raise ValueError("metadados das saídas divergem")
        conferir_saidas(s, rotulos, particao)
        t = s["teste"]
        acc = float(np.mean(np.equal(t["predicoes"], t["verdadeiros"])))
        if (registro["predicoes"] != t["predicoes"] or registro["verdadeiros"] != t["verdadeiros"]
                or not np.isclose(registro["acuracia"], acc, rtol=0, atol=1e-12)):
            raise ValueError("relatório diverge das saídas de teste")
    except (KeyError, TypeError, ValueError, OSError) as exc:
        raise SystemExit(f"{arquivo}: evidências ausentes/incompatíveis ({exc}). "
                         "Use outra --saida para reexecutar; resultados antigos não foram apagados.") from exc
"""Avalia um fold held-out nos três extratores, sem treino/calibração/TFLite.

Primeiro reproduz logits históricos de validação e teste. Depois mantém o
DatasetSinais nos três braços e isola PTS vs índice no caminho externo de 96
frames do app. Arquivos privados, saída nova, nenhum resultado parcial publicado.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np
import torch

from preparar_piloto_tasks import RAIZ, hash_arquivo
from extrair_piloto_tasks import destino_privado, POSE_SUBSET

sys.path.insert(0, str(RAIZ / "computer-vision-model/treino"))
import dados as dd
import evidencias_loso as ev
import modelo as mm
import treinar as tr
import exportar as ex
from fixture_paridade_classificador import _reamostrar_pelo_tempo

TOL_REPRODUCAO = 1e-5  # fixada antes de executar o piloto, logits absolutos
LIMIAR_DIAGNOSTICO = 0.60  # default já existente no app; NÃO otimizado em M01


def ler_json(p):
    return json.loads(p.read_text(encoding="utf-8"))


def conferir_hash(p, esperado):
    if hash_arquivo(p) != esperado:
        raise ValueError(f"hash divergente: {p}")


def conferir_particao(particao, pessoa):
    if particao["metodo"] != "loso" or particao["pessoas"]["teste"] != [pessoa]:
        raise ValueError("checkpoint não é o fold held-out da pessoa escolhida")
    grupos = particao["pessoas"]
    for a, b in (("treino", "teste"), ("validacao", "teste"), ("treino", "validacao")):
        if not grupos[a] or not grupos[b] or set(grupos[a]) & set(grupos[b]):
            raise ValueError("partição vazia ou pessoas sobrepostas")
    for g, ids in particao["ids"].items():
        if not ids or len(ids) != len(set(ids)):
            raise ValueError("IDs vazios/duplicados")
        for nome in ids:
            if Path(nome).name != nome or dd.parse_nome(Path(nome).stem)[0] not in grupos[g]:
                raise ValueError("ID fora da partição declarada")


def alinhar_clipes(extracao, ids):
    cs = extracao["clipes"]
    if not extracao["extracao_completa"] or extracao["falhas"]:
        raise ValueError("extração incompleta/com falhas")
    if len(cs) != len(ids) or len({c["id"] for c in cs}) != len(cs) or {c["id"] for c in cs} != set(ids):
        raise ValueError("extração deve cobrir exatamente todos os IDs do teste")
    mapa = {c["id"]: c for c in cs}
    return [mapa[i] for i in ids]


def conferir_npz(d, n):
    if d["ts_ms"].shape != (n,) or d["pts_s"].shape != (n,) or n < 3:
        raise ValueError("shape temporal inválido")
    if not np.array_equal(d["frame_index"], np.arange(n)):
        raise ValueError("índices incompletos/desordenados")
    for k in ("ts_ms", "pts_s"):
        if not np.isfinite(d[k]).all() or np.any(np.diff(d[k]) <= 0):
            raise ValueError("timestamps inválidos")
    if not np.array_equal(d["ts_ms"], np.rint((d["pts_s"] - d["pts_s"][0]) * 1000).astype(np.int64)):
        raise ValueError("PTS e milissegundos divergem")
    for modo in ("holistic", "tasks"):
        seq, mask, hands = d[modo], d[modo + "_valido"], d[modo + "_maos"]
        if seq.shape != (n, 57, 3) or not np.isfinite(seq).all():
            raise ValueError("landmarks inválidos")
        if mask.shape != (n,) or mask.dtype != bool or hands.shape != (n, 2) or hands.dtype != bool:
            raise ValueError("máscaras inválidas")
        if np.any(seq[~mask] != 0):
            raise ValueError("frame inválido contém dados")
        if mask.sum() < 3:
            # Falhar, não diminuir silenciosamente o denominador da avaliação.
            raise ValueError("clipe sem três frames válidos; definir política de abstenção separadamente")
        for lado, (a, b) in enumerate(dd.BLOCOS_MAO):
            if np.any(seq[mask & ~hands[:, lado], a:b] != 0):
                raise ValueError("mão ausente contém dados")


def preparar(seq, cfg):
    seq = seq[:, :, :3 if cfg["com_z"] else 2].astype(np.float32)
    if cfg["z_recentrado"]:
        seq = dd.recentrar_z(seq)
    if cfg["imputar"]:
        seq = dd.imputar_maos(seq, 5)
    return seq


def logits_treino(rede, seqs, ids, rotulos, cfg, batch):
    clipes = [dd.Clipe(*dd.parse_nome(Path(i).stem), preparar(s, cfg)) for i, s in zip(ids, seqs)]
    loader = tr._loader(clipes, rotulos, None, False, batch, 0, False, "gcn", cfg["ossos"], cfg["movimento"])
    out = []
    tr._avaliar(rede, loader, torch.nn.CrossEntropyLoss(), torch.device("cpu"), logits=out)
    return np.asarray(out)


def conferir_reproducao(obtidos, salvos):
    a, b = np.asarray(obtidos), np.asarray(salvos)
    if a.shape != b.shape or not np.isfinite(a).all() or not np.isfinite(b).all():
        raise ValueError("shape/logits inválidos na reprodução")
    diferenca = float(np.abs(a - b).max())
    if diferenca > TOL_REPRODUCAO or np.any(a.argmax(1) != b.argmax(1)):
        raise ValueError(f"logits históricos não reproduzidos: máximo {diferenca}")
    return {"max_dif_logit": diferenca, "tolerancia_absoluta": TOL_REPRODUCAO,
            "discordancias_top1": 0, "amostras": len(a)}


def entrada_app(seq, ts, frames=96):
    # Imputação no índice nativo, como HandGapImputer. Não ponderada por tempo.
    return _reamostrar_pelo_tempo(dd.imputar_maos(seq, 5), ts, frames)


def logits_app(modelo, seqs, tempos, batch, frames=96):
    entradas = np.stack([entrada_app(s, t, frames) for s, t in zip(seqs, tempos)])
    out, alterados = [], 0
    delta_max = 0.0
    with torch.inference_mode():
        for i in range(0, len(entradas), batch):
            x = torch.from_numpy(entradas[i:i + batch])
            out.extend(modelo(x).numpy().tolist())
            h = modelo.cabeca
            antes = h.recentrar(x) if h.recentrar is not None else x
            depois = h.imputar(antes) if h.imputar is not None else antes
            dif = (depois - antes).abs().flatten(1).amax(1)
            alterados += int((dif > 0).sum())
            delta_max = max(delta_max, float(dif.max()))
    return np.asarray(out), {"clipes_com_segunda_imputacao_ativa": alterados,
                            "max_delta_coordenada_segunda_imputacao": delta_max}


def resumir(logits, verdadeiros, ids, rotulos):
    preds = logits.argmax(1)
    cm = np.zeros((len(rotulos), len(rotulos)), dtype=int)
    np.add.at(cm, (verdadeiros, preds), 1)
    erros = [{"id": ids[i], "verdadeiro": rotulos[t], "predito": rotulos[p]}
             for i, (t, p) in enumerate(zip(verdadeiros, preds)) if t != p]
    z = logits - logits.max(axis=1, keepdims=True)
    probs = np.exp(z)
    probs /= probs.sum(axis=1, keepdims=True)
    maximos = probs.max(axis=1)
    aceitos = maximos >= LIMIAR_DIAGNOSTICO
    corretos = preds == np.asarray(verdadeiros)
    confianca = {
        "temperatura": 1.0, "calibrada": False, "limiar_fixo": LIMIAR_DIAGNOSTICO,
        "max_softmax_por_clipe": maximos.tolist(), "aceitos": int(aceitos.sum()),
        "corretos_aceitos": int((aceitos & corretos).sum()),
        "incorretos_aceitos": int((aceitos & ~corretos).sum()),
        "rejeitados": [{"id": ids[i], "correto_top1": bool(corretos[i]),
                        "max_softmax": float(maximos[i])} for i in np.flatnonzero(~aceitos)],
    }
    return {"total": len(ids), "acertos": int(np.trace(cm)), "acuracia": float(np.trace(cm) / len(ids)),
            "predicoes": preds.tolist(), "logits": logits.tolist(), "erros": erros,
            "diagnostico_limiar_fixo": confianca,
            "matriz_confusao": cm.tolist(),
            "por_sinal": {r: {"acertos": int(cm[i, i]), "total": int(cm[i].sum())}
                          for i, r in enumerate(rotulos)}}


def comparar(a, b, verdadeiros, ids):
    pa, pb = np.asarray(a["predicoes"]), np.asarray(b["predicoes"])
    y = np.asarray(verdadeiros)
    return {"concordantes": int((pa == pb).sum()), "discordantes": int((pa != pb).sum()),
            "ambos_corretos": int(((pa == y) & (pb == y)).sum()),
            "so_a_correto": int(((pa == y) & (pb != y)).sum()),
            "so_b_correto": int(((pa != y) & (pb == y)).sum()),
            "ambos_errados": int(((pa != y) & (pb != y)).sum()),
            "ids_alterados": [ids[i] for i in np.flatnonzero(pa != pb)],
            "max_dif_logit": float(np.abs(np.asarray(a["logits"]) - b["logits"]).max())}


def executar(marcador, pasta_extracao, landmarks, destino):
    destino = destino_privado(destino)
    r = ler_json(marcador)
    cp, ef = ev.artefatos_fold(marcador)
    for chave, p in (("checkpoint", cp), ("saidas", ef)):
        conferir_hash(p, r["evidencias"][chave]["sha256"])
    e = ler_json(ef)
    ev.conferir_retomada(marcador, r, {"sha256": mm.pv.hash_json(e["contexto"])}, e["particao"], r["rotulos"])
    x = ler_json(pasta_extracao / "extracao.json")
    conferir_particao(e["particao"], x["pessoa"])
    ids, rotulos = e["teste"]["ids"], e["rotulos"]
    pares = alinhar_clipes(x, ids)
    codigo = mm.codigo_atual()
    if codigo["fontes_sha256"] != e["contexto"]["codigo_sha256"]:
        raise ValueError("código de treino mudou desde o checkpoint")
    if codigo["python"] != e["contexto"]["python"] or codigo["pacotes"] != e["contexto"]["pacotes"]:
        raise ValueError("ambiente diferente da referência")
    # O checkpoint privado deste projeto já teve o hash verificado acima.
    rede, labels, meta = mm.carregar(cp)
    if labels != rotulos or meta["proveniencia"]["particao"] != e["particao"] or meta["contexto_sha256"] != r["evidencias"]["contexto_sha256"]:
        raise ValueError("checkpoint e evidências não correspondem")
    modelo, labels_export, origem = ex.montar(cp, "landmarks", arquitetura="gcn")
    cfg = origem["cabeca"]
    layout = ex.resolver_layout(origem, None)
    if labels_export != rotulos or layout["dimensoes"] != 3 or [p["indice_mediapipe_pose"] for p in layout["pose_ordenada"]] != POSE_SUBSET:
        raise ValueError("layout/rótulos incompatíveis com piloto 57x3")
    if x["config"] != e["contexto"]["config"]:
        raise ValueError("config de extração difere da referência")
    inventario = {s["arquivo"]: s for s in meta["proveniencia"]["dados"]["amostras"]}
    batch = r["args"]["batch"]
    torch.set_num_threads(r["args"]["threads"])
    reproducao, historicos = {}, {}
    for grupo in ("validacao", "teste"):
        gi = e[grupo]["ids"]
        seqs = []
        for nome in gi:
            p = landmarks / nome
            conferir_hash(p, inventario[nome]["sha256"])
            seq = np.load(p, allow_pickle=False)
            if seq.ndim != 3 or seq.shape[1:] != (57, 3) or len(seq) < 3 or not np.isfinite(seq).all():
                raise ValueError("landmark histórico inválido")
            seqs.append(seq)
        verdadeiros = [rotulos.index(dd.parse_nome(Path(i).stem)[1]) for i in gi]
        if verdadeiros != e[grupo]["verdadeiros"]:
            raise ValueError("rótulos verdadeiros não correspondem aos IDs")
        out = logits_treino(rede, seqs, gi, rotulos, cfg, batch)
        reproducao[grupo] = conferir_reproducao(out, e[grupo]["logits"])
        if grupo == "teste":
            historicos = {"seqs": seqs, "logits": out}
    seqs_novos = {m: [] for m in ("holistic", "tasks")}
    tempos = {m: [] for m in seqs_novos}
    diagnosticos = []
    for i, par in enumerate(pares):
        nome = Path(par["id"]).with_suffix(".npz").name
        if par["artefato"] != nome or par["landmark_sha256"] != inventario[par["id"]]["sha256"]:
            raise ValueError("artefato/landmark não corresponde ao teste histórico")
        p = pasta_extracao / nome
        conferir_hash(p, par["sha256"])
        with np.load(p, allow_pickle=False) as d:
            conferir_npz(d, par["frames"])
            diag = {"id": par["id"], "frames_historico": len(historicos["seqs"][i]), "frames_novo": par["frames"]}
            for m in seqs_novos:
                mask = d[m + "_valido"]
                seqs_novos[m].append(d[m][mask].copy())
                tempos[m].append(d["ts_ms"][mask].copy())
                diag[m + "_frames_validos"] = int(mask.sum())
            hist, novo = historicos["seqs"][i], seqs_novos["holistic"][-1]
            diag["historico_novo_max_abs_se_mesmo_shape"] = float(np.abs(hist - novo).max()) if hist.shape == novo.shape else None
            diagnosticos.append(diag)
    y = e["teste"]["verdadeiros"]
    resultados = {"historico_treino": resumir(historicos["logits"], y, ids, rotulos)}
    segunda = {}
    for m, seqs in seqs_novos.items():
        out = logits_treino(rede, seqs, ids, rotulos, cfg, batch)
        resultados[m + "_treino"] = resumir(out, y, ids, rotulos)
        for tempo, ts in (("indice96", [np.arange(len(s)) for s in seqs]), ("pts96", tempos[m])):
            out, info = logits_app(modelo, seqs, ts, batch)
            nome = m + "_app_" + tempo
            resultados[nome] = resumir(out, y, ids, rotulos)
            segunda[nome] = info
    comparacoes = {}
    for a, b in (("historico_treino", "holistic_treino"), ("holistic_treino", "tasks_treino"),
                 ("historico_treino", "tasks_treino"),
                 ("holistic_treino", "holistic_app_indice96"), ("tasks_treino", "tasks_app_indice96"),
                 ("holistic_app_indice96", "holistic_app_pts96"), ("tasks_app_indice96", "tasks_app_pts96"),
                 ("holistic_app_pts96", "tasks_app_pts96")):
        comparacoes[a + " -> " + b] = comparar(resultados[a], resultados[b], y, ids)
    doc = {"schema": 1, "concluido": True, "pessoa_teste": x["pessoa"], "ids": ids,
           "rotulos": rotulos, "verdadeiros": y, "melhor_epoca": r["melhor_epoca"],
           "checkpoint_sha256": hash_arquivo(cp), "evidencias_sha256": hash_arquivo(ef),
           "extracao_sha256": hash_arquivo(pasta_extracao / "extracao.json"),
           "gerador_sha256": hash_arquivo(Path(__file__)), "codigo_treino_sha256": codigo["fontes_sha256"],
           "codigo_dependencias": {n: hash_arquivo(Path(__file__).with_name(n)) for n in
                                   ("fixture_paridade_classificador.py", "extrair_piloto_tasks.py", "preparar_piloto_tasks.py")},
           "python": codigo["python"], "pacotes": codigo["pacotes"], "representacao": cfg,
           "reproducao": reproducao, "resultados": resultados, "comparacoes": comparacoes,
           "segunda_imputacao": segunda, "diagnosticos": diagnosticos,
           "protocolo": {"treino": "mesmo DatasetSinais: recentrar, imputar, ossos, índice->64",
                         "app_indice96": "imputar no nativo, índice->96, cabeça export (inclui imputação)->64",
                         "app_pts96": "imputar no nativo, PTS arredondado ms->96, mesma cabeça->64",
                         "ausencia_pose": "remove só para entrada de inferência, mantendo PTS dos sobreviventes",
                         "selecao": "sem ajuste de pesos/limiar/temperatura/época após observar teste",
                         "confianca": "diagnóstico por clipe do default 0.60 com T=1; não é calibração nem teste de frase"},
           "limitacoes": ["uma pessoa, sem média LOSO/intervalo populacional", "sem Android/TFLite/segmentação",
                          "PTS de vídeo não são tempo de captura ou uptime do app",
                          "índice96 vs PTS96 isola tempo; treino vs app_indice96 combina operações",
                          "imputação por clipe não reproduz estado entre segmentos de uma sessão",
                          "extração Holistic nova reinicia por clipe; histórico pode diferir por estado/versão",
                          "sem calibração ou avaliação de frases/OOV", "não seleciona receita pela acurácia de M01"]}
    destino.mkdir(parents=True)
    ev.escrever_json(destino / "avaliacao.json", doc)
    for nome, res in resultados.items():
        print(f"{nome}: {res['acertos']}/{res['total']} ({res['acuracia']:.1%})")
    print("Reprodução:", reproducao)
    return doc


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rodada", type=Path, required=True)
    ap.add_argument("--extracao", type=Path, required=True)
    ap.add_argument("--landmarks", type=Path, required=True)
    ap.add_argument("--saida", type=Path, required=True)
    a = ap.parse_args()
    executar(a.rodada, a.extracao, a.landmarks, a.saida)


if __name__ == "__main__":
    main()
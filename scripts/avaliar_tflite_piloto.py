"""Paridade float32 desktop do piloto já avaliado, sem treino ou calibração.

Reproduz quatro braços app (Holistic/Tasks × índice/PTS) no ambiente de
exportação e compara TFLite, PyTorch local e os logits privados anteriores.
O ambiente pode diferir do treino; essa diferença é medida, não ocultada.
Não executa Android nem altera assets de produção. Saída privada e nova.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import platform
from pathlib import Path

import numpy as np
import torch

import avaliar_piloto_tasks as ap

TOL = ap.ex.TOL_LOGITS["nenhuma"]


def paridade(a, b):
    a, b = np.asarray(a), np.asarray(b)
    if a.ndim != 2 or not a.size or a.shape != b.shape or not np.isfinite(a).all() or not np.isfinite(b).all():
        raise ValueError("shape/logits inválidos")
    delta = float(np.abs(a - b).max())
    discordancias = int(np.count_nonzero(a.argmax(1) != b.argmax(1)))
    return {"amostras": len(a), "max_dif_logit": delta,
            "discordancias_top1": discordancias, "tolerancia_absoluta": TOL,
            "aprovada": delta <= TOL and discordancias == 0}


def contrato(interpreter, classes):
    entradas, saidas = interpreter.get_input_details(), interpreter.get_output_details()
    if len(entradas) != 1 or len(saidas) != 1:
        raise ValueError("esperada uma entrada e uma saída")
    ent, sai = entradas[0], saidas[0]
    if list(ent["shape"]) != [1, 96, 57, 3] or list(sai["shape"]) != [1, classes]:
        raise ValueError("shape diverge do contrato do piloto")
    if ent["dtype"] != np.float32 or sai["dtype"] != np.float32:
        raise ValueError("entrada/saída devem ser float32")
    return ent["index"], sai["index"]


def executar(referencia, checkpoint, pasta_extracao, fixtures, saida):
    saida = ap.destino_privado(saida)
    ref = ap.ler_json(referencia)
    if not ref["concluido"]:
        raise ValueError("referência incompleta")
    ap.conferir_hash(checkpoint, ref["checkpoint_sha256"])
    ap.conferir_hash(pasta_extracao / "extracao.json", ref["extracao_sha256"])
    ap.conferir_hash(Path(ap.__file__), ref["gerador_sha256"])
    for nome, sha in ref["codigo_dependencias"].items():
        ap.conferir_hash(Path(ap.__file__).with_name(nome), sha)
    if ap.mm.codigo_atual()["fontes_sha256"] != ref["codigo_treino_sha256"]:
        raise ValueError("código de treino diferente da avaliação de referência")

    fixture = ap.ler_json(fixtures / "paridade_classificador.json")
    nome = fixture["modelo"]
    if not nome or Path(nome).name != nome or not nome.endswith(".tflite"):
        raise ValueError("nome de modelo inválido")
    modelo_path = fixtures / nome
    sidecar_path = modelo_path.with_suffix(".json")
    sidecar = ap.ler_json(sidecar_path)
    ap.conferir_hash(modelo_path, sidecar["sha256"])
    if fixture["smoke"] or not fixture["tflite_validado"] or sidecar["args"]["quantizacao"] != "nenhuma":
        raise ValueError("exige fixture real e conversão float32 validada")
    if fixture["checkpoint_sha256"] != ref["checkpoint_sha256"] or sidecar["origem"]["sha256"] != ref["checkpoint_sha256"]:
        raise ValueError("fixture/sidecar não pertencem ao checkpoint")
    torch.set_num_threads(2)
    modelo, rotulos, origem = ap.ex.montar(checkpoint, "landmarks", arquitetura="gcn")
    if rotulos != ref["rotulos"] or rotulos != sidecar["rotulos"] or rotulos != fixture["rotulos"]:
        raise ValueError("rótulos divergentes")
    if origem["cabeca"] != ref["representacao"] or origem["cabeca"] != fixture["representacao"]:
        raise ValueError("representação divergente")
    if sidecar["contrato_entrada"]["layout_landmarks"] != ap.ex.resolver_layout(origem, None):
        raise ValueError("layout divergente")
    precisao = ap.ex._conferir_precisao(modelo_path, "nenhuma")
    from ai_edge_litert.interpreter import Interpreter
    runtime = Interpreter(model_path=str(modelo_path), num_threads=2)
    runtime.allocate_tensors()
    ent, sai = contrato(runtime, len(rotulos))

    def inferir(entradas):
        pt, lite = [], []
        with torch.inference_mode():
            for entrada in entradas:
                tensor = np.ascontiguousarray(entrada[None], dtype=np.float32)
                pt.append(modelo(torch.from_numpy(tensor))[0].numpy())
                runtime.set_tensor(ent, tensor)
                runtime.invoke()
                lite.append(runtime.get_tensor(sai)[0])
        return np.asarray(pt), np.asarray(lite)

    sinteticos = fixture["sequencias"]
    pt, lite = inferir([np.asarray(s["imputados_reamostrados"], dtype=np.float32) for s in sinteticos])
    esperado = [s["logits_app_pytorch"] for s in sinteticos]
    sintetico = {"pytorch_vs_fixture": paridade(pt, esperado),
                 "tflite_vs_fixture": paridade(lite, esperado),
                 "tflite_vs_pytorch": paridade(lite, pt)}

    extracao = ap.ler_json(pasta_extracao / "extracao.json")
    ap.conferir_particao(origem["meta"]["proveniencia"]["particao"], ref["pessoa_teste"])
    if extracao["pessoa"] != ref["pessoa_teste"] or origem["meta"]["proveniencia"]["particao"]["ids"]["teste"] != ref["ids"]:
        raise ValueError("pessoa/IDs fora do fold")
    entradas = {f"{modo}_app_{tempo}96": [] for modo in ("holistic", "tasks") for tempo in ("indice", "pts")}
    for clipe in ap.alinhar_clipes(extracao, ref["ids"]):
        nome_npz = Path(clipe["id"]).with_suffix(".npz").name
        if clipe["artefato"] != nome_npz:
            raise ValueError("artefato fora do inventário")
        npz = pasta_extracao / nome_npz
        ap.conferir_hash(npz, clipe["sha256"])
        with np.load(npz, allow_pickle=False) as d:
            ap.conferir_npz(d, clipe["frames"])
            for modo in ("holistic", "tasks"):
                mask = d[modo + "_valido"]
                seq = d[modo][mask]
                for tempo, ts in (("indice", np.arange(len(seq))), ("pts", d["ts_ms"][mask])):
                    entradas[f"{modo}_app_{tempo}96"].append(ap.entrada_app(seq, ts))
    resultados = {}
    for nome, tensors in entradas.items():
        pt, lite = inferir(tensors)
        esperado = ref["resultados"][nome]["logits"]
        resultados[nome] = {
            "entrada_float32_sha256": hashlib.sha256(np.stack(tensors).astype("<f4").tobytes()).hexdigest(),
            "pytorch_export_vs_referencia": paridade(pt, esperado),
            "tflite_vs_pytorch_export": paridade(lite, pt),
            "tflite_vs_referencia": paridade(lite, esperado),
            "tflite": ap.resumir(lite.astype(np.float64), ref["verdadeiros"], ref["ids"], rotulos),
            "logits_pytorch_export": pt.tolist(),
        }
        print(nome, resultados[nome]["tflite_vs_referencia"], flush=True)
    aprovado = all(v["aprovada"] for v in sintetico.values()) and all(
        r[k]["aprovada"] for r in resultados.values() for k in (
            "pytorch_export_vs_referencia", "tflite_vs_pytorch_export", "tflite_vs_referencia"))
    arquivos = {"referencia": referencia, "checkpoint": checkpoint,
                "modelo": modelo_path, "sidecar": sidecar_path,
                "fixture": fixtures / "paridade_classificador.json",
                "extracao": pasta_extracao / "extracao.json", "script": Path(__file__)}
    doc = {"schema": 1, "concluido": True, "paridade_aprovada": aprovado,
           "android_executado": False, "pessoa_teste": ref["pessoa_teste"],
           "ids": ref["ids"], "rotulos": rotulos, "verdadeiros": ref["verdadeiros"],
           "artefatos": {k: {"arquivo": str(p.resolve()), "sha256": ap.hash_arquivo(p)} for k, p in arquivos.items()},
           "ambiente_exportacao": {"python": platform.python_version(),
                "pacotes": {p: importlib.metadata.version(p) for p in (
                    "torch", "torchvision", "numpy", "scipy", "PyYAML", "litert-torch", "ai-edge-litert")}},
           "ambiente_referencia": {"python": ref["python"], "pacotes": ref["pacotes"]},
           "threads": 2, "precisao": precisao, "sintetico": sintetico, "resultados": resultados,
           "limitacoes": ["desktop CPU, não Android/óculos", "quatro braços de 100 clipes, não 400 amostras independentes",
                          "sem treino, quantização, calibração ou seleção adicional", "sem medir latência de produção"]}
    saida.mkdir(parents=True, exist_ok=True)
    ap.ev.escrever_json(saida / "paridade.json", doc)
    return doc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for nome in ("referencia", "checkpoint", "extracao", "fixtures", "saida"):
        parser.add_argument("--" + nome, type=Path, required=True)
    a = parser.parse_args()
    doc = executar(a.referencia, a.checkpoint, a.extracao, a.fixtures, a.saida)
    if not doc["paridade_aprovada"]:
        raise SystemExit("paridade reprovada; relatório preservado, não usar como aprovação")


if __name__ == "__main__":
    main()
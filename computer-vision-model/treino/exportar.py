"""Exporta o classificador treinado para `.tflite` — o formato que o app carrega.

POR QUE ESTE ARQUIVO EXISTE. O app declara `org.tensorflow:tensorflow-lite:2.14.0`
e espera `sinal_classifier.tflite` em `assets/`, mas nenhum caminho PyTorch ->
TFLite existia no repositório: o único export era `src/export/to_tflite.py`,
escrito para Keras (`from_keras_model`), com a versão quantizada em
`NotImplementedError`. Enquanto isso não fecha, o modelo não chega no aparelho.

OS DOIS MODOS, E POR QUE O PADRÃO É `landmarks`.

    --modo landmarks   entrada (1, T, P, 2)      <- PADRÃO
    --modo imagem      entrada (1, 3, 224, 224)

No modo `imagem` o `.tflite` começa onde a ResNet começa, e o app precisa montar
o Skeleton-DML sozinho: transpor, empilhar 3 frames por canal, concatenar x|y,
recortar em ±2,0, mapear para [0,1] e redimensionar para 224×224. Cada um desses
passos é uma chance de divergir do treino **em silêncio** — o modelo não recusa
uma imagem montada errada, ele só erra mais, e nada no app denuncia isso.

No modo `landmarks` a montagem da imagem entra no próprio grafo (`CabecaSkeletonDML`,
que é `representacao.para_imagem` reescrito em ops do torch). O app entrega os
landmarks que já extrai e recebe os logits. O contrato vira "T frames de P pontos
(x, y), em unidades de ombro" — verificável, em vez de replicável.

O PREÇO DO MODO `landmarks`: o grafo exportado tem T fixo. No treino, T varia por
clipe (70 a 232 frames) e o redimensionamento para 224 absorve a diferença; aqui o
app precisa entregar exatamente T frames. Se isso muda a acurácia, é medição com
dado real — não dá para responder neste export. Ver §"Pendências" no README.

O BACKEND DE CONVERSÃO, E POR QUE NÃO É O onnx2tf. Medido neste repositório, com
pesos aleatórios (`--smoke`), comparando cada etapa contra o PyTorch:

    só a ResNet (modo imagem)     torch->onnx 2,7e-07   onnx->tflite 4,8e-07   ✓
    só a cabeça Skeleton-DML      torch->onnx 5,8e-06   onnx->tflite 8,3e-01   ✗
    modelo inteiro (landmarks)                          logits divergem 3,6e-01, top-1 troca

A cabeça isolada está **certa**: a divergência de 0,83 some (vira 5,4e-06) ao
compensar a transposição — o onnx2tf devolve a imagem em NHWC, que é o layout
nativo dele. O problema aparece ao **colar as duas metades**: o conversor trata o
`permute(0,3,1,2)` da cabeça como se fosse a troca de layout padrão e o elimina,
e a ResNet passa a convoluir nos eixos errados. Nada avisa; o arquivo converte,
roda e classifica errado. Por isso o padrão é `ai-edge-torch`, que preserva a
semântica do PyTorch, e o `--backend onnx` fica documentado como alternativa
válida **só no modo imagem** (onde não há cabeça para colar).

QUANTIZAÇÃO. Três modos, todos medidos aqui com `--smoke` (ResNet-18, 20 classes):

    --quantizacao nenhuma    45,0 MB   tudo float32              (padrão)
    --quantizacao float16    22,5 MB   22 tensores em float16
    --quantizacao dinamica   11,3 MB   22 tensores em int8

`dinamica` é int8 **só nos pesos**, com ativações em float — por isso não precisa
de dataset de calibração. O que fica de fora é a quantização **inteira completa**
(ativações também em int8): essa exige um conjunto representativo para calibrar as
faixas, e calibrar com ruído produz um modelo que converte, roda e erra.

O tamanho medido bate com a aritmética do README (11,2M parâmetros): ~22 MB em
float16, ~11 MB em int8.

⚠️ Os números de tamanho acima são fatos; o **efeito da quantização na acurácia
não foi medido** — `--smoke` usa pesos aleatórios, e perda de precisão só se avalia
com o checkpoint real sobre dado real. Antes de mandar um modelo quantizado para o
aparelho, rode a LOSO com ele.

O conversor **ignora silenciosamente** flags que não entende: pedir float16 pela
chave aninhada `target_spec.supported_types` não surte efeito e devolve int8
dinâmico (reproduzido aqui). Por isso `_conferir_precisao` abre o arquivo gerado e
confere os tipos dos tensores contra o que foi pedido, em vez de confiar no pedido.

Uso:
    python exportar.py --checkpoint resultados-resnet/modelo_final.pt \
                       --saida ../models/sinal_classifier.tflite
    python exportar.py --smoke            # valida o toolchain sem checkpoint nem dado
    python exportar.py --checkpoint ... --quantizacao float16
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

import modelo as md
import representacao as rp

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent / "datasets"))
import proveniencia as pv  # noqa: E402

LADO = 224
# Máxima divergência tolerada entre PyTorch e TFLite no mesmo tensor de entrada.
# Em float32 é ruído de aritmética acumulada numa ResNet-18: acima disso é
# diferença de GRAFO, não de precisão. Nos modos quantizados a perda de precisão é
# esperada e maior, então quem decide é a concordância de top-1 — a folga numérica
# aqui só existe para ainda pegar grafo trocado.
TOL_LOGITS = {"nenhuma": 2e-3, "float16": 5e-2, "dinamica": 5e-2}


class CabecaSkeletonDML(nn.Module):
    """`representacao.para_imagem` + resize, em ops que o conversor entende.

    Espelha `representacao.para_imagem` (numpy) e o resize de `treinar.py`. As
    duas implementações têm de concordar — `teste_cabeca_bate_com_numpy` no
    selftest trava isso, senão o modo `landmarks` exportaria um pré-processamento
    que ninguém treinou.
    """

    def __init__(self, frames_por_canal: int = rp.FRAMES_POR_CANAL,
                 limite: float = rp.LIMITE, lado: int = LADO):
        super().__init__()
        self.n = frames_por_canal
        self.limite = limite
        self.lado = lado

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        # seq: (N, T, P, 2) -> imagem (N, 3, 224, 224)
        if seq.ndim != 4 or seq.shape[-1] != 2:
            raise ValueError("CabecaSkeletonDML exige (N, T, P, 2); exportação 3D não suportada")
        if seq.shape[1] < self.n or seq.shape[2] < 1:
            raise ValueError("landmarks sem pontos ou frames suficientes")
        n = self.n
        t_util = (seq.shape[1] // n) * n
        seq = seq[:, :t_util]
        x = seq[..., 0].permute(0, 2, 1)              # (N, P, T)
        y = seq[..., 1].permute(0, 2, 1)
        p = x.shape[1]
        x = x.reshape(x.shape[0], p, -1, n)           # (N, P, T/n, n)
        y = y.reshape(y.shape[0], p, -1, n)
        img = torch.cat([x, y], dim=2)                # (N, P, 2*T/n, n)
        img = torch.clamp(img, -self.limite, self.limite)
        img = (img + self.limite) / (2 * self.limite)
        img = img.permute(0, 3, 1, 2)                 # (N, n, P, W)
        return F.interpolate(img, size=(self.lado, self.lado),
                             mode="bilinear", align_corners=False)


class ClassificadorLandmarks(nn.Module):
    """Landmarks -> logits, com o pré-processamento dentro do grafo."""

    def __init__(self, rede: nn.Module):
        super().__init__()
        self.cabeca = CabecaSkeletonDML()
        self.rede = rede

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        return self.rede(self.cabeca(seq))


def montar(checkpoint: Path | None, modo: str, num_classes: int = 20
           ) -> tuple[nn.Module, list[str], dict]:
    """Modelo pronto para exportar, com os rótulos e a proveniência do checkpoint.

    Sem checkpoint (`--smoke`) constrói pesos aleatórios: serve para validar o
    toolchain de conversão, nunca para gerar artefato de entrega.
    """
    if checkpoint is None:
        rede = md.construir(num_classes, pretreinado=False).eval()
        rotulos = [f"classe{i:02d}" for i in range(num_classes)]
        origem = {"smoke": True, "aviso": "pesos aleatórios — não é modelo de entrega"}
    else:
        rede, rotulos, meta = md.carregar(checkpoint)
        if not isinstance(rede, md.ResNet):
            raise SystemExit(
                f"{checkpoint.name} é um checkpoint {type(rede).__name__}; o export "
                "cobre a ResNet-18, que é o modelo do MVP (ver treino/README.md).")
        origem = {"arquivo": checkpoint.name, "sha256": pv.hash_arquivo(checkpoint),
                  "meta": meta}
    rede.eval()
    modelo_final = ClassificadorLandmarks(rede) if modo == "landmarks" else rede
    return modelo_final.eval(), rotulos, origem


def entrada_exemplo(modo: str, frames: int, pontos: int) -> torch.Tensor:
    """Tensor com o shape do contrato — define o shape fixo do grafo exportado."""
    if modo == "landmarks":
        # Faixa realista: landmarks em unidades de ombro ficam em [-1,56; +1,84].
        return torch.empty(1, frames, pontos, 2).uniform_(-1.8, 1.8)
    return torch.rand(1, 3, LADO, LADO)


def resolver_layout(origem: dict, pontos_cli: int | None) -> dict:
    """Deriva P e a ordem do checkpoint, nunca da configuração atual do projeto.

    ResNet aceita alturas arbitrárias após resize: os pesos não revelam se o
    treino usou 49 ou 57 pontos, nem se houve z. Paridade numérica não prova isso.
    Metadados da extração com três dims NÃO implicam treino 3D; com_z é a opção
    efetiva do carregador de treino, enquanto normalizacao.usar_z é da PoC DTW.
    """
    meta = origem.get("meta", {})
    prov = meta.get("proveniencia", {})
    opcoes = [meta.get("args", {}), prov.get("args", {})]
    if (any(a.get("com_z") or a.get("z_recentrado") for a in opcoes)
            or meta.get("limite_escala_z") is not None):
        raise SystemExit("checkpoint usa z/3D; exportador atual é exclusivamente 2D — "
                         "não é seguro descartar z, mesmo no modo imagem")
    limite = meta.get("limite_escala", rp.LIMITE)
    if limite != rp.LIMITE:
        raise SystemExit(f"limite_escala={limite} do checkpoint diverge do exportador ({rp.LIMITE})")
    candidatos = [meta.get("pontos"), prov.get("config", {}).get("pose_indices")]
    fonte = "checkpoint"
    if origem.get("smoke"):
        import yaml
        cfg = yaml.safe_load((AQUI.parent / "PoC" / "config.yaml").read_text(encoding="utf-8"))
        candidatos = [cfg["pose_indices"]]
        fonte = "config_smoke"
    ordens = []
    for pose in candidatos:
        if pose is None:
            continue
        if (not isinstance(pose, dict) or not pose
                or any(type(i) is not int or not 0 <= i <= 32 for i in pose.values())
                or len(set(pose.values())) != len(pose)):
            raise SystemExit("metadado de pontos inválido: esperado mapa ordenado de índices MediaPipe Pose")
        ordens.append(list(pose.items()))
    if ordens and any(o != ordens[0] for o in ordens[1:]):
        raise SystemExit("ordem de pontos diverge entre meta.pontos e proveniência do checkpoint")
    if pontos_cli is not None and pontos_cli <= 42:
        raise SystemExit("--pontos deve incluir pelo menos um ponto de pose e 42 pontos de mãos")
    if ordens:
        pontos = len(ordens[0]) + 42
        if pontos_cli is not None and pontos_cli != pontos:
            raise SystemExit(f"--pontos={pontos_cli} diverge do checkpoint/configuração: {pontos} pontos")
        ordem = [{"nome": nome, "indice_mediapipe_pose": i} for nome, i in ordens[0]]
    elif pontos_cli is not None:
        pontos, ordem, fonte = pontos_cli, None, "cli_legado_nao_verificado"
        print("[export] ⚠ checkpoint sem mapa de pose: --pontos declara apenas a contagem; "
              "ordem deve ser conferida antes de integrar ao app")
    else:
        raise SystemExit("checkpoint sem metadado de pontos; forneça --pontos explicitamente "
                         "para legado ou regenere um checkpoint com mapa de pose")
    return {"pontos": pontos, "dimensoes": 2, "coordenadas": ["x", "y"],
            "fonte_layout": fonte, "pose_ordenada": ordem,
            "maos": [{"lado": "esquerda", "indices": list(range(21))},
                     {"lado": "direita", "indices": list(range(21))}],
            "limite_escala": limite}


def _flags_quantizacao(quantizacao: str) -> dict:
    """Flags do conversor TFLite por modo. Ver §QUANTIZAÇÃO na docstring."""
    if quantizacao == "nenhuma":
        return {}
    import tensorflow as tf

    if quantizacao == "float16":
        # As DUAS chaves são necessárias: só `target_spec` não surte efeito, e só
        # `optimizations` cai em int8 dinâmico. Ambas medidas aqui.
        return {"optimizations": [tf.lite.Optimize.DEFAULT],
                "target_spec": tf.lite.TargetSpec(supported_types=[tf.float16])}
    return {"optimizations": [tf.lite.Optimize.DEFAULT]}  # dinamica: int8 nos pesos


def _conferir_precisao(destino: Path, quantizacao: str) -> dict:
    """Confere no ARQUIVO que a precisão pedida foi de fato aplicada.

    Existe porque o conversor ignora em silêncio flag que não entende: pedindo
    float16 pela chave aninhada, ele devolveu int8 dinâmico sem avisar. Confiar no
    pedido, aqui, é como confiar que a conversão preservou o modelo sem medir.
    """
    esperado = {"nenhuma": "float32", "float16": "float16", "dinamica": "int8"}[quantizacao]
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:  # pragma: no cover - depende do ambiente
        from tensorflow.lite import Interpreter

    interp = Interpreter(model_path=str(destino))
    interp.allocate_tensors()
    tipos: dict[str, int] = {}
    for d in interp.get_tensor_details():
        if len(d["shape"]) and int(np.prod(d["shape"])) > 1000:  # tensores de peso
            nome = np.dtype(d["dtype"]).name
            tipos[nome] = tipos.get(nome, 0) + 1
    if esperado not in tipos:
        raise SystemExit(
            f"[export] ✗ pedi quantização {quantizacao!r} (esperava tensores "
            f"{esperado}), mas o arquivo tem {tipos}. O conversor ignorou a flag — "
            "não use este arquivo.")
    return {"tipos_tensores": tipos, "tamanho_mb": round(destino.stat().st_size / 1e6, 1)}


def converter(modelo_torch: nn.Module, exemplo: torch.Tensor, destino: Path,
              quantizacao: str = "nenhuma", backend: str = "ai-edge") -> str:
    """PyTorch -> TFLite. Devolve o backend efetivamente usado.

    `ai-edge` é o caminho oficial do Google e o padrão. `onnx` existe como
    alternativa, mas veja a ressalva de layout na docstring do módulo: ele **não**
    serve para o modo `landmarks`.
    """
    destino.parent.mkdir(parents=True, exist_ok=True)
    if backend == "ai-edge":
        _via_ai_edge(modelo_torch, exemplo, destino, quantizacao)
    elif backend == "onnx":
        _via_onnx(modelo_torch, exemplo, destino, quantizacao)
    else:
        raise SystemExit(f"backend desconhecido: {backend!r}")
    return backend


def _via_ai_edge(modelo_torch, exemplo, destino: Path, quantizacao: str) -> None:
    try:
        import ai_edge_torch
    except ImportError as e:  # pragma: no cover - depende do ambiente
        raise SystemExit(
            f"ai-edge-torch indisponível ({e}). Ele exige torch<2.10 — com um torch "
            "mais novo o pip resolve para a 0.2.0, que depende de torch_xla e quebra "
            "com 'undefined symbol'. Ver treino/README.md §exportação."
        ) from e

    flags = _flags_quantizacao(quantizacao)
    edge = ai_edge_torch.convert(modelo_torch, (exemplo,),
                                 _ai_edge_converter_flags=flags or None)
    edge.export(str(destino))


def _via_onnx(modelo_torch, exemplo, destino: Path, quantizacao: str) -> None:
    """PyTorch -> ONNX -> TFLite (onnx2tf). Só confiável no modo `imagem`."""
    import tempfile

    import onnx2tf

    with tempfile.TemporaryDirectory() as tmp:
        onnx_path = Path(tmp) / "modelo.onnx"
        torch.onnx.export(modelo_torch, (exemplo,), str(onnx_path),
                          input_names=["entrada"], output_names=["logits"],
                          opset_version=17, dynamo=False)
        onnx2tf.convert(input_onnx_file_path=str(onnx_path),
                        output_folder_path=str(Path(tmp) / "tf"),
                        keep_ncw_or_nchw_or_ncdhw_input_names=["entrada"],
                        copy_onnx_input_output_names_to_tflite=True,
                        non_verbose=True,
                        output_integer_quantized_tflite=False)
        sufixo = "float16" if quantizacao == "float16" else "float32"
        gerado = Path(tmp) / "tf" / f"modelo_{sufixo}.tflite"
        destino.write_bytes(gerado.read_bytes())


def conferir_paridade(modelo_torch: nn.Module, destino: Path, modo: str, frames: int,
                      pontos: int, amostras: int = 8) -> dict:
    """Compara PyTorch e o .tflite gravado, no mesmo tensor de entrada.

    É a única verificação que prova que a conversão preservou o modelo. Sem ela o
    export "funciona" (gera arquivo, roda no aparelho) e classifica errado.
    """
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:  # pragma: no cover - depende do ambiente
        from tensorflow.lite import Interpreter

    interp = Interpreter(model_path=str(destino))
    interp.allocate_tensors()
    ent, sai = interp.get_input_details()[0], interp.get_output_details()[0]

    piores, discordancias = [], 0
    for _ in range(amostras):
        x = entrada_exemplo(modo, frames, pontos)
        with torch.no_grad():
            esperado = modelo_torch(x).numpy()
        interp.set_tensor(ent["index"], x.numpy().astype(ent["dtype"]))
        interp.invoke()
        obtido = interp.get_tensor(sai["index"])
        piores.append(float(np.max(np.abs(esperado - obtido))))
        discordancias += int(np.argmax(esperado) != np.argmax(obtido))

    # os shapes do interpretador vêm como numpy.int32, que o json não serializa
    return {"amostras": amostras, "max_dif_logit": max(piores),
            "discordancias_top1": discordancias,
            "shape_entrada": [int(v) for v in ent["shape"]],
            "dtype_entrada": np.dtype(ent["dtype"]).name,
            "shape_saida": [int(v) for v in sai["shape"]]}


def escrever_sidecar(destino: Path, rotulos: list[str], origem: dict, modo: str,
                     paridade: dict, args: dict) -> Path:
    """Rótulos + contrato de entrada ao lado do .tflite.

    Os rótulos viajam com o modelo pelo mesmo motivo de `modelo.salvar`: um
    artefato que não sabe a ordem das classes que prevê faz o app acertar o índice
    e falar a palavra errada.
    """
    sidecar = destino.with_suffix(".json")
    contrato = _contrato(modo, args)
    if (paridade["shape_entrada"] != contrato["shape"]
            or paridade["dtype_entrada"] != contrato["dtype"]
            or paridade["shape_saida"] != [1, len(rotulos)]):
        raise SystemExit("contrato do sidecar diverge da interface do TFLite; exportação recusada")
    sidecar.write_text(json.dumps({
        "schema": 1, "modelo": destino.name, "sha256": pv.hash_arquivo(destino),
        "rotulos": rotulos, "modo": modo, "contrato_entrada": contrato,
        "paridade_pytorch": paridade, "origem": origem,
        "codigo": {"sha256": md.codigo_atual()["fontes_sha256"]}, "args": args,
    }, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    (destino.parent / f"{destino.stem}.labels.txt").write_text(
        "\n".join(rotulos) + "\n", encoding="utf-8")
    return sidecar


def _contrato(modo: str, args: dict) -> dict:
    layout = args["layout"]
    if modo == "landmarks":
        return {"shape": [1, args["frames"], layout["pontos"], 2], "dtype": "float32",
                "layout_landmarks": layout,
                "descricao": "landmarks normalizados em unidades de ombro (origem no "
                             "ponto médio dos ombros, escala = distância entre eles), "
                             "ordem [pose | mão esquerda 21 | mão direita 21]; mão "
                             "ausente = zeros. O pré-processamento Skeleton-DML está "
                             "dentro do grafo.",
                "frames_fixos": args["frames"],
                "temporal": {"frames": args["frames"], "dinamico": False,
                             "reamostragem_embutida": False,
                             "politica_app": "exigir_shape_exato; adaptação temporal a validar com dados reais"},
                "normalizacao_embutida": False, "imputacao_embutida": False}
    return {"shape": [1, 3, LADO, LADO], "dtype": "float32",
            "layout_landmarks_preprocessamento": layout,
            "descricao": "imagem Skeleton-DML já montada, em [0,1] (SEM normalização "
                         "ImageNet). O app precisa reproduzir representacao.para_imagem "
                         "e o resize bilinear para 224×224."}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--checkpoint", type=Path,
                    help="modelo_final.pt do treino; omitido só com --smoke")
    ap.add_argument("--saida", type=Path, default=AQUI.parent / "models" / "sinal_classifier.tflite")
    ap.add_argument("--modo", choices=["landmarks", "imagem"], default="landmarks")
    ap.add_argument("--frames", type=int, default=96,
                    help="T fixo do grafo no modo landmarks (múltiplo de 3)")
    ap.add_argument("--pontos", type=int, default=None,
                    help="opcional: confere a contagem derivada do checkpoint (pose + 42); "
                         "obrigatório para legados sem metadados; nunca substitui um mapa conflitante")
    ap.add_argument("--quantizacao", choices=["nenhuma", "float16", "dinamica"],
                    default="nenhuma",
                    help="precisão dos pesos: nenhuma=45MB, float16=22,5MB, "
                         "dinamica=int8 nos pesos=11,3MB (efeito na acurácia NÃO medido)")
    ap.add_argument("--backend", choices=["ai-edge", "onnx"], default="ai-edge",
                    help="conversor; 'onnx' só é confiável no --modo imagem (ver docstring)")
    ap.add_argument("--smoke", action="store_true",
                    help="pesos aleatórios: valida o toolchain, não gera entrega")
    args = ap.parse_args()

    if not args.smoke and args.checkpoint is None:
        raise SystemExit("informe --checkpoint (ou --smoke para validar o toolchain)")
    if args.smoke and args.checkpoint is not None:
        raise SystemExit("--smoke e --checkpoint são mutuamente exclusivos")
    if args.modo == "landmarks" and (args.frames < rp.FRAMES_POR_CANAL
                                    or args.frames % rp.FRAMES_POR_CANAL):
        raise SystemExit(f"--frames deve ser múltiplo de {rp.FRAMES_POR_CANAL}, "
                         f"veio {args.frames}")
    if args.backend == "onnx" and args.modo == "landmarks":
        raise SystemExit(
            "--backend onnx com --modo landmarks produz um modelo silenciosamente "
            "errado: o conversor elimina o permute da cabeça e a ResNet convolui nos "
            "eixos trocados (medido: logits divergem 3,6e-01). Use --backend ai-edge, "
            "ou --modo imagem se precisar do onnx. Ver a docstring deste arquivo.")

    modelo_torch, rotulos, origem = montar(args.checkpoint if not args.smoke else None,
                                           args.modo)
    args.layout = resolver_layout(origem, args.pontos)
    args.pontos = args.layout["pontos"]
    exemplo = entrada_exemplo(args.modo, args.frames, args.pontos)
    print(f"[export] modo={args.modo} entrada={tuple(exemplo.shape)} "
          f"classes={len(rotulos)} quantizacao={args.quantizacao}")

    backend = converter(modelo_torch, exemplo, args.saida,
                        quantizacao=args.quantizacao, backend=args.backend)
    precisao = _conferir_precisao(args.saida, args.quantizacao)
    print(f"[export] gravado {args.saida} ({precisao['tamanho_mb']} MB, "
          f"backend={backend}, tensores={precisao['tipos_tensores']})")

    paridade = conferir_paridade(modelo_torch, args.saida, args.modo, args.frames,
                                 args.pontos)
    print(f"[export] paridade PyTorch↔TFLite: max_dif={paridade['max_dif_logit']:.2e} "
          f"top1_discordante={paridade['discordancias_top1']}/{paridade['amostras']}")
    tol = TOL_LOGITS[args.quantizacao]
    if paridade["max_dif_logit"] > tol or paridade["discordancias_top1"]:
        raise SystemExit(
            f"[export] ✗ conversão divergiu do PyTorch (tolerância {tol:.0e}). "
            "O arquivo foi gravado, mas NÃO use: o modelo no aparelho não é o treinado.")

    sidecar = escrever_sidecar(args.saida, rotulos, origem, args.modo,
                               paridade | {"precisao": precisao},
                               vars(args) | {"saida": str(args.saida), "backend": backend})
    print(f"[export] rótulos e contrato em {sidecar.name}")
    if args.smoke:
        print("[export] ⚠ --smoke: pesos aleatórios. Toolchain validado, artefato descartável.")


if __name__ == "__main__":
    main()

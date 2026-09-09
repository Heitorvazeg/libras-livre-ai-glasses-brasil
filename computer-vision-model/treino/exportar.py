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

O QUE ESTE ARQUIVO **NÃO** FAZ: quantização int8. Ela exige um dataset
representativo para calibrar as faixas de ativação, e calibrar com ruído produz um
modelo que converte, roda, e erra — pior que não ter. `--float16` está disponível
porque não precisa de calibração (é só o tipo dos pesos).

Uso:
    python exportar.py --checkpoint resultados-resnet/modelo_final.pt \
                       --saida ../models/sinal_classifier.tflite
    python exportar.py --smoke            # valida o toolchain sem checkpoint nem dado
    python exportar.py --checkpoint ... --modo imagem --float16
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
# Ordem de grandeza de ruído de float32 acumulado numa ResNet-18; qualquer coisa
# acima disso é diferença de grafo, não de aritmética.
TOL_LOGITS = 2e-3


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


def converter(modelo_torch: nn.Module, exemplo: torch.Tensor, destino: Path,
              float16: bool = False):
    """PyTorch -> TFLite via ai-edge-torch (caminho oficial do Google)."""
    try:
        import ai_edge_torch
    except ImportError as e:  # pragma: no cover - depende do ambiente
        raise SystemExit(
            "ai-edge-torch não instalado. `pip install ai-edge-torch` (ver "
            "treino/README.md §exportação)." ) from e

    flags = {}
    if float16:
        import tensorflow as tf

        flags = {"optimizations": [tf.lite.Optimize.DEFAULT],
                 "target_spec.supported_types": [tf.float16]}
    edge = ai_edge_torch.convert(modelo_torch, (exemplo,),
                                 _ai_edge_converter_flags=flags or None)
    destino.parent.mkdir(parents=True, exist_ok=True)
    edge.export(str(destino))
    return edge


def conferir_paridade(modelo_torch: nn.Module, destino: Path, modo: str, frames: int,
                      pontos: int, amostras: int = 8) -> dict:
    """Compara PyTorch e o .tflite gravado, no mesmo tensor de entrada.

    É a única verificação que prova que a conversão preservou o modelo. Sem ela o
    export "funciona" (gera arquivo, roda no aparelho) e classifica errado.
    """
    from ai_edge_litert.interpreter import Interpreter

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

    return {"amostras": amostras, "max_dif_logit": max(piores),
            "discordancias_top1": discordancias,
            "shape_entrada": list(ent["shape"]), "dtype_entrada": str(ent["dtype"]),
            "shape_saida": list(sai["shape"])}


def escrever_sidecar(destino: Path, rotulos: list[str], origem: dict, modo: str,
                     paridade: dict, args: dict) -> Path:
    """Rótulos + contrato de entrada ao lado do .tflite.

    Os rótulos viajam com o modelo pelo mesmo motivo de `modelo.salvar`: um
    artefato que não sabe a ordem das classes que prevê faz o app acertar o índice
    e falar a palavra errada.
    """
    sidecar = destino.with_suffix(".json")
    sidecar.write_text(json.dumps({
        "schema": 1, "modelo": destino.name, "sha256": pv.hash_arquivo(destino),
        "rotulos": rotulos, "modo": modo, "contrato_entrada": _contrato(modo, args),
        "paridade_pytorch": paridade, "origem": origem,
        "codigo": {"sha256": md.codigo_atual()["fontes_sha256"]}, "args": args,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    (destino.parent / f"{destino.stem}.labels.txt").write_text(
        "\n".join(rotulos) + "\n", encoding="utf-8")
    return sidecar


def _contrato(modo: str, args: dict) -> dict:
    if modo == "landmarks":
        return {"shape": [1, args["frames"], args["pontos"], 2], "dtype": "float32",
                "descricao": "landmarks normalizados em unidades de ombro (origem no "
                             "ponto médio dos ombros, escala = distância entre eles), "
                             "ordem [pose | mão esquerda 21 | mão direita 21]; mão "
                             "ausente = zeros. O pré-processamento Skeleton-DML está "
                             "dentro do grafo.",
                "frames_fixos": args["frames"]}
    return {"shape": [1, 3, LADO, LADO], "dtype": "float32",
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
    ap.add_argument("--pontos", type=int, default=49,
                    help="pontos por frame; 49 = 7 de pose + 21 de cada mão")
    ap.add_argument("--float16", action="store_true",
                    help="pesos em float16 (~metade do arquivo, sem calibração)")
    ap.add_argument("--smoke", action="store_true",
                    help="pesos aleatórios: valida o toolchain, não gera entrega")
    args = ap.parse_args()

    if not args.smoke and args.checkpoint is None:
        raise SystemExit("informe --checkpoint (ou --smoke para validar o toolchain)")
    if args.modo == "landmarks" and args.frames % rp.FRAMES_POR_CANAL:
        raise SystemExit(f"--frames deve ser múltiplo de {rp.FRAMES_POR_CANAL}, "
                         f"veio {args.frames}")

    modelo_torch, rotulos, origem = montar(args.checkpoint if not args.smoke else None,
                                           args.modo)
    exemplo = entrada_exemplo(args.modo, args.frames, args.pontos)
    print(f"[export] modo={args.modo} entrada={tuple(exemplo.shape)} "
          f"classes={len(rotulos)} float16={args.float16}")

    converter(modelo_torch, exemplo, args.saida, float16=args.float16)
    tamanho = args.saida.stat().st_size / 1e6
    print(f"[export] gravado {args.saida} ({tamanho:.1f} MB)")

    paridade = conferir_paridade(modelo_torch, args.saida, args.modo, args.frames,
                                 args.pontos)
    print(f"[export] paridade PyTorch↔TFLite: max_dif={paridade['max_dif_logit']:.2e} "
          f"top1_discordante={paridade['discordancias_top1']}/{paridade['amostras']}")
    if paridade["max_dif_logit"] > TOL_LOGITS or paridade["discordancias_top1"]:
        raise SystemExit(
            f"[export] ✗ conversão divergiu do PyTorch (tolerância {TOL_LOGITS:.0e}). "
            "O arquivo foi gravado, mas NÃO use: o modelo no aparelho não é o treinado.")

    sidecar = escrever_sidecar(args.saida, rotulos, origem, args.modo,
                               paridade, vars(args) | {"saida": str(args.saida)})
    print(f"[export] rótulos e contrato em {sidecar.name}")
    if args.smoke:
        print("[export] ⚠ --smoke: pesos aleatórios. Toolchain validado, artefato descartável.")


if __name__ == "__main__":
    main()

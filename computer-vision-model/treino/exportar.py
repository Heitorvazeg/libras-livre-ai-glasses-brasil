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

import cabeca_gcn as cg
import gcn as gg
import modelo as md
import representacao as rp

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent / "datasets"))
import proveniencia as pv  # noqa: E402

LADO = 224
N_POSE, N_MAO = gg.N_POSE, gg.N_MAO
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


def _config_cabeca_gcn(origem: dict) -> dict:
    """Flags da cabeça lidas do CHECKPOINT, nunca da configuração atual do projeto.

    Montar a cabeça com ossos quando o checkpoint treinou sem eles produz um
    modelo que converte, roda e classifica errado — o tipo de erro que este
    arquivo inteiro existe para evitar. Por isso a fonte é `meta["args"]`, e a
    contagem de canais resultante é conferida contra `config_modelo.canais_ent`
    logo depois: se divergirem, alguma flag foi lida errado.
    """
    meta = origem.get("meta", {})
    args = meta.get("args", {}) or meta.get("proveniencia", {}).get("args", {})
    if origem.get("smoke"):
        args = {"com_z": True, "z_recentrado": True, "ossos": True,
                "movimento": False, "sem_imputacao": False}
    # EXIGIR A CHAVE, NÃO ACEITAR O DEFAULT. A guarda de `canais_ent` compara um
    # número só contra cinco flags, e duas delas não mudam canal nenhum:
    # `z_recentrado` e `sem_imputacao`. Um checkpoint cujo metadado não traga
    # `z_recentrado` seria exportado SEM recentrar o z — a feature que vale
    # +2,1 pp — com a contagem de canais batendo perfeitamente. O mesmo vale para
    # a imputação, que ainda seria declarada como embutida no sidecar.
    # `ossos` e `movimento` também colidem entre si na guarda (ambos dobram), e
    # só se separam sendo lidos explicitamente.
    faltando = [k for k in ("com_z", "z_recentrado", "ossos", "movimento",
                            "sem_imputacao") if k not in args]
    if faltando:
        raise SystemExit(
            f"o checkpoint não declara {', '.join(faltando)} nos metadados. Assumir "
            "o padrão exportaria uma cabeça diferente da que treinou, e a conferência "
            "de canais não pega a diferença — `z_recentrado` e `sem_imputacao` não "
            "mudam a contagem. Regenere o checkpoint com um treinar.py que grave "
            "todas as flags de representação.")
    return {"z_recentrado": bool(args["z_recentrado"]),
            "imputar": not bool(args["sem_imputacao"]),
            "ossos": bool(args["ossos"]),
            "movimento": bool(args["movimento"]),
            "com_z": bool(args["com_z"])}


def montar(checkpoint: Path | None, modo: str, num_classes: int = 20,
           arquitetura: str = "resnet") -> tuple[nn.Module, list[str], dict]:
    """Modelo pronto para exportar, com os rótulos e a proveniência do checkpoint.

    Sem checkpoint (`--smoke`) constrói pesos aleatórios: serve para validar o
    toolchain de conversão, nunca para gerar artefato de entrega.
    """
    if checkpoint is None:
        origem = {"smoke": True, "arquitetura": arquitetura,
                  "aviso": "pesos aleatórios — não é modelo de entrega"}
        rotulos = [f"classe{i:02d}" for i in range(num_classes)]
        if arquitetura == "gcn":
            cfg = _config_cabeca_gcn(origem)
            canais = (3 if cfg["com_z"] else 2) * (2 if cfg["ossos"] else 1) \
                * (2 if cfg["movimento"] else 1)
            rede = gg.construir(num_classes, canais_ent=canais)
        else:
            rede = md.construir(num_classes, pretreinado=False)
    else:
        rede, rotulos, meta = md.carregar(checkpoint)
        origem = {"arquivo": checkpoint.name, "sha256": pv.hash_arquivo(checkpoint),
                  "meta": meta, "arquitetura": "gcn" if isinstance(rede, gg.STGCN) else "resnet"}
        if not isinstance(rede, (md.ResNet, gg.STGCN)):
            raise SystemExit(
                f"{checkpoint.name} é um checkpoint {type(rede).__name__}; o export "
                "cobre ResNet-18 e ST-GCN.")
    rede.eval()

    if origem["arquitetura"] == "gcn":
        if modo != "landmarks":
            raise SystemExit("ST-GCN só exporta no --modo landmarks: ele consome o "
                             "esqueleto, não uma imagem montada.")
        cfg = _config_cabeca_gcn(origem)
        # FONTE ÚNICA. `resolver_layout` precisa saber quantas coordenadas a
        # entrada tem, e essa decisão é a MESMA que monta a cabeça. Deixar cada
        # um derivar por conta própria já produziu divergência silenciosa aqui:
        # a cabeça montada para 3 coordenadas recebendo entrada de 2 gerou metade
        # dos canais e só estourou lá dentro, no BatchNorm.
        origem["cabeca"] = cfg
        cabeca = cg.CabecaGCN(z_recentrado=cfg["z_recentrado"], imputar=cfg["imputar"],
                              ossos=cfg["ossos"], movimento=cfg["movimento"])
        esperado = rede.config["canais_ent"]
        obtido = (3 if cfg["com_z"] else 2) * (2 if cfg["ossos"] else 1) \
            * (2 if cfg["movimento"] else 1)
        if obtido != esperado:
            raise SystemExit(
                f"a cabeça montada pelas flags do checkpoint produz {obtido} canais, "
                f"mas a rede foi treinada com {esperado}. Alguma flag de "
                "representação não está no checkpoint — exportação recusada.")
        return cg.ClassificadorGCN(rede, cabeca).eval(), rotulos, origem

    modelo_final = ClassificadorLandmarks(rede) if modo == "landmarks" else rede
    return modelo_final.eval(), rotulos, origem


def entrada_exemplo(modo: str, frames: int, pontos: int, dims: int = 2,
                    lacunas: bool = False) -> torch.Tensor:
    """Tensor com o shape do contrato — define o shape fixo do grafo exportado.

    `lacunas` zera blocos de mão para exercitar o caminho de imputação da cabeça
    do GCN. Sem isso a paridade roda sempre em dado denso: `uniform_` nunca
    produz um bloco exatamente zerado, então o gather/where da imputação e o osso
    pulso->punho com mão ausente ficariam sem conferência — e é justamente o
    único trecho com lógica dependente de dado, onde um conversor erraria.
    """
    if modo != "landmarks":
        return torch.rand(1, 3, LADO, LADO)
    # Faixa realista: landmarks em unidades de ombro ficam em [-1,56; +1,84].
    x = torch.empty(1, frames, pontos, dims).uniform_(-1.8, 1.8)
    if lacunas and pontos >= N_POSE + 2 * N_MAO:
        import gcn as _gg
        a, b = _gg.N_POSE, _gg.N_POSE + _gg.N_MAO
        c, d = b, b + _gg.N_MAO
        x[:, frames // 4:frames // 4 + 3, a:b] = 0.0        # curta: imputada
        x[:, frames // 2:frames // 2 + 12, c:d] = 0.0       # longa: fica zerada
    return x


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
    usa_z = (any(a.get("com_z") or a.get("z_recentrado") for a in opcoes)
             or meta.get("limite_escala_z") is not None)
    # Para o GCN, quem manda é a config com que a cabeça foi de fato construída
    # em `montar` — não uma segunda leitura dos mesmos metadados.
    if origem.get("arquitetura") == "gcn":
        # Sem a config da cabeça não há como saber quantas coordenadas a entrada
        # tem, e adivinhar pelos metadados aqui foi o que já produziu uma cabeça
        # de 3 coordenadas recebendo entrada de 2. A invariante é `montar` rodar
        # antes; se não rodou, é erro de uso e tem que falhar alto.
        if "cabeca" not in origem:
            raise SystemExit("resolver_layout chamado antes de montar() para um "
                             "checkpoint GCN: a configuração da cabeça é quem "
                             "define o número de coordenadas da entrada.")
        usa_z = bool(origem["cabeca"]["com_z"])
    # A ResNet continua 2D: a cabeça Skeleton-DML monta a imagem a partir de x,y e
    # não há caminho testado para o z ali. O ST-GCN consome o esqueleto direto e a
    # sua cabeça trata as três coordenadas, então para ele o z é suportado.
    if usa_z and origem.get("arquitetura") != "gcn":
        raise SystemExit("checkpoint usa z/3D; o export da ResNet é exclusivamente 2D — "
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
    dims = 3 if (usa_z and origem.get("arquitetura") == "gcn") else 2
    return {"pontos": pontos, "dimensoes": dims,
            "coordenadas": ["x", "y", "z"][:dims],
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
    # O pacote foi renomeado: `ai-edge-torch` virou `litert-torch` e parou de
    # receber atualizações. O nome antigo ainda instala, mas o módulo importado
    # fica sem `convert` — falha em atributo, não em import, o que engana. Tentar
    # o nome novo primeiro e cair no antigo mantém os dois ambientes funcionando.
    convert = None
    for mod in ("litert_torch", "ai_edge_torch"):
        try:
            m = __import__(mod)
        except ImportError:
            continue
        if hasattr(m, "convert"):
            convert = m.convert
            break
    if convert is None:  # pragma: no cover - depende do ambiente
        raise SystemExit(
            "conversor indisponível: instale `litert-torch` (o antigo `ai-edge-torch` "
            "foi renomeado e o módulo legado não expõe mais `convert`). "
            "Ver treino/README.md §exportação.")

    # A API mudou junto com o nome: o antigo aceitava `_ai_edge_converter_flags`
    # (flags cruas do conversor TFLite); o novo não tem esse parâmetro. Passar a
    # chave errada dá TypeError, o que é bom — o modo silencioso seria o conversor
    # ACEITAR e ignorar, que é exatamente o que `_conferir_precisao` já vigia.
    import inspect
    kwargs = {}
    aceita = inspect.signature(convert).parameters
    flags = _flags_quantizacao(quantizacao)
    if flags:
        if "_ai_edge_converter_flags" in aceita:
            kwargs["_ai_edge_converter_flags"] = flags
        else:
            raise SystemExit(
                f"quantização {quantizacao!r} pedida, mas o conversor instalado não "
                "expõe as flags do TFLite. Converta sem quantização ou volte ao "
                "ai-edge-torch antigo; `_conferir_precisao` recusaria o arquivo de "
                "qualquer forma.")
    edge = convert(modelo_torch, (exemplo,), **kwargs)
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
                      pontos: int, amostras: int = 8, dims: int = 2) -> dict:
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
    for i in range(amostras):
        # Metade das amostras com lacunas de mão: o caminho de imputação e o osso
        # pulso->punho com mão ausente só existem quando há bloco zerado.
        x = entrada_exemplo(modo, frames, pontos, dims, lacunas=bool(i % 2))
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
    contrato = _contrato(modo, args, origem.get("cabeca"))
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


def _contrato(modo: str, args: dict, cabeca: dict | None = None) -> dict:
    layout = args["layout"]
    if modo == "landmarks":
        gcn = args.get("arquitetura") == "gcn"
        # POR FLAG, NÃO SÓ PELA ARQUITETURA. `imputacao_embutida: gcn` mentia
        # para um checkpoint `--sem-imputacao`: a cabeça saía sem imputar
        # (`origem["cabeca"]["imputar"] is False`) e o sidecar continuava
        # declarando `true` — no MESMO arquivo que já carrega a config real em
        # `origem.cabeca`. Cada flag agora reflete o que a cabeça de fato monta.
        cabeca = cabeca or {}
        imputa = gcn and bool(cabeca.get("imputar", True))
        recentra = gcn and bool(cabeca.get("z_recentrado"))
        tem_ossos = gcn and bool(cabeca.get("ossos"))
        partes = []
        if gcn:
            if recentra:
                partes.append("recentragem do z")
            if imputa:
                partes.append("imputação de lacunas curtas")
            if tem_ossos:
                partes.append("ossos")
            partes.append("reamostragem temporal")
            pre = ("O pré-processamento do ST-GCN (" + ", ".join(partes)
                  + ") está dentro do grafo.")
        else:
            pre = "O pré-processamento Skeleton-DML está dentro do grafo."
        return {"shape": [1, args["frames"], layout["pontos"], layout["dimensoes"]],
                "dtype": "float32", "layout_landmarks": layout,
                "descricao": "landmarks normalizados em unidades de ombro (origem no "
                             "ponto médio dos ombros, escala = distância entre eles), "
                             "ordem [pose | mão esquerda 21 | mão direita 21]; mão "
                             "ausente = zeros. " + pre,
                "frames_fixos": args["frames"],
                "temporal": {"frames": args["frames"], "dinamico": False,
                             "reamostragem_embutida": gcn,
                             "reamostragem_alvo": gg.T_FIXO if gcn else None,
                             "politica_app": "exigir_shape_exato; adaptação temporal a validar com dados reais"},
                "normalizacao_embutida": False,
                # O app NÃO deve imputar quando o grafo já imputa: imputar duas
                # vezes não é idempotente, a segunda passada interpola sobre valores
                # que a primeira inventou. Para um checkpoint `--sem-imputacao`
                # isto agora sai False, e o app É responsável por imputar.
                "imputacao_embutida": imputa}
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
    ap.add_argument("--arquitetura", choices=["resnet", "gcn"], default="resnet",
                    help="só com --smoke: qual rede construir com pesos aleatórios; "
                         "com --checkpoint a arquitetura vem do próprio arquivo")
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
                                           args.modo, arquitetura=args.arquitetura)
    args.layout = resolver_layout(origem, args.pontos)
    args.arquitetura = origem.get("arquitetura", "resnet")
    args.pontos = args.layout["pontos"]
    exemplo = entrada_exemplo(args.modo, args.frames, args.pontos,
                              args.layout["dimensoes"])
    print(f"[export] modo={args.modo} entrada={tuple(exemplo.shape)} "
          f"classes={len(rotulos)} quantizacao={args.quantizacao}")

    backend = converter(modelo_torch, exemplo, args.saida,
                        quantizacao=args.quantizacao, backend=args.backend)
    precisao = _conferir_precisao(args.saida, args.quantizacao)
    print(f"[export] gravado {args.saida} ({precisao['tamanho_mb']} MB, "
          f"backend={backend}, tensores={precisao['tipos_tensores']})")

    paridade = conferir_paridade(modelo_torch, args.saida, args.modo, args.frames,
                                 args.pontos, dims=args.layout["dimensoes"])
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

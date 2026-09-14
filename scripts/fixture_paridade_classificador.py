"""Fixture de paridade entre o caminho do app e o caminho do treino (docs/prontidao-demo/02 §2.7).

Gera, com semente fixa, num diretório de saída:
  - `smoke_sinal_classifier.tflite` + `.json`: o export `--smoke --arquitetura gcn` (pesos
    aleatórios, mesmo contrato da entrega) do MESMO modelo PyTorch usado abaixo;
  - `paridade_classificador.json`: para algumas sequências sintéticas,
      1. os landmarks crus, como o MediaPipe entregaria ao app (com timestamps irregulares, frames
         descartados e lacunas de mão);
      2. os landmarks normalizados por `PoC/src/extract.py:frame_normalizado` (a fonte de verdade);
      3. a sequência imputada (`dados.imputar_maos`) na linha do tempo real e reamostrada PELO TEMPO
         para os frames do contrato — o caminho do app;
      4. os logits do PyTorch sobre o item 3;
      5. o top-1 do PyTorch pelo caminho do TREINO: o mesmo movimento a 24 fps uniforme, sem
         descarte, imputado e entregue com N frames (a cabeça reamostra N -> 64).

Os testes do app usam o fixture assim:
  - JVM: LandmarkNormalizer, HandGapImputer e ReamostragemTemporal reproduzem os itens 2 e 3;
  - instrumentado: o .tflite sobre o item 3 reproduz o item 4, e o caminho inteiro do app sobre o
    item 1 dá o top-1 do item 5.

COM O `--smoke`, O TOP-1 NÃO PROVA NADA: medido, o GCN de pesos aleatórios responde sempre a mesma
classe, com margem de 0,003 a 0,024 logit, para qualquer entrada. Por isso os testes comparam os
LOGITS (sensíveis à entrada) e só conferem o top-1 quando `margem_treino` passa de
`margem_minima_top1` — o que passa a acontecer com o checkpoint real.

Mora em scripts/, e não em computer-vision-model/treino/: é ferramenta do app, e só LÊ o código
da trilha de visão (exportar.py, dados.py, PoC/src/extract.py). Não grava nada lá, nem __pycache__.

Uso (a partir da raiz, no ambiente do export — hoje torch 2.13 + litert-torch + torchvision 0.28):
    python scripts/fixture_paridade_classificador.py --saida mobile-app-companion/app/src/androidTest/assets
"""
from __future__ import annotations

import argparse
import json
import sys
import types
from pathlib import Path

import numpy as np
import torch

sys.dont_write_bytecode = True  # nada de __pycache__ dentro da trilha de visão

RAIZ = Path(__file__).resolve().parent.parent
TREINO = RAIZ / "computer-vision-model" / "treino"
sys.path.insert(0, str(TREINO))
sys.path.insert(0, str(TREINO.parent / "PoC" / "src"))

import dados  # noqa: E402
import exportar as ex  # noqa: E402
from config import load_config  # noqa: E402
from extract import frame_normalizado  # noqa: E402

LARGURA, ALTURA = 540, 960
FPS = 24.0
MARGEM_MINIMA_TOP1 = 0.5  # logits; abaixo disso o teste do app não confere o top-1


def _lm(x, y, z, vis=None):
    return types.SimpleNamespace(x=float(x), y=float(y), z=float(z), visibility=0.0 if vis is None else float(vis))


def _pose_base(rng):
    """33 pontos plausíveis de uma pessoa de frente, em coordenadas de imagem (0..1)."""
    pts = np.zeros((33, 4))
    pts[:, 0] = 0.5 + rng.uniform(-0.05, 0.05, 33)
    pts[:, 1] = np.linspace(0.15, 0.95, 33)
    pts[:, 2] = rng.uniform(-0.2, 0.2, 33)
    pts[:, 3] = 0.99
    pts[11, :3] = [0.40, 0.40, -0.10]  # ombro esquerdo
    pts[12, :3] = [0.60, 0.40, -0.05]  # ombro direito
    pts[13, :3] = [0.36, 0.52, -0.08]
    pts[14, :3] = [0.64, 0.52, -0.06]
    pts[15, :3] = [0.38, 0.62, -0.12]  # pulso esquerdo
    pts[16, :3] = [0.62, 0.62, -0.10]  # pulso direito
    return pts


def _movimento(rng):
    """Uma trajetória suave por mão: função do tempo em segundos."""
    a = rng.uniform(0.03, 0.12, 4)
    f = rng.uniform(0.6, 2.2, 4)
    fase = rng.uniform(0, 2 * np.pi, 4)
    return lambda t: np.array([a[0] * np.sin(2 * np.pi * f[0] * t + fase[0]),
                               a[1] * np.cos(2 * np.pi * f[1] * t + fase[1]),
                               a[2] * np.sin(2 * np.pi * f[2] * t + fase[2]),
                               a[3] * np.cos(2 * np.pi * f[3] * t + fase[3])])


def _frame_cru(pose, forma_mao, desloc, t, mao_esq, mao_dir):
    d = desloc(t)
    p = pose.copy()
    p[15, 0] += d[0]; p[15, 1] += d[1]
    p[16, 0] += d[2]; p[16, 1] += d[3]

    def mao(pulso, sinal):
        pts = forma_mao.copy()
        pts[:, 0] = p[pulso, 0] + sinal * forma_mao[:, 0]
        pts[:, 1] = p[pulso, 1] + forma_mao[:, 1]
        return pts

    return p, (mao(15, -1) if mao_esq else None), (mao(16, 1) if mao_dir else None)


def _resultado(pose, mao_esq, mao_dir):
    r = types.SimpleNamespace()
    r.pose_landmarks = types.SimpleNamespace(landmark=[_lm(*q) for q in pose])
    r.left_hand_landmarks = None if mao_esq is None else types.SimpleNamespace(landmark=[_lm(*q) for q in mao_esq])
    r.right_hand_landmarks = None if mao_dir is None else types.SimpleNamespace(landmark=[_lm(*q) for q in mao_dir])
    return r


def _normalizar(frames, cfg):
    return np.stack([frame_normalizado(_resultado(*f), LARGURA, ALTURA, cfg, dims=3) for f in frames])


def _reamostrar_pelo_tempo(seq, ts, alvo):
    destino = np.linspace(ts[0], ts[-1], alvo)
    saida = np.empty((alvo, seq.shape[1], seq.shape[2]), dtype=np.float32)
    for v in range(seq.shape[1]):
        for c in range(seq.shape[2]):
            saida[:, v, c] = np.interp(destino, ts, seq[:, v, c])
    return saida


def _logits(modelo, seq):
    with torch.no_grad():
        return modelo(torch.from_numpy(np.ascontiguousarray(seq[None], dtype=np.float32)))[0].numpy()


def _sequencia(rng, frames_contrato, cfg, modelo):
    pose = _pose_base(rng)
    forma_mao = np.column_stack([np.linspace(0, 0.06, 21), np.linspace(0, 0.08, 21), rng.uniform(-0.05, 0.05, 21)])
    desloc = _movimento(rng)
    duracao = rng.uniform(1.2, 2.8)
    n_uniforme = int(duracao * FPS)
    t_uniforme = np.arange(n_uniforme) / FPS
    # Lacunas de mão (em índice do vídeo uniforme): uma curta e uma longa, uma em cada mão.
    lacuna_curta = set(range(int(n_uniforme * 0.3), int(n_uniforme * 0.3) + 3))
    lacuna_longa = set(range(int(n_uniforme * 0.6), int(n_uniforme * 0.6) + 9))

    def frames_em(indices, tempos):
        return [_frame_cru(pose, forma_mao, desloc, t, i not in lacuna_curta, i not in lacuna_longa)
                for i, t in zip(indices, tempos)]

    # Caminho do TREINO: vídeo uniforme, sem descarte.
    treino = dados.imputar_maos(_normalizar(frames_em(range(n_uniforme), t_uniforme), cfg), 5)
    logits_treino = _logits(modelo, treino)
    ordem = np.argsort(logits_treino)[::-1]
    margem = float(logits_treino[ordem[0]] - logits_treino[ordem[1]])

    # Caminho do APP: ~25% dos frames descartados e jitter nos timestamps.
    mantidos = [i for i in range(n_uniforme) if i in (0, n_uniforme - 1) or rng.random() > 0.25]
    ts_ms = np.array([round(t_uniforme[i] * 1000 + rng.uniform(-6, 6)) if 0 < i < n_uniforme - 1 else round(t_uniforme[i] * 1000)
                      for i in mantidos], dtype=np.int64)
    ts_ms = np.maximum.accumulate(ts_ms + np.arange(len(ts_ms)) * 0)  # não decrescente
    crus = frames_em(mantidos, ts_ms / 1000.0)
    normalizados = _normalizar(crus, cfg)
    imputados = dados.imputar_maos(normalizados, 5)
    app = _reamostrar_pelo_tempo(imputados, ts_ms.astype(np.float64), frames_contrato)
    logits_app = _logits(modelo, app)

    return {
        "largura": LARGURA, "altura": ALTURA,
        "ts_ms": ts_ms.tolist(),
        "pose": [f[0].round(6).tolist() for f in crus],
        "mao_esq": [None if f[1] is None else f[1].round(6).tolist() for f in crus],
        "mao_dir": [None if f[2] is None else f[2].round(6).tolist() for f in crus],
        "normalizados": normalizados.astype(np.float32).tolist(),
        "imputados_reamostrados": app.tolist(),
        "logits_app_pytorch": logits_app.tolist(),
        "top1_treino": int(ordem[0]),
        "margem_treino": margem,
        "top1_app_pytorch": int(np.argmax(logits_app)),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--saida", type=Path, required=True)
    ap.add_argument("--semente", type=int, default=20260913)
    ap.add_argument("--frames", type=int, default=96)
    ap.add_argument("--sequencias", type=int, default=3)
    args = ap.parse_args()

    torch.manual_seed(args.semente)
    rng = np.random.default_rng(args.semente)
    args.saida.mkdir(parents=True, exist_ok=True)

    modelo, rotulos, origem = ex.montar(None, "landmarks", arquitetura="gcn")
    layout = ex.resolver_layout(origem, None)
    exemplo = ex.entrada_exemplo("landmarks", args.frames, layout["pontos"], layout["dimensoes"])
    destino = args.saida / "smoke_sinal_classifier.tflite"
    backend = ex.converter(modelo, exemplo, destino, quantizacao="nenhuma", backend="ai-edge")
    precisao = ex._conferir_precisao(destino, "nenhuma")
    paridade = ex.conferir_paridade(modelo, destino, "landmarks", args.frames, layout["pontos"],
                                    dims=layout["dimensoes"])
    if paridade["max_dif_logit"] > ex.TOL_LOGITS["nenhuma"] or paridade["discordancias_top1"]:
        raise SystemExit(f"conversão divergiu do PyTorch: {paridade}")
    ex.escrever_sidecar(destino, rotulos, origem, "landmarks", paridade | {"precisao": precisao},
                        {"frames": args.frames, "layout": layout, "arquitetura": "gcn",
                         "pontos": layout["pontos"], "quantizacao": "nenhuma", "smoke": True,
                         "semente": args.semente, "saida": destino.name, "backend": backend})

    cfg = load_config()
    sequencias = [_sequencia(rng, args.frames, cfg, modelo) for _ in range(args.sequencias)]
    tentativas = len(sequencias)

    (args.saida / "paridade_classificador.json").write_text(json.dumps({
        "_origem": "scripts/fixture_paridade_classificador.py",
        "semente": args.semente, "frames": args.frames, "lacuna_maxima": 5,
        "margem_minima_top1": MARGEM_MINIMA_TOP1,
        "modelo": destino.name, "rotulos": rotulos, "sequencias": sequencias,
    }), encoding="utf-8")
    print(f"[fixture] {len(sequencias)} sequências, margens do treino {[round(x['margem_treino'], 3) for x in sequencias]}, modelo {destino.name}, "
          f"paridade tflite max_dif={paridade['max_dif_logit']:.2e}")


if __name__ == "__main__":
    main()

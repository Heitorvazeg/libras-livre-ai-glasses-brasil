"""Portão 2 (§10) + teste de paridade da Fase 4.

Duas perguntas distintas, medidas juntas porque compartilham o laço:

1. PARIDADE: o .tflite produz a MESMA sequência de tokens que o PyTorch?
   Divergência aqui não levanta erro — só degrada em silêncio, exatamente a
   classe de falha que o header do LandmarkNormalizer.kt documenta.
2. PORTÃO 2: as métricas do §10 caem depois da quantização? Quantização não é
   transformação neutra; quem decide a entrada no APK é a medição do ARTEFATO,
   não a do checkpoint.

O laço aqui é o mesmo que o Kotlin vai ter (§6.3): uma passada de encoder, T
passadas de decoder lendo logits[0, t]. Sem KV cache — ver para_tflite.py.

Uso: python exportacao/paridade.py [--tflite ...] [--experimento v2]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
from ai_edge_litert.interpreter import Interpreter
from transformers import AutoTokenizer, T5ForConditionalGeneration

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / "modelo"))
from avaliar import avaliar, cobre, f1, norm, tem_negacao  # noqa: E402
from dados import carregar  # noqa: E402
from template import contextualizar  # noqa: E402

S_ENC, T_DEC, EOS, PAD = 16, 24, 1, 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--experimento", default="v2")
    ap.add_argument("--tflite", default=str(RAIZ / "artefatos" / "modelo_contextualizacao.tflite"))
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained("unicamp-dl/ptt5-small-portuguese-vocab", legacy=False)
    _, val, remap = carregar()
    destok = json.loads((RAIZ / "artefatos" / "destokenizar.json").read_text("utf-8"))
    pt = T5ForConditionalGeneration.from_pretrained(
        RAIZ / f"resultados-{args.experimento}" / "modelo").eval()
    itp = Interpreter(model_path=args.tflite)
    enc = itp.get_signature_runner("encode")
    dec = itp.get_signature_runner("decode_step")

    def preparar(glosas):
        ids = [remap[i] for i in tok(" ".join(glosas)).input_ids if i in remap][:S_ENC]
        return (np.array([ids + [PAD] * (S_ENC - len(ids))], dtype=np.int64),
                np.array([[1] * len(ids) + [0] * (S_ENC - len(ids))], dtype=np.int64))

    def gerar_tflite(glosas):
        ei, em = preparar(glosas)
        h = list(enc(input_ids=ei, attention_mask=em).values())[0]
        seq = [PAD]
        for _ in range(T_DEC - 1):
            di = np.array([seq + [PAD] * (T_DEC - len(seq))], dtype=np.int64)
            lg = list(dec(decoder_input_ids=di, encoder_hidden=h, attention_mask=em).values())[0]
            nx = int(lg[0, len(seq) - 1].argmax())
            if nx == EOS:
                break
            seq.append(nx)
        return seq[1:]

    def gerar_pytorch(glosas):
        ei, em = preparar(glosas)
        seq = [PAD]
        for _ in range(T_DEC - 1):
            di = torch.tensor([seq + [PAD] * (T_DEC - len(seq))])
            with torch.no_grad():
                lg = pt(input_ids=torch.tensor(ei), attention_mask=torch.tensor(em),
                        decoder_input_ids=di, use_cache=False).logits
            nx = int(lg[0, len(seq) - 1].argmax())
            if nx == EOS:
                break
            seq.append(nx)
        return seq[1:]

    texto = lambda s: "".join(destok[i] for i in s if i > 1).replace("▁", " ").strip()

    # --- 1. paridade ---
    casos = sorted({tuple(p["glosas"]) for p in val})
    iguais, divergentes = 0, []
    for g in casos:
        a, b = gerar_tflite(list(g)), gerar_pytorch(list(g))
        if a == b:
            iguais += 1
        else:
            divergentes.append((g, texto(a), texto(b)))
    print(f"=== PARIDADE .tflite vs PyTorch — {len(casos)} sequências ===")
    print(f"  sequências de tokens idênticas: {iguais}/{len(casos)} = {iguais/len(casos):.1%}")
    for g, a, b in divergentes[:6]:
        print(f"    {list(g)}\n      tflite : {a!r}\n      pytorch: {b!r}")

    # --- 2. portão 2 ---
    from collections import defaultdict
    refs, glosas_de = defaultdict(list), {}
    for p in val:
        refs[p["seq_id"]].append(p["pt"])
        glosas_de[p["seq_id"]] = p["glosas"]
    cs = [(glosas_de[s], refs[s]) for s in sorted(refs)]
    avaliar("TEMPLATE (referência)", contextualizar, cs)
    avaliar(f"ARTEFATO .tflite ({Path(args.tflite).name})", lambda g: texto(gerar_tflite(g)), cs)


if __name__ == "__main__":
    main()

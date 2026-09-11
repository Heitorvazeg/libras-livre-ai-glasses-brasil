"""Export do modelo para .tflite com DUAS assinaturas (§6.3) — o spike do §0.

DECISÃO DE DESENHO: sem KV cache, por ora.

O plano previa `decode_step` carregando kv_cache como tensor de entrada e saída.
Isso exige tracear a estrutura de cache do T5, que em transformers 5.x é um objeto
`Cache` e não tuplas — difícil de exportar e frágil entre versões. A alternativa
adotada: o decoder recebe o PREFIXO INTEIRO, com comprimento fixo `T_DEC`, e
devolve logits de todas as posições; o laço no Kotlin lê `logits[0, t]` no passo t.

Custo: T passadas de decoder sobre sequência de tamanho T, em vez de T passadas
sobre 1 token — O(T²) em vez de O(T). Para T=24 e um decoder de 25M, isso é
aceitável porque a inferência acontece UMA VEZ POR SESSÃO (§1.1), e compra uma
simplificação grande do porte para Kotlin: shapes estáticos, sem estado entre
passos. KV cache fica como otimização, se a Fase 5 mostrar que a latência exige.

Uso: python exportacao/para_tflite.py --experimento v2 [--quantizar]
"""
from __future__ import annotations

import argparse
from pathlib import Path

import torch
from torch import nn
from transformers import T5ForConditionalGeneration
from transformers.modeling_outputs import BaseModelOutput

RAIZ = Path(__file__).resolve().parent.parent
S_ENC = 16   # glosas são curtas: 5 glosas ~ 12 peças
T_DEC = 24   # teto de saída, fixado NO EXPORT (§6.3) — não é parâmetro de runtime


class Encoder(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, input_ids, attention_mask):
        return self.m.get_encoder()(input_ids=input_ids,
                                    attention_mask=attention_mask).last_hidden_state


class Decoder(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, decoder_input_ids, encoder_hidden, attention_mask):
        saida = self.m(encoder_outputs=BaseModelOutput(last_hidden_state=encoder_hidden),
                       attention_mask=attention_mask,
                       decoder_input_ids=decoder_input_ids,
                       use_cache=False)
        return saida.logits


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--experimento", default="v2")
    ap.add_argument("--quantizacao", choices=["nenhuma", "int8-dynamic", "int8-peso", "fp16"],
                    default="int8-dynamic",
                    help="§6.2: int8-dynamic é a recomendada (pesos int8, ativações float, "
                         "sem dataset representativo). fp16 é a pista de GPU.")
    args = ap.parse_args()

    import litert_torch

    m = T5ForConditionalGeneration.from_pretrained(
        RAIZ / f"resultados-{args.experimento}" / "modelo").eval()
    d_model = m.config.d_model

    # sample_kwargs, não sample_args: os nomes viram o CONTRATO da assinatura no
    # .tflite. Com args posicionais o LiteRT batiza tudo de args_0/args_1/... e o
    # Kotlin passa a depender de ordem, não de nome.
    enc_kwargs = {"input_ids": torch.ones(1, S_ENC, dtype=torch.long),
                  "attention_mask": torch.ones(1, S_ENC, dtype=torch.long)}
    dec_kwargs = {"decoder_input_ids": torch.ones(1, T_DEC, dtype=torch.long),
                  "encoder_hidden": torch.zeros(1, S_ENC, d_model),
                  "attention_mask": torch.ones(1, S_ENC, dtype=torch.long)}

    quant = None
    if args.quantizacao != "nenhuma":
        from litert_torch.generative.quantize import quant_recipes as r
        quant = {"int8-dynamic": r.full_dynamic_recipe,
                 "int8-peso": r.full_weight_only_recipe,
                 "fp16": r.full_fp16_recipe}[args.quantizacao]()

    print(f"exportando {args.experimento}: encode[1,{S_ENC}] + decode_step[1,{T_DEC}] "
          f"| quantização: {args.quantizacao}")
    modelo_edge = (
        litert_torch.signature("encode", Encoder(m), sample_kwargs=enc_kwargs)
        .signature("decode_step", Decoder(m), sample_kwargs=dec_kwargs)
        .convert(quant_config=quant)
    )

    sufixo = "" if args.quantizacao == "int8-dynamic" else f"-{args.quantizacao}"
    destino = RAIZ / "artefatos" / f"modelo_contextualizacao{sufixo}.tflite"
    modelo_edge.export(str(destino))
    mb = destino.stat().st_size / 1e6
    print(f"OK -> {destino} ({mb:.1f} MB)")


if __name__ == "__main__":
    main()

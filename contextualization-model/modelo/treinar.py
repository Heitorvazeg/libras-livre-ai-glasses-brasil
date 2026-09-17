"""Fine-tuning do ptt5-small podado, no corpus glosa -> PT (§6.1.4).

Teacher forcing: passando `labels`, a HuggingFace deriva o decoder_input_ids
(labels deslocado 1 à direita) e treina TODAS as posições num único forward. Na
inferência não existe prefixo correto — o modelo consome a própria saída, um
token por vez. Treino = 1 passada, inferência = N passadas: é daí que vêm as duas
assinaturas do export (§6.3).

LR de fine-tuning (1e-4), não de treino do zero (1e-3): com 1e-3 o modelo
esquece o pré-treino, que é exatamente o que se está pagando para manter.

Uso: python modelo/treinar.py --experimento v1 [--epocas 20] [--sem-c3]
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import torch
from torch.utils.data import DataLoader
from transformers import AutoTokenizer, T5ForConditionalGeneration, get_linear_schedule_with_warmup

from dados import ParesGlosaPT, carregar, colar

RAIZ = Path(__file__).resolve().parent.parent
CHECKPOINT_TOK = "unicamp-dl/ptt5-small-portuguese-vocab"


def gerar(modelo, tok, remap, inv, glosas, max_novos=32):
    ids = [remap[i] for i in tok(" ".join(glosas)).input_ids if i in remap]
    saida = modelo.generate(torch.tensor([ids]), max_new_tokens=max_novos, num_beams=1)[0]
    pecas = [inv[int(i)] for i in saida if int(i) >= 3]
    return "".join(pecas).replace("▁", " ").strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--experimento", default="v1")
    ap.add_argument("--epocas", type=int, default=20)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--semente", type=int, default=42)
    ap.add_argument("--sem-c3", action="store_true",
                    help="só os pares que não dependem das glosas camada=3-proposta")
    args = ap.parse_args()

    torch.manual_seed(args.semente)
    tok = AutoTokenizer.from_pretrained(CHECKPOINT_TOK, legacy=False)
    modelo = T5ForConditionalGeneration.from_pretrained(RAIZ / "artefatos" / "modelo_podado")
    treino, val, remap = carregar(args.semente, sem_c3=args.sem_c3)
    destok = json.loads((RAIZ / "artefatos" / "destokenizar.json").read_text("utf-8"))

    dl_tr = DataLoader(ParesGlosaPT(treino, tok, remap), batch_size=args.batch,
                       shuffle=True, collate_fn=colar)
    dl_va = DataLoader(ParesGlosaPT(val, tok, remap), batch_size=args.batch, collate_fn=colar)

    otim = torch.optim.AdamW(modelo.parameters(), lr=args.lr, weight_decay=0.01)
    total = len(dl_tr) * args.epocas
    sched = get_linear_schedule_with_warmup(otim, int(total * 0.1), total)

    print(f"treino: {len(treino)} pares / {len({p['seq_id'] for p in treino})} sequências")
    print(f"val   : {len(val)} pares / {len({p['seq_id'] for p in val})} sequências (split POR seq_id)")
    print(f"{sum(p.numel() for p in modelo.parameters())/1e6:.1f}M parâmetros | "
          f"{len(dl_tr)} passos/época x {args.epocas} = {total}\n")

    t0, historico = time.time(), []
    for ep in range(1, args.epocas + 1):
        modelo.train()
        soma = 0.0
        for lote in dl_tr:
            perda = modelo(**lote).loss
            perda.backward()
            torch.nn.utils.clip_grad_norm_(modelo.parameters(), 1.0)
            otim.step(); sched.step(); otim.zero_grad()
            soma += perda.item()
        modelo.eval()
        with torch.no_grad():
            vperda = sum(modelo(**l).loss.item() for l in dl_va) / len(dl_va)
        historico.append({"epoca": ep, "treino": soma / len(dl_tr), "val": vperda})
        print(f"época {ep:2d}/{args.epocas}  treino={soma/len(dl_tr):.4f}  val={vperda:.4f}"
              f"  ({time.time()-t0:.0f}s)")

    saida = RAIZ / f"resultados-{args.experimento}"
    saida.mkdir(exist_ok=True)
    modelo.save_pretrained(saida / "modelo")
    (saida / "historico.json").write_text(json.dumps(historico, indent=2), "utf-8")

    print("\namostras (sequências da VALIDAÇÃO, não vistas no treino):")
    vistas = set()
    for p in val:
        if p["seq_id"] in vistas:
            continue
        vistas.add(p["seq_id"])
        print(f"  {str(p['glosas']):<48} -> {gerar(modelo, tok, remap, destok, p['glosas'])!r}")
        if len(vistas) >= 12:
            break
    print(f"\nmodelo em {saida}/modelo  ({time.time()-t0:.0f}s no total)")


if __name__ == "__main__":
    main()

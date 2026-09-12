"""Dataset glosa -> PT para o fine-tuning.

DUAS DECISÕES METODOLÓGICAS, ambas com o mesmo espírito do leave-one-signer-out
da trilha de visão (docs/libras-livre-arquitetura.md §4.3):

1. O split é POR seq_id, nunca por par. As 8 paráfrases de uma sequência são
   variações do MESMO exemplo — separá-las aleatoriamente coloca paráfrases da
   mesma sequência no treino e na validação, e a validação passa a medir
   memorização. É a versão textual de validar com a mesma pessoa que se treinou.
2. A tokenização usa o tokenizer ORIGINAL e depois aplica o remap da poda. O
   tokenizer nunca é "podado" de fato — quem é podado é a tabela de embeddings.
"""
from __future__ import annotations

import hashlib
import json
import random
from pathlib import Path

import torch
from torch.utils.data import Dataset

RAIZ = Path(__file__).resolve().parent.parent


class ParesGlosaPT(Dataset):
    def __init__(self, pares, tok, remap, max_ent=32, max_sai=32):
        self.pares, self.tok, self.remap = pares, tok, remap
        self.max_ent, self.max_sai = max_ent, max_sai

    def __len__(self):
        return len(self.pares)

    def _ids(self, texto, limite):
        brutos = self.tok(texto, add_special_tokens=True, truncation=True,
                          max_length=limite).input_ids
        return [self.remap[i] for i in brutos if i in self.remap]

    def __getitem__(self, i):
        p = self.pares[i]
        return {"input_ids": self._ids(" ".join(p["glosas"]), self.max_ent),
                "labels": self._ids(p["pt"], self.max_sai)}


def colar(lote, pad_id=0):
    """Padding + -100 nos labels (posições ignoradas pela cross-entropy)."""
    me = max(len(x["input_ids"]) for x in lote)
    ms = max(len(x["labels"]) for x in lote)
    return {
        "input_ids": torch.tensor([x["input_ids"] + [pad_id] * (me - len(x["input_ids"])) for x in lote]),
        "attention_mask": torch.tensor([[1] * len(x["input_ids"]) + [0] * (me - len(x["input_ids"])) for x in lote]),
        # -100 e não pad_id: senão o modelo é treinado para prever padding.
        "labels": torch.tensor([x["labels"] + [-100] * (ms - len(x["labels"])) for x in lote]),
    }


def carregar(semente=42, val_frac=0.15, sem_c3=False):
    pares = [json.loads(l) for l in (RAIZ / "corpus" / "pares.jsonl").read_text("utf-8").splitlines()]
    if sem_c3:
        pares = [p for p in pares if not p["depende_c3"]]
    bruto = json.loads((RAIZ / "artefatos" / "remap.json").read_text("utf-8"))
    atual = hashlib.sha256((RAIZ / "corpus" / "pares.jsonl").read_bytes()).hexdigest()
    if bruto.get("corpus_sha256") != atual:
        raise SystemExit(
            "O corpus mudou depois da poda. Rode `python modelo/podar.py` ANTES de treinar\n"
            "(§6.1.2, decisão 1) — senão os tokens novos são descartados em silêncio e o\n"
            "modelo treina em alvos truncados.")
    remap = {int(k): v for k, v in bruto["antigo_para_novo"].items()}

    ids = sorted({p["seq_id"] for p in pares})
    random.Random(semente).shuffle(ids)
    corte = int(len(ids) * (1 - val_frac))
    treino_ids, val_ids = set(ids[:corte]), set(ids[corte:])
    return ([p for p in pares if p["seq_id"] in treino_ids],
            [p for p in pares if p["seq_id"] in val_ids], remap)

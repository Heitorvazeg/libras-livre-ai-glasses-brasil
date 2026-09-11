"""Poda de vocabulário do ptt5-small (§6.1.2 do plano).

NÃO é "pruning" no sentido usual (esparsificar pesos por magnitude). É truncar a
tabela de embeddings: jogar fora as LINHAS dos tokens que este domínio não usa.
As camadas de encoder/decoder — onde mora o português pré-treinado — não são
tocadas.

Roda ANTES do fine-tuning (§6.1.2, decisão 1): assim toda rodada de treino é mais
barata e o modelo avaliado no portão 1 é exatamente o que vai embarcar.

Saídas em artefatos/:
    modelo_podado/      checkpoint com shared.weight fatiado
    remap.json          id_antigo -> id_novo (e o inverso)
    glosa_ids.json      glosa -> [ids novos]      <- CONTRATO com o app
    destokenizar.json   [peça por id novo]        <- CONTRATO com o app

Os dois últimos são §6.1.3: entrada é vocabulário fechado e saída só precisa de
destokenização, então o app não precisa de SentencePiece em runtime.

Uso: python modelo/podar.py [--margem-curtos] [--checkpoint ...]
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

import torch
from transformers import AutoTokenizer, T5ForConditionalGeneration

RAIZ = Path(__file__).resolve().parent.parent
ARTEFATOS = RAIZ / "artefatos"
CHECKPOINT = "unicamp-dl/ptt5-small-portuguese-vocab"

# pad=0, eos=1, unk=2 em T5. Mantidos NA MESMA POSIÇÃO para que
# decoder_start_token_id / pad_token_id / eos_token_id do config continuem válidos
# sem reescrita.
N_ESPECIAIS = 3

# Caracteres que uma saída em PT-BR pode conter. A margem de poda se restringe a
# peças escritas só com estes — ver comentário no passo 2 de main().
ALFABETO_PT = set(
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "áàâãéêíóôõúüçÁÀÂÃÉÊÍÓÔÕÚÜÇ"
    "0123456789"
    " .,;:!?()[]{}\"'-–—/%°ºª+=*&@#$_\\\n\t"
)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", default=CHECKPOINT)
    ap.add_argument("--margem-max-len", type=int, default=2,
                    help="mantém toda peça com até N caracteres, além das usadas pelo corpus "
                         "(§6.1.2, decisão 2). 0 = sem margem.")
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(args.checkpoint, legacy=False)
    modelo = T5ForConditionalGeneration.from_pretrained(args.checkpoint)
    lexico = json.loads((RAIZ / "lexico" / "lexico-glosas.json").read_text("utf-8"))["glosas"]
    pares = [json.loads(l) for l in (RAIZ / "corpus" / "pares.jsonl").read_text("utf-8").splitlines()]

    # 1. tudo que o corpus realmente usa (glosas E português)
    usados: Counter[int] = Counter()
    for p in pares:
        usados.update(tok(" ".join(p["glosas"]), add_special_tokens=True).input_ids)
        usados.update(tok(p["pt"], add_special_tokens=True).input_ids)
    for glosa, info in lexico.items():           # o léxico inteiro, não só o que caiu no corpus
        usados.update(tok(glosa, add_special_tokens=False).input_ids)
        for forma in info["formas"]:
            usados.update(tok(forma, add_special_tokens=False).input_ids)

    # 2. margem (§6.1.2, decisão 2): manter as peças curtas deixa qualquer palavra
    # não vista ainda representável por composição de subpalavras. Sem isso, uma
    # saída válida que use um token ausente fica impossível de gerar.
    #
    # MEDIDO: filtrar só por comprimento puxa 9.089 peças de 1 caractere — a
    # esmagadora maioria CJK, cirílico e símbolos, que um modelo de português
    # nunca vai emitir. Peso morto que ainda por cima alarga o softmax. Por isso
    # a margem exige TAMBÉM que a peça seja escrivível em português.
    margem: set[int] = set()
    if args.margem_max_len > 0:
        for peca, i in tok.get_vocab().items():
            nu = peca.replace("▁", "")
            if 0 < len(nu) <= args.margem_max_len and all(c in ALFABETO_PT for c in nu):
                margem.add(i)

    # 3. decisão 3: fora os sentinelas de span-corruption — só serviam ao pré-treino
    sentinelas = {i for peca, i in tok.get_vocab().items()
                  if peca.startswith("<extra_id_")}

    manter = list(range(N_ESPECIAIS))
    manter += sorted((set(usados) | margem) - set(manter) - sentinelas)
    novo_n = len(manter)

    # 4. fatia a matriz compartilhada. lm_head é tied -> resolvido junto.
    assert modelo.config.tie_word_embeddings, "esperava embeddings amarrados (T5 v1.0)"
    emb_antigo = modelo.shared.weight.data
    modelo.resize_token_embeddings(novo_n)
    modelo.shared.weight.data = emb_antigo[manter].clone()
    modelo.lm_head.weight = modelo.shared.weight
    modelo.config.vocab_size = novo_n

    remap = {antigo: novo for novo, antigo in enumerate(manter)}
    ARTEFATOS.mkdir(exist_ok=True)
    modelo.save_pretrained(ARTEFATOS / "modelo_podado")
    # O hash do corpus vai junto: treinar.py recusa rodar se o corpus tiver mudado
    # depois da poda. Sem esta trava, dados.py descartaria em silêncio os tokens
    # novos (`if i in remap`) e o modelo treinaria em alvos truncados — falha que
    # não levanta erro nenhum, só degrada. Aconteceu de verdade entre v1 e v2.
    (ARTEFATOS / "remap.json").write_text(json.dumps(
        {"antigo_para_novo": {str(k): v for k, v in remap.items()},
         "novo_para_antigo": manter,
         "corpus_sha256": hashlib.sha256((RAIZ / "corpus" / "pares.jsonl").read_bytes()).hexdigest()},
        ensure_ascii=False), "utf-8")

    # 5. contratos com o app (§6.1.3)
    inv = {i: p for p, i in tok.get_vocab().items()}
    destok = [inv.get(antigo, "<?>") for antigo in manter]
    (ARTEFATOS / "destokenizar.json").write_text(
        json.dumps(destok, ensure_ascii=False), "utf-8")

    glosa_ids, fora = {}, []
    for glosa in lexico:
        ids = tok(glosa, add_special_tokens=False).input_ids
        if any(i not in remap for i in ids):
            fora.append(glosa)
            continue
        glosa_ids[glosa] = [remap[i] for i in ids]
    (ARTEFATOS / "glosa_ids.json").write_text(
        json.dumps(glosa_ids, ensure_ascii=False, indent=2), "utf-8")

    tot = sum(p.numel() for p in modelo.parameters())
    print(f"vocabulário : 32128 -> {novo_n}  ({novo_n*100//32128}%)")
    print(f"  do corpus : {len(usados)} | margem de peças curtas: {len(margem)} | "
          f"sentinelas descartadas: {len(sentinelas)}")
    print(f"parâmetros  : 60.5M -> {tot/1e6:.1f}M")
    print(f"glosas mapeadas: {len(glosa_ids)}/{len(lexico)}" + (f" | FORA: {fora}" if fora else ""))
    print(f"artefatos em {ARTEFATOS}/")


if __name__ == "__main__":
    main()

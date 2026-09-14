"""Decodificação restrita — implementação de referência do `argmaxRestrito` (§6.3).

O modelo v1 falhou por OMISSÃO: com dois substantivos de conteúdo, emitia um e
abandonava o outro (6 das 7 falhas). Não é falta de capacidade, é problema de
PARADA — o decoder escolhe `</s>` antes de dizer tudo. Correção estrutural.

DUAS TENTATIVAS DESCARTADAS, registradas porque o motivo importa:

1. *Bônus na primeira peça das formas que faltam, com `</s>` bloqueado.* Levou a
   negação a 1,000 e o fallback a 0,034, mas degenerou: `[obrigado, ajuda]` virou
   "a a a a a…". A primeira peça de "ajuda" é um `▁a` genérico — empurrar um token
   tão comum com `</s>` proibido vira laço. Bônus em peça isolada não é restrição,
   é pressão.
2. *`force_words_ids` da HuggingFace.* É a restrição disjuntiva certa, mas saiu do
   core no transformers 5.x (virou `custom_generate`, exige `trust_remote_code`).
   Não vale a dependência.

O QUE FICOU: laço guloso próprio com duas regras.
  a) `</s>` fica proibido enquanto houver glosa não coberta;
  b) se a cobertura não avança por `PACIENCIA` passos, **emite à força** a
     sequência de peças inteira da forma mais curta que falta.

(b) é o que (1) não tinha: em vez de empurrar o modelo token a token e torcer,
completa a palavra de uma vez. Garante terminação e cobertura, sem busca em feixe.

Isto é a REFERÊNCIA DO PORTE PARA KOTLIN (§6.3): o laço aqui tem a mesma forma que
o do app — uma passada de encoder, N passos de decoder, decisão por passo. Quem
portar deve reproduzir as duas regras, e o teste de paridade da Fase 4 compara
justamente estas saídas.
"""
from __future__ import annotations

import unicodedata

import torch

EOS, PAD, UNK = 1, 0, 2
PACIENCIA = 6


def norm(t: str) -> str:
    d = unicodedata.normalize("NFD", t.lower())
    d = "".join(c for c in d if unicodedata.category(c) != "Mn")
    return " ".join("".join(c if c.isalnum() or c.isspace() else " " for c in d).split())


def _formas_em_pecas(glosas, lexico, tokenizar):
    """glosa -> [(texto normalizado, [ids])], ordenado da forma mais curta para a mais longa."""
    saida = {}
    for g in glosas:
        alts = []
        for forma in lexico[g]["formas"]:
            ids = [i for i in tokenizar(forma) if i > UNK]
            if ids:
                alts.append((norm(forma), ids))
        if alts:
            saida[g] = sorted(alts, key=lambda x: len(x[1]))
    return saida


def gerar_restrito(modelo, glosas, ids_entrada, lexico, destok, tokenizar, max_novos=40):
    formas = _formas_em_pecas(glosas, lexico, tokenizar)
    entrada = torch.tensor([ids_entrada])

    def texto(ids):
        return norm("".join(destok[i] for i in ids if i > UNK).replace("▁", " "))

    def faltando(ids):
        t = texto(ids)
        return [g for g, alts in formas.items() if not any(f in t for f, _ in alts)]

    saida: list[int] = []
    sem_avanco, restavam = 0, len(faltando(saida))

    while len(saida) < max_novos:
        dec = torch.tensor([[PAD] + saida])
        with torch.no_grad():
            logits = modelo(input_ids=entrada, decoder_input_ids=dec).logits[0, -1]

        faltam = faltando(saida)
        if len(faltam) < restavam:
            restavam, sem_avanco = len(faltam), 0

        if not faltam:
            prox = int(logits.argmax())
            if prox == EOS:
                break
            saida.append(prox)
            continue

        # regra (b): cobertura travada — completa a palavra de uma vez
        if sem_avanco >= PACIENCIA:
            _, ids = formas[faltam[0]][0]
            saida.extend(ids)
            sem_avanco, restavam = 0, len(faltando(saida))
            continue

        # regra (a): não pode parar devendo conteúdo
        logits[EOS] = float("-inf")
        saida.append(int(logits.argmax()))
        sem_avanco += 1

    # garantia final: o que ainda faltar entra à força, custe a fluência
    for g in faltando(saida):
        saida.extend(formas[g][0][1])

    return "".join(destok[i] for i in saida if i > UNK).replace("▁", " ").strip()

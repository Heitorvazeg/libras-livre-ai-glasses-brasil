"""Detecção de CONTEÚDO INVENTADO — a linha do §8.1 que faltava virar métrica.

A guarda do §3.2 verifica que toda glosa APARECE. Nunca verifica que nada ALÉM
apareceu. Medido no v2:

    [eu, filho]            -> "eu tenho um filho que estuda aqui"   ("estuda aqui")
    [eu, voltar, america]  -> "eu volto amanhã na américa"          ("amanhã")

Em atendimento isso é pior que soar robótico: a pessoa não disse aquilo. O
template não tem esse modo de falha — ele só emite o que está na tabela.

COMO: toda palavra de conteúdo da saída precisa ser explicável por alguma glosa
do enunciado (via as formas do léxico) ou ser palavra funcional (artigo,
preposição, cópula, pronome oblíquo...). O que sobra é invenção.

Conservador de propósito: a lista de funcionais é generosa, então a métrica
SUBESTIMA a invenção. Um número alto aqui é confiável; um número baixo não prova
ausência.
"""
from __future__ import annotations

import json
import unicodedata
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
LEXICO = json.loads((RAIZ / "lexico" / "lexico-glosas.json").read_text("utf-8"))["glosas"]

FUNCIONAIS = set("""
a o as os um uma uns umas ao aos à às do da dos das no na nos nas num numa pelo pela
de em por para pra com sem sobre entre até desde após e ou mas que se como quando
é são era foi ser estar está estou estão esta este esse essa isso aquilo aquele aquela
me te se lhe nos vos meu minha meus minhas seu sua seus suas dele dela
eu tu ele ela nos eles elas voce senhor senhora
muito mais menos ja agora aqui ali la entao tambem so apenas bem mal sim nao
tenho tem temos ter tinha vou vai vamos ir aqui pode posso podem poderia
mesmo mesma proprio propria todo toda todos todas outro outra
fica ficar ficam sente sentir sentindo vem vir vai indo passa passar
dia dias hora horas vez vezes coisa parte lugar
""".split())
# VERBOS LEVES e dêiticos de tempo/lugar entram como funcionais porque em PT eles
# carregam a relação, não o conteúdo: "o banco FICA na esquina" não afirma nada que
# "banco esquina" já não afirme. O que NÃO entra é conteúdo novo de verdade
# ("amanhã", "estuda"), que é o que o §8.1 chama de invenção.


def _norm(t: str) -> str:
    d = unicodedata.normalize("NFD", t.lower())
    d = "".join(c for c in d if unicodedata.category(c) != "Mn")
    return "".join(c if c.isalnum() or c.isspace() else " " for c in d)


def inventadas(saida: str, glosas: list[str]) -> list[str]:
    """Palavras de conteúdo da saída que nenhuma glosa do enunciado explica."""
    permitidas = set()
    for g in glosas:
        for forma in LEXICO[g]["formas"]:
            permitidas.update(_norm(forma).split())
    fora = []
    for p in _norm(saida).split():
        if len(p) <= 2 or p in FUNCIONAIS or p in permitidas:
            continue
        # radical de 4+ letras batendo com alguma forma permitida conta como coberto
        if any(p[:4] == q[:4] for q in permitidas if len(q) >= 4):
            continue
        fora.append(p)
    return fora

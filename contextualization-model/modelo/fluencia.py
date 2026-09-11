"""Detecção de saída degenerada — o buraco que a guarda de cobertura não cobre.

DESCOBERTA (2026-09-11): com decodificação restrita, o modelo v1 produz lixo em
54% das combinações NÃO VISTAS, e esse lixo **passa** na guarda do §3.2 — porque
a guarda só verifica se cada glosa aparece, e a decodificação restrita força as
glosas a aparecerem. Exemplo real:

    [onde, você]  ->  'ondeJ dele dele dele dele dele você'

Cobre `onde` e `você`. Passaria. Seria falado.

Cobertura é necessária e não é suficiente. Este módulo é a outra metade: uma
checagem barata de BOA-FORMAÇÃO. Precisa existir igual no Kotlin se a
decodificação restrita for pro app.
"""
from __future__ import annotations

import re

# Peças que denunciam colapso do decoder. CUIDADO com falso positivo: a primeira
# versão marcava qualquer maiúscula e acusou 100% das saídas do template, que
# capitaliza a inicial. Só maiúscula NO MEIO de palavra conta (ex.: "ondeJ",
# "nãoVa"), além de dígito e caractere fora do alfabeto PT.
SUSPEITO = re.compile(r"(?<=[a-záàâãéêíóôõúüç])[A-Z]|\d|[^\w\s,.?!áàâãéêíóôõúüçÁÀÂÃÉÊÍÓÔÕÚÜÇ-]",
                      re.UNICODE)


def repeticao(texto: str, n: int = 3) -> bool:
    """Mesma palavra n vezes seguidas, ou o mesmo bigrama duas vezes."""
    p = texto.split()
    if any(len(set(p[i:i + n])) == 1 for i in range(len(p) - n + 1)):
        return True
    bigramas = [tuple(p[i:i + 2]) for i in range(len(p) - 1)]
    return len(bigramas) != len(set(bigramas))


def parece_degenerado(texto: str, n_glosas: int) -> tuple[bool, str]:
    """(é degenerado?, motivo). Conservador: na dúvida, NÃO acusa."""
    p = texto.split()
    if not p:
        return True, "vazio"
    if SUSPEITO.search(texto):
        return True, "caractere suspeito"
    if repeticao(texto):
        return True, "repetição"
    # Enunciados deste domínio têm ~2-4 palavras por glosa. Muito além disso é o
    # decoder enrolando porque o EOS estava bloqueado.
    if len(p) > max(8, n_glosas * 5):
        return True, "comprimento"
    return False, ""

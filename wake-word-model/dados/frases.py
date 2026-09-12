"""Frases-alvo e negativos/confusáveis pt-BR para o treino da wake word.

Duas frases simétricas delimitam sessões no app (ver
docs/orquestracao-dialogo-audio-plano.md §4.2): "Libras Livre, iniciar" abre,
"Libras Livre, encerrar" fecha. Cada classificador precisa aprender a distinguir a
sua frase de TUDO — inclusive da frase irmã, que é o confusável mais perigoso dos
dois (Fase 3, critério de sucesso: "incluindo confundir uma frase pela outra").

Este módulo só define TEXTO. Quem gera áudio é `sintetizar.py`.
"""

from __future__ import annotations

# Variações de superfície da MESMA frase — o classificador só vê "é a frase-alvo"
# vs. "não é", então variar pontuação/cadência aqui ajuda a generalizar como as
# pessoas realmente falam, sem inventar uma frase diferente.
VARIANTES_INICIAR = [
    "Libras Livre, iniciar",
    "Libras Livre iniciar",
    "libras livre, iniciar",
    "Libras Livre, pode iniciar",
]

VARIANTES_ENCERRAR = [
    "Libras Livre, encerrar",
    "Libras Livre encerrar",
    "libras livre, encerrar",
    "Libras Livre, pode encerrar",
]

# Confusáveis fonéticos e frases truncadas — pensados pra pegar o "falso positivo
# óbvio" antes de qualquer teste com gente de verdade (docs/orquestracao-dialogo-audio-plano.md
# Fase 3: "curar negativos/confusables em pt-BR na mão — não existe dataset pronto
# pra isso").
CONFUSAVEIS = [
    "Libras Livre",
    "Libras",
    "Livre",
    "iniciar",
    "encerrar",
    "vamos iniciar",
    "pode começar",
    "vamos começar o atendimento",
    "terminar",
    "finalizar",
    "finalizar o atendimento",
    "bibliotecas livres, iniciar",
    "libras livre, iniciando",
    "libras livre, encerrando",
    "libras, iniciar",
    "livre, iniciar",
    "libras livre, um segundo",
    "então tá, iniciar",
]

# Frases genéricas de um atendimento de balcão — negativo "de contexto", pra não
# disparar em qualquer fala perto do produto (o dispositivo é institucional, ver
# docs/orquestracao-dialogo-audio-plano.md §4.3).
CONTEXTO_ATENDIMENTO = [
    "bom dia, como posso ajudar",
    "qual é o seu nome",
    "você tem algum documento",
    "aguarde um momento, por favor",
    "vou verificar aqui pra você",
    "vou chamar o próximo",
    "pode repetir, por favor",
    "não entendi, pode falar de novo",
    "obrigado pela paciência",
    "já vamos te atender",
    "com licença, um minuto",
    "é pra já",
    "qual o motivo do seu atendimento hoje",
    "posso ver o seu cadastro",
]

POSITIVOS: dict[str, list[str]] = {
    "libras_livre_iniciar": VARIANTES_INICIAR,
    "libras_livre_encerrar": VARIANTES_ENCERRAR,
}


def negativos_para(modelo: str) -> list[str]:
    """Confusáveis + contexto + a FRASE IRMÃ (o confusável mais perigoso dos dois)."""
    irma = VARIANTES_ENCERRAR if modelo == "libras_livre_iniciar" else VARIANTES_INICIAR
    return CONFUSAVEIS + CONTEXTO_ATENDIMENTO + irma

"""Baseline por regras — o `TemplateGlossContextualizer` do §4, em Python.

Existe por dois motivos, e o primeiro importa mais: é o PORTÃO. O §10 diz que um
modelo que não bate isto no conjunto humano não entra no APK. Por isso é uma
tentativa HONESTA, não um espantalho — um baseline fraco de propósito tornaria a
comparação inútil e o portão, teatro.

Segundo motivo: é o fallback permanente de §3.2.

Cobre: sujeito explícito ou implícito (1ª/3ª pessoa), conjugação por pessoa,
negação, interrogativos, saudações compostas e predicados de estado.
"""
from __future__ import annotations

# Conjugação por pessoa: (1ª sing, 3ª sing).
VERBOS = {
    "querer":    ("quero", "quer"),
    "vontade":   ("quero", "quer"),
    "precisar":  ("preciso", "precisa"),
    "conhecer":  ("conheço", "conhece"),
    "esperar":   ("estou esperando", "está esperando"),
    "voltar":    ("volto", "volta"),
    "aproveitar":("vou aproveitar", "vai aproveitar"),
    "acontecer": ("aconteceu", "aconteceu"),
}
# Predicados de estado: (1ª sing, 3ª sing).
ESTADOS = {
    "dor":  ("estou com dor", "está com dor"),
    "medo": ("estou com medo", "está com medo"),
    "ruim": ("estou mal", "está mal"),
}
SUJEITOS = {"eu": ("eu", 0), "você": ("você", 1), "filho": ("o meu filho", 1), "aluno": ("o aluno", 1)}
OBJETOS = {
    "banheiro": "o banheiro", "documento": "o documento", "nome": "o nome",
    "número": "a senha", "vacina": "a vacina", "banco": "o banco",
    "esquina": "a esquina", "espelho": "o espelho", "bala": "uma bala",
    "maçã": "uma maçã", "sapo": "um sapo", "america": "a américa",
    "barulho": "barulho", "ajuda": "ajuda", "cinco": "cinco",
    "amarelo": "amarelo", "filho": "o meu filho", "aluno": "aluno",
}
TEMPO = {"manhã": "de manhã", "noite": "à noite"}
INTERROGATIVOS = {"onde": "Onde fica", "quanto": "Quanto tempo", "quando": "Quando"}
# Verbos que regem preposição — sem isto sai "preciso a vacina".
REGENCIA = {"precisar": "de"}
CONTRACAO = {("de", "o"): "do", ("de", "a"): "da", ("de", "um"): "de um", ("de", "uma"): "de uma"}


def _reger(prep: str, objeto: str) -> str:
    cabeca, _, resto = objeto.partition(" ")
    junto = CONTRACAO.get((prep, cabeca))
    return f"{junto} {resto}".strip() if junto else f"{prep} {objeto}"
SAUDACOES = {"oi": "olá", "obrigado": "obrigado", "por-favor": "por favor",
             "sim": "sim", "conhecer": None}


def contextualizar(glosas: list[str]) -> str:
    g = list(glosas)
    negado = "não" in g
    g = [x for x in g if x != "não"]

    # Saudação composta: oi+manhã/noite viram "bom dia"/"boa noite", não "olá de manhã".
    prefixo = ""
    if "oi" in g:
        g.remove("oi")
        if "manhã" in g:
            g.remove("manhã"); prefixo = "bom dia"
        elif "noite" in g:
            g.remove("noite"); prefixo = "boa noite"
        else:
            prefixo = "olá"
    sufixo = ""
    if "por-favor" in g:
        g.remove("por-favor"); sufixo = "por favor"
    if "obrigado" in g:
        g.remove("obrigado")
        agradecimento = "obrigado pela ajuda" if "ajuda" in g else "obrigado"
        if agradecimento.endswith("ajuda"):
            g.remove("ajuda")
        prefixo = (prefixo + ", " + agradecimento).lstrip(", ") if prefixo else agradecimento
    if "sim" in g:
        g.remove("sim"); prefixo = (prefixo + ", sim").lstrip(", ") if prefixo else "sim"

    interrog = next((x for x in g if x in INTERROGATIVOS), None)
    if interrog:
        g.remove(interrog)

    # Sujeito: pronome explícito manda; senão uma pessoa vira sujeito de 3ª.
    sujeito, pessoa = "", 0
    for cand in ("eu", "você", "filho", "aluno"):
        if cand in g and (cand in ("eu", "você") or any(x in VERBOS or x in ESTADOS for x in g)):
            sujeito, pessoa = SUJEITOS[cand]
            g.remove(cand)
            break

    tempo = [TEMPO[x] for x in g if x in TEMPO]
    g = [x for x in g if x not in TEMPO]

    nucleo = ""
    verbo = next((x for x in g if x in VERBOS), None)
    estado = next((x for x in g if x in ESTADOS), None)
    if verbo:
        g.remove(verbo); nucleo = VERBOS[verbo][pessoa]
    elif estado:
        g.remove(estado); nucleo = ESTADOS[estado][pessoa]

    objetos = [OBJETOS[x] for x in g if x in OBJETOS]
    if verbo in REGENCIA and objetos:
        objetos = [_reger(REGENCIA[verbo], objetos[0])] + objetos[1:]
    if "ruim" in g and estado != "ruim":
        objetos.append("ruim")
    objetos = [o for o in objetos if o]

    corpo = " ".join(p for p in ([sujeito] + (["não"] if negado else []) + [nucleo] + objetos + tempo) if p)

    if interrog:
        frase = f"{INTERROGATIVOS[interrog]} {corpo}?".replace("  ", " ")
    elif corpo:
        frase = corpo + "."
    else:
        frase = ""

    todo = ", ".join(p for p in [prefixo, frase] if p)
    if sufixo:
        todo = (todo.rstrip(".?!") + f", {sufixo}." if todo else sufixo.capitalize() + ".")
    todo = todo.strip()
    return todo[0].upper() + todo[1:] if todo else " ".join(glosas)


if __name__ == "__main__":
    casos = [["eu","não","querer","vacina"], ["onde","banheiro"], ["eu","dor","ruim"],
             ["quanto","esperar"], ["oi","manhã"], ["filho","precisar","vacina"],
             ["eu","precisar","ajuda"], ["obrigado","ajuda"], ["banheiro","onde","por-favor"],
             ["filho","dor"], ["eu","não","conhecer","banco"], ["você","querer","vacina"]]
    for gs in casos:
        print(f"{str(gs):<44} -> {contextualizar(gs)!r}")

"""Política de rótulos multilingues, compartilhada pela preparação e pela CLI.

Mesmo texto não prova equivalência entre sinais de ASL e Libras. Preservamos
Libras e excluímos todos os clipes WLASL desses rótulos, sem renomear arquivos.
Os registros recebidos precisam ter passado pela validação de proveniência.
"""
from collections import defaultdict


def exclusao_rotulos_asl(registros: dict[str, dict]) -> dict:
    """Relata conflitos exatos entre WLASL e V-LIBRASIL/MALTA selecionados.

    Usa a entrada anterior aos filtros de duplicatas, frames e mínimo de classe.
    MINDS nunca orienta essa seleção. Referências e rótulos ficam ordenados para
    produzir o mesmo manifesto independentemente da ordem de descoberta.
    """
    libras, wlasl = defaultdict(list), defaultdict(list)
    for nome, r in registros.items():
        if r["fonte"] in ("vlibrasil", "malta"):
            libras[r["sinal"]].append(nome)
        elif r["fonte"] == "wlasl":
            wlasl[r["sinal"]].append(nome)
    return {
        "politica": "excluir_wlasl_com_rotulo_exato_presente_em_libras_v1",
        "conflitos": [
            {"rotulo": rotulo, "libras": sorted(libras[rotulo]),
             "wlasl": sorted(wlasl[rotulo])}
            for rotulo in sorted(libras.keys() & wlasl.keys())
        ],
    }
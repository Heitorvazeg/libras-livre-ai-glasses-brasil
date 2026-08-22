"""Validação offline da ingestão — roda sem rede e sem baixar nada.

Cobre o que quebra em silêncio: a tradução dos nomes de arquivo das duas bases
para a convenção da PoC, a unicidade das pessoas entre bases (o erro que faria a
avaliação leave-one-signer-out testar em quem treinou), a ordem de download e a
coerência entre selecao.yaml e os dois config.yaml do repositório.

    python selftest.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ingest  # noqa: E402
from remote_zip import Membro  # noqa: E402

AQUI = Path(__file__).resolve().parent


class ZipFalso:
    """Só o que os coletores usam de um ZipRemoto: o dicionário de membros."""

    def __init__(self, nomes: dict[str, int]):
        self.membros = {n: Membro(n, tam, tam, 8, 0, 0) for n, tam in nomes.items()}

    def __len__(self) -> int:
        return len(self.membros)


def _ok(msg: str) -> None:
    print(f"  ✓ {msg}")


def teste_vocabularios_batem() -> None:
    """selecao.yaml, PoC/config.yaml e config.yaml precisam listar os mesmos sinais."""
    sel = ingest.carregar_selecao()
    selecionados = {s["sinal"] for s in sel["sinais"]}
    for config in (AQUI.parent / "PoC" / "config.yaml", AQUI.parent / "config.yaml"):
        vocab = set(yaml.safe_load(config.read_text(encoding="utf-8"))["vocabulario"])
        assert vocab == selecionados, f"{config}: vocabulário {sorted(vocab)} != seleção"
    assert len(selecionados) == 10, f"esperava 10 sinais, veio {len(selecionados)}"
    _ok("vocabulário igual em selecao.yaml, PoC/config.yaml e config.yaml")


def teste_traducao_de_nomes() -> None:
    """Nome de origem -> nome na convenção da PoC, nas duas bases."""
    sel = ingest.carregar_selecao()
    minds = ingest._clipes_minds(ZipFalso({
        "01AcontecerSinalizador05-3.mp4": 100,
        "17RuimSinalizador12-5.mp4": 100,
        "13MacaSinalizador01-1.mp4": 100,
        "02AlunoSinalizador05-3.mp4": 100,   # fora da seleção: tem que ser ignorado
    }), sel)
    obtidos = {c.destino for c in minds}
    assert obtidos == {"pessoaM05_sinal-acontecer_rep03.mp4",
                       "pessoaM12_sinal-ruim_rep05.mp4",
                       "pessoaM01_sinal-maca_rep01.mp4"}, obtidos

    vlib = ingest._clipes_vlibrasil(ZipFalso({
        "videos UFPE (V-LIBRASIL)/data/Ruim_Articulador2.mp4": 100,
        "videos UFPE (V-LIBRASIL)/data/Maçã (rosto)_Articulador1.mp4": 100,
        "videos UFPE (V-LIBRASIL)/data/Abacaxi_Articulador1.mp4": 100,  # fora da seleção
        "videos UFPE (V-LIBRASIL)/annotations.csv": 100,
    }), sel)
    obtidos = {c.destino for c in vlib}
    assert obtidos == {"pessoaV02_sinal-ruim_rep01.mp4",
                       "pessoaV01_sinal-maca_rep01.mp4"}, obtidos
    _ok("tradução dos nomes das duas bases para a convenção da PoC")


def teste_nomes_sao_legiveis_pela_poc() -> None:
    """A PoC precisa conseguir ler pessoa/sinal/rep de volta do nome do arquivo."""
    sys.path.insert(0, str(AQUI.parent / "PoC" / "src"))
    try:
        from dtw_classifier import parse_nome  # type: ignore
    except ImportError as e:  # numpy/dtaidistance podem não estar neste interpretador
        print(f"  ~ convenção de nome (pulado: {e})")
        return
    pessoa, sinal, rep = parse_nome("pessoaM05_sinal-acontecer_rep03")
    assert (pessoa, sinal, rep) == ("M05", "acontecer", "03"), (pessoa, sinal, rep)
    pessoa, sinal, rep = parse_nome("pessoaV02_sinal-ruim_rep01")
    assert (pessoa, sinal, rep) == ("V02", "ruim", "01"), (pessoa, sinal, rep)
    _ok("os nomes gerados voltam a pessoa/sinal/rep no parser da PoC")


def teste_pessoas_nao_colidem() -> None:
    """Sinalizador 02 de uma base != articulador 02 da outra."""
    sel = ingest.carregar_selecao()
    minds = ingest._clipes_minds(ZipFalso({"17RuimSinalizador02-1.mp4": 100}), sel)
    vlib = ingest._clipes_vlibrasil(
        ZipFalso({"videos UFPE (V-LIBRASIL)/data/Ruim_Articulador2.mp4": 100}), sel)
    assert minds[0].pessoa != vlib[0].pessoa, "as duas bases colidiram na mesma pessoa"
    _ok("pessoas de bases diferentes nunca colidem")


def teste_ordem_e_filtros() -> None:
    """A ordem é por repetição (interrupção deixa dataset balanceado) e --reps corta."""
    sel = ingest.carregar_selecao()
    clipes = ingest._clipes_minds(ZipFalso({
        "01AcontecerSinalizador01-1.mp4": 1, "01AcontecerSinalizador01-2.mp4": 1,
        "17RuimSinalizador01-1.mp4": 1, "17RuimSinalizador01-2.mp4": 1,
    }), sel)
    ordenados = ingest.filtrar(clipes, None, None, False)
    assert [c.rep for c in ordenados] == [1, 1, 2, 2], [c.rep for c in ordenados]

    so_uma = ingest.filtrar(clipes, None, 1, False)
    assert {c.rep for c in so_uma} == {1} and len(so_uma) == 2

    so_ruim = ingest.filtrar(clipes, {"ruim"}, None, False)
    assert {c.sinal for c in so_ruim} == {"ruim"}
    _ok("ordem por repetição, --reps e --sinais")


def teste_somente_validados() -> None:
    """--somente-validados descarta os rótulos pareados por julgamento."""
    sel = ingest.carregar_selecao()
    clipes = ingest._clipes_minds(ZipFalso({
        "17RuimSinalizador01-1.mp4": 1,   # rótulo idêntico nas duas bases
        "13MacaSinalizador01-1.mp4": 1,   # pareado por julgamento
    }), sel)
    validados = ingest.filtrar(clipes, None, None, True)
    assert {c.sinal for c in validados} == {"ruim"}, {c.sinal for c in validados}
    _ok("--somente-validados corta os rótulos a conferir")


def teste_manifesto(tmp: Path) -> None:
    """O manifesto registra origem, destino e o que já está em disco."""
    import csv

    sel = ingest.carregar_selecao()
    clipes = ingest._clipes_minds(ZipFalso({"17RuimSinalizador01-1.mp4": 42}), sel)
    destino = tmp / "raw"
    destino.mkdir(parents=True)
    saida = tmp / "manifest.csv"

    ingest.escrever_manifesto(clipes, destino, saida)
    linha = next(iter(csv.DictReader(saida.open(encoding="utf-8"))))
    assert linha["estado"] == "pendente" and linha["origem"] == "17RuimSinalizador01-1.mp4"

    (destino / clipes[0].destino).write_bytes(b"x")
    ingest.escrever_manifesto(clipes, destino, saida)
    linha = next(iter(csv.DictReader(saida.open(encoding="utf-8"))))
    assert linha["estado"] == "baixado", linha
    _ok("manifesto reflete origem, destino e estado em disco")


def main() -> None:
    import tempfile

    print("[selftest] ingestão de datasets públicos — sem rede")
    teste_vocabularios_batem()
    teste_traducao_de_nomes()
    teste_nomes_sao_legiveis_pela_poc()
    teste_pessoas_nao_colidem()
    teste_ordem_e_filtros()
    teste_somente_validados()
    with tempfile.TemporaryDirectory() as tmp:
        teste_manifesto(Path(tmp))
    print("[selftest] tudo OK — a receita de ingestão está coerente com o repositório.")


if __name__ == "__main__":
    main()

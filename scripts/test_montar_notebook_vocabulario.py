"""Gerador do notebook de vocabulário reduzido: o que sai precisa ser executável,
recortar o corpus a partir do inventário validado e não mexer na receita.
"""
import ast
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import montar_notebook_vocabulario as mnv

RAIZ = Path(__file__).resolve().parents[1]
SHA = subprocess.run(["git", "-C", str(RAIZ), "rev-parse", "HEAD"],
                     capture_output=True, text=True, check=True).stdout.strip()
DEZ = mnv.PALAVRAS_PADRAO


def fonte_codigo(nb):
    return "".join("".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code")


class TestGerador(unittest.TestCase):
    def test_celulas_compilam_e_nome_unico(self):
        for modo in ("final", "loso"):
            with self.subTest(modo=modo):
                nb = mnv.gerar(SHA, modo, DEZ, f"{modo}-teste-v1")
                for celula in nb["cells"]:
                    if celula["cell_type"] == "code":
                        ast.parse("".join(celula["source"]))
                        self.assertEqual(celula["outputs"], [])
                        self.assertIsNone(celula["execution_count"])
                codigo = fonte_codigo(nb)
                self.assertEqual(codigo.count("NOME_EXPERIMENTO ="), 1)
                self.assertIn(f'NOME_EXPERIMENTO = "{modo}-teste-v1"', codigo)
                self.assertNotIn("final-s20260917-v1", codigo)

    def test_guardas_do_corpus_preservadas(self):
        codigo = fonte_codigo(mnv.gerar(SHA, "final", DEZ, "x"))
        for trecho in ('"checkout", "--detach", COMMIT_APROVADO', "codigo_final.conferir_codigo",
                       "entrada_final.validar_minds", "HASH_BACKBONE_APROVADO",
                       'executar("selftest.py"', "empacotar()",
                       'hashlib.sha256(bruto).hexdigest() != amostra["sha256"]',
                       'registrar("inventario-vocabulario.json"'):
            self.assertIn(trecho, codigo)

    def test_recorte_usa_as_palavras_e_alimenta_o_treino(self):
        codigo = fonte_codigo(mnv.gerar(SHA, "final", DEZ, "x"))
        self.assertIn('PALAVRAS = ["banco", "banheiro", "cinco"', codigo)
        # O treino lê o recorte, não o corpus completo.
        self.assertIn('"--landmarks", str(MINDS_VOCAB)', codigo)
        self.assertNotIn('"--landmarks", str(MINDS)', codigo)
        # --inventario-final vale para as 800 identidades; não cabe no recorte.
        self.assertNotIn("--inventario-final", codigo)

    def test_receita_de_entrega_intacta(self):
        for modo in ("final", "loso"):
            codigo = fonte_codigo(mnv.gerar(SHA, modo, DEZ, "x"))
            for flag, valor in (("--semente", "20260917"), ("--epocas", "120"),
                                ("--lr", "1e-3"), ("--wd", "1e-4"), ("--batch", "64"),
                                ("--kernel-temporal", "9"), ("--fontes", "minds")):
                self.assertIn(f'"{flag}", "{valor}"', codigo, f"{modo}: {flag}")
            self.assertIn("EXTRAS_EXTERNOS = False", codigo)
            self.assertIn("AUG_DOMINIO = False", codigo)
            # As flags não podem ser PASSADAS ao treinar.py; o notebook original
            # cita as duas em comentários, então a busca é pelo argumento entre aspas.
            self.assertNotIn('"--aug-dominio"', codigo)
            self.assertNotIn('"--extras-manifesto"', codigo)

    def test_modos_diferem_no_esperado(self):
        final = fonte_codigo(mnv.gerar(SHA, "final", DEZ, "x"))
        loso = fonte_codigo(mnv.gerar(SHA, "loso", DEZ, "x"))
        self.assertIn('"--final", "--politica-final", "ultima"', final)
        self.assertNotIn("--final", loso.replace("--final-", ""))
        self.assertIn("--salvar-evidencias", loso)
        self.assertNotIn("--salvar-evidencias", final)
        self.assertIn("len(rodadas) != 8", loso)

    def test_cli_grava_notebook_e_metadados(self):
        with tempfile.TemporaryDirectory() as tmp:
            destino = Path(tmp) / "kernel"
            self.assertEqual(mnv.main(["--sha", SHA, "--saida", str(destino), "--modo", "final"]), 0)
            meta = json.loads((destino / "kernel-metadata.json").read_text())
            self.assertEqual(meta["id"], "walissonfagundes/final-vocab10")
            self.assertTrue(meta["is_private"] and meta["enable_gpu"])
            nb = json.loads((destino / meta["code_file"]).read_text())
            for celula in nb["cells"]:
                if celula["cell_type"] == "code":
                    ast.parse("".join(celula["source"]))

    def test_palavras_em_maiuscula_sao_normalizadas(self):
        with tempfile.TemporaryDirectory() as tmp:
            destino = Path(tmp) / "k"
            self.assertEqual(mnv.main(["--sha", SHA, "--saida", str(destino),
                                       "--modo", "final", "--palavras", "FILHO, Medo"]), 0)
            nb = json.loads(next(destino.glob("*.ipynb")).read_text())
            self.assertIn('PALAVRAS = ["filho", "medo"]', fonte_codigo(nb))

    def test_argumentos_invalidos(self):
        with tempfile.TemporaryDirectory() as tmp:
            for extra in (["--sha", SHA[:7], "--modo", "final"],
                          ["--sha", SHA, "--modo", "final", "--palavras", "filho,filho"],
                          ["--sha", SHA, "--modo", "final", "--palavras", "filho"]):
                with self.subTest(extra=extra), self.assertRaises(SystemExit):
                    mnv.main([*extra, "--saida", str(Path(tmp) / "k")])


if __name__ == "__main__":
    unittest.main()

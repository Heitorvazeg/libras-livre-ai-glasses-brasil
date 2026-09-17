"""Gerador do notebook LOSO: o que sai precisa ser executável e ser LOSO.

O primeiro notebook gerado quebrou no Kaggle (IndentationError) porque a troca
do nome do experimento cortava só a primeira linha de uma atribuição de duas.
Aqui todas as células são compiladas DEPOIS de todas as trocas.
"""
import ast
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import montar_notebook_loso as mnl

RAIZ = Path(__file__).resolve().parents[1]
SHA = subprocess.run(["git", "-C", str(RAIZ), "rev-parse", "HEAD"],
                     capture_output=True, text=True, check=True).stdout.strip()


def fonte_codigo(nb):
    return "".join("".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code")


class TestGerador(unittest.TestCase):
    def test_celulas_compilam_e_nome_unico(self):
        for evidencias in (False, True):
            with self.subTest(evidencias=evidencias):
                nb = mnl.gerar(SHA, evidencias, "loso-teste-v1")
                for celula in nb["cells"]:
                    if celula["cell_type"] == "code":
                        ast.parse("".join(celula["source"]))  # falha se sobrar linha órfã
                        self.assertEqual(celula["outputs"], [])
                        self.assertIsNone(celula["execution_count"])
                codigo = fonte_codigo(nb)
                self.assertEqual(codigo.count("NOME_EXPERIMENTO ="), 1)
                self.assertIn('NOME_EXPERIMENTO = "loso-teste-v1"', codigo)
                self.assertNotIn("final-s20260917", codigo)

    def test_receita_loso_sem_final_e_sem_extras(self):
        codigo = fonte_codigo(mnl.gerar(SHA, True, "x"))
        self.assertNotIn("FINAL_ARGS", codigo)
        self.assertNotIn("--final", codigo)
        self.assertIn('LOSO_ARGS.append("--salvar-evidencias")', codigo)
        self.assertIn("EXTRAS_EXTERNOS = False", codigo)
        self.assertIn("AUG_DOMINIO = False", codigo)
        for flag, valor in (("--semente", "20260917"), ("--epocas", "120"), ("--fontes", "minds")):
            self.assertIn(f'"{flag}", "{valor}"', codigo)
        self.assertNotIn('LOSO_ARGS.append("--salvar-evidencias")', fonte_codigo(mnl.gerar(SHA, False, "x")))

    def test_guardas_do_notebook_preservadas(self):
        codigo = fonte_codigo(mnl.gerar(SHA, True, "x"))
        for trecho in ('"checkout", "--detach", COMMIT_APROVADO', "codigo_final.conferir_codigo",
                       "entrada_final.validar_minds", "HASH_BACKBONE_APROVADO",
                       'executar("selftest.py"', "empacotar()"):
            self.assertIn(trecho, codigo)

    def test_troca_de_nome_em_uma_ou_varias_linhas(self):
        uma = 'NOME_EXPERIMENTO = "final-v1"\nEXP = BASE / NOME_EXPERIMENTO\n'
        duas = ('NOME_EXPERIMENTO = ("a" if AUG else\n                    "b" if EXT else "c")\n'
                'EXP = BASE / NOME_EXPERIMENTO\n')
        for fonte in (uma, duas):
            saida = mnl.trocar_nome_experimento(fonte, "novo")
            ast.parse(saida)
            self.assertIn('NOME_EXPERIMENTO = "novo"', saida)
            self.assertIn("EXP = BASE / NOME_EXPERIMENTO", saida)
        with self.assertRaises(SystemExit):
            mnl.trocar_nome_experimento("sem atribuicao aqui\n", "novo")

    def test_cli_grava_notebook_e_metadados(self):
        with tempfile.TemporaryDirectory() as tmp:
            destino = Path(tmp) / "kernel"
            self.assertEqual(mnl.main(["--sha", SHA, "--saida", str(destino), "--evidencias"]), 0)
            meta = json.loads((destino / "kernel-metadata.json").read_text())
            self.assertEqual(meta["id"], "walissonfagundes/etapa3-loso-evid")
            self.assertTrue(meta["is_private"] and meta["enable_gpu"])
            nb = json.loads((destino / meta["code_file"]).read_text())
            for celula in nb["cells"]:
                if celula["cell_type"] == "code":
                    ast.parse("".join(celula["source"]))

    def test_sha_precisa_ser_completo(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(SystemExit):
            mnl.main(["--sha", SHA[:7], "--saida", str(Path(tmp) / "k")])


if __name__ == "__main__":
    unittest.main()

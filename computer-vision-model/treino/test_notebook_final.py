"""Contrato estático do notebook; nunca executa células nem acessa rede/GPU."""
import ast
import json
from pathlib import Path
import unittest


class TestNotebookFinal(unittest.TestCase):
    def setUp(self):
        self.notebook = json.loads(Path(__file__).with_name("notebook_treino_final.ipynb").read_text())
        self.fontes = ["".join(c["source"]) if isinstance(c["source"], list) else c["source"]
                       for c in self.notebook["cells"] if c["cell_type"] == "code"]
        self.arvores = [ast.parse(s) for s in self.fontes]

    def test_celulas_sintaxe_sem_execucao(self):
        self.assertEqual(self.notebook["nbformat"], 4)
        self.assertEqual(len(self.fontes), 6)
        for c in self.notebook["cells"]:
            if c["cell_type"] == "code":
                self.assertIsNone(c["execution_count"])
                self.assertEqual(c["outputs"], [])

    def test_receita_e_guardas_obrigatorias(self):
        args = next(n.value for arvore in self.arvores for n in ast.walk(arvore)
                    if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name)
                    and t.id == "FINAL_ARGS" for t in n.targets))
        literais = [n.value for n in args.elts if isinstance(n, ast.Constant)]
        for flag in ("--final", "--ossos", "--com-z", "--z-recentrado", "--inventario-final"):
            self.assertIn(flag, literais)
        for flag, valor in (("--epocas", "120"), ("--politica-final", "ultima"),
                            ("--semente", "20260917"), ("--kernel-temporal", "9"),
                            ("--workers", "2"), ("--fontes", "minds")):
            self.assertEqual(literais[literais.index(flag)+1], valor)
        for flag in ("--movimento", "--sem-imputacao", "--adjacencia-adaptativa", "--salvar-evidencias"):
            self.assertNotIn(flag, literais)
        fonte = "\n".join(self.fontes)
        self.assertIn('os.environ.get("LIBRAS_COMMIT_FINAL", "")', fonte)
        self.assertIn('"checkout", "--detach", COMMIT_APROVADO', fonte)
        self.assertIn("entrada_final.preparar_minds", fonte)
        self.assertIn("manifesto_referencia=INVENTARIO_CAMINHO", fonte)
        self.assertIn('executar("test_politica_final.py"', fonte)
        self.assertNotIn('"merge"', fonte)


if __name__ == "__main__":
    unittest.main()
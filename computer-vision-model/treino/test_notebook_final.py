"""Contrato e descoberta isolada de pastas; sem executar células, rede ou GPU."""
import ast
import json
from pathlib import Path
import tempfile
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

    def _descobrir_minds(self, raiz, *, explicita=None, kaggle=True):
        # Executar somente o bloco de descoberta extraído do código real.
        # Não importar dependências do treino nem executar a célula inteira.
        blocos = [n for arvore in self.arvores for n in arvore.body
                  if isinstance(n, ast.If) and isinstance(n.test, ast.BoolOp)
                  and any(isinstance(v, ast.Name) and v.id == "EM_KAGGLE"
                          for v in n.test.values)]
        self.assertEqual(len(blocos), 1)
        contexto = {"RAIZ_INPUT": raiz, "EM_KAGGLE": kaggle, "ORIGEM_MINDS": explicita}
        exec(compile(ast.Module(body=blocos, type_ignores=[]), "<descoberta-minds>", "exec"), contexto)
        return contexto["ORIGEM_MINDS"]

    def test_hierarquia_kaggle_tres_corpora(self):
        with tempfile.TemporaryDirectory() as tmp:
            raiz = Path(tmp)
            dataset = raiz / "nome-privado-arbitrario"
            minds = dataset / "landmarks-minds" / "landmarks"
            pastas = [minds, dataset / "landmarks-malta" / "ladmarks-malta",
                      dataset / "landmarks-vlibrasil" / "landmarks-pretreino-auditado",
                      raiz / "outro-input" / "landmarks"]
            for pasta in pastas:
                pasta.mkdir(parents=True)
                (pasta / "preservar.npy").write_bytes(b"fixture-descoberta")
            antes = {p.relative_to(raiz): p.read_bytes() for p in raiz.rglob("*.npy")}
            for _ in range(2):
                self.assertEqual(self._descobrir_minds(raiz), minds)
            self.assertEqual(antes, {p.relative_to(raiz): p.read_bytes()
                                     for p in raiz.rglob("*.npy")})

    def test_hierarquia_ambigua_exige_origem_explicita(self):
        with tempfile.TemporaryDirectory() as tmp:
            raiz = Path(tmp)
            for dataset in ("primeiro", "segundo"):
                (raiz / dataset / "landmarks-minds" / "landmarks").mkdir(parents=True)
            with self.assertRaisesRegex(RuntimeError, "Mais de uma pasta MINDS"):
                self._descobrir_minds(raiz)
            explicita = raiz / "segundo" / "landmarks-minds" / "landmarks"
            self.assertEqual(self._descobrir_minds(raiz, explicita=explicita), explicita)

    def test_sem_hierarquia_preserva_descoberta_legada_e_modo_local(self):
        with tempfile.TemporaryDirectory() as tmp:
            raiz = Path(tmp)
            (raiz / "landmarks").mkdir()
            self.assertIsNone(self._descobrir_minds(raiz))
            (raiz / "landmarks-minds" / "landmarks").mkdir(parents=True)
            self.assertIsNone(self._descobrir_minds(raiz, kaggle=False))


if __name__ == "__main__":
    unittest.main()
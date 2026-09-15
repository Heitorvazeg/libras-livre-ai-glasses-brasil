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
        for flag in ("--movimento", "--sem-imputacao", "--adjacencia-adaptativa", "--salvar-evidencias",
                     "--aug-dominio", "--extras-manifesto", "--extras-raiz", "--extras-repeticoes"):
            self.assertNotIn(flag, literais)
        fonte = "\n".join(self.fontes)
        self.assertIn('AUG_DOMINIO = os.environ.get("LIBRAS_AUG_DOMINIO", "0") == "1"', fonte)
        self.assertIn('if AUG_DOMINIO:\n    FINAL_ARGS.append("--aug-dominio")', fonte)
        self.assertIn('get("aug_dominio", False)) != AUG_DOMINIO', fonte)
        self.assertIn('EXTRAS_EXTERNOS = os.environ.get("LIBRAS_EXTRAS_EXTERNOS", "0") == "1"', fonte)
        self.assertIn('if AUG_DOMINIO and EXTRAS_EXTERNOS:', fonte)
        self.assertIn('"--extras-repeticoes", "5"', fonte)
        self.assertIn('extras_externos.ler(MANIFESTO_EXTRAS, RAIZ_EXTRAS)', fonte)
        self.assertIn('executar("test_extras_externos.py", [], "preflight.log")', fonte)
        self.assertIn('extras_externos.gerar(TREINO / "calibracao_naovista_manifesto.json")', fonte)
        self.assertIn('registrar("entrada-extras.json"', fonte)
        self.assertIn('get("repeticoes") != (5 if EXTRAS_EXTERNOS else None)', fonte)
        self.assertIn('set(PESSOAS_EXTRAS)', fonte)
        self.assertIn('os.environ.get("LIBRAS_COMMIT_FINAL", "")', fonte)
        self.assertIn('"checkout", "--detach", COMMIT_APROVADO', fonte)
        self.assertIn("entrada_final.preparar_minds", fonte)
        self.assertIn("manifesto_referencia=INVENTARIO_CAMINHO", fonte)
        self.assertIn('executar("test_politica_final.py"', fonte)
        self.assertNotIn('"merge"', fonte)

    def test_argumentos_das_variantes_sem_rodar_treino(self):
        arvore = next(a for a in self.arvores if any(isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "FINAL_ARGS" for t in n.targets)
            for n in a.body))
        blocos = [n for n in arvore.body if (
            isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "FINAL_ARGS"
                                             for t in n.targets)) or (
            isinstance(n, ast.If) and isinstance(n.test, ast.Name)
            and n.test.id in ("AUG_DOMINIO", "EXTRAS_EXTERNOS")
            and any(isinstance(t, ast.Name) and t.id == "FINAL_ARGS" for t in ast.walk(n)))]
        self.assertEqual(len(blocos), 3)
        for aug, extras in ((False, False), (True, False), (False, True)):
            contexto = {k: Path("/fixture") for k in ("MINDS", "INVENTARIO_CAMINHO", "BACKBONE",
                "SAIDA_FINAL", "MANIFESTO_EXTRAS", "RAIZ_EXTRAS")}
            contexto.update(AUG_DOMINIO=aug, EXTRAS_EXTERNOS=extras)
            exec(compile(ast.Module(body=blocos, type_ignores=[]), "<args-final>", "exec"), contexto)
            args = contexto["FINAL_ARGS"]
            self.assertEqual("--aug-dominio" in args, aug)
            self.assertEqual("--extras-manifesto" in args, extras)
            if extras:
                self.assertEqual(args[args.index("--extras-repeticoes") + 1], "5")

    def test_extras_aceitam_pacote_ou_pasta_extraida(self):
        import contextlib, hashlib, io, pathlib, shutil, tarfile, types
        import numpy as np
        import extras_externos as ee
        bloco = next(n for arvore in self.arvores for n in arvore.body
                     if isinstance(n, ast.If) and isinstance(n.test, ast.Name)
                     and n.test.id == "EXTRAS_EXTERNOS" and "rglob" in ast.unparse(n))
        with tempfile.TemporaryDirectory() as t:
            base = Path(t)
            origem = base / "origem"
            itens = []
            for k, (pessoa, classe, corpus) in enumerate((("V01", "classe0", "vlibrasil"),
                                                         ("T002", "classe1", "malta"))):
                rel = f"PoC/data/x/pessoa{pessoa}_sinal-{classe}_rep01.npy"
                arq = origem / rel
                arq.parent.mkdir(parents=True, exist_ok=True)
                np.save(arq, np.full((5, 57, 3), 0.1 * (k + 1), np.float32))
                itens.append({"classe": classe, "pessoa": pessoa, "corpus": corpus, "arquivo": rel,
                              "sha256": hashlib.sha256(arq.read_bytes()).hexdigest()})
            pai = base / "pai.json"
            pai.write_text(json.dumps({"itens": itens, "divisao_pessoas": {
                "fixada_em": "2026-09-15", "avaliacao": {"pessoas": ["V03"]},
                "treino_externo": {"pessoas": ["T002", "V01"]}}}))
            manifesto = base / "extras.json"
            manifesto.write_text(json.dumps(ee.gerar(pai)))
            pacote = base / "extras-treino-externo.tar.gz"
            with tarfile.open(pacote, "w:gz") as tar:
                for it in itens:
                    tar.add(origem / it["arquivo"], arcname=it["arquivo"])
            hash_de = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()

            def rodar(nome, preparar):
                entrada = base / nome / "input"
                entrada.mkdir(parents=True)
                preparar(entrada)
                ctx = {"EXTRAS_EXTERNOS": True, "RAIZ_INPUT": entrada, "DESTINO": base / nome / "destino",
                       "MANIFESTO_EXTRAS": manifesto, "HASH_PACOTE_EXTRAS": hash_de(pacote),
                       "entrada": types.SimpleNamespace(pv=types.SimpleNamespace(hash_arquivo=hash_de)),
                       "pathlib": pathlib, "tempfile": tempfile, "tarfile": tarfile, "json": json}
                with contextlib.redirect_stdout(io.StringIO()):
                    exec(compile(ast.Module(body=[bloco], type_ignores=[]), "<extras>", "exec"), ctx)
                return ctx

            ctx = rodar("pacote", lambda d: shutil.copy(pacote, d / pacote.name))
            self.assertEqual((ctx["PESSOAS_EXTRAS"], ctx["ORIGEM_EXTRAS"]["forma"]), (["T002", "V01"], "pacote"))
            ctx = rodar("pasta", lambda d: shutil.copytree(origem, d / "extras-treino-externo"))
            self.assertEqual((ctx["PESSOAS_EXTRAS"], ctx["ORIGEM_EXTRAS"]["forma"]), (["T002", "V01"], "pasta_extraida"))
            ee.ler(manifesto, ctx["RAIZ_EXTRAS"])
            for nome, preparar in (("nenhum", lambda d: None),
                                   ("ambos", lambda d: (shutil.copy(pacote, d / pacote.name),
                                                        shutil.copytree(origem, d / "extraida")))):
                with self.assertRaises(RuntimeError):
                    rodar(nome, preparar)

            def adulterar(d):
                shutil.copytree(origem, d / "extraida")
                np.save(d / "extraida" / itens[0]["arquivo"], np.ones((5, 57, 3), np.float32))
            with self.assertRaises(ValueError):
                rodar("adulterado", adulterar)

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
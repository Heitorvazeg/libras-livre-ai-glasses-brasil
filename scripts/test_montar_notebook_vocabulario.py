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
                       'registrar("inventario-vocabulario.json"',
                       # guardas pós-treino da célula original, que a derivação precisa manter
                       'meta["proveniencia"]["codigo"]["commit"] != COMMIT_APROVADO',
                       "any(not torch.isfinite(v).all()",
                       'registrar("checkpoint-final.json"'):
            self.assertIn(trecho, codigo)

    def test_recorte_usa_as_palavras_e_alimenta_o_treino(self):
        codigo = fonte_codigo(mnv.gerar(SHA, "final", DEZ, "x"))
        self.assertIn('PALAVRAS = ["banco", "banheiro", "cinco"', codigo)
        # O treino lê o recorte, não o corpus completo.
        self.assertIn('"--landmarks", str(MINDS_VOCAB)', codigo)
        self.assertNotIn('"--landmarks", str(MINDS)', codigo)
        # --inventario-final vale para as 800 identidades; não cabe no recorte.
        # (o gerador explica isso num comentário, daí a busca pelo argumento entre aspas)
        self.assertNotIn('"--inventario-final"', codigo)

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
        self.assertNotIn("--final", loso)
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


class TestRecorteExecutado(unittest.TestCase):
    """Executa o trecho do recorte contra um corpus fictício.

    O resto da suíte confere texto; esta parte é a única lógica que não existe
    no notebook original, então precisa rodar de verdade: trocar o filtro de
    `sinal` por `pessoa`, por exemplo, passaria em todos os outros testes.
    """
    PESSOAS = [f"M{n:02d}" for n in range(1, 9)]
    SINAIS = ["filho", "medo", "amarelo"]

    def montar_corpus(self, raiz: Path):
        import hashlib
        import numpy as np
        minds = raiz / "minds"
        minds.mkdir(parents=True)
        amostras = []
        for pessoa in self.PESSOAS:
            for sinal in self.SINAIS:
                for rep in range(1, 6):
                    nome = f"pessoa{pessoa}_sinal-{sinal}_rep{rep:02d}.npy"
                    arr = np.full((4, 57, 3), len(amostras) + 1, dtype="float32")
                    np.save(minds / nome, arr, allow_pickle=False)
                    bruto = (minds / nome).read_bytes()
                    amostras.append({"arquivo": nome, "sha256": hashlib.sha256(bruto).hexdigest(),
                                     "pessoa": pessoa, "sinal": sinal, "rep": rep})
        inventario = {"amostras": amostras, "pessoas": list(self.PESSOAS),
                      "rotulos": sorted(self.SINAIS), "corpus_sha256": "0" * 64}
        return minds, inventario

    def rodar(self, raiz: Path, palavras, inventario, minds):
        lista = "[" + ", ".join(f'"{p}"' for p in palavras) + "]"
        codigo = mnv.RECORTE.replace("__PALAVRAS__", lista)
        registros = {}
        escopo = {"DESTINO": raiz / "destino", "MINDS": minds, "INVENTARIO_MINDS": inventario,
                  "registrar": lambda nome, valor: registros.__setitem__(nome, valor),
                  "print": lambda *a, **k: None}
        exec(compile(codigo, "<recorte>", "exec"), escopo)
        return escopo, registros

    def test_copia_so_os_clipes_das_palavras(self):
        with tempfile.TemporaryDirectory() as tmp:
            raiz = Path(tmp)
            minds, inventario = self.montar_corpus(raiz)
            escopo, registros = self.rodar(raiz, ("filho", "medo"), inventario, minds)
            copiados = sorted(p.name for p in escopo["MINDS_VOCAB"].iterdir())
            esperados = sorted(a["arquivo"] for a in inventario["amostras"]
                               if a["sinal"] in ("filho", "medo"))
            self.assertEqual(copiados, esperados)
            self.assertEqual(len(copiados), 2 * 8 * 5)
            self.assertNotIn("amarelo", " ".join(copiados))
            vocab = registros["inventario-vocabulario.json"]
            self.assertEqual(vocab["palavras"], ["filho", "medo"])
            self.assertEqual(vocab["n_clipes"], 80)
            # bytes idênticos aos do corpus validado
            for nome in copiados:
                self.assertEqual((escopo["MINDS_VOCAB"] / nome).read_bytes(),
                                 (minds / nome).read_bytes())

    def test_recusa_clipe_adulterado_depois_do_inventario(self):
        with tempfile.TemporaryDirectory() as tmp:
            raiz = Path(tmp)
            minds, inventario = self.montar_corpus(raiz)
            alvo = next(a for a in inventario["amostras"] if a["sinal"] == "filho")
            (minds / alvo["arquivo"]).write_bytes(b"\x93NUMPY adulterado")
            with self.assertRaises(RuntimeError) as erro:
                self.rodar(raiz, ("filho", "medo"), inventario, minds)
            self.assertIn("diverge do inventário", str(erro.exception))

    def test_recusa_palavra_fora_do_corpus_e_destino_existente(self):
        with tempfile.TemporaryDirectory() as tmp:
            raiz = Path(tmp)
            minds, inventario = self.montar_corpus(raiz)
            with self.assertRaises(RuntimeError):
                self.rodar(raiz, ("filho", "inexistente"), inventario, minds)
            self.rodar(raiz, ("filho", "medo"), inventario, minds)
            with self.assertRaises(RuntimeError) as erro:  # não sobrescreve recorte anterior
                self.rodar(raiz, ("filho", "medo"), inventario, minds)
            self.assertIn("já existe", str(erro.exception))


if __name__ == "__main__":
    unittest.main()

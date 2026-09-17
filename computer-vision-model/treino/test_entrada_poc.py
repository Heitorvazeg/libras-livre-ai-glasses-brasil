"""Regressões de entrada Kaggle, sem rede, GPU ou dados privados."""
import io
import json
import pathlib
import tarfile
import tempfile
import unittest

import numpy as np

from entrada_poc import PACOTE, localizar_minds, preparar_minds


class EntradaPocTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.raiz = pathlib.Path(self.tmp.name)
        self.input = self.raiz / "input"
        self.pasta = self.input / "libras_landmarks" / "landmarks"
        self.pasta.mkdir(parents=True)
        self.nome = "pessoaM01_sinal-ola_rep01.npy"
        np.save(self.pasta / self.nome, np.arange(4 * 57 * 3, dtype=np.float32).reshape(4, 57, 3))
        self.destino = self.raiz / "working" / "landmarks"

    def test_pasta_extraida_copia_sem_alterar_fonte(self):
        sidecar = self.pasta / (self.nome + ".proveniencia.json")
        sidecar.write_text('{"fonte": "minds"}', encoding="utf-8")
        antes = {p.name: p.read_bytes() for p in self.pasta.iterdir()}
        origem = localizar_minds(self.input)
        self.assertEqual(origem, self.pasta)
        self.assertEqual(len(preparar_minds(origem, self.destino)), 1)
        self.assertEqual(antes, {p.name: p.read_bytes() for p in self.pasta.iterdir()})
        self.assertEqual(antes, {p.name: p.read_bytes() for p in self.destino.iterdir()})

    def test_slug_e_profundidade_livres(self):
        outra = self.input / "qualquer-nome" / "versao" / "extraido" / "landmarks"
        outra.parent.mkdir(parents=True)
        self.pasta.rename(outra)
        self.assertEqual(localizar_minds(self.input), outra)

    def test_tar_compativel(self):
        pacote = self.input / "outro-slug" / PACOTE
        pacote.parent.mkdir()
        with tarfile.open(pacote, "w:gz") as tar:
            tar.add(self.pasta, arcname="landmarks")
        # A pasta também existe: a escolha explícita resolve a ambiguidade.
        with self.assertRaisesRegex(ValueError, "Mais de uma"):
            localizar_minds(self.input)
        origem = localizar_minds(self.input, pacote)
        preparar_minds(origem, self.destino)
        self.assertEqual((self.pasta / self.nome).read_bytes(),
                         (self.destino / self.nome).read_bytes())
        (self.pasta / self.nome).unlink()
        self.pasta.rmdir()
        self.assertEqual(localizar_minds(self.input), pacote)

    def test_multiplas_pastas_pedem_escolha(self):
        (self.input / "outra" / "landmarks").mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "ORIGEM_MINDS"):
            localizar_minds(self.input)
        self.assertEqual(localizar_minds(self.input, self.pasta), self.pasta)

    def test_ausencia_de_input_tem_diagnostico(self):
        with self.assertRaisesRegex(FileNotFoundError, "Add Input"):
            localizar_minds(self.raiz / "ausente")
        (self.pasta / self.nome).unlink()
        self.pasta.rmdir()
        with self.assertRaisesRegex(FileNotFoundError, "Entradas visíveis"):
            localizar_minds(self.input)

    def test_destino_preenchido_preservado(self):
        self.destino.mkdir(parents=True)
        salvo = self.destino / "resultado.txt"
        salvo.write_text("preservar")
        with self.assertRaisesRegex(ValueError, "Destino já preenchido"):
            preparar_minds(self.pasta, self.destino)
        self.assertEqual(salvo.read_text(), "preservar")
        self.assertEqual(list(self.destino.iterdir()), [salvo])

    def test_gitkeep_preservado(self):
        self.destino.mkdir(parents=True)
        (self.destino / ".gitkeep").write_text("original")
        (self.pasta / ".gitkeep").touch()
        preparar_minds(self.pasta, self.destino)
        self.assertEqual((self.destino / ".gitkeep").read_text(), "original")

    def test_vazio_e_mistura_rejeitados(self):
        (self.pasta / self.nome).unlink()
        with self.assertRaisesRegex(ValueError, "vazia"):
            preparar_minds(self.pasta, self.destino)
        np.save(self.pasta / "pessoaV01_sinal-ola_rep01.npy", np.zeros((4, 57, 3)))
        with self.assertRaisesRegex(ValueError, "convenção MINDS"):
            preparar_minds(self.pasta, self.destino)
        self.assertFalse(self.destino.exists())

    def test_shape_dtype_e_finitude(self):
        casos = [np.zeros((4, 57, 2)), np.zeros((4, 49, 3)), np.zeros((0, 57, 3)),
                 np.full((4, 57, 3), np.nan), np.full((4, 57, 3), np.inf),
                 np.zeros((4, 57, 3), dtype=np.int32)]
        for arr in casos:
            with self.subTest(shape=arr.shape, dtype=arr.dtype):
                np.save(self.pasta / self.nome, arr)
                with self.assertRaises(ValueError):
                    preparar_minds(self.pasta, self.destino)
                self.assertFalse(self.destino.exists())

    def test_sidecar_invalido_ou_orfao(self):
        sidecar = self.pasta / (self.nome + ".proveniencia.json")
        for texto in ("{", "[]", "{}"):
            sidecar.write_text(texto)
            with self.assertRaises(ValueError):
                preparar_minds(self.pasta, self.destino)
        sidecar.write_text('{"ok": true}')
        (self.pasta / self.nome).unlink()
        with self.assertRaisesRegex(ValueError, "sem landmark"):
            preparar_minds(self.pasta, self.destino)

    def test_links_e_subpastas_rejeitados(self):
        link = self.pasta / "link.npy"
        link.symlink_to(self.pasta / self.nome)
        with self.assertRaisesRegex(ValueError, "regulares"):
            preparar_minds(self.pasta, self.destino)
        link.unlink()
        link.mkdir()
        with self.assertRaisesRegex(ValueError, "regulares"):
            preparar_minds(self.pasta, self.destino)

    def test_membros_tar_inseguros(self):
        for nome, tipo in [("../escape.npy", tarfile.REGTYPE),
                           ("/escape.npy", tarfile.REGTYPE),
                           ("landmarks/link.npy", tarfile.SYMTYPE),
                           ("landmarks/link.npy", tarfile.LNKTYPE),
                           ("landmarks/sub/a.npy", tarfile.REGTYPE),
                           ("landmarks/a.txt", tarfile.REGTYPE)]:
            with self.subTest(nome=nome, tipo=tipo):
                pacote = self.raiz / PACOTE
                with tarfile.open(pacote, "w:gz") as tar:
                    membro = tarfile.TarInfo(nome)
                    membro.type = tipo
                    membro.linkname = "../fora"
                    tar.addfile(membro, io.BytesIO(b""))
                with self.assertRaises(ValueError):
                    preparar_minds(pacote, self.destino)
                self.assertFalse(self.destino.exists())

    def test_tar_duplicado_e_vazio(self):
        pacote = self.raiz / PACOTE
        with tarfile.open(pacote, "w:gz") as tar:
            for _ in range(2):
                tar.add(self.pasta / self.nome, arcname=f"landmarks/{self.nome}")
        with self.assertRaisesRegex(ValueError, "duplicado"):
            preparar_minds(pacote, self.destino)
        with tarfile.open(pacote, "w:gz"):
            pass
        with self.assertRaisesRegex(ValueError, "ausente"):
            preparar_minds(pacote, self.destino)

    def test_celula_real_kaggle_sem_gpu_ou_rede(self):
        treino = pathlib.Path(__file__).resolve().parent
        notebook = json.loads((treino / "notebook_poc_3d.ipynb").read_text())
        self.assertEqual(notebook["metadata"]["language_info"]["name"], "python")
        for cell in notebook["cells"]:
            if cell["cell_type"] == "code":
                compile("".join(cell["source"]), "notebook", "exec")
        source = "".join(notebook["cells"][5]["source"])
        self.assertIn('entrada["preparar_minds"]', source)
        # Só troca o mount fixo para a árvore sintética; executa o código real.
        source = source.replace('pathlib.Path("/kaggle/input")',
                                f"pathlib.Path({str(self.input)!r})")
        repo = self.raiz / "repo"
        exec(compile(source, "celula_6", "exec"), {
            "REPO": repo, "TREINO": treino, "pathlib": pathlib,
            "EM_KAGGLE": True, "EM_COLAB": False,
        })
        self.assertTrue((repo / "computer-vision-model" / "PoC" / "data" /
                         "landmarks" / self.nome).is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
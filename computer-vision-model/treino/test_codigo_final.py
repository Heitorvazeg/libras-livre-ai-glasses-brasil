"""Guarda de snapshot em repositório Git temporário, sem rede ou treino."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from codigo_final import conferir_codigo, validar_sha


class TestCodigoFinal(unittest.TestCase):
    def test_sha_explicito(self):
        for valor in ("", "HEAD", "main", "abc123", "g" * 40, None):
            with self.subTest(valor=valor), self.assertRaises(ValueError):
                validar_sha(valor)

    def test_snapshot_limpo_divergente_e_modificado(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            def git(*args):
                return subprocess.run(["git", "-C", tmp, *args], check=True,
                                      capture_output=True, text=True).stdout.strip()
            git("init")
            fonte = repo / "computer-vision-model/treino/x.py"
            fonte.parent.mkdir(parents=True)
            fonte.write_text("x = 1\n")
            git("add", ".")
            git("-c", "user.name=Teste", "-c", "user.email=teste@example.invalid",
                "commit", "-m", "sintético")
            sha = git("rev-parse", "HEAD")
            self.assertEqual(conferir_codigo(repo, sha), sha)
            (repo / "log-privado.txt").write_text("não é código")
            self.assertEqual(conferir_codigo(repo, sha), sha)
            with self.assertRaisesRegex(ValueError, "HEAD"):
                conferir_codigo(repo, "0" * 40)
            novo = fonte.with_name("sombra.py")
            novo.write_text("x = 2\n")
            with self.assertRaisesRegex(ValueError, "alterações"):
                conferir_codigo(repo, sha)
            novo.unlink()
            fonte.write_text("x = 3\n")
            with self.assertRaisesRegex(ValueError, "alterações"):
                conferir_codigo(repo, sha)


if __name__ == "__main__":
    unittest.main()
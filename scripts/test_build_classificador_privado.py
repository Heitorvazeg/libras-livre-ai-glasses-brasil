"""Testes Gradle opt-in: LIBRAS_TESTAR_BUILD_PRIVADO=1; não gera APK nem publica.

Exige SDK/Gradle locais. Usa apenas pacotes temporários e assets gerados em build/.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import pacote_classificador_privado as p


@unittest.skipUnless(os.environ.get("LIBRAS_TESTAR_BUILD_PRIVADO") == "1", "Gradle opt-in")
class TestBuildPrivado(unittest.TestCase):
    repo = Path(__file__).resolve().parents[1]

    def gradle(self, *args, sucesso=True):
        resultado = subprocess.run([str(self.repo/"mobile-app-companion/gradlew"),
            "-p", str(self.repo/"mobile-app-companion"), "--max-workers=2", "--console=plain",
            *args], capture_output=True, text=True)
        self.assertEqual(resultado.returncode == 0, sucesso, resultado.stdout + resultado.stderr)
        return resultado.stdout + resultado.stderr

    def test_pacote_toggle_hash_ausencia_e_release(self):
        # Baseline é usado somente como bytes privados; nenhum Interpreter é executado.
        origem = self.repo/"experimentos-privados/app-baseline-v1"
        p.verificar(origem)
        with tempfile.TemporaryDirectory() as tmp:
            pacote = Path(tmp)/"pacote"
            pacote.mkdir()
            for nome in p.ARQUIVOS:
                (pacote/nome).write_bytes((origem/nome).read_bytes())
            flag = f"-PlibrasLivre.classificadorPrivado={pacote}"
            tarefa = ":app:prepararClassificadorPrivado"
            gerado = self.repo/"mobile-app-companion/app/build/generated/classificadorPrivado/assets"
            try:
                self.gradle(tarefa, flag)
                for nome in p.ARQUIVOS:
                    self.assertEqual((gerado/nome).read_bytes(), (pacote/nome).read_bytes())
                self.gradle(tarefa)
                self.assertEqual(list(gerado.iterdir()), [])
                self.gradle(tarefa, flag)
                texto = self.gradle(":app:assembleRelease", "--dry-run", flag, sucesso=False)
                self.assertIn("release proibido", texto)
                modelo = pacote/p.MODELO
                modelo.write_bytes(modelo.read_bytes()+b"adulterado")
                self.assertIn("inválido", self.gradle(tarefa, flag, sucesso=False))
                modelo.unlink()
                self.assertIn("exatamente", self.gradle(tarefa, flag, sucesso=False))
                self.assertIn("absoluto", self.gradle(tarefa,
                    "-PlibrasLivre.classificadorPrivado=relativo", sucesso=False))
            finally:
                self.gradle(tarefa)


if __name__ == "__main__": unittest.main()
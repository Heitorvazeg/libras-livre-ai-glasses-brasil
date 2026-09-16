import json
from pathlib import Path
import tempfile
import unittest

import pacote_classificador_privado as p


class TestPacote(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.raiz = Path(tmp.name)
        self.modelo = self.raiz / "export"
        self.modelo.write_bytes(b"\0\0\0\0TFL3fixture")
        self.sidecar = self.raiz / "export.json"
        self.d = dict(schema=1, modo="landmarks", sha256=p.sha256(self.modelo.read_bytes()),
            origem={"sha256": "a" * 64}, rotulos=[f"sinal{i}" for i in range(20)],
            contrato_entrada=dict(shape=[1,96,57,3], dtype="float32", frames_fixos=96,
                layout_landmarks={"pose_ordenada": [{"indice_mediapipe_pose": i} for i in p.POSE]}))
        self.gravar()
        self.saida = self.raiz / "pacote"

    def gravar(self):
        self.sidecar.write_text(json.dumps(self.d))

    def preparar(self):
        return p.preparar(self.modelo, self.sidecar, self.saida, experimento="baseline-v1",
            modelo_sha256=p.sha256(self.modelo.read_bytes()), checkpoint_sha256="a" * 64)

    def test_bytes_preservados_e_somente_allowlist(self):
        m, s = self.modelo.read_bytes(), self.sidecar.read_bytes()
        identidade = self.preparar()
        self.assertEqual(p.verificar(self.saida), identidade)
        self.assertEqual({f.name for f in self.saida.iterdir()}, p.ARQUIVOS)
        self.assertEqual((self.saida/p.MODELO).read_bytes(), m)
        self.assertEqual((self.saida/p.SIDECAR).read_bytes(), s)
        self.assertEqual(self.modelo.read_bytes(), m)
        self.assertEqual(self.sidecar.read_bytes(), s)
        with self.assertRaisesRegex(ValueError, "existe"):
            self.preparar()

    def test_hash_origem_contrato_e_calibracao(self):
        for chave, valor in (("origem", {"sha256":"b"*64}), ("sha256", "c"*64),
                ("rotulos", ["repetido"]*20), ("calibracao", None)):
            anterior = self.d.copy()
            self.d[chave] = valor
            self.gravar()
            with self.subTest(chave=chave), self.assertRaises(ValueError):
                self.preparar()
            self.assertFalse(self.saida.exists())
            self.d = anterior

    def test_adulteracao_ausencia_e_extra(self):
        self.preparar()
        m = self.saida/p.MODELO
        original = m.read_bytes()
        m.write_bytes(original+b"x")
        with self.assertRaises(ValueError): p.verificar(self.saida)
        m.write_bytes(original)
        extra = self.saida/"checkpoint.pt"
        extra.write_bytes(b"nao incluir")
        with self.assertRaises(ValueError): p.verificar(self.saida)
        extra.unlink()
        m.unlink()
        with self.assertRaises(ValueError): p.verificar(self.saida)

    def test_caminhos_e_links(self):
        for caminho in (Path("relativo"), p.REPO/"mobile-app-companion/app/src/main/assets/privado"):
            with self.assertRaises(ValueError): p.caminho_privado(caminho)
        self.preparar()
        (self.raiz/"link").symlink_to(self.saida, target_is_directory=True)
        with self.assertRaises(ValueError): p.verificar(self.raiz/"link")

    def test_json_ambiguo(self):
        for bruto in (b'{"a":1,"a":2}', b'{"a":NaN}', b'[]'):
            with self.assertRaises(ValueError): p.ler_json(bruto)


if __name__ == "__main__": unittest.main()
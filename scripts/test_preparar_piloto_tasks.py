import tempfile
from pathlib import Path
import unittest

from preparar_piloto_tasks import inventariar


class TestPiloto(unittest.TestCase):
    def test_inventario_nao_altera_dados_e_nao_declara_avaliacao(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            lm = base / "pessoaM01_sinal-filho_rep01.npy"
            lm.write_bytes(b"landmarks de teste")
            video = lm.with_suffix(".mp4")
            video.write_bytes(b"video de teste")
            modelo = base / "pose.task"
            modelo.write_bytes(b"modelo de teste")
            antes = {p: p.read_bytes() for p in base.iterdir()}
            p = inventariar(base, base, "M01", {"pose": modelo, "hand": base / "ausente.task"})
            self.assertEqual(p["total_pares_com_video"], 1)
            self.assertFalse(p["avaliacao_executada"])
            self.assertFalse(p["extracao_executada"])
            self.assertIn("modelo Tasks ausente: hand", p["bloqueios"])
            self.assertEqual(antes, {p: p.read_bytes() for p in base.iterdir()})

    def test_ambiguidade_de_video_nao_escolhe_arbitrariamente(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            nome = "pessoaM01_sinal-filho_rep01"
            for ext in (".npy", ".mp4", ".avi"):
                (base / (nome + ext)).touch()
            p = inventariar(base, base, "M01", {})
            self.assertEqual(p["total_pares_com_video"], 0)
            self.assertTrue(any("encontrei 2" in b for b in p["bloqueios"]))

    def test_pessoa_invalida_e_ausencia(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            with self.assertRaises(ValueError):
                inventariar(base, base, "../*", {})
            p = inventariar(base, base, "M01", {})
            self.assertEqual(p["total_landmarks"], 0)
            self.assertTrue(p["bloqueios"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
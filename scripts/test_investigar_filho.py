import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

from investigar_filho import RAIZ, inventario, lacunas, main, medir, video_e_contato


class TestInvestigacaoFilho(unittest.TestCase):
    def test_lacunas_separa_bordas_internas_e_ausencia_total(self):
        g = lacunas([True, True, False, True, True, True, False, True])
        self.assertEqual((g["inicio"], g["fim"], g["internos"], g["maior_interna"]), (2, 1, 3, 3))
        self.assertIsNone(lacunas([True] * 4)["internos"])
        self.assertEqual(lacunas([False] * 4)["maior"], 0)
        self.assertEqual(lacunas([False, True, False, True, True, False])["maior_interna"], 2)

    def test_imputacao_nao_altera_original_e_preserva_lacuna_longa(self):
        a = np.ones((16, 57, 3), dtype=np.float32)
        a[:, 15:36] = 0
        a[0:2, 36:57] = 0
        a[3:5, 36:57] = 0
        a[6:12, 36:57] = 0
        orig = a.copy()
        r = medir(a)
        self.assertTrue(np.array_equal(orig, a))
        m = r["maos"]["direita"]
        self.assertEqual(m["preenchidos"], 2)
        self.assertEqual(m["bruta"]["internos"], 8)
        self.assertEqual(m["apos_imputacao_leitura"]["ausentes"], 8)
        self.assertEqual(r["maos"]["esquerda"]["preenchidos"], 0)

    def test_movimento_exclui_saltos_para_zeros(self):
        a = np.ones((4, 57, 3), dtype=np.float32)
        a[1, 36:57] = 0
        r = medir(a)["maos"]["direita"]
        self.assertEqual(r["pares_presentes"], 1)
        self.assertEqual(r["passo_xy_pares_presentes_q05_q50_q95"], [0., 0., 0.])

    def test_rejeita_dados_invalidos(self):
        for a in (np.zeros((0, 57, 3), np.float32), np.zeros((2, 49, 3), np.float32),
                  np.ones((2, 57, 3), np.float64), np.full((2, 57, 3), np.nan, np.float32)):
            with self.assertRaises(ValueError):
                medir(a)

    def test_inventario_recusa_par_ausente(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError, "par ausente"):
                inventario(Path(d), Path(d))

    def test_destino_publico_ou_existente_recusado_antes_leitura(self):
        for destino in (RAIZ / "experimentos-privados", RAIZ / "scripts/saida-nao-criar"):
            with patch("sys.argv", ["investigar_filho", "--saida", str(destino)]), \
                 patch("investigar_filho.inventario") as inv:
                with self.assertRaises(SystemExit) as erro:
                    main()
                self.assertEqual(erro.exception.code, 2)
                inv.assert_not_called()

    def test_video_sintetico_contado_e_contato_criado(self):
        import cv2
        with tempfile.TemporaryDirectory() as d:
            video, contato = Path(d) / "teste.avi", Path(d) / "contato.jpg"
            writer = cv2.VideoWriter(str(video), cv2.VideoWriter_fourcc(*"MJPG"), 25., (64, 48))
            self.assertTrue(writer.isOpened())
            try:
                for i in range(10):
                    writer.write(np.full((48, 64, 3), i * 20, dtype=np.uint8))
            finally:
                writer.release()
            r = video_e_contato(video, contato)
            self.assertEqual(r["frames_decodificados"], 10)
            self.assertEqual(r["dimensoes"], [64, 48])
            self.assertEqual(r["indices_contato_video"], [0, 2, 4, 5, 7, 9])
            self.assertEqual(cv2.imread(str(contato)).shape[1], 6 * 240)


if __name__ == "__main__":
    unittest.main()
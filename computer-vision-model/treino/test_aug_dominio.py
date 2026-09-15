"""Augmentação de domínio (`--aug-dominio`): geometria preservada e treino antigo intacto."""
import unittest

import numpy as np

import dados as dd
import representacao as rp
from treinar import DatasetSinais


def sequencia(t=45, parado_ini=10, parado_fim=15, mao_esq=False):
    seq = np.zeros((t, 57, 3), dtype=np.float32)
    seq[:, 7, :2] = (0.5, 0.0)
    seq[:, 8, :2] = (-0.5, 0.0)
    seq[:, 0, :2] = (0.0, -0.6)
    fase = np.clip(np.arange(t) - parado_ini, 0, t - parado_ini - parado_fim)
    ang = fase * 0.4
    seq[:, 12, 0] = -0.4 + 0.3 * np.cos(ang)
    seq[:, 12, 1] = 0.6 + 0.3 * np.sin(ang)
    seq[:, 10, :2] = seq[:, 12, :2] * 0.5 + np.array([-0.5, 0.0]) * 0.5
    seq[:, 11, :2] = (0.5, 1.4)
    seq[:, 9, :2] = (0.5, 0.7)
    forma = np.linspace(0.0, 0.2, 21, dtype=np.float32)[:, None] * np.array([1.0, -1.0], np.float32)
    seq[:, 36:57, :2] = seq[:, 12, None, :2] + forma
    seq[:, 36:57, 2] = np.linspace(-0.1, 0.1, 21)
    if mao_esq:
        seq[:, 15:36, :2] = seq[:, 11, None, :2] + forma
    return seq


class TestEscalarAmplitude(unittest.TestCase):
    def test_k_um_nao_muda(self):
        s = sequencia()
        np.testing.assert_allclose(rp.escalar_amplitude(s, 1.0), s)

    def test_trajetoria_escala_e_forma_da_mao_preservada(self):
        s = sequencia()
        f = rp.escalar_amplitude(s, 0.5)
        dev_antes = s[:, 12, :2] - s[:, 12, :2].mean(0)
        dev_depois = f[:, 12, :2] - f[:, 12, :2].mean(0)
        np.testing.assert_allclose(dev_depois, 0.5 * dev_antes, atol=1e-6)
        np.testing.assert_allclose(f[:, 36:57, :2] - f[:, 12, None, :2],
                                   s[:, 36:57, :2] - s[:, 12, None, :2], atol=1e-6)
        np.testing.assert_allclose(f[:, 10, :2] - s[:, 10, :2],
                                   0.5 * (f[:, 12, :2] - s[:, 12, :2]), atol=1e-6)
        np.testing.assert_array_equal(f[:, 36:57, 2], s[:, 36:57, 2])

    def test_mao_ausente_continua_zero_e_corpo_intocado(self):
        s = sequencia()
        f = rp.escalar_amplitude(s, 0.7)
        self.assertTrue((f[:, 15:36] == 0).all())
        np.testing.assert_array_equal(f[:, [0, 7, 8]], s[:, [0, 7, 8]])

    def test_nao_altera_entrada(self):
        s = sequencia()
        copia = s.copy()
        rp.escalar_amplitude(s, 0.6)
        np.testing.assert_array_equal(s, copia)


class TestRepouso(unittest.TestCase):
    def test_detecta_parados(self):
        ini, fim = rp.frames_parados(sequencia(t=60, parado_ini=12, parado_fim=18))
        self.assertAlmostEqual(ini, 12, delta=2)
        self.assertAlmostEqual(fim, 18, delta=2)

    def test_parado_inteiro(self):
        s = np.zeros((10, 57, 3), dtype=np.float32)
        self.assertEqual(rp.frames_parados(s), (0, 0))


class TestAumentarDominio(unittest.TestCase):
    def test_deterministico_e_minimo_de_frames(self):
        s = sequencia()
        for semente in range(50):
            a = rp.aumentar_dominio(s, np.random.default_rng(semente))
            b = rp.aumentar_dominio(s, np.random.default_rng(semente))
            np.testing.assert_array_equal(a, b)
            self.assertGreaterEqual(len(a), 3)
            self.assertTrue((a[:, 15:36] == 0).all())

    def test_recusa_layout_errado(self):
        with self.assertRaises(ValueError):
            rp.aumentar_dominio(np.zeros((10, 49, 3), np.float32), np.random.default_rng(0))


class TestDatasetSemFlag(unittest.TestCase):
    def clipes(self):
        return [dd.Clipe("M01", "filho", "01", sequencia()),
                dd.Clipe("M02", "medo", "01", sequencia(mao_esq=True))]

    def test_flag_desligada_igual_ao_caminho_antigo(self):
        rot = ["filho", "medo"]
        ds = DatasetSinais(self.clipes(), rot, None, True, semente=7, arquitetura="gcn", ossos=True)
        import gcn as gg
        import torch
        for i, c in enumerate(self.clipes()):
            rng = np.random.default_rng([7, 0, 0, i])
            esperado = torch.from_numpy(gg.para_sequencia(gg.com_ossos(rp.aumentar(c.seq, rng, None), gg.pais())))
            x, _ = ds[i]
            self.assertTrue(torch.equal(x, esperado))

    def test_flag_ligada_muda_so_com_aumentar(self):
        rot = ["filho", "medo"]
        sem_aug = DatasetSinais(self.clipes(), rot, None, False, arquitetura="gcn", ossos=True, aug_dominio=True)
        base = DatasetSinais(self.clipes(), rot, None, False, arquitetura="gcn", ossos=True)
        for i in range(2):
            self.assertTrue(np.array_equal(sem_aug[i][0].numpy(), base[i][0].numpy()))


if __name__ == "__main__":
    unittest.main()

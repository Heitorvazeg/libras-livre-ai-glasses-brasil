"""Guardas da comparação float32; integração real exige LiteRT e dados privados."""
import unittest

import numpy as np

from avaliar_tflite_piloto import contrato, paridade, TOL


class ContratoFake:
    def __init__(self, shape=(1, 96, 57, 3), dtype=np.float32):
        self.shape, self.dtype = shape, dtype

    def get_input_details(self):
        return [{"shape": self.shape, "dtype": self.dtype, "index": 0}]

    def get_output_details(self):
        return [{"shape": (1, 20), "dtype": np.float32, "index": 1}]


class TestParidadeTflite(unittest.TestCase):
    def test_identidade_e_ruido_float(self):
        a = np.array([[1., 3.], [2., 0.]])
        self.assertTrue(paridade(a, a + TOL / 2)["aprovada"])
        self.assertEqual(paridade(a, a)["max_dif_logit"], 0)

    def test_delta_e_top1_sao_guardas_independentes(self):
        self.assertFalse(paridade([[1., 3.]], [[1., 3. + TOL * 2]])["aprovada"])
        r = paridade([[0., TOL / 4]], [[TOL / 4, 0.]])
        self.assertLess(r["max_dif_logit"], TOL)
        self.assertEqual(r["discordancias_top1"], 1)
        self.assertFalse(r["aprovada"])

    def test_rejeita_formas_e_valores_invalidos(self):
        for a, b in (([], []), ([1., 2.], [1., 2.]), ([[1., 2.]], [[1.]]),
                     ([[np.nan, 1.]], [[1., 2.]]), ([[1., 2.]], [[np.inf, 2.]])):
            with self.subTest(a=a), self.assertRaises(ValueError):
                paridade(a, b)

    def test_contrato_float32_exato(self):
        self.assertEqual(contrato(ContratoFake(), 20), (0, 1))
        for runtime in (ContratoFake(shape=(1, 64, 57, 3)), ContratoFake(dtype=np.float16)):
            with self.assertRaises(ValueError):
                contrato(runtime, 20)
        with self.assertRaises(ValueError):
            contrato(ContratoFake(), 19)


if __name__ == "__main__":
    unittest.main()
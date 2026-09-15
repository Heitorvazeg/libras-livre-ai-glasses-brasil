"""Regressões dos achados de 3e81c3f; dados sintéticos, sem treino real."""
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

import calibracao as cb
from test_calibracao import escrever_bundle


class TestGuardasNumericas(unittest.TestCase):
    def test_sem_limiar_nao_finge_rejeicao_com_um(self):
        probs = cb.probabilidades(np.array([[1000., -1000.]]))
        r = cb.escolher_limiar(probs, np.array([1]))
        self.assertTrue(r["meta_nao_atingida"])
        self.assertIsNone(r["limiar_sugerido"])

    def test_temperatura_invalida_recusada(self):
        for t in (-1., 0., float("nan"), float("inf"), True, 1e-100, 1e100):
            with self.subTest(t=t), self.assertRaises(ValueError):
                cb.probabilidades(np.array([[1., 0.]]), t)

    def test_ajuste_recusa_alvos_nao_inteiros_e_dados_invalidos(self):
        casos = [(np.ones((1, 2)), np.array([.9])),
                 (np.ones((1, 2)), np.array([True])),
                 (np.ones((0, 2)), np.array([], dtype=int)),
                 (np.ones((1, 1)), np.array([0])),
                 (np.array([[np.nan, 0.]]), np.array([0])),
                 (np.array([[np.inf, 0.]]), np.array([0]))]
        for z, y in casos:
            with self.subTest(shape=z.shape, alvos=y), self.assertRaises(ValueError):
                cb.ajustar_temperatura(z, y)

    def test_probabilidades_e_bins_invalidos_recusados(self):
        for p in (np.array([[np.nan, 0.]]), np.array([[2., -1.]]),
                  np.array([[.2, .2]]), np.empty((0, 2))):
            with self.subTest(p=p), self.assertRaises(ValueError):
                cb.escolher_limiar(p, np.zeros(len(p), dtype=int))
        with self.assertRaises(ValueError):
            cb.ece(np.array([[.5, .5]]), np.array([0]), n_bins=0)

    def test_probabilidades_estaveis_com_logits_grandes(self):
        p = cb.probabilidades(np.array([[1e308, -1e308], [-1e308, 1e308]]), .5)
        self.assertTrue(np.isfinite(p).all())
        np.testing.assert_array_equal(p.argmax(1), [0, 1])

    def test_json_nao_finito_recusado(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "invalido.json"
            for texto in ('{"temperatura": NaN}', '{"temperatura": Infinity}',
                          '{"temperatura": 1e400}'):
                path.write_text(texto)
                with self.assertRaises(ValueError):
                    cb.ler_json(path)


class TestIdentidade(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.z = np.array([[4., 0.], [0., 4.], [1., 0.], [0., 1.]])
        self.y = np.array([0, 1, 1, 0])
        self.p = escrever_bundle(self.base, "f1.json", ["a", "b"], self.z, self.y)

    def test_duplicacao_mesmo_caminho_e_copia_recusadas(self):
        with self.assertRaisesRegex(ValueError, "duplicad"):
            cb.carregar_pool([self.p, self.p])
        import shutil
        shutil.copytree(self.base / "rodadas", self.base / "copia/rodadas")
        with self.assertRaisesRegex(ValueError, "duplicad"):
            cb.carregar_pool([self.p, self.base / "copia/rodadas/artefatos/f1.evidencias.json"])

    def test_checkpoint_ou_evidencia_adulterados_recusados(self):
        ckpt = self.p.with_name("f1.pt")
        original = ckpt.read_bytes()
        ckpt.write_bytes(b"alterado")
        with self.assertRaisesRegex(ValueError, "hash"):
            cb.carregar_pool([self.p])
        ckpt.write_bytes(original)
        d = json.loads(self.p.read_text()); d["validacao"]["logits"][0][0] += .5
        self.p.write_text(json.dumps(d))
        with self.assertRaisesRegex(ValueError, "hash"):
            cb.carregar_pool([self.p])

    def test_alvo_fracionario_e_particao_sobreposta_recusados(self):
        original = self.p.read_text()
        for campo in ("alvo", "particao"):
            d = json.loads(original)
            if campo == "alvo":
                d["validacao"]["verdadeiros"][0] = .9
            else:
                d["particao"]["pessoas"]["treino"] = d["particao"]["pessoas"]["validacao"]
                d["particao"]["ids"]["treino"] = d["particao"]["ids"]["validacao"]
            self.p.write_text(json.dumps(d))
            with self.subTest(campo=campo), self.assertRaises(ValueError):
                cb.carregar_pool([self.p])

    def test_pool_multi_checkpoint_nao_exporta_e_teste_nao_ajusta(self):
        z, y, _ = cb.carregar_pool([self.p])
        np.testing.assert_array_equal(z, self.z)
        np.testing.assert_array_equal(y, self.y)
        p2 = escrever_bundle(self.base, "f2.json", ["a", "b"], self.z, self.y)
        b = cb.calibrar([self.p, p2])
        self.assertEqual(b["escopo"], "pool_loso_analise")
        self.assertIsNone(b["checkpoint_sha256"])
        with self.assertRaisesRegex(ValueError, "pool"):
            cb.validar_exportacao(b, "a" * 64, ["a", "b"])

    def test_modelo_rotulos_politica_e_fontes_precisam_conferir(self):
        b = cb.calibrar([self.p]); h = b["checkpoint_sha256"]
        cb.validar_exportacao(b, h, ["a", "b"])
        with self.assertRaisesRegex(ValueError, "checkpoint"):
            cb.validar_exportacao(b, "outro", ["a", "b"])
        with self.assertRaisesRegex(ValueError, "rótulos"):
            cb.validar_exportacao(b, h, ["b", "a"])
        for alteracao in ({"temperatura": float("nan")}, {"limiar_sugerido": 0.},
                          {"meta_nao_atingida": True, "limiar_sugerido": None},
                          {"schema": 1}, {"aprovado_entrega": True}):
            with self.subTest(alteracao=alteracao), self.assertRaises(ValueError):
                cb.validar_exportacao(b | alteracao, h, ["a", "b"])
        self.p.unlink()
        with self.assertRaises(ValueError):
            cb.validar_exportacao(b, h, ["a", "b"])

    def test_sem_meta_cli_grava_null_e_json_estrito(self):
        from unittest.mock import patch
        z = np.array([[0., 0.], [0., 0.]])
        p = escrever_bundle(self.base, "f3.json", ["a", "b"], z, np.array([1, 1]))
        saida = self.base / "cal.json"
        with patch("sys.argv", ["calibracao", "--evidencias", str(p), "--saida", str(saida)]):
            cb.main()
        b = cb.ler_json(saida)
        self.assertIsNone(b["limiar_sugerido"])
        self.assertTrue(b["meta_nao_atingida"])
        with self.assertRaisesRegex(ValueError, "sem limiar"):
            cb.validar_exportacao(b, b["checkpoint_sha256"], ["a", "b"])


if __name__ == "__main__":
    unittest.main()
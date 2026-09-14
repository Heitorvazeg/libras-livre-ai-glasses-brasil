from pathlib import Path
import tempfile
from types import SimpleNamespace as NS
import unittest

import numpy as np

import extrair_piloto_tasks as p


def pose(largura=.4):
    pts = [NS(x=.5, y=.5, z=0., visibility=1.) for _ in range(33)]
    pts[11].x, pts[12].x = .5 - largura / 2, .5 + largura / 2
    pts[15].x, pts[16].x = .3, .7
    return pts


def mao(x, y=.5):
    return [NS(x=x, y=y, z=0.) for _ in range(21)]


class TestExtracao(unittest.TestCase):
    def test_pose_mais_larga_e_empate_estavel(self):
        menor, maior = pose(.1), pose(.5)
        self.assertIs(p.escolher_pose([menor, maior], 800, 400), maior)
        self.assertIs(p.escolher_pose([maior, pose(.5)], 800, 400), maior)
        self.assertIsNone(p.escolher_pose([[]], 800, 400))

    def test_maos_por_pulsos_nao_ordem_handedness(self):
        lados, aceitas = p.selecionar_maos(pose(), [mao(.7), mao(.3), mao(.99)], 800, 400)
        self.assertEqual(lados, [1, 0])
        self.assertEqual(aceitas, [0, 1])

    def test_guloso_disputa_e_limite_estrito(self):
        lados, _ = p.selecionar_maos(pose(), [mao(.3), mao(.31)], 800, 400)
        self.assertEqual(lados, [0, 1])  # segunda vai para pulso livre, como app
        pp = pose(.5)
        pp[15].x, pp[16].x = 0., 1.
        lados, _ = p.selecionar_maos(pp, [mao(.25)], 100, 100)
        self.assertEqual(lados, [None, None])

    def test_distancias_em_pixels_nao_coordenadas_normalizadas(self):
        pp = pose()
        pp[15].x, pp[15].y = .3, .8
        pp[16].x, pp[16].y = .4, .5
        lados, _ = p.selecionar_maos(pp, [mao(.3, .5)], 1000, 100)
        self.assertEqual(lados, [0, None])

    def test_tempo_irregular_preservado_e_colisoes_recusadas(self):
        pts, ts = p.conferir_timestamps(['10', '10.033', '10.100'])
        np.testing.assert_array_equal(ts, [0, 33, 100])
        self.assertEqual(pts[0], 10)
        for valores in ([], [None], [0, 0], [1, 0], [0, .0001], [0, float('nan')]):
            with self.subTest(valores=valores), self.assertRaises(ValueError):
                p.conferir_timestamps(valores)

    def test_normalizacao_e_mascara_nao_comparam_ausencia(self):
        cfg = p.load_config()
        vec = p.frame_normalizado(p.resultado(pose(), mao(.3), None), 800, 400, cfg, dims=3)
        self.assertEqual(vec.shape, (57, 3))
        np.testing.assert_array_equal(vec[36:], 0)
        pp = pose(); pp[11].visibility = .1
        self.assertIsNone(p.frame_normalizado(p.resultado(pp), 800, 400, cfg, dims=3))
        dados = {"ts_ms": np.array([0, 33]), "pts_s": np.array([0, .033]),
                 "tasks": np.ones((2, 57, 3)), "holistic": np.zeros((2, 57, 3)),
                 "tasks_valido": np.array([True, False]), "holistic_valido": np.ones(2, dtype=bool),
                 "tasks_maos": np.ones((2, 2), dtype=bool), "holistic_maos": np.zeros((2, 2), dtype=bool)}
        st = p.estatisticas(dados)
        self.assertEqual(st['frames_pose_comum'], 1)
        self.assertIsNone(st['diferencas_em_ombros']['mao_esq']['xy_mediana'])
        self.assertAlmostEqual(st['diferencas_em_ombros']['pose']['xy_mediana'], 2**.5)

    def test_destino_existente_e_publico_recusados(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                p.destino_privado(Path(d))
            self.assertEqual(p.destino_privado(Path(d)/'novo'), Path(d)/'novo')
        with self.assertRaises(ValueError):
            p.destino_privado(p.RAIZ/'scripts'/'nao-gravar')

    def test_inventario_hashes_pessoa_e_ids(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            modelo = base/'modelo.task'
            modelo.write_bytes(b'modelo de teste')
            video = base/'pessoaM01_sinal-acontecer_rep01.mp4'
            lm = video.with_suffix('.npy')
            video.write_bytes(b'video de teste'); lm.write_bytes(b'landmarks de teste')
            par = {'id': lm.name, 'video': str(video), 'landmark': str(lm),
                   'video_sha256': p.hash_arquivo(video), 'landmark_sha256': p.hash_arquivo(lm)}
            inv = {'schema': 1, 'pessoa_teste': 'M01', 'pares': [par],
                   'modelos': {k: {'arquivo': str(modelo), 'sha256': p.hash_arquivo(modelo)} for k in p.URLS}}
            _, pares = p.validar_inventario(inv)
            self.assertEqual(pares, [par])
            with self.assertRaisesRegex(ValueError, 'duplicados'):
                p.validar_inventario(inv | {'pares': [par, par]})
            with self.assertRaisesRegex(ValueError, 'ID inválido'):
                p.validar_inventario(inv | {'pessoa_teste': 'M02'})
            video.write_bytes(b'alterado')
            with self.assertRaisesRegex(ValueError, 'video difere'):
                p.validar_inventario(inv)
            modelo.write_bytes(b'alterado')
            with self.assertRaisesRegex(ValueError, 'modelo ausente/alterado'):
                p.validar_inventario(inv)


if __name__ == '__main__':
    unittest.main(verbosity=2)
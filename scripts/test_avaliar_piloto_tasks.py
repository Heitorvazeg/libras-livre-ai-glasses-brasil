import contextlib
import io
import json
from pathlib import Path
import unittest

import numpy as np

import avaliar_piloto_tasks as p


def arrays(n=4):
    d = {'ts_ms': np.arange(n) * 40, 'pts_s': np.arange(n) * .04, 'frame_index': np.arange(n)}
    for m in ('tasks', 'holistic'):
        d[m] = np.ones((n, 57, 3), dtype=np.float32)
        d[m + '_valido'] = np.ones(n, dtype=bool)
        d[m + '_maos'] = np.ones((n, 2), dtype=bool)
    return d


class TestAvaliacao(unittest.TestCase):
    def test_reproducao_exige_logits_e_top1(self):
        a = np.array([[2., 1.], [.5, 2.]])
        self.assertEqual(p.conferir_reproducao(a, a)['max_dif_logit'], 0)
        for b in (a + .01, a[:, :1], np.full_like(a, np.nan)):
            with self.assertRaises(ValueError):
                p.conferir_reproducao(a, b)
        with self.assertRaises(ValueError):
            p.conferir_reproducao([[0., 1e-6]], [[1e-6, 0.]])

    def test_particao_exige_teste_isolado(self):
        part = {'metodo': 'loso', 'pessoas': {'treino': ['M03'], 'validacao': ['M02'], 'teste': ['M01']},
                'ids': {g: [f'pessoa{v}_sinal-oi_rep01.npy'] for g, v in
                        [('treino', 'M03'), ('validacao', 'M02'), ('teste', 'M01')]}}
        p.conferir_particao(part, 'M01')
        with self.assertRaises(ValueError):
            p.conferir_particao(part, 'M02')
        part['pessoas']['treino'] = ['M01']
        with self.assertRaises(ValueError):
            p.conferir_particao(part, 'M01')

    def test_alinhamento_por_id_nao_ordem_ou_subconjunto(self):
        x = {'clipes': [{'id': 'b'}, {'id': 'a'}], 'falhas': [], 'extracao_completa': True}
        self.assertEqual([c['id'] for c in p.alinhar_clipes(x, ['a', 'b'])], ['a', 'b'])
        for ruim in (x | {'clipes': [{'id': 'a'}, {'id': 'a'}]}, x | {'extracao_completa': False},
                     x | {'falhas': ['falha']}, x | {'clipes': [{'id': 'a'}]}):
            with self.assertRaises(ValueError):
                p.alinhar_clipes(ruim, ['a', 'b'])

    def test_npz_mascaras_indices_tempo_e_denominador(self):
        p.conferir_npz(arrays(), 4)
        for chave, valor in [('tasks_valido', np.zeros(4, dtype=bool)),
                             ('frame_index', np.array([0, 1, 3, 4])),
                             ('ts_ms', np.array([0, 40, 80, 130])),
                             ('tasks_maos', np.zeros((4, 2), dtype=bool)),
                             ('tasks', np.full((4, 57, 3), np.nan))]:
            with self.subTest(chave=chave), self.assertRaises(ValueError):
                p.conferir_npz(arrays() | {chave: valor}, 4)

    def test_indice_equivale_tempo_uniforme_nao_irregular(self):
        seq = np.arange(4, dtype=np.float32)[:, None, None] * np.ones((4, 57, 3), dtype=np.float32)
        a = p.entrada_app(seq, np.arange(4), 9)
        np.testing.assert_allclose(a, p.entrada_app(seq, np.arange(4) * 40, 9), atol=1e-6)
        self.assertGreater(float(np.abs(a - p.entrada_app(seq, [0, 10, 100, 120], 9)).max()), .1)

    def test_contagens_pareadas_e_erros_por_sinal(self):
        y, ids, labels = [0, 0, 1, 1], ['a', 'b', 'c', 'd'], ['x', 'y']
        a = p.resumir(np.array([[2, 1], [2, 1], [2, 1], [1, 2]]), y, ids, labels)
        b = p.resumir(np.array([[2, 1], [1, 2], [1, 2], [1, 2]]), y, ids, labels)
        c = p.comparar(a, b, y, ids)
        self.assertEqual((c['ambos_corretos'], c['so_a_correto'], c['so_b_correto'], c['ambos_errados']), (2, 1, 1, 0))
        self.assertEqual(c['ids_alterados'], ['b', 'c'])
        self.assertEqual(a['por_sinal']['x'], {'acertos': 2, 'total': 2})

    def test_limiar_fixo_nao_altera_top1_ou_denominador(self):
        r = p.resumir(np.array([[0., .1], [2., 0.], [2., 0.]]), [1, 0, 1], ['a', 'b', 'c'], ['x', 'y'])
        self.assertEqual(r['acertos'], 2)
        self.assertEqual(r['total'], 3)
        d = r['diagnostico_limiar_fixo']
        self.assertFalse(d['calibrada'])
        self.assertEqual(d['limiar_fixo'], .6)
        self.assertEqual(d['aceitos'], 2)
        self.assertEqual(d['incorretos_aceitos'], 1)
        self.assertEqual(d['rejeitados'][0]['id'], 'a')
        self.assertTrue(d['rejeitados'][0]['correto_top1'])

    def test_integracao_checkpoint_e_npz_sinteticos_sem_sobrescrita(self):
        # Usa o fixture sintético já testado de um treino GCN real de uma época.
        from test_evidencias_loso import TestEvidenciasLoso
        fixture = TestEvidenciasLoso()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.executar()
        marcador, r, _, saidas = fixture.arquivos()
        e = json.loads(saidas.read_text())
        pasta = fixture.base / 'extracao'
        pasta.mkdir()
        x = {'pessoa': 'M01', 'extracao_completa': True, 'falhas': [], 'clipes': [],
             'config': e['contexto']['config']}
        for nome in e['teste']['ids']:
            lm = fixture.lm / nome
            seq = np.load(lm)
            d = arrays(len(seq))
            d['tasks'], d['holistic'] = seq, seq
            arq = pasta / Path(nome).with_suffix('.npz').name
            np.savez_compressed(arq, **d)
            x['clipes'].append({'id': nome, 'artefato': arq.name, 'frames': len(seq),
                                'sha256': p.hash_arquivo(arq), 'landmark_sha256': p.hash_arquivo(lm)})
        (pasta / 'extracao.json').write_text(json.dumps(x))
        out = fixture.base / 'comparacao'
        antes = {v: v.read_bytes() for v in [marcador, saidas, *pasta.iterdir()]}
        with contextlib.redirect_stdout(io.StringIO()):
            doc = p.executar(marcador, pasta, fixture.lm, out)
        self.assertTrue(doc['concluido'])
        self.assertEqual(len(doc['resultados']), 7)
        for m in ('holistic', 'tasks'):
            self.assertEqual(doc['resultados']['historico_treino']['logits'], doc['resultados'][m + '_treino']['logits'])
            c = doc['comparacoes'][m + '_app_indice96 -> ' + m + '_app_pts96']
            self.assertEqual(c['discordantes'], 0)
        with self.assertRaisesRegex(ValueError, 'saída já existe'):
            p.executar(marcador, pasta, fixture.lm, out)
        for v, conteudo in antes.items():
            self.assertEqual(v.read_bytes(), conteudo)
        arq.write_bytes(b'alterado')
        with self.assertRaisesRegex(ValueError, 'hash divergente'), contextlib.redirect_stdout(io.StringIO()):
            p.executar(marcador, pasta, fixture.lm, fixture.base / 'recusar')
        self.assertFalse((fixture.base / 'recusar').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
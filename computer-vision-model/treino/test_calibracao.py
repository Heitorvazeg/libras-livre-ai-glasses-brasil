"""Regressões de calibracao.py — sempre com casos que TÊM erro real.

O fold único que temos hoje (M01) acertou validação quase inteira, e um caso
100%-correto degenera a calibração (T->0, limiar aceita tudo) sem testar o
código que importa: o que acontece quando a rede erra. Por isso os cenários
aqui são sintéticos e controlados, com acerto e erro misturados.
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

import calibracao as cb


def escrever_bundle(tmp, nome, rotulos, logits, alvos, checkpoint_bytes=None):
    """Bundle completo sintético; pesos são bytes opacos salvo teste de export."""
    marcador = Path(tmp) / "rodadas" / nome
    ckpt, caminho = cb.ev.artefatos_fold(marcador)
    caminho.parent.mkdir(parents=True, exist_ok=True)
    pessoa = "V" + Path(nome).stem
    args = {"final": False, "epocas": 120}
    contexto = {"schema": 1, "args": args, "config": {"pose_indices": {"nariz": 0}},
                "dados_sha256": "a" * 64, "codigo_sha256": "b" * 64}
    ids = [f"pessoa{pessoa}_sinal-{rotulos[a]}_rep{i:03d}.npy" for i, a in enumerate(alvos)]
    teste_id = f"pessoaT{pessoa}_sinal-{rotulos[0]}_rep01.npy"
    part = {"metodo": "loso", "pessoas": {"treino": ["Tr"], "validacao": [pessoa], "teste": ["T" + pessoa]},
            "ids": {"treino": [f"pessoaTr_sinal-{rotulos[0]}_rep01.npy"],
                    "validacao": ids, "teste": [teste_id]}}
    teste_logits = [[1.] + [0.] * (len(rotulos) - 1)]
    d = {"schema": 1, "rotulos": rotulos, "particao": part, "contexto": contexto,
         "melhor_epoca": 1,
         "validacao": {"ids": ids, "logits": logits.tolist(), "predicoes": logits.argmax(1).tolist(),
                       "verdadeiros": alvos.tolist()},
         "teste": {"ids": [teste_id], "logits": teste_logits, "predicoes": [0], "verdadeiros": [0]}}
    cb.ev.escrever_json(caminho, d)
    ckpt.write_bytes(checkpoint_bytes if checkpoint_bytes is not None else ("sintetico " + nome).encode())
    reg = {"args": args, "rotulos": rotulos, "melhor_epoca": 1,
           "predicoes": [0], "verdadeiros": [0], "acuracia": 1.,
           "evidencias": {"schema": 1, "contexto_sha256": cb.ev.mm.pv.hash_json(contexto), "particao": part,
               "checkpoint": {"arquivo": ckpt.relative_to(marcador.parent).as_posix(), "sha256": cb.ev.mm.pv.hash_arquivo(ckpt)},
               "saidas": {"arquivo": caminho.relative_to(marcador.parent).as_posix(), "sha256": cb.ev.mm.pv.hash_arquivo(caminho)}}}
    cb.ev.escrever_json(marcador, reg)
    return caminho


def _logits_controlados(rng, n, c, confianca_certo, confianca_errado, taxa_erro):
    """Logits onde a classe certa recebe `confianca_certo`, exceto numa fração
    `taxa_erro` das amostras, onde outra classe recebe `confianca_errado` e a
    certa fica baixa — simula uma rede que erra com confiança variável."""
    alvos = rng.integers(0, c, size=n)
    logits = rng.normal(0, 0.3, size=(n, c))
    erra = rng.random(n) < taxa_erro
    for i in range(n):
        if erra[i]:
            outra = (alvos[i] + 1 + rng.integers(0, c - 1)) % c
            logits[i, outra] = confianca_errado
            logits[i, alvos[i]] = -confianca_errado * 0.3
        else:
            logits[i, alvos[i]] = confianca_certo
    return logits.astype(np.float64), alvos.astype(np.int64)


class TestTemperatura(unittest.TestCase):
    def test_rede_excessivamente_confiante_pede_temperatura_maior_que_1(self):
        rng = np.random.default_rng(0)
        # Confiança sempre alta (~logit 6), mas erra 15% das vezes: overconfident.
        logits, alvos = _logits_controlados(rng, 400, 10, 6.0, 6.0, 0.15)
        t = cb.ajustar_temperatura(logits, alvos)
        self.assertGreater(t, 1.0, f"esperava T>1 pra suavizar rede overconfident, veio {t}")

    def test_temperatura_nao_muda_a_predicao_so_a_confianca(self):
        rng = np.random.default_rng(1)
        logits, alvos = _logits_controlados(rng, 200, 8, 4.0, 3.0, 0.2)
        antes = logits.argmax(axis=1)
        t = cb.ajustar_temperatura(logits, alvos)
        depois = cb.probabilidades(logits, t).argmax(axis=1)
        self.assertTrue(np.array_equal(antes, depois), "escalar T não pode mudar argmax")

    def test_temperatura_reduz_a_log_verossimilhanca_negativa_por_construcao(self):
        # É literalmente o que ajustar_temperatura minimiza — garantido, ao
        # contrário de ECE (métrica discretizada por bins, objetivo diferente
        # de NLL; correlacionada mas não idêntica — não dá pra garantir queda
        # de ECE numa amostra só, só em expectativa).
        rng = np.random.default_rng(2)
        logits, alvos = _logits_controlados(rng, 500, 12, 5.0, 5.0, 0.2)
        z = torch.tensor(logits, dtype=torch.float64)
        y = torch.tensor(alvos, dtype=torch.int64)
        nll_antes = float(torch.nn.functional.cross_entropy(z, y))
        t = cb.ajustar_temperatura(logits, alvos)
        nll_depois = float(torch.nn.functional.cross_entropy(z / t, y))
        self.assertLessEqual(nll_depois, nll_antes + 1e-6)

    def test_temperatura_reduz_ece_em_media_sobre_varias_sementes(self):
        # Aqui sim testo a propriedade prática (por que calibrar vale a pena),
        # mas em expectativa sobre várias amostras, não numa observação só.
        deltas = []
        for semente in range(8):
            rng = np.random.default_rng(100 + semente)
            logits, alvos = _logits_controlados(rng, 500, 12, 5.0, 5.0, 0.2)
            antes = cb.ece(cb.probabilidades(logits, 1.0), alvos)
            t = cb.ajustar_temperatura(logits, alvos)
            depois = cb.ece(cb.probabilidades(logits, t), alvos)
            deltas.append(antes - depois)
        self.assertGreater(np.mean(deltas), 0.0)

    def test_formas_invalidas_rejeitadas(self):
        with self.assertRaises(ValueError):
            cb.ajustar_temperatura(np.zeros((5, 3)), np.zeros(4, dtype=int))
        with self.assertRaises(ValueError):
            cb.ajustar_temperatura(np.zeros((5, 3)), np.array([0, 1, 2, 3, 9]))


class TestLimiar(unittest.TestCase):
    def test_com_erros_reais_encontra_cobertura_parcial_nao_trivial(self):
        rng = np.random.default_rng(3)
        # Acertos com confiança alta, erros com confiança baixa: separação clara.
        logits, alvos = _logits_controlados(rng, 600, 10, 5.0, 1.0, 0.25)
        probs = cb.probabilidades(logits, 1.0)
        r = cb.escolher_limiar(probs, alvos, acc_minima=0.95)
        self.assertFalse(r["meta_nao_atingida"])
        self.assertGreaterEqual(r["acuracia_dos_aceitos"], 0.95)
        # Com 25% de erro geral e separação clara, cobertura deve ficar entre
        # "aceita só os perfeitos" e "aceita tudo" — nem 0 nem 100%.
        self.assertGreater(r["cobertura_esperada"], 0.0)
        self.assertLess(r["cobertura_esperada"], 1.0)

    def test_meta_muito_alta_para_dados_ruidosos_nao_e_atingida(self):
        rng = np.random.default_rng(4)
        logits, alvos = _logits_controlados(rng, 300, 6, 1.0, 1.0, 0.5)
        probs = cb.probabilidades(logits, 1.0)
        r = cb.escolher_limiar(probs, alvos, acc_minima=0.999)
        self.assertTrue(r["meta_nao_atingida"])
        self.assertIsNone(r["acuracia_dos_aceitos"])

    def test_acc_minima_fora_do_intervalo_falha(self):
        probs = np.eye(3)[[0, 1, 2]]
        with self.assertRaises(ValueError):
            cb.escolher_limiar(probs, np.array([0, 1, 2]), acc_minima=0.0)
        with self.assertRaises(ValueError):
            cb.escolher_limiar(probs, np.array([0, 1, 2]), acc_minima=1.5)


class TestPoolECli(unittest.TestCase):
    def _escrever_evidencias(self, tmp, nome, rotulos, logits, alvos):
        return escrever_bundle(tmp, nome, rotulos, logits, alvos)

    def test_pool_concatena_dois_folds(self):
        rng = np.random.default_rng(5)
        rotulos = [f"c{i}" for i in range(5)]
        l1, a1 = _logits_controlados(rng, 50, 5, 4.0, 2.0, 0.2)
        l2, a2 = _logits_controlados(rng, 60, 5, 4.0, 2.0, 0.2)
        with tempfile.TemporaryDirectory() as tmp:
            p1 = self._escrever_evidencias(tmp, "f1.json", rotulos, l1, a1)
            p2 = self._escrever_evidencias(tmp, "f2.json", rotulos, l2, a2)
            logits, alvos, rot = cb.carregar_pool([p1, p2])
        self.assertEqual(logits.shape, (110, 5))
        self.assertEqual(len(alvos), 110)
        self.assertEqual(rot, rotulos)

    def test_pool_recusa_rotulos_divergentes(self):
        rng = np.random.default_rng(6)
        l1, a1 = _logits_controlados(rng, 20, 4, 3.0, 1.0, 0.2)
        l2, a2 = _logits_controlados(rng, 20, 4, 3.0, 1.0, 0.2)
        with tempfile.TemporaryDirectory() as tmp:
            p1 = self._escrever_evidencias(tmp, "f1.json", ["a", "b", "c", "d"], l1, a1)
            p2 = self._escrever_evidencias(tmp, "f2.json", ["a", "b", "c", "e"], l2, a2)
            with self.assertRaisesRegex(ValueError, "rótulos divergem"):
                cb.carregar_pool([p1, p2])

    def test_pool_vazio_falha(self):
        with self.assertRaises(ValueError):
            cb.carregar_pool([])

    def test_calibrar_ponta_a_ponta_com_pool_real(self):
        rng = np.random.default_rng(7)
        rotulos = [f"c{i}" for i in range(8)]
        l1, a1 = _logits_controlados(rng, 100, 8, 5.0, 2.0, 0.2)
        l2, a2 = _logits_controlados(rng, 100, 8, 5.0, 2.0, 0.2)
        with tempfile.TemporaryDirectory() as tmp:
            p1 = self._escrever_evidencias(tmp, "f1.json", rotulos, l1, a1)
            p2 = self._escrever_evidencias(tmp, "f2.json", rotulos, l2, a2)
            bloco = cb.calibrar([p1, p2], acc_minima=0.9)
        self.assertEqual(bloco["n_amostras_validacao"], 200)
        self.assertEqual(bloco["n_folds_validacao"], 2)
        self.assertIn("temperatura", bloco)
        self.assertFalse(bloco["meta_nao_atingida"])

    def test_cli_recusa_sobrescrever_saida_existente(self):
        rng = np.random.default_rng(8)
        rotulos = ["a", "b", "c"]
        l1, a1 = _logits_controlados(rng, 30, 3, 3.0, 1.0, 0.2)
        with tempfile.TemporaryDirectory() as tmp:
            p1 = self._escrever_evidencias(tmp, "f1.json", rotulos, l1, a1)
            saida = Path(tmp) / "ja-existe.json"
            saida.write_text("{}")
            import subprocess
            import sys
            r = subprocess.run([sys.executable, str(Path(__file__).parent / "calibracao.py"),
                               "--evidencias", str(p1), "--saida", str(saida)],
                              capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("já existe", r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()

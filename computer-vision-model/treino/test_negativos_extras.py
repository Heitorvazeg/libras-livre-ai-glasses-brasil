"""Regressões da PoC: negativos sem pares, entrada real da CLI e proveniência."""
from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import torch

import contrastivo as ct
import pretreinar as pt
from test_entrada_pretreino import fixture

TREINO = Path(__file__).resolve().parent


class TestAmostradorExtras(unittest.TestCase):
    def test_reciclagem_nao_repete_classe_no_mesmo_lote(self):
        for k in (2, 3):
            with self.subTest(k=k):
                rotulos = [f"par{i}" for i in range(32) for _ in range(k)]
                rotulos += [f"extra{i}" for i in range(20) for _ in range(k - 1)]
                am = ct.AmostradorPK(rotulos, p=8, k=k, negativos_extras=15)
                for _ in range(3):
                    indices = list(am)
                    tamanho = am.p * am.k + am.negativos_extras
                    self.assertEqual(len(indices), len(am))
                    for inicio in range(0, len(indices), tamanho):
                        lote = [rotulos[i] for i in indices[inicio:inicio + tamanho]]
                        extras = lote[am.p * am.k:]
                        self.assertEqual(len(set(extras)), 15)
                        self.assertTrue(all(Counter(lote)[r] == 1 for r in extras))

    def test_pedido_maior_que_classes_distintas_falha(self):
        for rotulos, k in ((["par", "par", "solo"], 2),
                           (["par"] * 3 + ["solo"] * 2, 3)):
            with self.subTest(k=k), self.assertRaisesRegex(ValueError, "classes distintas"):
                ct.AmostradorPK(rotulos, p=1, k=k, negativos_extras=2)

    def test_parametros_invalidos_falham_antes_da_amostragem(self):
        for kwargs in ({"p": 0}, {"k": 1}, {"negativos_extras": -1}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                ct.AmostradorPK(["a", "a", "solo"], **kwargs)

    def test_semente_reproduz_e_extras_nao_mudam_nucleo_pk(self):
        rotulos = [f"par{i}" for i in range(8) for _ in range(3)] + [f"solo{i}" for i in range(5)]
        base = ct.AmostradorPK(rotulos, p=2, k=2, semente=42)
        a = ct.AmostradorPK(rotulos, p=2, k=2, semente=42, negativos_extras=3)
        b = ct.AmostradorPK(rotulos, p=2, k=2, semente=42, negativos_extras=3)
        for _ in range(3):
            ia, ib, controle = list(a), list(b), list(base)
            self.assertEqual(ia, ib)
            nucleo = [i for pos in range(0, len(ia), 7) for i in ia[pos:pos + 4]]
            self.assertEqual(nucleo, controle)

    def test_zero_preserva_sequencia_do_sampler_anterior(self):
        rotulos = [f"par{i}" for i in range(8) for _ in range(3)] + ["solo"]
        am = ct.AmostradorPK(rotulos, p=2, k=2, semente=42)
        rng = np.random.default_rng(42)
        for _ in range(3):
            esperado = []
            for c in rng.permutation(am.classes):
                esperado.extend(int(i) for i in rng.choice(am.por_classe[c], size=2, replace=False))
            self.assertEqual(list(am), esperado)

    def test_supcon_extras_so_no_denominador_com_gradiente(self):
        torch.manual_seed(7)
        bruto = torch.randn(6, 8, dtype=torch.float64, requires_grad=True)
        z = torch.nn.functional.normalize(bruto, dim=1)
        y = torch.tensor([0, 0, 1, 1, 2, 3])
        obtida = ct.perda_supcon(z, y)
        termos = []
        for i, positivo in enumerate((1, 0, 3, 2)):
            sim = (z[i] @ z.T) / .07
            termos.append(-sim[positivo] + torch.logsumexp(sim[torch.arange(6) != i], dim=0))
        esperada = torch.stack(termos).mean()
        torch.testing.assert_close(obtida, esperada)
        obtida.backward()
        self.assertTrue(torch.isfinite(bruto.grad).all())
        self.assertTrue((bruto.grad[4:].abs().sum(dim=1) > 0).all())


class TestIntegracaoExtras(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.origens = fixture(self.base / "input", fontes=("minds", "vlibrasil", "malta", "wlasl"),
            rotulos_por_fonte={"vlibrasil": ["par", "raro", "so-val", "quase"],
                              "malta": ["pizza", "misto"],
                              "wlasl": ["pizza", "hello"]})
        # Dois rótulos raros: um no treino; o outro SÓ na pessoa de validação.
        for p in self.origens["vlibrasil"].glob("*.npy"):
            pessoa, sinal, _ = pt.pv.identidade_nome(p)
            if ((sinal == "raro" and pessoa != "V01") or (sinal == "so-val" and pessoa != "V03")
                    or (sinal == "quase" and pessoa == "V02")):
                p.unlink()
                pt.pv.sidecar(p).unlink()

    def comando(self, saida, *extras):
        return [sys.executable, str(TREINO / "pretreinar.py"),
                *[a for f in ("vlibrasil", "malta", "wlasl")
                  for a in ("--corpus", str(self.origens[f]))],
                "--fontes", "vlibrasil,malta,wlasl", "--avaliacao", str(self.origens["minds"]),
                "--arquitetura", "gcn", "--ossos", "--com-z", "--z-recentrado",
                "--objetivo", "contrastivo", "--pessoa-val", "V03", "--epocas", "2",
                "--p-classes", "2", "--workers", "0", "--threads", "2", "--semente", "42",
                "--dispositivo", "cpu", "--saida", str(saida), *extras]

    def executar(self, nome, *extras):
        saida = self.base / nome
        r = subprocess.run(self.comando(saida, *extras), capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return json.loads((saida / "backbone_gcn.json").read_text())

    def test_cli_recupera_singleton_sem_mudar_validacao_ou_galeria(self):
        controle = self.executar("controle")
        candidato = self.executar("candidato", "--negativos-extras", "2")
        pc, pn = controle["proveniencia"]["particao"], candidato["proveniencia"]["particao"]
        self.assertEqual(pc["validacao"], pn["validacao"])
        self.assertEqual(pc["galeria"], pn["galeria"])
        registros = candidato["proveniencia"]["dados"]["amostras"]
        raros = {r["id"] for r in registros if r["registro"]["sinal"] == "raro"}
        pool = raros | {r["id"] for r in registros if r["registro"]["sinal"] == "quase"
                and r["registro"]["pessoa"] == "V01"}
        proibidos = {r["id"] for r in registros if r["registro"]["sinal"] == "so-val"
                     or (r["registro"]["fonte"] == "wlasl" and r["registro"]["sinal"] == "pizza")}
        self.assertEqual(len(raros), 1)
        self.assertEqual(set(pn["negativos_extras_elegiveis"]), pool)
        self.assertTrue(pool <= set(pn["otimizacao_elegiveis"]))
        self.assertTrue(raros <= set(pc["descartadas"]))
        for campo in ("treino", "validacao", "galeria", "otimizacao_elegiveis"):
            self.assertFalse(proibidos & set(pn[campo]))
        self.assertFalse(set(pn["validacao"]) & set(pn["otimizacao_elegiveis"]))
        self.assertEqual(set(pn["ancoras_elegiveis"]), set(pc["otimizacao_elegiveis"]))
        self.assertFalse(raros & set(pn["ancoras_elegiveis"]))
        self.assertTrue(proibidos <= set(pn["descartadas"]))

    def test_auditoria_verifica_pool_sem_construir_modelo(self):
        import io
        from contextlib import redirect_stdout
        comando = self.comando(self.base / "auditoria", "--negativos-extras", "2", "--auditar")
        with patch.object(sys, "argv", comando[1:]), \
                patch.object(pt.gg, "construir", side_effect=AssertionError("não construir")), \
                redirect_stdout(io.StringIO()) as saida:
            pt.main()
        self.assertIn("pool: 2 classes", saida.getvalue())
        self.assertFalse((self.base / "auditoria").exists())
        comando[comando.index("--negativos-extras") + 1] = "3"
        with patch.object(sys, "argv", comando[1:]), \
                patch.object(pt.gg, "construir", side_effect=AssertionError("não construir")), \
                self.assertRaisesRegex(SystemExit, "classes distintas"):
            pt.main()

    def test_cli_rejeita_negativo_invalido_sem_treinar(self):
        for extras in (("--negativos-extras", "-1"), ("--k-exemplos", "1"), ("--p-classes", "0")):
            with self.subTest(extras=extras):
                r = subprocess.run(self.comando(self.base / "invalido", *extras),
                                   capture_output=True, text=True, timeout=120)
                self.assertNotEqual(r.returncode, 0)
                self.assertNotIn("Traceback", r.stderr)
                self.assertFalse((self.base / "invalido").exists())


def executar():
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    resultado = unittest.TextTestRunner(verbosity=2).run(suite)
    if not resultado.wasSuccessful():
        raise AssertionError("regressões da PoC de negativos extras falharam")


if __name__ == "__main__":
    unittest.main(verbosity=2)
"""Política final em dados sintéticos; nenhum treino MINDS ou export real."""
import contextlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import torch

import modelo as mm
import treinar as tr


class TestPoliticaFinal(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.lm = self.base / "landmarks"
        self.lm.mkdir()
        rng = np.random.default_rng(42)
        for pessoa in (1, 2, 3):
            for sinal in (0, 1):
                for rep in (1, 2):
                    np.save(self.lm / f"pessoaM{pessoa:02d}_sinal-classe{sinal}_rep{rep:02d}.npy",
                            rng.normal(0, .2, (16, 57, 3)).astype(np.float32))
        self.out = self.base / "final"
        self.argv = ["treinar.py", "--arquitetura", "gcn", "--ossos", "--com-z", "--z-recentrado",
                     "--landmarks", str(self.lm), "--epocas", "2", "--batch", "4", "--workers", "0",
                     "--threads", "2", "--dispositivo", "cpu", "--agendador", "cosseno",
                     "--saida", str(self.out), "--final", "--politica-final", "ultima", "--semente", "73"]

    def executar(self, argv=None):
        with patch.object(sys, "argv", self.argv if argv is None else argv), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            tr.main()

    def test_salva_ultimo_estado_sem_avaliar_e_com_proveniencia(self):
        modelos, estados = [], []
        construir, step = tr.gg.construir, torch.optim.Adam.step
        def capturar_modelo(*a, **k):
            m = construir(*a, **k)
            modelos.append(m)
            return m
        def capturar_step(otim, *a, **k):
            r = step(otim, *a, **k)
            estados.append({n: v.clone() for n, v in modelos[-1].state_dict().items()})
            return r
        with patch.object(tr.gg, "construir", side_effect=capturar_modelo), \
             patch.object(torch.optim.Adam, "step", capturar_step), \
             patch.object(tr, "_avaliar", side_effect=AssertionError("final não avalia")):
            self.executar()
        self.assertEqual(len(estados), 6)  # 12 clipes/batch4 × 2 épocas
        modelo, rotulos, meta = mm.carregar(self.out / "modelo_final.pt")
        for n, v in modelo.state_dict().items():
            self.assertTrue(torch.equal(v, estados[-1][n]), n)
        self.assertTrue(any(not torch.equal(estados[0][n], estados[-1][n]) for n in estados[0]))
        self.assertEqual(meta["epoca_salva"], 2)
        self.assertEqual(meta["politica_selecao"], "ultima")
        self.assertFalse(meta["avaliacao_independente"])
        part = meta["proveniencia"]["particao"]
        self.assertEqual(part["treino_pessoas"], ["M01", "M02", "M03"])
        self.assertEqual(part["validacao_pessoas"], [])
        self.assertEqual(part["teste"], [])
        self.assertFalse(part["validacao_sobrepoe_treino"])
        import exportar
        _, labels, origem = exportar.montar(self.out / "modelo_final.pt", "landmarks")
        self.assertEqual(labels, rotulos)
        self.assertEqual(exportar.resolver_layout(origem, None)["dimensoes"], 3)

    def test_cli_exige_politica_seed_e_exclusividade(self):
        sem_politica = self.argv.copy()
        i = sem_politica.index("--politica-final")
        del sem_politica[i:i + 2]
        for argv in (sem_politica, self.argv[:-2], [a for a in self.argv if a != "--final"],
                     self.argv + ["--salvar-evidencias"], self.argv + ["--epocas", "0"]):
            with self.subTest(argv=argv), patch.object(tr.dd, "carregar", side_effect=AssertionError("recusar antes de ler dados")), self.assertRaises(SystemExit):
                self.executar(argv)

    def test_saida_ocupada_nao_treina_nem_sobrescreve(self):
        self.out.mkdir()
        original = self.out / "modelo_final.pt"
        original.write_bytes(b"preservar")
        with patch.object(tr.dd, "carregar", side_effect=AssertionError("não carregar")), self.assertRaises(SystemExit):
            self.executar()
        self.assertEqual(original.read_bytes(), b"preservar")

    def test_perda_nao_finita_nao_publica_checkpoint(self):
        with patch.object(tr.nn.CrossEntropyLoss, "forward", return_value=torch.tensor(float("nan"))), self.assertRaisesRegex(ValueError, "não finita"):
            self.executar()
        self.assertFalse((self.out / "modelo_final.pt").exists())

    def test_inventario_final_incompleto_recusado_antes_do_modelo(self):
        with patch.object(tr.gg, "construir", side_effect=AssertionError("não construir")), \
                self.assertRaises(ValueError):
            self.executar(self.argv + ["--inventario-final", str(self.base / "inventario.json")])
        self.assertFalse(self.out.exists())

    def test_inventario_final_so_final_minds_e_sem_descarte(self):
        import entrada_final as ef
        extra = ["--inventario-final", str(self.base / "inventario.json")]
        with self.assertRaises(SystemExit):
            self.executar(self.argv + extra + ["--fontes", "vlibrasil"])
        inventario = {"amostras": [], "n_clipes": 800}
        with patch.object(ef, "validar_minds", return_value=inventario), \
                patch.object(tr.gg, "construir", side_effect=AssertionError("não construir")), \
                self.assertRaisesRegex(ValueError, "800 identidades"):
            self.executar(self.argv + extra)
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
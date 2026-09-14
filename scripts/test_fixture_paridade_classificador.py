"""Referências PyTorch e CLI de fixtures; conversão real depende do toolchain LiteRT."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import torch

import fixture_paridade_classificador as fp
import gcn
import modelo as mm


class TestFixtureReal(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        torch.set_num_threads(2)

    def checkpoint(self, com_z=True, sem_imputacao=False):
        args = dict(arquitetura="gcn", ossos=True, movimento=False,
                    com_z=com_z, z_recentrado=com_z, sem_imputacao=sem_imputacao)
        cfg = fp.load_config()
        modelo = gcn.construir(2, canais_ent=6 if com_z else 4)
        checkpoint = self.base / "fold.pt"
        mm.salvar(modelo, checkpoint, ["filho", "medo"],
                  {"args": args, "pontos": cfg.pose_indices})
        return checkpoint

    def executar(self, saida, *extras):
        argv = ["fixture", "--saida", str(saida), "--sequencias", "1", *extras]
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
            fp.main()
        return json.loads((saida / "paridade_classificador.json").read_text())

    def test_checkpoint_real_referencias_e_hash_sem_falso_tflite(self):
        cp = self.checkpoint()
        saida = self.base / "real"
        with patch.object(fp.ex, "converter", side_effect=AssertionError("não converter")):
            f = self.executar(saida, "--checkpoint", str(cp), "--somente-pytorch")
        self.assertFalse(f["smoke"])
        self.assertFalse(f["tflite_validado"])
        self.assertIsNone(f["modelo"])
        self.assertEqual(f["checkpoint_sha256"], mm.pv.hash_arquivo(cp))
        self.assertEqual(f["rotulos"], ["filho", "medo"])
        self.assertTrue(f["entrada_sintetica"])
        modelo, _, _ = fp.ex.montar(cp, "landmarks")
        seq = f["sequencias"][0]
        logits = fp._logits(modelo, np.asarray(seq["imputados_reamostrados"], dtype=np.float32))
        np.testing.assert_allclose(logits, seq["logits_app_pytorch"], atol=1e-6)
        self.assertFalse(list(saida.glob("*.tflite")))

    def test_sem_checkpoint_continua_smoke(self):
        f = self.executar(self.base / "smoke", "--somente-pytorch")
        self.assertTrue(f["smoke"])
        self.assertIsNone(f["checkpoint_sha256"])

    def test_representacao_2d_sem_imputacao_respeitada(self):
        cp = self.checkpoint(com_z=False, sem_imputacao=True)
        f = self.executar(self.base / "2d", "--checkpoint", str(cp), "--somente-pytorch")
        self.assertFalse(f["representacao"]["com_z"])
        self.assertFalse(f["representacao"]["imputar"])
        self.assertEqual(len(f["sequencias"][0]["logits_app_pytorch"]), 2)

    def test_destino_real_privado_e_vazio(self):
        cp = self.checkpoint()
        with self.assertRaisesRegex(SystemExit, "saída privada"):
            fp.conferir_destino(fp.RAIZ / "mobile-app-companion/app/src/androidTest/assets", cp)
        with self.assertRaisesRegex(SystemExit, "vazia"):
            fp.conferir_destino(self.base, cp)

    def test_flags_ausentes_no_checkpoint_recusadas(self):
        cp = self.checkpoint()
        d = torch.load(cp, weights_only=False)
        del d["meta"]["args"]["z_recentrado"]
        torch.save(d, cp)
        with self.assertRaisesRegex(SystemExit, "não declara"):
            self.executar(self.base / "invalido", "--checkpoint", str(cp), "--somente-pytorch")


if __name__ == "__main__":
    unittest.main(verbosity=2)
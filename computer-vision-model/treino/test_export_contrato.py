"""Contrato do CLI com checkpoint real; conversor TFLite simulado, sem dependências.

Não mede paridade TFLite. Exercita main, resolução de metadados e sidecar, os
caminhos que um teste isolado da cabeça e o smoke sem checkpoint não cobrem.
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import torch
import yaml
import numpy as np

import exportar as ex

CFG = yaml.safe_load((ex.AQUI.parent / "PoC" / "config.yaml").read_text(encoding="utf-8"))


class TestContrato(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)
        cls.pesos = ex.md.construir(3, pretreinado=False).state_dict()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.ckpt = self.base / "modelo.pt"
        self.saida = self.base / "modelo.tflite"

    def salvar(self, meta):
        torch.save({"state_dict": self.pesos, "arquitetura": "resnet",
                    "rotulos": ["a", "b", "c"], "meta": meta}, self.ckpt)

    def cli(self, extra=(), shape_override=None):
        capturado = {}

        def converter(modelo, entrada, destino, **kwargs):
            capturado["shape"] = list(entrada.shape)
            destino.write_bytes(b"TFLITE SIMULADO: teste de contrato, nao conversao")
            return "simulado"

        def paridade(*args, **kwargs):
            return {"amostras": 8, "max_dif_logit": 0.0, "discordancias_top1": 0,
                    "shape_entrada": shape_override or capturado["shape"],
                    "dtype_entrada": "float32", "shape_saida": [1, 3]}

        with patch.object(sys, "argv", ["exportar.py", "--checkpoint", str(self.ckpt),
                                        "--saida", str(self.saida), *extra]), \
                patch.object(ex, "converter", side_effect=converter) as conversor, \
                patch.object(ex, "_conferir_precisao", return_value={"tamanho_mb": 0,
                               "tipos_tensores": {"float32": 1}}), \
                patch.object(ex, "conferir_paridade", side_effect=paridade), \
                patch.object(ex.md, "codigo_atual", return_value={"fontes_sha256": "teste"}):
            try:
                ex.main()
            except SystemExit:
                if shape_override is None:
                    conversor.assert_not_called()
                raise
        return json.loads(self.saida.with_suffix(".json").read_text(encoding="utf-8"))

    def test_defaults_checkpoint_57_e_serializacao_path(self):
        self.salvar({"pontos": CFG["pose_indices"], "args": {"saida": Path("resultados")}})
        r = self.cli()
        c = r["contrato_entrada"]
        self.assertEqual(c["shape"], [1, 96, 57, 2])
        self.assertEqual(c["shape"], r["paridade_pytorch"]["shape_entrada"])
        self.assertEqual([p["nome"] for p in c["layout_landmarks"]["pose_ordenada"]],
                         list(CFG["pose_indices"]))
        self.assertEqual(r["args"]["checkpoint"], str(self.ckpt))
        self.assertNotIn("calibracao", r)
        self.assertNotIn("calibracao", r["args"])
        self.assertEqual(c["temporal"]["frames"], 96)
        self.assertFalse(c["temporal"]["reamostragem_embutida"])

    def test_override_conflitante_falha_antes_conversao(self):
        self.salvar({"pontos": CFG["pose_indices"]})
        with self.assertRaisesRegex(SystemExit, "49.*diverge.*57"):
            self.cli(["--pontos", "49"])
        self.assertEqual(self.cli(["--pontos", "57"])["args"]["pontos"], 57)

    def test_checkpoint_historico_49_nao_usa_config_atual(self):
        pose = dict(list(CFG["pose_indices"].items())[:7])
        self.salvar({"pontos": pose})
        self.assertEqual(self.cli()["contrato_entrada"]["shape"], [1, 96, 49, 2])

    def test_sem_mapa_exige_declaracao_explicita(self):
        self.salvar({})
        with self.assertRaisesRegex(SystemExit, "sem metadado de pontos"):
            self.cli()
        r = self.cli(["--pontos", "57"])
        self.assertEqual(r["args"]["layout"]["fonte_layout"], "cli_legado_nao_verificado")
        self.assertIsNone(r["args"]["layout"]["pose_ordenada"])

    def test_proveniencia_e_ordem_conflitante(self):
        self.salvar({"proveniencia": {"config": CFG}})
        self.assertEqual(self.cli()["args"]["pontos"], 57)
        invertido = dict(reversed(list(CFG["pose_indices"].items())))
        self.salvar({"pontos": invertido, "proveniencia": {"config": CFG}})
        with self.assertRaisesRegex(SystemExit, "ordem de pontos diverge"):
            self.cli()

    def test_checkpoint_3d_rejeitado_em_ambos_modos(self):
        for meta in ({"args": {"com_z": True}}, {"args": {"z_recentrado": True}},
                     {"proveniencia": {"args": {"com_z": True}}}, {"limite_escala_z": 5.0}):
            self.salvar(meta | {"pontos": CFG["pose_indices"]})
            for modo in ("landmarks", "imagem"):
                with self.subTest(meta=meta, modo=modo), self.assertRaisesRegex(SystemExit, "z/3D"):
                    self.cli(["--modo", modo])

    def test_cabeca_nao_descarta_z_e_export_torch_funciona(self):
        with self.assertRaisesRegex(ValueError, "3D não suportada"):
            ex.CabecaSkeletonDML()(torch.zeros(1, 96, 57, 3))
        x = torch.randn(1, 96, 57, 2)
        cabeca = ex.CabecaSkeletonDML().eval()
        exportado = torch.export.export(cabeca, (x,)).module()
        torch.testing.assert_close(exportado(x), cabeca(x))

    def test_contrato_recusa_shape_tflite_divergente(self):
        self.salvar({"pontos": CFG["pose_indices"]})
        with self.assertRaisesRegex(SystemExit, "contrato.*diverge"):
            self.cli(shape_override=[1, 96, 49, 2])
        self.assertFalse(self.saida.with_suffix(".json").exists())

    def test_frames_invalidos_e_customizados(self):
        self.salvar({"pontos": CFG["pose_indices"]})
        for t in (0, -3, 1, 100):
            with self.subTest(t=t), self.assertRaisesRegex(SystemExit, "frames"):
                self.cli(["--frames", str(t)])
        self.assertEqual(self.cli(["--frames", "120"])["contrato_entrada"]["shape"],
                         [1, 120, 57, 2])

    def test_smoke_layout_e_limite_incompativel(self):
        self.assertEqual(ex.resolver_layout({"smoke": True}, None)["pontos"], 57)
        self.salvar({"pontos": CFG["pose_indices"], "limite_escala": 5.0})
        with self.assertRaisesRegex(SystemExit, "limite_escala"):
            self.cli()

    def preparar_calibracao(self):
        import calibracao as cb
        from test_calibracao import escrever_bundle
        self.salvar({"pontos": CFG["pose_indices"]})
        z = np.array([[4., 0., 0.], [0., 4., 0.], [1., 0., 0.], [0., 1., 0.]])
        y = np.array([0, 1, 1, 0])
        evidencia = escrever_bundle(self.base, "fold.json", ["a", "b", "c"], z, y,
                                     checkpoint_bytes=self.ckpt.read_bytes())
        bloco = cb.calibrar([evidencia])
        path = self.base / "calibracao.json"
        path.write_text(json.dumps(bloco))
        return path, bloco

    def test_calibracao_mesmo_checkpoint_e_fontes_conferidas(self):
        path, bloco = self.preparar_calibracao()
        r = self.cli(["--calibracao", str(path)])
        self.assertEqual(r["calibracao"], bloco)
        self.assertEqual(r["origem"]["sha256"], bloco["checkpoint_sha256"])
        self.assertFalse(r["calibracao"]["aprovado_entrega"])

    def test_calibracao_invalida_recusada_antes_converter(self):
        path, bloco = self.preparar_calibracao()
        alteracoes = [{"temperatura": t} for t in (-1., 0., float("nan"), float("inf"), True, 1e-100, 1e100)]
        alteracoes += [{"checkpoint_sha256": "outro"}, {"rotulos": ["b", "a", "c"]},
                       {"schema": 1}, {"escopo": "pool_loso_analise"},
                       {"meta_nao_atingida": True, "limiar_sugerido": None},
                       {"limiar_sugerido": 0.}]
        for a in alteracoes:
            path.write_text(json.dumps(bloco | a))
            with self.subTest(a=a), self.assertRaisesRegex(SystemExit, "calibração recusada"):
                self.cli(["--calibracao", str(path)])
            self.assertFalse(self.saida.exists())
            self.assertFalse(self.saida.with_suffix(".json").exists())

        # Fonte removida também falha ANTES do conversor e preserva saídas antigas.
        path.write_text(json.dumps(bloco))
        Path(bloco["caminhos_fontes"][0]["evidencias"]).unlink()
        self.saida.write_bytes(b"modelo anterior")
        sidecar = self.saida.with_suffix(".json")
        sidecar.write_bytes(b"sidecar anterior")
        with self.assertRaisesRegex(SystemExit, "calibração recusada"):
            self.cli(["--calibracao", str(path)])
        self.assertEqual(self.saida.read_bytes(), b"modelo anterior")
        self.assertEqual(sidecar.read_bytes(), b"sidecar anterior")

    def test_calibracao_outro_checkpoint_mesmos_rotulos_recusada(self):
        path, _ = self.preparar_calibracao()
        self.salvar({"pontos": CFG["pose_indices"], "outro_checkpoint": True})
        with self.assertRaisesRegex(SystemExit, "checkpoint"):
            self.cli(["--calibracao", str(path)])
        self.assertFalse(self.saida.exists())

    def test_sidecar_direto_recusa_calibracao_invalida_sem_sobrescrever(self):
        path, bloco = self.preparar_calibracao()
        r = self.cli(["--calibracao", str(path)])
        sidecar = self.saida.with_suffix(".json")
        original = sidecar.read_bytes()
        with self.assertRaisesRegex(SystemExit, "calibração recusada"):
            ex.escrever_sidecar(self.saida, r["rotulos"], r["origem"], r["modo"],
                                r["paridade_pytorch"], r["args"], bloco | {"temperatura": float("nan")})
        self.assertEqual(sidecar.read_bytes(), original)


def executar():
    resultado = unittest.TextTestRunner(verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(TestContrato))
    if not resultado.wasSuccessful():
        raise AssertionError("regressões do contrato de exportação falharam")


if __name__ == "__main__":
    executar()
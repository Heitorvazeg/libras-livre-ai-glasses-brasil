"""Treinos curtos reais, em CPU e diretórios temporários; não lê corpora privados."""
from __future__ import annotations

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

import dados as dd
import evidencias_loso as ev
import modelo as mm
import treinar as tr


class TestEvidenciasLoso(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.lm = self.base / "landmarks"
        self.out = self.base / "resultado"
        self.lm.mkdir()
        rng = np.random.default_rng(7)
        for pessoa in range(1, 4):
            for classe in range(2):
                for rep in range(1, 3):
                    seq = rng.normal(classe * 0.4, 0.1, (16, 57, 3)).astype(np.float32)
                    np.save(self.lm / f"pessoaM{pessoa:02d}_sinal-classe{classe}_rep{rep:02d}.npy", seq)
        self.argv = ["treinar.py", "--arquitetura", "gcn", "--ossos", "--com-z",
                     "--z-recentrado", "--landmarks", str(self.lm), "--epocas", "1",
                     "--batch", "4", "--workers", "0", "--threads", "2",
                     "--dispositivo", "cpu", "--semente", "73", "--folds", "1",
                     "--saida", str(self.out)]

    def executar(self, evidencias=True, extras=()):
        args = self.argv + (["--salvar-evidencias"] if evidencias else []) + list(extras)
        with patch.object(sys, "argv", args), contextlib.redirect_stdout(io.StringIO()):
            tr.main()

    def arquivos(self):
        marcador = self.out / "rodadas" / "01-M01.json"
        r = json.loads(marcador.read_text())
        return marcador, r, *ev.artefatos_fold(marcador)

    def test_checkpoint_recarrega_logits_ids_e_contrato(self):
        self.executar()
        marcador, r, checkpoint, saida = self.arquivos()
        s = json.loads(saida.read_text())
        rede, rotulos, meta = mm.carregar(checkpoint)
        self.assertEqual(meta["melhor_epoca"], r["melhor_epoca"])
        self.assertEqual(meta["proveniencia"]["particao"]["metodo"], "loso")
        self.assertEqual(rotulos, s["rotulos"])
        clipes = dd.carregar(self.lm, com_z=True, z_recentrado=True)
        for grupo, pessoa in (("teste", "M01"), ("validacao", "M02")):
            cs = [c for c in clipes if c.pessoa == pessoa]
            loader = tr._loader(cs, rotulos, None, False, 4, 0, False, "gcn", True)
            logits = []
            tr._avaliar(rede, loader, torch.nn.CrossEntropyLoss(), torch.device("cpu"), logits=logits)
            np.testing.assert_allclose(logits, s[grupo]["logits"], rtol=0, atol=1e-7)
            self.assertEqual(s[grupo]["ids"], [ev.id_clipe(c) for c in cs])
        import exportar
        modelo_export, labels, origem = exportar.montar(checkpoint, "landmarks")
        self.assertEqual(labels, rotulos)
        self.assertEqual(exportar.resolver_layout(origem, None)["dimensoes"], 3)
        self.assertIsNotNone(modelo_export)
        self.assertEqual(r["evidencias"]["checkpoint"]["sha256"], mm.pv.hash_arquivo(checkpoint))
        self.assertEqual(list(marcador.parent.glob("*.json")), [marcador])

    def test_retomada_nao_treina_e_nao_regrava_artefatos(self):
        self.executar()
        arquivos = self.arquivos()
        antes = {p: p.read_bytes() for p in (arquivos[0], arquivos[2], arquivos[3])}
        with patch.object(tr, "treinar_rodada", side_effect=AssertionError("não deveria treinar")):
            self.executar()
        for p, conteudo in antes.items():
            self.assertEqual(p.read_bytes(), conteudo)

    def test_instrumentacao_preserva_predicoes_pesos_e_rng(self):
        pesos = {}
        original = tr.treinar_rodada
        def capturar(*args, **kwargs):
            resultado = original(*args, **kwargs)
            pesos.update({k: v.detach().clone() for k, v in resultado[-1].state_dict().items()})
            return resultado
        with patch.object(tr, "treinar_rodada", side_effect=capturar):
            self.executar(False)
        baseline = json.loads((self.out / "rodadas" / "01-M01.json").read_text())
        rng_antes = torch.get_rng_state().clone()
        self.out = self.base / "instrumentado"
        self.argv[-1] = str(self.out)
        self.executar(True)
        _, atual, checkpoint, _ = self.arquivos()
        self.assertEqual(baseline["predicoes"], atual["predicoes"])
        self.assertEqual(baseline["melhor_epoca"], atual["melhor_epoca"])
        self.assertTrue(torch.equal(rng_antes, torch.get_rng_state()))
        for k, valor in torch.load(checkpoint, weights_only=False)["state_dict"].items():
            self.assertTrue(torch.equal(pesos[k], valor), k)

    def test_legado_recusado_sem_apagar(self):
        self.executar(False)
        marcador = self.out / "rodadas" / "01-M01.json"
        antes = marcador.read_bytes()
        with self.assertRaisesRegex(SystemExit, "evidências ausentes/incompatíveis"):
            self.executar(True)
        self.assertEqual(antes, marcador.read_bytes())

    def test_nao_desliga_conferencia_de_evidencias_na_retomada(self):
        self.executar(True)
        with self.assertRaisesRegex(SystemExit, "mantenha --salvar-evidencias"):
            self.executar(False)

    def test_checkpoint_ausente_e_saida_corrompida_recusados(self):
        self.executar()
        _, _, checkpoint, saida = self.arquivos()
        bytes_cp = checkpoint.read_bytes()
        checkpoint.unlink()
        with self.assertRaisesRegex(SystemExit, "evidências ausentes/incompatíveis"):
            self.executar()
        checkpoint.write_bytes(bytes_cp + b"alterado")
        with self.assertRaisesRegex(SystemExit, "hash/nome"):
            self.executar()
        checkpoint.write_bytes(bytes_cp)
        saida.write_text("{}")
        with self.assertRaisesRegex(SystemExit, "hash/nome"):
            self.executar()

    def test_codigo_ambiente_e_backbone_invalidam_retomada(self):
        self.executar()
        codigo = mm.codigo_atual()
        with patch.object(tr, "treinar_rodada", side_effect=AssertionError("não deveria treinar")):
            for chave in ("fontes_sha256", "python", "pacotes"):
                with self.subTest(chave=chave), patch.object(mm, "codigo_atual",
                                                            return_value=codigo | {chave: "alterado"}):
                    with self.assertRaisesRegex(SystemExit, "código, dados, ambiente"):
                        self.executar()
            backbone = self.base / "outro-backbone.pt"
            backbone.write_bytes(b"preflight must reject before loading")
            with self.assertRaisesRegex(SystemExit, "código, dados, ambiente"):
                self.executar(extras=["--inicializar", str(backbone)])

    def test_interrupcao_antes_marcador_refaz_fold(self):
        original = ev.escrever_json
        marcador = self.out / "rodadas" / "01-M01.json"
        def interromper(destino, conteudo):
            if destino == marcador:
                raise OSError("interrupção simulada")
            original(destino, conteudo)
        with patch.object(ev, "escrever_json", side_effect=interromper):
            with self.assertRaisesRegex(OSError, "interrupção simulada"):
                self.executar()
        self.assertFalse(marcador.exists())
        self.assertTrue(all(p.is_file() for p in ev.artefatos_fold(marcador)))
        with patch.object(tr, "treinar_rodada", wraps=tr.treinar_rodada) as treino:
            self.executar()
        self.assertEqual(treino.call_count, 1)
        self.assertTrue(marcador.exists())

    def test_retomada_parcial_so_treina_fold_faltante(self):
        self.executar()
        arquivos = self.arquivos()
        antes = {p: p.read_bytes() for p in (arquivos[0], arquivos[2], arquivos[3])}
        with patch.object(tr, "treinar_rodada", wraps=tr.treinar_rodada) as treino:
            self.executar(extras=["--folds", "2"])
        self.assertEqual(treino.call_count, 1)
        self.assertEqual(treino.call_args.args[2][0].pessoa, "M02")
        for p, conteudo in antes.items():
            self.assertEqual(p.read_bytes(), conteudo)

    def test_dado_alterado_recusado_antes_de_treinar(self):
        self.executar()
        p = next(self.lm.glob("*.npy"))
        np.save(p, np.load(p) + 0.01)
        with patch.object(tr, "treinar_rodada", side_effect=AssertionError("não deveria treinar")):
            with self.assertRaisesRegex(SystemExit, "código, dados, ambiente"):
                self.executar()

    def test_particao_sobreposta_e_logits_invalidos_recusados(self):
        clipes = dd.carregar(self.lm)
        with self.assertRaisesRegex(ValueError, "disjuntas"):
            ev.particao_fold(clipes, clipes, clipes)
        self.executar()
        _, r, _, saida = self.arquivos()
        s = json.loads(saida.read_text())
        s["teste"]["logits"][0][0] = float("nan")
        with self.assertRaisesRegex(ValueError, "inconsistentes"):
            ev.conferir_saidas(s, r["rotulos"], s["particao"])

    def test_final_com_evidencias_e_zero_epocas_recusados(self):
        with self.assertRaises(SystemExit):
            self.executar(extras=["--final"])
        with self.assertRaises(SystemExit):
            self.executar(extras=["--epocas", "0"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
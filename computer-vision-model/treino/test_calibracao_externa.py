"""Checkpoint/landmarks sintéticos; inferência real CPU, conversão TFLite simulada."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import torch
import yaml

import calibracao as cb
import calibracao_externa as ce
import dados as dd
import exportar as ex
import gcn
from treinar import DatasetSinais


class TestCalibracaoExterna(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(2)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.lm = self.base / "landmarks"
        self.lm.mkdir()
        self.man = self.base / "manifesto.json"
        self.prot = self.base / "protocolo.json"
        self.ev = self.base / "evidencias.json"
        self.cal = self.base / "calibracao.json"
        self.ckpt = self.base / "modelo.pt"
        self.rotulos = ["a", "b", "c"]
        rng = np.random.default_rng(12)
        itens = []
        for sinal in self.rotulos:
            for pessoa in ("V01", "V02"):
                p = self.lm / f"pessoa{pessoa}_sinal-{sinal}_rep01.npy"
                arr = rng.normal(0, .2, (9, 57, 3)).astype(np.float32)
                arr[2:4, 15:36] = 0  # exercício real de imputação de mãos
                np.save(p, arr)
                itens.append({"arquivo": str(p.relative_to(self.base)), "classe": sinal,
                              "pessoa": pessoa, "corpus": "vlibrasil", "sha256": ce.hash_arquivo(p)})
        self.manifesto = {"schema": 1, "finalidade": "calibracao_experimental",
            "avaliacao_independente": False, "aprovado_entrega": False,
            "exposicao_previa": ce.EXPOSICAO, "itens": itens, "n_clipes": 6,
            "n_pessoas": 2, "pessoas": ["V01", "V02"], "classes_cobertas": self.rotulos,
            "pessoas_por_classe": {r: ["V01", "V02"] for r in self.rotulos}}
        self.gravar(self.man, self.manifesto)
        self.gravar(self.prot, ce.protocolo(.3, 1.))
        self.rede = gcn.construir(3, canais_ent=6, largura=4)
        with torch.no_grad():
            self.rede.fc.weight.zero_()
            self.rede.fc.bias.copy_(torch.tensor([2., 0., -1.]))
        cfg = yaml.safe_load((ex.AQUI.parent / "PoC/config.yaml").read_text())
        args = {"final": True, "politica_final": "ultima", "epocas": 2,
                "ossos": True, "com_z": True, "z_recentrado": True,
                "sem_imputacao": False, "movimento": False,
                "adjacencia_adaptativa": False, "kernel_temporal": 9}
        self.meta = {"args": args, "politica_selecao": "ultima", "epoca_salva": 2,
            "avaliacao_independente": False, "pontos": cfg["pose_indices"], "pessoas": ["M01"],
            "proveniencia": {"dados": {"amostras": [{"arquivo": "treino-sintetico.npy", "sha256": "0"*64}]}}}
        self.salvar_modelo()

    def gravar(self, p, d):
        p.write_text(json.dumps(d, ensure_ascii=False, allow_nan=False))

    def salvar_modelo(self):
        torch.save({"state_dict": self.rede.state_dict(), "arquitetura": "gcn",
                    "config_modelo": self.rede.config, "rotulos": self.rotulos, "meta": self.meta}, self.ckpt)
        self.checkpoint_sha = ce.hash_arquivo(self.ckpt)

    def inferir(self):
        return ce.gerar_evidencias(self.ckpt, self.man, self.base, self.prot, self.ev)

    def preparar(self):
        self.inferir()
        return ce.ajustar(self.ev, self.cal)

    def validar(self, bloco):
        cb.validar_exportacao(bloco, self.checkpoint_sha, self.rotulos)

    def test_protocolo_obrigatorio_sem_defaults_e_sem_sobrescrita(self):
        for a, c in ((0, .5), (.9, -1), (.9, 1.1), (True, .5), (.9, float("nan"))):
            with self.subTest(a=a, c=c), self.assertRaises(ValueError):
                ce.protocolo(a, c)
        self.gravar(self.prot, ce.protocolo(.3, 1.) | {"aprovado_entrega": 0})
        with self.assertRaises(ValueError):
            ce.ler_protocolo(self.prot)
        self.prot.unlink()
        with patch.object(ce.mm, "carregar") as carregar, self.assertRaises(OSError):
            self.inferir()
        carregar.assert_not_called()
        self.assertFalse(self.ev.exists())
        self.ev.write_text("preservar")
        with self.assertRaisesRegex(ValueError, "existe"):
            self.inferir()
        self.assertEqual(self.ev.read_text(), "preservar")

    def test_manifesto_hash_duplicata_contagens_rotulo_caminho_exposicao(self):
        variantes = []
        for chave, valor in (("sha256", "f" * 64), ("classe", "b"),
                             ("arquivo", "../fora.npy"), ("pessoa", "M01")):
            d = copy.deepcopy(self.manifesto)
            d["itens"][0][chave] = valor
            variantes.append(d)
        variantes += [self.manifesto | {"n_clipes": 7},
                      self.manifesto | {"avaliacao_independente": True},
                      self.manifesto | {"itens": self.manifesto["itens"] * 2}]
        for d in variantes:
            self.gravar(self.man, d)
            with self.subTest(d=d), self.assertRaises(ValueError):
                ce.ler_manifesto(self.man, self.base)

    def test_arrays_invalidos_e_symlink_recusados(self):
        p = self.base / self.manifesto["itens"][0]["arquivo"]
        for arr in (np.zeros((2,57,3), np.float32), np.zeros((3,49,3), np.float32),
                    np.zeros((3,57,3), np.float64), np.full((3,57,3), np.nan, np.float32)):
            np.save(p, arr)
            self.manifesto["itens"][0]["sha256"] = ce.hash_arquivo(p)
            self.gravar(self.man, self.manifesto)
            with self.assertRaises(ValueError):
                ce.ler_manifesto(self.man, self.base)
        alvo = p.with_name("alvo.npy")
        p.rename(alvo)
        p.symlink_to(alvo)
        with self.assertRaisesRegex(ValueError, "simbólico"):
            ce.ler_manifesto(self.man, self.base)

    def test_inferencia_mesmo_preprocessamento_loader_sem_augmentacao(self):
        # Backbone não constante para comparação efetiva dos tensores/resultados.
        torch.manual_seed(18)
        self.rede = gcn.construir(3, canais_ent=6, largura=4)
        self.salvar_modelo()
        original = {n: v.clone() for n, v in self.rede.state_dict().items()}
        d = self.inferir()
        clipes = dd.carregar(self.lm, fontes="vlibrasil", com_z=True, z_recentrado=True)
        por_id = {f"landmarks/pessoa{c.pessoa}_sinal-{c.sinal}_rep{c.rep}.npy": c for c in clipes}
        ds = DatasetSinais([por_id[i] for i in d["ids"]], self.rotulos, None, False,
                           arquitetura="gcn", ossos=True)
        self.rede.eval()
        with torch.inference_mode():
            esperado = torch.cat([self.rede(x[None]) for x, _ in ds]).numpy()
        np.testing.assert_allclose(d["logits"], esperado, atol=1e-7)
        for n,v in self.rede.state_dict().items():
            self.assertTrue(torch.equal(v, original[n]))
        self.assertFalse(d["avaliacao_independente"])
        self.assertEqual(d["protocolo_sha256"], ce.hash_arquivo(self.prot))

    def test_checkpoint_loso_ou_representacao_incompativel_recusados(self):
        original = copy.deepcopy(self.meta)
        for chave, valor in (("final", False), ("z_recentrado", False), ("movimento", True),
                             ("movimento", None), ("sem_imputacao", None)):
            self.meta = copy.deepcopy(original)
            self.meta["args"][chave] = valor
            self.salvar_modelo()
            with self.subTest(chave=chave), self.assertRaises(ValueError):
                self.inferir()
            self.assertFalse(self.ev.exists())
        self.meta = copy.deepcopy(original)
        self.meta.pop("avaliacao_independente")
        self.salvar_modelo()
        with self.assertRaises(ValueError):
            self.inferir()

    def test_sobreposicao_fine_tuning_recusada(self):
        for alterar in ("pessoas", "bytes"):
            if alterar == "pessoas":
                self.meta["pessoas"] = ["V01"]
            else:
                self.meta["pessoas"] = ["M01"]
                self.meta["proveniencia"]["dados"]["amostras"][0]["sha256"] = self.manifesto["itens"][0]["sha256"]
            self.salvar_modelo()
            with self.assertRaisesRegex(ValueError, "sobrepõe"):
                self.inferir()

    def test_ajuste_e_validacao_sem_desserializar_checkpoint(self):
        bloco = self.preparar()
        self.assertEqual(bloco["schema"], 3)
        self.assertEqual(bloco["n_amostras_ajuste"], 6)
        self.assertFalse(bloco["aprovado_entrega"])
        self.assertFalse(bloco["meta_nao_atingida"])
        self.assertEqual(bloco["cobertura_esperada"], 1.)
        with patch.object(torch, "load", side_effect=AssertionError("não desserializar")):
            self.validar(bloco)
        with self.assertRaisesRegex(ValueError, "existe"):
            ce.ajustar(self.ev, self.cal)

    def test_campos_adulterados_e_outro_checkpoint_recusados(self):
        bloco = self.preparar()
        for mudanca in ({"avaliacao_independente": True}, {"avaliacao_independente": None},
                {"avaliacao_independente": 0}, {"aprovado_entrega": 0},
                {"aprovado_entrega": True}, {"meta_nao_atingida": None},
                        {"rotulos": list(reversed(self.rotulos))}, {"checkpoint_sha256": "1"*64},
                        {"temperatura": float("nan")}, {"temperatura": -1},
                        {"limiar_sugerido": 0.}, {"cobertura_minima": 0.},
                        {"n_amostras_ajuste": 99}, {"limites": []}, {"schema": 2}):
            with self.subTest(mudanca=mudanca), self.assertRaises(ValueError):
                self.validar(bloco | mudanca)

    def test_fontes_alteradas_e_removidas_recusadas(self):
        bloco = self.preparar()
        for p in (self.ev, self.man, self.prot, self.ckpt,
                  self.base / self.manifesto["itens"][0]["arquivo"]):
            original = p.read_bytes()
            p.write_bytes(original + b" ")
            with self.subTest(path=p), self.assertRaises(ValueError):
                self.validar(bloco)
            p.unlink()
            with self.assertRaises(ValueError):
                self.validar(bloco)
            p.write_bytes(original)

    def test_criterio_cobertura_e_acuracia_sem_politica_viavel(self):
        self.gravar(self.prot, ce.protocolo(.9, .8))
        d = self.inferir()
        # Fixture de logits conhecidos: só 2/6 aceitos corretos no melhor limiar.
        d["logits"] = [[5,0,0], [5,0,0], [1,0,0], [1,0,0], [1,0,0], [1,0,0]]
        self.gravar(self.ev, d)
        with patch.object(cb, "ajustar_temperatura", return_value=1.):
            r = ce.ajustar(self.ev, self.cal)
        self.assertAlmostEqual(r["cobertura_esperada"], 2/6)
        self.assertTrue(r["meta_nao_atingida"])
        with self.assertRaisesRegex(ValueError, "viável"):
            self.validar(r)
        # Sem acerto algum: limiar null, não sentinel 1.
        self.cal.unlink()
        d["logits"] = [[0,5,0], [0,5,0], [5,0,0], [5,0,0], [5,0,0], [5,0,0]]
        self.gravar(self.ev, d)
        with patch.object(cb, "ajustar_temperatura", return_value=1.):
            r = ce.ajustar(self.ev, self.cal)
        self.assertIsNone(r["limiar_sugerido"])
        with self.assertRaises(ValueError):
            self.validar(r)

    def test_alvos_fracionarios_e_ordem_ids_recusados(self):
        d = self.inferir()
        for alterado in (d | {"verdadeiros": [0.9]*6}, d | {"ids": list(reversed(d["ids"]))},
                         d | {"logits": [[1,2]]*6}):
            self.gravar(self.ev, alterado)
            with self.assertRaises(ValueError):
                ce.ajustar(self.ev, self.cal)
            self.assertFalse(self.cal.exists())

    def test_export_cli_schema3_e_recusa_antes_converter(self):
        bloco = self.preparar()
        saida = self.base / "modelo.tflite"
        def converter(modelo, entrada, destino, **kwargs):
            destino.write_bytes(b"TFLITE simulado")
            return "simulado"
        paridade = {"amostras": 8, "max_dif_logit": 0., "discordancias_top1": 0,
                    "shape_entrada": [1,96,57,3], "dtype_entrada": "float32", "shape_saida": [1,3]}
        argv = ["exportar.py", "--checkpoint", str(self.ckpt), "--saida", str(saida),
                "--calibracao", str(self.cal)]
        with patch.object(sys, "argv", argv), patch.object(ex, "converter", side_effect=converter) as conversor, \
                patch.object(ex, "_conferir_precisao", return_value={"tamanho_mb":0, "tipos_tensores":{"float32":1}}), \
                patch.object(ex, "conferir_paridade", return_value=paridade):
            ex.main()
            sidecar = saida.with_suffix(".json")
            self.assertEqual(json.loads(sidecar.read_text())["calibracao"], bloco)
            orig_modelo, orig_sidecar = saida.read_bytes(), sidecar.read_bytes()
            conversor.reset_mock()
            for precisao in ("float16", "dinamica"):
                with patch.object(sys, "argv", argv + ["--quantizacao", precisao]), \
                        self.assertRaisesRegex(SystemExit, "float32"):
                    ex.main()
            conversor.assert_not_called()
            self.ev.unlink()
            conversor.reset_mock()
            with self.assertRaisesRegex(SystemExit, "calibração recusada"):
                ex.main()
            conversor.assert_not_called()
            self.assertEqual(saida.read_bytes(), orig_modelo)
            self.assertEqual(sidecar.read_bytes(), orig_sidecar)


if __name__ == "__main__":
    unittest.main()
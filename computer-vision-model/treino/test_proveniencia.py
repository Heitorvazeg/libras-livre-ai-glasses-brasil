"""Regressões offline da frente A; arquivos e checkpoints sempre temporários."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

import numpy as np
import torch
import yaml

import pretreinar as pt
import modelo as mm
import proveniencia as pv
import ingest_pretreino as ip

AQUI = Path(__file__).resolve().parent
CFG = yaml.safe_load((AQUI.parent / "PoC" / "config.yaml").read_text(encoding="utf-8"))


def criar_corpus(destino: Path, n_pessoas=3, n_classes=3, reps=1, seed=3):
    """Fixture V-LIBRASIL simulada, com a cadeia vídeo → landmarks completa."""
    destino.mkdir(parents=True, exist_ok=True)
    raw = destino.parent / (destino.name + "-videos")
    raw.mkdir(exist_ok=True)
    rng = np.random.default_rng(seed)
    bundle = {"id": pv.BUNDLES["vlibrasil"], "indice_sha256": pv.hash_json("fixture")}
    for p in range(1, n_pessoas + 1):
        for c in range(n_classes):
            for rep in range(1, reps + 1):
                stem = f"pessoaV{p:02d}_sinal-classe{c}_rep{rep:02d}"
                video, lm = raw / (stem + ".mp4"), destino / (stem + ".npy")
                conteudo = f"vídeo SINTÉTICO {seed}/{stem}".encode()
                video.write_bytes(conteudo)
                pv.registrar_video(video, fonte="vlibrasil", origem=f"fixture/{stem}.mp4",
                                   bundle=bundle, tamanho=len(conteudo),
                                   crc32=zlib.crc32(conteudo))
                seq = rng.normal(c * .1, .2, (12, len(CFG["pose_indices"]) + 42, 3))
                np.save(lm, seq.astype(np.float32))
                pv.registrar_landmarks(video, lm, CFG)


class TestProveniencia(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.corpus = self.base / "corpus"
        criar_corpus(self.corpus)
        self.avaliacao = self.base / "avaliacao"
        self.avaliacao.mkdir()

    def auditar(self, dirs=None):
        return pt.auditar_corpora(dirs or [self.corpus], avaliacao=self.avaliacao)

    def test_origem_herdada_e_hash(self):
        p = next(self.corpus.glob("*.npy"))
        r = pv.ler(p)
        self.assertTrue(r["origem"].startswith("fixture/"))
        self.assertEqual(r["extracao"]["dims"], 3)
        self.assertEqual(r["bundle"]["id"], pv.BUNDLES["vlibrasil"])
        self.assertEqual(len(self.auditar()["amostras"]), 9)
        p.write_bytes(p.read_bytes() + b"alteracao")
        with self.assertRaisesRegex(ValueError, "hash divergente"):
            self.auditar()

    def test_diretorios_repetidos_e_symlink(self):
        alias = self.base / "alias"
        alias.symlink_to(self.corpus, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "repetido"):
            self.auditar([self.corpus, alias])

    def test_reservado_por_origem_nao_por_rotulo(self):
        p = next(self.corpus.glob("*.npy"))
        r = pv.ler(p)
        reserva = next(r for r in pv.ler_reservas() if r["fonte"] == "vlibrasil")
        r["origem"] = reserva["origem"]
        pv.escrever(pv.sidecar(p), r)
        with self.assertRaisesRegex(ValueError, "reservado para avaliação"):
            self.auditar()

    def test_landmarks_duplicados(self):
        a, b = sorted(self.corpus.glob("*.npy"))[:2]
        r = pv.ler(b)
        b.write_bytes(a.read_bytes())
        r["landmarks"]["sha256"] = pv.hash_arquivo(b)
        pv.escrever(pv.sidecar(b), r)
        with self.assertRaisesRegex(ValueError, "duplicada por origem/hash"):
            self.auditar()

    def test_hash_igual_avaliacao(self):
        p = next(self.corpus.glob("*.npy"))
        (self.avaliacao / "outro-nome.npy").write_bytes(p.read_bytes())
        with self.assertRaisesRegex(ValueError, "reservado para avaliação"):
            self.auditar()

    def test_sem_proveniencia_e_sem_manifesto(self):
        p = next(self.corpus.glob("*.npy"))
        pv.sidecar(p).unlink()
        with self.assertRaisesRegex(ValueError, "proveniência ausente"):
            self.auditar()
        with patch.object(ip, "MANIFESTO", self.base / "ausente.csv"), \
                patch.object(ip, "zip_do_kaggle") as rede, \
                patch.object(sys, "argv", ["ingest_pretreino.py", "--listar"]):
            with self.assertRaisesRegex(SystemExit, "manifesto.*ausente"):
                ip.main()
            rede.assert_not_called()

    def test_minds_rejeitado_por_subprocess(self):
        minds = self.base / "minds"
        minds.mkdir()
        np.save(minds / "pessoaM01_sinal-ajuda_rep01.npy", np.zeros((6, 57, 3)))
        r = subprocess.run([sys.executable, str(AQUI / "pretreinar.py"),
                            "--corpus", str(minds), "--auditar"],
                           capture_output=True, text=True, timeout=60)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("MINDS (prefixo M) proibido", r.stderr)

    def test_migracao_legada_offline_retomavel(self):
        import registrar_legado as legado
        raw = self.corpus.parent / (self.corpus.name + "-videos")
        membros = []
        for video in sorted(raw.glob("*.mp4")):
            pessoa, sinal, rep = pv.identidade_nome(video)
            conteudo = video.read_bytes()
            origem = f"videos UFPE (V-LIBRASIL)/data/{sinal}_Articulador{int(pessoa[1:])}.mp4"
            membros.append({"nome": origem, "tamanho": len(conteudo),
                            "tamanho_comprimido": len(conteudo), "metodo": 0,
                            "offset_cabecalho": 0, "crc": zlib.crc32(conteudo)})
            pv.sidecar(video).unlink()
        antes = {p.name: pv.hash_arquivo(p) for p in self.corpus.glob("*.npy")}
        for p in self.corpus.glob("*.npy"):
            pv.sidecar(p).unlink()
        indice = self.base / "indice.json"
        indice.write_text(json.dumps({"membros": membros}), encoding="utf-8")
        self.assertEqual(legado.registrar(raw, indice, "vlibrasil", self.corpus, CFG), 9)
        self.assertEqual(legado.registrar(raw, indice, "vlibrasil", self.corpus, CFG), 9)
        for p in self.corpus.glob("*.npy"):
            self.assertEqual(pv.hash_arquivo(p), antes[p.name])
            self.assertTrue(pv.ler(p)["extracao"]["config_declarada_para_legado"])
        self.assertEqual(len(self.auditar()["amostras"]), 9)
        r = subprocess.run([sys.executable, str(Path(legado.__file__)),
                            "--videos", str(raw), "--indice", str(indice),
                            "--fonte", "vlibrasil", "--landmarks", str(self.corpus)],
                           capture_output=True, text=True, timeout=60)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("confirmar-config-legada", r.stderr)

    def test_auditar_nao_constroi_modelo(self):
        with patch.object(mm, "construir") as modelo, \
                patch.object(sys, "argv", ["pretreinar.py", "--corpus", str(self.corpus),
                                           "--auditar", "--threads", "2"]):
            pt.main()
            modelo.assert_not_called()

    def test_extracao_herda_sidecar_antes_de_descartar_video(self):
        from contextlib import nullcontext
        from types import SimpleNamespace
        sys.path.insert(0, str(AQUI.parent / "PoC" / "src"))
        import extract
        video = next((self.corpus.parent / (self.corpus.name + "-videos")).glob("*.mp4"))
        entrada = self.base / "entrada-unica"
        entrada.mkdir()
        copia = entrada / video.name
        copia.write_bytes(video.read_bytes())
        pv.escrever(pv.sidecar(copia), pv.ler(video))
        saida = self.base / "extraidos"
        fake_mp = SimpleNamespace(solutions=SimpleNamespace(holistic=SimpleNamespace(
            Holistic=lambda **kw: nullcontext(object()))))
        seq = np.ones((12, len(CFG["pose_indices"]) + 42, 3), dtype=np.float32)
        with patch.dict(sys.modules, {"mediapipe": fake_mp}), \
                patch.object(extract, "extrair_video", return_value=(seq, 0)), \
                patch.object(sys, "argv", ["extract.py", "--entrada", str(entrada),
                                           "--saida", str(saida), "--descartar-video"]):
            extract.main()
        self.assertFalse(copia.exists())
        meta = pv.ler(saida / (video.stem + ".npy"))
        self.assertEqual(meta["video"]["sha256"], pv.hash_arquivo(video))
        self.assertFalse(meta["extracao"]["config_declarada_para_legado"])

    def test_checkpoint_com_inventario_particao_e_codigo(self):
        saida = self.base / "saida"
        # P maior que o corpus exercita também o batch efetivo no chamador.
        r = subprocess.run([sys.executable, str(AQUI / "pretreinar.py"),
                            "--corpus", str(self.corpus), "--arquitetura", "gcn",
                            "--objetivo", "contrastivo", "--epocas", "1",
                            "--p-classes", "32", "--workers", "0", "--threads", "2",
                            "--dispositivo", "cpu", "--saida", str(saida)],
                           capture_output=True, text=True, timeout=180)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        d = torch.load(saida / "backbone_gcn.pt", weights_only=False, map_location="cpu")
        prov = d["meta"]["proveniencia"]
        self.assertEqual(prov["dados"]["manifesto_corpus_sha256"],
                         pv.hash_json(prov["dados"]["amostras"]))
        partes = prov["particao"]
        self.assertFalse(set(partes["treino"]) & set(partes["validacao"]))
        self.assertEqual(len(partes["otimizacao_elegiveis"]), 6)
        self.assertEqual(len(partes["validacao"]), 3)
        self.assertTrue(prov["codigo"]["fontes"])
        self.assertEqual(prov["codigo"]["fontes_sha256"], pv.hash_json(prov["codigo"]["fontes"]))
        self.assertEqual(prov["config"], CFG)
        self.assertFalse(any(k.startswith("fc.") for k in d["backbone"]))
        paralelo = json.loads((saida / "backbone_gcn.json").read_text(encoding="utf-8"))
        self.assertEqual(paralelo["proveniencia"], prov)
        # Saver genérico também guarda proveniência sem alterar o contrato meta.
        modelo = mm.construir(2, pretreinado=False)
        mm.salvar(modelo, saida / "final.pt", ["a", "b"], {"origem": "teste"})
        final = torch.load(saida / "final.pt", weights_only=False, map_location="cpu")
        self.assertEqual(final["meta"], {"origem": "teste"})
        self.assertTrue(final["proveniencia"]["codigo"]["fontes"])
        inventario = mm.inventario_final(self.corpus, pt.carregar_corpora([self.corpus], 2))
        self.assertEqual(len(inventario["amostras"]), 9)
        self.assertTrue(all(r["origem_status"] == "verificada" for r in inventario["amostras"]))
        mm.salvar(modelo, saida / "final.pt", ["a", "b"],
              {"proveniencia": prov, "args": {"inicializar": str(saida / "backbone_gcn.pt")}})
        final = torch.load(saida / "final.pt", weights_only=False, map_location="cpu")
        pai = final["proveniencia"]["inicializacao"]
        self.assertEqual(pai["sha256"], pv.hash_arquivo(saida / "backbone_gcn.pt"))
        self.assertEqual(pai["meta"]["proveniencia"], prov)


def executar():
    resultado = unittest.TextTestRunner(verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(TestProveniencia))
    if not resultado.wasSuccessful():
        raise AssertionError("regressões de isolamento/proveniência falharam")


if __name__ == "__main__":
    executar()
"""Cópia conservadora de corpus: nenhum original removido ou rótulo inventado."""
import tempfile
import unittest
import zlib
from pathlib import Path

import numpy as np

import preparar_corpus_auditado as pc
import proveniencia as pv


class TestPreparacao(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.entrada = self.base / "original"
        self.entrada.mkdir()
        self.saida = self.base / "auditado"
        cfg = {"pose_indices": {"nariz": 0}, "holistic": {}, "normalizacao": {}}
        for i, conteudo in enumerate((b"duplicado", b"duplicado", b"unico")):
            stem = f"pessoaV0{i+1}_sinal-classe{i}_rep01"
            video, arr = self.base / (stem + ".mp4"), self.entrada / (stem + ".npy")
            video.write_bytes(conteudo)
            pv.registrar_video(video, fonte="vlibrasil", origem=f"fixture/{stem}",
                               bundle={"id": pv.BUNDLES["vlibrasil"], "indice_sha256": "a" * 64},
                               tamanho=len(conteudo), crc32=zlib.crc32(conteudo))
            np.save(arr, np.full((4, 43, 3), i, dtype=np.float32))
            pv.registrar_landmarks(video, arr, cfg)

    def test_exclui_grupo_inteiro_sem_tocar_originais(self):
        antes = {p.name: p.read_bytes() for p in self.entrada.iterdir()}
        plano = pc.planejar(self.entrada)
        self.assertEqual(len(plano["excluidos"]), 2)
        self.assertEqual(len(plano["mantidos"]), 1)
        pc.preparar(self.entrada, self.saida, plano)
        self.assertEqual(antes, {p.name: p.read_bytes() for p in self.entrada.iterdir()})
        for p in self.saida.glob("*.npy"):
            self.assertEqual(p.read_bytes(), antes[p.name])
            self.assertEqual(pv.sidecar(p).read_bytes(), antes[pv.sidecar(p).name])
        self.assertTrue((self.saida / "preparacao.json").is_file())
        with self.assertRaisesRegex(ValueError, "destino já existe"):
            pc.preparar(self.entrada, self.saida, plano)

    def test_sem_sidecar_nao_copia(self):
        pv.sidecar(next(self.entrada.glob("*.npy"))).unlink()
        with self.assertRaisesRegex(ValueError, "proveniência ausente"):
            pc.planejar(self.entrada)
        self.assertFalse(self.saida.exists())

    def test_plano_alterado_nao_copia(self):
        plano = pc.planejar(self.entrada)
        plano["mantidos"].append(plano["excluidos"][0])
        with self.assertRaisesRegex(ValueError, "entrada mudou"):
            pc.preparar(self.entrada, self.saida, plano)
        self.assertFalse(self.saida.exists())

    def test_destino_dentro_da_origem_rejeitado(self):
        with self.assertRaisesRegex(ValueError, "dentro do corpus"):
            pc.preparar(self.entrada, self.entrada / "filho", pc.planejar(self.entrada))


if __name__ == "__main__":
    unittest.main(verbosity=2)
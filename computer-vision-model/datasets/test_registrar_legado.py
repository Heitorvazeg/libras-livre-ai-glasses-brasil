"""Migração offline: colisões de slug só se resolvem pelo conteúdo do vídeo."""
import json
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

import registrar_legado as legado
import proveniencia as pv


class TestColisaoLegada(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.videos = self.base / "videos"
        self.videos.mkdir()
        self.indice = self.base / "indice.json"
        self.video = self.videos / "pessoaV01_sinal-avo_rep01.mp4"
        self.video.write_bytes(b"avo-masculino")
        self.membros = []
        for palavra, conteudo in [("Avó", b"avo-feminino!"), ("Avô", b"avo-masculino")]:
            self.membros.append({
                "nome": f"videos UFPE (V-LIBRASIL)/data/{palavra}_Articulador1.mp4",
                "tamanho": len(conteudo), "tamanho_comprimido": len(conteudo),
                "metodo": 0, "offset_cabecalho": 0, "crc": zlib.crc32(conteudo),
            })
        self.reservas = patch.object(pv, "ler_reservas", return_value=[])
        self.reservas.start()
        self.addCleanup(self.reservas.stop)

    def registrar(self):
        self.indice.write_text(json.dumps({"membros": self.membros}), encoding="utf-8")
        return legado.registrar(self.videos, self.indice, "vlibrasil")

    def test_colisao_resolvida_por_crc_e_retomavel(self):
        antes = pv.hash_arquivo(self.video)
        self.assertEqual(self.registrar(), 1)
        self.assertEqual(self.registrar(), 1)
        self.assertEqual(pv.hash_arquivo(self.video), antes)
        self.assertEqual(pv.ler(self.video)["origem"], self.membros[1]["nome"])

    def test_colisao_com_bytes_iguais_permanece_ambigua(self):
        self.membros[0]["tamanho"] = self.membros[1]["tamanho"]
        self.membros[0]["crc"] = self.membros[1]["crc"]
        with self.assertRaisesRegex(ValueError, "origem ambígua"):
            self.registrar()
        self.assertFalse(pv.sidecar(self.video).exists())

    def test_nenhum_crc_compativel_nao_inventa_origem(self):
        self.video.write_bytes(b"arquivo corrompido")
        with self.assertRaisesRegex(ValueError, "nenhuma origem.*tamanho/CRC"):
            self.registrar()
        self.assertFalse(pv.sidecar(self.video).exists())

    def test_colisao_fora_do_subconjunto_nao_bloqueia(self):
        self.video.rename(self.videos / "pessoaV01_sinal-ola_rep01.mp4")
        ola = dict(self.membros[1], nome="videos UFPE (V-LIBRASIL)/data/Ola_Articulador1.mp4")
        self.membros.append(ola)
        self.assertEqual(self.registrar(), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
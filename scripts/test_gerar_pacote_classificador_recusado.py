from pathlib import Path
import json
import struct
import tempfile
import unittest

import gerar_pacote_classificador_recusado as gerador
import pacote_classificador_privado as pacote


class TestGeradorRecusado(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.raiz = Path(tmp.name)

    def test_reproduzivel_hash_consistente_e_contrato_20_sem_grafo(self):
        a, b = self.raiz / "a", self.raiz / "b"
        self.assertEqual(gerador.gerar(a), gerador.gerar(b))
        self.assertEqual({p.name for p in a.iterdir()}, pacote.ARQUIVOS)
        for nome in pacote.ARQUIVOS:
            self.assertEqual((a / nome).read_bytes(), (b / nome).read_bytes())
        modelo = (a / pacote.MODELO).read_bytes()
        self.assertEqual(modelo[4:8], b"TFL3")
        self.assertGreater(struct.unpack("<I", modelo[:4])[0], len(modelo))
        self.assertEqual(pacote.verificar(a)["experimento"], gerador.EXPERIMENTO)
        sidecar = json.loads((a / pacote.SIDECAR).read_bytes())
        self.assertEqual(len(set(sidecar["rotulos"])), 20)
        self.assertEqual(sidecar["contrato_entrada"]["shape"], [1, 96, 57, 3])
        self.assertNotIn("calibracao", sidecar)
        self.assertEqual(sidecar["origem"]["sha256"], pacote.sha256(gerador.CHECKPOINT_SINTETICO))

    def test_nao_sobrescreve_nem_diretorio_vazio(self):
        for nome in ("vazio", "ocupado"):
            destino = self.raiz / nome
            destino.mkdir()
            if nome == "ocupado":
                (destino / "alheio").write_bytes(b"preservar")
            antes = {p.name: p.read_bytes() for p in destino.iterdir()}
            with self.assertRaises(FileExistsError):
                gerador.gerar(destino)
            self.assertEqual(antes, {p.name: p.read_bytes() for p in destino.iterdir()})

    def test_recusa_relativo_versionado_e_symlink(self):
        for destino in (Path("relativo"), pacote.REPO / "scripts/nao-gerar"):
            with self.assertRaises(ValueError):
                gerador.gerar(destino)
        link = self.raiz / "link"
        link.symlink_to(self.raiz, target_is_directory=True)
        with self.assertRaises(ValueError):
            gerador.gerar(link / "novo")
        self.assertFalse((self.raiz / "novo").exists())

    def test_adulteracao_nao_passa_por_integridade(self):
        destino = self.raiz / "pacote"
        gerador.gerar(destino)
        (destino / pacote.MODELO).write_bytes(gerador.MODELO_INVALIDO + b"alterado")
        with self.assertRaises(ValueError):
            pacote.verificar(destino)


if __name__ == "__main__":
    unittest.main()
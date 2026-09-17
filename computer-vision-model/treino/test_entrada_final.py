"""Regressões sintéticas completas (800 NPY pequenos), sem treino ou rede."""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np

import entrada_final as entrada


class EntradaFinalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.modelo_tmp = tempfile.TemporaryDirectory()
        cls.modelo = Path(cls.modelo_tmp.name) / "modelo"
        cls.modelo.mkdir()
        # Independente das constantes do produtor para detectar mudanças da receita.
        cls.pessoas = ("M01", "M02", "M05", "M06", "M08", "M10", "M11", "M12")
        cls.sinais = (
            "acontecer", "amarelo", "banheiro", "barulho", "espelho", "filho",
            "maca", "medo", "ruim", "sapo", "aluno", "america", "aproveitar",
            "bala", "banco", "cinco", "conhecer", "esquina", "vacina", "vontade",
        )
        for pessoa in cls.pessoas:
            for sinal in cls.sinais:
                for rep in range(1, 6):
                    nome = f"pessoa{pessoa}_sinal-{sinal}_rep{rep:02d}.npy"
                    np.save(cls.modelo / nome, np.full((3, 57, 3), rep / 10, dtype=np.float32))
        cls.nome = "pessoaM01_sinal-acontecer_rep01.npy"

    @classmethod
    def tearDownClass(cls):
        cls.modelo_tmp.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.raiz = Path(self.tmp.name)
        self.origem = self.raiz / "origem"
        shutil.copytree(self.modelo, self.origem)
        self.destino = self.raiz / "preparado"
        self.clipe = self.origem / self.nome

    def sidecar(self, pasta=None):
        pasta = self.origem if pasta is None else pasta
        caminho = pasta / (self.nome + ".proveniencia.json")
        caminho.write_text('{"extrator":"legado-nao-certificado"}', encoding="utf-8")
        return caminho

    def pacote(self, *, membro=None, conteudo=b"", tipo=tarfile.REGTYPE, raiz=True):
        caminho = self.raiz / "entrada.tar.gz"
        with tarfile.open(caminho, "w:gz") as tar:
            if raiz:
                info = tarfile.TarInfo("landmarks")
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            for p in sorted(self.origem.iterdir()):
                tar.add(p, arcname="landmarks/" + p.name, recursive=False)
            if membro is not None:
                info = tarfile.TarInfo(membro)
                info.type = tipo
                info.linkname = "../../fora"
                info.size = len(conteudo) if tipo == tarfile.REGTYPE else 0
                tar.addfile(info, io.BytesIO(conteudo) if tipo == tarfile.REGTYPE else None)
        return caminho

    def assert_sem_instalacao(self):
        self.assertFalse(self.destino.exists())
        self.assertEqual(list(self.raiz.glob(".minds-v1-*")), [])

    def test_vocabulario_config_contem_as_vinte_classes_explicitas(self):
        config = (Path(__file__).resolve().parents[1] / "PoC" / "config.yaml").read_text()
        # Leitura restrita deste bloco escalar, sem dependência de PyYAML.
        bloco = config.split("\nvocabulario:\n", 1)[1]
        rotulos = []
        for linha in bloco.splitlines():
            sem_comentario = linha.split("#", 1)[0].rstrip()
            if sem_comentario and not sem_comentario[0].isspace():
                break
            if sem_comentario.strip().startswith("- "):
                rotulos.append(sem_comentario.strip()[2:])
        self.assertGreaterEqual(len(rotulos), 20)
        self.assertTrue(set(self.sinais).issubset(rotulos))
        self.assertEqual(set(entrada.ROTULOS_MINDS), set(self.sinais))
        self.assertEqual(entrada.PESSOAS_MINDS, self.pessoas)

    def test_completo_hashes_inventario_sidecar_sem_certificar_legado(self):
        sidecar = self.sidecar()
        (self.origem / ".gitkeep").write_bytes(b"preservar estes bytes\n")
        inventario = entrada.validar_minds(self.origem)
        self.assertEqual(inventario["n_clipes"], 800)
        self.assertEqual(len(inventario["amostras"]), 800)
        self.assertEqual(len(inventario["arquivos"]), 802)
        self.assertEqual(inventario["pessoas"], list(self.pessoas))
        self.assertEqual(inventario["rotulos"], sorted(self.sinais))
        self.assertFalse(inventario["proveniencia_certificada"])
        primeira = next(a for a in inventario["amostras"] if a["arquivo"] == self.nome)
        self.assertEqual(primeira, {"arquivo": self.nome, "pessoa": "M01",
                                   "sinal": "acontecer", "rep": "01",
                                   "sha256": hashlib.sha256(self.clipe.read_bytes()).hexdigest()})
        arquivos = [{"arquivo": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
                     "tamanho": p.stat().st_size} for p in sorted(self.origem.iterdir())]
        self.assertEqual(inventario["arquivos"], arquivos)
        canonico = json.dumps(arquivos, sort_keys=True, ensure_ascii=False,
                              separators=(",", ":"), allow_nan=False).encode()
        self.assertEqual(inventario["corpus_sha256"], hashlib.sha256(canonico).hexdigest())
        self.assertTrue(sidecar.name in {a["arquivo"] for a in arquivos})
        self.assertEqual(inventario, entrada.validar_minds(self.origem))

    def test_faltante(self):
        self.clipe.unlink()
        with self.assertRaisesRegex(ValueError, "faltantes"):
            entrada.preparar_minds(self.origem, self.destino)
        self.assert_sem_instalacao()

    def test_extras_e_nomes_incorretos(self):
        for nome in ("pessoaM03_sinal-acontecer_rep01.npy", "pessoaV01_sinal-filho_rep01.npy",
                     "pessoaM01_sinal-ola_rep01.npy", "pessoaM01_sinal-filho_rep06.npy",
                     "pessoaM01_sinal-filho_rep1.npy", "errado.npy", "README.txt",
                     "errado.npy.proveniencia.json"):
            with self.subTest(nome=nome):
                extra = self.origem / nome
                extra.write_bytes(self.clipe.read_bytes())
                try:
                    with self.assertRaisesRegex(ValueError, "extra/nome inválido"):
                        entrada.validar_minds(self.origem)
                finally:
                    extra.unlink()

    def test_tipo_nan_infinito_shape_curto_pickle_e_bytes_extras(self):
        original = self.clipe.read_bytes()
        invalidos = [np.zeros((3, 57, 3), dtype=np.float64),
                     np.zeros((3, 57, 3), dtype=np.int32),
                     np.full((3, 57, 3), np.nan, dtype=np.float32),
                     np.full((3, 57, 3), np.inf, dtype=np.float32),
                     np.zeros((3, 56, 3), dtype=np.float32),
                     np.zeros((3, 57, 2), dtype=np.float32),
                     np.zeros((3, 57), dtype=np.float32),
                     np.zeros((2, 57, 3), dtype=np.float32),
                     np.zeros((0, 57, 3), dtype=np.float32),
                     np.zeros((3, 57, 3), dtype=object)]
        for arr in invalidos:
            with self.subTest(dtype=arr.dtype, shape=arr.shape, finito=arr.dtype != object):
                np.save(self.clipe, arr)
                with self.assertRaisesRegex(ValueError, "Clipe inválido"):
                    entrada.validar_minds(self.origem)
        for bruto in (original[:-1], original + b"extra", b"nao-npy"):
            self.clipe.write_bytes(bruto)
            with self.assertRaisesRegex(ValueError, "Clipe inválido"):
                entrada.validar_minds(self.origem)

    def test_np_load_explicita_allow_pickle_false(self):
        with patch.object(entrada.np, "load", wraps=np.load) as load:
            entrada.validar_minds(self.origem)
        self.assertEqual(load.call_count, 800)
        self.assertTrue(all(c.kwargs.get("allow_pickle") is False for c in load.call_args_list))

    def test_sidecar_invalido_e_orfao(self):
        sidecar = self.sidecar()
        for bruto in (b"{}", b"[]", b"null", b"nao-json", b"\xff"):
            sidecar.write_bytes(bruto)
            with self.assertRaisesRegex(ValueError, "Sidecar"):
                entrada.validar_minds(self.origem)
        self.sidecar()
        self.clipe.unlink()
        with self.assertRaises(ValueError):
            entrada.validar_minds(self.origem)

    def test_symlinks_subpasta_fifo_recusados(self):
        self.clipe.unlink()
        self.clipe.symlink_to(self.modelo / self.nome)
        with self.assertRaisesRegex(ValueError, "regular"):
            entrada.validar_minds(self.origem)
        self.clipe.unlink()
        self.clipe.mkdir()
        with self.assertRaisesRegex(ValueError, "regular"):
            entrada.validar_minds(self.origem)
        self.clipe.rmdir()
        os.mkfifo(self.clipe)
        with self.assertRaisesRegex(ValueError, "regular"):
            entrada.validar_minds(self.origem)

    def test_links_na_origem_destino_e_ancestrais(self):
        alias = self.raiz / "alias"
        alias.symlink_to(self.origem, target_is_directory=True)
        for caminho in (alias, alias / "subpasta"):
            with self.assertRaisesRegex(ValueError, "simbólico"):
                entrada.preparar_minds(caminho, self.destino)
        alias.unlink()
        alias.symlink_to(self.raiz / "inexistente")
        for destino in (alias, alias / "novo"):
            with self.assertRaisesRegex(ValueError, "simbólico"):
                entrada.preparar_minds(self.origem, destino)
        self.assert_sem_instalacao()

    def test_instalacao_atomica_sem_alterar_origem_e_reuso_intacto(self):
        self.sidecar()
        (self.origem / ".gitkeep").touch()
        antes = entrada.validar_minds(self.origem)
        stats = {p.name: (p.stat().st_ino, p.stat().st_mtime_ns) for p in self.origem.iterdir()}
        rename_original = Path.rename
        observados = []

        def observar_rename(path, target):
            self.assertEqual(Path(target), self.destino)
            self.assertFalse(self.destino.exists())
            self.assertTrue((path / "preparacao.json").is_file())
            self.assertEqual(entrada.validar_minds(path / "landmarks"), antes)
            observados.append(path)
            return rename_original(path, target)

        with patch.object(Path, "rename", observar_rename):
            pasta = entrada.preparar_minds(self.origem, self.destino)
        self.assertEqual(len(observados), 1)
        self.assertEqual(pasta, self.destino / "landmarks")
        self.assertEqual(entrada.validar_minds(pasta), antes)
        self.assertEqual(entrada.validar_minds(self.origem), antes)
        self.assertEqual(stats, {p.name: (p.stat().st_ino, p.stat().st_mtime_ns)
                                 for p in self.origem.iterdir()})
        registro = self.destino / "preparacao.json"
        registrados = {p: (p.stat().st_ino, p.stat().st_mtime_ns)
                       for p in [registro, pasta, *pasta.iterdir()]}
        bruto = registro.read_bytes()
        self.assertEqual(json.loads(bruto)["inventario"], antes)
        self.assertEqual(entrada.preparar_minds(self.origem, self.destino), pasta)
        self.assertEqual(registro.read_bytes(), bruto)
        self.assertEqual(registrados, {p: (p.stat().st_ino, p.stat().st_mtime_ns)
                                      for p in registrados})
        self.assertEqual(list(self.raiz.glob(".minds-v1-*")), [])

    def test_destino_vazio_preexistente_nao_e_instalacao(self):
        self.destino.mkdir()
        with self.assertRaisesRegex(ValueError, "estrutura/registro"):
            entrada.preparar_minds(self.origem, self.destino)
        self.assertEqual(list(self.destino.iterdir()), [])

    def test_mutacao_npy_valido_no_destino_nao_e_reparada(self):
        pasta = entrada.preparar_minds(self.origem, self.destino)
        adulterado = pasta / self.nome
        np.save(adulterado, np.ones((3, 57, 3), dtype=np.float32))
        bruto = adulterado.read_bytes()
        with self.assertRaisesRegex(ValueError, "Destino adulterado"):
            entrada.preparar_minds(self.origem, self.destino)
        self.assertEqual(adulterado.read_bytes(), bruto)
        self.assertNotEqual(self.clipe.read_bytes(), bruto)

    def test_mutacao_remocao_e_adicao_sidecar_e_gitkeep_no_destino(self):
        self.sidecar()
        (self.origem / ".gitkeep").write_bytes(b"original")
        pasta = entrada.preparar_minds(self.origem, self.destino)
        for nome in (self.nome + ".proveniencia.json", ".gitkeep"):
            p = pasta / nome
            original = p.read_bytes()
            for valor in (b'{"mudou":true}', None):
                with self.subTest(nome=nome, valor=valor):
                    if valor is None:
                        p.unlink()
                    else:
                        p.write_bytes(valor)
                    with self.assertRaisesRegex(ValueError, "Destino adulterado"):
                        entrada.preparar_minds(self.origem, self.destino)
                    p.write_bytes(original)
        novo = pasta / "pessoaM02_sinal-filho_rep02.npy.proveniencia.json"
        novo.write_bytes(b'{"extra":true}')
        with self.assertRaisesRegex(ValueError, "Destino adulterado"):
            entrada.preparar_minds(self.origem, self.destino)

    def test_registro_alterado_ausente_ou_simbolico(self):
        entrada.preparar_minds(self.origem, self.destino)
        registro = self.destino / "preparacao.json"
        original = registro.read_bytes()
        for valor in (b"{}", original + b" "):
            registro.write_bytes(valor)
            with self.assertRaisesRegex(ValueError, "Registro diverge"):
                entrada.preparar_minds(self.origem, self.destino)
        registro.unlink()
        with self.assertRaisesRegex(ValueError, "estrutura/registro"):
            entrada.preparar_minds(self.origem, self.destino)
        fora = self.raiz / "registro.json"
        fora.write_bytes(original)
        registro.symlink_to(fora)
        with self.assertRaisesRegex(ValueError, "regular"):
            entrada.preparar_minds(self.origem, self.destino)

    def test_mesmos_bytes_outra_entrada_e_saida_movida_nao_reusam(self):
        entrada.preparar_minds(self.origem, self.destino)
        outra = self.raiz / "outra-origem"
        shutil.copytree(self.origem, outra)
        with self.assertRaisesRegex(ValueError, "Registro diverge"):
            entrada.preparar_minds(outra, self.destino)
        movido = self.raiz / "movido"
        self.destino.rename(movido)
        with self.assertRaisesRegex(ValueError, "Registro diverge"):
            entrada.preparar_minds(self.origem, movido)

    def test_origem_alterada_npy_e_sidecar_impede_reuso(self):
        sidecar = self.sidecar()
        entrada.preparar_minds(self.origem, self.destino)
        for p, novo in ((sidecar, b'{"versao":2}'), (self.clipe, self.clipe.read_bytes() + b"x")):
            original = p.read_bytes()
            p.write_bytes(novo)
            with self.assertRaises(ValueError):
                entrada.preparar_minds(self.origem, self.destino)
            p.write_bytes(original)
        np.save(self.clipe, np.ones((3, 57, 3), dtype=np.float32))
        with self.assertRaisesRegex(ValueError, "Registro diverge"):
            entrada.preparar_minds(self.origem, self.destino)

    def test_manifesto_referencia_dict_e_json(self):
        self.sidecar()
        referencia = entrada.validar_minds(self.origem)
        arquivo = self.raiz / "manifesto.json"
        arquivo.write_text(json.dumps(referencia), encoding="utf-8")
        self.assertEqual(entrada.validar_minds(self.origem, manifesto_referencia=arquivo), referencia)
        entrada.preparar_minds(self.origem, self.destino, manifesto_referencia=referencia)
        self.sidecar().write_bytes(b'{"versao":2}')
        with self.assertRaisesRegex(ValueError, "Manifesto de referência diverge"):
            entrada.validar_minds(self.origem, manifesto_referencia=referencia)
        novo_destino = self.raiz / "referencia-recusada"
        with self.assertRaisesRegex(ValueError, "Manifesto de referência diverge"):
            entrada.preparar_minds(self.origem, novo_destino, manifesto_referencia=arquivo)
        self.assertFalse(novo_destino.exists())

    def test_sobreposicao_recusada_sem_modificar_entrada(self):
        antes = entrada.validar_minds(self.origem)
        for origem, destino in ((self.origem, self.origem),
                                (self.origem, self.origem / "saida"),
                                (self.origem, self.raiz),
                                (self.clipe, self.origem)):
            with self.subTest(origem=origem, destino=destino):
                with self.assertRaisesRegex(ValueError, "conter um ao outro"):
                    entrada.preparar_minds(origem, destino)
        self.assertEqual(entrada.validar_minds(self.origem), antes)

    def test_tar_completo_preserva_bytes_e_reusa(self):
        self.sidecar()
        (self.origem / ".gitkeep").touch()
        pacote = self.pacote()
        bruto = pacote.read_bytes()
        pasta = entrada.preparar_minds(pacote, self.destino)
        self.assertEqual(entrada.validar_minds(pasta), entrada.validar_minds(self.origem))
        registro = json.loads((self.destino / "preparacao.json").read_bytes())
        self.assertEqual(registro["origem"]["sha256"], hashlib.sha256(bruto).hexdigest())
        self.assertEqual(entrada.preparar_minds(pacote, self.destino), pasta)
        self.assertEqual(pacote.read_bytes(), bruto)
        # Troca de bytes do tar, mesmo quando a biblioteca ignora o sufixo.
        pacote.write_bytes(bruto + b"mudou")
        with self.assertRaisesRegex(ValueError, "Registro diverge"):
            entrada.preparar_minds(pacote, self.destino)

    def test_tar_sem_membro_diretorio_e_filter_data_explicito(self):
        pacote = self.pacote(raiz=False)
        original = tarfile.TarFile.extractall
        filtros = []

        def extrair(tar, *args, **kwargs):
            filtros.append(kwargs.get("filter"))
            return original(tar, *args, **kwargs)

        with patch.object(tarfile.TarFile, "extractall", extrair):
            entrada.preparar_minds(pacote, self.destino)
        self.assertEqual(filtros, ["data"])

    def test_tar_traversal_alias_nested_extra_duplicados_e_links(self):
        ataques = [
            ("../fora", tarfile.REGTYPE), ("landmarks/../../fora", tarfile.REGTYPE),
            (str(self.raiz / "fora"), tarfile.REGTYPE),
            ("./landmarks/" + self.nome, tarfile.REGTYPE),
            ("landmarks//" + self.nome, tarfile.REGTYPE),
            ("landmarks/./" + self.nome, tarfile.REGTYPE),
            ("landmarks/sub/" + self.nome, tarfile.REGTYPE),
            ("landmarks\\" + self.nome, tarfile.REGTYPE),
            ("outra/" + self.nome, tarfile.REGTYPE),
            ("landmarks/extra.npy", tarfile.REGTYPE),
            ("landmarks/README.txt", tarfile.REGTYPE),
            ("landmarks/" + self.nome, tarfile.REGTYPE),
            ("landmarks/", tarfile.DIRTYPE),
            ("landmarks/sub", tarfile.DIRTYPE),
            ("landmarks/.gitkeep", tarfile.SYMTYPE),
            ("landmarks/.gitkeep", tarfile.LNKTYPE),
            ("landmarks/.gitkeep", tarfile.FIFOTYPE),
            ("landmarks/.gitkeep", tarfile.CHRTYPE),
        ]
        for nome, tipo in ataques:
            with self.subTest(nome=nome, tipo=tipo):
                pacote = self.pacote(membro=nome, tipo=tipo)
                with self.assertRaises(ValueError):
                    entrada.preparar_minds(pacote, self.destino)
                self.assert_sem_instalacao()
                self.assertFalse((self.raiz / "fora").exists())

    def test_tar_validacao_array_e_cobertura_apos_extracao(self):
        np.save(self.clipe, np.zeros((2, 57, 3), dtype=np.float32))
        with self.assertRaisesRegex(ValueError, "Clipe inválido"):
            entrada.preparar_minds(self.pacote(), self.destino)
        self.assert_sem_instalacao()
        self.clipe.unlink()
        with self.assertRaisesRegex(ValueError, "faltantes"):
            entrada.preparar_minds(self.pacote(), self.destino)
        self.assert_sem_instalacao()

    def test_tar_sem_suporte_data_filter_nao_tem_fallback(self):
        pacote = self.pacote()
        with patch.object(tarfile, "data_filter"):
            del tarfile.data_filter
            with self.assertRaisesRegex(RuntimeError, "sem fallback inseguro"):
                entrada.preparar_minds(pacote, self.destino)
        self.assert_sem_instalacao()

    def test_tar_trocado_durante_extracao_nao_e_instalado(self):
        pacote = self.pacote()
        extrair = entrada._extrair_tar

        def adulterar(snapshot, staging):
            extrair(snapshot, staging)
            pacote.write_bytes(pacote.read_bytes() + b"mudou durante extracao")

        with patch.object(entrada, "_extrair_tar", adulterar):
            with self.assertRaisesRegex(ValueError, "Tar de origem mudou"):
                entrada.preparar_minds(pacote, self.destino)
        self.assert_sem_instalacao()

    def test_reuso_recusa_extra_faltante_e_landmarks_simbolico(self):
        pasta = entrada.preparar_minds(self.origem, self.destino)
        extra = self.destino / "outro-corpus"
        extra.mkdir()
        with self.assertRaisesRegex(ValueError, "estrutura/registro"):
            entrada.preparar_minds(self.origem, self.destino)
        self.assertTrue(extra.is_dir())
        extra.rmdir()
        clipe = pasta / self.nome
        clipe.unlink()
        with self.assertRaisesRegex(ValueError, "faltantes"):
            entrada.preparar_minds(self.origem, self.destino)
        self.assertFalse(clipe.exists())
        salvo = self.raiz / "landmarks-movidos"
        pasta.rename(salvo)
        pasta.symlink_to(self.origem, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "simbólico"):
            entrada.preparar_minds(self.origem, self.destino)
        self.assertTrue(pasta.is_symlink())

    def test_tar_invalido_origem_fifo_e_arquivo_destino(self):
        pacote = self.raiz / "invalido.tar"
        pacote.write_bytes(b"nao e tar")
        with self.assertRaisesRegex(ValueError, "Pacote tar inválido"):
            entrada.preparar_minds(pacote, self.destino)
        self.assert_sem_instalacao()
        pacote.unlink()
        os.mkfifo(pacote)
        with self.assertRaisesRegex(ValueError, "Origem irregular"):
            entrada.preparar_minds(pacote, self.destino)
        self.assert_sem_instalacao()
        self.destino.write_bytes(b"nao substituir")
        with self.assertRaisesRegex(ValueError, "Destino não é diretório"):
            entrada.preparar_minds(self.origem, self.destino)
        self.assertEqual(self.destino.read_bytes(), b"nao substituir")

    def test_falha_copia_ou_rename_limpa_staging(self):
        for alvo in ("entrada_final._copiar", "entrada_final.Path.rename"):
            with self.subTest(alvo=alvo):
                with patch(alvo, side_effect=OSError("falha simulada")):
                    with self.assertRaisesRegex(OSError, "falha simulada"):
                        entrada.preparar_minds(self.origem, self.destino)
                self.assert_sem_instalacao()

    def test_mutacao_durante_copia_recusada(self):
        copiar = entrada._copiar

        def adulterar(arquivo, destino):
            copiar(arquivo, destino)
            if arquivo.name == self.nome:
                np.save(arquivo, np.ones((3, 57, 3), dtype=np.float32))

        with patch.object(entrada, "_copiar", adulterar):
            with self.assertRaisesRegex(ValueError, "Origem mudou"):
                entrada.preparar_minds(self.origem, self.destino)
        self.assert_sem_instalacao()

    def test_pos_loader_real_completo_filtrado_duplicado_e_identidade_trocada(self):
        # Único import do loader: exercita o contrato real sem modificar dados.py.
        from dados import carregar

        inventario = entrada.validar_minds(self.origem)
        clipes = carregar(self.origem, fontes="minds", com_z=True, z_recentrado=True)
        self.assertIsNone(entrada.conferir_carregados(inventario, reversed(clipes)))
        with self.assertRaisesRegex(ValueError, "Loader alterou identidades"):
            entrada.conferir_carregados(inventario, clipes[:-1])
        with self.assertRaisesRegex(ValueError, "extras/duplicadas"):
            entrada.conferir_carregados(inventario, clipes + clipes[:1])
        trocados = clipes[:-1] + [clipes[0]]  # 800 não basta: multiconjunto exato.
        with self.assertRaisesRegex(ValueError, "Loader alterou identidades"):
            entrada.conferir_carregados(inventario, trocados)
        outro = SimpleNamespace(pessoa="M01", sinal="acontecer", rep="1")
        with self.assertRaisesRegex(ValueError, "Loader alterou identidades"):
            entrada.conferir_carregados(inventario, clipes[:-1] + [outro])
        with self.assertRaisesRegex(ValueError, "identidade textual"):
            entrada.conferir_carregados(inventario, [object()])
        filtrados = carregar(self.origem, fontes="vlibrasil")
        self.assertEqual(filtrados, [])
        with self.assertRaisesRegex(ValueError, "Loader alterou identidades"):
            entrada.conferir_carregados(inventario, filtrados)
        with self.assertRaisesRegex(ValueError, "Inventário"):
            entrada.conferir_carregados({**inventario, "amostras": inventario["amostras"][:-1]},
                                       clipes[:-1])


if __name__ == "__main__":
    unittest.main()
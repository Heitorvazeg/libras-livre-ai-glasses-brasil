"""Clipes externos no treino final: manifesto fechado e checkpoint rastreável."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import calibracao_externa as ce
import dados as dd
import extras_externos as ee
import modelo as mm
import treinar as tr


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


class Base(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        rng = np.random.default_rng(7)
        self.lm = self.base / "landmarks"
        self.lm.mkdir()
        for pessoa in (1, 2, 3):
            for sinal in (0, 1):
                for rep in (1, 2):
                    np.save(self.lm / f"pessoaM{pessoa:02d}_sinal-classe{sinal}_rep{rep:02d}.npy",
                            rng.normal(0, .2, (16, 57, 3)).astype(np.float32))
        self.raiz = self.base / "externos"
        itens = []
        for corpus, pasta, pessoa, classe in (("vlibrasil", "v", "V01", "classe0"),
                                              ("malta", "t", "T002", "classe1"),
                                              ("vlibrasil", "v", "V03", "classe1")):
            rel = f"{pasta}/pessoa{pessoa}_sinal-{classe}_rep01.npy"
            arq = self.raiz / rel
            arq.parent.mkdir(parents=True, exist_ok=True)
            np.save(arq, rng.normal(0, .2, (12, 57, 3)).astype(np.float32))
            itens.append({"classe": classe, "pessoa": pessoa, "corpus": corpus,
                          "arquivo": rel, "sha256": sha(arq)})
        self.pai = self.base / "pai.json"
        self.pai.write_text(json.dumps({
            "itens": itens,
            "divisao_pessoas": {"fixada_em": "2026-09-15",
                                "avaliacao": {"pessoas": ["V03"]},
                                "treino_externo": {"pessoas": ["T002", "V01"]}}}))
        self.man = self.base / "extras.json"
        self.man.write_text(json.dumps(ee.gerar(self.pai)))


class TestManifesto(Base):
    def test_gerar_exclui_avaliacao_e_ler_valida(self):
        d, arrays = ee.ler(self.man, self.raiz)
        self.assertEqual(d["pessoas"], ["T002", "V01"])
        self.assertEqual(d["origem"]["pessoas_excluidas"], ["V03"])
        self.assertEqual(d["origem"]["manifesto_sha256"], sha(self.pai))
        self.assertEqual(len(arrays), 2)

    def alterar(self, f):
        d = json.loads(self.man.read_text())
        f(d)
        self.man.write_text(json.dumps(d))

    def test_recusa_pessoa_de_avaliacao(self):
        pai = json.loads(self.pai.read_text())
        v03 = next(i for i in pai["itens"] if i["pessoa"] == "V03")
        self.alterar(lambda d: (d["itens"].append({k: v03[k] for k in
                                ("classe", "pessoa", "corpus", "arquivo", "sha256")}),
                                d.update(ee._com_contagens({"itens": d["itens"]}))))
        with self.assertRaisesRegex(ValueError, "avaliação"):
            ee.ler(self.man, self.raiz)

    def test_recusa_hash_alterado(self):
        self.alterar(lambda d: d["itens"][0].update(sha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "Hash"):
            ee.ler(self.man, self.raiz)

    def test_recusa_finalidade_e_contagem(self):
        self.alterar(lambda d: d.update(finalidade="calibracao_experimental"))
        with self.assertRaises(ValueError):
            ee.ler(self.man, self.raiz)

    def test_recusa_caminho_fora_da_raiz(self):
        self.alterar(lambda d: d["itens"][0].update(arquivo="../pai.json"))
        with self.assertRaises(ValueError):
            ee.ler(self.man, self.raiz)

    def test_recusa_origem_incompleta(self):
        self.alterar(lambda d: d["origem"].pop("manifesto_sha256"))
        with self.assertRaises(ValueError):
            ee.ler(self.man, self.raiz)

    def test_recusa_bytes_adicionais_e_npz_disfarcado(self):
        original = self.man.read_text()
        item = json.loads(original)["itens"][0]
        arq = self.raiz / item["arquivo"]
        npz = io.BytesIO()
        np.savez(npz, dados=np.zeros((3, 57, 3), np.float32))
        for conteudo in (arq.read_bytes() + b"sobra", npz.getvalue()):
            with self.subTest(tamanho=len(conteudo)):
                self.man.write_text(original)
                arq.write_bytes(conteudo)
                self.alterar(lambda d: d["itens"][0].update(sha256=sha(arq)))
                with self.assertRaisesRegex(ValueError, "Bytes adicionais|NPY"):
                    ee.ler(self.man, self.raiz)

    def test_preprocessamento_igual_ao_loader_existente(self):
        # Incluir lacunas de mão para exercitar imputação e recentragem do z.
        d = json.loads(self.man.read_text())
        for item in d["itens"]:
            arq = self.raiz / item["arquivo"]
            arr = np.load(arq)
            arr[2:4, 15:36] = 0
            np.save(arq, arr)
            item["sha256"] = sha(arq)
        self.man.write_text(json.dumps(d))
        for com_z, z_recentrado, imputar in ((False, False, False), (True, True, True)):
            with self.subTest(com_z=com_z, imputar=imputar):
                clipes, _, _ = ee.carregar(
                    self.man, self.raiz, rotulos=["classe0", "classe1"], pessoas_minds=["M01"],
                    com_z=com_z, z_recentrado=z_recentrado, imputar=imputar)
                for clipe, item in zip(clipes, d["itens"]):
                    existentes = dd.carregar((self.raiz / item["arquivo"]).parent,
                        fontes=item["corpus"], com_z=com_z, z_recentrado=z_recentrado, imputar=imputar)
                    esperado = next(c for c in existentes if c.pessoa == clipe.pessoa
                                    and c.sinal == clipe.sinal and c.rep == clipe.rep)
                    np.testing.assert_array_equal(clipe.seq, esperado.seq)

    def test_copias_tem_augmentacao_propria_sem_mutar_clipe(self):
        clipes, _, _ = ee.carregar(self.man, self.raiz, rotulos=["classe0", "classe1"],
            pessoas_minds=["M01"], com_z=True, z_recentrado=True, imputar=True)
        antes = clipes[0].seq.copy()
        ds = tr.DatasetSinais(clipes * 5, ["classe0", "classe1"], None, True,
                              semente=73, arquitetura="gcn", ossos=True)
        a, _ = ds[0]
        b, _ = ds[len(clipes)]
        self.assertFalse(np.array_equal(a.numpy(), b.numpy()))
        np.testing.assert_array_equal(a.numpy(), ds[0][0].numpy())
        np.testing.assert_array_equal(antes, clipes[0].seq)

    def test_carregar_recusa_classe_fora_e_pessoa_minds(self):
        with self.assertRaisesRegex(ValueError, "vocabulário"):
            ee.carregar(self.man, self.raiz, rotulos=["classe0"], pessoas_minds=["M01"],
                        com_z=True, z_recentrado=True, imputar=True)
        with self.assertRaisesRegex(ValueError, "MINDS"):
            ee.carregar(self.man, self.raiz, rotulos=["classe0", "classe1"], pessoas_minds=["V01"],
                        com_z=True, z_recentrado=True, imputar=True)


class TestTreinoFinal(Base):
    def argv(self, *extra):
        return ["treinar.py", "--arquitetura", "gcn", "--ossos", "--com-z", "--z-recentrado",
                "--landmarks", str(self.lm), "--epocas", "2", "--batch", "4", "--workers", "0",
                "--threads", "2", "--dispositivo", "cpu", "--agendador", "cosseno",
                "--saida", str(self.base / "final"), "--final", "--politica-final", "ultima",
                "--semente", "73", *extra]

    def executar(self, argv):
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()):
            tr.main()

    def test_checkpoint_registra_externos_e_repeticoes(self):
        tamanhos = []
        original = tr._loader

        def espiar(clipes, *a, **k):
            tamanhos.append(len(clipes))
            return original(clipes, *a, **k)

        with patch.object(tr, "_loader", side_effect=espiar):
            self.executar(self.argv("--extras-manifesto", str(self.man), "--extras-raiz", str(self.raiz),
                                    "--extras-repeticoes", "3"))
        self.assertEqual(tamanhos, [12 + 2 * 3])
        modelo, rotulos, meta = mm.carregar(self.base / "final" / "modelo_final.pt")
        self.assertEqual(meta["pessoas"], ["M01", "M02", "M03", "T002", "V01"])
        self.assertEqual(meta["extras"]["repeticoes"], 3)
        self.assertEqual(meta["extras"]["manifesto_sha256"], sha(self.man))
        amostras = meta["proveniencia"]["dados"]["amostras"]
        externas = [a for a in amostras if a["origem_status"] == "externo_manifesto"]
        self.assertEqual({a["pessoa"] for a in externas}, {"T002", "V01"})
        self.assertEqual(len(amostras), 14)
        self.assertEqual(meta["proveniencia"]["particao"]["treino_pessoas"],
                         ["M01", "M02", "M03", "T002", "V01"])
        contrato = ce._contrato_final(modelo, rotulos, meta)
        manifesto = json.loads(self.man.read_text())
        with self.assertRaisesRegex(ValueError, "sobrepõe"):
            ce._sem_sobreposicao(manifesto, contrato)
        # Mesmo com pessoa renomeada, reutilizar bytes de treino deve falhar.
        with self.assertRaisesRegex(ValueError, "sobrepõe"):
            ce._sem_sobreposicao(manifesto | {"pessoas": ["V99"]}, contrato)
        ce._sem_sobreposicao({"pessoas": ["V03"], "itens": [{"sha256": "f" * 64}]}, contrato)

    def test_sem_extras_nada_muda_no_checkpoint(self):
        self.executar(self.argv())
        _, _, meta = mm.carregar(self.base / "final" / "modelo_final.pt")
        self.assertIsNone(meta["extras"])
        self.assertEqual(meta["pessoas"], ["M01", "M02", "M03"])
        self.assertNotIn("extras_manifesto", meta["args"])

    def test_cli_exige_os_tres_juntos_e_final(self):
        for extra in (("--extras-manifesto", str(self.man)),
                      ("--extras-manifesto", str(self.man), "--extras-raiz", str(self.raiz),
                       "--extras-repeticoes", "0")):
            with self.assertRaises(SystemExit):
                self.executar(self.argv(*extra))
        sem_final = [a for a in self.argv() if a not in ("--final", "--politica-final", "ultima")]
        with self.assertRaises(SystemExit):
            self.executar(sem_final + ["--extras-manifesto", str(self.man), "--extras-raiz",
                                       str(self.raiz), "--extras-repeticoes", "2"])

    def test_cli_recusa_misturar_etapas_e_fontes(self):
        for extra in (("--aug-dominio",), ("--fontes", "todas")):
            with self.subTest(extra=extra), patch.object(tr.dd, "carregar") as carregar:
                with self.assertRaises(SystemExit):
                    self.executar(self.argv("--extras-manifesto", str(self.man), "--extras-raiz",
                        str(self.raiz), "--extras-repeticoes", "5", *extra))
                carregar.assert_not_called()


if __name__ == "__main__":
    unittest.main()

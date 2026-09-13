"""Regressões offline das entradas e das células reais do notebook Kaggle."""
from __future__ import annotations

import io
import json
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import types
import unittest
from unittest.mock import patch

import numpy as np
import torch
import yaml

import entrada_pretreino as ep
import pretreinar as pt
import treinar as tr
import gcn

TREINO = pathlib.Path(__file__).resolve().parent
REPO = TREINO.parents[1]
CFG = yaml.safe_load((TREINO.parent / "PoC/config.yaml").read_text())
NB = TREINO / "notebook_pretreino_malta.ipynb"


def fixture(base, completo=False):
    origens = {}
    rng = np.random.default_rng(16)
    for fonte, (_, pasta) in ep.FONTES.items():
        d = base / fonte / "versao" / pasta
        d.mkdir(parents=True)
        origens[fonte] = d
        prefixo = {"minds": "M", "vlibrasil": "V", "malta": "T"}[fonte]
        pessoas, classes, reps = (8, 20, 5) if fonte == "minds" and completo else (3, 3, 1)
        for pessoa in range(1, pessoas + 1):
            for classe in range(classes):
                for rep in range(1, reps + 1):
                    stem = f"pessoa{prefixo}{pessoa:02d}_sinal-classe{classe}_rep{rep:02d}"
                    p = d / (stem + ".npy")
                    np.save(p, rng.uniform(-1, 1, (6, 57, 3)).astype(np.float32))
                    if fonte != "minds":
                        video = base / (stem + ".mp4")
                        video.write_bytes(stem.encode())
                        ep.pv.registrar_video_http(video, fonte=fonte,
                            origem=f"https://example.invalid/{stem}", indice_sha256="a" * 64)
                        ep.pv.registrar_landmarks(video, p, CFG)
    (origens["vlibrasil"] / "preparacao.json").write_text('{"politica": "fixture auditada"}')
    return origens


class TestEntradaPretreino(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = pathlib.Path(self.tmp.name)
        self.origens = fixture(self.base / "input")
        self.destino = self.base / "working" / "dados"

    def preparar(self):
        return ep.preparar(self.origens, self.destino, validar_minds_completo=False)

    def test_pastas_readonly_retomada_e_entrada_preservada(self):
        antes = {f: ep.inventario(p) for f, p in self.origens.items()}
        for d in self.origens.values():
            for p in d.iterdir():
                p.chmod(0o444)
        plano = self.preparar()
        self.assertEqual(self.preparar(), plano)
        self.assertEqual(antes, {f: ep.inventario(p) for f, p in self.origens.items()})
        self.assertEqual(plano["contagens"], {f: 9 for f in ep.FONTES})
        self.assertTrue((self.destino / ep.FONTES["vlibrasil"][1] / "preparacao.json").exists())

    def test_tar_e_gitkeep(self):
        for fonte, pasta in list(self.origens.items()):
            (pasta / ".gitkeep").touch()
            pacote = self.base / ep.FONTES[fonte][0]
            with tarfile.open(pacote, "w:gz") as tar:
                tar.add(pasta, arcname=pasta.name)
            self.origens[fonte] = pacote
        self.assertEqual(self.preparar()["contagens"]["malta"], 9)

    def test_busca_recursiva_ambigua_explicita(self):
        raiz = self.base / "input"
        for fonte, p in self.origens.items():
            self.assertEqual(ep.localizar(raiz, fonte), p)
        extra = raiz / "outra" / "landmarks-malta"
        extra.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "ORIGENS"):
            ep.localizar(raiz, "malta")
        self.assertEqual(ep.localizar(raiz, "malta", extra), extra)
        with self.assertRaisesRegex(FileNotFoundError, "Add Input"):
            ep.localizar(self.base / "nao-existe", "malta")

    def test_duplicatas_exclui_grupo_inteiro_e_audita(self):
        arquivos = sorted(self.origens["malta"].glob("*.npy"))
        primeiro = ep.pv.ler(arquivos[0])
        segundo = ep.pv.ler(arquivos[1])
        segundo["video"]["sha256"] = primeiro["video"]["sha256"]
        ep.pv.escrever(ep.pv.sidecar(arquivos[1]), segundo)
        with self.assertRaisesRegex(ValueError, "duplicada"):
            pt.auditar_corpora([self.origens["vlibrasil"], self.origens["malta"]],
                              avaliacao=self.origens["minds"])
        plano = self.preparar()
        self.assertEqual(len(plano["excluidos"]), 2)
        self.assertEqual(plano["contagens"]["malta"], 7)
        self.assertTrue(all(p.is_file() for p in arquivos))
        a = pt.auditar_corpora([self.destino / ep.FONTES[f][1] for f in ("vlibrasil", "malta")],
                              avaliacao=self.destino / "landmarks")
        self.assertEqual(len(a["amostras"]), 16)

    def test_sidecar_ausente_hash_shape_e_sem_commit_parcial(self):
        p = next(self.origens["malta"].glob("*.npy"))
        meta = ep.pv.sidecar(p)
        original = meta.read_bytes()
        meta.unlink()
        with self.assertRaisesRegex(ValueError, "proveniência ausente"):
            self.preparar()
        self.assertFalse(self.destino.exists())
        meta.write_bytes(original)
        np.save(p, np.zeros((6, 57, 2), dtype=np.float32))
        with self.assertRaisesRegex(ValueError, "hash divergente"):
            self.preparar()
        r = json.loads(original)
        r["landmarks"]["sha256"] = ep.pv.hash_arquivo(p)
        ep.pv.escrever(meta, r)
        with self.assertRaises(ValueError):
            self.preparar()
        self.assertFalse(self.destino.exists())

    def test_minds_incompleto_e_misto(self):
        with self.assertRaisesRegex(ValueError, "800 clipes"):
            ep.preparar(self.origens, self.destino)
        p = next(self.origens["minds"].glob("*.npy"))
        p.rename(p.with_name(p.name.replace("pessoaM", "pessoaV")))
        with self.assertRaisesRegex(ValueError, "MINDS"):
            self.preparar()

    def test_entrada_ou_saida_modificada_recusa_retomada(self):
        self.preparar()
        p = next((self.destino / "landmarks").glob("*.npy"))
        p.write_bytes(b"corrompido")
        with self.assertRaisesRegex(ValueError, "Saída preparada foi alterada"):
            self.preparar()
        p.write_bytes((self.origens["minds"] / p.name).read_bytes())
        q = self.origens["vlibrasil"] / "preparacao.json"
        q.write_text('{"alterado": true}')
        with self.assertRaisesRegex(ValueError, "outra entrada"):
            self.preparar()

    def test_tar_links_traversal_e_duplicados(self):
        pacote = self.base / "invalido.tar.gz"
        self.origens["malta"] = pacote
        for nome, tipo in [("../escape.npy", tarfile.REGTYPE),
                            ("landmarks-malta/link.npy", tarfile.SYMTYPE),
                            ("landmarks-malta/link.npy", tarfile.LNKTYPE)]:
            with self.subTest(nome=nome, tipo=tipo):
                with tarfile.open(pacote, "w:gz") as tar:
                    m = tarfile.TarInfo(nome)
                    m.type, m.linkname = tipo, "../fora"
                    tar.addfile(m, io.BytesIO())
                with self.assertRaises(ValueError):
                    self.preparar()
                self.assertFalse(self.destino.exists())
        with tarfile.open(pacote, "w:gz") as tar:
            for _ in range(2):
                tar.addfile(tarfile.TarInfo("landmarks-malta/a.npy"), io.BytesIO())
        with self.assertRaisesRegex(ValueError, "duplicado"):
            self.preparar()

    def test_isolamento_minds_mantido(self):
        self.preparar()
        minds = self.destino / "landmarks"
        v = next((self.destino / ep.FONTES["vlibrasil"][1]).glob("*.npy"))
        shutil.copyfile(v, minds / "pessoaM99_sinal-reservado_rep01.npy")
        with self.assertRaisesRegex(ValueError, "reservado para avaliação"):
            pt.auditar_corpora([v.parent], avaliacao=minds)


class TestNotebook(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)
        cls.notebook = json.loads(NB.read_text())
        cls.code = {i + 1: "".join(c["source"]) for i, c in enumerate(cls.notebook["cells"])
                    if c["cell_type"] == "code"}

    def test_sintaxe_e_sem_output_privado(self):
        for i, source in self.code.items():
            compile(source, f"celula_{i}", "exec")
        for cell in self.notebook["cells"]:
            self.assertFalse(cell.get("outputs"))

    def test_celulas_reais_input_configuracao_retomada_e_resultado(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            inp = base / "input"
            fixture(inp, completo=True)
            work = base / "working"
            work.mkdir()
            ctx = dict(pathlib=pathlib, os=__import__("os"), sys=sys, shutil=shutil,
                       subprocess=subprocess, REPO=REPO, TREINO=TREINO, BASE=work,
                       EM_KAGGLE=True, EM_COLAB=False, np=np, torch=torch, tarfile=tarfile,
                       THREADS="2", WORKERS="0", COMMIT_CODIGO="fixture")
            source = self.code[6].replace('pathlib.Path("/kaggle/input")', f'pathlib.Path({str(inp)!r})')
            exec(compile(source, "celula_6", "exec"), ctx)
            exec(compile(source, "celula_6_repetida", "exec"), ctx)
            self.assertEqual(ctx["PLANO"]["contagens"]["minds"], 800)
            calls = []

            class Processo:
                def __init__(self, cmd, **kw):
                    calls.append(cmd)
                    self.stdout, self.returncode = ["simulado offline\n"], 0
                def wait(self): return self.returncode
                def __enter__(self): return self
                def __exit__(self, *exc): return False

            class Probe:
                def cuda(self): return self
                def eval(self): return self
                def __call__(self, x): return torch.zeros(2, 3)

            original_zeros = torch.zeros
            def zeros_cpu(*args, **kw):
                kw.pop("device", None)
                return original_zeros(*args, **kw)

            with patch.object(subprocess, "Popen", Processo), patch.object(gcn, "construir", return_value=Probe()), \
                    patch.object(torch, "zeros", side_effect=zeros_cpu), patch.object(torch.cuda, "empty_cache"):
                exec(compile(self.code[8], "celula_8", "exec"), ctx)
                exp = ctx["EXP"]
                exec(compile(self.code[8], "celula_8_repetida", "exec"), ctx)
                self.assertEqual(exp, ctx["EXP"])
                self.assertTrue(any("--avaliacao" in c and "--auditar" in c for c in calls))
                self.assertIn("--landmarks", ctx["FT_ARGS"])
                self.assertNotIn("--final", ctx["FT_ARGS"])
                ctx["PLANO"]["entrada_sha256"] = "alterada"
                with self.assertRaisesRegex(RuntimeError, "mudaram"):
                    exec(compile(self.code[8], "celula_8_dados_alterados", "exec"), ctx)
            # Relatório só depois dos oito folds, nunca uma média parcial.
            rodadas = ctx["SAIDA_FT"] / "rodadas"
            rodadas.mkdir(parents=True)
            for i in range(1, 9):
                (rodadas / f"{i:02d}-M{i:02d}.json").write_text(json.dumps({"teste": f"M{i:02d}", "acuracia": .9}))
            exec(compile(self.code[13], "celula_13", "exec"), ctx)
            self.assertEqual(ctx["media"], 90.0)
            next(rodadas.glob("*.json")).unlink()
            with self.assertRaisesRegex(RuntimeError, "incompleto"):
                exec(compile(self.code[13], "celula_13_parcial", "exec"), ctx)
            archive = ctx["empacotar"]()
            with tarfile.open(archive) as tar:
                self.assertFalse(any("dados-pretreino" in m.name for m in tar.getmembers()))

    def test_preflight_sem_gpu_falha_claro(self):
        ctx = dict(sys=sys, subprocess=subprocess, os=__import__("os"))
        with patch.object(torch.cuda, "is_available", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "Accelerator"):
                exec(compile(self.code[4], "celula_4", "exec"), ctx)

    def test_git_falha_alto_sem_apagar_clone(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            repo = work / "libras-livre-ai-glasses-brasil"
            treino = repo / "computer-vision-model" / "treino"
            treino.mkdir(parents=True)
            helper = treino / "entrada_pretreino.py"
            helper.write_text("# preservar")
            source = self.code[3].replace('"/kaggle/working"', repr(str(work)))

            def fake_git(cmd, **kwargs):
                if "fetch" in cmd:
                    raise subprocess.CalledProcessError(1, cmd, stderr="rede indisponível")
                if "status" in cmd:
                    return subprocess.CompletedProcess(cmd, 0, stdout="")
                return subprocess.CompletedProcess(cmd, 0, stdout="feat/pretreino-gcn-representacao\n")

            with patch.object(subprocess, "run", side_effect=fake_git):
                with self.assertRaisesRegex(RuntimeError, "Internet"):
                    exec(compile(source, "celula_3_git_falhou", "exec"), {})
            self.assertEqual(helper.read_text(), "# preservar")
            with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout="outra-branch")):
                with self.assertRaisesRegex(RuntimeError, "outra branch"):
                    exec(compile(source, "celula_3_branch_errada", "exec"), {})
            self.assertEqual(helper.read_text(), "# preservar")

    def test_celulas_checkpoint_finetuning_e_backup_em_falha(self):
        with tempfile.TemporaryDirectory() as tmp:
            exp = pathlib.Path(tmp)
            pre, ft = exp / "pre", exp / "ft"
            pre.mkdir()
            checkpoint = pre / "backbone_gcn.pt"
            args = dict(com_z=True, z_recentrado=True, ossos=True, movimento=False, sem_imputacao=False)
            backups, calls = [], []

            def executar(script, argumentos, log):
                calls.append((script, argumentos))
                if script == "pretreinar.py":
                    pt.salvar_backbone(gcn.construir(3, canais_ent=6), checkpoint, "gcn", {"args": args})
                    checkpoint.with_suffix(".json").write_text(json.dumps({"args": args}))
                else:
                    raise subprocess.CalledProcessError(1, script)

            ctx = dict(CHECKPOINT=checkpoint, SAIDA_FT=ft, EXP=exp, json=json,
                       executar=executar, PRE_ARGS=["--ossos", "--com-z", "--z-recentrado"],
                       FT_ARGS=["--inicializar", str(checkpoint)],
                       empacotar=lambda: backups.append(True), pv=ep.pv, gcn=gcn)
            exec(compile(self.code[9], "celula_9", "exec"), ctx)
            self.assertEqual(len(calls), 1)
            exec(compile(self.code[9], "celula_9_repetida", "exec"), ctx)
            self.assertEqual(len(calls), 1, "Não deve sobrescrever backbone concluído")
            with self.assertRaises(subprocess.CalledProcessError):
                exec(compile(self.code[11], "celula_11_falha", "exec"), ctx)
            self.assertEqual(len(backups), 3)
            checkpoint.write_bytes(checkpoint.read_bytes() + b"mudou")
            with self.assertRaisesRegex(RuntimeError, "alterado"):
                exec(compile(self.code[11], "celula_11_checkpoint_alterado", "exec"), ctx)


class TestRepresentacao(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)

    def test_guarda_imputacao_e_z_antes_de_aplicar(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "backbone.pt"
            model = gcn.construir(3, canais_ent=6)
            args = dict(com_z=True, z_recentrado=True, ossos=True, movimento=False, sem_imputacao=False)
            pt.salvar_backbone(model, p, "gcn", {"args": args})
            count = tr.aplicar_backbone(model, p, "gcn", types.SimpleNamespace(**args))
            self.assertGreater(count, 0)
            for flag in ("sem_imputacao", "z_recentrado"):
                with self.assertRaisesRegex(SystemExit, flag):
                    tr.aplicar_backbone(model, p, "gcn", types.SimpleNamespace(**(args | {flag: not args[flag]})))

    def test_cli_contrastivo_seis_canais_e_finetuning_cpu(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            origens = fixture(base / "input")
            pre = base / "pre"
            cmd = [sys.executable, str(TREINO / "pretreinar.py"),
                   "--corpus", str(origens["vlibrasil"]), "--corpus", str(origens["malta"]),
                   "--avaliacao", str(origens["minds"]), "--arquitetura", "gcn",
                   "--ossos", "--com-z", "--z-recentrado", "--objetivo", "contrastivo",
                   "--pessoa-val", "V03", "--epocas", "1", "--p-classes", "2",
                   "--workers", "0", "--threads", "2", "--dispositivo", "cpu", "--saida", str(pre)]
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            checkpoint = pre / "backbone_gcn.pt"
            saved = torch.load(checkpoint, map_location="cpu", weights_only=False)
            self.assertTrue(saved["meta"]["args"]["com_z"])
            model = gcn.construir(3, canais_ent=6)
            self.assertGreater(tr.aplicar_backbone(model, checkpoint, "gcn",
                types.SimpleNamespace(com_z=True, z_recentrado=True, ossos=True, movimento=False, sem_imputacao=False)), 0)
            out = base / "ft"
            r = subprocess.run([sys.executable, str(TREINO / "treinar.py"),
                "--landmarks", str(origens["minds"]), "--arquitetura", "gcn",
                "--ossos", "--com-z", "--z-recentrado", "--inicializar", str(checkpoint),
                "--epocas", "1", "--folds", "1", "--batch", "3", "--workers", "0",
                "--threads", "2", "--dispositivo", "cpu", "--semente", "16", "--saida", str(out)],
                capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue((out / "relatorio.md").is_file())
            self.assertEqual(len(list((out / "rodadas").glob("*.json"))), 1)
            # Reexecutar o comando exato deve reutilizar o JSON, não treinar de novo.
            novamente = subprocess.run(r.args, capture_output=True, text=True, timeout=120)
            self.assertEqual(novamente.returncode, 0, novamente.stdout + novamente.stderr)
            self.assertIn("reaproveitando", novamente.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)

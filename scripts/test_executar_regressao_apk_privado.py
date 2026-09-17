"""Unitários stdlib SEM SDK/Gradle/adb. Não importa os testes opt-in anteriores."""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import warnings
import zipfile

import executar_regressao_apk_privado as e


def exemplo_a() -> dict[str, bytes]:
    modelo = b"\xff\xff\xff\x7fTFL3unitario-A-nao-modelo"
    checkpoint = e.pacote.sha256(b"sem-checkpoint-unitario")
    sidecar = e.serializar(dict(
        schema=1, modo="landmarks", sha256=e.pacote.sha256(modelo),
        origem={"sha256": checkpoint}, rotulos=[f"sintetico_{i}" for i in range(20)],
        contrato_entrada=dict(shape=[1, 96, 57, 3], dtype="float32", frames_fixos=96,
                             layout_landmarks={"pose_ordenada": [
                                 {"indice_mediapipe_pose": i} for i in e.pacote.POSE]})))
    identidade = e.serializar(dict(
        schema=1, experimental=True, aprovado_entrega=False, experimento="unitario-A",
        calibracao="ausente_nao_calibrado", modelo_sha256=e.pacote.sha256(modelo),
        sidecar_sha256=e.pacote.sha256(sidecar), checkpoint_sha256=checkpoint))
    return {e.pacote.MODELO: modelo, e.pacote.SIDECAR: sidecar, e.pacote.IDENTIDADE: identidade}


def buildconfig(esperado) -> str:
    flag = "true" if esperado is not None else "false"
    sha = e.pacote.sha256(esperado[e.pacote.IDENTIDADE]) if esperado is not None else ""
    return ("package " + e.APP + ";\npublic final class BuildConfig {\n"
            f"public static final boolean CLASSIFICADOR_PRIVADO_OBRIGATORIO = {flag};\n"
            f'public static final String CLASSIFICADOR_IDENTIDADE_SHA256 = "{sha}";\n}}\n')


def zip_apk(destino: Path, arquivos: dict[str, bytes], compressao=zipfile.ZIP_STORED) -> None:
    destino.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destino, "w", compression=compressao) as z:
        z.writestr("AndroidManifest.xml", b"fixture-nao-APK-instalavel")
        for nome, bruto in arquivos.items():
            z.writestr("assets/" + nome, bruto)


class HelpersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.raiz = Path(self.tmp.name)
        self.apk = self.raiz / "app.apk"
        self.a = exemplo_a()
        self.fixtures = {"paridade_classificador.json": b"{}", "sentinel.txt": b"somente androidTest"}
        self.hashes = {n: e.pacote.sha256(b) for n, b in self.fixtures.items()}

    def validar(self, esperado=None, principal=True):
        return e.validar_apk(self.apk, esperado, principal=principal,
                            fixtures=self.hashes, proibidas=set(self.fixtures))

    def test_b_deterministico_negativo_diferente_todos_bytes_hashes(self):
        b = e.pacote_b(self.a)
        self.assertEqual(b, e.pacote_b(self.a))
        e.validar_bytes(b)
        self.assertEqual(set(b), e.pacote.ARQUIVOS)
        self.assertTrue(all(b[n] != self.a[n] for n in b))
        self.assertGreater(int.from_bytes(b[e.pacote.MODELO][:4], "little"), len(b[e.pacote.MODELO]))
        self.assertEqual(b[e.pacote.MODELO][4:8], b"TFL3")
        self.assertTrue(all(e.pacote.sha256(b[n]) != e.pacote.sha256(self.a[n]) for n in b))
        self.assertNotEqual(e.pacote_b(b), b)

    def test_buildconfig_on_off_e_hash_selecionado(self):
        for esperado in (self.a, e.pacote_b(self.a), None):
            with self.subTest(esperado=esperado is not None):
                self.assertEqual(e.validar_buildconfig(buildconfig(esperado), esperado)["obrigatorio"],
                                 esperado is not None)

    def test_buildconfig_rejeita_velho_flag_hash_vazio_duplicado_comentario(self):
        for texto, esperado in ((buildconfig(self.a), None), (buildconfig(None), self.a),
                                (buildconfig(self.a), e.pacote_b(self.a)),
                                (buildconfig(self.a) * 2, self.a),
                                ("/* " + buildconfig(self.a) + " */", self.a),
                                (buildconfig(self.a).replace("= true;", "= false;"), self.a)):
            with self.subTest(texto=texto):
                with self.assertRaises(ValueError):
                    e.validar_buildconfig(texto, esperado)

    def test_apk_bytes_exatos_e_ciclo(self):
        for esperado in (self.a, e.pacote_b(self.a), None, self.a):
            zip_apk(self.apk, esperado or {})
            resultado = self.validar(esperado)
            self.assertEqual(resultado["privados_sha256"],
                             {n: e.pacote.sha256(b) for n, b in (esperado or {}).items()})

    def test_apk_adulterado_ausente_extra_renomeado_residuo(self):
        casos = [dict(self.a, **{e.pacote.MODELO: b"adulterado"}),
                 {n: b for n, b in self.a.items() if n != e.pacote.SIDECAR},
                 dict(self.a, **{"sinal_classifier.bak": b"residuo"}),
                 dict(self.a, **{"outra/sinal_classifier.tflite": b"residuo"})]
        for arquivos in casos:
            with self.subTest(nomes=sorted(arquivos)):
                zip_apk(self.apk, arquivos)
                with self.assertRaises(ValueError):
                    self.validar(self.a)
        zip_apk(self.apk, self.a)
        with self.assertRaises(ValueError):
            self.validar(None)
        with self.assertRaises(ValueError):
            self.validar(e.pacote_b(self.a))

    def test_zip_duplicado_traversal_nao_canonico_e_corrompido(self):
        zip_apk(self.apk, self.a)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as z:
                z.writestr("assets/" + e.pacote.MODELO, self.a[e.pacote.MODELO])
        with self.assertRaisesRegex(ValueError, "duplicados"):
            self.validar(self.a)
        for nome in ("../escape", "a//b", "./x", "x\\y"):
            zip_apk(self.apk, {nome: b"x"})
            with self.assertRaisesRegex(ValueError, "canônico"):
                self.validar()
        self.apk.write_bytes(b"nao zip")
        with self.assertRaises(zipfile.BadZipFile):
            self.validar()

    def test_modelo_precisa_nao_comprimido(self):
        zip_apk(self.apk, self.a, zipfile.ZIP_DEFLATED)
        with self.assertRaisesRegex(ValueError, "comprimido"):
            self.validar(self.a)

    def test_fixtures_somente_test_apk_e_substituicao_nao_adicao(self):
        zip_apk(self.apk, self.fixtures)
        self.validar(principal=False)
        with self.assertRaisesRegex(ValueError, "vazou"):
            self.validar()
        for arquivos in (dict(self.fixtures, **{"smoke_antigo.tflite": b"x"}),
                         {"paridade_classificador.json": b"{}"},
                         dict(self.fixtures, **{"sentinel.txt": b"sentinel errado"}),
                         dict(self.fixtures, **self.a)):
            zip_apk(self.apk, arquivos)
            with self.assertRaises(ValueError):
                self.validar(principal=False)
        zip_apk(self.apk, {"sub/sentinel.txt": b"x"})
        with self.assertRaisesRegex(ValueError, "vazou"):
            self.validar()

    def test_resultado_negativo_exige_retorno_e_motivo_da_invocacao(self):
        e.validar_resultado(0, "", None)
        e.validar_resultado(1, "release proibido", "release proibido")
        for codigo, log, motivo in ((1, "erro SDK", "release proibido"),
                                   (0, "release proibido", "release proibido"),
                                   (1, "", None)):
            with self.assertRaises(ValueError):
                e.validar_resultado(codigo, log, motivo)

    def test_fontes_substituicao_restauracao_efetivas_e_token(self):
        fixture, gerados, pa = self.raiz / "fixture", self.raiz / "generated", self.raiz / "A"
        dados = dict(token="novo", pacote=str(pa), permitirAssetsFaltando="false",
                     fontes={"androidTest": [str(fixture)], "debug": [str(gerados)], "main": []})
        def conferir(d):
            e.validar_fontes(d, token="novo", selecionado=pa, fixtures=fixture,
                             gerados=gerados, colisao=None)
        conferir(dados)
        casos = [dict(dados, token="velho"), dict(dados, pacote=None),
                 dict(dados, permitirAssetsFaltando="true")]
        for fonte in ({"androidTest": [str(fixture), "fonte-antiga"]},
                      {"main": [str(fixture)]}, {"debug": []}):
            casos.append(dict(dados, fontes=dict(dados["fontes"], **fonte)))
        for d in casos:
            with self.assertRaises(ValueError):
                conferir(d)
        with self.assertRaisesRegex(ValueError, "injetada"):
            e.validar_fontes(dados, token="novo", selecionado=pa, fixtures=fixture,
                             gerados=gerados, colisao=self.raiz / "colisao")

    def test_comando_somente_assemble_sem_jvm_bypass_ou_cwd_implicito(self):
        repo = self.raiz / "worktree"
        wrapper = repo / "mobile-app-companion/gradlew"
        comando = e.comando_gradle(wrapper, wrapper.parent, e.TAREFAS_DEBUG,
                                  self.raiz / "init.gradle", self.raiz / "A")
        self.assertEqual(comando[0], str(wrapper))
        self.assertEqual(comando[comando.index("-p") + 1], str(wrapper.parent))
        self.assertIn("--max-workers=2", comando)
        self.assertIn("-PlibrasLivre.permitirAssetsFaltando=false", comando)
        self.assertEqual(comando[-2:], list(e.TAREFAS_DEBUG))
        self.assertFalse(any(x in comando for x in ("test", "build", "clean", "connectedDebugAndroidTest", "adb")))
        sem = e.comando_gradle(wrapper, wrapper.parent, e.TAREFAS_DEBUG, self.raiz / "init.gradle", None)
        self.assertFalse(any(x.startswith("-PlibrasLivre.classificador") for x in sem))

    def test_init_colisao_externa_sem_editar_gradle_fontes_e_literal_seguro(self):
        texto = e.init_gradle("uuid", self.raiz / "fontes.json", self.raiz / "colisao-'-$")
        self.assertIn("getByName('main').assets.srcDir(", texto)
        self.assertIn("colisao-\\'-$", texto)
        self.assertIn("ETAPA5_FONTES_uuid", texto)
        self.assertNotIn("build.gradle.kts", texto)
        self.assertNotIn("src/main/assets", texto)
        self.assertNotIn("assets.srcDir(", e.init_gradle("uuid", self.raiz / "fontes.json"))

    def test_sem_executar_nenhum_comando_ou_saida(self):
        with patch.object(e, "caminhos_execucao") as caminhos, patch.object(e.subprocess, "Popen") as popen:
            with contextlib.redirect_stdout(io.StringIO()) as stream:
                e.main(["--pacote", str(self.raiz / "ausente"), "--saida", str(self.raiz / "saida")])
        caminhos.assert_not_called()
        popen.assert_not_called()
        self.assertEqual(json.loads(stream.getvalue())["status"], "NAO_EXECUTADO")
        self.assertFalse((self.raiz / "saida").exists())

    def test_caminhos_wrapper_do_worktree_saida_ignored_nova_e_android_home(self):
        repo = self.raiz / "repo"
        wrapper = repo / "mobile-app-companion/gradlew"
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text("# wrapper ficticio, nunca executado")
        wrapper.chmod(0o700)
        sdk = self.raiz / "sdk-ficticio"
        sdk.mkdir()
        args = e.criar_parser().parse_args([
            "--executar", "--pacote", str(self.raiz / "A"),
            "--saida", str(repo / "experimentos-privados/nova")])
        def git(*argv, **kwargs):
            return Mock(returncode=0, stdout=b"")
        with patch.dict(e.os.environ, {"ANDROID_HOME": str(sdk)}), \
                patch.object(e.subprocess, "run", side_effect=git):
            self.assertEqual(e.caminhos_execucao(args, repo)[0], wrapper)
            for invalido in (Path("relativo"), self.raiz / "outro-worktree/gradlew"):
                args.gradle = invalido
                with self.assertRaises(ValueError):
                    e.caminhos_execucao(args, repo)
            args.gradle = wrapper
            args.saida = self.raiz / "externa"
            with self.assertRaisesRegex(ValueError, "experimentos-privados"):
                e.caminhos_execucao(args, repo)
            args.saida = repo / "experimentos-privados/nova"
            with patch.object(e.subprocess, "run", return_value=Mock(returncode=1)):
                with self.assertRaisesRegex(ValueError, "ignored"):
                    e.caminhos_execucao(args, repo)
            with patch.dict(e.os.environ, {"ANDROID_HOME": "relativo"}):
                with self.assertRaisesRegex(ValueError, "ANDROID_HOME"):
                    e.caminhos_execucao(args, repo)
            args.saida.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, "existente"):
                e.caminhos_execucao(args, repo)


class OrquestracaoTest(unittest.TestCase):
    """Simula fases/subprocessos: nada é compilado e nenhum SDK é consultado."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / "repo"
        self.app = self.repo / "mobile-app-companion/app"
        self.padrao = self.app / "src/androidTest/assets"
        self.padrao.mkdir(parents=True)
        (self.padrao / "paridade_classificador.json").write_bytes(b'{"smoke":true}')
        (self.padrao / "video.mp4").write_bytes(b"video-unitario-nao-midia")
        (self.app / "build.gradle.kts").write_text("// fonte imutavel")
        self.origem = Path(self.tmp.name) / "pacote"
        self.origem.mkdir()
        self.a = exemplo_a()
        for nome, bruto in self.a.items():
            (self.origem / nome).write_bytes(bruto)
        self.saida = self.repo / "experimentos-privados/novo"
        self.r = e.Regressao(self.repo, self.app.parent / "gradlew", self.origem,
                            self.saida, Path(self.tmp.name) / "sdk-nao-usado")
        self.chamadas = []

    def simular(self, nome, selecionado, esperado, **opcoes):
        self.chamadas.append(nome)
        if nome == "sem_pacote":
            self.assertIsNone(selecionado)
            self.assertIsNone(esperado)
        elif nome == "B_negativo_transporte":
            self.assertNotEqual(esperado, self.a)
        else:
            self.assertEqual(esperado, self.a)
        if nome == "fixtures_substituidas":
            self.assertEqual(len(list(opcoes["fixtures"].iterdir())), 2)
        if nome == "colisao_bloqueada":
            self.assertFalse(opcoes["colisao"].is_relative_to(self.app / "src"))
            self.assertIn("bloqueio", opcoes)
        if nome == "restauracao_final_A_padrao":
            self.assertEqual(opcoes, {})
        if selecionado:
            self.assertEqual({n: (selecionado / n).read_bytes() for n in e.pacote.ARQUIVOS}, esperado)

    def test_ciclo_completo_fontes_intactas_temporarios_removidos(self):
        antes = e.inventario(self.app / "src")
        with patch.object(self.r, "fase", side_effect=self.simular), patch.object(e.subprocess, "Popen") as popen:
            self.r.executar()
        popen.assert_not_called()
        self.assertEqual(self.chamadas, e.PLANO)
        self.assertEqual(e.inventario(self.app / "src"), antes)
        self.assertEqual(list(self.saida.glob(".fontes-temporarias-*")), [])
        registro = json.loads((self.saida / "evidencia.json").read_text())
        self.assertEqual(registro["status"], "APROVADO_CONTEUDO_NAO_RUNTIME")

    def test_finally_apos_falha_em_cada_fase_preserva_excecao_original(self):
        for nome_falho in e.PLANO[:-1]:
            with self.subTest(fase=nome_falho):
                self.r.saida = self.saida.parent / nome_falho
                self.chamadas = []
                erro = ValueError("original " + nome_falho)
                def falhar(nome, *args, **kwargs):
                    self.simular(nome, *args, **kwargs)
                    if nome == nome_falho:
                        raise erro
                with patch.object(self.r, "fase", side_effect=falhar):
                    with self.assertRaises(ValueError) as capturado:
                        self.r.executar()
                self.assertIs(capturado.exception, erro)
                self.assertEqual(self.chamadas[-1], "restauracao_final_A_padrao")
                registro = json.loads((self.r.saida / "evidencia.json").read_text())
                self.assertEqual(registro["status"], "FALHOU")
                self.assertIn(str(erro), registro["erro_original"])

    def test_original_e_restore_falham_ambos_arquivados_sem_mascarar(self):
        original, restauracao = ValueError("original"), RuntimeError("restore")
        def falhar(nome, *args, **kwargs):
            self.simular(nome, *args, **kwargs)
            raise restauracao if nome == "restauracao_final_A_padrao" else original
        with patch.object(self.r, "fase", side_effect=falhar):
            with self.assertRaises(ValueError) as capturado:
                self.r.executar()
        self.assertIs(capturado.exception, original)
        self.assertIs(capturado.exception.__cause__, restauracao)
        registro = json.loads((self.saida / "evidencia.json").read_text())
        self.assertIn("original", registro["erro_original"])
        self.assertIn("restore", registro["erro_restauracao"])

    def test_somente_restore_falha_nao_aprova(self):
        def falhar(nome, *args, **kwargs):
            self.simular(nome, *args, **kwargs)
            if nome == "restauracao_final_A_padrao":
                raise RuntimeError("restore")
        with patch.object(self.r, "fase", side_effect=falhar):
            with self.assertRaisesRegex(RuntimeError, "restore"):
                self.r.executar()
        registro = json.loads((self.saida / "evidencia.json").read_text())
        self.assertEqual(registro["status"], "FALHOU")
        self.assertIsNone(registro["erro_original"])
        self.assertIn("restore", registro["erro_restauracao"])

    def preparar_fase(self):
        self.saida.mkdir(parents=True)
        self.r.fixtures_padrao = e.inventario(self.padrao)
        self.r.fixtures_temporarias = {"sentinel.txt": e.pacote.sha256(b"sentinel")}
        for arquivo in (*self.r.apks.values(), self.r.config):
            arquivo.parent.mkdir(parents=True, exist_ok=True)
            arquivo.write_bytes(b"ARTEFATO_VELHO")

    def test_build_falhou_nao_aceita_apk_velho_log_atual(self):
        self.preparar_fase()
        def processo(*args, **kwargs):
            kwargs["stdout"].write(b"FALHA_ATUAL_SDK\n")
            return Mock(wait=Mock(return_value=1))
        with patch.object(e.subprocess, "Popen", side_effect=processo):
            with self.assertRaisesRegex(ValueError, "nenhum APK"):
                self.r.fase("falha", self.origem, self.a)
        fase = self.r.registro["fases"][-1]
        self.assertEqual(fase["returncode"], 1)
        self.assertEqual(fase["status"], "FALHOU")
        self.assertFalse(fase["artefatos_aceitos"])
        self.assertEqual(fase["artefatos"], {})
        self.assertEqual(len(fase["descartados_antes"]), 3)
        self.assertEqual(fase["log"]["sha256"], e.pacote.sha256(b"FALHA_ATUAL_SDK\n"))
        self.assertTrue(all(not p.exists() for p in self.r.apks.values()))

    def test_exit_zero_sem_artefatos_nao_aceita_resultado_velho(self):
        self.preparar_fase()
        with patch.object(e.subprocess, "Popen", return_value=Mock(wait=Mock(return_value=0))):
            with self.assertRaises(ValueError):
                self.r.fase("zero_incompleto", self.origem, self.a)
        self.assertFalse(self.r.registro["fases"][-1]["artefatos_aceitos"])

    def test_fase_sucesso_simulado_confere_snapshots_logs_fontes_buildconfig(self):
        self.preparar_fase()
        def processo(argv, **kwargs):
            fase = self.r.registro["fases"][-1]
            pasta = Path(argv[argv.index("--init-script") + 1]).parent
            kwargs["stdout"].write(("ETAPA5_FONTES_" + fase["token"] + "\n").encode())
            (pasta / "fontes.json").write_bytes(e.serializar(dict(
                token=fase["token"], pacote=str(self.origem), permitirAssetsFaltando="false",
                fontes={"androidTest": [str(self.padrao)], "main": [],
                        "debug": [str(self.r.gerados)]})))
            self.r.config.write_text(buildconfig(self.a))
            self.r.gerados.mkdir(parents=True)
            for nome, bruto in self.a.items():
                (self.r.gerados / nome).write_bytes(bruto)
            zip_apk(self.r.apks["principal"], self.a)
            zip_apk(self.r.apks["androidTest"],
                    {n: (self.padrao / n).read_bytes() for n in self.r.fixtures_padrao})
            self.assertEqual(kwargs["env"]["ANDROID_HOME"], str(self.r.sdk))
            self.assertEqual(kwargs["cwd"], self.r.projeto)
            return Mock(wait=Mock(return_value=0))
        with patch.object(e.subprocess, "Popen", side_effect=processo):
            self.r.fase("sucesso_simulado", self.origem, self.a)
        fase = self.r.registro["fases"][-1]
        self.assertEqual(fase["status"], "APROVADO_CONTEUDO")
        self.assertTrue(fase["artefatos_aceitos"])
        for tipo in ("principal", "androidTest"):
            snapshot = self.saida / fase["artefatos"][tipo]["snapshot"]
            self.assertEqual(e.hash_arquivo(snapshot), fase["artefatos"][tipo]["sha256"])
        self.assertEqual(fase["buildconfig"]["identidade_sha256"],
                         e.pacote.sha256(self.a[e.pacote.IDENTIDADE]))

    def test_keyboardinterrupt_tambem_tenta_restore_e_registra_falha(self):
        def interromper(nome, *args, **kwargs):
            self.simular(nome, *args, **kwargs)
            if nome == "B_negativo_transporte":
                raise KeyboardInterrupt()
        with patch.object(self.r, "fase", side_effect=interromper):
            with self.assertRaises(KeyboardInterrupt):
                self.r.executar()
        self.assertEqual(self.chamadas[-1], "restauracao_final_A_padrao")
        registro = json.loads((self.saida / "evidencia.json").read_text())
        self.assertEqual(registro["status"], "FALHOU")
        self.assertIn("KeyboardInterrupt", registro["erro_original"])

    def test_saida_existente_recusada_sem_build(self):
        self.saida.mkdir(parents=True)
        (self.saida / "preservar.txt").write_bytes(b"nao alterar")
        with patch.object(self.r, "fase") as fase:
            with self.assertRaises(FileExistsError):
                self.r.executar()
        fase.assert_not_called()
        self.assertEqual((self.saida / "preservar.txt").read_bytes(), b"nao alterar")


if __name__ == "__main__":
    unittest.main()
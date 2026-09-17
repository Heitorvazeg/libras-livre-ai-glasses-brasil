import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import uuid
import zipfile

import executar_etapa4_android as host
from executar_etapa4_android import (
    REAL, RECUSADO, RECUPERACOES, criar_parser, validar_execucao,
    validar_instrumentacao, validar_pacote_modo, validar_processos, validar_recuperacao,
)
from gerar_pacote_classificador_recusado import bytes_pacote
import pacote_classificador_privado as pacote


class TestProtocoloInstrumentacao(unittest.TestCase):
    def saida(self):
        status = "\n".join(f"INSTRUMENTATION_STATUS: {k}={v}" for k, v in
                           (("class", "Classe"), ("test", "metodo"), ("current", "1"), ("numtests", "1")))
        prova = json.dumps(dict(execucao="uuid-novo", fase="metodo"))
        return (f"{status}\nINSTRUMENTATION_STATUS_CODE: 1\n"
                f"{status}\nINSTRUMENTATION_STATUS_CODE: 0\n"
                f"INSTRUMENTATION_RESULT: etapa4_prova={prova}\n"
                "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")

    def test_sucesso_estrito(self):
        self.assertEqual(validar_instrumentacao(self.saida(), "Classe", "metodo", "uuid-novo")["fase"], "metodo")

    def test_adb_zero_nao_basta_para_failure_skip_assume_zero_test_ou_crash(self):
        original = self.saida()
        for texto in (
            original.replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
            original.replace("STATUS_CODE: 0", "STATUS_CODE: -3"),
            original.replace("STATUS_CODE: 0", "STATUS_CODE: -4"),
            original.replace("numtests=1", "numtests=0"),
            original.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            original.replace("OK (1 test)", "FAILURES!!!"),
            original + "INSTRUMENTATION_RESULT: shortMsg=Process crashed\n",
            original.replace("etapa4_prova=", "prova_ausente="),
            original.replace("uuid-novo", "uuid-velho"),
            original.replace("test=metodo", "test=outro"),
            original.replace("OK (1 test)", "OK (0 tests)"),
        ):
            with self.subTest(texto=texto), self.assertRaises(ValueError):
                validar_instrumentacao(texto, "Classe", "metodo", "uuid-novo")

    def test_mesmo_processo_nao_passa_por_activity_nova_ou_uuid_de_metodo(self):
        um = dict(pid=10, start_elapsed_ms=123, processo_uuid="p1", marcador="m1",
                  execucao="run", limiar="0.83", identidade_sha256="hash", identidade={"modelo": "hash"})
        dois = dict(um, processo_uuid="p2", marcador="m2", verificado_antes_de_alterar=True, cleanup=True,
                    processo_anterior={k: um[k] for k in ("pid", "start_elapsed_ms", "processo_uuid")})
        with self.assertRaises(ValueError):
            validar_processos(um, dois)
        dois["start_elapsed_ms"] = 456  # PID reutilizado, mas outro início de processo é válido.
        validar_processos(um, dois)
        dois["limiar"] = "0.60"
        with self.assertRaises(ValueError):
            validar_processos(um, dois)


class TestRecuperacaoSeparada(unittest.TestCase):
    UUID = "20dfed91-cfe0-4660-aa28-60b267265cc1"

    def test_parser_seleciona_recuperacao_recusado_sem_disparar_comandos(self):
        args = criar_parser().parse_args([
            "--modo", "recuperar-recusado", "--execucao", self.UUID,
            "--serial", "emulator-5554", "--aapt", "/sdk/aapt",
            "--app-apk", "/privado/app.apk", "--test-apk", "/privado/test.apk",
            "--pacote", "/privado/negativo", "--saida", "/privado/recuperacao",
        ])
        self.assertEqual(args.modo, "recuperar-recusado")
        validar_execucao(args.modo, args.execucao)
        self.assertEqual(RECUPERACOES[args.modo], (
            RECUSADO, "recuperarEstadoRecusadoInterrompido", "etapa4RecuperarRecusado", "RECUSADO"))
        self.assertEqual(RECUPERACOES["recuperar"], (
            REAL, "limparEstadoInterrompido", "etapa4RecuperarPersistencia", "REAL"))

    def test_uuid_dono_obrigatorio_so_nas_recuperacoes(self):
        for modo in RECUPERACOES:
            validar_execucao(modo, self.UUID)
            for invalido in (None, "", "outro", self.UUID.upper(), self.UUID.replace("-", "")):
                with self.subTest(modo=modo, uuid=invalido), self.assertRaises(ValueError):
                    validar_execucao(modo, invalido)
        for modo in ("real", "recusado"):
            validar_execucao(modo, None)
            with self.assertRaises(ValueError):
                validar_execucao(modo, self.UUID)

    def test_pacote_negativo_so_nos_dois_modos_recusado(self):
        negativo = bytes_pacote()
        outro = dict(negativo, **{pacote.MODELO: b"outro-modelo"})
        adulterado = dict(negativo, **{pacote.IDENTIDADE: b"outra-identidade"})
        for modo in ("recusado", "recuperar-recusado"):
            validar_pacote_modo(modo, negativo)
            for errado in (outro, adulterado):
                with self.subTest(modo=modo), self.assertRaises(ValueError):
                    validar_pacote_modo(modo, errado)
        for modo in ("real", "recuperar"):
            with self.assertRaises(ValueError):
                validar_pacote_modo(modo, negativo)

    def test_recuperacao_nao_e_aprovacao_nem_prova_real(self):
        for modo, (_, metodo, _, tipo) in RECUPERACOES.items():
            prova = dict(fase=metodo, tipo=tipo, cleanup=True,
                         status="RECUPERADO_SEM_EVIDENCIA", identidade_sha256="hash")
            validar_recuperacao(prova, modo, "hash")
            for campo, valor in (("fase", "outro"), ("tipo", "OUTRO"), ("cleanup", False),
                                 ("cleanup", "true"), ("status", "APROVADO"),
                                 ("identidade_sha256", "outro")):
                with self.subTest(modo=modo, campo=campo), self.assertRaises(ValueError):
                    validar_recuperacao(dict(prova, **{campo: valor}), modo, "hash")
            incompleta = dict(prova)
            del incompleta["cleanup"]
            with self.assertRaises(ValueError):
                validar_recuperacao(incompleta, modo, "hash")

    def test_prova_json_nao_objeto_falha_explicitamente(self):
        texto = TestProtocoloInstrumentacao().saida()
        original = json.dumps(dict(execucao="uuid-novo", fase="metodo"))
        for invalida in ("null", "[]", '"texto"'):
            with self.subTest(prova=invalida), self.assertRaises(ValueError):
                validar_instrumentacao(texto.replace(original, invalida), "Classe", "metodo", "uuid-novo")


def pronto_exemplo(modo="interromper-real", execucao=TestRecuperacaoSeparada.UUID, marcador=None):
    snapshot = {"chave_futura": "literal\nç😀", "comando_de_voz": "true"}
    alterado = dict(snapshot, comando_de_voz="false", limiar_confianca="0.83")
    return dict(versao=1, tipo=host.RECUPERACOES[host.INTERRUPCOES[modo][3]][3], status="PRONTO",
                fase=host.INTERRUPCOES[modo][1], execucao=execucao, marcador=marcador or str(uuid.uuid4()),
                identidade_sha256="a" * 64, pid=123, start_elapsed_ms=1234, start_ticks="124",
                processo_uuid=str(uuid.uuid4()), snapshot=snapshot, alterado=alterado,
                snapshot_sha256=host.hash_snapshot(snapshot), alterado_sha256=host.hash_snapshot(alterado))


def prova_recuperada(pronto, modo, marcador):
    _, metodo, _, tipo = host.RECUPERACOES[host.INTERRUPCOES[modo][3]]
    return dict(execucao=pronto["execucao"], fase=metodo, tipo=tipo, cleanup=True,
                status="RECUPERADO_SEM_EVIDENCIA", identidade_sha256=pronto["identidade_sha256"],
                interrupcao=dict(pid=124, start_elapsed_ms=1500, start_ticks="150",
                                 processo_uuid=str(uuid.uuid4()), marcador=marcador, pronto=pronto,
                                 verificado_antes_de_alterar=True, snapshot_exato=True,
                                 snapshot_restaurado=pronto["snapshot"],
                                 snapshot_restaurado_sha256=pronto["snapshot_sha256"]))


def inicio_instrumentacao(classe, metodo):
    return "".join(f"INSTRUMENTATION_STATUS: {k}={v}\n" for k, v in
                   (("class", classe), ("test", metodo), ("numtests", 1), ("current", 1))) + \
        "INSTRUMENTATION_STATUS_CODE: 1\n"


FIM_CRASH = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"


class TestInterrupcaoValidacoes(unittest.TestCase):
    def test_modos_optin_uuid_fresco_e_pacote_exato(self):
        for modo in host.INTERRUPCOES:
            host.validar_execucao(modo, None)
            with self.assertRaises(ValueError):
                host.validar_execucao(modo, TestRecuperacaoSeparada.UUID)
        negativo = bytes_pacote()
        host.validar_pacote_modo("interromper-recusado", negativo)
        with self.assertRaises(ValueError):
            host.validar_pacote_modo("interromper-real", negativo)
        for nome in pacote.ARQUIVOS:
            with self.subTest(nome=nome), self.assertRaises(ValueError):
                host.validar_pacote_modo("interromper-recusado", dict(negativo, **{nome: b"outro"}))

    def test_hash_enquadrado_utf8_ordem_ausencias_e_strings(self):
        self.assertEqual(host.hash_snapshot({}), pacote.sha256(b""))
        self.assertEqual(host.hash_snapshot({"ç": "😀", "a": ""}),
                         pacote.sha256("1:a0:2:ç4:😀".encode("utf-8")))
        self.assertEqual(host.hash_snapshot({"ç": "😀", "a": ""}), host.hash_snapshot({"a": "", "ç": "😀"}))
        self.assertNotEqual(host.hash_snapshot({}), host.hash_snapshot({"a": ""}))
        self.assertNotEqual(host.hash_snapshot({"a": "bc"}), host.hash_snapshot({"ab": "c"}))
        for invalido in (None, [], {"a": False}, {"a": 1}, {"a": ["v"]}):
            with self.subTest(invalido=invalido), self.assertRaises(ValueError):
                host.hash_snapshot(invalido)

    def test_pronto_rejeita_uuid_tipo_fase_pacote_hash_e_pid_invalidos(self):
        p = pronto_exemplo()
        def validar(valor):
            host.validar_pronto(valor, "interromper-real", p["execucao"], p["marcador"], p["identidade_sha256"])
        validar(p)
        for campo, valor in (("execucao", str(uuid.uuid4())), ("marcador", str(uuid.uuid4())),
                             ("tipo", "RECUSADO"), ("fase", "outro"), ("status", "APROVADO"),
                             ("versao", True), ("pid", True), ("pid", 0), ("pid", "123"),
                             ("start_elapsed_ms", -1), ("start_ticks", "1;id"), ("start_ticks", 124),
                             ("processo_uuid", "x"), ("identidade_sha256", "b" * 64),
                             ("snapshot_sha256", "b" * 64), ("snapshot", {}), ("alterado", p["snapshot"])):
            with self.subTest(campo=campo, valor=valor), self.assertRaises(ValueError):
                validar(dict(p, **{campo: valor}))
        for campo in p:
            incompleto = dict(p)
            del incompleto[campo]
            with self.subTest(ausente=campo), self.assertRaises(ValueError):
                validar(incompleto)

    def test_run_as_sem_caminho_ou_comando_remoto_injetado(self):
        p = pronto_exemplo()
        argv = host.leitura_pronto(p["execucao"], p["marcador"])
        self.assertEqual(argv[:5], ["exec-out", "run-as", host.APP, "sh", "-c"])
        script, = shlex.split(argv[5])
        self.assertIn(f'files/etapa4-interrupcao/{p["execucao"]}-{p["marcador"]}.json', script)
        self.assertIn('[ -L "$p" ]', script)
        self.assertIn('pwd -P', script)
        self.assertNotIn("rm ", script)
        for malicioso in ("../etc", "x;am force-stop outro", p["execucao"].upper(), ""):
            with self.subTest(malicioso=malicioso), self.assertRaises(ValueError):
                host.leitura_pronto(malicioso, p["marcador"])

    def test_pid_exato_vivo_stat_com_parenteses_e_reuso(self):
        p = pronto_exemplo()
        stat = "123 (nome (com) parenteses) S " + "0 " * 18 + "124 0"
        host.validar_processo_vivo(p, "123\n", stat)
        for pids, dados in (("", stat), ("123 124", stat), ("124", stat),
                            ("123", stat.replace("124 0", "125 0")),
                            ("123", stat.replace(") S ", ") Z ")), ("123", "truncado"),
                            ("123", stat.replace("123 (", "124 ("))):
            with self.subTest(pids=pids, dados=dados), self.assertRaises(ValueError):
                host.validar_processo_vivo(p, pids, dados)

    def test_fim_anormal_exige_inicio_unico_crash_nao_adb_exitcode(self):
        inicio = inicio_instrumentacao("Classe", "metodo")
        for rc in (0, 1):
            fim = host.validar_fim_interrompido(inicio + FIM_CRASH, rc, "Classe", "metodo")
            self.assertEqual(fim["status"], "INTERRUPCAO_ESPERADA")
            self.assertIs(fim["teste_interrompido_aprovado"], False)
        for texto, rc in ((inicio, 0), (FIM_CRASH, 0), (inicio + FIM_CRASH, -15),
                          (inicio + FIM_CRASH, 2), (inicio + "device offline", 1),
                          (inicio + FIM_CRASH.replace("CODE: 0", "CODE: -1"), 0),
                          (inicio + FIM_CRASH.replace("Process crashed.", "Permission denied"), 0),
                          (inicio.replace("test=metodo", "test=outro") + FIM_CRASH, 0),
                          (inicio + FIM_CRASH + "OK (1 test)\n", 0),
                          (inicio + FIM_CRASH + "INSTRUMENTATION_RESULT: etapa4_prova={}\n", 0)):
            with self.subTest(texto=texto, rc=rc), self.assertRaises(ValueError):
                host.validar_fim_interrompido(texto, rc, "Classe", "metodo")
        for status in (0, -1, -2, -3, -4, 1):
            with self.subTest(status=status), self.assertRaises(ValueError):
                host.validar_fim_interrompido(inicio + f"INSTRUMENTATION_STATUS_CODE: {status}\n" + FIM_CRASH,
                                            0, "Classe", "metodo")

    def test_recuperacao_exige_snapshot_exato_processo_distinto_e_mesmo_journal(self):
        for modo in host.INTERRUPCOES:
            p = pronto_exemplo(modo)
            marcador = str(uuid.uuid4())
            prova = prova_recuperada(p, modo, marcador)
            host.validar_recuperacao_interrupcao(prova, p, modo, marcador)
            for campo, valor in (("pronto", dict(p, snapshot={})), ("marcador", p["marcador"]),
                                 ("processo_uuid", p["processo_uuid"]), ("snapshot_exato", False),
                                 ("verificado_antes_de_alterar", False), ("snapshot_restaurado", {}),
                                 ("snapshot_restaurado_sha256", "x"), ("start_ticks", None)):
                ruim = dict(prova, interrupcao=dict(prova["interrupcao"], **{campo: valor}))
                with self.subTest(modo=modo, campo=campo), self.assertRaises(ValueError):
                    host.validar_recuperacao_interrupcao(ruim, p, modo, marcador)
            for campo in ("start_elapsed_ms", "start_ticks"):
                ruim = dict(prova, interrupcao=dict(prova["interrupcao"], pid=p["pid"], **{campo: p[campo]}))
                with self.subTest(modo=modo, mesmo=campo), self.assertRaises(ValueError):
                    host.validar_recuperacao_interrupcao(ruim, p, modo, marcador)
            # Reciclagem do PID é válida somente com início E UUID diferentes.
            reciclado = dict(prova, interrupcao=dict(prova["interrupcao"], pid=p["pid"]))
            host.validar_recuperacao_interrupcao(reciclado, p, modo, marcador)


class AndroidFalso:
    """Simula protocolo externo, nunca executa subprocesso/adb. Também testa a ordem host."""
    def __init__(self, modo, falha=None):
        self.modo, self.falha = modo, falha
        self.eventos, self.leituras, self.checou = [], 0, 0
        self.returncode = None
        self.pid = 9999  # PID do cliente adb local, nunca o PID Android do PRONTO.

    def popen(self, argv, *, stdout, stderr, start_new_session):
        self.argv, self.stdout = argv, stdout
        def arg(nome):
            return argv[argv.index(nome) + 1]
        self.pronto = pronto_exemplo(self.modo, arg("etapa4Execucao"), arg("etapa4Marcador"))
        self.pronto["identidade_sha256"] = arg("etapa4IdentidadeSha256")
        classe, metodo, _, _ = host.INTERRUPCOES[self.modo]
        self.stdout.write(inicio_instrumentacao(classe, metodo).encode())
        self.stdout.flush()
        self.eventos.append("preparar")
        self.assert_sessao = start_new_session
        if self.falha == "saida_precoce":
            self.returncode = 0
        return self

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        if self.returncode is None:
            raise subprocess.TimeoutExpired(self.argv, timeout)
        return self.returncode

    def terminate(self):
        self.eventos.append("terminate_cliente")
        self.returncode = -15

    def kill(self):
        self.eventos.append("kill_cliente")
        self.returncode = -9

    def adb(self, *partes, nome, timeout=120):
        if partes[:3] == ("exec-out", "run-as", host.APP) and partes[3] == "sh":
            self.leituras += 1
            self.eventos.append("ler_pronto")
            if self.falha == "run_as":
                raise ValueError("run-as: package not debuggable")
            if self.leituras == 1 or self.falha == "sem_pronto":
                return "ETAPA4_AUSENTE\n"
            p = dict(self.pronto)
            if self.falha == "uuid_errado":
                p["marcador"] = str(uuid.uuid4())
            if self.falha == "mudou_pronto" and nome == "pronto-pre-kill":
                p["pid"] += 1
            if self.falha == "json_parcial":
                return '{"status":'
            if self.falha == "json_duplicado":
                return '{"status":"OUTRO",' + json.dumps(p)[1:]
            return json.dumps(p)
        if partes == ("shell", "pidof", host.APP):
            self.eventos.append("pid")
            return "456" if self.falha == "pid_diferente" else "123"
        if partes == ("exec-out", "run-as", host.APP, "cat", "/proc/123/stat"):
            self.eventos.append("stat")
            if self.falha == "morre_pre_kill" and self.checou:
                self.returncode = 1
            return "123 (target) S " + "0 " * 18 + ("999" if self.falha == "pid_reusado" else "124")
        if partes == ("shell", "am", "force-stop", host.APP):
            self.eventos.append("force-stop")
            if self.falha == "timeout_fim":
                return ""
            self.returncode = 0
            texto = FIM_CRASH
            if self.falha == "aprovacao":
                texto = "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n" + FIM_CRASH
            elif self.falha == "desconexao":
                texto = "error: device offline\n"
            self.stdout.write(texto.encode())
            self.stdout.flush()
            return ""
        raise AssertionError(f"Comando não esperado/permitido: {partes}")

    def emulador(self):
        self.eventos.append("revalidar_emulador")
        self.checou += 1
        if self.falha == "serial":
            raise ValueError("Serial divergiu")

    def instrumentar(self, classe, metodo, optin, marcador):
        return ["adb-falso", "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r",
                "-e", "class", classe + "#" + metodo, "-e", optin, "true",
                "-e", "etapa4Execucao", TestRecuperacaoSeparada.UUID, "-e", "etapa4Marcador", marcador,
                "-e", "etapa4IdentidadeSha256", "a" * 64, host.RUNNER]

    def recuperar(self, modo, marcador):
        self.eventos.append("recuperar")
        if self.falha == "recuperacao":
            raise ValueError("snapshot não restaurado")
        return prova_recuperada(self.pronto, self.modo, marcador)


class TestMaquinaInterrupcao(unittest.TestCase):
    def executar(self, falso, saida, registro):
        with patch.object(host.subprocess, "Popen", side_effect=falso.popen), \
                patch.object(host.subprocess, "run", side_effect=AssertionError("Subprocesso real proibido")), \
                patch.object(host.time, "monotonic", side_effect=[0, 0, 1, 121] if falso.falha == "sem_pronto" else None,
                             return_value=0):
            host.ensaiar_interrupcao(modo=falso.modo, execucao=TestRecuperacaoSeparada.UUID,
                                    identidade_sha="a" * 64, saida=saida, registro=registro, salvar=lambda: None,
                                    adb=falso.adb, emulador=falso.emulador, instrumentar=falso.instrumentar,
                                    recuperar=falso.recuperar)

    def test_ambos_journals_interrompem_so_apos_pronto_e_recuperam_sem_instalar(self):
        for modo in host.INTERRUPCOES:
            with self.subTest(modo=modo), tempfile.TemporaryDirectory() as tmp:
                falso = AndroidFalso(modo)
                registro = {"comandos": []}
                self.executar(falso, Path(tmp), registro)
                self.assertEqual(falso.eventos, ["preparar", "ler_pronto", "ler_pronto", "pid", "stat",
                                               "revalidar_emulador", "ler_pronto", "pid", "stat",
                                               "force-stop", "recuperar"])
                self.assertIs(falso.assert_sessao, True)
                self.assertEqual(registro["interrupcao"]["status"], "INTERRUPCAO_ESPERADA")
                self.assertIs(registro["interrupcao"]["teste_interrompido_aprovado"], False)
                self.assertEqual(registro["recuperacao"]["status"], "RECUPERACAO_VALIDADA")

    def test_sem_prontidao_fresca_e_viva_nunca_force_stop_nem_recupera(self):
        for falha in ("saida_precoce", "run_as", "sem_pronto", "uuid_errado", "json_parcial", "json_duplicado",
                      "pid_diferente", "pid_reusado", "mudou_pronto", "morre_pre_kill", "serial"):
            with self.subTest(falha=falha), tempfile.TemporaryDirectory() as tmp:
                falso = AndroidFalso("interromper-real", falha)
                registro = {"comandos": []}
                with self.assertRaises(ValueError):
                    self.executar(falso, Path(tmp), registro)
                self.assertNotIn("force-stop", falso.eventos)
                self.assertNotIn("recuperar", falso.eventos)
                self.assertIs(registro["interrupcao"]["force_stop_enviado"], False)
                self.assertIsNotNone(falso.poll())

    def test_kill_sem_fim_anormal_ou_com_aprovacao_nao_recupera(self):
        for falha in ("timeout_fim", "aprovacao", "desconexao"):
            with self.subTest(falha=falha), tempfile.TemporaryDirectory() as tmp:
                falso = AndroidFalso("interromper-real", falha)
                registro = {"comandos": []}
                with self.assertRaises((ValueError, subprocess.TimeoutExpired)):
                    self.executar(falso, Path(tmp), registro)
                self.assertIn("force-stop", falso.eventos)
                self.assertNotIn("recuperar", falso.eventos)
                self.assertNotEqual(registro["interrupcao"]["status"], "INTERRUPCAO_ESPERADA")
                self.assertIs(registro["interrupcao"]["teste_interrompido_aprovado"], False)

    def test_recuperacao_falha_nao_apaga_interrupcao_confirmada(self):
        with tempfile.TemporaryDirectory() as tmp:
            falso = AndroidFalso("interromper-recusado", "recuperacao")
            registro = {"comandos": []}
            with self.assertRaises(ValueError):
                self.executar(falso, Path(tmp), registro)
            self.assertEqual(registro["interrupcao"]["status"], "INTERRUPCAO_ESPERADA")
            self.assertEqual(registro["recuperacao"]["status"], "FALHOU")


class TestCliInterrupcao(unittest.TestCase):
    def executar_cli(self, modo, falha=None):
        with tempfile.TemporaryDirectory() as tmp:
            raiz = Path(tmp)
            privado = raiz / "pacote"
            privado.mkdir()
            dados = bytes_pacote()
            if modo == "interromper-real":
                dados[pacote.MODELO] += b"grafo-nao-executado-teste-host"
                sidecar = pacote.ler_json(dados[pacote.SIDECAR])
                sidecar["sha256"] = pacote.sha256(dados[pacote.MODELO])
                dados[pacote.SIDECAR] = json.dumps(sidecar).encode()
                id = pacote.ler_json(dados[pacote.IDENTIDADE])
                id.update(modelo_sha256=sidecar["sha256"], sidecar_sha256=pacote.sha256(dados[pacote.SIDECAR]))
                dados[pacote.IDENTIDADE] = json.dumps(id).encode()
            for nome, bruto in dados.items():
                (privado / nome).write_bytes(bruto)
            aapt, app, test = (raiz / n for n in ("aapt", "app.apk", "test.apk"))
            aapt.touch()
            with zipfile.ZipFile(app, "w") as z:
                for nome, bruto in dados.items():
                    z.writestr("assets/" + nome, bruto)
            with zipfile.ZipFile(test, "w") as z:
                z.writestr("sem-video.txt", "Ensaio não exige sinais.mp4")
            falso = AndroidFalso(modo, falha)
            comandos = []

            def run(argv, **kwargs):
                comandos.append(argv)
                if argv[0] == str(aapt):
                    if argv[2] == "badging":
                        nome = host.TEST_APP if "test-atual.apk" in argv[-1] else host.APP
                        texto = f"package: name='{nome}'\napplication-debuggable\n"
                    else:
                        texto = f'android:targetPackage="{host.APP}"\nandroid:name="androidx.test.runner.AndroidJUnitRunner"'
                    return subprocess.CompletedProcess(argv, 0, texto.encode())
                self.assertEqual(argv[:3], ["adb-falso", "-s", "emulator-5554"])
                p = argv[3:]
                if p == ["get-state"]:
                    texto = "device"
                elif p == ["get-serialno"]:
                    texto = "emulator-5554"
                elif p == ["shell", "getprop", "ro.kernel.qemu"]:
                    texto = "1"
                elif p[:1] == ["install"]:
                    falso.eventos.append("install")
                    texto = "Success"
                elif p[:3] == ["shell", "pm", "path"]:
                    texto = "package:/data/app/" + p[3] + ".apk"
                elif p[:2] == ["shell", "sha256sum"]:
                    caminho = test if p[2].endswith(host.TEST_APP + ".apk") else app
                    texto = ("0" * 64 if falha == "apk_instalado" else host.hash_arquivo(caminho)) + " " + p[2]
                elif p[:3] == ["shell", "am", "force-stop"] and "preparar" not in falso.eventos:
                    self.assertEqual(p[3:], [host.APP])
                    texto = ""
                elif p[:3] == ["shell", "am", "instrument"]:
                    self.assertEqual(falso.eventos[-1], "force-stop")
                    nome, marcador = p[p.index("class") + 1], p[p.index("etapa4Marcador") + 1]
                    classe, metodo = nome.split("#")
                    prova = falso.recuperar(host.INTERRUPCOES[modo][3], marcador)
                    inicio = inicio_instrumentacao(classe, metodo)
                    texto = inicio + inicio.replace("STATUS_CODE: 1", "STATUS_CODE: 0") + \
                        f'INSTRUMENTATION_RESULT: etapa4_prova={json.dumps(prova)}\n' + \
                        "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
                else:
                    texto = falso.adb(*p, nome="pronto-pre-kill" if falso.checou else "poll")
                return subprocess.CompletedProcess(argv, 0, texto.encode())

            argv = [str(Path(host.__file__).resolve()), "--modo", modo, "--serial", "emulator-5554",
                    "--adb", "adb-falso", "--aapt", str(aapt), "--app-apk", str(app), "--test-apk", str(test),
                    "--pacote", str(privado), "--saida", str(raiz / "saida")]
            with patch("sys.argv", argv), patch.object(host.subprocess, "run", side_effect=run), \
                    patch.object(host.subprocess, "Popen", side_effect=falso.popen), patch("builtins.print"):
                if falha:
                    with self.assertRaises(ValueError):
                        host.main()
                else:
                    host.main()
            registro = json.loads((raiz / "saida/execucao.json").read_text())
            self.assertEqual(registro["status"], "FALHOU" if falha else "INTERRUPCAO_ESPERADA_E_RECUPERACAO_VALIDADA")
            if not falha:
                self.assertEqual(falso.eventos[:3], ["install", "install", "preparar"])
                self.assertNotIn("install", falso.eventos[3:])
                self.assertEqual(len(registro["provas"]), 1)  # Só recuperação: interrompido não gerou prova aprovada.
                self.assertEqual(registro["provas"][0]["status"], "RECUPERADO_SEM_EVIDENCIA")
                self.assertIs(registro["interrupcao"]["teste_interrompido_aprovado"], False)
            if falha == "apk_instalado":
                self.assertNotIn("preparar", falso.eventos)
            for comando in comandos:
                self.assertNotIn("uninstall", comando)
                self.assertNotIn("clear", comando)
                self.assertNotIn("kill-server", comando)

    def test_cli_ambos_modos_preserva_validacao_apks_sem_video_e_sem_reinstalar_entre_fases(self):
        for modo in host.INTERRUPCOES:
            with self.subTest(modo=modo):
                self.executar_cli(modo)

    def test_cli_aborta_apk_instalado_divergente_antes_de_preparar(self):
        self.executar_cli("interromper-real", "apk_instalado")

    def test_cli_nao_chama_aprovado_a_um_teste_interrompido_que_passou(self):
        self.executar_cli("interromper-recusado", "aprovacao")

    def test_cli_recuperacao_falha_gera_json_falho(self):
        self.executar_cli("interromper-real", "recuperacao")


if __name__ == "__main__":
    unittest.main()
"""Host opt-in stdlib: instala APKs explícitos e instrumenta somente um emulador alvo.

Não compila, não usa Gradle/UTP, não desinstala, não faz pm clear. Nunca aceita apenas
o exit code do adb: valida protocolo JUnit/instrumentation e prova fresca do Android.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import uuid
import zipfile

import pacote_classificador_privado as pacote
from gerar_pacote_classificador_recusado import bytes_pacote

APP = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
TEST_APP = APP + ".test"
RUNNER = TEST_APP + "/androidx.test.runner.AndroidJUnitRunner"
REAL = APP + ".libras.RealExperimentalEntreProcessosTest"
RECUSADO = APP + ".libras.RecusadoVideoOrquestradorTest"
RECUPERACOES = {
    "recuperar": (REAL, "limparEstadoInterrompido", "etapa4RecuperarPersistencia", "REAL"),
    "recuperar-recusado": (RECUSADO, "recuperarEstadoRecusadoInterrompido", "etapa4RecuperarRecusado", "RECUSADO"),
}
INTERRUPCOES = {
    "interromper-real": (REAL, "prepararInterrupcaoReal", "etapa4InterromperReal", "recuperar"),
    "interromper-recusado": (RECUSADO, "prepararInterrupcaoRecusado", "etapa4InterromperRecusado", "recuperar-recusado"),
}


def hash_snapshot(snapshot: dict) -> str:
    """Mesmo enquadramento UTF-8 do Android; não confunde ausência com defaults/string vazia."""
    exigir(isinstance(snapshot, dict) and all(isinstance(k, str) and isinstance(v, str)
                                             for k, v in snapshot.items()), "Snapshot deve conter apenas strings")
    h = hashlib.sha256()
    for chave in sorted(snapshot, key=lambda k: k.encode("utf-8")):
        for texto in (chave, snapshot[chave]):
            bruto = texto.encode("utf-8")
            h.update(str(len(bruto)).encode("ascii") + b":" + bruto)
    return h.hexdigest()


def validar_pronto(pronto: dict, modo: str, execucao: str, marcador: str, identidade_sha: str) -> None:
    exigir(isinstance(pronto, dict), "PRONTO deve ser objeto")
    _, metodo, _, recuperacao = INTERRUPCOES[modo]
    for chave, valor in dict(versao=1, status="PRONTO", tipo=RECUPERACOES[recuperacao][3],
                             fase=metodo, execucao=execucao, marcador=marcador,
                             identidade_sha256=identidade_sha).items():
        exigir(type(pronto.get(chave)) is type(valor) and pronto[chave] == valor,
               f"PRONTO inválido: {chave}")
    for chave in ("pid", "start_elapsed_ms"):
        exigir(type(pronto.get(chave)) is int and pronto[chave] > 0, f"Processo inválido: {chave}")
    exigir(isinstance(pronto.get("start_ticks"), str) and
           re.fullmatch(r"[1-9][0-9]*", pronto["start_ticks"]) is not None, "Start ticks inválido")
    processo_uuid = pronto.get("processo_uuid")
    exigir(isinstance(processo_uuid, str) and str(uuid.UUID(processo_uuid)) == processo_uuid,
           "UUID de processo inválido")
    for chave in ("snapshot", "alterado"):
        exigir(hash_snapshot(pronto.get(chave)) == pronto.get(chave + "_sha256"), f"Hash {chave} divergiu")
    exigir(pronto["snapshot"] != pronto["alterado"], "PRONTO não comprova alteração")


def leitura_pronto(execucao: str, marcador: str) -> list[str]:
    """run-as somente target, leitura allowlisted. Nenhum caminho/comando vem do JSON remoto.

    sh -c recebe UM literal cotado para o shell remoto do adb; UUID não pode injetar shell.
    Não segue links em files, na pasta ou no marcador; falha de run-as nunca vira 'ausente'.
    """
    for valor in (execucao, marcador):
        exigir(str(uuid.UUID(valor)) == valor, "Caminho run-as exige UUID canônico")
    caminho = f"files/etapa4-interrupcao/{execucao}-{marcador}.json"
    script = (f'case "$(pwd -P)" in /data/user/0/{APP}|/data/data/{APP}) ;; *) exit 41;; esac; '
              f'for p in files files/etapa4-interrupcao {caminho}; do '
              'if [ -L "$p" ]; then exit 42; fi; done; '
              'for p in files files/etapa4-interrupcao; do '
              'if [ -e "$p" ] && [ ! -d "$p" ]; then exit 43; fi; done; '
              f'if [ ! -e {caminho} ]; then printf "ETAPA4_AUSENTE\\n"; '
              f'elif [ -f {caminho} ]; then cat {caminho}; else exit 43; fi')
    return ["exec-out", "run-as", APP, "sh", "-c", shlex.quote(script)]


def validar_processo_vivo(pronto: dict, pids: str, stat: str) -> None:
    # pidof nome EXATO + start ticks observado em /proc: PID reciclado não autoriza kill.
    exigir(pids.split() == [str(pronto["pid"])], "PID target ausente, concorrente ou diferente do PRONTO")
    inicio, separador, campos = stat.strip().rpartition(") ")
    exigir(bool(separador) and inicio.startswith(str(pronto["pid"]) + " ("), "Stat de outro PID")
    valores = campos.split()
    exigir(len(valores) >= 20 and valores[0] not in ("Z", "X", "x") and
           valores[19] == pronto["start_ticks"], "Processo morreu ou PID foi reutilizado")


def validar_inicio_interrompido(texto: str, classe: str, metodo: str) -> None:
    codigos = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", texto, re.MULTILINE)
    exigir(codigos == ["1"], f"Teste interrompido deve ter só início, nunca aprovação/skip/falha JUnit: {codigos}")
    inicio = texto.split("INSTRUMENTATION_STATUS_CODE:", 1)[0]
    for chave, valor in (("class", classe), ("test", metodo), ("numtests", "1"), ("current", "1")):
        exigir(re.findall(rf"^INSTRUMENTATION_STATUS: {chave}=(.*)$", inicio, re.MULTILINE) == [valor],
               f"Início interrompido inválido: {chave}")
    exigir(not re.search(r"\bOK\s*\(|etapa4_prova=|FAILURES!!!|INSTRUMENTATION_STATUS: (?:stack|Error)=",
                         texto, re.IGNORECASE), "Interrupção não pode conter aprovação/prova/falha JUnit")


def validar_fim_interrompido(texto: str, returncode: int, classe: str, metodo: str) -> dict:
    validar_inicio_interrompido(texto, classe, metodo)
    exigir(returncode in (0, 1), "Cliente adb encerrado por sinal/erro inesperado")
    exigir(re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", texto, re.MULTILINE) == ["0"],
           "Falta fim anormal Android (código 0); timeout/desconexão não são evidência")
    mensagens = re.findall(r"^INSTRUMENTATION_RESULT: shortMsg=(.*)$", texto, re.MULTILINE)
    exigir(len(mensagens) == 1 and re.fullmatch(r"Process (?:crashed|killed)\.?", mensagens[0]) is not None,
           "Fim não confirma interrupção do processo")
    return dict(status="INTERRUPCAO_ESPERADA", teste_interrompido_aprovado=False,
                adb_returncode=returncode, instrumentation_code=0, short_msg=mensagens[0])


def validar_recuperacao_interrupcao(prova: dict, pronto: dict, modo: str, marcador: str) -> None:
    validar_recuperacao(prova, INTERRUPCOES[modo][3], pronto["identidade_sha256"])
    exigir(prova.get("execucao") == pronto["execucao"], "Recuperação de outro dono")
    evidencia = prova.get("interrupcao")
    exigir(isinstance(evidencia, dict) and evidencia.get("pronto") == pronto,
           "Recuperação não leu o PRONTO exato do journal")
    exigir(evidencia.get("marcador") == marcador and marcador != pronto["marcador"], "Marcador de recuperação divergiu")
    for chave in ("pid", "start_elapsed_ms"):
        exigir(type(evidencia.get(chave)) is int and evidencia[chave] > 0, "Processo recuperador inválido")
    ticks = evidencia.get("start_ticks")
    exigir(isinstance(ticks, str) and re.fullmatch(r"[1-9][0-9]*", ticks) is not None,
           "Start ticks recuperador inválido")
    exigir(evidencia["pid"] != pronto["pid"] or evidencia["start_elapsed_ms"] != pronto["start_elapsed_ms"],
           "Recuperação no mesmo PID/start")
    exigir(evidencia["pid"] != pronto["pid"] or ticks != pronto["start_ticks"],
           "Recuperação no mesmo PID/start ticks")
    novo_uuid = evidencia.get("processo_uuid")
    exigir(isinstance(novo_uuid, str) and str(uuid.UUID(novo_uuid)) == novo_uuid and
           novo_uuid != pronto["processo_uuid"], "UUID estático não mudou")
    exigir(evidencia.get("verificado_antes_de_alterar") is True and evidencia.get("snapshot_exato") is True,
           "Falta validação anterior à escrita/restauração exata")
    exigir(evidencia.get("snapshot_restaurado") == pronto["snapshot"] and
           evidencia.get("snapshot_restaurado_sha256") == hash_snapshot(pronto["snapshot"]),
           "Snapshot restaurado inexato")


def ensaiar_interrupcao(*, modo: str, execucao: str, identidade_sha: str, saida: Path,
                       registro: dict, salvar, adb, emulador, instrumentar, recuperar,
                       timeout_pronto: int = 120) -> None:
    """Máquina host: só PRONTO fresco + processo vivo permitem force-stop; sem UTP entre fases."""
    classe, metodo, optin, modo_recuperacao = INTERRUPCOES[modo]
    marcador = str(uuid.uuid4())
    argv = instrumentar(classe, metodo, optin, marcador)
    registro["interrupcao"] = dict(status="AGUARDANDO_PRONTO", marcador=marcador,
                                   teste_interrompido_aprovado=False, force_stop_enviado=False)
    registro["recuperacao"] = dict(status="NAO_INICIADA", modo=modo_recuperacao)
    registro["comandos"].append(argv)
    salvar()
    log = saida / (metodo + ".log")
    with log.open("xb") as stream:
        processo = subprocess.Popen(argv, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            limite = time.monotonic() + timeout_pronto
            tentativa = 0

            def texto_log():
                return log.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")

            def vivo(pronto):
                pids = adb("shell", "pidof", APP, nome="interrupcao-pid", timeout=10)
                stat = adb("exec-out", "run-as", APP, "cat", f'/proc/{pronto["pid"]}/stat',
                           nome="interrupcao-stat", timeout=10)
                validar_processo_vivo(pronto, pids, stat)
                exigir(processo.poll() is None, "Instrumentation terminou antes do force-stop")
                texto = texto_log()
                validar_inicio_interrompido(texto, classe, metodo)
                exigir("INSTRUMENTATION_CODE:" not in texto and "INSTRUMENTATION_RESULT:" not in texto,
                       "Instrumentation já finalizou")

            while True:
                exigir(processo.poll() is None, "Instrumentation terminou antes de PRONTO; ver log")
                exigir(time.monotonic() < limite, "Timeout esperando PRONTO; journal preservado")
                tentativa += 1
                texto = adb(*leitura_pronto(execucao, marcador), nome=f"pronto-{tentativa}", timeout=10)
                if texto.strip() != "ETAPA4_AUSENTE":
                    pronto = pacote.ler_json(texto.encode("utf-8"))
                    validar_pronto(pronto, modo, execucao, marcador, identidade_sha)
                    vivo(pronto)
                    break
                # Polling por condição, não espera fixa para acertar o kill. wait detecta saída precoce.
                try:
                    processo.wait(timeout=min(0.1, max(0.001, limite - time.monotonic())))
                except subprocess.TimeoutExpired:
                    continue
                raise ValueError("Instrumentation terminou sem PRONTO")

            registro["interrupcao"].update(status="PRONTO_VALIDADO", pronto=pronto)
            salvar()
            emulador()  # Revalidar serial/qemu imediatamente antes de autorizar o único target.
            exigir(pacote.ler_json(adb(*leitura_pronto(execucao, marcador), nome="pronto-pre-kill", timeout=10)
                                  .encode("utf-8")) == pronto,
                   "PRONTO mudou antes do force-stop")
            vivo(pronto)
            registro["interrupcao"]["force_stop_enviado"] = True
            salvar()
            adb("shell", "am", "force-stop", APP, nome="force-stop-interrupcao", timeout=10)
            retorno = processo.wait(timeout=60)
            fim = validar_fim_interrompido(texto_log(), retorno, classe, metodo)
            registro["interrupcao"].update(fim)
            salvar()
        finally:
            # Timeout/Ctrl-C/falha: recolher só o cliente adb filho; não matar target automaticamente,
            # não apagar journal nem fingir que desconexão local é interrupção Android.
            if processo.poll() is None:
                processo.terminate()
                try:
                    processo.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    processo.kill()
                    processo.wait(timeout=5)

    registro["recuperacao"]["status"] = "EM_EXECUCAO"
    salvar()
    try:
        novo_marcador = str(uuid.uuid4())
        prova = recuperar(modo_recuperacao, novo_marcador)
        validar_recuperacao_interrupcao(prova, pronto, modo, novo_marcador)
        registro["recuperacao"].update(status="RECUPERACAO_VALIDADA", prova=prova)
    except BaseException as erro:
        registro["recuperacao"].update(status="FALHOU", erro=repr(erro))
        raise
    finally:
        salvar()


def exigir(condicao: bool, mensagem: str) -> None:
    if not condicao:
        raise ValueError(mensagem)


def hash_arquivo(arquivo: Path) -> str:
    h = hashlib.sha256()
    with arquivo.open("rb") as stream:
        for bloco in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(bloco)
    return h.hexdigest()


def validar_instrumentacao(texto: str, classe: str, metodo: str, execucao: str) -> dict:
    """-w -r: exatamente um início e um fim bem-sucedido; assumptions/skips são falhas."""
    exigir(not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|"
                         r"shortMsg=|Process crashed|FAILURE|Error:|INSTRUMENTATION_RESULT: stream=.*Error",
                         texto, re.IGNORECASE), "Instrumentation relatou falha/erro")
    finais = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", texto, re.MULTILINE)
    exigir(finais == ["-1"], f"Exit instrumentation deve ser -1 (RESULT_OK), recebido {finais}")
    codigos = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", texto, re.MULTILINE)
    exigir(codigos == ["1", "0"], f"Exige start=1 e sucesso=0; skips/assumptions falham: {codigos}")
    blocos = re.split(r"^INSTRUMENTATION_STATUS_CODE: -?\d+\s*$", texto, flags=re.MULTILINE)
    for bloco in blocos[:2]:
        for chave, esperado in (("numtests", "1"), ("current", "1"), ("class", classe), ("test", metodo)):
            valores = re.findall(rf"^INSTRUMENTATION_STATUS: {chave}=(.*)$", bloco, re.MULTILINE)
            exigir(valores == [esperado], f"Status {chave}: esperado {esperado!r}, recebido {valores}")
    exigir(re.search(r"^OK \(1 test\)\s*$", texto, re.MULTILINE) is not None,
           "Resumo JUnit OK (1 test) ausente")
    provas = re.findall(r"^INSTRUMENTATION_RESULT: etapa4_prova=(.*)$", texto, re.MULTILINE)
    exigir(len(provas) == 1, "Prova fresca ausente ou duplicada")
    prova = pacote.ler_json(provas[0].encode("utf-8"))
    exigir(isinstance(prova, dict), "Prova deve ser objeto JSON")
    exigir(prova.get("execucao") == execucao and prova.get("fase") == metodo, "UUID/fase da prova diverge")
    return prova


def validar_processos(um: dict, dois: dict) -> None:
    exigir(um["pid"] != dois["pid"] or um["start_elapsed_ms"] != dois["start_elapsed_ms"],
           "Fases rodaram no mesmo PID/start")
    exigir(um["processo_uuid"] != dois["processo_uuid"], "UUID estático do processo repetido")
    exigir(um["marcador"] != dois["marcador"], "Marcador de invocação repetido")
    exigir(dois["processo_anterior"] == {k: um[k] for k in ("pid", "start_elapsed_ms", "processo_uuid")},
           "Fase 2 não leu o marcador persistido pela fase 1")
    for chave in ("execucao", "limiar", "identidade_sha256", "identidade"):
        exigir(um[chave] == dois[chave], f"Persistência divergiu: {chave}")
    exigir(dois.get("verificado_antes_de_alterar") is True and dois.get("cleanup") is True,
           "Verificação anterior à escrita/limpeza final ausente")


def validar_recuperacao(prova: dict, modo: str, identidade_sha: str) -> None:
    _, metodo, _, tipo = RECUPERACOES[modo]
    exigir(prova.get("fase") == metodo and prova.get("tipo") == tipo, "Recuperação de outro tipo/método")
    exigir(prova.get("status") == "RECUPERADO_SEM_EVIDENCIA" and prova.get("cleanup") is True,
           "Recuperação não confirmou restauração; não é evidência de aprovação")
    exigir(prova.get("identidade_sha256") == identidade_sha, "Recuperação pertence a outro pacote")


def validar_pacote_modo(modo: str, esperado: dict[str, bytes]) -> None:
    negativo = bytes_pacote()
    if modo in ("recusado", "recuperar-recusado", "interromper-recusado"):
        exigir(esperado == negativo, "RECUSADO/recuperação exige EXATAMENTE a fixture sintética v1")
    else:
        exigir(esperado[pacote.MODELO] != negativo[pacote.MODELO], "Não usar fixture inválida no modo real")


def validar_execucao(modo: str, execucao: str | None) -> None:
    if modo in RECUPERACOES:
        exigir(execucao is not None and str(uuid.UUID(execucao)) == execucao,
               "Recuperação exige UUID dono canônico em --execucao")
    else:
        exigir(execucao is None, "UUID fresco é gerado pelo host; não reutilizar --execucao")


def criar_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--modo", choices=("real", "recusado", *RECUPERACOES, *INTERRUPCOES), required=True)
    ap.add_argument("--serial", required=True, help="Somente emulator-NNNN; nunca escolhe device padrão")
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--aapt", type=Path, required=True, help="SDK build-tools/aapt (não aapt2)")
    ap.add_argument("--app-apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--pacote", type=Path, required=True, help="Pacote privado esperado nesta execução")
    ap.add_argument("--saida", type=Path, required=True, help="Diretório privado absoluto NOVO para evidências/APKs")
    ap.add_argument("--execucao", help="Só recuperar/recuperar-recusado: UUID dono da execução interrompida")
    ap.add_argument("--timeout-pronto", type=int, default=120, help="Limite host em segundos; não temporiza o kill")
    return ap


def main() -> None:
    args = criar_parser().parse_args()
    exigir(re.fullmatch(r"emulator-[0-9]+", args.serial) is not None, "Somente serial de emulador explícito")
    validar_execucao(args.modo, args.execucao)
    exigir(args.timeout_pronto > 0, "--timeout-pronto deve ser positivo")
    execucao = args.execucao or str(uuid.uuid4())
    for arquivo in (args.aapt, args.app_apk, args.test_apk):
        exigir(arquivo.is_absolute() and arquivo.is_file(), f"Exige arquivo absoluto existente: {arquivo}")
    pacote.verificar(args.pacote)
    esperado = {nome: (args.pacote / nome).read_bytes() for nome in pacote.ARQUIVOS}
    validar_pacote_modo(args.modo, esperado)
    identidade_sha = pacote.sha256(esperado[pacote.IDENTIDADE])
    saida = pacote.caminho_privado(args.saida)
    saida.parent.mkdir(parents=True, exist_ok=True)
    saida.mkdir(mode=0o700, exist_ok=False)
    registro = dict(execucao=execucao, modo=args.modo, serial=args.serial, status="EM_EXECUCAO",
                    identidade_sha256=identidade_sha, comandos=[], provas=[])
    destino = saida / "execucao.json"

    def salvar() -> None:
        temporario = saida / ".execucao.json.tmp"
        with temporario.open("w", encoding="utf-8") as stream:
            stream.write(json.dumps(registro, ensure_ascii=False, indent=2) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        temporario.replace(destino)

    def comando(argv: list[str], nome: str, timeout: int = 120) -> str:
        registro["comandos"].append(argv)
        salvar()
        try:
            resultado = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       timeout=timeout, check=False)
        except subprocess.TimeoutExpired as erro:
            (saida / f"{nome}.log").write_bytes(erro.output or b"")
            raise
        texto = resultado.stdout.decode("utf-8", errors="replace").replace("\r\n", "\n")
        (saida / f"{nome}.log").write_text(texto, encoding="utf-8")
        exigir(resultado.returncode == 0, f"Comando {nome} saiu {resultado.returncode}; ver log")
        return texto

    def adb(*partes: str, nome: str, timeout: int = 120) -> str:
        return comando([args.adb, "-s", args.serial, *partes], nome, timeout)

    def emulador() -> None:
        exigir(adb("get-state", nome="device-state").strip() == "device", "Device offline")
        exigir(adb("get-serialno", nome="device-serial").strip() == args.serial, "Serial divergiu")
        exigir(adb("shell", "getprop", "ro.kernel.qemu", nome="device-qemu").strip() == "1",
               "Destino não confirma ser emulador; abortando")

    def parar_alvo(nome: str) -> None:
        emulador()  # Reconfere antes de CADA force-stop; nunca all/users/servidor adb.
        adb("shell", "am", "force-stop", APP, nome=nome)

    def instrumentar(classe: str, metodo: str, optin: str, marcador: str) -> list[str]:
        return [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                    "-e", "class", f"{classe}#{metodo}", "-e", optin, "true",
                    "-e", "etapa4Execucao", execucao, "-e", "etapa4Marcador", marcador,
                    "-e", "etapa4IdentidadeSha256", identidade_sha, RUNNER]

    def fase(classe: str, metodo: str, optin: str, marcador: str | None = None) -> dict:
        marcador = marcador or str(uuid.uuid4())
        texto = comando(instrumentar(classe, metodo, optin, marcador), metodo, timeout=600)
        prova = validar_instrumentacao(texto, classe, metodo, execucao)
        exigir(prova.get("identidade_sha256") == identidade_sha, "Prova pertence a outro pacote")
        if args.modo in RECUPERACOES:
            validar_recuperacao(prova, args.modo, identidade_sha)
        if args.modo == "real":
            exigir(prova.get("marcador") == marcador, "Prova não contém marcador novo enviado pelo host")
        registro["provas"].append(prova)
        salvar()
        return prova

    salvar()
    print(f"UUID dono: {execucao}\nEvidências: {saida}", flush=True)
    try:
        emulador()
        apks = []
        for origem, nome, aplicativo in ((args.app_apk, "app-atual.apk", APP),
                                         (args.test_apk, "test-atual.apk", TEST_APP)):
            copia = saida / nome
            shutil.copyfile(origem, copia)
            hash_copia = hash_arquivo(copia)
            exigir(hash_copia == hash_arquivo(origem), "APK mudou durante o snapshot; interromper build concorrente")
            badging = comando([str(args.aapt), "dump", "badging", str(copia)], nome + "-badging")
            exigir(re.findall(r"^package: name='([^']+)'", badging, re.MULTILINE) == [aplicativo],
                   f"APK não é do app alvo: {aplicativo}")
            if aplicativo == APP:
                exigir("application-debuggable" in badging, "Exige APK debug")
                with zipfile.ZipFile(copia) as z:
                    for asset, bruto in esperado.items():
                        exigir(z.namelist().count("assets/" + asset) == 1, f"Asset ausente/duplicado: {asset}")
                        exigir(z.read("assets/" + asset) == bruto, f"APK antigo/outro pacote: {asset}")
            else:
                manifest = comando([str(args.aapt), "dump", "xmltree", str(copia), "AndroidManifest.xml"],
                                   "test-manifest")
                exigir(re.search(r'android:targetPackage[^\n]*="' + re.escape(APP) + r'"', manifest) is not None,
                       "Instrumentation não aponta ao app alvo")
                exigir('="androidx.test.runner.AndroidJUnitRunner"' in manifest, "Runner inesperado")
                if args.modo == "recusado":
                    with zipfile.ZipFile(copia) as z:
                        exigir(z.namelist().count("assets/sinais.mp4") == 1, "Falta vídeo de sinais no APK de testes")
                        registro["video_sha256"] = pacote.sha256(z.read("assets/sinais.mp4"))
            registro[nome] = dict(origem=str(origem), snapshot=str(copia), sha256=hash_copia)
            apks.append((copia, aplicativo, hash_copia))
        # Toda validação de ambos os APKs termina ANTES de modificar o emulador.
        for copia, aplicativo, sha in apks:
            texto = adb("install", "-r", "-t", str(copia), nome=aplicativo + "-install", timeout=300)
            exigir(re.search(r"^Success\s*$", texto, re.MULTILINE) is not None, "Instalação não confirmou Success")
            caminhos = adb("shell", "pm", "path", aplicativo, nome=aplicativo + "-path").splitlines()
            exigir(len(caminhos) == 1 and caminhos[0].startswith("package:/"), "Exige APK monolítico instalado")
            remoto = caminhos[0].removeprefix("package:")
            exigir(re.fullmatch(r"/[A-Za-z0-9_./=+~-]+", remoto) is not None, "Caminho remoto inesperado")
            hash_instalado = adb("shell", "sha256sum", remoto, nome=aplicativo + "-sha256").split()
            exigir(bool(hash_instalado) and hash_instalado[0] == sha, "APK instalado não é o snapshot atual")
        parar_alvo("force-stop-inicial")
        if args.modo in INTERRUPCOES:
            ensaiar_interrupcao(modo=args.modo, execucao=execucao, identidade_sha=identidade_sha,
                               saida=saida, registro=registro, salvar=salvar, adb=adb, emulador=emulador,
                               instrumentar=instrumentar,
                               recuperar=lambda modo, marcador: fase(*RECUPERACOES[modo][:3], marcador=marcador),
                               timeout_pronto=args.timeout_pronto)
        elif args.modo == "real":
            um = fase(REAL, "fase1Gravar", "etapa4RealPersistencia")
            parar_alvo("force-stop-entre-fases")
            # Sem instalar, pm clear, reset de prefs ou UTP entre essas duas invocações.
            dois = fase(REAL, "fase2VerificarAntesDeAlterar", "etapa4RealPersistencia")
            validar_processos(um, dois)
        elif args.modo == "recusado":
            prova = fase(RECUSADO, "segmentoRealRecusadoNaoGeraGlosaConfirmacaoOuTraducao", "etapa4RecusadoVideo")
            exigir(prova.get("status") == "APROVADO" and prova.get("sem_glosa_confirmacao_traducao") is True,
                   "Recusa por segmento não comprovada")
            exigir(prova.get("teardown_pipeline_concluido") is True and prova.get("cleanup") is True,
                   "Falta barreira final do pipeline ou restauração do snapshot RECUSADO")
            exigir(prova.get("video_sha256") == registro["video_sha256"], "Vídeo observado diverge do APK de testes")
        else:
            classe, metodo, optin, _ = RECUPERACOES[args.modo]
            fase(classe, metodo, optin)
        registro["status"] = ("INTERRUPCAO_ESPERADA_E_RECUPERACAO_VALIDADA" if args.modo in INTERRUPCOES
                      else "RECUPERADO_SEM_EVIDENCIA" if args.modo in RECUPERACOES else "APROVADO")
    except BaseException as erro:
        registro["status"] = "FALHOU"
        registro["erro"] = repr(erro)
        raise
    finally:
        salvar()
        # Não apagar prefs em caso de crash: o UUID dono permite recuperação explícita e auditável.
        # Também não fazer force-stop de outro pacote, kill-server ou uninstall no finally.


if __name__ == "__main__":
    main()
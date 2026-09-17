"""Etapa 5: regressão opt-in de transporte APK, Python 3.10+, somente stdlib.

Sem --executar apenas imprime o plano. Não instala, instrumenta ou executa modelos.
O pacote B é NEGATIVO, determinístico e deliberadamente sem grafo TFLite válido.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
from datetime import datetime, timezone
import uuid
import zipfile

import pacote_classificador_privado as pacote

REPO = Path(__file__).resolve().parents[1]
APP = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
TAREFAS_DEBUG = (":app:assembleDebug", ":app:assembleDebugAndroidTest")
PLANO = ["A_padrao", "B_negativo_transporte", "sem_pacote", "A_restaurado",
         "fixtures_substituidas", "colisao_bloqueada", "release_bloqueado",
         "restauracao_final_A_padrao"]


def exigir(condicao: bool, mensagem: str) -> None:
    if not condicao:
        raise ValueError(mensagem)


def agora() -> str:
    return datetime.now(timezone.utc).isoformat()


def serializar(valor) -> bytes:
    return (json.dumps(valor, ensure_ascii=True, sort_keys=True, indent=2) + "\n").encode()


def hash_arquivo(caminho: Path) -> str:
    h = hashlib.sha256()
    with caminho.open("rb") as arquivo:
        for bloco in iter(lambda: arquivo.read(1024 * 1024), b""):
            h.update(bloco)
    return h.hexdigest()


def inventario(raiz: Path) -> dict[str, str]:
    """Hashes sem arquivar os conteúdos das fontes (inclusive mídia grande)."""
    exigir(raiz.is_dir(), f"Diretório de fontes ausente: {raiz}")
    return {p.relative_to(raiz).as_posix(): hash_arquivo(p)
            for p in sorted(raiz.rglob("*")) if p.is_file()}


def validar_bytes(arquivos: dict[str, bytes]) -> None:
    exigir(set(arquivos) == pacote.ARQUIVOS, "Allowlist do pacote deve ser exata")
    pacote.conferir(arquivos[pacote.MODELO], arquivos[pacote.SIDECAR],
                   pacote.ler_json(arquivos[pacote.IDENTIDADE]))


def pacote_b(a: dict[str, bytes]) -> dict[str, bytes]:
    """B não é modelo final: root offset impossível, payload derivado de A.

    Independente do gerador da etapa 4; nenhum import/execução de treino/LiteRT.
    Mesmo se A for sintético, B tem bytes e hashes diferentes em todos os arquivos.
    """
    validar_bytes(a)
    modelo = b"\xff\xff\xff\x7fTFL3etapa5-negativo-transporte:" + bytes.fromhex(
        pacote.sha256(a[pacote.MODELO]))
    if modelo == a[pacote.MODELO]:
        modelo += b"B"
    checkpoint = pacote.sha256(b"etapa5:sem-checkpoint-real:" + modelo)
    sidecar = pacote.ler_json(a[pacote.SIDECAR])
    sidecar.update(sha256=pacote.sha256(modelo),
                   origem={"sha256": checkpoint, "tipo": "sintetica_sem_checkpoint"},
                   rotulos=[f"etapa5_nao_linguistico_{i:02d}" for i in range(20)])
    sidecar_bytes = serializar(sidecar)
    identidade = dict(schema=1, experimental=True, aprovado_entrega=False,
                      experimento="etapa5-B-negativo-transporte-nao-final",
                      calibracao="ausente_nao_calibrado", checkpoint_sha256=checkpoint,
                      modelo_sha256=pacote.sha256(modelo),
                      sidecar_sha256=pacote.sha256(sidecar_bytes))
    b = {pacote.MODELO: modelo, pacote.SIDECAR: sidecar_bytes,
         pacote.IDENTIDADE: serializar(identidade)}
    validar_bytes(b)
    exigir(all(a[n] != b[n] for n in pacote.ARQUIVOS), "A/B devem diferir nos três arquivos")
    return b


def validar_buildconfig(texto: str, esperado: dict[str, bytes] | None) -> dict:
    # Só fonte Java gerada: não confundir com leitura do DEX ou valor em runtime.
    texto = re.sub(r"/\*.*?\*/|//[^\n]*", "", texto, flags=re.DOTALL)
    obrigatorio = re.findall(
        r"public\s+static\s+final\s+boolean\s+CLASSIFICADOR_PRIVADO_OBRIGATORIO\s*=\s*(true|false)\s*;",
        texto)
    identidade = re.findall(
        r'public\s+static\s+final\s+String\s+CLASSIFICADOR_IDENTIDADE_SHA256\s*=\s*"([0-9a-f]*)"\s*;',
        texto)
    sha = pacote.sha256(esperado[pacote.IDENTIDADE]) if esperado is not None else ""
    exigir(obrigatorio == ["true" if esperado is not None else "false"],
           "BuildConfig gerado: flag ausente, duplicada ou incorreta")
    exigir(identidade == [sha], "BuildConfig gerado: identidade ausente, duplicada ou incorreta")
    return {"obrigatorio": esperado is not None, "identidade_sha256": sha,
            "escopo": "fonte_Java_gerada_nao_runtime"}


def indice_zip(z: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    nomes = z.namelist()
    exigir(len(nomes) == len(set(nomes)), "ZIP contém nomes duplicados")
    for nome in nomes:
        exigir(not nome.startswith("/") and "\\" not in nome
               and all(p not in (".", "..", "") for p in nome.rstrip("/").split("/")),
               f"Nome ZIP não canônico: {nome}")
    return {i.filename: i for i in z.infolist() if not i.is_dir()}


def validar_apk(apk: Path, esperado: dict[str, bytes] | None, *, principal: bool,
                fixtures: dict[str, str], proibidas: set[str]) -> dict:
    """Comparação exata de bytes privados e hashes de fixtures; não extrai ZIP."""
    with zipfile.ZipFile(apk) as z:
        entradas = indice_zip(z)
        assets = {n.removeprefix("assets/"): i for n, i in entradas.items()
                  if n.startswith("assets/")}
        # Detecta extras/renomeações do namespace privado, inclusive em subpastas.
        privados = {n for n in assets if Path(n).name.startswith("sinal_classifier")}
        permitidos = pacote.ARQUIVOS if principal and esperado is not None else set()
        exigir(privados == permitidos, f"Allowlist privada do APK divergiu: {sorted(privados)}")
        privados_hash = {}
        if principal and esperado is not None:
            validar_bytes(esperado)
            for nome, bruto in esperado.items():
                exigir(z.read("assets/" + nome) == bruto, f"Bytes APK diferentes: {nome}")
                privados_hash[nome] = pacote.sha256(bruto)
            exigir(assets[pacote.MODELO].compress_type == zipfile.ZIP_STORED,
                   "Modelo privado comprimido: mmap indisponível")
        if principal:
            exigir(not ({Path(n).name for n in assets} & {Path(n).name for n in proibidas}),
                   "Fixture de androidTest vazou para APK principal")
        else:
            exigir(set(assets) == set(fixtures), "Assets androidTest não são a fonte selecionada exata")
            for nome, sha in fixtures.items():
                with z.open("assets/" + nome) as stream:
                    h = hashlib.sha256()
                    for bloco in iter(lambda: stream.read(1024 * 1024), b""):
                        h.update(bloco)
                exigir(h.hexdigest() == sha, f"Fixture androidTest divergiu: {nome}")
        return {"sha256": hash_arquivo(apk), "privados_sha256": privados_hash,
                "assets": sorted(assets), "fixtures_sha256": {} if principal else fixtures}


def validar_fontes(dados: dict, *, token: str, selecionado: Path | None,
                   fixtures: Path, gerados: Path, colisao: Path | None) -> None:
    exigir(dados.get("token") == token, "Auditoria de fontes não é desta invocação")
    exigir(dados.get("pacote") == (str(selecionado) if selecionado else None),
           "Propriedade privada efetiva divergiu (inclusive propriedades globais)")
    exigir(dados.get("permitirAssetsFaltando") == "false", "Bypass de assets reais não permitido")
    fontes = dados["fontes"]
    exigir(fontes["androidTest"] == [str(fixtures.resolve())], "androidTest não substituiu/restaurou fontes")
    exigir(str(gerados.resolve()) in fontes["debug"], "Fonte gerada debug ausente")
    for nome, diretorios in fontes.items():
        if nome != "androidTest":
            exigir(str(fixtures.resolve()) not in diretorios, "Fixtures são fonte do APK alvo")
    if colisao is not None:
        exigir(str(colisao.resolve()) in fontes["main"], "Colisão não foi injetada como fonte real")


def init_gradle(token: str, auditoria: Path, colisao: Path | None = None) -> str:
    # Literais Groovy single-quoted: sem interpolação de $, inclusive em caminhos.
    def literal(valor: str) -> str:
        return "'" + valor.replace("\\", "\\\\").replace("'", "\\'") + "'"
    injecao = ""
    if colisao is not None:
        injecao = f"""
gradle.beforeProject {{ p ->
  if (p.path == ':app') p.plugins.withId('com.android.application') {{
    p.extensions.getByName('android').sourceSets.getByName('main').assets.srcDir({literal(str(colisao))})
  }}
}}
"""
    return "import groovy.json.JsonOutput\n" + injecao + f"""
gradle.projectsEvaluated {{
  def p = gradle.rootProject.project(':app')
  def fontes = p.extensions.getByName('android').sourceSets.collectEntries {{ s ->
    [(s.name): s.assets.srcDirs.collect {{ it.canonicalPath }}.sort()]
  }}
  def dados = [token: {literal(token)}, fontes: fontes,
    pacote: p.providers.gradleProperty('librasLivre.classificadorPrivado').orNull,
    permitirAssetsFaltando: p.providers.gradleProperty('librasLivre.permitirAssetsFaltando').orNull]
  new File({literal(str(auditoria))}).setText(JsonOutput.toJson(dados), 'UTF-8')
  p.logger.lifecycle({literal('ETAPA5_FONTES_' + token)})
}}
"""


def comando_gradle(wrapper: Path, projeto: Path, tarefas: tuple[str, ...], init: Path,
                   selecionado: Path | None, fixtures: Path | None = None) -> list[str]:
    comando = [str(wrapper), "-p", str(projeto), "--max-workers=2", "--console=plain",
               "--no-daemon", "--no-build-cache", "--no-configuration-cache",
               "-PlibrasLivre.permitirAssetsFaltando=false", "--init-script", str(init)]
    if selecionado is not None:
        comando.append(f"-PlibrasLivre.classificadorPrivado={selecionado}")
    if fixtures is not None:
        comando.append(f"-PlibrasLivre.classificadorFixtures={fixtures}")
    return comando + list(tarefas)


def validar_resultado(returncode: int, texto: str, bloqueio: str | None) -> None:
    if bloqueio is None:
        exigir(returncode == 0, f"Gradle falhou ({returncode}); nenhum APK é evidência de sucesso")
    else:
        exigir(returncode != 0 and bloqueio in texto,
               f"Bloqueio esperado não comprovado: {bloqueio}; retorno={returncode}")


def caminhos_execucao(args, repo: Path = REPO) -> tuple[Path, Path, Path, Path]:
    repo = repo.resolve()
    wrapper = args.gradle or repo / "mobile-app-companion/gradlew"
    exigir(wrapper.is_absolute() and wrapper.is_file() and os.access(wrapper, os.X_OK),
           "--gradle exige wrapper absoluto executável")
    exigir(wrapper == repo / "mobile-app-companion/gradlew" and not wrapper.is_symlink(),
           "--gradle deve ser o wrapper deste worktree, derivado do script")
    origem = pacote.caminho_privado(args.pacote)
    exigir(not origem.is_relative_to(repo) or origem.is_relative_to(repo / "experimentos-privados"),
           "Pacote não pode ser fonte versionada")
    saida = pacote.caminho_privado(args.saida)
    exigir(saida.is_relative_to(repo / "experimentos-privados") and saida != repo / "experimentos-privados",
           "--saida deve ser nova dentro de experimentos-privados/ deste worktree (ignored)")
    exigir(not saida.exists() and not saida.is_relative_to(origem)
           and not origem.is_relative_to(saida), "Saída existente ou sobreposta ao pacote")
    # Não editar .gitignore nem aceitar um arquivo forçado no índice.
    ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "-q", "--", str(saida)], check=False)
    exigir(ignored.returncode == 0, "Saída privada não está ignored")
    tracked = subprocess.run(["git", "-C", str(repo), "ls-files", "--", str(saida)],
                             check=True, stdout=subprocess.PIPE)
    exigir(not tracked.stdout, "Saída privada contém caminho rastreado")
    sdk = Path(os.environ.get("ANDROID_HOME", ""))
    exigir(sdk.is_absolute() and sdk.is_dir(), "ANDROID_HOME exige SDK absoluto existente")
    return wrapper, origem, saida, sdk.resolve()


@contextmanager
def trava(projeto: Path):
    # Advisory, sem arquivo novo fora da saída; serializa apenas instâncias deste CLI.
    dono = os.open(projeto, os.O_RDONLY)
    try:
        try:
            fcntl.flock(dono, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as erro:
            raise ValueError("Outra regressão etapa5 está usando este worktree") from erro
        try:
            yield
        finally:
            fcntl.flock(dono, fcntl.LOCK_UN)
    finally:
        os.close(dono)


class Regressao:
    def __init__(self, repo: Path, wrapper: Path, origem: Path, saida: Path, sdk: Path):
        self.repo, self.wrapper, self.origem, self.saida, self.sdk = repo, wrapper, origem, saida, sdk
        self.projeto = repo / "mobile-app-companion"
        self.app = self.projeto / "app"
        self.build = self.app / "build"
        self.padrao = self.app / "src/androidTest/assets"
        self.gerados = self.build / "generated/classificadorPrivado/assets"
        self.config = self.build / "generated/source/buildConfig/debug" / APP.replace(".", "/") / "BuildConfig.java"
        self.apks = {"principal": self.build / "outputs/apk/debug/app-debug.apk",
                     "androidTest": self.build / "outputs/apk/androidTest/debug/app-debug-androidTest.apk"}
        self.fontes_baseline = None
        self.registro = {"schema": 1, "execucao": str(uuid.uuid4()), "inicio": agora(),
                         "status": "EM_EXECUCAO", "escopo": "conteudo_APK_fontes_BuildConfig_gerado_NAO_runtime",
                         "repo": str(repo), "pacote_selecionado": str(origem), "android_home": str(sdk),
                         "plano": PLANO, "fases": [], "erro_original": None, "erro_restauracao": None}

    def salvar(self) -> None:
        temporario = self.saida / ".evidencia.json.tmp"
        temporario.write_bytes(serializar(self.registro))
        temporario.replace(self.saida / "evidencia.json")

    def snapshot(self, origem: Path, destino: Path) -> dict:
        antes = hash_arquivo(origem)
        shutil.copyfile(origem, destino)
        exigir(antes == hash_arquivo(destino) == hash_arquivo(origem),
               f"Artefato mudou durante snapshot: {origem}; pare builds concorrentes")
        return {"origem": str(origem), "snapshot": str(destino.relative_to(self.saida)), "sha256": antes}

    def fase(self, nome: str, selecionado: Path | None, esperado: dict[str, bytes] | None,
             *, fixtures: Path | None = None, colisao: Path | None = None,
             bloqueio: str | None = None, release: bool = False) -> None:
        pasta = self.saida / nome
        pasta.mkdir()
        token = str(uuid.uuid4())
        auditoria = pasta / "fontes.json"
        init = pasta / "fontes.init.gradle"
        init.write_text(init_gradle(token, auditoria, colisao), encoding="utf-8")
        tarefas = (":app:assembleRelease", "--dry-run") if release else TAREFAS_DEBUG
        argv = comando_gradle(self.wrapper, self.projeto, tarefas, init, selecionado, fixtures)
        fase = {"nome": nome, "inicio": agora(), "status": "EM_EXECUCAO", "comando": argv,
                "token": token, "returncode": None, "artefatos": {}, "artefatos_aceitos": False,
                "init_sha256": hash_arquivo(init), "descartados_antes": {}, "bloqueio_esperado": bloqueio}
        self.registro["fases"].append(fase)
        self.salvar()
        log = pasta / "gradle.log"
        try:
            # Remove SOMENTE saídas conhecidas. Nunca clean, src ou build.gradle.
            # Não usa mtime: resultado zero + saídas recriadas + bytes conferidos.
            for p in (*self.apks.values(), self.config, self.gerados):
                exigir(not any(item.is_symlink() for item in (p, *p.parents)),
                       f"Saída de build redirecionada por symlink: {p}")
            for p in (*self.apks.values(), self.config):
                if p.exists():
                    fase["descartados_antes"][str(p)] = hash_arquivo(p)
                    p.unlink()
            self.salvar()
            env = dict(os.environ, ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk))
            with log.open("xb") as stream:
                # Sem timeout que abandone Gradle em background e corra contra o finally.
                # Em Ctrl-C, aguarda o filho encerrar antes de iniciar a restauração.
                processo = subprocess.Popen(argv, cwd=self.projeto, env=env, stdout=stream,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    fase["returncode"] = processo.wait()
                except BaseException:
                    # Grupo próprio: não sinaliza terminais, outros builds ou adb.
                    try:
                        os.killpg(processo.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    fase["returncode"] = processo.wait()
                    raise
            texto = log.read_text(encoding="utf-8", errors="replace")
            validar_resultado(fase["returncode"], texto, bloqueio)
            exigir("ETAPA5_FONTES_" + token in texto, "Log não contém auditoria desta invocação")
            fontes = pacote.ler_json(auditoria.read_bytes())
            validar_fontes(fontes, token=token, selecionado=selecionado,
                           fixtures=fixtures or self.padrao, gerados=self.gerados, colisao=colisao)
            temporarias_permitidas = {str(p.resolve()) for p in (fixtures, colisao) if p is not None}
            for diretorios in fontes["fontes"].values():
                for diretorio in diretorios:
                    if Path(diretorio).is_relative_to(self.saida):
                        exigir(diretorio in temporarias_permitidas, "Fonte temporária residual não autorizada")
            if fixtures is None and colisao is None:
                if self.fontes_baseline is None:
                    self.fontes_baseline = fontes["fontes"]
                else:
                    exigir(fontes["fontes"] == self.fontes_baseline, "Fontes padrão não foram restauradas")
            fase["fontes_sha256"] = hash_arquivo(auditoria)
            if bloqueio is not None:
                fase["status"] = "BLOQUEIO_ESPERADO_CONFIRMADO"
                # Mesmo um APK parcial/velho nunca vira snapshot aprovado da fase negativa.
                return
            exigir(self.config.is_file(), "BuildConfig desta invocação ausente")
            config_snapshot = pasta / "BuildConfig.java"
            config_arquivo = self.snapshot(self.config, config_snapshot)
            fase["buildconfig"] = validar_buildconfig(config_snapshot.read_text(encoding="utf-8"), esperado)
            fase["buildconfig"]["arquivo"] = config_arquivo
            hashes = {n: pacote.sha256(b) for n, b in (esperado or {}).items()}
            exigir(self.gerados.is_dir() and {p.name for p in self.gerados.iterdir()} == set(hashes),
                     "Allowlist dos assets gerados contém entrada ausente/extra")
            exigir(inventario(self.gerados) == hashes, "Allowlist/bytes dos assets gerados divergiu")
            fase["gerados_sha256"] = hashes
            fixtures_hash = inventario(fixtures) if fixtures else self.fixtures_padrao
            for tipo, origem in self.apks.items():
                exigir(origem.is_file(), f"APK desta invocação ausente: {tipo}")
                arquivo = pasta / (tipo + ".apk")
                fase["artefatos"][tipo] = self.snapshot(origem, arquivo)
                fase["artefatos"][tipo]["verificacao"] = validar_apk(
                    arquivo, esperado, principal=tipo == "principal", fixtures=fixtures_hash,
                    proibidas=set(self.fixtures_padrao) | set(self.fixtures_temporarias))
            fase["artefatos_aceitos"] = True
            fase["status"] = "APROVADO_CONTEUDO"
        except BaseException as erro:
            fase["status"] = "FALHOU"
            fase["erro"] = repr(erro)
            raise
        finally:
            fase["fim"] = agora()
            if log.exists():
                fase["log"] = {"arquivo": str(log.relative_to(self.saida)), "sha256": hash_arquivo(log)}
            # Apenas presença/hash, nunca aprovação, quando o comando/assertion falha.
            if not fase["artefatos_aceitos"]:
                fase["saidas_nao_validadas"] = {str(p): hash_arquivo(p)
                                                 for p in self.apks.values() if p.is_file()}
            self.salvar()

    def executar(self) -> None:
        self.saida.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.salvar()
        erro_original = None
        erro_restore = None
        try:
            exigir(self.build.resolve().is_relative_to(self.repo.resolve()) and
                   not any(p.is_symlink() for p in (self.build, *self.build.parents)),
                   "Build redirecionado por symlink/fora do worktree não é permitido")
            pacote.verificar(self.origem)
            a = {n: (self.origem / n).read_bytes() for n in pacote.ARQUIVOS}
            validar_bytes(a)
            b = pacote_b(a)
            self.fixtures_padrao = inventario(self.padrao)
            exigir("paridade_classificador.json" in self.fixtures_padrao, "Smoke padrão ausente")
            fontes_antes = inventario(self.app / "src")
            gradle_antes = hash_arquivo(self.app / "build.gradle.kts")
            self.registro.update(fontes_antes=fontes_antes, build_gradle_sha256=gradle_antes,
                                 fixtures_padrao=self.fixtures_padrao,
                                 pacotes={"A": {n: pacote.sha256(v) for n, v in a.items()},
                                          "B": {n: pacote.sha256(v) for n, v in b.items()}},
                                 B="NEGATIVO_DETERMINISTICO_TRANSPORTE_NAO_MODELO_FINAL")
            # Pacotes arquivados SOMENTE na saída ignored; A fica imutável para restaurar.
            for nome, dados in (("pacote-A", a), ("pacote-B", b)):
                pasta = self.saida / nome
                pasta.mkdir(mode=0o700)
                for arquivo, bruto in dados.items():
                    (pasta / arquivo).write_bytes(bruto)
            pa, pb = self.saida / "pacote-A", self.saida / "pacote-B"
            with tempfile.TemporaryDirectory(prefix=".fontes-temporarias-", dir=self.saida) as tmp:
                temporario = Path(tmp)
                fixture_dir = temporario / "androidTest"
                fixture_dir.mkdir()
                shutil.copyfile(self.padrao / "paridade_classificador.json",
                                fixture_dir / "paridade_classificador.json")
                sentinel = "etapa5_sentinel_" + self.registro["execucao"] + ".txt"
                (fixture_dir / sentinel).write_text(
                    "etapa5 fixture exclusiva androidTest " + self.registro["execucao"] + "\n",
                    encoding="utf-8")
                self.fixtures_temporarias = inventario(fixture_dir)
                exigir(self.fixtures_temporarias["paridade_classificador.json"] ==
                       self.fixtures_padrao["paridade_classificador.json"], "Smoke mudou durante cópia")
                colisao = temporario / "colisao-assets"
                colisao.mkdir()
                (colisao / pacote.MODELO).write_bytes(b"ETAPA5_COLISAO_SINTETICA_SEM_MODELO")
                self.registro["fixtures_temporarias"] = self.fixtures_temporarias
                self.salvar()
                try:
                    self.fase("A_padrao", pa, a)
                    self.fase("B_negativo_transporte", pb, b)
                    self.fase("sem_pacote", None, None)
                    self.fase("A_restaurado", pa, a)
                    self.fase("fixtures_substituidas", pa, a, fixtures=fixture_dir)
                    self.fase("colisao_bloqueada", pa, a, colisao=colisao,
                              bloqueio="Colisão do classificador privado com assets em " + str(colisao))
                    self.fase("release_bloqueado", pa, a, release=True,
                              bloqueio="classificadorPrivado é exclusivo de debug; release proibido.")
                except BaseException as erro:
                    erro_original = erro
                    self.registro["erro_original"] = repr(erro)
                finally:
                    # Nenhuma propriedade/init de fixture ou colisão nesta invocação.
                    # Recompõe AMBOS APKs, não apenas a tarefa de copiar assets.
                    try:
                        self.fase("restauracao_final_A_padrao", pa, a)
                    except BaseException as erro:
                        erro_restore = erro
                        self.registro["erro_restauracao"] = repr(erro)
                    try:
                        fontes_depois = inventario(self.app / "src")
                        self.registro["fontes_depois"] = fontes_depois
                        exigir(fontes_depois == fontes_antes and
                               hash_arquivo(self.app / "build.gradle.kts") == gradle_antes,
                               "Fontes/build.gradle mudaram durante a execução; não restaurar arquivos alheios")
                    except BaseException as erro:
                        self.registro["erro_integridade_fontes"] = repr(erro)
                        if erro_restore is None:
                            erro_restore = erro
                            self.registro["erro_restauracao"] = repr(erro)
            if erro_original is not None:
                if erro_restore is not None:
                    raise erro_original from erro_restore
                raise erro_original
            if erro_restore is not None:
                raise erro_restore
            self.registro["status"] = "APROVADO_CONTEUDO_NAO_RUNTIME"
        except BaseException as erro:
            self.registro["status"] = "FALHOU"
            if self.registro["erro_original"] is None and erro_restore is None:
                self.registro["erro_original"] = repr(erro)
            raise
        finally:
            self.registro["fim"] = agora()
            self.salvar()


def criar_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--executar", action="store_true", help="Autoriza builds e snapshots privados; nunca runtime")
    ap.add_argument("--pacote", type=Path, required=True, help="Pacote A absoluto privado selecionado; sem baseline fixo")
    ap.add_argument("--saida", type=Path, required=True, help="Diretório absoluto NOVO em experimentos-privados/ deste worktree")
    ap.add_argument("--gradle", type=Path, help="Wrapper absoluto deste worktree; default derivado da localização do script")
    return ap


def main(argv: list[str] | None = None) -> None:
    args = criar_parser().parse_args(argv)
    if not args.executar:
        print(serializar({"status": "NAO_EXECUTADO", "plano": PLANO,
                          "aviso": "Nenhum comando ou escrita. Use --executar para autorizar builds."}).decode())
        return
    wrapper, origem, saida, sdk = caminhos_execucao(args)
    # Novos arquivos privados, inclusive logs e snapshots, sem permissões de grupo/outros.
    mascara = os.umask(0o077)
    try:
        with trava(REPO / "mobile-app-companion"):
            Regressao(REPO, wrapper, origem, saida, sdk).executar()
    finally:
        os.umask(mascara)


if __name__ == "__main__":
    main()
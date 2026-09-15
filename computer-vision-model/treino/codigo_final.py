"""Identidade explícita do código da run final; nunca escolhe HEAD implicitamente."""
from pathlib import Path
import re
import subprocess


def validar_sha(commit: str) -> str:
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Informe o SHA completo do commit aprovado, já publicado, não uma branch")
    return commit


def conferir_codigo(repo: Path, commit: str) -> str:
    validar_sha(commit)

    def git(*args):
        return subprocess.run(["git", "-C", str(repo), *args], check=True,
                              capture_output=True, text=True).stdout.strip()

    if git("rev-parse", "HEAD") != commit:
        raise ValueError("HEAD diverge do commit aprovado; não executar treino")
    # Não rejeitar logs/dados privados não rastreados; rejeitar código novo que
    # possa sombrear imports, além de qualquer mudança em arquivos rastreados.
    if (git("status", "--porcelain", "--untracked-files=no")
            or git("status", "--porcelain", "--", "computer-vision-model/treino",
                   "computer-vision-model/datasets", "computer-vision-model/PoC/src")):
        raise ValueError("Código com alterações locais; commit não identifica estes bytes")
    return commit
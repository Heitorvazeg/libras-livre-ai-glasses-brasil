"""Pacote debug privado: somente bytes do export e identidade, sem pickle/treino.

Hashes comprovam integridade e seleção explícita, não autoria/licença/acurácia.
Nesta integração só são aceitos exports sem calibração (não calibrados).
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile

REPO = Path(__file__).resolve().parents[1]
MODELO = "sinal_classifier.tflite"
SIDECAR = "sinal_classifier.json"
IDENTIDADE = "sinal_classifier.identidade.json"
ARQUIVOS = {MODELO, SIDECAR, IDENTIDADE}
POSE = [0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24]


def sha256(dados: bytes) -> str:
    return hashlib.sha256(dados).hexdigest()


def hash_valido(valor) -> bool:
    return isinstance(valor, str) and re.fullmatch(r"[0-9a-f]{64}", valor) is not None


def ler_json(bruto: bytes) -> dict:
    def pares(itens):
        d = {}
        for k, v in itens:
            if k in d:
                raise ValueError(f"Chave JSON duplicada: {k}")
            d[k] = v
        return d
    def nao_finito(valor):
        raise ValueError(f"JSON não finito: {valor}")
    d = json.loads(bruto, object_pairs_hook=pares, parse_constant=nao_finito)
    if not isinstance(d, dict):
        raise ValueError("Objeto JSON obrigatório")
    return d


def caminho_privado(caminho: Path) -> Path:
    caminho = Path(caminho)
    if not caminho.is_absolute():
        raise ValueError("Exige caminho privado absoluto")
    if any(p.is_symlink() for p in (caminho, *caminho.parents)):
        raise ValueError("Links simbólicos não são aceitos")
    caminho = caminho.resolve()
    if caminho.is_relative_to(REPO) and not caminho.is_relative_to(REPO / "experimentos-privados"):
        raise ValueError("Use experimentos-privados/ ou diretório fora do repositório")
    return caminho


def conferir(modelo: bytes, sidecar: bytes, identidade: dict) -> dict:
    try:
        if (type(identidade["schema"]) is not int or identidade["schema"] != 1
                or identidade["experimental"] is not True or identidade["aprovado_entrega"] is not False
                or identidade["calibracao"] != "ausente_nao_calibrado"
                or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}", identidade["experimento"])
                or any(not hash_valido(identidade[k]) for k in
                       ("modelo_sha256", "sidecar_sha256", "checkpoint_sha256"))):
            raise ValueError("Identidade experimental inválida")
        if sha256(modelo) != identidade["modelo_sha256"] or sha256(sidecar) != identidade["sidecar_sha256"]:
            raise ValueError("Hash dos bytes diverge da identidade")
        if len(modelo) < 8 or modelo[4:8] != b"TFL3":
            raise ValueError("Exige arquivo TFLite (a validade do grafo é conferida no Android)")
        d = ler_json(sidecar)
        contrato = d["contrato_entrada"]
        rotulos = d["rotulos"]
        if (d["schema"] != 1 or d["modo"] != "landmarks"
                or d["sha256"] != identidade["modelo_sha256"]
                or d["origem"]["sha256"] != identidade["checkpoint_sha256"]):
            raise ValueError("Sidecar diverge do modelo/checkpoint selecionado")
        if "calibracao" in d:
            raise ValueError("Esta integração aceita somente export sem bloco de calibração")
        if (contrato["shape"] != [1, 96, 57, 3] or contrato["dtype"] != "float32"
                or contrato["frames_fixos"] != 96
                or [p["indice_mediapipe_pose"] for p in contrato["layout_landmarks"]["pose_ordenada"]] != POSE
                or not isinstance(rotulos, list) or len(rotulos) != 20
                or any(not isinstance(r, str) or not r.strip() for r in rotulos)
                or len(set(rotulos)) != len(rotulos)):
            raise ValueError("Contrato exige 96 frames, 57 pontos xyz e 20 rótulos únicos")
        return identidade
    except (KeyError, TypeError) as exc:
        raise ValueError(f"Pacote incompleto/inválido: {exc}") from exc


def verificar(pasta: Path) -> dict:
    pasta = caminho_privado(pasta)
    if not pasta.is_dir() or {p.name for p in pasta.iterdir()} != ARQUIVOS:
        raise ValueError("Pacote deve conter exatamente modelo, sidecar e identidade")
    for nome in ARQUIVOS:
        p = caminho_privado(pasta / nome)
        if not p.is_file():
            raise ValueError(f"Arquivo regular obrigatório: {nome}")
    return conferir((pasta / MODELO).read_bytes(), (pasta / SIDECAR).read_bytes(),
                    ler_json((pasta / IDENTIDADE).read_bytes()))


def preparar(modelo: Path, sidecar: Path, saida: Path, *, experimento: str,
             modelo_sha256: str, checkpoint_sha256: str) -> dict:
    saida = caminho_privado(saida)
    if saida.exists():
        raise ValueError("Saída já existe; não sobrescrever pacote")
    bruto = caminho_privado(modelo).read_bytes()
    json_bruto = caminho_privado(sidecar).read_bytes()
    identidade = dict(schema=1, experimental=True, aprovado_entrega=False,
        experimento=experimento, modelo_sha256=modelo_sha256,
        sidecar_sha256=sha256(json_bruto), checkpoint_sha256=checkpoint_sha256,
        calibracao="ausente_nao_calibrado")
    conferir(bruto, json_bruto, identidade)
    saida.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".pacote-", dir=saida.parent) as tmp:
        pasta = Path(tmp) / "pacote"
        pasta.mkdir()
        (pasta / MODELO).write_bytes(bruto)
        (pasta / SIDECAR).write_bytes(json_bruto)
        (pasta / IDENTIDADE).write_text(json.dumps(identidade, indent=2, sort_keys=True) + "\n")
        verificar(pasta)
        if saida.exists():
            raise ValueError("Saída criada durante a preparação; não sobrescrever")
        pasta.rename(saida)
    return identidade


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="acao", required=True)
    criar = sub.add_parser("preparar")
    for nome in ("modelo", "sidecar", "saida"):
        criar.add_argument(f"--{nome}", type=Path, required=True)
    for nome in ("experimento", "modelo-sha256", "checkpoint-sha256"):
        criar.add_argument(f"--{nome}", required=True)
    validar = sub.add_parser("verificar")
    validar.add_argument("pasta", type=Path)
    args = vars(ap.parse_args())
    acao = args.pop("acao")
    print(json.dumps(preparar(**args) if acao == "preparar" else verificar(**args), indent=2))


if __name__ == "__main__":
    main()
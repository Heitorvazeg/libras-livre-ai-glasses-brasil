"""Fixture NEGATIVA determinística, sem modelo/checkpoint real nem dependências externas.

Passa pelo contrato e hashes do pacote privado; NÃO é um FlatBuffer válido.
O checkpoint_sha256 identifica apenas uma frase sintética, nunca pesos de treino.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import pacote_classificador_privado as pacote

EXPERIMENTO = "etapa4-recusado-sintetico-v1"
# Root offset impossível (fora dos 8 bytes), apesar do identificador de arquivo TFL3.
MODELO_INVALIDO = b"\xff\xff\xff\x7fTFL3"
CHECKPOINT_SINTETICO = b"LIBRAS Livre etapa4: fixture negativa, sem checkpoint real, v1"


def bytes_pacote() -> dict[str, bytes]:
    modelo_hash = pacote.sha256(MODELO_INVALIDO)
    checkpoint_hash = pacote.sha256(CHECKPOINT_SINTETICO)
    sidecar = dict(
        schema=1, modo="landmarks", sha256=modelo_hash,
        origem={"sha256": checkpoint_hash, "tipo": "sintetica_sem_checkpoint"},
        rotulos=[f"fixture_nao_linguistica_{i:02d}" for i in range(20)],
        contrato_entrada=dict(
            shape=[1, 96, 57, 3], dtype="float32", frames_fixos=96,
            imputacao_embutida=False,
            layout_landmarks={"pose_ordenada": [
                {"indice_mediapipe_pose": i} for i in pacote.POSE
            ]},
        ),
    )

    def serializar(obj: dict) -> bytes:
        return (json.dumps(obj, ensure_ascii=True, sort_keys=True, indent=2) + "\n").encode("utf-8")

    sidecar_bytes = serializar(sidecar)
    identidade = dict(
        schema=1, experimental=True, aprovado_entrega=False, experimento=EXPERIMENTO,
        modelo_sha256=modelo_hash, sidecar_sha256=pacote.sha256(sidecar_bytes),
        checkpoint_sha256=checkpoint_hash, calibracao="ausente_nao_calibrado",
    )
    pacote.conferir(MODELO_INVALIDO, sidecar_bytes, identidade)
    return {pacote.MODELO: MODELO_INVALIDO, pacote.SIDECAR: sidecar_bytes,
            pacote.IDENTIDADE: serializar(identidade)}


def gerar(saida: Path) -> dict:
    saida = pacote.caminho_privado(saida)
    arquivos = bytes_pacote()
    saida.parent.mkdir(parents=True, exist_ok=True)
    # Reserva exclusiva, inclusive se o destino existente estiver vazio. Não usar rename(),
    # que em POSIX pode substituir um diretório vazio criado por outro processo.
    saida.mkdir(mode=0o700, exist_ok=False)
    # Em falha parcial, deixa o destino reservado para inspeção; nunca remove arquivos alheios.
    for nome, bruto in arquivos.items():
        with (saida / nome).open("xb") as arquivo:
            arquivo.write(bruto)
    return pacote.verificar(saida)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--saida", type=Path, required=True,
                        help="Diretório privado absoluto NOVO; não sobrescreve")
    args = parser.parse_args()
    print(json.dumps(gerar(args.saida), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
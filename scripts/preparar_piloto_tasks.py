"""Inventário offline para o piloto Tasks × Holistic; não extrai nem treina.

Relaciona vídeos e landmarks da pessoa escolhida, registra hashes e bloqueios.
O manifesto não é uma autorização de uso/licença nem prova de compatibilidade.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

RAIZ = Path(__file__).resolve().parents[1]
POC = RAIZ / "computer-vision-model" / "PoC"
ASSETS = RAIZ / "mobile-app-companion" / "app" / "src" / "main" / "assets"


def hash_arquivo(p: Path) -> str:
    with p.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def inventariar(videos: Path, landmarks: Path, pessoa: str, modelos: dict[str, Path]) -> dict:
    if not re.fullmatch(r"M\d+", pessoa):
        raise ValueError("pessoa deve ser um ID MINDS, como M01")
    bloqueios = []
    pares = []
    arquivos = sorted(landmarks.glob(f"pessoa{pessoa}_sinal-*_rep*.npy"))
    if not arquivos:
        bloqueios.append("nenhum landmark Holistic para a pessoa selecionada")
    for lm in arquivos:
        candidatos = [p for p in videos.glob(lm.stem + ".*")
                      if p.suffix.lower() in {".mp4", ".avi", ".mov", ".mkv"}]
        par = {"id": lm.name, "landmark": str(lm.resolve()), "landmark_sha256": hash_arquivo(lm)}
        if len(candidatos) != 1:
            bloqueios.append(f"{lm.stem}: esperava um vídeo, encontrei {len(candidatos)}")
        else:
            par.update(video=str(candidatos[0].resolve()), video_sha256=hash_arquivo(candidatos[0]))
        pares.append(par)
    modelos_info = {}
    for nome, p in modelos.items():
        existe = p.is_file()
        modelos_info[nome] = {"arquivo": str(p.resolve()), "presente": existe,
                              "sha256": hash_arquivo(p) if existe else None}
        if not existe:
            bloqueios.append(f"modelo Tasks ausente: {nome}")
    # O inventário não carrega pesos nem presume que um modelo final é held-out.
    bloqueios.append("selecionar e validar checkpoint LOSO que excluiu a pessoa de teste")
    bloqueios.append("executar extrator Tasks com contrato do app e comparação held-out")
    return {"schema": 1, "pessoa_teste": pessoa, "app_referencia": "80fe186",
            "pares": pares, "total_landmarks": len(pares),
            "total_pares_com_video": sum("video" in p for p in pares),
            "modelos": modelos_info, "bloqueios": bloqueios,
            "extracao_executada": False, "avaliacao_executada": False,
            "opcoes_tasks": {"running_mode": "VIDEO", "num_poses": 2, "num_hands": 4,
                             "raio_pulso_ombros": 0.5, "atribuicao": "gulosa",
                             "pose": "maior_largura_ombros"},
            "requisitos": ["preservar índices e timestamps de captura separadamente do processamento",
                           "registrar frames sem pose/mãos, não comprimir a linha do tempo",
                           "usar mesma normalização e ordem 57x3 do app",
                           "saída nova e privada; nunca substituir landmarks originais",
                           "não interpretar uma pessoa como prova de equivalência geral"]}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pessoa", default="M01")
    ap.add_argument("--videos", type=Path, default=POC / "data/raw")
    ap.add_argument("--landmarks", type=Path, default=POC / "data/landmarks")
    ap.add_argument("--pose-model", type=Path, default=ASSETS / "pose_landmarker_lite.task")
    ap.add_argument("--hand-model", type=Path, default=ASSETS / "hand_landmarker.task")
    ap.add_argument("--saida", type=Path, required=True)
    args = ap.parse_args()
    destino = args.saida.resolve()
    if destino.is_relative_to(RAIZ) and not destino.is_relative_to(RAIZ / "experimentos-privados"):
        ap.error("manifesto privado: use experimentos-privados/ ou saída fora do repositório")
    if destino.exists():
        ap.error("saída já existe; escolha outro arquivo para preservar o inventário anterior")
    try:
        plano = inventariar(args.videos, args.landmarks, args.pessoa,
                            {"pose_lite": args.pose_model, "hand": args.hand_model})
    except ValueError as exc:
        ap.error(str(exc))
    destino.parent.mkdir(parents=True, exist_ok=True)
    with destino.open("x", encoding="utf-8") as f:
        json.dump(plano, f, ensure_ascii=False, indent=2)
    print(f"[piloto] {plano['total_pares_com_video']}/{plano['total_landmarks']} pares; "
          f"{len(plano['bloqueios'])} bloqueios; extração/avaliação NÃO executadas")
    for bloqueio in plano["bloqueios"]:
        print(f"  - {bloqueio}")


if __name__ == "__main__":
    main()
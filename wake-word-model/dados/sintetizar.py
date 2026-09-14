#!/usr/bin/env python3
"""Sintetiza os clipes positivos e negativos pt-BR pro treino local da wake word.

Substitui a etapa `--generate_clips` do `train.py` do openWakeWord. Aquela etapa
espera o `piper-sample-generator` (checkpoint VITS multi-falante com amostragem de
speaker embedding) — só existe pronto em en/de/fr/nl, não em pt-BR (ver
docs/wake-word-treino-plano.md §2). Em vez disso, usamos várias vozes Piper pt-BR de
comunidade (sherpa-onnx, o MESMO motor que `PiperSherpaOnnxTtsEngine.kt` usa pro TTS
do app) com noise_scale/velocidade variados por chamada — menos diverso do ponto de
vista de identidade vocal que um gerador multi-falante de verdade, mas é fala real em
pt-BR, o que o pipeline oficial não oferece pronto pra este idioma.

Escreve direto na estrutura que `train.py` espera
(`<output>/<modelo>/{positive,negative}_{train,test}/*.wav`), então dá pra rodar
`train.py --augment_clips --train_model` na sequência sem nunca passar por
`--generate_clips` (que nem tentaria importar isto).
"""

from __future__ import annotations

import argparse
import random
import sys
import uuid
from pathlib import Path

import librosa
import numpy as np
import scipy.io.wavfile as wavfile
import sherpa_onnx

sys.path.insert(0, str(Path(__file__).resolve().parent))
from frases import POSITIVOS, negativos_para  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
VOZES_DIR = RAIZ / "vozes"
RUIDO_BG_DIR = RAIZ / "ruido" / "esc50_background"
FALA_PT_DIR = RAIZ / "ruido" / "fala_pt"

SAMPLE_RATE = 16000

# (noise_scale, noise_scale_w) — cobre timbre/prosódia; velocidade varia por chamada
# via `speed` (ver `_sintetizar_um`). Piper não expõe amostragem contínua de speaker
# embedding como o piper-sample-generator; isto é o substituto discreto (ver header).
VARIACOES_TIMBRE = [
    (0.5, 0.6),
    (0.667, 0.8),
    (0.8, 0.9),
    (0.9, 1.0),
]
VELOCIDADES = [0.85, 0.95, 1.0, 1.05, 1.15]

# Quantos clipes falados por split. Bem abaixo do "recomendado" (20k+) do
# custom_model.yml original — ver README.md "O que fica de fora desta rodada" pro
# porquê e pro que isso custa em falso-positivo/negativo.
N_TRAIN = 450
N_VAL = 100
# Recortes de ruído ambiente (ESC-50) somados aos negativos falados — dá ao
# classificador alguns exemplos de "não é fala nenhuma", que os confusáveis por si só
# não cobrem.
N_RUIDO_EXTRA_TRAIN = 40
N_RUIDO_EXTRA_VAL = 10
# Recortes de fala real em português (MLS) — a peça que faltava especificamente pro
# libras_livre_encerrar: recall preso em ~0,40-0,47 em QUALQUER limiar (ver
# resultados/libras_livre_encerrar/relatorio.md, "Curva de limiar"), sinal de falta
# de diversidade nos negativos, não de calibração. Volume maior que o ruído ambiente
# porque é a fonte de maior valor — fonética/prosódia portuguesa de verdade, que nem
# o ACAV100M (majoritariamente inglês) nem os confusáveis manuscritos oferecem.
N_FALA_PT_TRAIN = 300
N_FALA_PT_VAL = 60


def carregar_vozes(vozes_dir: Path) -> list[tuple[str, sherpa_onnx.OfflineTts]]:
    """Uma engine sherpa-onnx por (voz, variação de timbre) — ver VARIACOES_TIMBRE."""
    engines: list[tuple[str, sherpa_onnx.OfflineTts]] = []
    diretorios = sorted(vozes_dir.glob("vits-piper-pt_BR-*"))
    if not diretorios:
        raise SystemExit(f"Nenhuma voz em {vozes_dir} — rode dados/baixar_vozes.sh antes.")
    for d in diretorios:
        onnx_path = next(d.glob("*.onnx"), None)
        tokens_path = d / "tokens.txt"
        data_dir = d / "espeak-ng-data"
        if onnx_path is None or not tokens_path.exists():
            print(f"  aviso: {d.name} incompleto, pulando")
            continue
        for noise_scale, noise_scale_w in VARIACOES_TIMBRE:
            cfg = sherpa_onnx.OfflineTtsConfig(
                model=sherpa_onnx.OfflineTtsModelConfig(
                    vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                        model=str(onnx_path),
                        tokens=str(tokens_path),
                        data_dir=str(data_dir),
                        noise_scale=noise_scale,
                        noise_scale_w=noise_scale_w,
                    ),
                    num_threads=1,
                    provider="cpu",
                ),
            )
            nome = f"{d.name}[ns={noise_scale},nsw={noise_scale_w}]"
            engines.append((nome, sherpa_onnx.OfflineTts(cfg)))
    print(f"  {len(diretorios)} vozes x {len(VARIACOES_TIMBRE)} timbres = {len(engines)} engines carregadas")
    return engines


def _sintetizar_um(engines: list[tuple[str, sherpa_onnx.OfflineTts]], frases: list[str], destino: Path) -> None:
    _, tts = random.choice(engines)
    frase = random.choice(frases)
    speed = random.choice(VELOCIDADES)
    audio = tts.generate(frase, sid=0, speed=speed)
    amostras = np.asarray(audio.samples, dtype=np.float32)
    # As vozes "medium"/"high" (faber, cadu, jeff, miro, dii) saem a 22050 Hz; só a
    # "low" (edresson) já sai em 16 kHz. train.py exige TODOS os clipes em 16 kHz —
    # ver docs/wake-word-treino-plano.md (augment_clips valida isto e derruba o
    # treino se encontrar uma taxa diferente).
    if audio.sample_rate != SAMPLE_RATE:
        amostras = librosa.resample(amostras, orig_sr=audio.sample_rate, target_sr=SAMPLE_RATE)
    amostras_int16 = (amostras * 32767).astype(np.int16)
    wavfile.write(destino, SAMPLE_RATE, amostras_int16)


def gerar_split(engines, frases: list[str], destino_dir: Path, n: int) -> None:
    destino_dir.mkdir(parents=True, exist_ok=True)
    existentes = len(list(destino_dir.glob("*.wav")))
    if existentes >= n:
        print(f"    {destino_dir.name}: já tem {existentes}/{n}, pulando")
        return
    for i in range(existentes, n):
        _sintetizar_um(engines, frases, destino_dir / f"{uuid.uuid4().hex}.wav")
        if (i + 1) % 100 == 0:
            print(f"    {destino_dir.name}: {i + 1}/{n}")
    print(f"    {destino_dir.name}: {n}/{n} concluído")


def adicionar_recortes_como_negativo(
    origem_dir: Path, prefixo: str, destino_dir: Path, n: int, duracao_s: float = 1.5
) -> None:
    """Recorta wavs de `origem_dir` (16 kHz, já preparados por dados/baixar_ruido.py
    ou dados/baixar_fala_pt.py) como negativos extras — cada fonte cobre um tipo de
    "não é a frase" que os confusáveis manuscritos sozinhos não cobrem (ruído
    ambiente: "não é fala nenhuma"; fala real em português: "é português, mas não é
    a frase")."""
    if not origem_dir.exists():
        print(f"    (sem {origem_dir.name} baixado ainda; pulando esta parte)")
        return
    arquivos = list(origem_dir.glob("*.wav"))
    if not arquivos:
        return
    n_amostras = int(duracao_s * SAMPLE_RATE)
    existentes = len(list(destino_dir.glob(f"{prefixo}-*.wav")))
    for i in range(existentes, n):
        origem = random.choice(arquivos)
        sr, dados = wavfile.read(origem)
        if len(dados) <= n_amostras:
            recorte = np.pad(dados, (0, n_amostras - len(dados)))
        else:
            inicio = random.randint(0, len(dados) - n_amostras)
            recorte = dados[inicio : inicio + n_amostras]
        wavfile.write(destino_dir / f"{prefixo}-{uuid.uuid4().hex}.wav", SAMPLE_RATE, recorte.astype(np.int16))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--output-dir", default=str(RAIZ / "treino"))
    ap.add_argument("--n-train", type=int, default=N_TRAIN)
    ap.add_argument("--n-val", type=int, default=N_VAL)
    args = ap.parse_args()

    output_dir = Path(args.output_dir)
    print("Carregando vozes Piper pt-BR...")
    engines = carregar_vozes(VOZES_DIR)

    for modelo, positivos in POSITIVOS.items():
        negativos = negativos_para(modelo)
        base = output_dir / modelo
        print(f"\n== {modelo} ==")
        print("  positivos (treino):")
        gerar_split(engines, positivos, base / "positive_train", args.n_train)
        print("  positivos (validação):")
        gerar_split(engines, positivos, base / "positive_test", args.n_val)
        print("  negativos falados (treino):")
        gerar_split(engines, negativos, base / "negative_train", args.n_train)
        print("  negativos falados (validação):")
        gerar_split(engines, negativos, base / "negative_test", args.n_val)
        print("  negativos de ruído ambiente (extra):")
        adicionar_recortes_como_negativo(RUIDO_BG_DIR, "ruido", base / "negative_train", N_RUIDO_EXTRA_TRAIN)
        adicionar_recortes_como_negativo(RUIDO_BG_DIR, "ruido", base / "negative_test", N_RUIDO_EXTRA_VAL)
        print("  negativos de fala real em português (extra):")
        adicionar_recortes_como_negativo(FALA_PT_DIR, "falapt", base / "negative_train", N_FALA_PT_TRAIN)
        adicionar_recortes_como_negativo(FALA_PT_DIR, "falapt", base / "negative_test", N_FALA_PT_VAL)

    print("\nPronto.")


if __name__ == "__main__":
    main()

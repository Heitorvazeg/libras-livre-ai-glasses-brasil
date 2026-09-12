#!/usr/bin/env python3
"""Baixa RIR (respostas ao impulso, pra simular reverberação de ambiente) e ruído de
fundo, mais o conjunto de validação de falso-positivo do próprio openWakeWord.

Ver docs/wake-word-treino-plano.md §3. Deliberadamente pequeno (dezenas de MB, não os
data sets de GBs que o notebook oficial do openWakeWord baixa) — troca robustez
marginal contra ruído por rodar numa sessão sem GPU e sem horas de banda. Ver
README.md "O que fica de fora desta rodada" pro custo dessa escolha.

Licenças (nada aqui entra no git — só working tree, mesma regra dos outros assets
pesados do projeto, ver mobile-app-companion/download-assets.sh):
  - MIT environmental impulse responses: domínio público (MIT McDermott Lab).
  - ESC-50: CC BY-NC 3.0 — uso de treino/augmentation aqui, NUNCA embarcado no app.
  - Validação de falso-positivo do openWakeWord: Apache 2.0 (mesmo projeto).
"""

from __future__ import annotations

import concurrent.futures as cf
import json
import random
import urllib.request
from pathlib import Path

import numpy as np
import soundfile as sf

try:
    import librosa
except ImportError:  # pragma: no cover - garantido por requirements.txt
    librosa = None

RAIZ = Path(__file__).resolve().parent.parent
RUIDO = RAIZ / "ruido"
RIR_DIR = RUIDO / "mit_rirs"
BG_DIR = RUIDO / "esc50_background"
VAL_FEATURES = RUIDO / "validation_set_features.npy"

MIT_RIR_API = (
    "https://huggingface.co/api/datasets/davidscripka/"
    "MIT_environmental_impulse_responses/tree/main/16khz?recursive=true"
)
MIT_RIR_BASE = (
    "https://huggingface.co/datasets/davidscripka/"
    "MIT_environmental_impulse_responses/resolve/main/16khz/"
)
ESC50_API = "https://api.github.com/repos/karolpiczak/ESC-50/contents/audio"
ESC50_BASE = "https://raw.githubusercontent.com/karolpiczak/ESC-50/master/audio/"
VAL_FEATURES_URL = (
    "https://huggingface.co/datasets/davidscripka/openwakeword_features/"
    "resolve/main/validation_set_features.npy"
)

SAMPLE_RATE = 16000
N_BACKGROUND = 120

# train.py carrega este arquivo inteiro e monta janelas DESLIZANTES com passo 1 (não
# passo N) — pro tamanho de janela do nosso modelo (~21 frames), as 481 mil linhas
# originais (~11h) viram uma matriz de ~3,9 GB só pra isso, repetida a cada checagem
# periódica de falso-positivo durante o treino. Nesta sessão (12 CPUs, 15 GB de RAM,
# compartilhados com o resto do desktop) isso já derrubou o processo por OOM. Recorte
# pra ~2,7h — ainda um proxy de falso-positivo razoável, bem mais barato de manter em
# memória. Aumente se rodar numa máquina com mais RAM disponível.
MAX_FRAMES_VALIDACAO = 120_000


def _get_json(url: str):
    with urllib.request.urlopen(url) as r:
        return json.loads(r.read())


def _fetch(url: str, destino: Path) -> None:
    if destino.exists() and destino.stat().st_size > 0:
        return
    destino.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(url, destino)


def _fetch_resample_16k(url: str, destino: Path) -> None:
    """Baixa um wav e regrava em 16 kHz mono PCM16 (ESC-50 vem em 44,1 kHz)."""
    if destino.exists() and destino.stat().st_size > 0:
        return
    destino.parent.mkdir(parents=True, exist_ok=True)
    tmp = destino.with_suffix(".orig.wav")
    urllib.request.urlretrieve(url, tmp)
    audio, sr = sf.read(tmp, dtype="float32", always_2d=False)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if sr != SAMPLE_RATE:
        assert librosa is not None, "librosa é necessário pra resample — ver requirements.txt"
        audio = librosa.resample(audio, orig_sr=sr, target_sr=SAMPLE_RATE)
    sf.write(destino, audio, SAMPLE_RATE, subtype="PCM_16")
    tmp.unlink(missing_ok=True)


def baixar_rir() -> None:
    existentes = list(RIR_DIR.glob("*.wav")) if RIR_DIR.exists() else []
    if len(existentes) > 200:
        print(f"RIR: já tem {len(existentes)} arquivos, pulando")
        return
    print("RIR (MIT): listando arquivos...")
    itens = [i for i in _get_json(MIT_RIR_API) if i["type"] == "file"]
    print(f"RIR (MIT): baixando {len(itens)} arquivos (~8 MB, domínio público)...")
    with cf.ThreadPoolExecutor(max_workers=8) as ex:
        futs = [
            ex.submit(_fetch, MIT_RIR_BASE + item["path"].split("/")[-1], RIR_DIR / item["path"].split("/")[-1])
            for item in itens
        ]
        for f in cf.as_completed(futs):
            f.result()
    print(f"RIR: {len(list(RIR_DIR.glob('*.wav')))} arquivos em {RIR_DIR}")


def baixar_background(n: int = N_BACKGROUND) -> None:
    existentes = list(BG_DIR.glob("*.wav")) if BG_DIR.exists() else []
    if len(existentes) >= n:
        print(f"Background: já tem {len(existentes)} arquivos, pulando")
        return
    print("Background (ESC-50, CC BY-NC — só treino, nunca embarcado): listando...")
    itens = [i for i in _get_json(ESC50_API) if i["name"].endswith(".wav")]
    random.Random(42).shuffle(itens)
    escolhidos = itens[:n]
    print(f"Background: baixando e resampleando {len(escolhidos)} arquivos pra 16 kHz...")
    with cf.ThreadPoolExecutor(max_workers=8) as ex:
        futs = [
            ex.submit(_fetch_resample_16k, ESC50_BASE + item["name"], BG_DIR / item["name"])
            for item in escolhidos
        ]
        for f in cf.as_completed(futs):
            f.result()
    print(f"Background: {len(list(BG_DIR.glob('*.wav')))} arquivos em {BG_DIR}")


def baixar_validacao() -> None:
    if VAL_FEATURES.exists() and VAL_FEATURES.stat().st_size > 1_000_000:
        print("Validação de falso-positivo: já existe, pulando")
        return
    print("Validação de falso-positivo (openWakeWord, ~11h pré-computadas, ~185 MB)...")
    tmp = VAL_FEATURES.with_suffix(".completo.npy")
    _fetch(VAL_FEATURES_URL, tmp)
    completo = np.load(tmp)
    recorte = completo[:MAX_FRAMES_VALIDACAO]
    np.save(VAL_FEATURES, recorte)
    tmp.unlink(missing_ok=True)
    horas = len(recorte) * 1280 / 1000 / 3600  # 1280 ms por passo de feature, ver train.py
    print(f"Validação: {VAL_FEATURES} — recortado de {len(completo)} pra {len(recorte)} frames (~{horas:.1f}h)")


if __name__ == "__main__":
    baixar_rir()
    baixar_background()
    baixar_validacao()

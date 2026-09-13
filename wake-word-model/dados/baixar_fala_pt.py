#!/usr/bin/env python3
"""Baixa fala real em português (MLS Portuguese) e recorta em clipes curtos pra
usar como negativo extra no treino — a peça que faltava pro `libras_livre_encerrar`.

Por quê: `resultados/libras_livre_encerrar/relatorio.md` (Rodada 2, com ACAV100M)
mostrou recall preso em ~0,40-0,47 em QUALQUER limiar — não é questão de
calibração, é falta de diversidade nos negativos. O ACAV100M é áudio genérico
(majoritariamente inglês/música/ruído — ver docs/wake-word-treino-plano.md §4);
os confusáveis de `dados/frases.py` são só texto que NÓS pensamos em escrever.
Nenhuma das duas fontes ensina o classificador como é falar PORTUGUÊS de verdade,
que é exatamente o que "terminar"/"finalizar" (os confusáveis mais parecidos com
`encerrar`) precisam pra não ficar num limbo entre "é a frase" e "não é".

MLS Portuguese (`facebook/multilingual_librispeech`, CC-BY-4.0): audiolivros em
português (LibriVox, domínio público na origem), 16 kHz, com transcrição. Não é
pt-BR — é majoritariamente português europeu — mas ainda ensina fonética e
prosódia portuguesa de verdade, o que nenhuma das outras fontes oferece. Usa os
splits pequenos "1_hours" e "9_hours" (curados pelo próprio MLS pra treino de
baixo recurso) — não o corpus completo (centenas de GB).

Licença: CC-BY-4.0 — uso de treino/augmentation aqui, nunca embarcado no app
(mesma regra do ESC-50 em dados/baixar_ruido.py).
"""

from __future__ import annotations

import random
import urllib.request
from io import BytesIO
from pathlib import Path

import numpy as np
import pandas as pd
import scipy.io.wavfile as wavfile
import soundfile as sf

RAIZ = Path(__file__).resolve().parent.parent
DESTINO = RAIZ / "ruido" / "fala_pt"
SAMPLE_RATE = 16000
DURACAO_CLIPE_S = 1.5
N_CLIPES = 600  # ~15 min de negativo real em pt — bem mais que os 120 do ESC-50

MLS_BASE = "https://huggingface.co/datasets/facebook/multilingual_librispeech/resolve/main/portuguese/"
SPLITS = ["1_hours-00000-of-00001.parquet", "9_hours-00000-of-00001.parquet"]


def _baixar_parquet(nome: str, destino: Path) -> None:
    if destino.exists() and destino.stat().st_size > 0:
        return
    destino.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(MLS_BASE + nome, destino)


def _recortar(audio: np.ndarray, n_amostras: int, rng: random.Random) -> np.ndarray | None:
    if len(audio) < n_amostras:
        return None
    inicio = rng.randint(0, len(audio) - n_amostras)
    return audio[inicio : inicio + n_amostras]


def baixar_fala_pt(n: int = N_CLIPES) -> None:
    existentes = list(DESTINO.glob("*.wav")) if DESTINO.exists() else []
    if len(existentes) >= n:
        print(f"Fala pt (MLS): já tem {len(existentes)} clipes, pulando")
        return

    tmp_dir = RAIZ / "ruido" / "_mls_tmp"
    print("Fala pt (MLS Portuguese, CC-BY-4.0): baixando splits pequenos (~157 MB no total)...")
    for split in SPLITS:
        _baixar_parquet(split, tmp_dir / split)

    print("Fala pt (MLS): lendo e recortando clipes...")
    DESTINO.mkdir(parents=True, exist_ok=True)
    n_amostras = int(DURACAO_CLIPE_S * SAMPLE_RATE)
    rng = random.Random(42)

    linhas: list[bytes] = []
    for split in SPLITS:
        df = pd.read_parquet(tmp_dir / split, columns=["audio"])
        linhas.extend(row["bytes"] for row in df["audio"])
    rng.shuffle(linhas)

    escritos = len(existentes)
    for audio_bytes in linhas:
        if escritos >= n:
            break
        try:
            audio, sr = sf.read(BytesIO(audio_bytes), dtype="float32", always_2d=False)
        except Exception:
            continue
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        if sr != SAMPLE_RATE:
            continue  # MLS já vem em 16 kHz; pular no caso raro de vir diferente
        # Cada clipe de origem é uma frase inteira (alguns segundos) — tira só um
        # recorte por clipe, pra maximizar quantos FALANTES/frases diferentes entram
        # nos N_CLIPES finais, em vez de várias fatias do mesmo áudio.
        recorte = _recortar(audio, n_amostras, rng)
        if recorte is None:
            continue
        amostras = (recorte * 32767).astype(np.int16)
        wavfile.write(DESTINO / f"mls-{escritos:05d}.wav", SAMPLE_RATE, amostras)
        escritos += 1

    print(f"Fala pt (MLS): {escritos} clipes em {DESTINO}")


if __name__ == "__main__":
    baixar_fala_pt()

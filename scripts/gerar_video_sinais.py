"""Gera o sinais.mp4 do FluxoCompletoTest: uma pessoa parada que "faz" 3 sinais.

Sem vídeo de Libras com licença para o repositório, o movimento é sintético: a parte do quadro
abaixo da linha dos ombros estica na vertical e volta (0,7 s), três vezes, com 1,1 s parado entre
elas e 6 s parado no fim. As mãos descem até ~0,5 largura de ombro e voltam; os ombros não mexem,
então o detector (1.1–1.6) vê três segmentos e o silêncio fecha a frase.

Imagem: male_full_height_hands.jpg, dos assets de teste do MediaPipe
(https://storage.googleapis.com/mediapipe-assets/male_full_height_hands.jpg, Apache License 2.0).

    uv run --no-project --with pillow --with imageio-ffmpeg scripts/gerar_video_sinais.py saida.mp4
"""

import math
import subprocess
import sys
import urllib.request
from io import BytesIO

import imageio_ffmpeg
from PIL import Image

URL = "https://storage.googleapis.com/mediapipe-assets/male_full_height_hands.jpg"
FPS, LARGURA, ALTURA = 30, 540, 960
ROTEIRO = [("parado", 1.5)] + [("sinal", 0.7), ("parado", 1.1)] * 3 + [("parado", 6.0)]

saida = sys.argv[1] if len(sys.argv) > 1 else "sinais.mp4"
foto = Image.open(BytesIO(urllib.request.urlopen(URL).read())).convert("RGB")
meio_corpo = foto.crop((40, 60, 620, 640)).resize((540, 540), Image.LANCZOS)
ombros_y = int((235 - 60) * 540 / 580)


def quadro(escala: float) -> Image.Image:
    fundo = Image.new("RGB", (LARGURA, ALTURA), (222, 220, 216))
    baixo = meio_corpo.crop((0, ombros_y, 540, 540))
    baixo = baixo.resize((540, int((540 - ombros_y) * escala)), Image.BILINEAR)
    fundo.paste(meio_corpo.crop((0, 0, 540, ombros_y)), (0, 180))
    fundo.paste(baixo, (0, 180 + ombros_y))
    return fundo


ffmpeg = subprocess.Popen(
    [imageio_ffmpeg.get_ffmpeg_exe(), "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
     "-s", f"{LARGURA}x{ALTURA}", "-r", str(FPS), "-i", "-", "-vf", "format=yuv420p", "-c:v", "libx265",
     "-x265-params", "keyint=30:bframes=0:log-level=error", "-tag:v", "hvc1", saida],
    stdin=subprocess.PIPE,
)
for tipo, duracao in ROTEIRO:
    n = int(duracao * FPS)
    for i in range(n):
        escala = 1.0 + (0.35 * math.sin(math.pi * i / n) ** 2 if tipo == "sinal" else 0.0)
        ffmpeg.stdin.write(quadro(escala).tobytes())
ffmpeg.stdin.close()
sys.exit(ffmpeg.wait())

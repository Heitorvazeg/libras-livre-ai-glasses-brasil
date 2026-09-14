"""Resposta do atendente para o FluxoCompletoTest: fala sintetizada injetada no microfone do emulador.

O teste instrumentado conduz a tela do app até a escuta (⑤) e escreve "FluxoCompleto: OUVINDO" no
logcat. Este script sintetiza a resposta com a mesma voz Piper do app, espera essa linha e injeta o
áudio pelo gRPC do emulador (EmulatorController.injectAudio). Não usa o áudio do host.

    uv run --no-project --with grpcio-tools --with sherpa-onnx --with numpy \
        scripts/fluxo_completo_emulador.py [--texto "Qual é a idade dele?"]

Rode antes de disparar o teste (ver docs/guia-de-testes-mock-e-oculos.md, "Fluxo completo no emulador").
"""

import argparse
import glob
import importlib
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[1]
VOZ = RAIZ / "mobile-app-companion/app/src/main/assets/tts/pt_br"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))


def sintetizar(texto: str) -> tuple[int, bytes]:
    import numpy as np
    import sherpa_onnx

    vits = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(VOZ / "pt_BR-edresson-low.onnx"),
        tokens=str(VOZ / "tokens.txt"),
        data_dir=str(VOZ / "espeak-ng-data"),
    )
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=vits)))
    audio = tts.generate(texto, sid=0, speed=0.9)
    # Meio segundo de silêncio antes e um depois: o Vosk precisa do fim de fala para avançar (4.1).
    amostras = np.concatenate([np.zeros(audio.sample_rate // 2), audio.samples, np.zeros(audio.sample_rate)])
    return audio.sample_rate, (np.clip(amostras, -1, 1) * 32767).astype("<i2").tobytes()


def stubs_do_emulador():
    from grpc_tools import protoc

    destino = tempfile.mkdtemp(prefix="emulador-grpc-")
    proto = SDK / "emulator/lib/emulator_controller.proto"
    inclusao = Path(protoc.__file__).parent / "_proto"
    if protoc.main(["protoc", f"-I{proto.parent}", f"-I{inclusao}", f"--python_out={destino}",
                    f"--grpc_python_out={destino}", proto.name]) != 0:
        sys.exit(f"não consegui gerar os stubs a partir de {proto}")
    sys.path.insert(0, destino)
    return importlib.import_module("emulator_controller_pb2"), importlib.import_module("emulator_controller_pb2_grpc")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--texto", default="Qual é a idade dele?")
    args = parser.parse_args()

    import grpc

    taxa, pcm = sintetizar(args.texto)
    pb, rpc = stubs_do_emulador()

    arquivos = glob.glob(os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "avd/running/pid_*.ini"))
    if not arquivos:
        sys.exit("nenhum emulador rodando")
    conf = dict(linha.strip().split("=", 1) for linha in open(arquivos[0]) if "=" in linha)
    stub = rpc.EmulatorControllerStub(grpc.insecure_channel(f"localhost:{conf['grpc.port']}"))
    metadados = [("authorization", f"Bearer {conf['grpc.token']}")]

    print("esperando o teste abrir a escuta (FluxoCompleto: OUVINDO)…", flush=True)
    adb = SDK / "platform-tools/adb"
    subprocess.run([adb, "logcat", "-c"], check=True)
    log = subprocess.Popen([adb, "logcat", "-v", "brief", "FluxoCompleto:I", "*:S"], stdout=subprocess.PIPE, text=True)
    for linha in log.stdout:
        if linha.rstrip().endswith("OUVINDO"):
            break
    log.kill()
    time.sleep(1.0)

    formato = pb.AudioFormat(samplingRate=taxa, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16)
    passo = taxa // 50 * 2  # 20 ms de S16 mono

    def pacotes():
        inicio = time.time()
        for i, pos in enumerate(range(0, len(pcm), passo)):
            yield pb.AudioPacket(format=formato, timestamp=int(time.time() * 1e6), audio=pcm[pos:pos + passo])
            espera = inicio + (i + 1) * 0.02 - time.time()
            if espera > 0:
                time.sleep(espera)

    print(f"injetando \"{args.texto}\" ({len(pcm) // 2 / taxa:.1f} s)", flush=True)
    stub.injectAudio(pacotes(), metadata=metadados)
    print("pronto", flush=True)


if __name__ == "__main__":
    main()

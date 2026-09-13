"""Compat shims para esta venv — corrigem duas rupturas de API entre as versões
recentes de libs (as únicas com wheel pro Python 3.14 desta sessão) e o que
`openwakeword/data.py` (vendorizado, não é nosso código) espera. Instalado por
treinar.sh no site-packages da venv — por isso mora em `_vendor/`, não em `dados/`.

1. `acoustics==0.2.6` (usada só por `acoustics.generator.noise` — nunca por
   `acoustics.directivity`, o único módulo que precisa disto) importa
   `scipy.special.sph_harm`, removido em favor de `sph_harm_y` (ordem de
   argumentos e convenção theta/phi trocadas).
2. `torchaudio` desta versão roteia `load()`/`info()` sempre por `torchcodec`
   (removeu os backends antigos `sox_io`/`soundfile`), e o wheel do `torchcodec`
   pro Python 3.14 linka contra libs de CUDA (`libnvrtc.so.13`) mesmo em uso
   CPU-only — quebra o import mesmo sem GPU nenhuma envolvida. Substituição:
   `soundfile` direto (já é dependência de qualquer forma, via `librosa`).
"""

try:
    import scipy.special as _sp

    if not hasattr(_sp, "sph_harm") and hasattr(_sp, "sph_harm_y"):

        def sph_harm(m, n, theta, phi, *args, **kwargs):
            # convenção antiga: theta=azimutal, phi=polar (oposto da sph_harm_y nova)
            return _sp.sph_harm_y(n, m, phi, theta)

        _sp.sph_harm = sph_harm
except ImportError:
    pass


try:
    import collections

    import soundfile as _sf
    import torch as _torch
    import torchaudio as _ta

    _AudioMetaData = collections.namedtuple(
        "AudioMetaData", ["sample_rate", "num_frames", "num_channels", "bits_per_sample", "encoding"]
    )

    def _load_via_soundfile(uri, frame_offset=0, num_frames=-1, normalize=True, channels_first=True, format=None, buffer_size=4096, backend=None):
        data, sr = _sf.read(str(uri), dtype="float32", always_2d=True)
        end = None if num_frames == -1 else frame_offset + num_frames
        data = data[frame_offset:end]
        tensor = _torch.from_numpy(data.T.copy() if channels_first else data.copy())
        return tensor, sr

    def _info_via_soundfile(uri, format=None, backend=None):
        info = _sf.info(str(uri))
        bits = {"PCM_16": 16, "PCM_24": 24, "PCM_32": 32, "FLOAT": 32, "DOUBLE": 64}.get(info.subtype, 16)
        return _AudioMetaData(
            sample_rate=info.samplerate,
            num_frames=info.frames,
            num_channels=info.channels,
            bits_per_sample=bits,
            encoding="PCM_S",
        )

    _ta.load = _load_via_soundfile
    _ta.info = _info_via_soundfile
except ImportError:
    pass

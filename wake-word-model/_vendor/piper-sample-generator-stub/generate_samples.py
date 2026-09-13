"""Stub de `piper-sample-generator` — só existe pra satisfazer o import incondicional
que `openwakeword/train.py` faz logo no início (`from generate_samples import
generate_samples`), mesmo quando a flag `--generate_clips` não é usada.

Este projeto não chama `--generate_clips`: `dados/sintetizar.py` gera os clipes
positivos/negativos com vozes Piper pt-BR (sherpa-onnx) diretamente na estrutura de
pastas que o `train.py` espera — ver docs/wake-word-treino-plano.md §2 pro motivo
(o piper-sample-generator real só tem checkpoint multi-falante pronto em
en/de/fr/nl, não em pt-BR).

Se algum dia existir um checkpoint pt-BR compatível com o piper-sample-generator de
verdade, troque `piper_sample_generator_path` no config/*.yaml pelo clone real
(https://github.com/rhasspy/piper-sample-generator) — este stub deixa de ser
necessário, e `--generate_clips` passaria a funcionar como no notebook oficial.
"""


def generate_samples(*args, **kwargs):
    raise NotImplementedError(
        "Stub — este projeto não usa --generate_clips (ver docstring do módulo). "
        "Os clipes são gerados por dados/sintetizar.py."
    )

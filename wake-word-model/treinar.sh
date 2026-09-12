#!/usr/bin/env bash
#
# treinar.sh — pipeline completo: vozes -> dados sintéticos -> ruído -> treino -> export .onnx
#
# Roda os dois classificadores (iniciar/encerrar) em sequência. Ver README.md e
# docs/wake-word-treino-plano.md pro "porquê" de cada etapa e pro que fica de fora
# desta rodada (ACAV100M completo, piper-sample-generator multi-falante em pt-BR).
#
# Idempotente por etapa: reexecutar pula o que já existe (vozes, ruído baixado,
# clipes já sintetizados, features já augmentadas). Pra forçar do zero, apague
# vozes/, ruido/ e/ou treino/<modelo>/ conforme a etapa.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

VENV="$DIR/.venv"
if [ ! -d "$VENV" ]; then
  echo "==> criando venv"
  python3 -m venv "$VENV"
fi
PIP="$VENV/bin/pip"
PY="$VENV/bin/python"

echo "==> instalando dependências (a primeira vez leva alguns minutos)"
"$PIP" install -q --upgrade pip
"$PIP" install -q -r requirements.txt

# Ver _vendor/sitecustomize_compat_shims.py: acoustics==0.2.6 e torchaudio quebram
# contra as versões novas de scipy/torchcodec que este Python exige. Só afeta esta venv.
SITE_PACKAGES="$("$PY" -c 'import site; print(site.getsitepackages()[0])')"
cp "$DIR/_vendor/sitecustomize_compat_shims.py" "$SITE_PACKAGES/sitecustomize.py"

OWW_SRC="$DIR/_vendor/openWakeWord-src"
if [ ! -d "$OWW_SRC" ]; then
  echo "==> clonando openWakeWord (fonte — train.py não vem no pacote publicado no PyPI)"
  git clone --depth 1 https://github.com/dscripka/openWakeWord "$OWW_SRC"
fi
# O DataLoader de treino usa `num_workers=os.cpu_count()//2` — pensado pro caso de
# uso original (features do ACAV100M, ~17 GB, onde paralelismo compensa o fork). Já
# causou OOM kill nesta sessão (12 CPUs, 15 GB de RAM, sem esse dataset — o nosso é
# pequeno, single-process é rápido o bastante e não arrisca o processo inteiro). Sem
# efeito se já foi aplicado (sed não acha o texto de novo).
sed -i 's/num_workers=n_cpus, prefetch_factor=16/num_workers=0/' "$OWW_SRC/openwakeword/train.py"

# Bug do upstream: todo outro --flag é checado com `is True` (funciona certo com o
# default="False" — STRING, não bool — que o argparse usa pra essas flags), menos
# --convert_to_tflite, checado só por truthiness — "False" (string) é truthy, então
# esse bloco roda SEMPRE, mesmo sem passar a flag. Não usamos .tflite (a integração
# Android é ONNX Runtime — ver docs/wake-word-treino-plano.md §2), e o bloco exige
# tensorflow-cpu==2.8.1 + onnx_tf que não instalamos de propósito. Corrige pro mesmo
# padrão `is True` das outras flags.
sed -i 's/if args.convert_to_tflite:/if args.convert_to_tflite is True:/' "$OWW_SRC/openwakeword/train.py"

# --no-deps: o setup.py do repositório puxa tensorflow-cpu==2.8.1 + onnx_tf (só usados
# pra converter pra .tflite — a integração Android usa .onnx, ver
# docs/wake-word-treino-plano.md §2) e speexdsp-ns (sem wheel nesta plataforma/versão
# de Python). requirements.txt já cobre tudo que train.py/data.py realmente importam.
"$PIP" install -q -e "$OWW_SRC" --no-deps

# resources/models/ não vem no clone (binários grandes, baixados sob demanda). São os
# MESMOS dois arquivos fixos que mobile-app-companion/download-assets.sh baixa pro app
# — aqui servem pro AudioFeatures do train.py extrair as features de treino.
OWW_MODELS="$OWW_SRC/openwakeword/resources/models"
mkdir -p "$OWW_MODELS"
for f in melspectrogram.onnx embedding_model.onnx; do
  if [ -s "$OWW_MODELS/$f" ]; then
    continue
  fi
  echo "==> baixando $f (modelo fixo do openWakeWord)"
  curl -fSL --retry 3 --connect-timeout 30 -o "$OWW_MODELS/$f" \
    "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$f"
done

echo "==> vozes Piper pt-BR"
bash dados/baixar_vozes.sh

echo "==> ruído (RIR + background) e validação de falso-positivo"
"$PY" dados/baixar_ruido.py

echo "==> sintetizando clipes positivos/negativos (os dois modelos de uma vez, reaproveita as engines)"
"$PY" dados/sintetizar.py --output-dir ./treino

for MODELO in libras_livre_iniciar libras_livre_encerrar; do
  echo "==================================================================="
  echo "==> $MODELO: augmentation + extração de features"
  echo "==================================================================="
  "$PY" "$OWW_SRC/openwakeword/train.py" --training_config "config/$MODELO.yaml" --augment_clips

  echo "==> $MODELO: treino + export .onnx"
  "$PY" "$OWW_SRC/openwakeword/train.py" --training_config "config/$MODELO.yaml" --train_model

  echo "==> $MODELO: relatório"
  "$PY" avaliar.py --modelo "$MODELO"
done

echo
echo "==> pronto."
echo "    Classificadores: treino/libras_livre_{iniciar,encerrar}.onnx"
echo "    Relatórios:      resultados/libras_livre_{iniciar,encerrar}/relatorio.md"
echo
echo "Pra testar no app, copie os dois .onnx pra:"
echo "  ../mobile-app-companion/app/src/main/assets/wakeword/libras_livre_iniciar.onnx"
echo "  ../mobile-app-companion/app/src/main/assets/wakeword/libras_livre_encerrar.onnx"
echo "e troque qual WakeWordDetector o CameraViewModel instancia (ver"
echo "docs/orquestracao-dialogo-audio-plano.md Fase 3, último item)."

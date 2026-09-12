#!/usr/bin/env bash
#
# download-assets.sh — baixa os modelos/assets pesados do Libras Livre para
# app/src/main/assets/. Nenhum deles é versionado no git (ver assets/.gitignore);
# rode este script uma vez antes de buildar o app.
#
# Idempotente: pula o que já existe. Force um re-download apagando o arquivo/pasta
# alvo, ou rode com FORCE=1 ./download-assets.sh
#
# Origens (ver também ../java/com/.../libras/README.md):
#   Visão  — MediaPipe (Google)            *.task
#   TTS    — Piper/sherpa-onnx (int8)       tts/pt_br/*
#   STT    — Vosk pt-BR                     vosk-model-small-pt-0.3/*
#   Wake   — openWakeWord (fixos)           melspectrogram.onnx, embedding_model.onnx
#
# NÃO baixados aqui (ver mobile-app-companion/README.md §2.3):
#   - modelo_contextualizacao.tflite — gerado por
#     contextualization-model/exportacao/para_tflite.py --experimento v2
#   - wakeword/libras_livre_{iniciar,encerrar}.onnx — exigem treino (ver rodapé)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSETS="$SCRIPT_DIR/app/src/main/assets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$ASSETS/tts/pt_br" "$ASSETS/wakeword"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
skip() { printf '\033[1;32m ✓ \033[0m %s (já existe, pulando)\n' "$*"; }

fetch() { # fetch <url> <destino>
  curl -fSL --retry 3 --connect-timeout 30 -o "$2" "$1"
}

present() { # present <caminho> — true se existe e não está vazio, salvo FORCE=1
  [ "${FORCE:-0}" != "1" ] && [ -s "$1" ]
}

# ---------------------------------------------------------------------------
# 1) Visão — MediaPipe (.task)
# ---------------------------------------------------------------------------
MP_BASE="https://storage.googleapis.com/mediapipe-models"
declare -A TASKS=(
  ["pose_landmarker_lite.task"]="$MP_BASE/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
  ["hand_landmarker.task"]="$MP_BASE/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
)
for f in "${!TASKS[@]}"; do
  if present "$ASSETS/$f"; then skip "$f"; else
    log "MediaPipe: $f"
    fetch "${TASKS[$f]}" "$ASSETS/$f"
  fi
done

# ---------------------------------------------------------------------------
# 2) TTS — Piper/sherpa-onnx (variante int8, bate com o que o app espera)
# ---------------------------------------------------------------------------
if present "$ASSETS/tts/pt_br/pt_BR-edresson-low.onnx"; then
  skip "tts/pt_br/pt_BR-edresson-low.onnx"
else
  log "TTS: vits-piper-pt_BR-edresson-low-int8.tar.bz2 (~21MB)"
  fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-edresson-low-int8.tar.bz2" \
        "$TMP/tts.tar.bz2"
  tar -xjf "$TMP/tts.tar.bz2" -C "$TMP"
  # Extrai o CONTEÚDO da pasta do tar (não a pasta) para tts/pt_br/
  cp -a "$TMP"/vits-piper-pt_BR-edresson-low-int8/. "$ASSETS/tts/pt_br/"
fi

# ---------------------------------------------------------------------------
# 3) STT — Vosk pt-BR
#    Motor ATIVO: CameraViewModel instancia VoskSttEngine. Sem este modelo, a
#    transcrição da resposta do atendente não funciona.
# ---------------------------------------------------------------------------
if present "$ASSETS/vosk-model-small-pt-0.3/final.mdl"; then
  skip "vosk-model-small-pt-0.3/"
else
  log "STT: vosk-model-small-pt-0.3.zip (~40MB)"
  fetch "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip" "$TMP/vosk.zip"
  unzip -q "$TMP/vosk.zip" -d "$TMP/vosk"
  cp -a "$TMP"/vosk/vosk-model-small-pt-0.3/. "$ASSETS/vosk-model-small-pt-0.3/"
fi

# ---------------------------------------------------------------------------
# 4) Wake word — openWakeWord (modelos fixos, sem treino)
# ---------------------------------------------------------------------------
OWW="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
for f in melspectrogram.onnx embedding_model.onnx; do
  if present "$ASSETS/$f"; then skip "$f"; else
    log "Wake word (fixo): $f"
    fetch "$OWW/$f" "$ASSETS/$f"
  fi
done

# ---------------------------------------------------------------------------
echo
log "Concluído."
echo
echo "PENDENTE (não baixável — exige treino): os classificadores custom pt-BR"
echo "  app/src/main/assets/wakeword/libras_livre_iniciar.onnx"
echo "  app/src/main/assets/wakeword/libras_livre_encerrar.onnx"
echo "O openWakeWord só traz modelos prontos em inglês (alexa, hey jarvis, ...)."
echo "Treine as duas frases pt-BR via automatic_model_training.ipynb do openWakeWord"
echo "(ver docs/orquestracao-dialogo-audio-plano.md §7 Fase 3) e coloque os .onnx acima."

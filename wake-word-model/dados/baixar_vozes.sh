#!/usr/bin/env bash
#
# baixar_vozes.sh — baixa as vozes Piper pt-BR (bundle sherpa-onnx, mesmo formato que
# mobile-app-companion usa pro TTS do app) usadas pra sintetizar o dataset de treino
# da wake word. Ver README.md "Por que várias vozes, e não um gerador multi-falante"
# pro motivo desta escolha.
#
# Idempotente: pula vozes já baixadas.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VOZES_DIR="$SCRIPT_DIR/../vozes"
mkdir -p "$VOZES_DIR"

BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

# As 6 vozes pt-BR de comunidade disponíveis prontas no release do sherpa-onnx
# (variante int8: ~21 MB cada, mesma escolha de tamanho que a voz de TTS do app).
VOZES=(edresson-low faber-medium cadu-medium jeff-medium miro-high dii-high)

for v in "${VOZES[@]}"; do
  dir="$VOZES_DIR/vits-piper-pt_BR-$v-int8"
  if [ -d "$dir" ] && [ -n "$(ls -A "$dir" 2>/dev/null)" ]; then
    printf '\033[1;32m ✓ \033[0m %s (já existe)\n' "$v"
    continue
  fi
  printf '\033[1;34m==>\033[0m baixando voz %s\n' "$v"
  tmp="$(mktemp)"
  curl -fSL --retry 3 --connect-timeout 30 -o "$tmp" "$BASE/vits-piper-pt_BR-$v-int8.tar.bz2"
  tar -xjf "$tmp" -C "$VOZES_DIR"
  rm -f "$tmp"
done

echo "Vozes prontas em $VOZES_DIR"

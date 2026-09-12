#!/bin/bash
# Temperatura por NÚCLEO (coretemp), clock e o que o regulador está fazendo.
# Usa o mesmo sensor que o regulador — não o acpitz do chassi, que tem inércia
# de minutos e mostraria um número otimista.
#   verde  < 74°C   amarelo 74-79°C (faixa de regulação)   vermelho >= 80°C (limite)
HW=""
for d in /sys/class/hwmon/hwmon*; do
  [ "$(cat "$d/name" 2>/dev/null)" = coretemp ] && HW=$d && break
done
[ -z "$HW" ] && { echo "coretemp não encontrado"; exit 1; }
R=$'\e[0m'; VERDE=$'\e[32m'; AMAR=$'\e[33m'; VERM=$'\e[31m'
DATA=/home/walisson/libras-livre-ai-glasses-brasil/computer-vision-model/PoC/data
trap 'printf "\n"; exit 0' INT
while true; do
  MAX=0; LINHA=""
  for f in "$HW"/temp*_label; do
    case "$(cat "$f")" in
      Core*) V=$(( $(cat "${f%_label}_input")/1000 ))
             [ $V -gt $MAX ] && MAX=$V
             if   [ $V -ge 80 ]; then C=$VERM
             elif [ $V -ge 74 ]; then C=$AMAR
             else                     C=$VERDE; fi
             LINHA+="${C}$(printf '%2d' $V)${R} ";;
    esac
  done
  MHZ=$(( $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq)/1000 ))
  PCT=$(cat /sys/devices/system/cpu/intel_pstate/max_perf_pct)
  PERF=$(cat /sys/firmware/acpi/platform_profile)
  NPY=$(ls "$DATA"/landmarks-malta/*.npy 2>/dev/null | wc -l)
  printf '\r\033[K%s| max %s%3d°C%s | %4d MHz | teto %3d%% | %-11s | npy %s' \
    "$LINHA" "$([ $MAX -ge 80 ] && echo "$VERM" || echo "$VERDE")" "$MAX" "$R" \
    "$MHZ" "$PCT" "$PERF" "$NPY"
  sleep 1
done

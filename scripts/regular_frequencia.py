#!/usr/bin/env python3
"""Mantém o clock no ponto mais alto que o teto térmico permite, em malha fechada.

POR QUE ISTO É MELHOR QUE PAUSAR O TRABALHO. O ciclo SIGSTOP/SIGCONT do
`extrair_com_limite_termico.py` respeita o teto, mas de forma binária: corre a
toda até bater na parede, congela, corre de novo. O tempo parado é trabalho não
feito, e o clock durante a corrida está mais alto do que o sustentável.

Regular a frequência resolve na raiz: em vez de alternar entre 100% e 0%, o
processador fica no percentual que produz exatamente a temperatura desejada.
Nada para, e o núcleo passa o tempo todo perto do alvo em vez de oscilar.

CONTROLE PROPORCIONAL, NÃO LIGA/DESLIGA. Passo fixo é ruim nos dois sentidos:
pequeno demais e a temperatura sobe mais rápido que a correção; grande demais e
o sistema oscila. O passo aqui cresce com o erro — 1°C acima do alvo corrige de
leve, 6°C acima corrige forte — e a descida é mais lenta que a subida (assimetria
deliberada: passar do teto é caro, ficar 3% abaixo do ótimo não é).

POR QUE `max_perf_pct` E NÃO `scaling_max_freq`. O primeiro é um só arquivo para
o pacote inteiro; o segundo é por CPU (12 aqui) e precisaria de 12 escritas
coerentes por ciclo. Com `intel_pstate` ativo, o percentual é a interface certa.

ATENÇÃO AO PERFIL DE PLATAFORMA. Se `platform_profile` estiver em `quiet`, o
firmware limita a potência (PL1/PL2) num ponto abaixo do que o P-state permite,
e mexer em `max_perf_pct` não muda nada — o gargalo é outro. O script detecta e
avisa, em vez de regular no vazio.

Uso:
    python regular_frequencia.py                 # alvo 74°C, limite 80°C
    python regular_frequencia.py --alvo 70
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

CTL = Path("/sys/devices/system/cpu/intel_pstate/max_perf_pct")
PERFIL = Path("/sys/firmware/acpi/platform_profile")
FREQ = Path("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
ESTADO = Path(__file__).resolve().parents[1] / "computer-vision-model" / "PoC" / "data" / ".regulador.json"


def sensores() -> list[Path]:
    for hw in sorted(Path("/sys/class/hwmon").glob("hwmon*")):
        n = hw / "name"
        if n.is_file() and n.read_text().strip() == "coretemp":
            return [p.with_name(p.name.replace("_label", "_input"))
                    for p in sorted(hw.glob("temp*_label"))
                    if p.read_text().startswith("Core")]
    return []


def temp(ss: list[Path]) -> float:
    v = []
    for s in ss:
        try:
            v.append(int(s.read_text()) / 1000.0)
        except (OSError, ValueError):
            pass
    return max(v) if v else 0.0


def escrever(pct: int) -> bool:
    """Escrita via a regra NOPASSWD estreita; sem senha em lugar nenhum."""
    r = subprocess.run(["sudo", "-n", "/usr/bin/tee", str(CTL)],
                       input=f"{pct}\n", capture_output=True, text=True)
    return r.returncode == 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--alvo", type=float, default=74.0, help="°C que se quer manter")
    ap.add_argument("--limite", type=float, default=80.0, help="°C que não pode ser passado")
    ap.add_argument("--min-pct", type=int, default=25)
    ap.add_argument("--partida", type=int, default=40,
                    help="%% inicial; sobe até o alvo em vez de descer do topo")
    ap.add_argument("--max-pct", type=int, default=100)
    ap.add_argument("--intervalo", type=float, default=1.0)
    ap.add_argument("--assentamento", type=float, default=6.0,
                    help="s a esperar o efeito de uma correção antes da próxima")
    ap.add_argument("--banda", type=float, default=3.0,
                    help="°C abaixo do alvo antes de voltar a subir o teto")
    args = ap.parse_args(argv)

    ss = sensores()
    if not ss:
        raise SystemExit("coretemp ausente")
    if not escrever(int(CTL.read_text())):
        raise SystemExit("sem permissão para escrever max_perf_pct — falta a regra NOPASSWD")
    perfil = PERFIL.read_text().strip() if PERFIL.is_file() else "?"
    if perfil == "quiet":
        print("[regulador] ⚠ platform_profile=quiet limita a potência no firmware; "
              "o percentual do P-state não será o gargalo. Mude para 'balanced' "
              "para o regulador ter efeito.", file=sys.stderr, flush=True)

    # PARTIDA FRIA POR BAIXO. Começar em max_pct e descer cria uma janela quente
    # em toda partida: os trabalhadores sobem juntos em menos de um segundo e a
    # primeira correção só chega depois. Medido aqui: pico de 83°C com 4
    # trabalhadores e 84°C com 7, ambos acima do limite de 80 — e o regime já
    # estava estável logo em seguida, ou seja, o estouro foi só da largada.
    # Subir a partir de um ponto conservador custa alguns segundos de throughput
    # e não estoura nada.
    pct = args.partida
    escrever(pct)
    picos, inicio, escritas, ultimo = 0, time.time(), 0, 0.0
    try:
        while True:
            t = temp(ss)
            erro = t - args.alvo
            novo = pct
            # ASSENTAMENTO. Sem esperar o efeito da correção anterior, o laço vira
            # windup: corrige, a temperatura ainda não respondeu, corrige de novo,
            # e o teto despenca. Medido aqui: 66→49→48→47→38→34 em seis leituras,
            # com o clock caindo de 3,0 GHz para 1,6 GHz e a extração ficando MAIS
            # lenta do que sem regulador nenhum. O núcleo leva alguns segundos para
            # refletir uma mudança de P-state; só decide de novo depois disso.
            urgente = t >= args.limite
            if urgente or time.time() - ultimo >= args.assentamento:
                if erro > 0:
                    # Passo suave: a correção grande é para emergência, não para
                    # oscilação normal em torno do alvo.
                    passo = max(1, int(erro))
                    if urgente:
                        passo = max(passo, 10)
                        picos += 1
                    novo = max(args.min_pct, pct - passo)
                elif erro < -args.banda:
                    novo = min(args.max_pct, pct + 2)
            if novo != pct and escrever(novo):
                pct, escritas, ultimo = novo, escritas + 1, time.time()
            ESTADO.write_text(json.dumps({
                "atualizado": time.time(), "temp_max_nucleo": round(t, 1),
                "alvo": args.alvo, "limite": args.limite, "max_perf_pct": pct,
                "mhz": round(int(FREQ.read_text()) / 1000) if FREQ.is_file() else None,
                "platform_profile": perfil, "vezes_no_limite": picos,
                "escritas": escritas, "decorrido_s": round(time.time() - inicio),
            }), encoding="utf-8")
            time.sleep(args.intervalo)
    except KeyboardInterrupt:
        pass
    finally:
        # Devolver a máquina como estava é obrigação: deixar o teto em 30% depois
        # de um Ctrl+C transformaria o laptop em lesma sem ninguém saber por quê.
        escrever(args.max_pct)
        print(f"[regulador] encerrado; max_perf_pct devolvido a {args.max_pct}%",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

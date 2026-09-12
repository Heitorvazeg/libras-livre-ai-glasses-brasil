#!/usr/bin/env python3
"""Extrai landmarks em paralelo mantendo os NÚCLEOS abaixo de um teto de temperatura.

POR QUE NÚCLEO E NÃO CHASSI. `acpitz` (e os sensores de gabinete em geral) tem
inércia térmica de minutos: ele sobe muito depois do núcleo e desce muito depois
também. Regular por ele deixa o núcleo passar do teto por um bom tempo antes do
sensor acusar. Quem responde na escala de segundos é `coretemp`, e é nele que
este script regula — máximo entre todos os `Core N`, não a média nem o `Package`.

COMO REGULA, JÁ QUE NÃO HÁ ROOT. `intel_pstate/max_perf_pct` pertence ao root,
então não dá para baixar a frequência. O que sobra, e funciona bem, é ciclo de
trabalho: SIGSTOP em todos os trabalhadores quando passa do teto, SIGCONT quando
volta à marca de retomada. Parar é instantâneo e não perde trabalho — o processo
congela onde está e continua depois. Matar e reiniciar perderia o vídeo em curso.

A HISTERESE É OBRIGATÓRIA. Parar em 80 e voltar em 80 produz centenas de
pausas por minuto (o núcleo cai ~2°C em menos de um segundo sem carga), e o
custo de troca engole o ganho. Retomar só alguns graus abaixo mantém a
frequência de chaveamento em algo como uma vez a cada dezenas de segundos.

PASSADAS REPETIDAS, PORQUE O DOWNLOAD AINDA CORRE. `extract.py` pula .npy que já
existe, então cada passada processa só o que chegou desde a anterior. O script
repete enquanto o download estiver vivo e ainda houver vídeo sem landmark.

Uso:
    python extrair_com_limite_termico.py --entrada .../raw-malta --saida .../landmarks-malta
    python extrair_com_limite_termico.py --teto 80 --retomar 72 --trabalhadores 4
"""
from __future__ import annotations

import argparse
import json
import signal
import subprocess
import sys
import time
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[1]
CVM = RAIZ / "computer-vision-model"
EXTRATOR = CVM / "PoC" / "src" / "extract.py"
PYTHON_MP = Path("/tmp/claude-1000/-home-walisson-libras-livre-ai-glasses-brasil"
                 "/6c421e0f-0c34-4a6d-9e4f-445547e67274/scratchpad/mpvenv/bin/python")
ESTADO = CVM / "PoC" / "data" / ".extracao-ufsc.json"


def sensores_coretemp() -> list[Path]:
    """Só `coretemp`, e dentro dele só os rótulos 'Core N'."""
    for hw in sorted(Path("/sys/class/hwmon").glob("hwmon*")):
        nome = (hw / "name")
        if nome.is_file() and nome.read_text().strip() == "coretemp":
            return [p.with_name(p.name.replace("_label", "_input"))
                    for p in sorted(hw.glob("temp*_label"))
                    if p.read_text().startswith("Core")]
    return []


def temperatura(sensores: list[Path]) -> float:
    """O MAIOR núcleo. A média esconde um núcleo saturado atrás de outros frios."""
    vals = []
    for s in sensores:
        try:
            vals.append(int(s.read_text()) / 1000.0)
        except (OSError, ValueError):
            continue
    return max(vals) if vals else 0.0


def pendentes(entrada: Path, saida: Path) -> int:
    feitos = {p.stem for p in saida.glob("*.npy")}
    return sum(1 for v in entrada.glob("*.mp4") if v.stem not in feitos)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--entrada", type=Path, required=True)
    ap.add_argument("--saida", type=Path, required=True)
    # BANDA DE GUARDA. Medido nesta máquina (i5-1334U): com 4 trabalhadores e poll
    # de 2 s, o núcleo passou de 80 para 91°C entre duas leituras. MediaPipe satura
    # os núcleos de performance em menos de um segundo, e o sensor só conta depois.
    # Então o teto de AÇÃO fica abaixo do teto pedido, e o poll desce para 0,5 s:
    # o limite de 80°C é o que não pode ser ultrapassado, não o gatilho.
    ap.add_argument("--teto", type=float, default=76.0,
                    help="°C: gatilho de pausa (fica abaixo do limite real, por overshoot)")
    ap.add_argument("--limite", type=float, default=80.0,
                    help="°C: limite que não pode ser ultrapassado; excedê-lo reduz trabalhadores")
    ap.add_argument("--retomar", type=float, default=68.0, help="°C: abaixo disso, retoma")
    ap.add_argument("--trabalhadores", type=int, default=2)
    ap.add_argument("--intervalo", type=float, default=0.5, help="s entre leituras do sensor")
    ap.add_argument("--aguardar-pid", type=int, default=0,
                    help="repete enquanto este PID (o download) estiver vivo")
    args = ap.parse_args(argv)
    if args.retomar >= args.teto:
        raise SystemExit("--retomar precisa ser menor que --teto (histerese)")
    # ABSOLUTO, SEMPRE. Os trabalhadores rodam com cwd=PoC/, então um caminho
    # relativo dado aqui resolve DE NOVO contra PoC/ e vira
    # `PoC/computer-vision-model/PoC/data/...`. O extract.py então diz "nenhum
    # vídeo", sai em 2 s, o laço reinicia, e o conjunto parece estar extraindo
    # sem produzir nada. Foi exatamente o que aconteceu, por 123 passadas.
    args.entrada = args.entrada.resolve()
    args.saida = args.saida.resolve()
    if not args.entrada.is_dir():
        raise SystemExit(f"entrada não existe: {args.entrada}")

    sensores = sensores_coretemp()
    if not sensores:
        raise SystemExit("coretemp não encontrado; recuso regular por sensor de chassi")
    args.saida.mkdir(parents=True, exist_ok=True)

    vivo = lambda pid: pid and Path(f"/proc/{pid}").exists()
    passada, pausas, t_pausado, excedeu, estagnadas = 0, 0, 0.0, 0, 0
    trabalhadores = args.trabalhadores
    inicio = time.time()

    while True:
        se_faltam = pendentes(args.entrada, args.saida)
        if se_faltam == 0 and not vivo(args.aguardar_pid):
            break
        if se_faltam == 0:
            time.sleep(20)
            continue

        passada += 1
        # `nice` baixa a prioridade: não muda a temperatura sozinho, mas evita que a
        # extração dispute CPU com o download e com o resto da máquina.
        # NUNCA /dev/null. Mandar a saída dos trabalhadores para o vazio já custou
        # caro aqui: a extração ficou minutos em laço, produzindo quase nada, e o
        # motivo estava no stderr que eu tinha jogado fora. Silêncio de trabalhador
        # é indistinguível de trabalho, e é exatamente o que não se pode confundir.
        logs = args.saida.parent / ".extracao-logs"
        logs.mkdir(exist_ok=True)
        handles = [(logs / f"worker{i+1}.log").open("w") for i in range(trabalhadores)]
        procs = [subprocess.Popen(
            ["nice", "-n", "10", str(PYTHON_MP), str(EXTRATOR),
             "--entrada", str(args.entrada), "--saida", str(args.saida),
             "--particao", f"{i+1}/{trabalhadores}"],
            cwd=str(CVM / "PoC"), stdout=h, stderr=subprocess.STDOUT)
            for i, h in enumerate(handles)]

        parado, marca, pico = False, 0.0, 0.0
        while any(p.poll() is None for p in procs):
            t = temperatura(sensores)
            pico = max(pico, t)
            if t > args.limite:
                excedeu += 1
            if not parado and t > args.teto:
                for p in procs:
                    if p.poll() is None:
                        p.send_signal(signal.SIGSTOP)
                parado, pausas, marca = True, pausas + 1, time.time()
            elif parado and t < args.retomar:
                for p in procs:
                    if p.poll() is None:
                        p.send_signal(signal.SIGCONT)
                parado = False
                t_pausado += time.time() - marca
            ESTADO.write_text(json.dumps({
                "atualizado": time.time(), "passada": passada,
                "temp_max_nucleo": round(t, 1), "pico_passada": round(pico, 1),
                "teto_gatilho": args.teto, "limite": args.limite,
                "trabalhadores": trabalhadores, "excedeu_limite": excedeu,
                "estado": "pausado_termico" if parado else "extraindo",
                "pausas": pausas, "segundos_pausado": round(t_pausado),
                "landmarks": len(list(args.saida.glob("*.npy"))),
                "faltam": pendentes(args.entrada, args.saida),
                "decorrido_s": round(time.time() - inicio),
            }), encoding="utf-8")
            time.sleep(args.intervalo)

        # Se o limite REAL foi furado nesta passada, a pausa não está bastando:
        # menos trabalhador é o único lever que sobra sem root.
        if excedeu and trabalhadores > 1:
            trabalhadores -= 1
            excedeu = 0

        # Um trabalhador congelado no fim da passada nunca termina: solta todos.
        for p in procs:
            try:
                p.send_signal(signal.SIGCONT)
            except OSError:
                pass
        for h in handles:
            h.close()
        # Passada que não produziu nada e terminou rápido é laço de falha, não
        # trabalho. Sem este freio o orquestrador gira para sempre reiniciando
        # processos que morrem na largada — foi o que aconteceu.
        codigos = [p.returncode for p in procs]
        if any(c not in (0, None) for c in codigos):
            print(f"[termico] passada {passada}: saídas {codigos}; "
                  f"ver {logs}/worker*.log", file=sys.stderr, flush=True)
        if pendentes(args.entrada, args.saida) >= se_faltam and all(
                c == 0 for c in codigos):
            estagnadas += 1
            if estagnadas >= 3:
                print("[termico] 3 passadas sem progresso: abortando em vez de "
                      "girar em falso. Veja os logs dos trabalhadores.",
                      file=sys.stderr, flush=True)
                return 2
        else:
            estagnadas = 0

    ESTADO.write_text(json.dumps({"atualizado": time.time(), "estado": "concluido",
                                  "passadas": passada, "pausas": pausas,
                                  "segundos_pausado": round(t_pausado),
                                  "landmarks": len(list(args.saida.glob("*.npy"))),
                                  "decorrido_s": round(time.time() - inicio)}),
                      encoding="utf-8")
    print("extração concluída", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

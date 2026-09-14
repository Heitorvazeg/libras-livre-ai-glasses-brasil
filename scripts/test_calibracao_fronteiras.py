"""Testes do script de calibração rápida (docs/prontidao-demo/01-segmentacao.md §1.10).

CSVs sintéticos no formato do gravador do app (GravadorSessao.kt / FormatoCsv).
"""
import csv
import io
import random
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import calibracao_fronteiras as cal

FIXAS = ["tipo", "ts_ms", "turno", "estado", "v_mao_esq", "v_mao_dir", "v_pulsos", "v_final",
         "v_suavizada", "pose", "mao_esq", "mao_dir", "nome", "detalhe"]
PONTOS = [f"p{p:02d}_{c}" for p in range(57) for c in "xyz"]


def escrever(caminho: Path, linhas: list[dict]) -> None:
    with caminho.open("w", encoding="utf-8", newline="") as arquivo:
        w = csv.DictWriter(arquivo, fieldnames=FIXAS + PONTOS)
        w.writeheader()
        for linha in linhas:
            w.writerow({**{k: "" for k in FIXAS + PONTOS}, **linha})


def frame(ts, v, estado="PARADO", maos=True):
    return {"tipo": "frame", "ts_ms": ts, "turno": 1, "estado": estado, "v_suavizada": v,
            "pose": "1", "mao_esq": "1" if maos else "0", "mao_dir": "0"}


class CalibracaoTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        rnd = random.Random(1)
        # R1: ruído entre 0 e 0,1 ombro/s, com uma linha de métrica no meio (deve ser ignorada).
        r1 = [frame(i * 42, rnd.uniform(0, 0.1)) for i in range(500)]
        r1.insert(10, {"tipo": "metrica", "ts_ms": 400, "nome": "fps_recebido", "detalhe": "24.0"})
        escrever(self.base / "R1-pessoa1.csv", r1)
        # R2: dois sinais de ~1 s a 1 ombro/s; no primeiro, uma pausa interna de ~85 ms e uma perda de
        # mãos de ~170 ms. A pausa ocupa 3 de 48 frames: menos de 10%, para o p10 medir o movimento.
        r2, ts = [], 0
        for n in range(2):
            for _ in range(20):
                r2.append(frame(ts, 0.05)); ts += 42
            for i in range(24):
                pausa = n == 0 and 8 <= i < 11
                sem_maos = n == 0 and 16 <= i < 21
                r2.append(frame(ts, 0.1 if pausa else 1.0, "SINALIZANDO", maos=not sem_maos)); ts += 42
        escrever(self.base / "R2-pessoa1.csv", r2)

    def test_piso_limiares_checagem_pausa_e_oclusao(self):
        r = cal.calibrar(cal.ler_frames(self.base / "R1-pessoa1.csv"), cal.ler_frames(self.base / "R2-pessoa1.csv"))
        self.assertEqual(500, r["frames_repouso"])
        self.assertAlmostEqual(0.095, r["piso_ruido"], delta=0.01)
        self.assertAlmostEqual(r["piso_ruido"] * 1.75, r["limiar_saida"])
        self.assertAlmostEqual(r["piso_ruido"] * 2.75, r["limiar_entrada"])
        self.assertEqual(2, r["sinais"])
        self.assertTrue(r["checagem_ok"])
        self.assertGreaterEqual(r["maior_pausa_interna_ms"], 80)
        self.assertLessEqual(r["maior_pausa_interna_ms"], 130)
        self.assertGreaterEqual(r["maior_oclusao_ms"], 160)

    def test_checagem_falha_quando_os_sinais_sao_lentos_demais(self):
        lento = [frame(i * 42, 0.12, "SINALIZANDO") for i in range(50)]
        escrever(self.base / "R2-lento.csv", lento)
        r = cal.calibrar(cal.ler_frames(self.base / "R1-pessoa1.csv"), cal.ler_frames(self.base / "R2-lento.csv"))
        self.assertFalse(r["checagem_ok"])

    def test_cli_separa_por_nome_e_imprime(self):
        saida = io.StringIO()
        with redirect_stdout(saida):
            codigo = cal.main([str(self.base / "R1-pessoa1.csv"), str(self.base / "R2-pessoa1.csv")])
        self.assertEqual(0, codigo)
        self.assertIn("limiar de saída sugerido", saida.getvalue())
        self.assertEqual(2, cal.main([str(self.base / "R1-pessoa1.csv")]))


if __name__ == "__main__":
    unittest.main()

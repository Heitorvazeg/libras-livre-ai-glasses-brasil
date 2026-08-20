"""Validação do pipeline da PoC sem câmera e sem participantes.

A coleta é o gargalo do plano (§9), não o código — mas entregar código não
testado significa descobrir os bugs no dia da avaliação, com os clipes reais já
gravados. Este self-test exercita todo o caminho com dados sintéticos:

  1. normalização (§5.2) — invariância a deslocamento, escala e resolução;
  2. marcação de mão ausente e descarte de frames sem pose confiável;
  3. convenção de nome dos clipes;
  4. DTW 1-NN (§5.3) — distância de sequência consigo mesma e vizinho correto;
  5. leave-one-signer-out completo (§5.4) sobre um dataset sintético, incluindo
     os artefatos de results/;
  6. controle negativo: dataset de puro ruído tem de reprovar no critério §6.3
     (garante que a métrica não está viciada para dar verde);
  7. leitura de vídeo + MediaPipe Holistic rodando de ponta a ponta.

O que ele NÃO valida: a qualidade da detecção de landmarks em pessoas reais
sinalizando — isso só os clipes de verdade respondem.

Uso:
    python src/selftest.py
"""
from __future__ import annotations

import shutil
import tempfile
import traceback
from pathlib import Path
from types import SimpleNamespace

import numpy as np

import evaluate
from config import load_config
from dtw_classifier import (DTWClassifier, carregar_dataset, dtw_dist,
                            escolher_backend, matriz_distancias, parse_nome)
from extract import extrair_video, frame_normalizado

CFG = load_config()


# ---------------------------------------------------------------- utilidades

def _lm(x, y, z=0.0, vis=1.0):
    return SimpleNamespace(x=x, y=y, z=z, visibility=vis)


def _fake_results(offset=(0.0, 0.0), escala=1.0, com_maos=(True, True), vis=1.0):
    """Monta um `results` do Holistic com pose + mãos em posições conhecidas."""
    ox, oy = offset
    pose = [_lm(0.5, 0.5, 0.0, vis) for _ in range(33)]
    # ombros a ±0.1 (antes da escala) do centro; demais pontos em posições fixas.
    pose[0] = _lm(ox + 0.5, oy + 0.5 - 0.15 * escala, 0.0, vis)            # nariz
    pose[11] = _lm(ox + 0.5 - 0.10 * escala, oy + 0.5, 0.0, vis)           # ombro esq
    pose[12] = _lm(ox + 0.5 + 0.10 * escala, oy + 0.5, 0.0, vis)           # ombro dir
    pose[13] = _lm(ox + 0.5 - 0.14 * escala, oy + 0.5 + 0.10 * escala, 0.0, vis)
    pose[14] = _lm(ox + 0.5 + 0.14 * escala, oy + 0.5 + 0.10 * escala, 0.0, vis)
    pose[15] = _lm(ox + 0.5 - 0.16 * escala, oy + 0.5 + 0.20 * escala, 0.0, vis)
    pose[16] = _lm(ox + 0.5 + 0.16 * escala, oy + 0.5 + 0.20 * escala, 0.0, vis)

    def mao(sinal):
        pontos = [_lm(ox + 0.5 + sinal * (0.16 + 0.004 * i) * escala,
                      oy + 0.5 + (0.20 + 0.004 * i) * escala, 0.0) for i in range(21)]
        return SimpleNamespace(landmark=pontos)

    return SimpleNamespace(
        pose_landmarks=SimpleNamespace(landmark=pose),
        left_hand_landmarks=mao(-1) if com_maos[0] else None,
        right_hand_landmarks=mao(+1) if com_maos[1] else None,
    )


def _clipe_sintetico(rng, base, direcao, freq, fase, n_frames):
    """Sequência (n_frames, num_pontos, dims) oscilando ao longo de `direcao`."""
    t = np.linspace(0, 1, n_frames)[:, None, None]
    onda = np.sin(2 * np.pi * freq * t + fase)
    ruido = rng.normal(0, 0.01, size=(n_frames, *base.shape))
    return (base[None] + onda * direcao[None] + ruido).astype(np.float32)


def _dataset_sintetico(destino: Path, pessoas=4, sinais=5, reps=3, seed=7, ruido_puro=False):
    """Grava .npy no formato de extract.py: sinais distintos, estilo por sinalizante."""
    rng = np.random.default_rng(seed)
    destino.mkdir(parents=True, exist_ok=True)
    vocab = CFG.vocabulario[:sinais]
    forma = (CFG.num_pontos, CFG.dims)

    protótipos = {s: (rng.normal(0, 1, forma), rng.normal(0, 0.6, forma),
                      1.0 + i * 0.7, i * 0.9) for i, s in enumerate(vocab)}
    for p in range(1, pessoas + 1):
        estilo_off = rng.normal(0, 0.05, forma)
        estilo_amp = rng.uniform(0.9, 1.1)
        for sinal in vocab:
            base, direcao, freq, fase = protótipos[sinal]
            for r in range(1, reps + 1):
                n = int(rng.integers(25, 45))
                if ruido_puro:
                    seq = rng.normal(0, 1, size=(n, *forma)).astype(np.float32)
                else:
                    seq = _clipe_sintetico(rng, base + estilo_off, direcao * estilo_amp,
                                           freq, fase + rng.normal(0, 0.05), n)
                np.save(destino / f"pessoa{p:02d}_sinal-{sinal}_rep{r:02d}.npy", seq)
    return vocab


def _cfg_temp(landmarks: Path, results: Path):
    """Cópia rasa do config apontando para diretórios temporários."""
    cfg = load_config()
    cfg.paths = dict(cfg.paths, landmarks=str(landmarks), results=str(results))
    return cfg


# -------------------------------------------------------------------- testes

def teste_normalizacao_invariante():
    """§5.2: deslocar a pessoa no quadro ou afastá-la da câmera não muda o vetor."""
    base = frame_normalizado(_fake_results(), 1280, 720, CFG)
    assert base is not None, "frame válido foi rejeitado"
    assert base.shape == (CFG.num_pontos, CFG.dims), f"forma inesperada: {base.shape}"

    deslocado = frame_normalizado(_fake_results(offset=(0.2, -0.1)), 1280, 720, CFG)
    assert np.allclose(base, deslocado, atol=1e-5), "normalização não é invariante a deslocamento"

    afastado = frame_normalizado(_fake_results(escala=0.6), 1280, 720, CFG)
    assert np.allclose(base, afastado, atol=1e-5), "normalização não é invariante à escala"

    outra_res = frame_normalizado(_fake_results(), 640, 360, CFG)
    assert np.allclose(base, outra_res, atol=1e-5), "normalização depende da resolução"

    # sem correção de proporção, x e y viriam em escalas diferentes num vídeo 16:9
    ombro_esq = base[list(CFG.pose_indices).index("ombro_esq")]
    assert np.isclose(np.linalg.norm(ombro_esq[:2]), 0.5, atol=1e-5), \
        "escala errada: o ombro deveria ficar a 0.5 da origem (metade da distância entre ombros)"


def teste_mao_ausente_e_frame_invalido():
    """Mão não detectada vira zeros; frame sem pose ou com ombro ocluído é descartado."""
    sem_esquerda = frame_normalizado(_fake_results(com_maos=(False, True)), 1280, 720, CFG)
    ini = len(CFG.pose_indices)
    assert np.all(sem_esquerda[ini:ini + 21] == 0), "mão ausente deveria ser zeros"
    assert np.any(sem_esquerda[ini + 21:] != 0), "mão presente não deveria ser zerada"

    deslocado = frame_normalizado(_fake_results(offset=(0.3, 0.2), com_maos=(False, True)),
                                  1280, 720, CFG)
    assert np.all(deslocado[ini:ini + 21] == 0), \
        "o marcador de ausência mudou com a posição da pessoa no quadro"

    sem_pose = SimpleNamespace(pose_landmarks=None, left_hand_landmarks=None,
                               right_hand_landmarks=None)
    assert frame_normalizado(sem_pose, 1280, 720, CFG) is None, "frame sem pose deveria ser descartado"

    ocluido = _fake_results(vis=0.1)
    assert frame_normalizado(ocluido, 1280, 720, CFG) is None, \
        "frame com ombro pouco visível deveria ser descartado"


def teste_convencao_de_nome():
    assert parse_nome("pessoa03_sinal-ajuda_rep02") == ("03", "ajuda", "02")
    assert parse_nome("pessoa03_sinal-marcar-consulta_rep11") == ("03", "marcar-consulta", "11")
    for ruim in ("pessoa03-ajuda-02", "sinal-ajuda_rep01", "pessoa03_sinal-ajuda_repXX"):
        try:
            parse_nome(ruim)
        except ValueError:
            continue
        raise AssertionError(f"nome inválido aceito: {ruim}")


def teste_dtw_basico():
    rng = np.random.default_rng(3)
    a = np.ascontiguousarray(rng.normal(size=(30, CFG.num_pontos * CFG.dims)), dtype=np.double)
    assert dtw_dist(a, a, CFG) < 1e-6, "distância de uma sequência para ela mesma deveria ser ~0"

    b = np.ascontiguousarray(a + rng.normal(0, 0.01, a.shape), dtype=np.double)
    c = np.ascontiguousarray(rng.normal(size=(34, a.shape[1])), dtype=np.double)
    assert dtw_dist(a, b, CFG) < dtw_dist(a, c, CFG), "DTW não separou sequência parecida de aleatória"

    # tolerância a variação de velocidade: repetir frames não pode explodir a distância
    lento = np.ascontiguousarray(np.repeat(a, 2, axis=0), dtype=np.double)
    assert dtw_dist(a, lento, CFG) < dtw_dist(a, c, CFG), "DTW não absorveu variação de ritmo"


def teste_1nn_e_matriz():
    with tempfile.TemporaryDirectory() as tmp:
        lm = Path(tmp) / "landmarks"
        _dataset_sintetico(lm, pessoas=3, sinais=4, reps=2, seed=11)
        cfg = _cfg_temp(lm, Path(tmp) / "results")
        clips = carregar_dataset(cfg=cfg)
        assert len(clips) == 3 * 4 * 2, f"esperava 24 clipes, li {len(clips)}"

        alvo = clips[0]
        clf = DTWClassifier([c for c in clips if c.pessoa != alvo.pessoa], cfg=cfg)
        assert clf.prever(alvo.seq).sinal == alvo.sinal, "1-NN errou um caso fácil"

        m = matriz_distancias(clips, cfg, verboso=False)
        assert m.shape == (len(clips), len(clips)), "matriz com forma errada"
        assert np.allclose(m, m.T, rtol=1e-6), "matriz de distâncias não é simétrica"
        assert np.all(np.isfinite(m)), "matriz com inf/NaN (poda vazando para o resultado)"
        assert np.allclose(np.diag(m), 0), "diagonal deveria ser zero"


def teste_loso_completo():
    """Roda evaluate de ponta a ponta e confere métrica e artefatos do §10."""
    tmp = Path(tempfile.mkdtemp())
    try:
        lm, res = tmp / "landmarks", tmp / "results"
        vocab = _dataset_sintetico(lm, pessoas=4, sinais=5, reps=3, seed=7)
        cfg = _cfg_temp(lm, res)
        clips = carregar_dataset(cfg=cfg)

        dist = matriz_distancias(clips, cfg, verboso=False)
        accs, predicoes = evaluate.leave_one_signer_out(clips, dist)
        acc = float(np.mean(accs))
        assert len(accs) == 4, "deveria haver uma rodada por pessoa"
        assert len(predicoes) == len(clips), "toda pessoa precisa ser testada uma vez"
        assert all(p.vizinho.split("_")[0] != f"pessoa{p.pessoa}" for p in predicoes), \
            "vazamento: o vizinho mais próximo veio da própria pessoa de teste"
        assert acc > 0.9, f"dataset sintético separável deu apenas {acc:.1%}"

        rotulos = sorted(vocab)
        cm = evaluate.matriz_confusao(predicoes, rotulos)
        assert cm.sum() == len(predicoes), "matriz de confusão não fecha com o nº de predições"
        assert cm.trace() == sum(p.acerto for p in predicoes), "diagonal ≠ acertos"

        tem_plot = evaluate.plotar_confusao(cm, rotulos, res / "confusion_matrix.png")
        evaluate.escrever_csv(predicoes, res / "predicoes.csv")
        evaluate.escrever_relatorio(res / "relatorio.md", cfg, clips, accs, predicoes, cm,
                                    rotulos, evaluate.conferir_coleta(clips, cfg), 0.0, tem_plot)
        for nome in ("confusion_matrix.png", "predicoes.csv", "relatorio.md"):
            assert (res / nome).exists() and (res / nome).stat().st_size > 0, f"{nome} não foi gerado"
        texto = (res / "relatorio.md").read_text(encoding="utf-8")
        assert f"{acc:.1%}" in texto, "o relatório não traz a acurácia calculada"
        assert "SINAL VERDE" in texto, "veredito ausente no relatório"
        print(f"        (acurácia sintética = {acc:.1%}, {len(clips)} clipes)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def teste_controle_negativo():
    """Ruído puro tem de reprovar — se desse verde, a métrica estaria viciada."""
    with tempfile.TemporaryDirectory() as tmp:
        lm = Path(tmp) / "landmarks"
        _dataset_sintetico(lm, pessoas=3, sinais=5, reps=3, seed=5, ruido_puro=True)
        cfg = _cfg_temp(lm, Path(tmp) / "results")
        clips = carregar_dataset(cfg=cfg)
        accs, _ = evaluate.leave_one_signer_out(clips, matriz_distancias(clips, cfg, verboso=False))
        acc = float(np.mean(accs))
        assert acc < 0.6, f"ruído puro atingiu {acc:.1%} — a avaliação está otimista demais"
        assert "VERMELHO" in evaluate.veredito(acc, cfg), "veredito não reprovou o controle negativo"
        print(f"        (ruído puro = {acc:.1%}, chance = 20%)")


def teste_loso_exige_dois_sinalizantes():
    with tempfile.TemporaryDirectory() as tmp:
        lm = Path(tmp) / "landmarks"
        _dataset_sintetico(lm, pessoas=1, sinais=3, reps=2, seed=1)
        cfg = _cfg_temp(lm, Path(tmp) / "results")
        clips = carregar_dataset(cfg=cfg)
        try:
            evaluate.leave_one_signer_out(clips, matriz_distancias(clips, cfg, verboso=False))
        except SystemExit:
            pass
        else:
            raise AssertionError("LOSO aceitou uma única pessoa — o resultado não teria sentido")
        avisos = " ".join(evaluate.conferir_coleta(clips, cfg))
        assert "participante" in avisos, "conferência de coleta não avisou sobre poucos participantes"


def teste_video_e_holistic():
    """Grava um .mp4, lê de volta e roda o Holistic — valida OpenCV + MediaPipe na máquina."""
    import cv2
    import mediapipe as mp

    with tempfile.TemporaryDirectory() as tmp:
        caminho = Path(tmp) / "pessoa99_sinal-teste_rep01.mp4"
        escritor = cv2.VideoWriter(str(caminho), cv2.VideoWriter_fourcc(*"mp4v"), 15.0, (320, 240))
        assert escritor.isOpened(), "OpenCV não conseguiu abrir o codec mp4v usado por record.py"
        rng = np.random.default_rng(0)
        for _ in range(8):
            escritor.write(rng.integers(0, 255, (240, 320, 3), dtype=np.uint8))
        escritor.release()
        assert caminho.stat().st_size > 0, "vídeo gravado ficou vazio"

        with mp.solutions.holistic.Holistic(**CFG.holistic) as holistic:
            seq, descartados = extrair_video(caminho, holistic, CFG)
        # ruído não contém pessoa: o esperado é descartar tudo, sem quebrar.
        assert seq.shape[0] == 0 and descartados == 8, \
            f"esperava 8 frames descartados em vídeo sem pessoa, veio {seq.shape[0]}/{descartados}"
        assert seq.shape[1:] == (CFG.num_pontos, CFG.dims), "forma do array vazio inconsistente"


TESTES = [
    ("normalização invariante (§5.2)", teste_normalizacao_invariante),
    ("mão ausente / frame inválido", teste_mao_ausente_e_frame_invalido),
    ("convenção de nome dos clipes", teste_convencao_de_nome),
    ("DTW básico (§5.3)", teste_dtw_basico),
    ("1-NN e matriz de distâncias", teste_1nn_e_matriz),
    ("leave-one-signer-out completo (§5.4)", teste_loso_completo),
    ("controle negativo (ruído reprova)", teste_controle_negativo),
    ("LOSO exige ≥2 sinalizantes", teste_loso_exige_dois_sinalizantes),
    ("vídeo + MediaPipe Holistic", teste_video_e_holistic),
]


def main() -> int:
    print(f"[selftest] backend DTW: {escolher_backend(CFG)} | "
          f"{CFG.num_pontos} pontos × {CFG.dims} dims | {len(CFG.vocabulario)} sinais no vocabulário\n")
    falhas = 0
    for nome, fn in TESTES:
        try:
            fn()
        except Exception:
            falhas += 1
            print(f"  ✗ {nome}")
            print("    " + traceback.format_exc().replace("\n", "\n    ").rstrip())
        else:
            print(f"  ✓ {nome}")

    print()
    if falhas:
        print(f"[selftest] {falhas} de {len(TESTES)} testes falharam.")
        return 1
    print(f"[selftest] {len(TESTES)}/{len(TESTES)} OK — pipeline pronto para receber os clipes reais.")
    print("[selftest] o que só dado real valida: qualidade da detecção de landmarks em "
          "pessoas sinalizando e a acurácia de verdade (§6.3).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

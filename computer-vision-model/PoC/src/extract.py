"""§5.2 — Extração de landmarks com MediaPipe Holistic + normalização.

Para cada vídeo em data/raw/, roda o Holistic frame a frame e monta, por frame,
um vetor de pontos na ordem:

    [ pose_subset (config.pose_indices) | mão esquerda (21) | mão direita (21) ]

com (x, y, z) por ponto — 49 pontos × 3 = 147 valores por frame na configuração
padrão. Pose_subset = nariz, ombros, cotovelos, pulsos: contexto de tronco sem
inflar a dimensionalidade.

NORMALIZAÇÃO (o passo que mais afeta o resultado, §5.2):
  1. converte as coordenadas normalizadas do MediaPipe para pixels (x·W, y·H,
     z·W) — sem isso, x e y estão em escalas diferentes quando o vídeo não é
     quadrado, e clipes gravados em resoluções/proporções diferentes não são
     comparáveis entre si;
  2. origem = ponto médio entre os ombros;
  3. escala = distância entre os ombros (medida em x,y — o z de pose é ruidoso
     demais para servir de escala);
  4. cada ponto vira (ponto − origem) / escala.

Isso torna os landmarks invariantes à distância da pessoa até a câmera, à
posição dela no quadro e à resolução da gravação. Frames sem pose confiável
(ombros ausentes ou com visibilidade baixa) são descartados — sem referência
estável não há como normalizar.

Mão não detectada no frame vira zeros DEPOIS da normalização (o zero é a própria
origem, o ponto médio dos ombros): um marcador de ausência estável, que não
depende de onde a pessoa estava no quadro.

Ressalva sobre z: o z das mãos é relativo ao punho e o z de pose é relativo ao
quadril — não estão no mesmo referencial. Mantido por ser o que o plano pede,
mas é o primeiro parâmetro a testar desligando (normalizacao.usar_z: false) se a
acurácia cair na zona amarela do §6.3.

Saída: data/landmarks/<mesmo-nome-base>.npy, float32 (num_frames, num_pontos, dims).

Uso:
    python src/extract.py                    # processa tudo que ainda não tem .npy
    python src/extract.py --overwrite        # reprocessa mesmo o que já existe
    python src/extract.py --descartar-video  # apaga o .mp4 após extrair (§4.4)
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from config import load_config

# cv2 e mediapipe são importados SOB DEMANDA (dentro das funções que os usam), não
# no topo: assim `from extract import frame_normalizado` — que é numpy puro — funciona
# sem a stack pesada de visão instalada. É o que permite a API de validação
# (api/server.py) reusar a normalização sem depender de mediapipe/opencv, já que a
# extração de landmarks acontece no app, não no servidor.
N_HAND = 21


def _hand_px(hand_landmarks, w: int, h: int) -> np.ndarray | None:
    """21×3 pontos da mão em pixels, ou None se a mão não foi detectada."""
    if hand_landmarks is None:
        return None
    return np.array([[lm.x * w, lm.y * h, lm.z * w] for lm in hand_landmarks.landmark],
                    dtype=np.float64)


def frame_normalizado(results, w: int, h: int, cfg, dims: int | None = None) -> np.ndarray | None:
    """Pontos do frame normalizados (num_pontos, dims), ou None se não dá para normalizar.

    `dims` sobrescreve cfg.dims. A extração passa 3 de propósito: o .npy é o
    artefato caro (horas de MediaPipe), então guarda tudo que foi calculado e
    deixa o descarte do z para a LEITURA (carregar_dataset corta conforme
    normalizacao.usar_z). Assim, testar o z de novo não exige reextrair.
    """
    pose = results.pose_landmarks
    if pose is None:
        return None  # sem tronco não há referência estável de normalização

    lms = pose.landmark
    ref_a, ref_b = cfg.normalizacao["ref_a"], cfg.normalizacao["ref_b"]
    min_vis = cfg.normalizacao.get("min_visibilidade", 0.0)
    if min(lms[ref_a].visibility, lms[ref_b].visibility) < min_vis:
        return None  # ombro ocluído/fora do quadro: a escala ficaria instável

    a = np.array([lms[ref_a].x * w, lms[ref_a].y * h, lms[ref_a].z * w])
    b = np.array([lms[ref_b].x * w, lms[ref_b].y * h, lms[ref_b].z * w])
    origem = (a + b) / 2.0
    escala = float(np.linalg.norm(a[:2] - b[:2]))  # distância entre ombros, em pixels
    if escala < 1e-6:
        return None  # ombros colados/instáveis: descarta o frame

    pose_px = np.array([[lms[i].x * w, lms[i].y * h, lms[i].z * w] for i in cfg.pose_subset],
                       dtype=np.float64)
    maos = [_hand_px(results.left_hand_landmarks, w, h),
            _hand_px(results.right_hand_landmarks, w, h)]

    blocos = [(pose_px - origem) / escala]
    for mao in maos:
        if mao is None:
            blocos.append(np.zeros((N_HAND, 3)))  # ausência = origem (mediana dos ombros)
        else:
            blocos.append((mao - origem) / escala)

    pontos = np.concatenate(blocos, axis=0)
    return pontos[:, : (dims if dims is not None else cfg.dims)].astype(np.float32)


def extrair_video(caminho: Path, holistic, cfg) -> tuple[np.ndarray, int]:
    """Devolve (sequência normalizada, nº de frames descartados) de um vídeo."""
    import cv2  # import sob demanda: só a extração de vídeo precisa de opencv
    cap = cv2.VideoCapture(str(caminho))
    if not cap.isOpened():
        raise RuntimeError(f"não consegui abrir o vídeo {caminho}")
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    frames: list[np.ndarray] = []
    descartados = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if not w or not h:  # algumas capturas não reportam as dimensões no header
            h, w = frame.shape[:2]
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        rgb.flags.writeable = False
        vec = frame_normalizado(holistic.process(rgb), w, h, cfg, dims=3)
        if vec is None:
            descartados += 1
        else:
            frames.append(vec)
    cap.release()

    if not frames:
        return np.empty((0, cfg.num_pontos, 3), dtype=np.float32), descartados
    return np.stack(frames).astype(np.float32), descartados


def main() -> None:
    ap = argparse.ArgumentParser(description="Extração Holistic + normalização (§5.2).")
    ap.add_argument("--overwrite", action="store_true", help="reprocessa .npy já existentes")
    ap.add_argument("--descartar-video", action="store_true",
                    help="apaga o vídeo bruto após extrair os landmarks (§4.4)")
    ap.add_argument("--entrada", metavar="DIR",
                    help="diretório de vídeos (padrão: paths.raw_videos do config)")
    ap.add_argument("--saida", metavar="DIR",
                    help="diretório dos .npy (padrão: paths.landmarks do config). "
                         "Use um par --entrada/--saida próprio para corpora que NÃO "
                         "entram na avaliação, como o de pré-treino.")
    ap.add_argument("--particao", metavar="i/N",
                    help="processa só a fatia i de N (1-indexado), para rodar N "
                         "processos em paralelo — ex.: --particao 1/4")
    args = ap.parse_args()

    fatia, n_fatias = 1, 1
    if args.particao:
        try:
            fatia, n_fatias = (int(x) for x in args.particao.split("/"))
        except ValueError:
            raise SystemExit(f"--particao: use o formato i/N, ex.: 1/4 (veio {args.particao!r})")
        if not 1 <= fatia <= n_fatias:
            raise SystemExit(f"--particao: i precisa estar entre 1 e N (veio {args.particao})")

    try:
        import mediapipe as mp
    except ImportError as e:
        raise SystemExit("mediapipe não instalado — rode: pip install -r requirements.txt") from e

    cfg = load_config()
    raiz = Path(__file__).resolve().parent.parent
    raw_dir = raiz / args.entrada if args.entrada else cfg.path("raw_videos")
    lm_dir = raiz / args.saida if args.saida else cfg.path("landmarks")
    lm_dir.mkdir(parents=True, exist_ok=True)

    videos = sorted(raw_dir.glob("*.mp4"))
    if not videos:
        print(f"[extract] nenhum vídeo em {raw_dir} — traga clipes com "
              "../datasets/ingest.py ou grave com record.py primeiro.")
        return

    total_geral = len(videos)
    if n_fatias > 1:
        videos = videos[fatia - 1::n_fatias]  # intercalado: cada fatia mistura pessoas e sinais
        print(f"[extract] fatia {fatia}/{n_fatias}: {len(videos)} de {total_geral} vídeo(s)")

    print(f"[extract] {len(videos)} vídeo(s) | {cfg.num_pontos} pontos × {cfg.dims} dims por frame")
    extraidos = 0
    falhas: list[str] = []
    with mp.solutions.holistic.Holistic(**cfg.holistic) as holistic:
        for v in videos:
            destino = lm_dir / (v.stem + ".npy")
            if destino.exists() and not args.overwrite:
                print(f"[extract] pulando {v.name} (já extraído)")
                continue

            try:
                seq, descartados = extrair_video(v, holistic, cfg)
            except Exception as e:
                # Um vídeo ilegível (corrompido, download interrompido, arquivo
                # removido durante a execução) não pode derrubar o lote inteiro:
                # numa extração de milhares de clipes, isso significaria perder
                # horas de trabalho por causa de um arquivo.
                falhas.append(v.name)
                print(f"[extract] ⚠ {v.name}: falhou ({type(e).__name__}: {e}) — seguindo")
                continue
            total = seq.shape[0] + descartados
            if seq.shape[0] == 0:
                print(f"[extract] ⚠ {v.name}: nenhum frame com pose detectada "
                      "— verifique enquadramento/iluminação (checklist de setup)")
                continue

            np.save(destino, seq)
            extraidos += 1
            aviso = ""
            if total and descartados / total > 0.3:
                aviso = f"  ⚠ {descartados/total:.0%} dos frames descartados (pose instável)"
            print(f"[extract] {v.name} -> {destino.name}  ({seq.shape[0]} frames){aviso}")

            if args.descartar_video:
                v.unlink()
                print(f"[extract] vídeo bruto apagado: {v.name} (§4.4 — só os landmarks ficam)")

    print(f"[extract] concluído: {extraidos} clipe(s) extraído(s) em {lm_dir}")
    if falhas:
        print(f"[extract] ⚠ {len(falhas)} vídeo(s) falharam e foram pulados: "
              f"{', '.join(falhas[:10])}{' ...' if len(falhas) > 10 else ''}")


if __name__ == "__main__":
    main()

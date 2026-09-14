"""Piloto privado Tasks × Holistic nos mesmos frames; não treina nem altera originais.

Usa PTS de apresentação do vídeo via ffprobe (não timestamps da câmera dos óculos).
Preserva frames inválidos com máscara explícita; nunca comprime a linha do tempo
no artefato. Reinicia os dois pipelines por clipe para evitar estado entre pessoas.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import json
from pathlib import Path
import platform
import subprocess
import sys
import time
from types import SimpleNamespace

import numpy as np

from preparar_piloto_tasks import RAIZ, hash_arquivo

sys.path.insert(0, str(RAIZ / "computer-vision-model/PoC/src"))
from config import load_config
from extract import frame_normalizado

POSE_SUBSET = [0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24]
URLS = {
    "pose_lite": "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
    "hand": "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task",
}


def selecionar_maos(pose, maos, largura, altura):
    """AtribuicaoMaos.kt: distância em pixels, filtro estrito, pares estáveis/gulosos."""
    escala_px = np.array([largura, altura], dtype=np.float32)
    xy = np.array([[p.x, p.y] for p in pose], dtype=np.float32) * escala_px
    ombros = float(np.linalg.norm(xy[11] - xy[12]))
    punhos = [np.array([m[0].x, m[0].y], dtype=np.float32) * escala_px for m in maos]
    aceitas = [i for i, p in enumerate(punhos)
               if min(np.linalg.norm(p - xy[15]), np.linalg.norm(p - xy[16])) < 0.5 * ombros]
    pares = sorted([(float(np.sum((punhos[i] - xy[p]) ** 2)), i, lado)
                    for i in aceitas for lado, p in enumerate((15, 16))], key=lambda x: x[0])
    lados, usadas = [None, None], set()
    for _, i, lado in pares:
        if i not in usadas and lados[lado] is None:
            lados[lado] = i
            usadas.add(i)
    return lados, aceitas


def escolher_pose(poses, largura, altura):
    validas = [p for p in poses if len(p) == 33]
    if not validas:
        return None
    def distancia(p):
        return ((p[11].x - p[12].x) * largura) ** 2 + ((p[11].y - p[12].y) * altura) ** 2
    return max(validas, key=distancia)  # primeiro índice em empate, como Kotlin


def resultado(pose, esq=None, direita=None):
    def bloco(p):
        return None if p is None else SimpleNamespace(landmark=p)
    return SimpleNamespace(pose_landmarks=bloco(pose), left_hand_landmarks=bloco(esq),
                           right_hand_landmarks=bloco(direita))


def timestamps_video(video):
    bruto = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
                            "-show_frames", "-show_entries", "frame=best_effort_timestamp_time",
                            "-of", "json", str(video)], check=True, capture_output=True, text=True)
    return conferir_timestamps([f.get("best_effort_timestamp_time")
                               for f in json.loads(bruto.stdout)["frames"]])


def conferir_timestamps(valores):
    if not valores or any(v is None for v in valores):
        raise ValueError("PTS ausentes; não substituir silenciosamente por índice/fps")
    pts = np.asarray(valores, dtype=np.float64)
    if not np.isfinite(pts).all() or np.any(np.diff(pts) <= 0):
        raise ValueError("PTS inválidos/não crescentes")
    ts_ms = np.rint((pts - pts[0]) * 1000).astype(np.int64)
    if np.any(np.diff(ts_ms) <= 0):
        raise ValueError("PTS colidem em milissegundos; não alterar timestamps silenciosamente")
    return pts, ts_ms


def estatisticas(dados):
    n = len(dados["ts_ms"])
    out = {"frames": n, "duracao_pts_s": float(dados["pts_s"][-1] - dados["pts_s"][0])}
    for modo in ("tasks", "holistic"):
        validos = dados[modo + "_valido"]
        maos = dados[modo + "_maos"] & validos[:, None]
        out[modo] = {"frames_validos": int(validos.sum()),
                     "frames_mao_esq": int(maos[:, 0].sum()), "frames_mao_dir": int(maos[:, 1].sum())}
    comum = dados["tasks_valido"] & dados["holistic_valido"]
    out["frames_pose_comum"] = int(comum.sum())
    out["diferencas_em_ombros"] = {}
    for nome, sl, lado in (("pose", slice(0, 15), None), ("mao_esq", slice(15, 36), 0),
                           ("mao_dir", slice(36, 57), 1)):
        mascara = comum.copy()
        if lado is not None:
            mascara &= dados["tasks_maos"][:, lado] & dados["holistic_maos"][:, lado]
        delta = dados["tasks"][mascara, sl] - dados["holistic"][mascara, sl]
        xy = np.linalg.norm(delta[..., :2], axis=-1).ravel()
        z = np.abs(delta[..., 2]).ravel()
        out["diferencas_em_ombros"][nome] = {
            "frames_comuns": int(mascara.sum()), "pontos_comuns": len(xy),
            "xy_mediana": float(np.median(xy)) if len(xy) else None,
            "xy_p95": float(np.quantile(xy, .95)) if len(xy) else None,
            "z_abs_mediana": float(np.median(z)) if len(z) else None,
        }
    return out


def extrair_par(video, modelos, cfg):
    import cv2
    import mediapipe as mp
    pts, ts_ms = timestamps_video(video)
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise ValueError(f"vídeo ilegível: {video}")
    visao = mp.tasks.vision
    saida = {"pts_s": pts, "ts_ms": ts_ms, "frame_index": np.arange(len(pts))}
    for modo in ("tasks", "holistic"):
        saida[modo] = np.zeros((len(pts), 57, 3), dtype=np.float32)
        saida[modo + "_valido"] = np.zeros(len(pts), dtype=bool)
        saida[modo + "_maos"] = np.zeros((len(pts), 2), dtype=bool)
        saida[modo + "_processamento_ms"] = np.zeros(len(pts), dtype=np.float64)
    saida["tasks_contagens"] = np.zeros((len(pts), 3), dtype=np.int32)  # poses, mãos, aceitas
    saida["tasks_lados"] = np.full((len(pts), 2), -1, dtype=np.int32)
    try:
        with ExitStack() as stack:
            def base(nome):
                return mp.tasks.BaseOptions(model_asset_path=str(modelos[nome]),
                                            delegate=mp.tasks.BaseOptions.Delegate.CPU)
            pose = stack.enter_context(visao.PoseLandmarker.create_from_options(visao.PoseLandmarkerOptions(
                base_options=base("pose_lite"), running_mode=visao.RunningMode.VIDEO, num_poses=2,
                min_pose_detection_confidence=.5, min_pose_presence_confidence=.5, min_tracking_confidence=.5)))
            hand = stack.enter_context(visao.HandLandmarker.create_from_options(visao.HandLandmarkerOptions(
                base_options=base("hand"), running_mode=visao.RunningMode.VIDEO, num_hands=4,
                min_hand_detection_confidence=.5, min_hand_presence_confidence=.5, min_tracking_confidence=.5)))
            holistic = stack.enter_context(mp.solutions.holistic.Holistic(**cfg.holistic))
            for i, ts in enumerate(ts_ms):
                ok, bgr = cap.read()
                if not ok:
                    raise ValueError(f"OpenCV decodificou {i}, ffprobe informou {len(pts)} frames")
                h, w = bgr.shape[:2]
                rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                t = time.perf_counter()
                poses = pose.detect_for_video(image, int(ts)).pose_landmarks
                escolhida = escolher_pose(poses, w, h)
                task_result = resultado(None)
                saida["tasks_contagens"][i, 0] = len(poses)
                if escolhida is not None:
                    maos = [m for m in hand.detect_for_video(image, int(ts)).hand_landmarks if len(m) == 21]
                    lados, aceitas = selecionar_maos(escolhida, maos, w, h)
                    saida["tasks_contagens"][i, 1:] = [len(maos), len(aceitas)]
                    saida["tasks_lados"][i] = [-1 if m is None else m for m in lados]
                    task_result = resultado(escolhida, *[None if m is None else maos[m] for m in lados])
                saida["tasks_processamento_ms"][i] = (time.perf_counter() - t) * 1000
                t = time.perf_counter()
                hol_result = holistic.process(rgb)
                saida["holistic_processamento_ms"][i] = (time.perf_counter() - t) * 1000
                for modo, r in (("tasks", task_result), ("holistic", hol_result)):
                    vec = frame_normalizado(r, w, h, cfg, dims=3)
                    if vec is not None:
                        if vec.shape != (57, 3) or not np.isfinite(vec).all():
                            raise ValueError(f"landmarks inválidos: {modo}, frame {i}")
                        saida[modo][i] = vec
                        saida[modo + "_valido"][i] = True
                    saida[modo + "_maos"][i] = [r.left_hand_landmarks is not None, r.right_hand_landmarks is not None]
            if cap.read()[0]:
                raise ValueError("OpenCV decodificou mais frames que ffprobe")
    finally:
        cap.release()
    return saida


def destino_privado(destino):
    destino = destino.resolve()
    if destino.is_relative_to(RAIZ) and not destino.is_relative_to(RAIZ / "experimentos-privados"):
        raise ValueError("saída deve ser privada: experimentos-privados/ ou fora do repositório")
    if destino.exists():
        raise ValueError("saída já existe; escolher diretório novo (sem sobrescrita/retomada implícita)")
    return destino


def validar_inventario(inventario, limite=0):
    if inventario.get("schema") != 1:
        raise ValueError("schema de inventário desconhecido")
    modelos = {}
    for nome in URLS:
        m = inventario["modelos"][nome]
        p = Path(m["arquivo"])
        if not p.is_file() or hash_arquivo(p) != m["sha256"]:
            raise ValueError(f"modelo ausente/alterado: {nome}")
        modelos[nome] = p
    pares = inventario["pares"][:limite or None]
    if not pares or len({p["id"] for p in pares}) != len(pares):
        raise ValueError("pares vazios/duplicados")
    for p in pares:
        if Path(p["id"]).name != p["id"] or not p["id"].startswith(f"pessoa{inventario['pessoa_teste']}_"):
            raise ValueError("ID inválido ou fora da pessoa selecionada")
        for tipo in ("video", "landmark"):
            arquivo = Path(p[tipo])
            if arquivo.stem != Path(p["id"]).stem or hash_arquivo(arquivo) != p[tipo + "_sha256"]:
                raise ValueError(f"{tipo} difere do inventário: {p['id']}")
    return modelos, pares


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--inventario", type=Path, required=True)
    ap.add_argument("--saida", type=Path, required=True)
    ap.add_argument("--max-clipes", type=int, default=0, help="0=todos; primeiros N na ordem do inventário")
    a = ap.parse_args()
    if a.max_clipes < 0:
        ap.error("--max-clipes deve ser >= 0")
    try:
        destino = destino_privado(a.saida)
        inv = json.loads(a.inventario.read_text())
        modelos, pares = validar_inventario(inv, a.max_clipes)
        cfg = load_config()
        if cfg.pose_subset != POSE_SUBSET or cfg.num_pontos != 57:
            raise ValueError("config não corresponde ao layout 57 pontos do app")
    except (ValueError, KeyError, OSError) as e:
        ap.error(str(e))
    import cv2
    import mediapipe as mp
    destino.mkdir(parents=True)
    inicio = time.perf_counter()
    fontes = [Path(__file__), Path(__file__).with_name("preparar_piloto_tasks.py"),
              RAIZ / "computer-vision-model/PoC/src/extract.py", RAIZ / "computer-vision-model/PoC/src/config.py"]
    doc = {"schema": 1, "pessoa": inv["pessoa_teste"], "inventario_sha256": hash_arquivo(a.inventario),
           "codigo": {str(p.relative_to(RAIZ)): hash_arquivo(p) for p in fontes},
           "python": platform.python_version(), "mediapipe": mp.__version__, "opencv": cv2.__version__,
           "numpy": np.__version__, "config": cfg.raw,
           "ffprobe": subprocess.check_output(["ffprobe", "-version"], text=True).splitlines()[0],
           "modelos": inv["modelos"], "urls_modelos_configuradas_app": URLS,
           "contrato": {"pose": 2, "maos": 4, "confiancas": .5, "raio_pulso": .5, "delegate": "CPU",
                        "modo": "VIDEO", "estado": "reset_por_clipe", "rotacao_solicitada": 0,
                        "tempo": "PTS best_effort ffprobe; origem subtraída, arredondado para ms",
                        "invalidos": "zeros com mascara explicita; nao imputados"},
           "limitacoes": ["PTS do arquivo não são captura/uptime do app", "RGB OpenCV não testa conversão YUV Android",
                          "Holistic novo reinicia por clipe; legado reutilizava instância entre vídeos",
                          "runtime Python não prova igualdade ao runtime Android",
                          "comparação geométrica não é acurácia/calibração; z não é profundidade métrica"],
           "clipes": [], "falhas": [], "extracao_completa": False, "avaliacao_classificador": False}
    def gravar():
        temp = destino / "extracao.json.tmp"
        temp.write_text(json.dumps(doc, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
        temp.replace(destino / "extracao.json")
    gravar()
    for i, par in enumerate(pares, 1):
        try:
            for nome, modelo in modelos.items():
                if hash_arquivo(modelo) != inv["modelos"][nome]["sha256"]:
                    raise ValueError(f"modelo alterado durante o piloto: {nome}")
            dados = extrair_par(Path(par["video"]), modelos, cfg)
            # Verificar que fontes não mudaram durante o processamento.
            for tipo in ("video", "landmark"):
                if hash_arquivo(Path(par[tipo])) != par[tipo + "_sha256"]:
                    raise ValueError("fonte alterada durante a extração")
            nome = Path(par["id"]).with_suffix(".npz").name
            np.savez_compressed(destino / nome, **dados)
            info = estatisticas(dados)
            doc["clipes"].append({**par, "artefato": nome, "sha256": hash_arquivo(destino / nome), **info})
            print(f"[{i}/{len(pares)}] {par['id']}: {info['frames']} frames; "
                  f"Tasks {info['tasks']['frames_validos']}, Holistic {info['holistic']['frames_validos']}", flush=True)
        except Exception as e:
            doc["falhas"].append({"id": par["id"], "erro": f"{type(e).__name__}: {e}"})
            print(f"[falha] {par['id']}: {e}", flush=True)
        gravar()
    doc["extracao_completa"] = not doc["falhas"] and len(doc["clipes"]) == len(pares)
    doc["segundos"] = time.perf_counter() - inicio
    doc["total_frames"] = sum(c["frames"] for c in doc["clipes"])
    doc["cobertura"] = {m: {k: sum(c[m][k] for c in doc["clipes"])
                            for k in ("frames_validos", "frames_mao_esq", "frames_mao_dir")}
                        for m in ("tasks", "holistic")}
    gravar()
    print(f"Concluído: {len(doc['clipes'])}/{len(pares)} clipes; {doc['total_frames']} frames; {doc['segundos']:.1f}s")
    if not doc["extracao_completa"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
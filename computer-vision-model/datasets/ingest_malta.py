"""Baixa o MALTA-LIBRAS — a base que traz PESSOAS NOVAS por sinal.

POR QUE ESTA BASE. As nossas duas fontes de Libras são fundas em repetição e
rasas em gente: o MINDS tem 8 pessoas para 20 sinais, e a V-LIBRASIL tem 1.353
palavras com sempre os MESMOS 3 articuladores. O produto é institucional — vê uma
pessoa diferente a cada atendimento — então diversidade de sinalizante é a métrica
que decide, e é a que nos falta.

O MALTA-LIBRAS não é uma base: é um AGREGADOR de dicionários de Libras, cada um
com seu apresentador. Medido no `MALTA_LIBRAS_original.csv`: 4.367 rótulos e ~60
atores, com 332 rótulos em 5 atores ou mais. Para comparação, nenhuma palavra da
V-LIBRASIL passa de 3.

O QUE ISSO RESOLVE, concretamente (atores por palavra):

    precisar 7 · nao 11 · voce 4 · quanto 4 · eu 3      <- as funcionais que faltavam
    nome 7 · dor 6 · documento 5 · medico 5 · febre 5   <- atendimento
    conhecer 8 · banheiro 7 · bala 7 · cinco 6          <- reforço aos sinais do MINDS

FONTES INCLUÍDAS, E A QUE FICOU DE FORA. Baixamos Acessibilidade Brasil, UFSC
SignBank, UFV e USP — 9.886 vídeos. **Spread the Sign fica de fora por decisão
explícita**: é a única fonte cujo termo não é silêncio, e sim proibição ("only for
personal use", redistribuição vedada). As demais não publicaram termo localizável,
e o uso aqui é de pesquisa; um "não" escrito é outra coisa. O custo do corte é
pequeno — `precisar` cai de 7 atores para 6, `nao` de 11 para 10 — e nenhuma
palavra importante se perde. V-LIBRASIL também fica de fora: já está em disco.

IDENTIDADE DO SINALIZANTE. Sem ID de pessoa não existe leave-one-signer-out, e o
número de generalização deixa de significar o que diz. O `actor` está no
`MALTA_LIBRAS_original.csv`, não nos CSVs de link, então juntamos os dois pelo
nome do arquivo (normalizado: sem prefixo numérico, sem acento, sem extensão).
Quando o join falha, o fallback é a FONTE como identidade — conservador de
propósito: tratar 6 pessoas como 1 subestima a diversidade, tratar 1 como 6
inventaria independência que não existe. Erramos para o lado que não mente.

Uso:
    python ingest_malta.py --listar              # o que existe, sem baixar
    python ingest_malta.py --fonte ufv           # uma fonte (sonda de velocidade)
    python ingest_malta.py                       # todas as incluídas
"""
from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import os
import re
import sys
import time
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote, urlparse

import requests

import proveniencia as pv

AQUI = Path(__file__).resolve().parent
DESTINO = AQUI.parent / "PoC" / "data" / "raw-malta"
PREFIXO_PESSOA = "T"  # distingue de M (MINDS), V (V-LIBRASIL) e W (WLASL)

# Repositório do toolkit; só os CSVs nos interessam (o código deles é MIT, os
# vídeos são de cada dicionário — ver docs/investigacao-expansao-dataset.md).
REPO = "https://github.com/Malta-Lab/ISLR_LIBRAS.git"

FONTES = {
    "acessibilidade": "links_videos_acessibilidade_brasil.csv",
    "ufsc": "links_videos_ufsc_signbank.csv",
    "ufv": "links_videos_ufv.csv",
    "usp": "links_videos_usp.csv",
}
# Excluídas, com o motivo — para ninguém "consertar" isso por engano depois.
EXCLUIDAS = {
    "spreadthesign": "proibição explícita de redistribuição ('only for personal use')",
    "vlibrasil": "já está em disco em data/raw-pretreino",
}

# Cortesia com servidor de universidade: uma requisição por vez por host, com
# pausa entre elas. Não é otimização — é não derrubar o serviço de quem está
# cedendo o dado. Se o servidor recusar, a resposta certa é esperar mais, nunca
# insistir mais forte.
PAUSA_S = 0.35
TENTATIVAS = 3
ESPERA_ERRO_S = 5.0
TIMEOUT_S = 60


def slug(palavra: str) -> str:
    s = unicodedata.normalize("NFKD", str(palavra)).encode("ascii", "ignore").decode()
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s).strip("-").lower()
    return re.sub(r"-{2,}", "-", s)


# Cada CSV de links corresponde a dicionários específicos do CSV de atores.
# Medido por sobreposição de nomes de arquivo, não presumido:
#   acessibilidade -> Acessibilidades3 (Acessibilidades2 é .wmv e NÃO está nos links)
#   ufsc           -> UFSC_V2 (1.000) e UFSC (964) — é daqui que vem a diversidade
#   ufv/usp        -> homônimos
DICIONARIOS = {
    "acessibilidade": {"Acessibilidades3"},
    "ufsc": {"UFSC", "UFSC_V2"},
    "ufv": {"UFV"},
    "usp": {"USP"},
}
# Fontes com UM apresentador só: aqui a identidade não depende de join nenhum —
# todo vídeo da fonte é aquela pessoa. Vale para 56% dos vídeos e é fato do
# dicionário, não inferência.
ATOR_UNICO = {"acessibilidade": "2"}


def _chave_arquivo(nome: str) -> str:
    """Nome de arquivo -> chave de join, tolerante às diferenças entre os CSVs.

    Os dois lados nomeiam o mesmo vídeo de formas diferentes, e cada fonte tem o
    seu jeito — verificado nos dados, não suposto:

        USP     link `Libras_glossario_aula10_a_disposicao_STREAM.mp4`
                CSV  `a_disposicao.mp4`        -> tirar prefixo de aula e sufixo
        UFV     link `01028-europa.mp4`
                CSV  `01551-Abençoar.mp4`      -> tirar o prefixo numérico
        acess.  link `aSm_Prog001.mp4`
                CSV  `aSm_Prog001.mp4`         -> igual; NÃO mexer no sufixo Sm_Prog

    O acento sai por último. Sem o `unquote`, `%C3%A7` viraria texto literal e
    nunca casaria com `ç`.
    """
    base = unquote(str(nome))
    base = os.path.basename(base)
    base = re.sub(r"\.[A-Za-z0-9]+$", "", base)
    base = re.sub(r"(?i)^libras[_-]glossario[_-]aula\d+[_-]", "", base)   # USP
    base = re.sub(r"(?i)[_-]stream$", "", base)                            # USP
    base = re.sub(r"^\d+[-_]", "", base)                                   # UFV
    return slug(base)


def mapa_atores(raiz: Path) -> dict[str, dict[str, str]]:
    """Por fonte: chave de arquivo -> ator, do MALTA_LIBRAS_original.csv.

    Separado por fonte porque o mesmo nome de arquivo aparece em dicionários
    diferentes com atores diferentes — um mapa global atribuiria o ator errado.
    """
    csv_path = raiz / "dataset_intersections" / "MALTA_LIBRAS_original.csv"
    if not csv_path.is_file():
        raise SystemExit(f"CSV de atores ausente: {csv_path}")
    mapa: dict[str, dict[str, str]] = defaultdict(dict)
    with csv_path.open(encoding="utf-8") as f:
        for linha in csv.DictReader(f):
            caminho = linha.get("path", "")
            try:
                caminho = ast.literal_eval(caminho)[0]
            except (ValueError, SyntaxError, IndexError):
                pass
            chave = _chave_arquivo(str(caminho))
            if not chave:
                continue
            for fonte, dics in DICIONARIOS.items():
                if linha["dictionary"] in dics:
                    mapa[fonte].setdefault(chave, linha.get("actor", "").strip())
    return mapa


@dataclass
class Clipe:
    sinal: str
    pessoa: str
    rep: int
    url: str
    fonte: str

    @property
    def destino(self) -> str:
        return f"pessoa{self.pessoa}_sinal-{self.sinal}_rep{self.rep:02d}.mp4"


def coletar(raiz: Path, fontes: list[str]) -> list[Clipe]:
    atores = mapa_atores(raiz)
    contador: Counter = Counter()
    clipes: list[Clipe] = []
    for fonte in fontes:
        arquivo = raiz / "video_downloads" / FONTES[fonte]
        if not arquivo.is_file():
            raise SystemExit(f"CSV de links ausente: {arquivo}")
        with arquivo.open(encoding="utf-8") as f:
            for linha in csv.DictReader(f):
                palavra, url = linha.get("Palavra"), linha.get("Link")
                if not palavra or not url:
                    continue
                sinal = slug(palavra)
                if not sinal:
                    continue
                ator = (ATOR_UNICO.get(fonte)
                        or atores[fonte].get(_chave_arquivo(urlparse(url).path), ""))
                # Sem ator, a FONTE vira a identidade. Conservador de propósito:
                # colapsar pessoas subestima diversidade; inventar pessoas
                # inventaria independência que não existe.
                pessoa = f"{PREFIXO_PESSOA}{int(ator):03d}" if ator.isdigit() \
                    else f"{PREFIXO_PESSOA}{fonte[:3].upper()}"
                contador[(pessoa, sinal)] += 1
                clipes.append(Clipe(sinal, pessoa, contador[(pessoa, sinal)], url, fonte))
    return sorted(clipes, key=lambda c: (c.sinal, c.pessoa, c.rep))


def resumo(clipes: list[Clipe]) -> str:
    if not clipes:
        return "nenhum clipe"
    pessoas = {c.pessoa for c in clipes}
    por_sinal = defaultdict(set)
    for c in clipes:
        por_sinal[c.sinal].add(c.pessoa)
    dist = Counter(len(v) for v in por_sinal.values())
    linhas = [f"{len(clipes)} vídeos | {len(por_sinal)} rótulos | {len(pessoas)} pessoas"]
    for k in (2, 3, 5, 8):
        linhas.append(f"  rótulos com >={k} pessoas: {sum(n for a, n in dist.items() if a >= k)}")
    return "\n".join(linhas)


def baixar(clipes: list[Clipe], destino: Path, manifesto: Path) -> tuple[int, int]:
    destino.mkdir(parents=True, exist_ok=True)
    pendentes = [c for c in clipes if not (destino / c.destino).exists()]
    if ja := len(clipes) - len(pendentes):
        print(f"[malta] {ja} já em disco — pulando", flush=True)
    if not pendentes:
        return 0, 0

    novo = not manifesto.exists()
    registro = manifesto.open("a", newline="", encoding="utf-8")
    escritor = csv.writer(registro)
    if novo:
        escritor.writerow(["destino", "sinal", "pessoa", "fonte", "url", "bytes"])

    sessao = requests.Session()
    sessao.headers["User-Agent"] = "libras-livre/pesquisa (CEIA-UFG)"
    inicio, ok, falhas = time.time(), 0, 0
    for i, c in enumerate(pendentes, 1):
        alvo = destino / c.destino
        for tentativa in range(1, TENTATIVAS + 1):
            try:
                r = sessao.get(c.url, timeout=TIMEOUT_S, stream=True)
                if r.status_code != 200:
                    raise OSError(f"HTTP {r.status_code}")
                parcial = alvo.with_suffix(".parcial")
                with parcial.open("wb") as saida:
                    for bloco in r.iter_content(chunk_size=65536):
                        if bloco:
                            saida.write(bloco)
                if parcial.stat().st_size == 0:
                    raise OSError("arquivo vazio")
                parcial.rename(alvo)
                escritor.writerow([c.destino, c.sinal, c.pessoa, c.fonte, c.url,
                                   alvo.stat().st_size])
                ok += 1
                break
            except Exception as e:  # rede é falível; um vídeo ruim não mata o lote
                if tentativa == TENTATIVAS:
                    print(f"[malta] ✗ {c.destino}: {e}", flush=True)
                    falhas += 1
                else:
                    time.sleep(ESPERA_ERRO_S * tentativa)
        time.sleep(PAUSA_S)
        if i % 25 == 0 or i == len(pendentes):
            passado = time.time() - inicio
            taxa = i / passado
            resta = (len(pendentes) - i) / taxa / 60 if taxa else 0
            registro.flush()
            print(f"[malta] {i:>5}/{len(pendentes)}  {taxa:.1f} vid/s  "
                  f"{falhas} falha(s)  faltam ~{resta:.0f} min", flush=True)
    registro.close()
    return ok, falhas


def indice_sha256(raiz: Path, fontes: list[str]) -> str:
    """sha256 dos CSVs de links usados — o "índice" do MALTA.

    Um bundle do Kaggle tem um índice de ZIP versionado; aqui o equivalente é a
    lista de URLs. Pinar o hash dela responde depois a pergunta "que conjunto de
    links produziu estes arquivos?", que é a única âncora de reprodutibilidade
    que uma coleção baixada por HTTP tem.
    """
    h = hashlib.sha256()
    for fonte in sorted(fontes):
        h.update(fonte.encode())
        h.update((raiz / "video_downloads" / FONTES[fonte]).read_bytes())
    return h.hexdigest()


def registrar(raiz: Path, destino: Path, manifesto: Path, landmarks: Path | None,
              fontes: list[str]) -> None:
    """Gera os sidecars de proveniência a partir do manifesto do download.

    Sem isto, `pretreinar.py --auditar` rejeita tudo com "sem origem registrada;
    landmarks NÃO elegíveis para pré-treino" — que é exatamente o uso principal
    desta base. O manifesto guarda a URL de cada arquivo, então dá para registrar
    sem rebaixar nada e sem rebaixar dado.
    """
    import yaml
    if not manifesto.is_file():
        raise SystemExit(f"manifesto ausente: {manifesto}")
    indice = indice_sha256(raiz, fontes)
    cfg = yaml.safe_load((AQUI.parent / "PoC" / "config.yaml").read_text(encoding="utf-8"))

    ok_v = ok_l = pulados = 0
    erros: list[str] = []
    with manifesto.open(encoding="utf-8") as f:
        for linha in csv.DictReader(f):
            video = destino / linha["destino"]
            if not video.is_file():
                pulados += 1          # apagado por ser inválido, por exemplo
                continue
            try:
                pv.registrar_video_http(video, fonte="malta", origem=linha["url"],
                                        indice_sha256=indice)
                ok_v += 1
                npy = (landmarks / video.name).with_suffix(".npy") if landmarks else None
                if npy and npy.is_file():
                    pv.registrar_landmarks(video, npy, cfg, legado=True)
                    ok_l += 1
            except Exception as e:
                erros.append(f"{video.name}: {e}")
    print(f"[malta] sidecars: {ok_v} vídeo(s), {ok_l} landmark(s), {pulados} ausente(s)")
    if erros:
        print(f"[malta] ⚠ {len(erros)} falha(s); primeiras:")
        for e in erros[:5]:
            print(f"[malta]   {e}")
    print(f"[malta] índice dos CSVs de links: {indice[:16]}…")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", type=Path, required=True,
                    help=f"clone de {REPO} (só os CSVs são usados)")
    ap.add_argument("--fonte", action="append", choices=sorted(FONTES),
                    help="repita para várias; padrão é todas as incluídas")
    ap.add_argument("--listar", action="store_true", help="mostra a cobertura sem baixar")
    ap.add_argument("--registrar", action="store_true",
                    help="gera os sidecars de proveniência do que já está em disco, "
                         "a partir do manifesto; não baixa nada")
    ap.add_argument("--landmarks", type=Path,
                    help="com --registrar: diretório dos .npy, para registrá-los também")
    ap.add_argument("--destino", type=Path, default=DESTINO)
    args = ap.parse_args()

    fontes = args.fonte or sorted(FONTES)
    clipes = coletar(args.repo, fontes)
    print(f"[malta] fontes: {', '.join(fontes)}")
    for nome, motivo in EXCLUIDAS.items():
        print(f"[malta] excluída {nome}: {motivo}")
    print(resumo(clipes))
    if args.listar:
        return

    manifesto = args.destino.parent / "manifest-malta.csv"
    if args.registrar:
        registrar(args.repo, args.destino, manifesto, args.landmarks, fontes)
        return
    ok, falhas = baixar(clipes, args.destino, manifesto)
    print(f"[malta] concluído: {ok} baixado(s), {falhas} falha(s) -> {args.destino}")
    print(f"[malta] manifesto: {manifesto}")
    print("[malta] próximo passo: cd ../PoC && python src/extract.py "
          f"--entrada data/raw-malta --saida data/landmarks-malta")


if __name__ == "__main__":
    sys.exit(main())

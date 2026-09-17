"""Carga dos landmarks e montagem das partições leave-one-signer-out.

O dataset em disco é o que `PoC/src/extract.py` produz: um .npy por clipe, com
nome `pessoaXX_sinal-YYY_repNN.npy` e forma (frames, pontos, 3). O nome do
arquivo é a fonte de verdade de quem sinalizou o quê — é dele que sai a partição
por pessoa.

PARTIÇÃO (o ponto que decide se o número final significa alguma coisa): treinar
e testar com a MESMA pessoa mede memorização, não generalização. Como os óculos
atendem alguém novo a cada atendimento, toda avaliação aqui deixa uma pessoa
INTEIRA de fora. Seguindo os dois trabalhos de referência, cada rodada separa
também uma segunda pessoa para validação (escolha do melhor epoch) — senão o
early stopping estaria espiando o conjunto de teste.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np

# pessoa03_sinal-ajuda_rep02 -> pessoa=03, sinal=ajuda, rep=02
_RE_NOME = re.compile(r"pessoa(?P<pessoa>[^_]+)_sinal-(?P<sinal>.+)_rep(?P<rep>\d+)$")


@dataclass
class Clipe:
    pessoa: str
    sinal: str
    rep: str
    seq: np.ndarray  # (frames, pontos, 2), float32


@dataclass
class Particao:
    """Uma rodada de leave-one-signer-out."""

    teste: str
    validacao: str
    treino: list[str]


def parse_nome(stem: str) -> tuple[str, str, str]:
    m = _RE_NOME.match(stem)
    if not m:
        raise ValueError(f"nome fora da convenção 'pessoaNN_sinal-XXX_repNN': {stem!r}")
    return m.group("pessoa"), m.group("sinal"), m.group("rep")


def recentrar_z(seq: np.ndarray) -> np.ndarray:
    """Devolve o z de cada mão ao referencial do próprio punho.

    O QUE ESTÁ QUEBRADO. Em `extract.py`, a origem subtraída de TODOS os pontos é
    o ponto médio dos ombros, um vetor de 3 dimensões — z incluído. Para a pose
    isso é coerente: o z do MediaPipe para pose é relativo ao quadril, e recentrar
    no ombro só troca uma referência corporal por outra.

    Para as mãos, não é. O z de mão do MediaPipe já vem relativo ao PUNHO daquela
    mão. Subtrair dele o z do ombro mistura dois referenciais: o valor resultante
    não é profundidade em relação a nada em particular. É exatamente a "soma de
    referenciais diferentes" que o comentário do config.yaml descreve — só que
    descrita como propriedade inevitável do z, quando é consequência de como
    normalizamos.

    O CONSERTO. Subtrair de cada bloco de mão o z do nó 0 dele (o punho da própria
    mão) devolve o bloco ao referencial em que o MediaPipe o produziu, mantendo a
    escala em unidades de ombro.

    Por que isso importa para o experimento: medir "z ligado vs desligado" com o z
    quebrado mediria o defeito, não a informação. Se o resultado for negativo nas
    duas variantes, aí sim o z não serve; se for negativo só na crua, o problema
    era a normalização.

    Mão ausente é um bloco de zeros — subtrair o zero do nó 0 preserva isso.
    """
    if seq.shape[2] < 3:
        return seq
    saida = seq.copy()
    for a, b in BLOCOS_MAO:
        saida[:, a:b, 2] -= seq[:, a:a + 1, 2]
    return saida


# `todas` CONTINUA SENDO M+V, o que sempre foi. Acrescentar T aqui pareceu
# natural e é armadilha: `todas` alimenta a partição LOSO do treinar.py, e
# incluir MALTA faria as rodadas girarem também sobre as 8 pseudo-pessoas
# dele — inclusive `TUFS`, que são 2.039 clipes sob um ID que não é uma
# pessoa. A média reportada deixaria de ser o número do protocolo sem que
# nada no comando mudasse. Quem quer MALTA pede por nome ou compõe.
PREFIXOS_FONTE = {"minds": ("M",), "vlibrasil": ("V",), "malta": ("T",),
                  "wlasl": ("W",), "todas": ("M", "V")}


def prefixos_aceitos(fontes: str) -> tuple[str, ...]:
    """`fontes` ('vlibrasil', 'vlibrasil,malta', ...) -> tupla de prefixos de pessoa.

    Extraído de `carregar` para que quem só precisa saber "essa configuração
    aceita o prefixo X?" — como a guarda de `pretreinar.carregar_corpora` — não
    precise repetir o parsing, nem inferir a resposta a partir do que sobrou
    depois de filtros que nada têm a ver com configuração (frames curtos, por
    exemplo). É checagem de ELEGIBILIDADE, não de resultado de leitura.
    """
    pedidos = [f.strip() for f in fontes.split(",") if f.strip()]
    if not pedidos or any(f not in PREFIXOS_FONTE for f in pedidos):
        raise ValueError(f"fontes={fontes!r} — use um ou mais de {sorted(PREFIXOS_FONTE)}, "
                         "separados por vírgula")
    return tuple(sorted({p for f in pedidos for p in PREFIXOS_FONTE[f]}))


def carregar(lm_dir: Path, fontes: str = "minds", min_frames: int = 3,
             imputar: bool = True, lacuna_maxima: int = 5,
             com_z: bool = False, z_recentrado: bool = False) -> list[Clipe]:
    """Lê os .npy de `lm_dir`, ficando com x,y (e z, se `com_z`).

    `fontes`: 'minds' (M*), 'vlibrasil' (V*), 'malta' (T*), 'wlasl' (W*) ou
    'todas' (M+V+T), e aceita composição por vírgula ('vlibrasil,malta'). O padrão é
    MINDS porque é a única fonte com pessoas suficientes por sinal para a
    avaliação signer-independent valer — a V-LIBRASIL tem sempre os mesmos 3
    articuladores (docs/vocabulario-mvp-proposta.md).

    `imputar` preenche lacunas curtas de mão não detectada (ver imputar_maos).
    É feito na LEITURA, não na extração: o .npy guarda a verdade crua, custa horas
    de MediaPipe para refazer, e assim a imputação é reversível e comparável com
    um `--sem-imputacao`.

    `com_z` mantém a terceira coordenada; `z_recentrado` além disso devolve o z das
    mãos ao referencial do punho (ver recentrar_z). O .npy sempre tem as 3 dims, então
    trocar de variante NÃO exige reextrair — são horas de MediaPipe economizadas.
    """
    aceitos = prefixos_aceitos(fontes)

    clipes: list[Clipe] = []
    curtos: list[str] = []
    ignorados: dict[str, int] = {}
    for arquivo in sorted(lm_dir.glob("*.npy")):
        pessoa, sinal, rep = parse_nome(arquivo.stem)
        if not pessoa.startswith(aceitos):
            # Contar em vez de descartar calado. Pedir uma fonte que o diretório
            # não tem — ou esquecer de incluí-la em `fontes` — sumia com milhares
            # de clipes sem uma linha de log, e o treino seguia com menos dado do
            # que o operador acreditava ter.
            ignorados[pessoa[:1]] = ignorados.get(pessoa[:1], 0) + 1
            continue
        arr = np.load(arquivo)
        if arr.ndim != 3:
            raise ValueError(f"{arquivo.name}: esperava (frames, pontos, dims), veio {arr.shape}")
        if arr.shape[0] < min_frames:
            # Clipe sem frames suficientes para formar nem um canal da imagem:
            # quase sempre um vídeo em que o MediaPipe não achou o tronco.
            curtos.append(arquivo.name)
            continue
        # Padrão x,y — decisão HERDADA da PoC, não medida neste pipeline: os 4-6
        # pontos de ganho ao desligar o z (PoC/config.yaml) foram medidos no DTW
        # 1-NN, que soma distâncias cruas e não tem como se defender de um canal
        # com escala incoerente. ResNet e GCN têm peso aprendido e podem ponderar
        # o canal. `--com-z` existe para medir isso. Ver PLANO-CORRECOES.md (B5).
        seq = arr[:, :, :3 if com_z else 2].astype(np.float32)
        if com_z and z_recentrado:
            seq = recentrar_z(seq)
        if imputar:
            seq = imputar_maos(seq, lacuna_maxima)
        clipes.append(Clipe(pessoa, sinal, rep, seq))

    if ignorados:
        detalhe = ", ".join(f"{n} com prefixo {p!r}" for p, n in sorted(ignorados.items()))
        print(f"[dados] fontes={fontes}: {sum(ignorados.values())} clipe(s) fora do "
              f"filtro ({detalhe})")
    if curtos:
        print(f"[dados] {len(curtos)} clipe(s) descartado(s) por ter < {min_frames} frames "
              f"válidos: {', '.join(curtos[:5])}{' ...' if len(curtos) > 5 else ''}")
    return clipes


# Layout do vetor de pontos que extract.py produz (config.pose_indices + 2 mãos).
N_POSE, N_MAO = 15, 21
BLOCOS_MAO = ((N_POSE, N_POSE + N_MAO), (N_POSE + N_MAO, N_POSE + 2 * N_MAO))


def maos_ausentes(seq: np.ndarray) -> list[np.ndarray]:
    """Máscara booleana por frame, para cada mão: True = não detectada.

    `extract.py` escreve zeros quando o MediaPipe não acha a mão. Zero é a ORIGEM
    (ponto médio dos ombros), então a ausência não é neutra: ela teleporta a mão
    para o meio do peito. Detectamos pelo bloco inteiro exatamente zerado — 42
    coordenadas darem 0.0 por acaso não acontece.

    Olha só x,y de propósito: com `--com-z --z-recentrado`, o z do punho vira 0 por
    construção em TODO frame, e incluir o z aqui aproximaria uma mão presente do
    critério de ausência. x,y decide sozinho e não depende da variante de canais.
    """
    return [np.abs(seq[:, a:b, :2]).sum(axis=(1, 2)) == 0 for a, b in BLOCOS_MAO]


def imputar_maos(seq: np.ndarray, lacuna_maxima: int = 5) -> np.ndarray:
    """Interpola as mãos em lacunas curtas; preserva as longas como ausência.

    POR QUE ISTO EXISTE, com número. Medido nos 800 clipes do MINDS: 51,7% dos
    frames chegam sem NENHUMA mão detectada, e cada aparecimento/desaparecimento
    injeta um salto de 1,09 unidades de ombro (mediana) — cerca de 30% da
    amplitude total dos dados, que vai de -1,6 a +1,8. É um movimento brusco que
    nós mesmos criamos ao representar ausência como zero, e o modelo o vê como se
    fosse parte do sinal.

    Tentamos primeiro melhorar a DETECÇÃO, e não resolve: model_complexity=2
    (44,7% contra 47,4%), MediaPipe Hands isolado, e recorte ao redor do pulso
    (+5,3 pp apenas). Sobra tratar a lacuna, que é o que a literatura de ISLR em
    Libras já reportava valer >=4pp de F1 no MINDS.

    O limite de `lacuna_maxima` é deliberado: interpolar 3 frames entre duas
    detecções é reconstruir; interpolar 40 é inventar uma trajetória que não foi
    observada. Lacunas longas continuam zeradas — ausência é informação legítima
    (sinais de uma mão só existem, e a mão parada de fato não aparece).
    """
    saida = seq.copy()
    for (a, b), ausente in zip(BLOCOS_MAO, maos_ausentes(seq)):
        presentes = np.flatnonzero(~ausente)
        if presentes.size < 2:
            continue  # nada para interpolar entre
        for ini, fim in zip(presentes[:-1], presentes[1:]):
            vao = fim - ini - 1
            if 0 < vao <= lacuna_maxima:
                # interpolação linear entre as duas detecções que cercam a lacuna
                pesos = np.linspace(0, 1, vao + 2)[1:-1].reshape(-1, 1, 1)
                saida[ini + 1:fim, a:b, :] = (
                    seq[ini, a:b, :][None] * (1 - pesos) + seq[fim, a:b, :][None] * pesos)
    return saida


def rotulos(clipes: list[Clipe]) -> list[str]:
    return sorted({c.sinal for c in clipes})


def pessoas(clipes: list[Clipe]) -> list[str]:
    return sorted({c.pessoa for c in clipes})


def particoes(clipes: list[Clipe]) -> list[Particao]:
    """Uma partição por pessoa: ela é o teste, a seguinte é a validação.

    A validação rotaciona junto (pessoa i testa, pessoa i+1 valida) em vez de ser
    fixa: uma pessoa fixa de validação enviesaria a escolha de epoch para o
    estilo dela em todas as rodadas.
    """
    todas = pessoas(clipes)
    if len(todas) < 3:
        raise SystemExit(
            f"leave-one-signer-out com validação exige >=3 pessoas, achei {len(todas)}: {todas}")
    saida = []
    for i, p in enumerate(todas):
        val = todas[(i + 1) % len(todas)]
        saida.append(Particao(teste=p, validacao=val,
                              treino=[q for q in todas if q not in (p, val)]))
    return saida


def conferir(clipes: list[Clipe], vocabulario: list[str]) -> list[str]:
    """Avisos que mudam a leitura do resultado (classe rara, pessoa faltando...)."""
    avisos = []
    presentes = set(rotulos(clipes))
    if faltando := set(vocabulario) - presentes:
        avisos.append(f"sinais do vocabulário sem nenhum clipe: {sorted(faltando)}")
    if sobrando := presentes - set(vocabulario):
        avisos.append(f"clipes de sinais fora do vocabulário: {sorted(sobrando)}")

    por_sinal: dict[str, set[str]] = {}
    for c in clipes:
        por_sinal.setdefault(c.sinal, set()).add(c.pessoa)
    if solitarios := [s for s, ps in por_sinal.items() if len(ps) < 2]:
        avisos.append(f"sinais com uma única pessoa: {solitarios} — na rodada dela o erro é certo")
    return avisos

"""Validação do pipeline de treino com dados sintéticos — sem dataset real.

O treino real custa horas de CPU. Este self-test exercita o caminho inteiro em
segundos, com clipes sintéticos, para que um erro de encanamento apareça agora e
não depois de duas horas de ResNet:

  1. Skeleton-DML: forma da imagem, faixa [0,1], descarte dos frames sobrando;
  2. espelhamento: troca de fato os pares esquerda/direita e é involução;
  3. augmentação: preserva a forma e não gera NaN;
  4. partições leave-one-signer-out: teste, validação e treino sempre disjuntos;
  5. treino de ponta a ponta numa rodada: o modelo aprende classes separáveis;
  6. controle negativo: com rótulos aleatórios, a acurácia não sai da chance —
     garante que o item 5 não está passando por vazamento de dados.

Uso:
    python selftest.py
"""
from __future__ import annotations

import argparse
import collections
import sys
import tempfile
import traceback
from pathlib import Path
from unittest.mock import patch

import numpy as np
import torch

import contrastivo as ct
import dados as dd
import gcn
import modelo as mm
import representacao as rp
import treinar as tr

POSE = ["nariz", "olho_esq", "olho_dir", "orelha_esq", "orelha_dir", "boca_esq",
        "boca_dir", "ombro_esq", "ombro_dir", "cotovelo_esq", "cotovelo_dir",
        "pulso_esq", "pulso_dir", "quadril_esq", "quadril_dir"]
N_PONTOS = len(POSE) + 42


def _ok(msg: str) -> None:
    print(f"  ✓ {msg}")


def _clipe_sintetico(rng, classe: int, n_classes: int, pessoa: int, n_frames: int) -> np.ndarray:
    """Trajetória senoidal com frequência e padrão espacial próprios da classe.

    O padrão espacial por classe é ALEATÓRIO-mas-fixo (semeado pela classe), não
    uma função suave do índice do ponto. Isso importa: um padrão suave no índice
    é trivial para a representação em imagem (índices viram linhas vizinhas) e
    quase invisível para o GCN, cuja vizinhança é anatômica — o teste passaria a
    medir a representação em vez do encanamento. Assim as duas arquiteturas
    enxergam a mesma dificuldade.
    """
    t = np.linspace(0, 1, n_frames)[:, None, None]
    rng_classe = np.random.default_rng(1000 + classe)
    direcao = rng_classe.normal(0, 1.0, size=(1, N_PONTOS, 2))
    base = rng.normal(0, 0.05, size=(1, N_PONTOS, 2))
    freq = 1.0 + classe * (2.0 / max(n_classes, 1))
    fase = pessoa * 0.15
    onda = np.sin(2 * np.pi * freq * t + fase)
    ruido = rng.normal(0, 0.02, size=(n_frames, N_PONTOS, 2))
    return (base + onda * direcao * 0.4 + ruido).astype(np.float32)


def _dataset_sintetico(destino: Path, n_pessoas=4, n_classes=3, reps=3, seed=7,
                       rotulo_aleatorio=False) -> None:
    rng = np.random.default_rng(seed)
    destino.mkdir(parents=True, exist_ok=True)
    for p in range(1, n_pessoas + 1):
        for c in range(n_classes):
            for r in range(1, reps + 1):
                n_frames = int(rng.integers(30, 45))
                classe_geradora = int(rng.integers(0, n_classes)) if rotulo_aleatorio else c
                seq = _clipe_sintetico(rng, classe_geradora, n_classes, p, n_frames)
                # .npy real tem 3 dims; dados.carregar corta para x,y
                seq3 = np.concatenate([seq, np.zeros_like(seq[:, :, :1])], axis=2)
                np.save(destino / f"pessoaM{p:02d}_sinal-classe{c}_rep{r:02d}.npy", seq3)


# ------------------------------------------------------------------- testes

def teste_representacao() -> None:
    seq = np.random.uniform(-1.5, 1.5, size=(100, N_PONTOS, 2)).astype(np.float32)
    img = rp.para_imagem(seq)
    assert img.shape == (N_PONTOS, 2 * (100 // 3), 3), f"forma inesperada: {img.shape}"
    assert 0.0 <= img.min() and img.max() <= 1.0, "imagem fora de [0,1]"

    # valores acima do limite são cortados, não estouram a faixa
    extremo = np.full((6, N_PONTOS, 2), 99.0, dtype=np.float32)
    assert rp.para_imagem(extremo).max() <= 1.0, "clip do limite falhou"

    # clipe curto demais precisa falhar explicitamente, não gerar imagem vazia
    try:
        rp.para_imagem(np.zeros((2, N_PONTOS, 2), dtype=np.float32))
        raise AssertionError("clipe de 2 frames deveria ter sido rejeitado")
    except ValueError:
        pass
    _ok("Skeleton-DML: forma, faixa [0,1] e rejeição de clipe curto")


def teste_espelho() -> None:
    perm = rp.permutacao_espelho(POSE)
    assert len(perm) == N_PONTOS
    i_e, i_d = POSE.index("ombro_esq"), POSE.index("ombro_dir")
    assert perm[i_e] == i_d and perm[i_d] == i_e, "ombros não trocaram"
    assert perm[POSE.index("nariz")] == POSE.index("nariz"), "ponto central deveria ficar"
    base = len(POSE)
    assert list(perm[base:base + 21]) == list(range(base + 21, base + 42)), \
        "bloco da mão esquerda deveria virar o da direita"

    seq = np.random.uniform(-1, 1, size=(10, N_PONTOS, 2)).astype(np.float32)
    espelhado = rp.espelhar(seq, perm)
    assert np.allclose(espelhado[:, i_d, 0], -seq[:, i_e, 0]), \
        "x do ombro esquerdo deveria virar -x na posição do direito"
    assert np.allclose(rp.espelhar(espelhado, perm), seq), "espelhar 2x não voltou ao original"

    # pose_indices sem par _dir tem de falhar alto, não gerar mapa errado em silêncio
    try:
        rp.permutacao_espelho(["nariz", "ombro_esq"])
        raise AssertionError("faltando o par _dir, deveria ter levantado ValueError")
    except ValueError:
        pass
    _ok("espelhamento troca os pares e é involução")


def teste_augmentacao() -> None:
    rng = np.random.default_rng(0)
    perm = rp.permutacao_espelho(POSE)
    seq = np.random.uniform(-1, 1, size=(40, N_PONTOS, 2)).astype(np.float32)
    for _ in range(20):
        aug = rp.aumentar(seq, rng, perm)
        assert aug.shape == seq.shape, f"augmentação mudou a forma: {aug.shape}"
        assert np.isfinite(aug).all(), "augmentação gerou NaN/inf"
    _ok("augmentação preserva forma e não gera NaN")


def teste_particoes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        destino = Path(tmp)
        _dataset_sintetico(destino, n_pessoas=4, n_classes=3, reps=2)
        clipes = dd.carregar(destino, fontes="minds")
        assert len(clipes) == 4 * 3 * 2, f"esperava 24 clipes, veio {len(clipes)}"
        assert clipes[0].seq.shape[2] == 2, "carregar deveria devolver só x,y"

        parts = dd.particoes(clipes)
        assert len(parts) == 4, f"esperava 4 rodadas, veio {len(parts)}"
        for p in parts:
            assert p.teste != p.validacao, "teste e validação não podem ser a mesma pessoa"
            assert p.teste not in p.treino and p.validacao not in p.treino, \
                "pessoa de teste/validação vazou para o treino"
            assert len(p.treino) == 2, f"treino deveria ter 2 pessoas, veio {p.treino}"
        assert {p.teste for p in parts} == set(dd.pessoas(clipes)), \
            "toda pessoa precisa ser testada uma vez"

        # com menos de 3 pessoas não há como separar teste E validação
        poucos = [c for c in clipes if c.pessoa in ("M01", "M02")]
        try:
            dd.particoes(poucos)
            raise AssertionError("com 2 pessoas deveria ter falhado")
        except SystemExit:
            pass
    _ok("partições LOSO: teste, validação e treino disjuntos")


def _rodada_sintetica(rotulo_aleatorio: bool, epocas: int = 6,
                      arquitetura: str = "resnet", folds: int = 3) -> float:
    """Média de `folds` rodadas LOSO — uma só é ruído demais para virar asserção.

    Aprendemos isso na prática: a versão anterior media UMA rodada e passava em
    CPU (100%) enquanto reprovava em GPU (41,7%) com o MESMO código. Não era bug
    de pipeline, era o teste: uma ResNet de 11M de parâmetros generalizando a
    partir de ~100 clipes sintéticos tem variância enorme, e o resultado dependia
    de sorte de inicialização. Três rodadas reduzem isso o bastante para a
    asserção significar alguma coisa.
    """
    with tempfile.TemporaryDirectory() as tmp:
        destino = Path(tmp)
        _dataset_sintetico(destino, n_pessoas=6, n_classes=3, reps=8,
                           rotulo_aleatorio=rotulo_aleatorio)
        clipes = dd.carregar(destino, fontes="minds")
        rotulos = dd.rotulos(clipes)
        perm = rp.permutacao_espelho(POSE)

        args = argparse.Namespace(epocas=epocas, lr=1e-3, wd=1e-4, batch=8, workers=0,
                                  arquitetura=arquitetura, agendador="nenhum",
                                  inicializar=None)
        accs = []
        for part in dd.particoes(clipes)[:folds]:
            treino = [c for c in clipes if c.pessoa in part.treino]
            val = [c for c in clipes if c.pessoa == part.validacao]
            teste = [c for c in clipes if c.pessoa == part.teste]
            acc, _, _, _, _ = tr.treinar_rodada(treino, val, teste, rotulos, perm,
                                                args, torch.device("cpu"))
            accs.append(acc)
        return float(np.mean(accs))


def teste_treino_ponta_a_ponta() -> None:
    acc = _rodada_sintetica(rotulo_aleatorio=False)
    assert acc > 0.60, f"classes separáveis deveriam passar de 60%, veio {acc:.1%}"
    _ok(f"treino de ponta a ponta aprende classes separáveis ({acc:.0%})")


def teste_grafo_conectado() -> None:
    """Mãos precisam alcançar o tronco: grafo desconectado isola os dedos."""
    import numpy as _np
    v = gcn.N_POSE + 2 * gcn.N_MAO
    adj = _np.zeros((v, v), dtype=bool)
    for i, j in gcn.arestas():
        adj[i, j] = adj[j, i] = True
    visto, fila = {0}, [0]
    while fila:
        u = fila.pop()
        for w in _np.where(adj[u])[0]:
            if int(w) not in visto:
                visto.add(int(w))
                fila.append(int(w))
    assert len(visto) == v, f"grafo desconectado: {sorted(set(range(v)) - visto)} inalcançáveis"

    a = gcn.adjacencia(v, gcn.arestas())
    assert a.shape == (2, v, v), a.shape
    assert torch.allclose(a[1].sum(0), torch.ones(v), atol=1e-5), \
        "adjacência dos vizinhos não está normalizada por grau"
    _ok(f"grafo do esqueleto conectado ({v} nós, {len(gcn.arestas())} arestas)")


def teste_ossos() -> None:
    """Ossos: árvore coerente com as arestas, simétrica ao espelho e invariante
    a translação — as três propriedades pelas quais eles existem."""
    import numpy as _np
    pai = gcn.pais()
    v = gcn.N_POSE + 2 * gcn.N_MAO
    assert pai.shape == (v,) and pai[0] == 0, (pai.shape, pai[0])

    # Todo osso tem de ser uma aresta real do grafo (a raiz é o caso degenerado).
    conjunto = {frozenset(e) for e in gcn.arestas()}
    for filho, p in enumerate(pai):
        if filho != int(p):
            assert frozenset((filho, int(p))) in conjunto, \
                f"osso {filho}<-{p} não corresponde a nenhuma aresta"

    # Simetria: espelhar o esqueleto tem de espelhar os ossos, não embaralhá-los.
    # Se a árvore fosse enraizada num ombro isto falharia, e falharia em silêncio
    # — o modelo veria ossos incoerentes só nos clipes espelhados pela augmentação.
    perm = rp.permutacao_espelho(POSE)
    rng = _np.random.default_rng(11)
    seq = rng.normal(size=(7, v, 2)).astype(_np.float32)
    ossos_do_espelho = gcn.com_ossos(rp.espelhar(seq, perm), pai)[:, :, 2:]
    espelho_dos_ossos = gcn.com_ossos(seq, pai)[:, perm, 2:] * _np.array([-1.0, 1.0],
                                                                        dtype=_np.float32)
    assert _np.allclose(ossos_do_espelho, espelho_dos_ossos, atol=1e-5), \
        "a árvore de ossos não é simétrica ao espelhamento"

    # Invariância a translação: é a propriedade que justifica o canal extra.
    deslocado = gcn.com_ossos(seq + _np.float32(0.37), pai)[:, :, 2:]
    assert _np.allclose(deslocado, gcn.com_ossos(seq, pai)[:, :, 2:], atol=1e-5), \
        "ossos deveriam ser invariantes a translação"

    saida = gcn.para_sequencia(gcn.com_ossos(seq, pai))
    assert saida.shape == (4, gcn.T_FIXO, v), saida.shape
    modelo = gcn.construir(3, canais_ent=4)
    assert modelo(torch.from_numpy(saida).unsqueeze(0)).shape == (1, 3)
    _ok("ossos: árvore coerente, simétrica ao espelho e invariante a translação")


def teste_terceira_coordenada() -> None:
    """As duas representações têm de aceitar 3 coordenadas sem perder o z."""
    import numpy as _np
    rng = _np.random.default_rng(5)
    v = gcn.N_POSE + 2 * gcn.N_MAO
    seq = rng.normal(scale=0.8, size=(12, v, 3)).astype(_np.float32)

    # Imagem: o z entra como bloco de COLUNAS, não como 4º canal — os 3 canais
    # RGB continuam sendo 3 frames consecutivos.
    img2 = rp.para_imagem(seq[:, :, :2])
    img3 = rp.para_imagem(seq)
    assert img3.shape[0] == img2.shape[0] and img3.shape[2] == img2.shape[2]
    assert img3.shape[1] == img2.shape[1] * 3 // 2, (img2.shape, img3.shape)
    # O bloco x,y da imagem 3D tem de ser idêntico ao da 2D: acrescentar z não
    # pode reescalar o que já estava medido em 93,4%.
    assert _np.allclose(img3[:, :img2.shape[1]], img2, atol=1e-6), \
        "acrescentar z alterou os blocos x,y da imagem"
    assert 0.0 <= img3.min() and img3.max() <= 1.0

    # O limite do z é maior: um z grande não pode saturar como saturaria em x,y.
    grande = _np.zeros((3, v, 3), dtype=_np.float32); grande[:, :, 2] = 3.5
    assert rp.para_imagem(grande).max() < 1.0, \
        "z de 3.5 saturou — LIMITE_Z não está sendo aplicado (com LIMITE=2.0 ele viraria 1.0)"

    # GCN: 3 canais, e 6 com ossos (o dobro, não 4).
    assert gcn.para_sequencia(seq).shape == (3, gcn.T_FIXO, v)
    com_ossos = gcn.com_ossos(seq, gcn.pais())
    assert com_ossos.shape == (12, v, 6), com_ossos.shape
    assert _np.allclose(com_ossos[:, :, :3], seq, atol=1e-6)
    m = gcn.construir(3, canais_ent=6)
    assert m(torch.from_numpy(gcn.para_sequencia(com_ossos)).unsqueeze(0)).shape == (1, 3)

    # Recentrar o z zera o punho de cada mão e NÃO toca em x, y nem na pose.
    rec = dd.recentrar_z(seq)
    assert _np.allclose(rec[:, :, :2], seq[:, :, :2]), "recentrar mexeu em x,y"
    assert _np.allclose(rec[:, :dd.N_POSE, 2], seq[:, :dd.N_POSE, 2]), "recentrar mexeu na pose"
    for a, _b in dd.BLOCOS_MAO:
        assert _np.allclose(rec[:, a, 2], 0.0), "o punho da mão deveria virar z=0"
    # Mão ausente (bloco zerado) continua detectada como ausente depois de recentrar.
    ausente = seq.copy(); a, b = dd.BLOCOS_MAO[0]; ausente[3, a:b, :] = 0.0
    assert dd.maos_ausentes(dd.recentrar_z(ausente))[0][3], \
        "mão ausente deixou de ser detectada após recentrar o z"
    _ok("terceira coordenada: imagem, grafo, ossos e recentramento do z")


def teste_movimento_e_adjacencia() -> None:
    """Movimento, adjacência adaptativa e kernel temporal — as três variantes novas."""
    import numpy as _np
    v = gcn.N_POSE + 2 * gcn.N_MAO
    rng = _np.random.default_rng(17)
    seq = rng.normal(scale=0.6, size=(9, v, 2)).astype(_np.float32)

    # Movimento: dobra canais, primeiro frame zerado, e é a diferença de fato.
    mov = gcn.com_movimento(seq)
    assert mov.shape == (9, v, 4), mov.shape
    assert _np.allclose(mov[:, :, :2], seq), "movimento alterou os canais originais"
    assert _np.allclose(mov[0, :, 2:], 0.0), "o primeiro frame deveria ter variação zero"
    assert _np.allclose(mov[3, :, 2:], seq[3] - seq[2], atol=1e-6)

    # Invariância a translação: mover a pessoa não muda a velocidade dela.
    assert _np.allclose(gcn.com_movimento(seq + _np.float32(0.9))[:, :, 2:],
                        mov[:, :, 2:], atol=1e-6), \
        "movimento deveria ser invariante a translação"

    # Compõe com ossos, na ordem que o dataset usa: 2 -> 4 -> 8.
    comp = gcn.com_movimento(gcn.com_ossos(seq, gcn.pais()))
    assert comp.shape == (9, v, 8), comp.shape
    m = gcn.construir(3, canais_ent=8)
    assert m(torch.from_numpy(gcn.para_sequencia(comp)).unsqueeze(0)).shape == (1, 3)

    # Adjacência adaptativa inicia em ZERO: o modelo tem de começar idêntico ao
    # sem ela. Se inicializasse aleatória, "ligar a flag" mudaria o resultado por
    # ruído de inicialização e a comparação pareada perderia o sentido.
    torch.manual_seed(4)
    sem = gcn.construir(5)
    torch.manual_seed(4)
    com = gcn.construir(5, adjacencia_adaptativa=True)
    assert com.adaptativa is not None and sem.adaptativa is None
    assert torch.allclose(com.adaptativa, torch.zeros_like(com.adaptativa))
    x = torch.randn(2, 2, gcn.T_FIXO, v)
    sem.eval(); com.eval()
    with torch.no_grad():
        assert torch.allclose(sem(x), com(x), atol=1e-6), \
            "com adaptativa zerada, a saída deveria ser idêntica"

    # Kernel temporal: onde estão ~80% dos parâmetros. Reduzir tem de encolher.
    n9 = sum(p.numel() for p in gcn.construir(20).parameters())
    n5 = sum(p.numel() for p in gcn.construir(20, kernel_t=5).parameters())
    assert n5 < n9 * 0.7, f"kernel 5 deveria encolher bem o modelo: {n9} -> {n5}"
    assert gcn.construir(3, kernel_t=5)(torch.randn(1, 2, gcn.T_FIXO, v)).shape == (1, 3)

    # O config viaja no checkpoint — senão recarregar reconstrói outra arquitetura.
    cfg = gcn.construir(3, canais_ent=8, adjacencia_adaptativa=True, kernel_t=5).config
    assert cfg["canais_ent"] == 8 and cfg["adjacencia_adaptativa"] and cfg["kernel_t"] == 5

    # Kernel par quebra a soma residual, e só no primeiro lote — depois de alocar
    # GPU e carregar dados. Tem de recusar na construção.
    for k in (0, 2, 4, -1):
        try:
            gcn.construir(3, largura=8, kernel_t=k)
            raise AssertionError(f"kernel_t={k} deveria ter sido recusado")
        except ValueError:
            pass

    # Movimento SEM máscara inventa velocidade onde a mão só sumiu da detecção.
    # Medido no dataset real: 3.011 transições nos punhos, salto mediano 1,32
    # contra 0,0116 de deslocamento normal. Uma mão PARADA que some e volta não
    # pode gerar velocidade.
    parada = _np.ones((20, v, 2), dtype=_np.float32)
    parada[5:15, dd.BLOCOS_MAO[0][0]:dd.BLOCOS_MAO[0][1], :] = 0.0
    val = _np.ones(parada.shape[:2], dtype=bool)
    for (a, b), aus in zip(dd.BLOCOS_MAO, dd.maos_ausentes(parada)):
        val[:, a:b] = ~aus[:, None]
    assert _np.abs(gcn.com_movimento(parada)[:, :, 2:]).max() > 0.5, \
        "o teste precisa reproduzir o artefato antes de provar que a máscara o corrige"
    assert _np.abs(gcn.com_movimento(parada, val)[:, :, 2:]).max() == 0.0, \
        "com máscara, mão parada não pode ter velocidade"

    # O z do osso pulso(pose)->punho(mão) mede troca de referencial, não anatomia:
    # com --z-recentrado o punho vira z=0 e o pulso segue no referencial do corpo.
    s3 = _np.full((4, v, 3), 0.5, dtype=_np.float32)
    s3[:, :, 2] = 1.0
    o = gcn.com_ossos(dd.recentrar_z(s3))
    assert o[0, gcn.N_POSE, 5] == 0.0 and o[0, gcn.N_POSE + gcn.N_MAO, 5] == 0.0, \
        "o z das ligações pose<->mão deveria ser zerado"
    assert _np.allclose(o[:, :, :2], gcn.com_ossos(dd.recentrar_z(s3))[:, :, :2]), \
        "x e y dos ossos não podem ter mudado"
    _ok("movimento mascarado, adjacência adaptativa, kernel e z das ligações")


def teste_pipeline_real_das_flags() -> None:
    """Exercita o DatasetSinais e a semeadura COMO O TREINO os usa.

    POR QUE ISTO EXISTE. `teste_movimento_e_adjacencia` chama `gcn.com_movimento`
    com a máscara montada à mão — e passava enquanto o pipeline não montava
    máscara nenhuma no treino. O teste estava certo e o produto errado: a
    augmentação soma ruído, o bloco de mão ausente deixa de ser exatamente zero,
    e `maos_ausentes` não achava mais nada. A máscara valia na validação e no
    teste, e não no treino. Testar a função sem testar o caminho que a chama é
    como conferir a chave sem tentar a porta.
    """
    import argparse as _ap
    import numpy as _np
    v = gcn.N_POSE + 2 * gcn.N_MAO

    # 1. Mão ausente + augmentação: velocidade tem de ser zero no bloco ausente.
    seq = _np.ones((20, v, 2), dtype=_np.float32)
    a, b = dd.BLOCOS_MAO[0]
    seq[5:15, a:b, :] = 0.0
    clipes = [dd.Clipe("M01", "x", "01", seq)]
    for aumentar in (False, True):
        ds = tr.DatasetSinais(clipes, ["x"], None, aumentar, semente=1,
                              arquitetura="gcn", ossos=True, movimento=True)
        t, _ = ds[0]
        pico = t[4:, :, a:b].abs().max().item()
        assert pico == 0.0, \
            f"aumentar={aumentar}: mão ausente com velocidade {pico:.4f} — máscara inerte"

    # 2. Pesos pareados: camadas de forma idêntica têm de bater entre variantes.
    def pesos(canais):
        ns = _ap.Namespace(semente=20260916, arquitetura="gcn", com_z=False, ossos=True,
                           movimento=False, adjacencia_adaptativa=False, kernel_temporal=9)
        tr.semear(ns, 1)
        m = gcn.construir(20, canais_ent=canais)
        tr.semear_pesos(m, ns, 1)
        return m.blocos[1].tcn[0].weight.detach().clone()
    assert torch.equal(pesos(4), pesos(8)) and torch.equal(pesos(4), pesos(6)), \
        "variantes com canais diferentes deveriam compartilhar os pesos das camadas de mesma forma"

    # 3. Retomada não pode reaproveitar rodada de OUTRA configuração.
    import json as _json
    with tempfile.TemporaryDirectory() as tmp:
        destino = Path(tmp) / "saida"
        (destino / "rodadas").mkdir(parents=True)
        (destino / "rodadas" / "01-M01.json").write_text(_json.dumps({
            "rodada": 1, "teste": "M01", "validacao": "M02", "acuracia": 0.5,
            "melhor_epoca": 1, "predicoes": [], "verdadeiros": [], "rotulos": ["x"],
            "args": {"ossos": True, "com_z": False, "movimento": False},
        }), encoding="utf-8")
        gravado = {"ossos": True, "com_z": False, "movimento": False}
        atual = {"ossos": True, "com_z": True, "movimento": False}
        assert {k: v for k, v in gravado.items() if k not in tr.IGNORAR_NA_RETOMADA} != \
               {k: v for k, v in atual.items() if k not in tr.IGNORAR_NA_RETOMADA}, \
            "configurações diferentes precisam ser detectadas como diferentes"
        assert "saida" in tr.IGNORAR_NA_RETOMADA and "com_z" not in tr.IGNORAR_NA_RETOMADA
    _ok("pipeline real: máscara sob augmentação, pesos pareados, retomada confere config")


def teste_gcn_ponta_a_ponta() -> None:
    acc = _rodada_sintetica(rotulo_aleatorio=False, arquitetura="gcn")
    assert acc > 0.60, f"GCN em classes separáveis deveria passar de 60%, veio {acc:.1%}"
    _ok(f"treino de ponta a ponta com ST-GCN ({acc:.0%})")


def teste_controle_negativo(arquitetura: str = "resnet") -> None:
    """Rótulo aleatório tem de ficar na chance — senão há vazamento em algum lugar."""
    acc = _rodada_sintetica(rotulo_aleatorio=True, arquitetura=arquitetura)
    assert acc < 0.70, (f"com rótulos aleatórios a acurácia foi {acc:.1%} — alta demais "
                        "para 3 classes; suspeite de vazamento entre treino e teste")
    _ok(f"controle negativo ({arquitetura}): rótulo aleatório ({acc:.0%})")


def teste_gcn_controle_negativo() -> None:
    teste_controle_negativo(arquitetura="gcn")


def teste_checkpoints() -> None:
    """Salvar/carregar preserva arquitetura, configuração, rótulos e predições."""
    rotulos = ["classe0", "classe1", "classe2"]
    with tempfile.TemporaryDirectory() as tmp:
        caminho = Path(tmp) / "modelo.pt"
        casos = [
            ("gcn", gcn.construir(3), torch.randn(2, 2, gcn.T_FIXO, N_PONTOS)),
            ("gcn", gcn.construir(3, largura=16, canais_ent=3, dropout=0.1),
             torch.randn(2, 3, 16, N_PONTOS)),
            ("resnet", mm.construir(3, pretreinado=False), torch.randn(2, 3, 64, 64)),
        ]
        resnet18_original = mm.resnet18

        def resnet_sem_download(**kwargs):
            assert kwargs.get("weights") is None, "carregar não deve baixar pesos ImageNet"
            return resnet18_original(**kwargs)

        for arquitetura, original, entrada in casos:
            original.eval()
            meta = {"origem": "selftest"}
            mm.salvar(original, caminho, rotulos, meta)
            with patch.object(mm, "resnet18", side_effect=resnet_sem_download):
                restaurado, classes, info = mm.carregar(caminho)
            assert type(restaurado) is type(original), "arquitetura alterada ao carregar"
            assert not restaurado.training, "carregar deve devolver modelo em modo eval"
            assert classes == rotulos and info == meta, "rótulos/metadados alterados"
            if arquitetura == "gcn":
                assert restaurado.config == original.config, "configuração GCN perdida"
            with torch.no_grad():
                torch.testing.assert_close(restaurado(entrada), original(entrada))

        # Formato antigo: GCN padrão com arquitetura em meta.args; ResNet sem ela.
        for arquitetura, original, entrada in (casos[0], casos[2]):
            meta = {"args": {"arquitetura": arquitetura}} if arquitetura == "gcn" else {}
            torch.save({"state_dict": original.state_dict(), "rotulos": rotulos,
                        "meta": meta}, caminho)
            with patch.object(mm, "resnet18", side_effect=resnet_sem_download):
                restaurado, classes, info = mm.carregar(caminho)
            assert classes == rotulos and info == meta
            with torch.no_grad():
                torch.testing.assert_close(restaurado(entrada), original(entrada))

        torch.save({"arquitetura": "desconhecida", "rotulos": rotulos}, caminho)
        try:
            mm.carregar(caminho)
        except ValueError as exc:
            assert "arquitetura" in str(exc)
        else:
            raise AssertionError("arquitetura desconhecida deveria falhar explicitamente")
    _ok("checkpoints GCN/ResNet: round-trip, variantes, legado e carga sem download")


def teste_contrastivo() -> None:
    """SupCon premia pares da mesma classe, e a diagonal mascarada não vira NaN."""
    torch.manual_seed(0)
    z = torch.nn.functional.normalize(torch.randn(8, 16), dim=1)
    y = torch.tensor([0, 0, 1, 1, 2, 2, 3, 3])
    aleatorio = ct.perda_supcon(z, y)
    base = torch.nn.functional.normalize(torch.randn(4, 16), dim=1)
    agrupado = ct.perda_supcon(torch.repeat_interleave(base, 2, dim=0), y)
    assert torch.isfinite(aleatorio) and torch.isfinite(agrupado), \
        "perda virou NaN — a diagonal mascarada com -inf multiplicada por 0 faz isso"
    assert agrupado < aleatorio, "pares agrupados deveriam ter perda MENOR"

    # lote sem nenhum par positivo não pode quebrar o treino
    assert torch.isfinite(ct.perda_supcon(z, torch.arange(8)))

    zg = torch.nn.functional.normalize(torch.randn(8, 16), dim=1).requires_grad_(True)
    ct.perda_supcon(zg, y).backward()
    assert torch.isfinite(zg.grad).all(), "gradiente não finito"

    # amostrador precisa garantir K exemplos por classe, senão não há par
    rotulos = [f"c{i // 3}" for i in range(300)]
    am = ct.AmostradorPK(rotulos, p=8, k=2, semente=0)
    indices = list(am)
    for i in range(0, len(indices), 16):
        conta = collections.Counter(rotulos[j] for j in indices[i:i + 16])
        assert sum(v >= 2 for v in conta.values()) == 8, f"lote sem 8 pares: {conta}"
    assert len(ct.AmostradorPK(["a", "a", "b"], p=1, k=2).classes) == 1, \
        "classe com 1 exemplo deveria ser excluída"
    _ok("contrastivo: SupCon, máscara sem NaN e amostrador P×K")


def teste_pretreino_contrastivo_ponta_a_ponta() -> None:
    """Roda pretreinar.py inteiro no modo contrastivo, com dados sintéticos.

    Testar só as funções isoladas não pega o que quebrou de verdade aqui: o
    amostrador prometendo mais índices do que emite, a validação sem pares
    positivos e a seleção de época elegendo um lote vazio. Só o caminho completo
    revela isso.
    """
    import subprocess
    with tempfile.TemporaryDirectory() as tmp:
        destino = Path(tmp) / "corpus"
        # imita a V-LIBRASIL: muitas classes, 3 exemplos, um por pessoa
        from test_proveniencia import criar_corpus
        criar_corpus(destino, n_pessoas=3, n_classes=12, reps=1, seed=3)
        saida = Path(tmp) / "saida"
        r = subprocess.run(
            [sys.executable, str(Path(__file__).parent / "pretreinar.py"),
             "--corpus", str(destino), "--objetivo", "contrastivo",
             "--epocas", "2", "--p-classes", "4", "--k-exemplos", "2",
             "--threads", "2", "--workers", "0", "--dispositivo", "cpu",
             "--saida", str(saida)],
            capture_output=True, text=True, cwd=Path(__file__).parent)
        assert r.returncode == 0, f"pretreinar.py falhou:\n{r.stdout[-1500:]}\n{r.stderr[-1500:]}"
        assert "recuperação(pessoa nova)" in r.stdout, \
            f"validação não usou recuperação entre pessoas:\n{r.stdout[-800:]}"
        ckpt = saida / "backbone_resnet.pt"
        assert ckpt.exists(), "backbone não foi salvo"
        dados = torch.load(ckpt, map_location="cpu", weights_only=False)
        assert not any(k.startswith("fc.") for k in dados["backbone"]), \
            "a cabeça de classificação vazou para o backbone"
        assert dados["meta"]["objetivo"] == "contrastivo"
    _ok("pré-treino contrastivo roda de ponta a ponta e salva backbone limpo")


def teste_imputacao_maos() -> None:
    """Lacuna curta é preenchida com continuidade; lacuna longa fica como ausência.

    O limite existe porque as duas coisas são diferentes: interpolar 3 frames entre
    duas detecções reconstrói o que houve; interpolar 40 inventa uma trajetória que
    ninguém observou. E ausência longa é informação legítima — sinais de uma mão só
    existem.
    """
    seq = np.zeros((30, 57, 2), dtype=np.float32)
    seq[:, :dd.N_POSE, :] = 0.5
    mao = slice(dd.N_POSE, dd.N_POSE + dd.N_MAO)
    for f in range(30):
        seq[f, mao, :] = 1.0 + f * 0.01
    seq[10:13, mao, :] = 0.0     # lacuna curta (3)
    seq[20:30, mao, :] = 0.0     # lacuna longa (10)

    fora = dd.imputar_maos(seq, lacuna_maxima=5)
    ausente = dd.maos_ausentes(fora)[0]
    assert not ausente[10:13].any(), "lacuna curta deveria ter sido preenchida"
    assert ausente[20:30].all(), "lacuna longa NÃO deveria ser inventada"

    # o preenchimento tem de ficar entre os vizinhos, e ser monotônico aqui
    v = fora[10:13, dd.N_POSE, 0]
    assert seq[9, dd.N_POSE, 0] < v[0] <= v[-1] < seq[13, dd.N_POSE, 0], \
        f"interpolação fora do intervalo dos vizinhos: {v}"

    # a pose não pode ser tocada — só as mãos
    assert np.allclose(fora[:, :dd.N_POSE, :], seq[:, :dd.N_POSE, :]), \
        "imputação alterou pontos de pose"

    # clipe sem nenhuma detecção não pode quebrar nem inventar dados
    vazio = np.zeros((10, 57, 2), dtype=np.float32)
    assert dd.maos_ausentes(dd.imputar_maos(vazio))[0].all()
    _ok("imputação: lacuna curta preenchida, longa preservada, pose intacta")


def teste_isolamento_proveniencia() -> None:
    from test_proveniencia import executar
    executar()
    _ok("pré-treino: isolamento, cadeia de origem e proveniência de checkpoint")


TESTES = [
    ("Skeleton-DML (representação)", teste_representacao),
    ("espelhamento esquerda/direita", teste_espelho),
    ("augmentação", teste_augmentacao),
    ("partições leave-one-signer-out", teste_particoes),
    ("imputação de mãos ausentes", teste_imputacao_maos),
    ("treino de ponta a ponta", teste_treino_ponta_a_ponta),
    ("grafo do esqueleto (ST-GCN)", teste_grafo_conectado),
    ("vetores de osso (two-stream)", teste_ossos),
    ("terceira coordenada (x,y,z)", teste_terceira_coordenada),
    ("movimento, adjacência adaptativa, kernel", teste_movimento_e_adjacencia),
    ("pipeline real das flags novas", teste_pipeline_real_das_flags),
    ("treino de ponta a ponta com ST-GCN", teste_gcn_ponta_a_ponta),
    ("controle negativo (rótulo aleatório)", teste_controle_negativo),
    ("contrastivo: perda e amostrador", teste_contrastivo),
    ("contrastivo: pré-treino ponta a ponta", teste_pretreino_contrastivo_ponta_a_ponta),
    ("controle negativo ST-GCN", teste_gcn_controle_negativo),
    ("checkpoints GCN e ResNet", teste_checkpoints),
    ("isolamento e proveniência", teste_isolamento_proveniencia),
]


def main() -> int:
    torch.set_num_threads(4)
    torch.manual_seed(0)
    print("[selftest] pipeline de treino — dados sintéticos, sem dataset real")
    falhas = 0
    for nome, fn in TESTES:
        try:
            fn()
        except Exception:
            falhas += 1
            print(f"  ✗ {nome}")
            for linha in traceback.format_exc().splitlines():
                print(f"    {linha}")
    if falhas:
        print(f"\n[selftest] {falhas} de {len(TESTES)} testes falharam.")
        return 1
    print("\n[selftest] tudo OK — o pipeline de treino está coerente.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

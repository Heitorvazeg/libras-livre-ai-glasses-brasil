"""Calibração de confiança a partir de logits de validação salvos por --salvar-evidencias.

POR QUE ISTO EXISTE. O app usa a MENOR confiança da frase contra um limiar fixo
(padrão 0,60) pra decidir se pede repetição. Softmax de rede neural costuma ser
otimista — confiança alta não significa acerto provável — e o limiar certo depende
do modelo, não é um número que se adivinha. Sem medir isso, 0,60 é um chute.

O QUE FAZ. Duas coisas independentes, sobre os MESMOS logits de validação:
  1. Temperature scaling (Guo et al. 2017): um único escalar T que divide os logits
     antes do softmax, ajustado por máxima verossimilhança. T > 1 suaviza a
     confiança (rede excessivamente confiante, o caso comum); T < 1 a acentua.
     Não muda nenhuma predição (T não altera o argmax), só a confiança reportada.
  2. Limiar de aceite: maior cobertura com acurácia dos aceitos >= `acc_minima`,
      medida nos mesmos dados de ajuste. Sem candidato viável, limiar é null.

POR QUE SÓ EM VALIDAÇÃO, NUNCA EM TESTE. Ajustar T ou o limiar olhando o teste
vaza informação do conjunto que deveria medir generalização — o mesmo motivo pelo
qual a seleção de época do LOSO usa validação, não teste. Lemos explicitamente
`validacao`, nunca ajustamos nos logits de `teste`. Conferimos os artefatos contra
o marcador do fold, sem carregar pickle nem exigir o código atual no lugar do
histórico. Hashes conferem consistência, não certificam a veracidade da origem.

POR QUE POOLING DE VÁRIOS FOLDS IMPORTA. Uma pessoa de validação só (~100 clipes,
20 classes) é pouco pra estimar um limiar de aceite estável — um fold isolado é
um começo, não a calibração final. `carregar_pool` aceita quantos arquivos de
evidências quiser, da mesma execução e sem repetir pessoas/IDs de validação.
Esse pool mistura checkpoints: é SOMENTE análise, não calibração do final.
Mesmo um fold isolado reutiliza a validação de seleção da época: métricas de
ajuste NÃO são avaliação independente. Schema 2 registra esse limite e vincula
os bytes das evidências/checkpoints. Export experimental só no mesmo checkpoint.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import torch

import evidencias_loso as ev
from dados import parse_nome


def ler_json(caminho: Path) -> dict:
    """JSON estrito, inclusive overflow numérico (1e400), sem normalizar valores."""
    d = json.loads(Path(caminho).read_text(encoding="utf-8"))
    json.dumps(d, allow_nan=False)
    if not isinstance(d, dict):
        raise ValueError("objeto JSON exigido")
    return d


def validar_temperatura(t: float) -> float:
    if isinstance(t, (bool, np.bool_)) or not isinstance(t, (int, float, np.number)):
        raise ValueError("temperatura deve ser numérica")
    # O consumidor armazena Float: impedir overflow e underflow/subnormal.
    if not np.isfinite(t) or not np.finfo(np.float32).tiny <= t <= np.finfo(np.float32).max:
        raise ValueError("temperatura precisa ser positiva, finita e representável em float32 normal")
    return float(t)


def _matriz(x):
    x = np.asarray(x)
    if (x.ndim != 2 or x.shape[0] == 0 or x.shape[1] < 2
            or x.dtype.kind not in "fiu" or not np.isfinite(x).all()):
        raise ValueError("matriz numérica finita não vazia (N,C>=2) exigida")
    return x.astype(np.float64)


def _alvos(y, z):
    y = np.asarray(y)
    if (y.shape != (len(z),) or y.dtype.kind not in "iu"
            or not ((y >= 0) & (y < z.shape[1])).all()):
        raise ValueError("alvos inteiros (N,) no intervalo de classes exigidos")
    return y.astype(np.int64)


def _probs(p, y):
    p = _matriz(p)
    y = _alvos(y, p)
    if ((p < 0).any() or (p > 1).any()
            or not np.allclose(p.sum(1), 1., atol=1e-6, rtol=0)):
        raise ValueError("probabilidades devem somar 1 e estar em [0,1]")
    return p, y


def _ler_fold(caminho):
    """Confere evidência/marker/checkpoint, sem executar desserialização de pesos."""
    caminho = Path(caminho).resolve()
    if caminho.parent.name != "artefatos" or not caminho.name.endswith(".evidencias.json"):
        raise ValueError("exige artefatos/NN-Pessoa.evidencias.json e seu marcador LOSO")
    marcador = caminho.parent.parent / caminho.name.replace(".evidencias.json", ".json")
    d, reg = ler_json(caminho), ler_json(marcador)
    try:
        rotulos = d["rotulos"]
        if (not isinstance(rotulos, list) or len(rotulos) < 2
                or any(not isinstance(s, str) or not s for s in rotulos)
                or len(rotulos) != len(set(rotulos))):
            raise ValueError("rótulos únicos não vazios exigidos")
        part = d["particao"]
        if part["metodo"] != "loso":
            raise ValueError("exige partição LOSO")
        for g in ("treino", "validacao", "teste"):
            ids, pessoas = part["ids"][g], part["pessoas"][g]
            if (not ids or len(set(ids)) != len(ids) or not pessoas
                    or len(set(pessoas)) != len(pessoas)):
                raise ValueError("partição vazia ou duplicada")
            if g != "treino" and len(pessoas) != 1:
                raise ValueError("fold deve reservar uma pessoa por split")
            nomes = [parse_nome(Path(i).stem) for i in ids]
            if (set(p for p, _, _ in nomes) != set(pessoas)
                    or any(s not in rotulos for _, s, _ in nomes)):
                raise ValueError("IDs/pessoas/rótulos incompatíveis")
            if g != "treino":
                z = _matriz(d[g]["logits"])
                y = _alvos(d[g]["verdadeiros"], z)
                if [rotulos[int(a)] for a in y] != [s for _, s, _ in nomes]:
                    raise ValueError("alvos divergem dos sinais dos IDs")
        for a, b in (("treino", "validacao"), ("treino", "teste"), ("validacao", "teste")):
            if set(part["pessoas"][a]) & set(part["pessoas"][b]):
                raise ValueError("pessoas sobrepostas entre splits")
        ctx_hash = ev.mm.pv.hash_json(d["contexto"])
        args_identidade = {k: v for k, v in reg["args"].items()
                   if k not in {"saida", "folds", "landmarks", "inicializar"}}
        if (d["contexto"].get("schema") != 1 or d["contexto"]["args"] != args_identidade
                or reg["args"].get("final") is not False
                or type(d["melhor_epoca"]) is not int
                or not 1 <= d["melhor_epoca"] <= reg["args"]["epocas"]):
            raise ValueError("contexto/época incompatível")
        ev.conferir_retomada(marcador, reg, {"sha256": ctx_hash}, part, rotulos)
        ckpt, saidas = ev.artefatos_fold(marcador)
        if saidas.resolve() != caminho:
            raise ValueError("caminho das evidências divergente")
        identidade = {"evidencias_sha256": reg["evidencias"]["saidas"]["sha256"],
                      "checkpoint_sha256": reg["evidencias"]["checkpoint"]["sha256"],
                      "marcador_sha256": ev.mm.pv.hash_arquivo(marcador),
                      "contexto_sha256": ctx_hash,
                      "particao_sha256": ev.mm.pv.hash_json(part),
                      "pessoas_validacao": part["pessoas"]["validacao"]}
        caminhos = {"evidencias": str(caminho), "marcador": str(marcador),
                    "checkpoint": str(ckpt.resolve())}
        return d, identidade, caminhos
    except (KeyError, TypeError, IndexError, SystemExit) as exc:
        raise ValueError(f"{caminho}: evidências LOSO inválidas ({exc})") from exc


def _pool(caminhos):
    if not caminhos:
        raise ValueError("nenhum arquivo de evidências fornecido")
    zs, ys, fontes, locais = [], [], [], []
    rotulos, contexto = None, None
    hashes, modelos, ids, pessoas = set(), set(), set(), set()
    for caminho in caminhos:
        d, ident, local = _ler_fold(caminho)
        if rotulos is not None and d["rotulos"] != rotulos:
            raise ValueError("rótulos divergem do primeiro arquivo do pool")
        if contexto is not None and ident["contexto_sha256"] != contexto:
            raise ValueError("pool mistura contextos/receitas/representações")
        novos_ids = set(d["validacao"]["ids"])
        novas_pessoas = set(ident["pessoas_validacao"])
        if (ident["evidencias_sha256"] in hashes or ident["checkpoint_sha256"] in modelos
                or ids & novos_ids or pessoas & novas_pessoas):
            raise ValueError("evidências, checkpoint, IDs ou pessoas de validação duplicados")
        hashes.add(ident["evidencias_sha256"]); modelos.add(ident["checkpoint_sha256"])
        ids.update(novos_ids); pessoas.update(novas_pessoas)
        rotulos, contexto = d["rotulos"], ident["contexto_sha256"]
        zs.append(_matriz(d["validacao"]["logits"]))
        ys.append(_alvos(d["validacao"]["verdadeiros"], zs[-1]))
        fontes.append(ident); locais.append(local)
    return np.concatenate(zs), np.concatenate(ys), rotulos, fontes, locais

def carregar_pool(caminhos: list[Path]) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """API de análise: concatena apenas validação de bundles LOSO conferidos."""
    return _pool(caminhos)[:3]


def ajustar_temperatura(logits: np.ndarray, alvos: np.ndarray, iteracoes: int = 200) -> float:
    """T que minimiza a log-verossimilhança negativa de softmax(logits / T).

    Otimiza em log(T) pra manter T > 0 sem precisar de projeção/clamp a cada
    passo. NLL é convexa em 1/T, não em geral em log(T). A solução numérica
    é conferida contra T=1; não alegamos ótimo global por usar LBFGS.
    """
    logits = _matriz(logits)
    alvos = _alvos(alvos, logits)
    if type(iteracoes) is not int or iteracoes <= 0:
        raise ValueError("iteracoes deve ser inteiro positivo")
    z = torch.tensor(logits, dtype=torch.float64)
    y = torch.tensor(alvos, dtype=torch.int64)
    log_t = torch.zeros(1, dtype=torch.float64, requires_grad=True)
    inicial = float(torch.nn.functional.cross_entropy(z, y))
    if not np.isfinite(inicial):
        raise ValueError("NLL inicial não finita")
    otim = torch.optim.LBFGS([log_t], lr=0.5, max_iter=iteracoes,
                             line_search_fn="strong_wolfe")

    def passo():
        otim.zero_grad()
        t = torch.exp(log_t)
        perda = torch.nn.functional.cross_entropy(z / t, y)
        if not torch.isfinite(perda):
            raise ValueError("NLL não finita durante ajuste")
        perda.backward()
        if not torch.isfinite(log_t.grad).all():
            raise ValueError("gradiente não finito durante ajuste")
        return perda

    otim.step(passo)
    t_final = float(torch.exp(log_t).item())
    validar_temperatura(t_final)
    final = float(torch.nn.functional.cross_entropy(z / t_final, y))
    if not np.isfinite(final) or final > inicial + 1e-8:
        raise ValueError("ajuste não produziu NLL finita menor ou igual a T=1")
    return t_final


def probabilidades(logits: np.ndarray, temperatura: float = 1.0) -> np.ndarray:
    temperatura = validar_temperatura(temperatura)
    logits = _matriz(logits)
    # Centralizar ANTES de dividir evita +inf-inf; -inf representa exp=0.
    with np.errstate(over="ignore", under="ignore"):
        z = (logits - logits.max(axis=1, keepdims=True)) / temperatura
        exp = np.exp(z)
    return exp / exp.sum(axis=1, keepdims=True)


def ece(probs: np.ndarray, alvos: np.ndarray, n_bins: int = 15) -> float:
    """Expected Calibration Error: |confiança média - acurácia| por faixa de confiança."""
    probs, alvos = _probs(probs, alvos)
    if type(n_bins) is not int or n_bins <= 0:
        raise ValueError("n_bins deve ser inteiro positivo")
    confianca = probs.max(axis=1)
    acertos = (probs.argmax(axis=1) == alvos).astype(np.float64)
    bordas = np.linspace(0.0, 1.0, n_bins + 1)
    total = len(alvos)
    erro = 0.0
    for i in range(n_bins):
        # O último bin inclui a borda superior (confiança == 1.0 é comum).
        na_faixa = (confianca > bordas[i]) & (confianca <= bordas[i + 1]) if i > 0 \
            else (confianca >= bordas[i]) & (confianca <= bordas[i + 1])
        if not na_faixa.any():
            continue
        erro += (na_faixa.sum() / total) * abs(confianca[na_faixa].mean() - acertos[na_faixa].mean())
    return float(erro)


def escolher_limiar(probs: np.ndarray, alvos: np.ndarray, acc_minima: float = 0.90) -> dict:
    """Maior cobertura tal que a acurácia dos aceitos (confiança >= limiar) >= acc_minima.

    Varre só os valores de confiança observados (candidatos a limiar ótimo estão
    sempre nesse conjunto — entre dois valores observados a cobertura não muda).
    Sem candidato viável, devolve None. Não equivale a limiar 1: confiança
    saturada em 1 ainda seria aceita por >=. O export deve recusar esse estado.
    """
    probs, alvos = _probs(probs, alvos)
    if isinstance(acc_minima, bool) or not np.isfinite(acc_minima) or not 0.0 < acc_minima <= 1.0:
        raise ValueError("acc_minima precisa estar em (0, 1]")
    confianca = probs.max(axis=1)
    acertos = probs.argmax(axis=1) == alvos
    candidatos = np.unique(confianca)[::-1]  # do mais alto ao mais baixo: cobertura cresce
    melhor_limiar, melhor_cobertura, melhor_acc = None, 0.0, None
    for limiar in candidatos:
        aceitos = confianca >= limiar
        n = int(aceitos.sum())
        if n == 0:
            continue
        acc = float(acertos[aceitos].mean())
        if acc >= acc_minima:
            melhor_limiar, melhor_cobertura, melhor_acc = float(limiar), n / len(alvos), acc
    return {"limiar_sugerido": melhor_limiar, "cobertura_esperada": melhor_cobertura,
            "acuracia_dos_aceitos": melhor_acc, "meta_nao_atingida": melhor_acc is None}


def calibrar(caminhos_validacao: list[Path], acc_minima: float = 0.90) -> dict:
    """Análise experimental; pool multi-checkpoint nunca é exportável."""
    logits, alvos, rotulos, fontes, locais = _pool(caminhos_validacao)
    temperatura = ajustar_temperatura(logits, alvos)
    probs_antes = probabilidades(logits, 1.0)
    probs_depois = probabilidades(logits, temperatura)
    limiar = escolher_limiar(probs_depois, alvos, acc_minima)
    return {
        "schema": 2, "metodo": "temperature scaling na validação LOSO",
        "escopo": "checkpoint_loso" if len(fontes) == 1 else "pool_loso_analise",
        "avaliacao_independente": False, "aprovado_entrega": False,
        "checkpoint_sha256": fontes[0]["checkpoint_sha256"] if len(fontes) == 1 else None,
        "fontes": fontes, "caminhos_fontes": locais,
        "temperatura": temperatura,
        "limiar_sugerido": limiar["limiar_sugerido"],
        "cobertura_esperada": limiar["cobertura_esperada"],
        "acuracia_dos_aceitos": limiar["acuracia_dos_aceitos"],
        "acc_minima_alvo": acc_minima,
        "meta_nao_atingida": limiar["meta_nao_atingida"],
        "ece_sem_temperatura": ece(probs_antes, alvos),
        "ece_com_temperatura": ece(probs_depois, alvos),
        "n_amostras_validacao": int(len(alvos)),
        "n_folds_validacao": len(caminhos_validacao),
        "metricas_medidas_em": "mesmos_dados_do_ajuste",
        "n_erros_validacao": int((logits.argmax(1) != alvos).sum()),
        "rotulos": rotulos,
        "arquivos_validacao": [str(Path(c).resolve()) for c in caminhos_validacao],
    }


def validar_exportacao(bloco: dict, checkpoint_sha256: str, rotulos: list[str]) -> None:
    """Fail closed: reabre as fontes e confere a política para o MESMO fold.

    Não recalibra nem altera pesos. Não aprova entrega ou aplica limiar no app.
    JSON legado sem identidade exige nova análise, nunca edição dos hashes.
    """
    try:
        json.dumps(bloco, allow_nan=False)
        if (bloco.get("schema") != 2 or bloco.get("escopo") != "checkpoint_loso"
                or bloco.get("avaliacao_independente") is not False
                or bloco.get("aprovado_entrega") is not False):
            raise ValueError("calibração exige schema 2 de um checkpoint LOSO experimental; pool/legado recusado")
        validar_temperatura(bloco["temperatura"])
        if not checkpoint_sha256 or bloco["checkpoint_sha256"] != checkpoint_sha256:
            raise ValueError("checkpoint não corresponde à calibração")
        if bloco["rotulos"] != rotulos:
            raise ValueError("rótulos divergem da calibração")
        if bloco["meta_nao_atingida"] is not False or bloco["limiar_sugerido"] is None:
            raise ValueError("calibração sem limiar viável: exportação recusada")
        locais = bloco["caminhos_fontes"]
        if len(locais) != 1:
            raise ValueError("exige fonte única vinculada ao checkpoint")
        z, y, rs, fontes, caminhos = _pool([Path(locais[0]["evidencias"])])
        if (fontes != bloco["fontes"] or caminhos != locais or rs != rotulos
                or fontes[0]["checkpoint_sha256"] != checkpoint_sha256
                or bloco["n_folds_validacao"] != 1 or bloco["n_amostras_validacao"] != len(y)):
            raise ValueError("origem/contagem da calibração diverge das evidências")
        probs = probabilidades(z, bloco["temperatura"])
        esperado = escolher_limiar(probs, y, bloco["acc_minima_alvo"])
        for k, v in esperado.items():
            if bloco[k] != v:
                raise ValueError(f"política de calibração inconsistente: {k}")
    except (KeyError, TypeError, OSError) as exc:
        raise ValueError(f"calibração incompleta/inacessível: {exc}") from exc


def main() -> None:
    import argparse
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--evidencias", action="append", required=True, type=Path,
                    help="um ou mais *.evidencias.json de folds de VALIDAÇÃO "
                         "(rodadas/artefatos/NN-Pessoa.evidencias.json); nunca de teste")
    ap.add_argument("--acc-minima", type=float, default=0.90,
                    help="acurácia mínima exigida dos sinais aceitos (padrão 0,90)")
    ap.add_argument("--saida", type=Path, required=True)
    args = ap.parse_args()
    if args.saida.exists():
        ap.error("saída já existe; escolha outro caminho para não sobrescrever uma calibração anterior")
    try:
        bloco = calibrar(args.evidencias, args.acc_minima)
    except (ValueError, OSError) as exc:
        ap.error(str(exc))
    if bloco["meta_nao_atingida"]:
        print(f"[calibracao] ⚠ nenhum limiar atinge acurácia >= {args.acc_minima:.0%} "
              "nos dados de validação; sem limiar viável, exportação recusada")
    args.saida.parent.mkdir(parents=True, exist_ok=True)
    with args.saida.open("x", encoding="utf-8") as f:
        f.write(json.dumps(bloco, ensure_ascii=False, indent=2, allow_nan=False))
    print(f"[calibracao] T={bloco['temperatura']:.3f}  "
          f"ECE {bloco['ece_sem_temperatura']:.3f}->{bloco['ece_com_temperatura']:.3f}  "
          f"limiar={bloco['limiar_sugerido']} cobertura_ajuste={bloco['cobertura_esperada']:.1%} "
          f"acc_aceitos={bloco['acuracia_dos_aceitos']}  "
          f"({bloco['n_amostras_validacao']} amostras, {bloco['n_folds_validacao']} fold(s))")
    print("[calibracao] experimental: sem avaliação independente; não aprova modelo final/demo")


if __name__ == "__main__":
    main()

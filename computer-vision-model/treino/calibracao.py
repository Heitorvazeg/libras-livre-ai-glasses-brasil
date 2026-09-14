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
  2. Limiar de aceite: o maior limiar de confiança tal que os sinais ACEITOS (acima
     dele) acertam pelo menos `acc_minima`, medido nos mesmos dados de validação.

POR QUE SÓ EM VALIDAÇÃO, NUNCA EM TESTE. Ajustar T ou o limiar olhando o teste
vaza informação do conjunto que deveria medir generalização — o mesmo motivo pelo
qual a seleção de época do LOSO usa validação, não teste. Quem chamar `calibrar`
decide isso pela escolha de quais evidências passar; este módulo não sabe qual
arquivo é validação ou teste, só soma o que recebe.

POR QUE POOLING DE VÁRIOS FOLDS IMPORTA. Uma pessoa de validação só (~100 clipes,
20 classes) é pouco pra estimar um limiar de aceite estável — um fold isolado é
um começo, não a calibração final. `carregar_pool` aceita quantos arquivos de
evidências quiser; o chamador decide quantos folds estão disponíveis.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import torch


def carregar_pool(caminhos: list[Path]) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """Concatena logits/rótulos de vários *.evidencias.json (mesmo split, ex. validação).

    Falha se os arquivos não concordarem na lista de rótulos — misturar checkpoints
    de execuções diferentes produziria índices de classe sem sentido comum.
    """
    if not caminhos:
        raise ValueError("nenhum arquivo de evidências fornecido")
    logits_partes, alvos_partes, rotulos = [], [], None
    for caminho in caminhos:
        d = json.loads(Path(caminho).read_text(encoding="utf-8"))
        if d.get("schema") != 1:
            raise ValueError(f"{caminho}: schema de evidências desconhecido")
        if rotulos is None:
            rotulos = d["rotulos"]
        elif d["rotulos"] != rotulos:
            raise ValueError(f"{caminho}: rótulos divergem do primeiro arquivo do pool")
        v = d["validacao"]
        logits = np.asarray(v["logits"], dtype=np.float64)
        alvos = np.asarray(v["verdadeiros"], dtype=np.int64)
        if logits.shape != (len(alvos), len(rotulos)) or not np.isfinite(logits).all():
            raise ValueError(f"{caminho}: logits de validação com forma/valor inválido")
        logits_partes.append(logits)
        alvos_partes.append(alvos)
    return np.concatenate(logits_partes), np.concatenate(alvos_partes), rotulos


def ajustar_temperatura(logits: np.ndarray, alvos: np.ndarray, iteracoes: int = 200) -> float:
    """T que minimiza a log-verossimilhança negativa de softmax(logits / T).

    Otimiza em log(T) pra manter T > 0 sem precisar de projeção/clamp a cada
    passo. LBFGS porque é um problema convexo de 1 parâmetro — converge em
    poucas iterações, sem taxa de aprendizado pra ajustar.
    """
    if logits.ndim != 2 or alvos.shape != (logits.shape[0],):
        raise ValueError("logits (N,C) e alvos (N,) exigidos")
    if not ((alvos >= 0) & (alvos < logits.shape[1])).all():
        raise ValueError("alvos fora do intervalo de classes")
    z = torch.tensor(logits, dtype=torch.float64)
    y = torch.tensor(alvos, dtype=torch.int64)
    log_t = torch.zeros(1, dtype=torch.float64, requires_grad=True)
    otim = torch.optim.LBFGS([log_t], lr=0.5, max_iter=iteracoes,
                             line_search_fn="strong_wolfe")

    def passo():
        otim.zero_grad()
        t = torch.exp(log_t)
        perda = torch.nn.functional.cross_entropy(z / t, y)
        perda.backward()
        return perda

    otim.step(passo)
    t_final = float(torch.exp(log_t).item())
    if not np.isfinite(t_final) or t_final <= 0:
        raise ValueError(f"ajuste de temperatura divergiu: T={t_final}")
    return t_final


def probabilidades(logits: np.ndarray, temperatura: float = 1.0) -> np.ndarray:
    if temperatura <= 0:
        raise ValueError("temperatura precisa ser positiva")
    z = logits / temperatura
    z = z - z.max(axis=1, keepdims=True)
    exp = np.exp(z)
    return exp / exp.sum(axis=1, keepdims=True)


def ece(probs: np.ndarray, alvos: np.ndarray, n_bins: int = 15) -> float:
    """Expected Calibration Error: |confiança média - acurácia| por faixa de confiança."""
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
    Sem nenhum candidato que atinja acc_minima, devolve limiar 1.0 (aceita nada) e
    avisa: é sinal de que a rede não está confiável o bastante nesse split.
    """
    if not 0.0 < acc_minima <= 1.0:
        raise ValueError("acc_minima precisa estar em (0, 1]")
    confianca = probs.max(axis=1)
    acertos = probs.argmax(axis=1) == alvos
    candidatos = np.unique(confianca)[::-1]  # do mais alto ao mais baixo: cobertura cresce
    melhor_limiar, melhor_cobertura, melhor_acc = 1.0 + 1e-9, 0.0, None
    for limiar in candidatos:
        aceitos = confianca >= limiar
        n = int(aceitos.sum())
        if n == 0:
            continue
        acc = float(acertos[aceitos].mean())
        if acc >= acc_minima:
            melhor_limiar, melhor_cobertura, melhor_acc = float(limiar), n / len(alvos), acc
    return {"limiar_sugerido": min(melhor_limiar, 1.0), "cobertura_esperada": melhor_cobertura,
            "acuracia_dos_aceitos": melhor_acc, "meta_nao_atingida": melhor_acc is None}


def calibrar(caminhos_validacao: list[Path], acc_minima: float = 0.90) -> dict:
    """Bloco `calibracao` pronto para o sidecar do exportar.py."""
    logits, alvos, rotulos = carregar_pool(caminhos_validacao)
    temperatura = ajustar_temperatura(logits, alvos)
    probs_antes = probabilidades(logits, 1.0)
    probs_depois = probabilidades(logits, temperatura)
    limiar = escolher_limiar(probs_depois, alvos, acc_minima)
    return {
        "schema": 1, "metodo": "temperature scaling na validação LOSO",
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
        "rotulos": rotulos,
        "arquivos_validacao": [str(Path(c).resolve()) for c in caminhos_validacao],
    }


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
    bloco = calibrar(args.evidencias, args.acc_minima)
    if bloco["meta_nao_atingida"]:
        print(f"[calibracao] ⚠ nenhum limiar atinge acurácia >= {args.acc_minima:.0%} "
              "nos dados de validação; limiar sugerido rejeita tudo")
    args.saida.parent.mkdir(parents=True, exist_ok=True)
    args.saida.write_text(json.dumps(bloco, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[calibracao] T={bloco['temperatura']:.3f}  "
          f"ECE {bloco['ece_sem_temperatura']:.3f}->{bloco['ece_com_temperatura']:.3f}  "
          f"limiar={bloco['limiar_sugerido']:.3f} cobertura={bloco['cobertura_esperada']:.1%} "
          f"acc_aceitos={bloco['acuracia_dos_aceitos']}  "
          f"({bloco['n_amostras_validacao']} amostras, {bloco['n_folds_validacao']} fold(s))")


if __name__ == "__main__":
    main()

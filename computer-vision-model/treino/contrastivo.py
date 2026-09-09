"""Pré-treino contrastivo — ensina invariância a sinalizante, não a classificar.

POR QUE ISTO EXISTE. O pré-treino por classificação fracassou na V-LIBRASIL, e o
motivo é estrutural: 1.353 palavras com 3 clipes cada (um por articulador) pede
que o modelo reconheça uma palavra vista duas vezes, numa terceira pessoa. A
validação ficou em 0,2% contra 0,07% de chance — não aprendeu nada. Pior: com 3
clipes de 3 pessoas conhecidas, o caminho mais fácil para reduzir a perda é
memorizar QUEM está sinalizando, que é o oposto do que o produto precisa.

A mesma estrutura que arruína a classificação é ideal para o contrastivo. Três
execuções da mesma palavra por três pessoas diferentes formam exatamente o par
que queremos ensinar:

    "estes clipes são o mesmo sinal, feito por corpos diferentes
     -> aproxime-os no espaço de representação"

Isso É invariância a sinalizante — o requisito central dos óculos, que atendem
alguém novo a cada atendimento. E aprendizado contrastivo não depende de muitos
exemplos por classe: ele aprende de PARES, então 3 por classe basta.

Perda: SupCon (Khosla et al., 2020), que generaliza o NT-Xent para vários
positivos por âncora — necessário aqui, já que cada palavra tem 2 positivos.
"""
from __future__ import annotations

import numpy as np
import torch
from torch import nn
from torch.utils.data import Sampler


class CabecaProjecao(nn.Module):
    """MLP que leva a saída do backbone ao espaço onde a perda é calculada.

    Existe e é DESCARTADA depois: a prática padrão em contrastivo é medir a perda
    num espaço projetado e transferir só o backbone. Sem ela, a última camada do
    backbone fica especializada demais no objetivo contrastivo e transfere pior.
    """

    def __init__(self, entrada: int = 512, oculta: int = 512, saida: int = 128):
        super().__init__()
        self.rede = nn.Sequential(
            nn.Linear(entrada, oculta), nn.ReLU(inplace=True), nn.Linear(oculta, saida))

    def forward(self, x):
        # Normalizar na esfera unitária: a perda usa similaridade de cosseno, e
        # sem isso a magnitude do vetor viraria um atalho para reduzir a perda.
        return nn.functional.normalize(self.rede(x), dim=1)


class AmostradorPK(Sampler):
    """Monta lotes com P classes × K exemplos — sem isso não há pares positivos.

    Amostragem aleatória num corpus de 1.353 classes quase nunca colocaria dois
    clipes da mesma palavra no mesmo lote, e a perda contrastiva não teria com o
    que trabalhar. Este amostrador garante K exemplos de cada classe sorteada.
    """

    def __init__(self, rotulos: list[str], p: int = 32, k: int = 2, semente: int = 0):
        self.k = k
        self.p = p
        self.rng = np.random.default_rng(semente)
        self.por_classe: dict[str, list[int]] = {}
        for i, r in enumerate(rotulos):
            self.por_classe.setdefault(r, []).append(i)
        # Classes com menos de K exemplos não formam par: ficam de fora, com aviso.
        descartadas = [c for c, idx in self.por_classe.items() if len(idx) < k]
        if descartadas:
            print(f"[contrastivo] {len(descartadas)} classe(s) com < {k} clipes "
                  f"ficaram fora dos lotes (não formam par positivo)")
            for c in descartadas:
                del self.por_classe[c]
        self.classes = list(self.por_classe)
        self.lotes_por_epoca = max(len(self.classes) // p, 1)

    def __len__(self) -> int:
        return self.lotes_por_epoca * self.p * self.k

    def __iter__(self):
        classes = self.rng.permutation(self.classes)
        for b in range(self.lotes_por_epoca):
            for c in classes[b * self.p:(b + 1) * self.p]:
                idx = self.por_classe[c]
                escolha = self.rng.choice(idx, size=self.k,
                                          replace=len(idx) < self.k)
                yield from (int(i) for i in escolha)


def perda_supcon(z: torch.Tensor, rotulos: torch.Tensor,
                 temperatura: float = 0.07) -> torch.Tensor:
    """SupCon: aproxima exemplos da mesma classe, afasta os das demais.

    `z` já vem normalizado, então z @ z.T é a similaridade de cosseno. A
    temperatura controla o quanto a perda pune negativos difíceis; 0,07 é o valor
    padrão da literatura.
    """
    n = z.size(0)
    sim = z @ z.t() / temperatura
    # A diagonal é a similaridade do exemplo consigo mesmo (=1/T); precisa sair,
    # senão domina o denominador e o gradiente vira ruído.
    mascara_self = torch.eye(n, dtype=torch.bool, device=z.device)
    sim = sim.masked_fill(mascara_self, float("-inf"))

    positivos = (rotulos.unsqueeze(0) == rotulos.unsqueeze(1)) & ~mascara_self
    n_pos = positivos.sum(1)
    if not (n_pos > 0).any():
        return z.sum() * 0.0  # lote sem nenhum par: nada a aprender

    log_prob = sim - torch.logsumexp(sim, dim=1, keepdim=True)
    # torch.where em vez de multiplicar pela máscara: a diagonal de `log_prob` é
    # -inf (mascarada acima) e a máscara de positivos é 0 ali — o produto
    # -inf * 0 dá NaN e envenena o lote inteiro em silêncio.
    contrib = torch.where(positivos, log_prob, torch.zeros_like(log_prob))
    validos = n_pos > 0
    perda = -contrib.sum(1)[validos] / n_pos[validos]
    return perda.mean()

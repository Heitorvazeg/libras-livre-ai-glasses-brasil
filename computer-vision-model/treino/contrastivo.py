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

    PoC (branch poc/contrastivo-negativos-extras): `negativos_extras` recicla
    parte do que normalmente é descartado. Uma classe com < K clipes nunca pode
    ancorar um par positivo, mas `perda_supcon` já ignora corretamente qualquer
    linha sem positivo (`n_pos > 0`) — ela só entra no DENOMINADOR de log-softmax
    das outras âncoras do lote. Ou seja: incluir esses clipes como negativo extra
    não muda a matemática da perda em nada, só aproveita amostras que hoje ficam
    inertes. Cada classe descartada é distinta de todas as outras (por construção
    — cada rótulo cai em um único balde), então nunca cria par positivo espúrio
    entre si nem com as classes elegíveis do lote.
    """

    def __init__(self, rotulos: list[str], p: int = 32, k: int = 2, semente: int = 0,
                negativos_extras: int = 0):
        self.k = k
        self.negativos_extras = negativos_extras
        self.rng = np.random.default_rng(semente)
        self.por_classe: dict[str, list[int]] = {}
        for i, r in enumerate(rotulos):
            self.por_classe.setdefault(r, []).append(i)
        # Classes com menos de K exemplos não formam par: ficam de fora, com aviso.
        descartadas = [c for c, idx in self.por_classe.items() if len(idx) < k]
        # Snapshot ANTES de apagar: cada classe descartada empresta seus (< K,
        # geralmente 1) clipes ao pool de negativos extras.
        self._extras = [i for c in descartadas for i in self.por_classe[c]]
        if descartadas:
            print(f"[contrastivo] {len(descartadas)} classe(s) com < {k} clipes "
                  f"ficaram fora dos lotes (não formam par positivo)"
                  + (f"; {len(self._extras)} clipe(s) reaproveitado(s) como negativo extra"
                     if negativos_extras else ""))
            for c in descartadas:
                del self.por_classe[c]
        self.classes = list(self.por_classe)
        if not self.classes:
            raise SystemExit(f"nenhuma classe com >= {k} clipes — o contrastivo não tem "
                             "nenhum par positivo para aprender")
        if negativos_extras and not self._extras:
            raise SystemExit(f"negativos_extras={negativos_extras} pedido, mas nenhuma "
                             f"classe teve < {k} clipes para fornecer negativos extras")
        # P não pode exceder o nº de classes: pedir 32 classes quando só existem 2
        # fazia __len__ prometer 64 índices e __iter__ emitir 4, e o DataLoader com
        # drop_last=True descartava o lote incompleto — treino sem nenhum passo.
        self.p = min(p, len(self.classes))
        self.lotes_por_epoca = max(len(self.classes) // self.p, 1)

    def __len__(self) -> int:
        # Precisa bater exatamente com o que __iter__ emite: o DataLoader
        # dimensiona o laço por aqui, e a divergência aparece como lote faltando.
        return self.lotes_por_epoca * (self.p * self.k + self.negativos_extras)

    def __iter__(self):
        classes = self.rng.permutation(self.classes)
        extras_ordem = np.array([], dtype=int)
        if self.negativos_extras:
            # Pool cíclico: embaralha o pool inteiro e repete a ordem (com nova
            # permutação a cada volta) se a época pedir mais negativos do que o
            # pool tem — mantém exatamente `negativos_extras` por lote, sempre.
            necessarios = self.lotes_por_epoca * self.negativos_extras
            partes = []
            enquanto = 0
            while enquanto < necessarios:
                partes.append(self.rng.permutation(self._extras))
                enquanto += len(self._extras)
            extras_ordem = np.concatenate(partes)[:necessarios]
        cursor = 0
        for b in range(self.lotes_por_epoca):
            for c in classes[b * self.p:(b + 1) * self.p]:
                idx = self.por_classe[c]
                escolha = self.rng.choice(idx, size=self.k,
                                          replace=len(idx) < self.k)
                yield from (int(i) for i in escolha)
            if self.negativos_extras:
                yield from (int(i) for i in extras_ordem[cursor:cursor + self.negativos_extras])
                cursor += self.negativos_extras


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


def acuracia_recuperacao(emb_consulta: torch.Tensor, rot_consulta: list[str],
                         emb_galeria: torch.Tensor, rot_galeria: list[str]) -> float:
    """Top-1: o vizinho mais próximo na galeria é o MESMO sinal?

    POR QUE ESTA MÉTRICA, E NÃO A PRÓPRIA PERDA, PARA ESCOLHER A ÉPOCA.
    Usar SupCon na validação não funciona aqui, e o problema é silencioso: numa
    divisão aleatória de um corpus com 3 clipes por classe, quase nenhum lote de
    validação contém dois clipes da mesma palavra. Medido no corpus real: 12 de
    406 clipes (3%) tinham par, e um lote em sete não tinha nenhum — e um lote sem
    pares devolve perda 0.0, que a seleção de época elegeria como a MELHOR de
    todas. O checkpoint escolhido seria o de um lote vazio.

    Recuperação não tem esse problema e mede exatamente o que o pré-treino
    contrastivo deve ensinar: com a consulta vinda de um articulador reservado e a
    galeria dos articuladores de treino, ela pergunta "reconheço este sinal feito
    por uma pessoa que não vi treinando?" — que é o requisito do produto.
    """
    if emb_consulta.numel() == 0 or emb_galeria.numel() == 0:
        return 0.0
    vizinho = (emb_consulta @ emb_galeria.t()).argmax(dim=1)
    acertos = sum(rot_galeria[int(j)] == r for j, r in zip(vizinho, rot_consulta))
    return acertos / len(rot_consulta)

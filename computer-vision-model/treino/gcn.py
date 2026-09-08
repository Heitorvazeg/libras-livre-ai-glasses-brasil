"""ST-GCN — o esqueleto tratado como grafo, não como imagem.

POR QUE EXISTE, ao lado do Skeleton-DML. A crítica válida à representação em
imagem é que o eixo vertical dela é arbitrário: "ponto 5" estar ao lado de
"ponto 6" é acidente de ordenação da lista, não anatomia. A convolução assume
que vizinhos têm relação, e ali eles não têm. O GCN corrige isso na raiz — a
vizinhança é a ANATOMIA (o cotovelo é vizinho do ombro e do pulso, ponto), e a
convolução caminha por essas arestas.

Duas motivações que interessam ao produto, ainda a validar:
  - modelo bem menor que uma ResNet-18, o que importa no `.tflite` do celular;
    - o grafo permite explorar vetores de ossos/ângulos para lidar com mudanças
        de ponto de vista. Esta versão usa somente coordenadas x/y: não implementa
        essas features nem garante invariância ao ângulo da câmera.

O modelo atual classifica clipes completos; não é causal e não implementa
atenção dependente da entrada. A exportação TFLite também permanece pendente.

O QUE AINDA NÃO SABEMOS: não há, até onde a pesquisa foi, número publicado de
GCN no MINDS-Libras com protocolo leave-one-signer-out. Este módulo existe para
medir isso contra o nosso próprio baseline, na mesma régua — não por suposição
de que grafo é melhor.

Arquitetura: ST-GCN compacto (Yan et al., 2018), com particionamento por
distância (K=2: o próprio nó e os vizinhos imediatos) e máscara de importância
de aresta aprendida. Pequeno de propósito: são 800 clipes, e GCN treinado do
zero é faminto por dado — capacidade sobrando aqui vira memorização, igual ao
que já observamos com as ResNets maiores.
"""
from __future__ import annotations

import numpy as np
import torch
from torch import nn

# Layout do vetor de pontos, na ordem que extract.py monta:
#   0-14  pose (config.pose_indices)
#   15-35 mão esquerda (21)
#   36-56 mão direita (21)
N_POSE = 15
N_MAO = 21
PULSO_ESQ, PULSO_DIR = 11, 12  # índices dentro do bloco de pose
T_FIXO = 64  # frames após reamostragem temporal

# Topologia padrão dos 21 pontos de mão do MediaPipe (dedos + palma).
_ARESTAS_MAO = [
    (0, 1), (1, 2), (2, 3), (3, 4),        # polegar
    (0, 5), (5, 6), (6, 7), (7, 8),        # indicador
    (0, 9), (9, 10), (10, 11), (11, 12),   # médio
    (0, 13), (13, 14), (14, 15), (15, 16),  # anelar
    (0, 17), (17, 18), (18, 19), (19, 20),  # mínimo
    (5, 9), (9, 13), (13, 17),             # palma
]

# Pose: índices conforme a ORDEM de pose_indices no config.yaml
# 0 nariz · 1 olho_esq · 2 olho_dir · 3 orelha_esq · 4 orelha_dir · 5 boca_esq
# 6 boca_dir · 7 ombro_esq · 8 ombro_dir · 9 cotovelo_esq · 10 cotovelo_dir
# 11 pulso_esq · 12 pulso_dir · 13 quadril_esq · 14 quadril_dir
_ARESTAS_POSE = [
    (0, 1), (0, 2), (1, 3), (2, 4),        # nariz-olhos-orelhas
    (0, 5), (0, 6), (5, 6),                # nariz-boca
    (0, 7), (0, 8),                        # cabeça-ombros (aproxima o pescoço)
    (7, 8),                                # ombros
    (7, 9), (9, 11),                       # braço esquerdo
    (8, 10), (10, 12),                     # braço direito
    (7, 13), (8, 14), (13, 14),            # tronco
]


def arestas(n_pose: int = N_POSE, n_mao: int = N_MAO) -> list[tuple[int, int]]:
    """Arestas do grafo completo: pose + duas mãos + a ligação punho<->mão.

    A ligação punho-mão é o que torna o grafo CONECTADO. Sem ela, as mãos
    ficariam três componentes isoladas e a informação do tronco nunca chegaria
    aos dedos — que é justamente o contexto que distingue sinais articulados em
    lugares diferentes do corpo.
    """
    e = list(_ARESTAS_POSE)
    for base in (n_pose, n_pose + n_mao):
        e += [(a + base, b + base) for a, b in _ARESTAS_MAO]
    e.append((PULSO_ESQ, n_pose))              # pulso da pose <-> punho da mão esq
    e.append((PULSO_DIR, n_pose + n_mao))      # idem, direita
    return e


def adjacencia(n_nos: int, lista_arestas: list[tuple[int, int]]) -> torch.Tensor:
    """(2, V, V): subconjunto 0 = o próprio nó, subconjunto 1 = vizinhos imediatos.

    Cada subconjunto é normalizado por grau (A · D⁻¹). Sem isso, um nó muito
    conectado (o punho, ligado a cinco dedos e ao braço) dominaria a soma só por
    ter mais vizinhos.
    """
    a = np.zeros((n_nos, n_nos), dtype=np.float32)
    for i, j in lista_arestas:
        a[i, j] = a[j, i] = 1.0

    def normalizar(m: np.ndarray) -> np.ndarray:
        grau = m.sum(axis=0)
        inv = np.divide(1.0, grau, out=np.zeros_like(grau), where=grau > 0)
        return m @ np.diag(inv)

    return torch.from_numpy(np.stack([normalizar(np.eye(n_nos, dtype=np.float32)),
                                      normalizar(a)]))


def para_sequencia(seq: np.ndarray, t_fixo: int = T_FIXO) -> np.ndarray:
    """(T, V, 2) -> (2, t_fixo, V), reamostrando o tempo por interpolação linear.

    O GCN precisa de comprimento fixo para formar lote. Reamostrar (em vez de
    preencher com zeros) mantém o sinal inteiro representado — só muda a
    resolução temporal, do mesmo jeito que o redimensionamento fazia na
    representação em imagem.
    """
    t = seq.shape[0]
    if t == t_fixo:
        saida = seq
    else:
        origem = np.linspace(0.0, 1.0, t)
        destino = np.linspace(0.0, 1.0, t_fixo)
        saida = np.empty((t_fixo, seq.shape[1], seq.shape[2]), dtype=np.float32)
        for v in range(seq.shape[1]):
            for c in range(seq.shape[2]):
                saida[:, v, c] = np.interp(destino, origem, seq[:, v, c])
    return np.ascontiguousarray(saida.transpose(2, 0, 1), dtype=np.float32)


class _ConvGrafo(nn.Module):
    """Convolução espacial: mistura cada nó com seus vizinhos no grafo."""

    def __init__(self, canais_ent: int, canais_sai: int, k: int):
        super().__init__()
        self.k = k
        self.conv = nn.Conv2d(canais_ent, canais_sai * k, kernel_size=1)

    def forward(self, x: torch.Tensor, a: torch.Tensor) -> torch.Tensor:
        x = self.conv(x)
        n, kc, t, v = x.size()
        x = x.view(n, self.k, kc // self.k, t, v)
        # soma sobre os subconjuntos de adjacência, propagando pelas arestas
        return torch.einsum("nkctv,kvw->nctw", x, a).contiguous()


class _BlocoSTGCN(nn.Module):
    """Convolução no grafo (espaço) seguida de convolução no tempo."""

    def __init__(self, canais_ent: int, canais_sai: int, k: int,
                 passo: int = 1, kernel_t: int = 9, dropout: float = 0.3):
        super().__init__()
        self.gcn = _ConvGrafo(canais_ent, canais_sai, k)
        self.bn_g = nn.BatchNorm2d(canais_sai)
        self.tcn = nn.Sequential(
            nn.Conv2d(canais_sai, canais_sai, (kernel_t, 1),
                      stride=(passo, 1), padding=((kernel_t - 1) // 2, 0)),
            nn.BatchNorm2d(canais_sai),
            nn.Dropout(dropout),
        )
        if canais_ent == canais_sai and passo == 1:
            self.residual = nn.Identity()
        else:
            self.residual = nn.Sequential(
                nn.Conv2d(canais_ent, canais_sai, 1, stride=(passo, 1)),
                nn.BatchNorm2d(canais_sai),
            )
        self.relu = nn.ReLU()

    def forward(self, x: torch.Tensor, a: torch.Tensor) -> torch.Tensor:
        res = self.residual(x)
        x = self.relu(self.bn_g(self.gcn(x, a)))
        return self.relu(self.tcn(x) + res)


class STGCN(nn.Module):
    """ST-GCN compacto para sinais isolados.

    ~0,4M parâmetros contra ~11M da ResNet-18 — a diferença que importa para o
    `.tflite` no celular.
    """

    def __init__(self, num_classes: int, n_nos: int = N_POSE + 2 * N_MAO,
                 canais_ent: int = 2, largura: int = 64, dropout: float = 0.3):
        super().__init__()
        # Necessário para reconstruir também variantes não padrão do checkpoint.
        self.config = {
            "n_nos": n_nos, "canais_ent": canais_ent,
            "largura": largura, "dropout": dropout,
        }
        a = adjacencia(n_nos, arestas())
        self.register_buffer("a", a)
        # Importância de aresta aprendida: o grafo anatômico é o ponto de
        # partida, não a palavra final — o treino pode enfraquecer ligações que
        # não ajudam e reforçar as que distinguem sinais.
        self.importancia = nn.Parameter(torch.ones(a.size()))
        self.bn_entrada = nn.BatchNorm1d(canais_ent * n_nos)

        k = a.size(0)
        self.blocos = nn.ModuleList([
            _BlocoSTGCN(canais_ent, largura, k, dropout=dropout),
            _BlocoSTGCN(largura, largura, k, dropout=dropout),
            _BlocoSTGCN(largura, largura * 2, k, passo=2, dropout=dropout),
            _BlocoSTGCN(largura * 2, largura * 2, k, passo=2, dropout=dropout),
        ])
        self.fc = nn.Linear(largura * 2, num_classes)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: (N, C, T, V)
        n, c, t, v = x.size()
        x = self.bn_entrada(x.permute(0, 1, 3, 2).reshape(n, c * v, t))
        x = x.view(n, c, v, t).permute(0, 1, 3, 2).contiguous()

        a = self.a * self.importancia
        for bloco in self.blocos:
            x = bloco(x, a)

        x = torch.mean(x, dim=(2, 3))  # média sobre tempo e nós
        return self.fc(x)


def construir(num_classes: int, **kwargs) -> nn.Module:
    return STGCN(num_classes, **kwargs)

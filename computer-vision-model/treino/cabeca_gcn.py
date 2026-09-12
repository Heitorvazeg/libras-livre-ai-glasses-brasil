"""O pré-processamento do ST-GCN reescrito em torch, para caber dentro do `.tflite`.

POR QUE ESTE ARQUIVO EXISTE. Entre os landmarks que o app extrai e o que o ST-GCN
consome há quatro transformações que hoje acontecem em numpy, no `DatasetSinais`
e no `dados.carregar`:

    recentrar z das mãos -> imputar lacunas curtas -> ossos -> reamostrar o tempo

Se elas ficarem fora do grafo, o app precisa reimplementá-las em Kotlin. Cada uma
é uma chance de divergir do treino **em silêncio**: o modelo não recusa entrada
mal preparada, ele só erra mais, e nada denuncia. É o mesmo argumento que já fez
o modo `landmarks` do `exportar.py` ser o padrão em vez do modo `imagem`.

A ORDEM IMPORTA E É ESTA. É a mesma do treino: `dados.carregar` aplica recentragem
e imputação na leitura; `DatasetSinais.__getitem__` aplica ossos e, por último, a
reamostragem. Trocar ossos e reamostragem de lugar mudaria o resultado — osso de
sequência reamostrada não é reamostragem de osso.

O QUE CADA UMA ESPELHA, LINHA A LINHA:

    RecentrarZ    <- dados.recentrar_z
    ImputarMaos   <- dados.imputar_maos  (+ dados.maos_ausentes)
    ComOssos      <- gcn.com_ossos       (+ gcn.pais)
    ComMovimento  <- gcn.com_movimento
    ParaSequencia <- gcn.para_sequencia

`teste_cabeca_gcn.py` compara cada uma contra a versão numpy em dado aleatório e
em dado com mãos ausentes. Sem esse teste, exportaríamos um pré-processamento que
ninguém treinou.

A IMPUTAÇÃO É A ÚNICA COM LÓGICA DEPENDENTE DE DADO. O numpy percorre pares de
detecções consecutivas e interpola só quando a lacuna cabe no limite. Laço com
`if` não sobrevive a conversor. A versão aqui é a mesma regra escrita sem
ramificação: para cada quadro, qual a detecção anterior, qual a seguinte, qual o
tamanho da lacuna entre elas — tudo por `cummax` sobre índices — e a interpolação
entra multiplicada por uma máscara. Resultado idêntico, sem controle de fluxo.
"""
from __future__ import annotations

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

import dados as dd
import gcn as gg


class RecentrarZ(nn.Module):
    """Devolve o z de cada mão ao referencial do próprio punho (dados.recentrar_z)."""

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        # seq: (N, T, V, C)
        if seq.shape[-1] < 3:
            return seq
        saida = seq.clone()
        for a, b in dd.BLOCOS_MAO:
            saida[..., a:b, 2] = seq[..., a:b, 2] - seq[..., a:a + 1, 2]
        return saida


class ImputarMaos(nn.Module):
    """Interpola lacunas curtas de mão não detectada (dados.imputar_maos).

    Sem laço e sem `if`: a mesma regra escrita como máscara. Para cada quadro t e
    cada bloco de mão, `ant` é o índice da última detecção até t e `prox` o da
    primeira a partir de t. A lacuna entre duas detecções que cercam t mede
    `prox - ant - 1`, exatamente o `vao` do numpy, e só quadros cuja lacuna cabe
    no limite recebem o valor interpolado.
    """

    def __init__(self, lacuna_maxima: int = 5):
        super().__init__()
        self.lacuna_maxima = lacuna_maxima

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        n, t, v, c = seq.shape
        saida = seq.clone()
        idx = torch.arange(t, device=seq.device)
        for a, b in dd.BLOCOS_MAO:
            # Ausência é o bloco EXATAMENTE zerado em x,y — mesmo critério de
            # dados.maos_ausentes, que olha só x,y de propósito (com z recentrado
            # o punho vira 0 por construção e contaminaria o teste).
            presente = seq[:, :, a:b, :2].abs().sum(dim=(2, 3)) != 0      # (N, T)

            # `cummax` seria o natural aqui, e foi o que escrevi primeiro — mas o
            # conversor não tem lowering para ela ("Lowering not found:
            # aten.cummax.default"). Com T fixo no grafo, a mesma pergunta —
            # "qual a última detecção até t?" — sai de um máximo mascarado sobre
            # uma matriz T×T triangular, usando só `where` e `amax`. Para T=96 são
            # 9.216 posições por bloco de mão: irrelevante no custo, e composto de
            # operações que qualquer conversor entende.
            ate = idx[None, :] <= idx[:, None]                              # (T, T)
            apos = idx[None, :] >= idx[:, None]
            val_ant = torch.where(presente, idx, torch.full_like(idx, -1))  # (N, T)
            val_prox = torch.where(presente, idx, torch.full_like(idx, t))
            ant = torch.where(ate[None], val_ant[:, None, :],
                              torch.full_like(val_ant[:, None, :], -1)).amax(dim=2)
            prox = torch.where(apos[None], val_prox[:, None, :],
                               torch.full_like(val_prox[:, None, :], t)).amin(dim=2)

            cercado = (ant >= 0) & (prox <= t - 1) & ~presente
            vao = prox - ant - 1
            usar = cercado & (vao > 0) & (vao <= self.lacuna_maxima)       # (N, T)
            # SEM ATALHO AQUI. Um `if not usar.any(): continue` pouparia trabalho
            # quando não há lacuna, mas é desvio que depende do VALOR do tensor, e
            # a exportação morre nele: "Could not guard on data-dependent
            # expression". O `torch.where` abaixo já devolve o bloco intacto
            # quando a máscara é toda falsa — o custo é calcular a interpolação e
            # descartá-la, e é o preço de o grafo ser estático.
            # Índices seguros para o gather mesmo onde `usar` é falso.
            ant_s = ant.clamp(min=0)
            prox_s = prox.clamp(max=t - 1)
            bloco = seq[:, :, a:b, :]                                      # (N, T, M, C)
            g = lambda ix: torch.gather(
                bloco, 1, ix[:, :, None, None].expand(n, t, b - a, c))
            denom = (prox - ant).clamp(min=1).to(seq.dtype)
            peso = ((idx[None, :] - ant).to(seq.dtype) / denom)[:, :, None, None]
            interp = g(ant_s) * (1 - peso) + g(prox_s) * peso
            m = usar[:, :, None, None]
            saida[:, :, a:b, :] = torch.where(m, interp, bloco)
        return saida


class ComOssos(nn.Module):
    """Concatena os vetores de osso aos canais (gcn.com_ossos).

    A árvore `gcn.pais()` é fixa e vira buffer: no grafo exportado o osso é um
    gather com índice constante seguido de subtração.
    """

    def __init__(self):
        super().__init__()
        self.register_buffer("pai", torch.from_numpy(gg.pais().astype(np.int64)))

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        osso = seq - seq[:, :, self.pai, :]
        if seq.shape[-1] >= 3:
            # O z das duas ligações pulso(pose)->punho(mão) mede troca de
            # referencial, não anatomia. Zerar é o que o numpy faz.
            osso = osso.clone()
            osso[:, :, gg.N_POSE, 2] = 0.0
            osso[:, :, gg.N_POSE + gg.N_MAO, 2] = 0.0
        return torch.cat([seq, osso], dim=-1)


class ComMovimento(nn.Module):
    """Concatena a variação temporal dos canais (gcn.com_movimento).

    A máscara de validade do numpy depende de quais nós foram observados. Aqui a
    imputação já rodou antes, então o que sobra ausente são lacunas longas, que
    continuam zeradas — e a diferença entre dois zeros é zero. O primeiro quadro
    não tem anterior e sua variação é zero, como no numpy.
    """

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        d = torch.zeros_like(seq)
        d[:, 1:] = seq[:, 1:] - seq[:, :-1]
        return torch.cat([seq, d], dim=-1)


class ParaSequencia(nn.Module):
    """(N, T, V, C) -> (N, C, t_fixo, V), reamostrando o tempo (gcn.para_sequencia).

    O numpy usa `np.interp` de `linspace(0,1,T)` para `linspace(0,1,t_fixo)`, que
    inclui os dois extremos. O equivalente em torch é `align_corners=True`;
    com `False` as bordas saem deslocadas e a paridade quebra no primeiro e no
    último quadro — que é justamente onde o sinal começa e termina.
    """

    def __init__(self, t_fixo: int = gg.T_FIXO):
        super().__init__()
        self.t_fixo = t_fixo

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        n, t, v, c = seq.shape
        x = seq.permute(0, 2, 3, 1).reshape(n * v * c, 1, t)
        if t != self.t_fixo:
            x = F.interpolate(x, size=self.t_fixo, mode="linear", align_corners=True)
        return x.reshape(n, v, c, self.t_fixo).permute(0, 2, 3, 1).contiguous()


class CabecaGCN(nn.Module):
    """Landmarks crus -> tensor que o STGCN consome, tudo dentro do grafo.

    As flags espelham as do treino (`treinar.canais_gcn`): quem decide é o
    checkpoint, não a configuração atual do projeto. Construir a cabeça com
    ossos quando o checkpoint treinou sem eles daria um modelo que converte,
    roda e classifica errado.
    """

    def __init__(self, z_recentrado: bool = True, imputar: bool = True,
                 ossos: bool = True, movimento: bool = False,
                 lacuna_maxima: int = 5, t_fixo: int = gg.T_FIXO):
        super().__init__()
        self.recentrar = RecentrarZ() if z_recentrado else None
        self.imputar = ImputarMaos(lacuna_maxima) if imputar else None
        self.ossos = ComOssos() if ossos else None
        self.movimento = ComMovimento() if movimento else None
        self.sequencia = ParaSequencia(t_fixo)

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        if seq.ndim != 4:
            raise ValueError(f"esperava (N, T, V, C), veio {tuple(seq.shape)}")
        if self.recentrar is not None:
            seq = self.recentrar(seq)
        if self.imputar is not None:
            seq = self.imputar(seq)
        if self.ossos is not None:
            seq = self.ossos(seq)
        if self.movimento is not None:
            seq = self.movimento(seq)
        return self.sequencia(seq)


class ClassificadorGCN(nn.Module):
    """Landmarks -> logits, com o pré-processamento dentro do grafo."""

    def __init__(self, rede: nn.Module, cabeca: CabecaGCN):
        super().__init__()
        self.cabeca = cabeca
        self.rede = rede

    def forward(self, seq: torch.Tensor) -> torch.Tensor:
        return self.rede(self.cabeca(seq))

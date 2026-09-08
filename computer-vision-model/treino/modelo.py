"""ResNet-18 pré-treinada em ImageNet, com a cabeça trocada pelos nossos sinais.

POR QUE A MENOR DAS RESNETS. Parece contraintuitivo, mas é medido: na ablação de
Alves et al. 2024 sobre MINDS-Libras/LIBRAS-UFOP (protocolo LOPO, o mesmo nosso),
ResNet-18 fica em 0,93/0,82 e os modelos MAIORES pioram — ResNet-50 0,90/0,70,
EfficientNet-B6 0,89/0,78, MobileNetV4 0,87/0,70, ViT-medium empata em 0,93/0,81.
Com ~1.000 clipes, capacidade sobrando vira memorização. E a menor é justamente a
que cabe no celular depois.

A CABEÇA (mesma dos trabalhos de referência): BatchNorm -> 512->128 -> ReLU ->
Dropout(0.5) -> 128->classes. O dropout alto é a defesa contra o dataset pequeno.

O pré-treino do ImageNet é o que carrega o peso do transfer learning aqui. As
imagens de entrada não são fotos — são landmarks empilhados (ver
representacao.py) —, mas os filtros de borda/textura das primeiras camadas
transferem mesmo assim, que é o resultado empírico dos dois papers.
"""
from __future__ import annotations

import torch
from torch import nn
from torchvision.models import ResNet18_Weights, resnet18
from torchvision.models.resnet import ResNet


def construir(num_classes: int, congelar_ate: int = 0, *,
              pretreinado: bool = True) -> nn.Module:
    """ResNet-18 ImageNet com cabeça nova de `num_classes` saídas.

    `congelar_ate`: quantos blocos iniciais manter congelados (0 = fine-tuning
    completo, que é o que os trabalhos de referência fazem). Existe para a etapa
    de fine-tuning no vocabulário de domínio, quando houver pouquíssimo dado
    próprio por classe e congelar o início do backbone evita destruí-lo.
    `pretreinado=False`: reconstrói sem baixar pesos ImageNet, para carregar
    um checkpoint que já contém todos os pesos.
    """
    modelo = resnet18(weights=ResNet18_Weights.IMAGENET1K_V1 if pretreinado else None)

    if congelar_ate:
        blocos = [modelo.conv1, modelo.bn1, modelo.layer1, modelo.layer2,
                  modelo.layer3, modelo.layer4]
        for bloco in blocos[:congelar_ate]:
            for p in bloco.parameters():
                p.requires_grad = False

    n = modelo.fc.in_features
    modelo.fc = nn.Sequential(
        nn.BatchNorm1d(n),
        nn.Linear(n, 128),
        nn.ReLU(),
        nn.Dropout(0.5),
        nn.Linear(128, num_classes),
    )
    return modelo


def salvar(modelo: nn.Module, caminho, rotulos: list[str], meta: dict) -> None:
    """Salva pesos + rótulos + metadados no mesmo arquivo.

    Rótulos junto dos pesos de propósito: um checkpoint que não sabe a ordem das
    classes que ele mesmo prevê é uma armadilha — a inferência acerta o índice e
    erra a palavra.
    """
    from gcn import STGCN

    if isinstance(modelo, STGCN):
        arquitetura, config = "gcn", dict(modelo.config)
    elif isinstance(modelo, ResNet):
        arquitetura, config = "resnet", {}
    else:
        raise ValueError(f"arquitetura não suportada: {type(modelo).__name__}")
    torch.save({"state_dict": modelo.state_dict(), "rotulos": rotulos, "meta": meta,
                "arquitetura": arquitetura, "config_modelo": config}, caminho)


def carregar(caminho, map_location="cpu") -> tuple[nn.Module, list[str], dict]:
    """Restaura a arquitetura salva; aceita também checkpoints legados do treino."""
    dados = torch.load(caminho, map_location=map_location, weights_only=False)
    meta = dados.get("meta", {})
    # Antes do campo explícito, treinar.py registrava a arquitetura em args.
    # Checkpoints ResNet mais antigos não tinham nenhum dos dois campos.
    arquitetura = dados.get("arquitetura", meta.get("args", {}).get("arquitetura", "resnet"))
    if arquitetura == "gcn":
        from gcn import construir as construir_gcn

        modelo = construir_gcn(len(dados["rotulos"]), **dados.get("config_modelo", {}))
    elif arquitetura == "resnet":
        modelo = construir(len(dados["rotulos"]), pretreinado=False)
    else:
        raise ValueError(f"arquitetura de checkpoint não suportada: {arquitetura!r}")
    modelo.load_state_dict(dados["state_dict"])
    modelo.eval()
    return modelo, dados["rotulos"], meta

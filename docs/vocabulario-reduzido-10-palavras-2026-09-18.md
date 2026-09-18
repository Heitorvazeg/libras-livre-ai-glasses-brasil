# Vocabulário reduzido para 10 palavras — medição

**18/09/2026.** Hipótese do usuário: reduzir o número de classes reduz a chance
de confusão, então um modelo com só as palavras que fazem sentido num
atendimento deveria errar menos. Foi medido. **A hipótese não se sustentou.**

Ao contrário do etapa 2/3/4, este não é um protocolo pré-registrado: a redução
foi pedida como entrega, e a medição veio junto para não entregar um modelo sem
número. Os critérios de leitura abaixo são descritivos, não decisórios.

## O que foi feito

Dez palavras, escolhidas pelo usuário por terem relação com atendimento:
**banco, banheiro, cinco, conhecer, esquina, filho, medo, ruim, vacina,
vontade**. As outras dez saíram.

Duas runs no Kaggle, commit `3a329d5`, receita de entrega inalterada (backbone
`7a6e997c…`, semente 20260917, 120 épocas, lr 1e-3, cosseno, política "última"):

- `final-vocab10` → checkpoint de entrega `4a15c205…`, exportado para `.tflite`;
- `loso-vocab10` → 8 rodadas com evidências, para medir.

O corpus reduzido não veio de upload novo: o notebook valida os 800 clipes do
MINDS como sempre e só então copia os 400 clipes das dez palavras, conferindo o
sha256 de cada arquivo contra o inventário (`inventario-vocabulario.json`).

## Resultado 1 — acerto por sinal

| | acerto |
|---|---|
| modelo de 20 classes, nas mesmas 10 palavras | 381/400 = **95,25%** |
| modelo de 10 classes | 378/400 = **94,50%** |

Piorou 3 clipes — dentro do ruído de semente (que mede ~4 clipes), ou seja,
**empate**. Mas é um empate ruim: a tarefa ficou mais fácil (chance aleatória
subiu de 5% para 10%) e o acerto não subiu.

Por sinal (40 clipes cada):

| sinal | 20 classes | 10 classes |
|---|---|---|
| banco | 100,0% | 100,0% |
| banheiro | 95,0% | 92,5% |
| cinco | 100,0% | 100,0% |
| conhecer | 97,5% | 95,0% |
| esquina | 97,5% | 100,0% |
| **filho** | **75,0%** | **77,5%** |
| medo | 90,0% | 85,0% |
| ruim | 97,5% | 100,0% |
| vacina | 100,0% | 95,0% |
| vontade | 100,0% | 100,0% |

FILHO, o sinal mais fraco, continua em 77,5%: tirar dez concorrentes não o
consertou. E a confusão FILHO→MEDO **aumentou** de 6 para 9 clipes. Faz sentido:
no modelo de 20 classes parte dos erros de FILHO escapava para APROVEITAR (3
clipes), palavra que não existe mais; agora todos caem em MEDO.

## Resultado 2 — taxa por frase do roteiro (o que importa para a demo)

Os seis sinais do roteiro estão todos entre os dez, então a simulação da decisão
do app (`scripts/simular_frase_roteiro.py`) roda igual nos dois modelos.

| corte | 20 classes: certa / repetição / **errada** | 10 classes: certa / repetição / **errada** |
|---|---|---|
| 0,00 | 78,5% / 0,0% / **21,5%** | 75,3% / 0,0% / **24,7%** |
| **0,60** | **78,5% / 13,2% / 8,3%** | **71,5% / 8,0% / 20,5%** |
| 0,80 | 68,1% / 31,9% / **0,0%** | 64,2% / 29,7% / **6,1%** |
| 0,90 | 58,2% / 41,8% / 0,0% | 58,0% / 42,0% / 0,0% |

No corte do app (0,60), a frase falada errada — o pior desfecho, porque o
sistema afirma com confiança algo que a pessoa não disse — **subiu de 8,3% para
20,5%**, dois e meio vezes. E as frases certas caíram 7 pontos.

Por pessoa, no corte 0,60:

| pessoa | 20 classes | 10 classes |
|---|---|---|
| M01, M02 | 100% | 100% |
| M05 | 100% | 97% |
| M06 | 100% | 86% |
| M08 | 100% | 81% |
| M12 | 94% | 75% |
| M10, M11 | 17% | 17% |

Cinco pessoas que estavam em 100% saíram de 100%.

## Por que reduzir piorou

Duas causas, e as duas eram previsíveis só depois de medir:

1. **Menos classes é menos dado.** O corpus caiu de 800 para 400 clipes. As dez
   palavras descartadas não eram só distratoras: elas ensinavam representação
   compartilhada (formato de mão, trajetória, ritmo) que serve para todas. O
   modelo tem 0,47M parâmetros e aprende o *movimento*, não uma lista.
2. **Erro com menos concorrentes é erro mais confiante.** Com 20 classes, a
   probabilidade se espalha e o erro costuma ficar abaixo do corte, virando
   "pede repetição". Com 10, o mesmo erro sai mais confiante e passa do corte,
   virando "falada errada". Por isso a coluna de repetição encolheu (13,2% →
   8,0%) enquanto a de erro cresceu: a redução não eliminou erros, **converteu
   rejeição em afirmação errada**.

## Estado dos artefatos

O `.tflite` de 10 classes existe e está íntegro — 2,0 MB, contrato
`[1, 96, 57, 3]`, saída `[1, 10]`, paridade PyTorch↔TFLite de 2,29e-05 com 0
discordâncias de top-1. Fica em `experimentos-privados/export-final-s20260917-vocab10-v1/`
(privado), com o checkpoint conferido campo a campo: commit da proveniência,
pesos finitos, backbone, época 120, política "última".

**Recomendação: manter o modelo de 20 classes na demo.** Ele é melhor nos dois
números que importam, e o de 10 classes não compra nada em troca — nem acerto
por sinal, nem taxa por frase. A decisão é do usuário; o artefato de 10 classes
está pronto caso ele decida o contrário por outro motivo (menos palavras para o
roteiro, menos risco de fala fora do vocabulário).

Se a escolha for reduzir mesmo assim, o corte precisa subir: em 0,90 os dois
modelos empatam em frase certa (58%) e ambos zeram a frase errada.

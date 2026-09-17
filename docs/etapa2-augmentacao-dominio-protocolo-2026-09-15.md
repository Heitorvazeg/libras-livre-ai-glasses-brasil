# Etapa 2 — augmentação de domínio: protocolo

**15/09/2026. Escrito antes de qualquer treino desta etapa.** Objetivo: subir o
acerto nas 20 palavras do MINDS em pessoas de fora do MINDS, sem usar dados
externos no treino. Base: [diagnóstico de domínio](diagnostico-dominio-minds-externo-2026-09-15.md).

## O que já existe no treino

`representacao.aumentar`: rotação no plano (σ 12°), zoom do esqueleto inteiro
(σ 0,1), translação (σ 0,06) e espelhamento em 30% dos clipes. O zoom e a
translação afetam o corpo todo; a normalização por largura de ombro neutraliza a
maior parte deles. Nada varia a amplitude do movimento dos braços em relação ao
corpo, nem o repouso no início e no fim.

## Eixos escolhidos, com o motivo

| eixo | incluído | motivo |
|---|---|---|
| amplitude da trajetória dos braços | **sim** | Maior diferença medida (MINDS ~1,34 × externos 0,82–0,92 em larguras de ombro). A mão mantém a forma; só a trajetória encolhe ou cresce |
| corte do repouso no início e no fim | **sim** | V-LIBRASIL começa em movimento; MINDS tem 13% parado no início e 23% no fim. Barato |
| espelhamento | já existe (30%) | Mantido como está. O ganho de espelhar no teste (+4 clipes) fica para a etapa 4 |
| mão em repouso presente/ausente | não | Remover as mãos em repouso dos externos rendeu 1 clipe; sintetizar a mão exige template e o sinal medido não justifica agora |
| altura do espaço de sinalização | não | Local de articulação é informação linguística; deslocar o pulso no teste derrubou 6 clipes |
| label smoothing | não nesta etapa | É sobre confiança, não acerto. Vai para a etapa 5 |

## Implementação (flag nova, desligada por padrão)

`treinar.py --aug-dominio`. Aplicada no clipe **antes** de `rp.aumentar`, com
gerador próprio `[semente, rodada, época, índice, 1]`, para não alterar os
sorteios da augmentação existente. Sem a flag, o treino fica idêntico ao atual.

- **Amplitude**, em 50% dos clipes: fator k ~ U[0,60; 1,05], o mesmo nos dois
  braços. Para cada braço, o pulso da pose vira `centro + k·(pulso − centro)`
  (centro = média do pulso no clipe, só x e y); o cotovelo desloca metade; a mão
  detectada desloca junto com o pulso, sem mudar de forma. Blocos de mão ausentes
  continuam zerados.
- **Repouso**, em 50% dos clipes: detecta os frames parados no início e no fim
  (velocidade dos pulsos abaixo de 20% do máximo, mesma regra do diagnóstico) e
  corta um número sorteado entre 0 e o total parado de cada lado, mantendo pelo
  menos 3 frames.

## Comparação

- **Linha de base:** o checkpoint final do Kaggle `c7851d8a…`
  (`--final --politica-final ultima --semente 20260917`, backbone aprovado,
  120 épocas), treinado no notebook final em GPU T4.
- **Candidata:** a mesma receita + `--aug-dominio`, **rodada no mesmo notebook
  do Kaggle**, para as duas ficarem no mesmo ambiente.

> **Mudança registrada antes de qualquer resultado da candidata (15/09):** a
> versão inicial previa retreinar as duas localmente em CPU. Os treinos locais
> ficaram em ~147 s por época (~5 h cada) e foram interrompidos na época 3, sem
> avaliação. Nas três primeiras épocas a linha de base local reproduziu a curva do
> Kaggle (perda 2,024 → 1,637 local contra 2,024 → 1,637 no Kaggle). Nenhum
> checkpoint local foi gerado ou usado.
- Treino só com os 800 clipes do MINDS nas duas. Nenhum clipe externo entra.

## Métricas e regra de decisão (fixadas agora)

1. **Primária:** acerto top-1 nas **28 clipes do grupo de avaliação**
   (V03, TUFS, T048, T050), definido em `divisao_pessoas` do manifesto.
2. **Secundária:** acerto nos 42 clipes do grupo de treino externo. Nesta etapa
   eles também não entram no treino, então valem como segunda amostra.
3. **Adotar `--aug-dominio` só se** a candidata acertar **pelo menos 4 clipes a
   mais** que a linha de base nos 28 **e** não acertar menos nos 42.
   Qualquer resultado abaixo disso conta como "sem evidência de ganho", não como
   ganho pequeno.
4. Nenhum hiperparâmetro da augmentação é ajustado depois de ver o resultado. Se
   não passar, a etapa fecha e seguimos para a etapa 3.

## Limites conhecidos antes de rodar

- Uma semente só por variante; a variação entre execuções já medida no LOSO é
  ~1,7 pp e em 28 clipes o ruído é muito maior.
- Não mede regressão no próprio MINDS, porque o treino final usa todas as
  pessoas. Se a candidata passar, uma rodada LOSO fica como verificação antes de
  virar receita.
- As pessoas externas são proxy, não a câmera dos óculos.

## Resultado — 15/09/2026

**Candidata:** `experimentos-privados/final-s20260917-augdom-v1/`, checkpoint
`266fc75f…`, commit `290da77`, backbone `7a6e997c…`, `--aug-dominio` no comando,
120 épocas, Tesla T4 / torch 2.10.0+cu128 (mesmo ambiente da linha de base),
selftest e preflight OK.

| checkpoint | avaliação (28) | treino externo (42) |
|---|---:|---:|
| linha de base `c7851d8a` | 14 (50,0%) | 20 (47,6%) |
| candidata `266fc75f` | 12 (42,9%) — ganhou 1, perdeu 3 | 20 (47,6%) — ganhou 2, perdeu 2 |

Mudanças no grupo de avaliação: `sapo` (V03) passou a acertar; `banco`,
`espelho` e `filho` (TUFS) passaram a errar; `aluno` (TUFS) continuou errado.

**Decisão pela regra fixada:** a candidata não acertou 4 clipes a mais nos 28
(saldo −2) e empatou nos 42. **Sem evidência de ganho. `--aug-dominio` não é
adotada.** A flag continua no código, desligada por padrão, sem efeito na
receita aprovada. Nenhum hiperparâmetro da augmentação foi ajustado depois do
resultado. A etapa 2 fecha aqui; a próxima é a etapa 3 (treino com parte dos
clipes externos).

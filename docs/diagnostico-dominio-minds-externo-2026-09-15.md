# Diagnóstico: por que o modelo cai fora do MINDS

**15/09/2026.** Etapa 1 do plano para melhorar o acerto nas 20 palavras do
MINDS. Pergunta: a queda de ~96% (LOSO) para ~49% nos 70 clipes externos
(V-LIBRASIL + MALTA, [manifesto](calibracao-naovista-2026-09-14.md)) vem de uma
diferença de pré-processamento corrigível?

Só leitura e inferência com o checkpoint final `c7851d8a…`. Nenhum treino, nenhum
ajuste. As intervenções abaixo são diagnóstico, não candidatas a
pré-processamento do app.

## Resposta curta

**Não há um bug de extração, e nenhuma diferença isolada explica a queda.**
As maiores diferenças medidas entre os corpora, quando corrigidas nos clipes
externos, recuperam no máximo 4 clipes de 70, o que fica dentro do ruído dessa
amostra. Os erros são sistemáticos por classe, o que aponta para diferenças em
como o sinal é executado ou gravado, não para ruído de pipeline.

## 1. A extração histórica do MINDS é reprodutível

Comparação com a reextração Holistic feita no piloto do Astra, nos 100 clipes
do M01:

| | histórico | reextração |
|---|---:|---:|
| frames | 13.781 | 13.781 |
| com mão esquerda | 11,3% | 11,2% |
| com mão direita | 52,2% | 52,3% |

Mesmos comprimentos, contagens praticamente idênticas. Não é falha de extração.

## 2. O que difere entre os corpora

Estatísticas dos `.npy` crus (saída do `extract.py`) nas 20 classes. A
normalização por ombros funciona igual em todos (distância entre ombros 1,00,
centro 0,00; tamanho da mão ~0,26 larguras de ombro nos quatro grupos).

**Presença das mãos.** Em 6 das 8 pessoas do MINDS, a mão parada perto do
quadril não é detectada: a mão esquerda aparece em 0–56% dos clipes por classe
e as duas faltam em ~48% dos frames. M11 e M12 detectam as duas mãos em
~90%+ dos frames. Nos externos, as duas aparecem em 67–100%.

**Geometria durante o movimento** (frames ativos, medianas):

| | MINDS (6 pessoas) | MINDS (M11, M12) | V-LIBRASIL | MALTA |
|---|---:|---:|---:|---:|
| altura do pulso direito (abaixo do ombro) | 0,69 | 0,71 | 0,37 | 0,42 |
| altura do pulso esquerdo | 1,39 | 1,55 | 0,83 | 0,91 |
| distância entre pulsos | 1,29 | 1,22 | 0,71 | 0,99 |
| amplitude do pulso direito | 1,34 | 1,47 | 0,82 | 0,92 |

No MINDS os sinais são mais amplos (~50–60% maiores) e mais baixos, com a mão
não dominante no quadril. Nos externos são compactos, na altura do peito, com a
mão não dominante levantada. M11 e M12 seguem a geometria do resto do MINDS;
só a detecção das mãos é diferente.

**Repouso no início e no fim.** MINDS: 13% dos frames parados no início e 23%
no fim. MALTA: 13% e 15%. V-LIBRASIL: 0% e 11% (os clipes começam já em
movimento).

## 3. Corrigir cada diferença nos clipes externos

Acerto nos 70 clipes externos:

| intervenção | todos | V-LIBRASIL | MALTA | ganhou / perdeu |
|---|---:|---:|---:|---:|
| original | 48,6% | 46,7% | 50,0% | — |
| zerar mãos em repouso (limiar tirado só do MINDS) | 50,0% | 46,7% | 52,5% | 1 / 0 |
| zerar mão esquerda inteira | 44,3% | 60,0% | 32,5% | — |
| espelhar (troca esquerda/direita) | 54,3% | 56,7% | 52,5% | — |
| alinhar repouso inicial/final ao MINDS | 45,7% | 46,7% | 45,0% | 2 / 4 |
| ampliar movimento do pulso direito para a amplitude do MINDS | 52,9% | 50,0% | 55,0% | 4 / 1 |
| mover o pulso direito para a posição do MINDS | 40,0% | 43,3% | 37,5% | 4 / 10 |
| amplitude + posição | 45,7% | 50,0% | 42,5% | 4 / 6 |

Com 70 clipes, um clipe vale 1,4 pp e a margem de incerteza é da ordem de
±12 pp. Nenhuma intervenção muda o quadro: as melhores (espelhar, ampliar a
amplitude) ganham 3–4 clipes. Zerar a mão esquerda inteira ajuda o V-LIBRASIL e
derruba o MALTA, então não é uma correção consistente. Várias intervenções foram
testadas no mesmo conjunto, então mesmo esses ganhos pequenos podem ser acaso.

## 4. Os erros se repetem entre pessoas

Classes em que pessoas externas diferentes erram **para a mesma glosa**:

| classe | acertos | erro repetido |
|---|---:|---|
| ruim | 0/4 | `america` nas 4 pessoas |
| medo | 1/5 | `america` em 3 |
| conhecer | 0/3 | `america` em 2 |
| banheiro | 4/6 | `america` em 2 |
| esquina | 0/2 | `sapo` nas 2 |
| barulho | 2/5 | `banheiro` em 2 |

`america` também era o destino principal nas palavras fora do vocabulário
([teste de rejeição](rejeicao-fora-do-vocabulario-final-s20260917-v1-2026-09-15.md)).
Erro repetido entre pessoas e corpora diferentes não é ruído. As explicações
possíveis são duas, e este diagnóstico não separa uma da outra:

1. **Execução diferente do mesmo sinal** (compacto contra amplo, altura, mão de
   apoio), que o modelo treinado só no protocolo MINDS não generaliza.
2. **Variante lexical**: a mesma glosa realizada com outro sinal em outra região
   ou dicionário. Só revisão por alguém fluente em Libras confirma, e hoje não
   temos isso.

## 5. O que isso muda nas próximas etapas

- **Não há correção de pré-processamento a aplicar.** A etapa 1 fecha sem
  mudança no pipeline.
- **A etapa 2 (augmentação) passa a ter alvos concretos**, tirados das
  diferenças medidas: escala da trajetória dos braços (sem deformar a mão),
  presença ou ausência da mão em repouso, altura do espaço de sinalização e
  espelhamento. Pelos resultados da seção 3, a expectativa de ganho de cada eixo
  isolado é pequena.
- **A etapa 3 (treino com dados externos) é a mais alinhada ao problema**: se a
  diferença é de execução, exemplos reais do outro estilo ensinam o que a
  augmentação só aproxima. Se for variante lexical, só dados externos resolvem.
- **Qualquer ganho precisa de um grupo de pessoas externas separado antes**,
  porque os 70 clipes já foram usados várias vezes nestes diagnósticos.

## Arquivos

Privados, em `experimentos-privados/diagnostico-dominios-20260915/`:
`diag_corpora.py` (estatísticas), `diag_intervencao_maos.py` (mão esquerda e
espelhamento), `diag_amplitude.py` (amplitude e posição). As checagens de
reprodutibilidade, mão em repouso, alinhamento temporal, geometria em frames
ativos e consistência dos erros rodaram inline nesta sessão, sem script salvo.

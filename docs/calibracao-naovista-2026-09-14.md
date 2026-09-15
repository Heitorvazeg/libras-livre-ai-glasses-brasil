# Conjunto de calibração com pessoas não vistas — montado em 2026-09-14

## O que é

Um conjunto de 50 clipes já extraídos, cobrindo as **20 classes do MINDS**,
de **10 pessoas que nunca entraram no treino de classificação** (nem em
nenhuma rodada LOSO, nem em `--final`): V-LIBRASIL (V01, V02, V03) e MALTA
(T002, T042, T044, T045, T048, T050, TUFS). Resolve a pergunta em aberto do
item 2/P2 de [`pendencias-entrega-2026-09-14.md`](pendencias-entrega-2026-09-14.md):
como calibrar sem gravar gente nova. Manifesto com hash sha256 de cada clipe:
[`../computer-vision-model/treino/calibracao_naovista_manifesto.json`](../computer-vision-model/treino/calibracao_naovista_manifesto.json).

Não requer nenhuma gravação nova, nenhuma reextração: os `.npy` já existiam
em `PoC/data/landmarks/` (V-LIBRASIL) e `PoC/data/landmarks-malta/` (MALTA).

## Composição

| pessoa | corpus | clipes | classes |
|---|---|---|---|
| V01, V02, V03 | V-LIBRASIL | 10 cada (30 no total) | acontecer, amarelo, banheiro, barulho, espelho, filho, maca, medo, ruim, sapo |
| T002 | MALTA | 6 | america, cinco, conhecer, esquina, vacina, vontade |
| TUFS | MALTA | 6 | aluno, aproveitar, banco, esquina, vacina, vontade |
| T042 | MALTA | 2 | bala, conhecer |
| T044, T048 (×2), T045, T050 | MALTA | 1–3 cada | america, cinco, conhecer, bala |

As 10 classes cobertas pelo V-LIBRASIL e as 10 cobertas pelo MALTA são
exatamente complementares — a divisão não foi escolhida, é a interseção real
entre o vocabulário de cada corpus público e as 20 classes do MINDS (o
V-LIBRASIL local tem só essas 10 em comum; as outras 10 simplesmente não
existem nele com esse nome).

## Limites — não é um substituto do LOSO

- **1–3 repetições por pessoa/classe** (o LOSO do MINDS usa 5). Serve para uma
  checagem grosseira de calibração/limiar, não para métricas por classe com
  poder estatístico.
- **Corpus diferente do MINDS**: câmera, enquadramento e população de
  sinalizantes distintos. Mede generalização entre domínios — um teste mais
  honesto que reusar validação LOSO — mas não é a mesma distribuição que a
  câmera dos óculos vai capturar.
- **Exposição parcial ao pipeline**: `V03` nunca foi vista em nenhuma etapa
  (reservada até do pré-treino contrastivo, ver
  [`politica-modelo-final-2026-09-14.md`](politica-modelo-final-2026-09-14.md)).
  As outras 9 pessoas foram vistas no pré-treino contrastivo **sem rótulo**
  — uma exposição bem mais fraca que o vazamento do pool LOSO (que via
  rótulo E gradiente de classificação), mas não é independência total do
  pipeline inteiro. Se for preciso um subconjunto 100% limpo, usar só V03
  (10 clipes, 10 classes).

## O que falta para virar calibração de verdade

1. `modelo_final.pt` (item 1 — aguardando a run `--final` no Kaggle).
2. O pipeline de calibração do Astra, em reescrita ativa nesta sessão
   (`calibracao.py`, schema 2 com vínculo a checkpoint/fontes) — rodar
   inferência sobre este manifesto e gerar logits no formato que esse
   schema exigir. Não antecipar esse formato aqui para não duplicar/colidir
   com o trabalho em andamento dele.
3. Decidir e registrar, antes de olhar qualquer resultado de teste, os
   critérios de aceite (meta de acurácia nos aceitos, cobertura mínima) —
   por instrução explícita do usuário, calibração é avaliação separada com
   critério fixado a priori.

# Teste de épocas (30 × 120): protocolo

**15/09/2026. Escrito antes de treinar.** Pergunta: treinar menos épocas deixa
o modelo menos preso ao jeito MINDS de gravar e menos confiante demais?

Motivo: na rodada LOSO M01 a validação chega a 99% na época 30 e a 100% na 40,
e a perda de treino termina em ~0,01. Nos 42 clipes externos, o checkpoint da
época 99 dessa rodada acertou 23 contra 20 do final de 120 épocas — diferença
dentro do ruído e confundida pelo número de pessoas no treino. 300 épocas
foram descartadas: a curva já está plana e mais épocas só baixariam mais a
perda.

## Comparação

- **Linha de base:** final do Kaggle `c7851d8a…` (120 épocas).
- **Candidata:** mesma receita, commit `290da77`, `--epocas 30`, sem
  `--aug-dominio`, no mesmo notebook e GPU. A curva do agendador cosseno se
  ajusta às 30 épocas (`T_max = épocas`). Notebook:
  `experimentos-privados/teste-epocas-30/notebook_teste_epocas_30.ipynb`.
- Só MINDS no treino; nenhum clipe externo.

## Onde medir

- **42 clipes do grupo de treino externo** (T002, T042, T044, T045, TUFV, V01,
  V02). Ainda não entraram em nenhum treino. Depois que a etapa 3 rodar, deixam
  de servir para esta pergunta.
- **Os 28 clipes de avaliação não são usados**, porque a escolha de épocas é
  decisão de projeto.

## Regra de decisão

- Com 42 clipes, um clipe vale 2,4 pp. Usando a mesma barra relativa da etapa 2
  (~14 pp), **30 épocas só são consideradas melhores se acertarem pelo menos 6
  clipes a mais** que a linha de base (20/42 → 26/42 ou mais).
- Relatado sem entrar na decisão: confiança mediana nos acertos e nos erros.
- Passar a barra não troca a receita sozinho: indica que vale confirmar o
  número de épocas em LOSO antes de mudar o final.

## Resultado — 15/09/2026

Run no Kaggle enviada pelo CLI (`walissonfagundes/teste-epocas-30`, privada),
mesmos datasets, GPU T4 e imagem Docker das runs anteriores. Backup em
`experimentos-privados/final-s20260917-e30-v1/`: commit `290da77`, backbone
`7a6e997c…`, `--epocas 30`, semente 20260917, sem `--aug-dominio`, checkpoint
`77ff9bae…`, época salva 30, selftest OK, Tesla T4 / torch 2.10.0+cu128. Perda de
treino na última época: 0,126 (a de 120 épocas termina em 0,007).

Nos 42 clipes do grupo de treino externo:

| checkpoint | acerto | V-LIBRASIL | MALTA | confiança mediana nos acertos | nos erros |
|---|---:|---:|---:|---:|---:|
| 120 épocas `c7851d8a` | 20/42 | 9/20 | 11/22 | 1,00 | 0,75 |
| 30 épocas `77ff9bae` | 18/42 | 7/20 | 11/22 | 0,98 | 0,53 |

30 épocas não acertou nenhum clipe que o de 120 errava e perdeu 2.
**Não passa a regra (≥ 26/42): sem evidência de ganho de acerto com menos
épocas. A receita continua com 120.** Os 28 clipes de avaliação não foram usados.

Observação fora da decisão: com 30 épocas a confiança nos erros cai bastante
(0,75 → 0,53) sem mudar a dos acertos. Isso é informação para a etapa de
calibração, não para o número de épocas.

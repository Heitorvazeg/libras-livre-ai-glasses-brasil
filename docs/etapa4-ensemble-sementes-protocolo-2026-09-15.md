# Etapa 4 — ensemble de sementes: protocolo de medição

**15/09/2026. Escrito antes de qualquer run.** Objetivo: **medir** quanto a
média de modelos treinados com sementes diferentes muda o acerto e a confiança.
Decisão do usuário: medir **sem compromisso de adotar** — o custo no app (N
modelos carregados, N inferências por sinal) é decisão à parte, do lado da
integração.

Contexto: etapas 2 (augmentação) e 3 (clipes externos no treino) foram medidas e
não adotadas; a receita de entrega continua a do final `c7851d8a`.

## O que é medido

Ensemble = treinar a **mesma receita** com sementes diferentes e, na inferência,
tirar a **média das probabilidades** (softmax com T=1) dos modelos. Nada mais
muda: mesma arquitetura, mesmo backbone, mesmos dados.

## Fase A — efeito fora do MINDS (barata)

- **Sementes:** 20260917 (a da receita), **20260918** e **20260919**. Fixadas
  agora, sem escolher depois pelo resultado.
- Três treinos `--final` no commit `47615c3`, mesma receita (backbone
  `7a6e997c…`, 120 épocas, lr 1e-3, wd 1e-4, lote 64, cosseno, ossos + xyz
  recentrado, kernel 9), sem `--aug-dominio` e sem extras. O de 20260917 é
  retreinado nesse mesmo commit para os três ficarem no mesmo código e ambiente.
- **Medida:** acerto top-1 nos **28 clipes do grupo de avaliação** (V03, TUFS,
  T048, T050), comparando:
  1. cada semente sozinha;
  2. a média das probabilidades das três.
- Também reportado, sem entrar na decisão: confiança mediana nos acertos e nos
  erros, e quantas previsões erradas caem em cada glosa.

## Fase B — efeito dentro do MINDS (só se a fase A justificar)

Três execuções LOSO completas (uma por semente) com `--salvar-evidencias`, para
ter os logits de cada rodada. Ensemble por rodada = média das probabilidades das
três sementes na mesma pessoa de teste. Compara com a média LOSO de cada semente
sozinha. Custo estimado: ~1 h de GPU por semente.

## Como interpretar (fixado agora)

Isto é medição, não adoção. Para orientar a leitura:

- **Ganho relevante:** o ensemble acertar **4 clipes a mais** que a melhor
  semente sozinha nos 28 (a mesma barra das etapas 2 e 3).
- **Ganho pequeno:** 1 a 3 clipes — dentro do ruído desta amostra; não sustenta
  triplicar o custo no app sozinho.
- **Sem ganho:** empate ou piora.
- A confiança nos erros cair sem cair a dos acertos conta como argumento
  separado, útil para a calibração, mesmo que o acerto não mude.

## Limites conhecidos antes de rodar

- 28 clipes; um clipe vale 3,6 pp. Esse grupo já foi usado na decisão da etapa 3,
  então não é um conjunto intocado.
- Três sementes só; a variação entre execuções já medida é ~1,7 pp no LOSO.
- Nada disso mede a câmera dos óculos.
- Ensemble não é a receita de entrega enquanto o custo no app não for aceito.

## Resultado da fase A — 15/09/2026

Três treinos `--final` no commit `47615c3`, backbone `7a6e997c…`, 120 épocas,
sem augmentação e sem extras, Tesla T4; selftest OK nos três. Backups em
`experimentos-privados/final-s{20260917,20260918,20260919}-ens-v1/`.

| modelo | acerto (28) | confiança nos acertos | confiança nos erros | destino principal dos erros |
|---|---:|---:|---:|---|
| semente 20260917 | 14/28 (50,0%) | 0,99 | 0,95 | america (7) |
| semente 20260918 | 12/28 (42,9%) | 0,98 | 0,91 | america (5), filho (4) |
| semente 20260919 | 11/28 (39,3%) | 0,99 | 0,86 | america (7) |
| **ensemble das 3** | **12/28 (42,9%)** | 0,98 | **0,77** | america (7) |

**Sem ganho de acerto.** O ensemble fica em 12/28, igual à média das sementes
individuais (12,3) e 2 clipes abaixo da melhor semente sozinha. Pelo critério
fixado, não há motivo de acurácia para pagar o custo de N modelos no app, e a
**fase B (LOSO por semente) não se justifica**.

### O que o resultado também mostra

- **A confiança nos erros cai de 0,86–0,95 para 0,77**, sem mexer na confiança
  dos acertos. É o único efeito consistente: o ensemble ajuda a separar acerto de
  erro, não a acertar mais. Isso interessa à calibração, não à acurácia.
- **A variação entre sementes é grande neste grupo:** 11, 12 e 14 de 28
  (39% a 50%) com a mesma receita. Um clipe vale 3,6 pp, então o ruído de semente
  sozinho cobre ~4 clipes. Comparações de 1 a 3 clipes neste conjunto não
  sustentam conclusão — os critérios das etapas 2 a 4 (≥ 4 clipes) já
  respeitavam isso, e o ganho da etapa 3 (+5 sobre a base) ficou acima dessa
  faixa, mas não muito.
- **Escolher "a melhor semente" olhando estes 28 clipes seria seleção no próprio
  conjunto de avaliação** e não vale como ganho. A receita segue com a semente
  20260917, fixada antes por política, não por desempenho.
- A semente 20260917 retreinada no commit `47615c3` deu os mesmos 14/28 do
  checkpoint de entrega `c7851d8a`, treinado em outro commit — reprodutibilidade
  coerente entre as duas execuções.

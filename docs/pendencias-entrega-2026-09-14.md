# Pendências rumo à entrega — 2026-09-14

> **Ordem substituída pelo [plano integrado de validação](validacao-visao-app-2026-09-14.md).**
> Preservado como histórico e registro de decisões/execuções informadas por outras
> frentes. Não usar acurácia de treino para escolher o final; M9 não espera o final;
> distorção por fps e pareamento não estão comprovados nos termos abaixo.
> Os resultados WLASL informados neste documento não foram revalidados nesta etapa.
> A integração autorizada foi feita em branch local própria, sem publicar/fechar PRs.

Levantamento das frentes em aberto nesta data, com prioridade estabelecida para as que estão
no **caminho crítico da entrega** (produzir `sinal_classifier.tflite` para o app). Cada item
lista o que falta e, quando já decidido, quem/como vai resolver — o plano é separar um agente
por pendência.

Documento relacionado: [`correcoes-modelo-visao-pontos-de-teste-2026-09-14.md`](correcoes-modelo-visao-pontos-de-teste-2026-09-14.md),
que já detalha os achados A–F citados abaixo (M9, receita do M1, reamostragem por fps).

---

## Caminho crítico da entrega — em ordem de prioridade

### P1. Decidir a semente do checkpoint de entrega (`--final`)

**Onde estamos.** O pré-treino contrastivo está consolidado como efetivo: reduz a variância
entre rodadas LOSO em três execuções distintas (semente 20260916: desvio 5,6%→4,8%; mais duas
execuções recentes, 96,6% de média nas três). Usar pré-treino na entrega está decidido.

**O que falta decidir.** `treinar.py --final` treina com TODAS as pessoas do MINDS, sem
holdout — não é uma das 8 rodadas LOSO, é um treino à parte. A acurácia que o justifica é a
média LOSO medida antes, não uma medição direta desse checkpoint específico (ver achado A do
documento de correções). Como a semente controla inicialização de pesos, ordem de embaralhamento
e augmentação dentro de um único treino, **não sabemos hoje o quanto a escolha da semente
influencia o resultado do `--final`** especificamente — só temos variância medida entre rodadas
LOSO (que são 8 treinos diferentes por execução), não entre sementes de um único treino `--final`.

**Por que é P1.** Sem resolver isso, não dá para produzir o artefato de entrega com confiança.
Bloqueia tudo mais nesta lista que depende do checkpoint final (ex.: M9 na sua forma definitiva).

**Abordagens possíveis a investigar** (não decidido, para quem for atacar isso):
- Rodar `--final` com 2-3 sementes diferentes e comparar via alguma métrica interna de
  consistência (ex.: acurácia no próprio conjunto de treino, ou concordância entre os
  checkpoints em uma amostra de validação cruzada informal).
- Escolher a semente que corresponda à rodada LOSO historicamente mais estável (menor variância
  fold a fold).
- Aceitar que a escolha da semente do `--final` é inerentemente não verificável do mesmo jeito
  que o LOSO, e documentar isso como limitação conhecida em vez de tentar eliminá-la.

---

### P2. M9 — recall e confusão dos sinais do roteiro da demo

**Onde estamos.** Já há evidência concreta nos dados existentes (achado B do documento de
correções): FILHO e MEDO são a confusão mais frequente em duas execuções reais recentes
(filho 75-80% de recall; `filho→medo` é a confusão nº1 do relatório em ambas). Os dois estão na
mesma frase do roteiro da demo (FILHO, VACINA, VONTADE, CINCO, MEDO, BANHEIRO).

**O que falta.** Investigação dedicada — um subagente deve aprofundar isso: confirmar o padrão
nos checkpoints disponíveis, quantificar o risco real na frase da demo, e propor se há palavra
do roteiro pra trocar (preservando o sentido da frase) caso o checkpoint final repita o problema.

**Por que é P2.** Pode rodar em paralelo ao P1 usando os checkpoints LOSO já existentes (não
precisa esperar o `--final`), mas a decisão final (trocar palavra do roteiro ou não) só fecha
depois que o checkpoint de entrega existir — por isso vem logo atrás do P1, não à frente.

**Como resolver.** Subagente dedicado, focado nos relatórios já baixados
(`.../scratchpad/pretreino-malta-v1-semente20260917/` e `.../pretreino-malta-v1-wlasl/`) mais
qualquer checkpoint novo que surgir.

---

### P3. PoC de reamostragem por tempo real (fps)

**Onde estamos.** Decisão tomada em 12/09 (nota de projeto `fps-heterogeneo-entre-corpora`):
reamostrar por tempo real, não por contagem de frame, antes de misturar corpora com fps muito
diferentes (MINDS 30fps, MALTA 12fps). Nunca implementada — `gcn.para_sequencia` e
`cabeca_gcn.Reamostragem` continuam por índice de frame. O backbone pré-treinado que gerou os
96,6% já carrega essa distorção nos dois lados da comparação (controle e candidato), então o
ganho relativo do pré-treino provavelmente se sustenta, mas a acurácia absoluta de qualquer
checkpoint pode estar subestimada ou distorcida de um jeito ainda não medido.

**O que falta.** Montar uma PoC específica (branch própria, em paralelo ao que já está rodando)
que reamostre por tempo real em vez de índice de frame, e meça o efeito via LOSO — mesmo
protocolo de comparação pareada usado no resto da sessão (mesma semente controle vs. candidato).

**Por que é P3.** Não bloqueia a entrega do checkpoint atual (a distorção afeta os dois lados da
comparação igualmente, então o "veredito" pró-pré-treino continua válido), mas é uma correção de
fundo que pode mudar a acurácia absoluta reportada e vale medir antes de travar a arquitetura de
vez.

**Como resolver.** Branch nova (`poc/reamostragem-tempo-real` ou similar), agente dedicado.

---

### P4. `computer-vision-model/treino/README.md` desatualizado

**Onde estamos.** Achado C do documento de correções: o README ainda afirma que "o ST-GCN não
tem export" — falso desde a PR #12. Mais duas passagens menores também desatualizadas
("Exportação 3D não suportada", ausência de reamostragem/imputação embutidas).

**Por que é P4.** Não afeta o resultado nem o artefato de entrega, só a documentação — mas é
barato de corrigir e evita retrabalho de quem ler o README achando que falta algo que já existe.

**Como resolver.** Correção pontual de texto, sem necessidade de subagente dedicado — pode entrar
junto de qualquer outra tarefa que já esteja mexendo no arquivo.

---

## Fora do caminho crítico — resultados aguardados, não bloqueiam a entrega

### WLASL no pré-treino — **ENCERRADO em 2026-09-14**
Run concluída, semente 20260916 (comparação pareada de verdade contra a run sem WLASL, mesma
semente): **96,2%** com WLASL vs. **96,6%** sem WLASL — Δ −0,4pp, dentro do ruído de ~1,7pp já
estabelecido nesta sessão. Sem padrão consistente por rodada (mistura de ganhos e perdas
pequenos). Decisão: **não incluir WLASL na receita padrão de pré-treino** — sem ganho
demonstrado, e com custo extra (licença de pesquisa, corpus em outra língua, rótulos
conflitantes com Libras). Não será mesclado com os demais resultados. Infraestrutura de suporte
a WLASL (`entrada_pretreino.py`, `rotulos_pretreino.py`, `--fontes wlasl`) permanece no código
como capacidade opcional, desligada por padrão — não precisa ser revertida.

Achado lateral, relevante para o P2 (M9): nessa run o padrão de confusão mudou — `esquina↔sapo`
foi a confusão mais frequente (em vez de `filho↔medo`, dominante nas outras execuções), e
`esquina` (80,0%) ficou com recall pior que `filho` (82,5%). Mostra que o padrão de confusão não
é totalmente estável entre configurações — informação a considerar na investigação do M9.

### PoC de negativos extras no contrastivo — **ENCERRADA em 2026-09-14**
Run concluída, semente 20260916 (comparação pareada contra a run sem negativos extras, mesma
semente): **95,4%** com negativos extras vs. **96,6%** sem — Δ **−1,2pp**. Diferente do WLASL
(mistura de + e −, sinal de ruído), aqui **8 de 8 rodadas empataram ou pioraram, nenhuma
melhorou** — padrão consistente demais pra ser ruído, mesmo com quedas pequenas por rodada
(0 a −3pp). Decisão: **negativos extras (N=16, pool de classes com <2 clipes) piora o
pré-treino contrastivo**, não vai para a receita padrão. Hipótese não testada para o porquê:
~5.327 negativos extras por época pode sobrecarregar o denominador do SupCon com classes
raras/ruidosas em relação ao sinal das 32×2 âncoras reais, diluindo o gradiente útil. Branch
`poc/contrastivo-negativos-extras` fica sem merge — aguardando sinal do usuário como as demais
(ver "Explicitamente em espera").

**Decisão de arquitetura decorrente:** com WLASL e negativos extras descartados, a receita de
pré-treino consolidada é **V-LIBRASIL+MALTA (sem WLASL, sem negativos extras) + ST-GCN
`--ossos --com-z --z-recentrado`** — a mesma que já vinha rendendo 96,6% desde a primeira run
desta sessão.

---

## Explicitamente em espera

### Branches e PRs (#15, #16, `poc/contrastivo-negativos-extras`)
Nenhuma ação — aguardando sinal do usuário para decidir merge/fechamento de qualquer uma.

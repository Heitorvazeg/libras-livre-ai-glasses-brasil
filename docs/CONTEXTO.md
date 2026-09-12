# Contexto do projeto — estado atual e como as peças se encaixam

Este documento existe para quem chega no meio: outra pessoa do time, um assistente de
IA em outra sessão, ou nós mesmos daqui a duas semanas. Ele responde **o que já está
resolvido, o que está em aberto e por quê** — sem exigir a leitura do histórico.

**Última atualização:** 2026-09-12
**Prazo do hackathon:** 16/09/2026
**Branch de integração:** `dev`

> Índice da documentação: [`README.md`](README.md). Como rodar cada trilha: os
> READMEs de `computer-vision-model/`, `contextualization-model/` e
> `mobile-app-companion/`.

---

## 1. O que o projeto é

Óculos Ray-Ban Meta que traduzem Libras para fala, num balcão de atendimento. A câmera
capta a pessoa surda sinalizando, o celular reconhece os sinais e fala em português.

O sistema tem **três trilhas**, e este documento cobre em detalhe a primeira:

| Trilha | O que faz | Onde |
|---|---|---|
| Visão | vídeo → glosa | `computer-vision-model/` |
| Contextualização | glosa → frase em português | `contextualization-model/` (ver §5.5) |
| App | roda os dois modelos, fala e escuta | `mobile-app-companion/` |

**A premissa que molda todas as decisões técnicas:** o dispositivo é **institucional**,
fica com o atendente e serve dezenas de pessoas surdas diferentes por dia. Não há
calibração por usuário. O modelo precisa funcionar com alguém que ele nunca viu, desde
o primeiro sinal.

Consequência prática: **a avaliação de generalização entre pessoas no MINDS deixa uma
pessoa inteira de fora do treino** (*leave-one-signer-out*, LOSO), inclusive do
pré-treino. Diagnósticos com pessoas conhecidas não sustentam essa alegação.

---

## 2. Onde o modelo está (números medidos)

| Modelo | LOSO signer-independent | Parâmetros |
|---|---|---|
| Chance aleatória | 5,0% | — |
| Baseline DTW 1-NN (PoC) | 70,0% | — |
| ST-GCN x,y (orçamento justo) | 72,1% | 0,46M |
| ST-GCN + ossos | 91,0% / 92,5% | 0,46M |
| **ST-GCN + ossos + z** | **94,6% / 94,9%** | **0,47M** |
| Skeleton-DML + ResNet-18 | 93,0 / 93,4 / 93,5 / 91,8% | 11,25M |
| **ResNet-18 + imputação** | **95,1%** | 11,25M |
| Literatura (mesma base, mesmo protocolo) | 93-94% | — |

**O ST-GCN é o modelo de entrega** (decidido em 11/09). Ele **empatou** com a melhor
ResNet — 94,6/94,9 contra 95,1 — usando **24× menos parâmetros**. Exportado dá ~1,9 MB em
float32 e 0,47 MB em int8, contra 45 MB e 11,2 MB da ResNet.

⚠️ Uma versão anterior deste documento afirmava que o GCN **superou** a ResNet. Estava
errado: comparava contra a ResNet de 93,0% ignorando a de 95,1% do próprio repositório.
Ver [`decisao-arquitetura-modelo.md`](decisao-arquitetura-modelo.md).

**O que fez o GCN sair de 72% para 94%:** ossos (+18,9 pp, 8 de 8 folds) e z recentrado
(+2,1 pp, confirmado em segunda semente). Ambos são features derivadas dos landmarks que
já tínhamos — nenhum dado novo foi coletado.

⚠️ **Leia a variância antes de comparar qualquer coisa:** a mesma configuração oscila
~1,7 ponto entre execuções. Diferenças menores que ~2 pontos são ruído, não resultado.

⚠️ **Todos esses números são sobre vídeo de estúdio**, frontal e com enquadramento
controlado. É teto otimista. O número de balcão só sai com coleta própria.

---

## 3. Os dados, e o papel de cada conjunto

| Conjunto | Clipes | Classes | Pessoas | Papel |
|---|---|---|---|---|
| **MINDS-Libras** | 800 | 20 sinais | 8 | **treino + avaliação LOSO** |
| V-LIBRASIL (auditada) | 4.025 | 1.349 palavras | 3 (sempre os mesmos) | pré-treino |
| **MALTA-LIBRAS** | 6.353 | 5.958 rótulos | 8 (uma delas é 90%) | pré-treino |
| WLASL100 (ASL) | 1.013 | 100 | 64 | pré-treino |
| V-LIBRASIL (curada) | 30 | 10 sinais | 3 | clipes reservados |

⚠️ **MALTA e WLASL ainda NÃO estão habilitados no código.** `dados.carregar` aceita só
`M` e `V`; a auditoria do pré-treino rejeita qualquer coisa que não seja `V`. Habilitá-los
preservando o bloqueio do MINDS é trabalho pendente.

Detalhes de licença, o que falhou no download do MALTA e por que ele serve só para
pré-treino: [`decisao-datasets-e-licencas.md`](decisao-datasets-e-licencas.md).

**Os papéis não se misturam, e isso é deliberado.** Um clipe que entra no pré-treino não
pode aparecer na avaliação — senão o número de generalização deixa de significar o que
diz. Já barramos um vazamento assim: os 30 clipes reservados eram os MESMOS
vídeos que o corpus de pré-treino baixava (`datasets/ingest_pretreino.py` exclui por
caminho no zip de origem, não por nome de arquivo, porque três sinais têm rótulos
diferentes entre as bases).

**Reservar os vídeos exatos não reserva o domínio nem os articuladores.** Os mesmos
três articuladores e o mesmo domínio visual aparecem no corpus V-LIBRASIL usado no
pré-treino (V03 participa da validação interna para escolher a época). Portanto, os
30 clipes são um diagnóstico em clipes reservados, **não um teste de domínio ou de
pessoas inéditas** após esse estágio. O teste real no cenário de balcão depende de
coleta própria, com pessoas e condições não usadas no desenvolvimento.

**Classes compartilhadas entre bases não são contaminação em transferência
supervisionada.** Não se excluem automaticamente palavras V-LIBRASIL por existirem
no MINDS; o isolamento é de amostras/origens e de pessoas de teste, não de vocabulário.

**O MINDS não pode ser usado no pré-treino.** Ele é o conjunto de avaliação; pré-treinar
nele vazaria a pessoa de teste.

---

## 4. Pipeline, na ordem

```
vídeo → MediaPipe Holistic → landmarks (.npy) → representação → modelo → sinal
        (extract.py)          57 pontos × 3      Skeleton-DML     ResNet-18
                                                  ou grafo         ou ST-GCN
```

| Etapa | Onde | Custo |
|---|---|---|
| Baixar vídeos | `datasets/ingest*.py` (lê só o índice do zip remoto) | minutos |
| Extrair landmarks | `PoC/src/extract.py --particao i/N` | **horas, CPU, sem GPU** |
| Pré-treinar | `treino/pretreinar.py` | ~1,5h CPU |
| Treinar + avaliar | `treino/treinar.py` | ~2,5h CPU / minutos GPU |
| Validar o encanamento | `treino/selftest.py` | segundos |

**A extração é CPU e não acelera em GPU** — ela fica na máquina local. O treino vai para
GPU (`treino/notebook_gpu.ipynb`, Colab/Kaggle), onde horas viram minutos.

O [notebook GPU](../computer-vision-model/treino/notebook_gpu.ipynb) encadeia auditoria
obrigatória (`pretreinar.py --auditar`, sem modelo), pré-treino ResNet contrastivo
(`--objetivo contrastivo --pessoa-val V03`) e fine-tuning MINDS
(`--fontes minds --inicializar <checkpoint>`). A flag de auditoria e os sidecars são
pré-requisitos da execução; sem eles, o fluxo aborta. Baselines são opcionais, em
diretórios separados; o checkpoint final (todos os MINDS, sem teste independente)
fica separado dos resultados LOSO. O download privado inclui todos os artefatos,
inclusive checkpoints e JSON, não apenas relatórios.

São necessários dois pacotes **privados**: `landmarks-minds.tar.gz` com pasta
`landmarks/` (metadados opcionais no treino normal) e `landmarks-vlibrasil.tar.gz`
com pasta `landmarks-pretreino/`, incluindo cada `.npy` e seu
`*.npy.proveniencia.json`. V-LIBRASIL é **CC BY-NC-ND** (não-comercial, sem
derivações); landmarks não eliminam restrições da fonte. Não publicar dados,
sidecars, pacotes ou checkpoints derivados; uso em nuvem privada também exige
respeitar os termos. Não há autorização de uso comercial ou redistribuição aqui.

### Convenção de nome — é a fonte de verdade

`pessoaXX_sinal-YYY_repNN.npy`. Todo o pipeline extrai pessoa/sinal/repetição daí.
Prefixos: `M` = MINDS, `V` = V-LIBRASIL, `W` = WLASL, `T` = MALTA. Isso garante que pessoas de bases
diferentes nunca colidam.

---

## 5. Decisões em aberto e o que as decide

### ResNet-18 vs ST-GCN — decidido: ST-GCN

Empataram em acurácia (94,6/94,9 contra 95,1), e o GCN tem **24× menos parâmetros**. Para
um modelo que precisa virar `.tflite` no celular, empatar com 1/24 do tamanho decide.

**O que a decisão custa:** o `exportar.py` cobre **só a ResNet** e recusa checkpoint GCN
explicitamente. Escrever o export do GCN — incluindo calcular os ossos **dentro do grafo**,
já que hoje isso acontece em Python no `DatasetSinais` — é a maior dependência entre a
decisão e ter algo rodando no aparelho. É trabalho novo, não uma flag.

### O pré-treino ajuda?
Em teste. O primeiro pré-treino (classificação na V-LIBRASIL) **falhou**: validação em
0,2% contra 0,07% de chance. O motivo é estrutural — 1.353 classes com 3 clipes cada,
um por articulador, é uma tarefa quase não-aprendível.

**Diagnóstico e correção:** a mesma estrutura que arruína a classificação é ideal para
aprendizado **contrastivo**. Três execuções da mesma palavra por três pessoas diferentes
formam o par exato que ensina *invariância a sinalizante* — o requisito central do
produto. Implementado em `treino/contrastivo.py` (perda SupCon + amostrador P×K).

### A lacuna de ponto de vista
A normalização atual cobre posição no quadro, distância da câmera e resolução. **Não
cobre ângulo de câmera** (óculos de cima, de baixo, de lado) — e nenhuma base pública que
temos permite medir isso, porque são todas estúdio frontal. É risco real de produto, e só
a coleta própria responde.

### Contextualização glosa → português — implementada, sob guarda

A lista de glosas reconhecidas não é português: Libras tem ordem e estrutura
próprias. `contextualization-model/` treinou um seq2seq (ptt5-small podado, 45,1M,
vocabulário de 1.987 tokens) que vira `.tflite` de 46 MB e roda no app.

**O modelo não bateu o contextualizador por template em F1** em nenhuma das três
rodadas (0,826 a 0,837 contra 0,871 a 0,884), na validação sintética. Por isso o
app usa a cadeia `modelo → guarda → template → passthrough`, com o template como
piso: a guarda aceita o modelo em ~95% das sessões, e ele ganha onde há relação
gramatical entre glosas ("banco esquina" → "o banco fica na esquina").

⚠️ **O corpus é sintético** — escrito por um LLM a partir de sequências de glosas
que também foram hipótese de um LLM. Não é Libras observada. O portão real é um
conjunto humano de teste que ainda não existe. Nenhum número dessa trilha deve
aparecer em apresentação sem essa ressalva.

### Vocabulário
Os 20 sinais do MINDS são banco de provas, não produto. O vocabulário de atendimento está
proposto em três camadas, por confiança de generalização, em
[`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md). ~9 termos institucionais
(`marcar`, `atendimento`, `senha`...) **não existem em nenhuma base pública com mais de
uma pessoa** — só coleta própria resolve.

---

## 6. Armadilhas que já nos custaram tempo

Registradas para não se repetirem:

1. **Vazamento silencioso entre corpora.** Comparar por nome de arquivo deixou 9 de 30
   clipes passarem, porque o mesmo vídeo tem rótulos diferentes em bases diferentes. Use a
   origem, não o nome.
2. **Orçamento de treino, não arquitetura.** O ST-GCN "perdeu" por 49 pontos com
   hiperparâmetros de fine-tuning (~270 atualizações de peso). Com orçamento de treino do
   zero (~2.160), recuperou 29 pontos. Ao comparar arquiteturas, confira quantas
   atualizações cada uma recebeu.
3. **Testes estocásticos viram asserção falsa.** Um teste sintético de uma rodada passava
   em CPU (100%) e falhava em GPU (41,7%) com o mesmo código. Média de 3 rodadas resolveu.
4. **`-inf × 0 = NaN`.** Mascarar a diagonal com `-inf` e multiplicar pela máscara de
   positivos envenena o lote inteiro em silêncio. Use `torch.where`.
5. **Inércia térmica engana medição.** Ler a temperatura logo após mudar a configuração dá
   número otimista; o regime estável leva minutos.
6. **`pkill -f <padrão>` casa com o próprio comando** que o executa, e mata o shell que o
   chamou. Aconteceu quatro vezes; uma delas matou o orquestrador de um experimento
   noturno e custou ~7h de máquina ociosa. Mate por PID.

---

## 7. Como validar que nada quebrou

```bash
cd computer-vision-model/treino && python selftest.py       # pipeline de treino
cd computer-vision-model/datasets && python selftest.py     # receita de ingestão
cd computer-vision-model/PoC && python src/selftest.py      # extração e DTW
```

O selftest do treino inclui um **controle negativo**: com rótulos aleatórios, a acurácia
tem de ficar na chance. Se subir, há vazamento entre treino e teste — o erro mais caro
possível aqui, porque produz um número bonito e falso.

---

## 8. Onde ler mais

| Documento | Assunto |
|---|---|
| [`decisao-datasets-e-licencas.md`](decisao-datasets-e-licencas.md) | quais datasets, sob qual enquadramento legal, e o que o MALTA rendeu |
| [`protocolo-treinamento.md`](protocolo-treinamento.md) | o procedimento de treino ponta a ponta |
| [`investigacao-expansao-dataset.md`](investigacao-expansao-dataset.md) | investigação original das bases, estado da arte |
| [`decisao-arquitetura-modelo.md`](decisao-arquitetura-modelo.md) | ResNet vs GCN, com números e ressalvas |
| [`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md) | vocabulário em 3 camadas, para o consultor de Libras |
| [`libras-livre-arquitetura.md`](libras-livre-arquitetura.md) | arquitetura de produto (visão longa) |
| [`libras-livre-poc-plano.md`](libras-livre-poc-plano.md) | plano original da PoC |
| [`contextualizacao-glosa-seq2seq-plano.md`](contextualizacao-glosa-seq2seq-plano.md) | contextualização glosa → português: decisões e guardas |
| [`README.md`](README.md) | índice de toda a documentação, com o estado de cada plano |
| [`../computer-vision-model/treino/README.md`](../computer-vision-model/treino/README.md) | como rodar o treino |
| [`../contextualization-model/README.md`](../contextualization-model/README.md) | como rodar a trilha de contextualização |
| [`../mobile-app-companion/README.md`](../mobile-app-companion/README.md) | como buildar e rodar o app |

---

## 9. Princípio que vale para quem continuar

Quase todo erro caro deste projeto veio de **aceitar um número sem perguntar o que ele
mede**. O 44,6% do GCN parecia veredito e era configuração. Os 93,4% parecem precisos e
têm ±1,7. A validação em 0,2% parecia fracasso do pré-treino e era a tarefa sendo
impossível.

Antes de concluir de um número: *o que exatamente foi medido, com qual orçamento, e
quanto ele varia se eu rodar de novo?*

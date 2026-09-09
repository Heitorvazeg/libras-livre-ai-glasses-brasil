# Contexto do projeto — estado atual e como as peças se encaixam

Este documento existe para quem chega no meio: outra pessoa do time, um assistente de
IA em outra sessão, ou nós mesmos daqui a duas semanas. Ele responde **o que já está
resolvido, o que está em aberto e por quê** — sem exigir a leitura do histórico.

**Última atualização:** 2026-09-09
**Prazo do hackathon:** 16/09/2026
**Branch de trabalho:** `claude/libras-detection-model-53kd30`

---

## 1. O que o projeto é

Óculos Ray-Ban Meta que traduzem Libras para fala, num balcão de atendimento. A câmera
capta a pessoa surda sinalizando, o celular reconhece os sinais e fala em português.

**A premissa que molda todas as decisões técnicas:** o dispositivo é **institucional**,
fica com o atendente e serve dezenas de pessoas surdas diferentes por dia. Não há
calibração por usuário. O modelo precisa funcionar com alguém que ele nunca viu, desde
o primeiro sinal.

Consequência prática: **toda avaliação neste projeto deixa uma pessoa inteira de fora do
treino** (*leave-one-signer-out*, LOSO). Um número que não respeite isso não vale.

---

## 2. Onde o modelo está (números medidos)

| Modelo | Acurácia signer-independent | Observação |
|---|---|---|
| Chance aleatória | 5,0% | 20 classes |
| Baseline DTW 1-NN (PoC) | 70,0% | 10 sinais — tarefa **mais fácil** |
| **Skeleton-DML + ResNet-18** | **93,4% / 93,5% / 91,8%** | três execuções; **variância ~1,7 ponto** |
| ST-GCN (config de fine-tuning) | 44,6% | subtreinado — ver §5 |
| ST-GCN (config de treino do zero) | 73,9% | +29 pontos com orçamento adequado |
| Literatura (mesma base, mesmo protocolo) | 93-94% | Alves 2024; dos Santos 2025 |

**A ResNet-18 é o modelo do MVP.** Está no teto do que a literatura reporta e foi
reproduzida em CPU e GPU.

⚠️ **Leia a variância antes de comparar qualquer coisa:** a mesma configuração oscila
~1,7 ponto entre execuções. Diferenças menores que ~2 pontos são ruído, não resultado.

⚠️ **Todos esses números são sobre vídeo de estúdio**, frontal e com enquadramento
controlado. É teto otimista. O número de balcão só sai com coleta própria.

---

## 3. Os dados, e o papel de cada conjunto

| Conjunto | Clipes | Classes | Pessoas | Papel |
|---|---|---|---|---|
| **MINDS-Libras** | 800 | 20 sinais | 8 | **treino + avaliação LOSO** |
| V-LIBRASIL (curada) | 30 | 10 sinais | 3 | teste de domínio — **nunca treinada** |
| V-LIBRASIL (completa) | 4.053 | 1.353 palavras | 3 | pré-treino |
| WLASL100 (ASL) | 1.013 | 100 | 64 | pré-treino |

**Os papéis não se misturam, e isso é deliberado.** Um clipe que entra no pré-treino não
pode aparecer na avaliação — senão o número de generalização deixa de significar o que
diz. Já barramos um vazamento assim: os 30 clipes do teste de domínio eram os MESMOS
vídeos que o corpus de pré-treino baixava (`datasets/ingest_pretreino.py` exclui por
caminho no zip de origem, não por nome de arquivo, porque três sinais têm rótulos
diferentes entre as bases).

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

### Convenção de nome — é a fonte de verdade

`pessoaXX_sinal-YYY_repNN.npy`. Todo o pipeline extrai pessoa/sinal/repetição daí.
Prefixos: `M` = MINDS, `V` = V-LIBRASIL, `W` = WLASL. Isso garante que pessoas de bases
diferentes nunca colidam.

---

## 5. Decisões em aberto e o que as decide

### ResNet-18 vs ST-GCN
A ResNet vence hoje por ~18 pontos. O GCN tem duas vantagens que não dependem de
acurácia: **24× menos parâmetros** (importa para o `.tflite` no celular) e ângulos/ossos
como representação nativa (endereçaria a robustez a ângulo de câmera). Ver
[`decisao-arquitetura-modelo.md`](decisao-arquitetura-modelo.md).

**Para o MVP: ResNet, decidido.** Para o produto: em aberto.

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
| [`investigacao-expansao-dataset.md`](investigacao-expansao-dataset.md) | de onde vêm os dados, licenças, estado da arte |
| [`decisao-arquitetura-modelo.md`](decisao-arquitetura-modelo.md) | ResNet vs GCN, com números e ressalvas |
| [`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md) | vocabulário em 3 camadas, para o consultor de Libras |
| [`libras-livre-arquitetura.md`](libras-livre-arquitetura.md) | arquitetura de produto (visão longa) |
| [`libras-livre-poc-plano.md`](libras-livre-poc-plano.md) | plano original da PoC |
| [`../computer-vision-model/treino/README.md`](../computer-vision-model/treino/README.md) | como rodar o treino |

---

## 9. Princípio que vale para quem continuar

Quase todo erro caro deste projeto veio de **aceitar um número sem perguntar o que ele
mede**. O 44,6% do GCN parecia veredito e era configuração. Os 93,4% parecem precisos e
têm ±1,7. A validação em 0,2% parecia fracasso do pré-treino e era a tarefa sendo
impossível.

Antes de concluir de um número: *o que exatamente foi medido, com qual orçamento, e
quanto ele varia se eu rodar de novo?*

# Protocolo de treinamento — como o modelo é treinado, medido e entregue

**Atualizado:** 2026-09-10 · **Prazo do hackathon:** 16/09/2026

Este documento é o **procedimento**: o que rodar, em que ordem, com quais garantias, e o
que ainda falta. Ele não repete o que já está em outros lugares:

| Para | Leia |
|---|---|
| Entender o projeto do zero | [`CONTEXTO.md`](CONTEXTO.md) |
| Por que ResNet e não GCN | [`decisao-arquitetura-modelo.md`](decisao-arquitetura-modelo.md) |
| Isolamento e proveniência do pré-treino | [`protocolo-pretreino.md`](protocolo-pretreino.md) |
| De onde vêm os dados e as licenças | [`investigacao-expansao-dataset.md`](investigacao-expansao-dataset.md) |
| Vocabulário do MVP | [`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md) |
| Correções em andamento e donos | [`PLANO-CORRECOES.md`](PLANO-CORRECOES.md) |

---

## 1. Os dados e o papel de cada um

**Os três corpora não se fundem num conjunto de treino.** Cada um existe por um motivo
diferente, e misturá-los destrói a única métrica honesta que temos.

| Corpus | Clipes | Classes | Pessoas | Papel | Prefixo |
|---|---|---|---|---|---|
| **MINDS-Libras** | 800 | 20 sinais | 8 | **treino supervisionado + avaliação LOSO** | `M` |
| V-LIBRASIL auditada | 4.025 (4.053 originais) | 1.353 palavras | 3 (sempre os mesmos) | pré-treino | `V` |
| WLASL100 (ASL) | 1.013 | 100 | 64 | corpus disponível; pré-treino multi-fonte ainda não habilitado | `W` |

Por que não mesclar, com o motivo específico de cada um:

- **WLASL é ASL.** Os rótulos são sinais americanos; não dá para classificar Libras com
  eles no espaço de saída. Ele entra pelas *configurações de mão e primitivas de
  movimento*, não pelo léxico — e por 64 sinalizantes em condições variadas, que é o que
  as nossas bases de Libras não têm.
- **V-LIBRASIL tem 3 clipes por classe**, um por articulador. Foi exatamente isso que fez
  o pré-treino por classificação fracassar (validação em 0,2% contra 0,07% de chance):
  1.353 classes com 3 exemplos é tarefa quase não-aprendível. A mesma estrutura, porém, é
  ideal para aprendizado **contrastivo** — três execuções da mesma palavra por três
  pessoas formam o par que ensina invariância a sinalizante.
- **MINDS é o conjunto de avaliação.** Pré-treinar nele vaza a pessoa de teste e o número
  de generalização deixa de significar o que diz.

O carregador de treino reflete isso: `--fontes {minds, vlibrasil, todas}`. **WLASL não é
opção de treino supervisionado nem de pré-treino no carregador atual**: a auditoria
rejeita W explicitamente. Sua incorporação requer um experimento separado.

**Atualização de dados em 10/09:** a proveniência legada foi regularizada e os 28
registros envolvidos em duplicatas de vídeo ficaram fora de uma cópia separada.
Usar `landmarks-pretreino-auditado`: 4.025 amostras aprovadas, das quais 4.021 em
1.349 classes passam o filtro padrão de pelo menos dois clipes. O corpus original
permanece intacto e reprovado por duplicatas. Evidências e limites em
[auditoria-pretreino-2026-09-10.md](auditoria-pretreino-2026-09-10.md).

### Vazamento já barrado uma vez

Os 30 clipes V-LIBRASIL reservados eram os **mesmos vídeos** que o corpus de pré-treino
baixava. A exclusão passou a ser por **caminho no zip de origem**, não por nome de
arquivo, porque três sinais têm rótulos diferentes entre as bases — comparar por nome
deixou 9 de 30 passarem.

### Reservar vídeo não reserva domínio

Os mesmos três articuladores e o mesmo domínio visual aparecem no corpus de pré-treino
(V03 participa da validação interna). Os 30 clipes são **diagnóstico em clipes
reservados**, não teste de domínio nem de pessoas inéditas.

---

## 2. O protocolo de avaliação: leave-one-signer-out

**Toda rodada deixa uma pessoa inteira de fora do treino e testa nela.** São 8 rodadas
(uma por pessoa do MINDS); o número reportado é a média.

Isso não é preferência metodológica — decorre do produto. O dispositivo é
**institucional**: fica com o atendente e serve dezenas de pessoas surdas diferentes por
dia, sem calibração por usuário. O modelo precisa funcionar com alguém que nunca viu.
Medir com pessoas conhecidas mede memorização.

A validação também rotaciona: a pessoa `i` testa, a `i+1` valida. Assim a escolha de
época não olha para o teste.

**O que a LOSO não prova:** todos os clipes são estúdio frontal, enquadramento
controlado. É teto otimista. O número de balcão só sai com coleta própria (§7).

---

## 3. O pipeline, na ordem

```
vídeo → MediaPipe Holistic → landmarks .npy → representação → modelo → sinal
        (PoC/src/extract.py)  57 pontos × 3   Skeleton-DML    ResNet-18
                                               ou grafo        ou ST-GCN
```

| Etapa | Comando | Custo |
|---|---|---|
| Baixar vídeos | `datasets/ingest*.py` | minutos |
| Extrair landmarks | `PoC/src/extract.py --particao i/N` | **horas, CPU, sem GPU** |
| Auditar isolamento | `treino/pretreinar.py --auditar` | segundos |
| Pré-treinar | `treino/pretreinar.py` | ~1,5 h CPU |
| Treinar + avaliar | `treino/treinar.py` | ~2,5 h CPU / minutos GPU |
| Modelo de entrega | `treino/treinar.py --final` | idem, 1 rodada |
| Exportar | `treino/exportar.py` | minutos |

A extração é CPU e não acelera em GPU — fica na máquina local. O treino vai para GPU
(Kaggle ou Colab, ver §6).

### 3.1 Auditoria antes de qualquer treino

```bash
python pretreinar.py --corpus <dir> --manifesto-avaliacao <csv> --auditar
```

Confere isolamento e proveniência e **sai sem construir modelo**. É pré-requisito, não
opcional: sem ela o pré-treino pode estar consumindo o conjunto de avaliação.

### 3.2 Pré-treino contrastivo

```bash
python pretreinar.py --arquitetura resnet --objetivo contrastivo \
  --corpus ../PoC/data/landmarks-pretreino-auditado \
    --pessoa-val V03 --epocas 15 --lr 1e-4 --batch 64 \
    --p-classes 32 --k-exemplos 2 --saida <dir>
```

`--corpus` **pode ser repetido** para diretórios V-LIBRASIL disjuntos, todos auditados.
Não acrescentar WLASL ou MINDS: o pré-treino atual rejeita ambas as fontes.

Perda SupCon + amostrador P×K; a cabeça de projeção é descartada depois. A época é
escolhida por **recuperação top-1**, não por perda de validação: a perda dava 0,0 em lotes
sem pares positivos e elegia sempre a primeira época.

### 3.3 Treino supervisionado + LOSO

```bash
python treinar.py --arquitetura resnet --fontes minds \
    --epocas 30 --lr 1e-4 --wd 1e-4 --batch 64 --dispositivo cuda \
    --semente 20260916 --inicializar <checkpoint-do-pretreino> --saida <dir>
```

Produz `relatorio.md` (acurácia por rodada, por sinal, pares confundidos, config de
reprodutibilidade) e `matriz_confusao.npy`. **Não** salva checkpoint — LOSO mede, não
entrega.

Flags que mudam a representação:

| Flag | Efeito | Estado |
|---|---|---|
| *(padrão)* | x, y; lacunas curtas de mão imputadas | **em uso** |
| `--sem-imputacao` | desliga a imputação | comparação |
| `--com-z` | acrescenta a terceira coordenada | **em teste** (§5) |
| `--z-recentrado` | com `--com-z`: devolve o z da mão ao punho | **em teste** (§5) |
| `--ossos` | GCN: soma vetores de osso aos canais (dobra) | **+18,9 pp, 8/8 folds** |
| `--movimento` | GCN: soma a variação temporal dos canais (dobra) | em teste |
| `--adjacencia-adaptativa` | GCN: matriz aprendida somada à anatômica | em teste |
| `--kernel-temporal N` | GCN: kernel da convolução temporal (padrão 9) | em teste |
| `--semente N` | fixa inicialização, embaralhamento e augmentação | **obrigatório ao comparar** |

As três primeiras **compõem e dobram os canais** na ordem em que o dataset aplica:
coordenadas → ossos → movimento. Com x,y: 2 → 4 → 8. Com x,y,z: 3 → 6 → 12. A conta vive
só em `treinar.canais_gcn()`; espalhá-la faria a construção do modelo e a contagem de
parâmetros divergirem em silêncio.

### Onde estão os parâmetros do ST-GCN

~80% deles estão nas **convoluções temporais**, não no grafo. Consequências práticas:

| Mudança | Custo |
|---|---|
| qualquer canal a mais (z, ossos, movimento) | +306 a +3.060 (≤0,7%) |
| adjacência adaptativa | +6.498 |
| kernel temporal 9 → 5 | **−163.840** (−35%) |

Por isso features de entrada são praticamente de graça e o kernel é o único item que
**encolhe** o modelo — e ele nunca foi calibrado.

### Comparação pareada: o que `--semente` garante e o que não garante

`--semente` fixa três coisas que antes vazavam: a inicialização do modelo, a ordem dos
lotes e a augmentação (por semente, rodada, época e índice).

**A ordem dos lotes usa um gerador próprio do DataLoader, não o RNG global.** Isso não é
detalhe: o RNG global é consumido pela construção do modelo, e variantes com contagens de
parâmetros diferentes o deixam em estados diferentes. Antes da correção, "mesma semente"
produzia ordem de lotes diferente para cada variante — a comparação pareada que a flag
prometia não existia. Verificado: cinco variantes com canais e kernels distintos agora
produzem lote idêntico.

⚠️ **Semente igual não torna a escolha do melhor entre N variantes livre de viés.** O
vencedor de uma varredura ganha em parte por mérito e em parte por sorte, e o número dele
é otimista. Confirmar exige **rerodar controle E candidato numa semente nova** — só o
vencedor testaria sensibilidade à semente, não superioridade. Estimativa realmente livre
do viés de seleção exigiria avaliação externa ou validação aninhada, que não temos.

### Artefatos por rodada, e retomada

Cada rodada LOSO grava `rodadas/NN-<pessoa>.json` (acurácia, época escolhida, predições e
verdadeiros por clipe) assim que fecha. Uma execução LOSO do ST-GCN leva ~55 min; oito
rodadas passam de 7 h, e sessão de nuvem morre por limite de tempo. Com o parcial em
disco, reexecutar o mesmo comando **retoma de onde parou** em vez de recomeçar.

### 3.4 Modelo de entrega

```bash
python treinar.py <mesmas flags> --final --saida <dir>
```

Treina com **todas as 8 pessoas** e salva `modelo_final.pt`. Não há holdout: a estimativa
de qualidade dele **é o número da LOSO rodada antes**, com a mesma configuração. O
`treinar.py` registra isso na proveniência (`"validacao_sobrepoe_treino": true`) para
ninguém citar a acurácia dele como teste.

### 3.5 Exportação

⚠️ **`exportar.py` vive em `claude/libras-detection-model-53kd30`**, não nesta branch — é
trabalho da Frente A e ainda não foi mergeado. Os comandos abaixo pressupõem essa branch.

```bash
python exportar.py --checkpoint <dir>/modelo_final.pt \
                   --saida ../models/sinal_classifier.tflite
python exportar.py --smoke     # valida o toolchain sem checkpoint
```

Gera três arquivos: o `.tflite`, um `.json` com rótulos, contrato de entrada, hash e
proveniência, e um `.labels.txt`. O `SignClassifier` do app consome isso.

**Contrato padrão é `landmarks`, não imagem:** o `.tflite` recebe `(1, T, P, 2)` e a
montagem Skeleton-DML acontece **dentro do grafo**. O modo `imagem` existe, mas joga para
o app reproduzir transposição, empilhamento, clip e resize — cada passo uma chance de
divergir do treino em silêncio.

**T é fixo no grafo** (padrão 96 frames). No treino T varia por clipe (70 a 232) e o
resize absorve; na exportação o app precisa entregar exatamente T frames.

---

## 4. Quantização — o que ela é e o que ela não é

| Modo | Tamanho | Precisão dos pesos | Divergência de logit |
|---|---|---|---|
| `nenhuma` | 45,0 MB | float32 | 1,8e-07 |
| `float16` | 22,5 MB | float16 | 4,1e-04 |
| `dinamica` | 11,3 MB | int8 | 5,5e-03 |

`dinamica` é int8 **só nos pesos**, ativações em float — por isso **não precisa de dataset
de calibração nem de retreino**. É pós-treino: pega o modelo pronto e converte.

**Calibração não melhora acurácia.** Ela só mede faixas de ativação, e só é necessária na
quantização **inteira completa** (ativações também em int8), que ficou de fora justamente
porque calibrar com dado não representativo produz um modelo que converte, roda e erra.

⚠️ **Os tamanhos são fatos; o efeito na acurácia não foi medido.** Os números acima vêm de
`--smoke`, com pesos aleatórios — validam a conversão, não a qualidade. Antes de mandar um
modelo quantizado para o aparelho, **rode a LOSO com ele**.

---

## 5. Decisões atuais e a base de cada uma

| Decisão | Base | Status |
|---|---|---|
| **ResNet-18 é o modelo do MVP** | 93,4% contra 73,9% do GCN, mesma régua | decidido para o MVP |
| Avaliação é LOSO no MINDS | o dispositivo é institucional, vê pessoa nova sempre | fixo |
| MINDS nunca no pré-treino | é o conjunto de avaliação | fixo |
| Pré-treino é contrastivo, não classificação | classificação deu 0,2% (1.353 classes × 3 clipes) | decidido |
| Imputar lacunas curtas de mão (≤5 frames) | 95,1% contra 93,4%, 7 de 8 folds acima | **ligado por padrão** |
| Usar só x, y | **herdada da PoC do DTW, nunca medida aqui** | **em teste** |
| 57 pontos (15 pose + 21 + 21) | tronco, braços e 7 âncoras faciais; face mesh fora | fixo |
| Normalizar por ombros | origem no ponto médio, escala = distância | fixo |

### O que está em teste agora

A PoC das 3 coordenadas ([`poc-tres-coordenadas.md`](poc-tres-coordenadas.md)) mede se o
`z` ajuda. O `dados.py` descarta a terceira coordenada desde o primeiro commit, e a
justificativa (64,0% → 68,7% ao desligar o z) foi medida no **DTW 1-NN** — que soma
distâncias cruas e não tem defesa contra um canal de escala incoerente. ResNet e GCN têm
peso aprendido. A conclusão não transfere em nenhuma das duas direções.

Isso deixou de ser curiosidade porque `extracao-landmarks-plano.md` decidiu que o app vai
extrair 3 canais, e o modelo é construído com `canais_ent=2`: **erro de shape** na
integração, não diferença de acurácia.

---

## 6. Números medidos

**O ST-GCN alcançou a ResNet com 24× menos parâmetros** — não a superou. A melhor ResNet
medida aqui é **95,1%**; o melhor ST-GCN é 94,6/94,9%. Ver
[`decisao-arquitetura-modelo.md`](decisao-arquitetura-modelo.md), incluindo por que uma
versão anterior afirmou superioridade e estava errada.

Etapa 1 do notebook de variantes (`gcn-20260910-235322`, commit `d48b313`):

| Configuração | LOSO | Parâmetros |
|---|---|---|
| **ST-GCN + ossos + z** | **94,6% / 94,9%** | 0,47M |
| ST-GCN + ossos + z + movimento ⚠️ | 94,5% | 0,47M |
| ST-GCN + ossos (controle) | 92,5% | 0,46M |
| ST-GCN + ossos + kernel 5 | 92,2% | **0,30M** |
| ST-GCN + ossos + movimento ⚠️ | 91,9% | 0,47M |
| ST-GCN + ossos + adj. adaptativa | 89,9% | 0,47M |

⚠️ As duas variantes com `--movimento` mediram uma implementação quebrada: a máscara de
validade estava inerte durante o treino. Corrigido em 11/09; **precisam ser refeitas antes
de descartar movimento**.

⚠️ **Estes relatórios ainda não estão commitados.** Pelo critério que este documento aplica
ao "GCN em 95,6%" de terceiros, são números não verificados até entrarem no repositório.

⚠️ **A comparação entre arquiteturas não é pareada em orçamento:** o GCN recebe 1.080
atualizações de peso, a ResNet 270 (controle) ou 540 (imputada). O
`decisao-arquitetura-modelo.md` §6 registra que a ResNet **não convergiu**. Mudar só o
batch da ResNet moveu +2,1 pp.

Histórico, na mesma régua:

| Configuração | LOSO | Onde |
|---|---|---|
| Chance aleatória (20 classes) | 5,0% | — |
| DTW 1-NN (PoC, 10 sinais) | 70,0% | `PoC/results/relatorio.md` |
| ResNet-18, sem imputação | 93,4 / 93,5 / 91,8% | `resultados-resnet/relatorio.md` |
| **ResNet-18, com imputação** | **95,1%** | `resultados-resnet-imputado/relatorio.md` |
| ST-GCN, config de fine-tuning | 44,6% | `resultados-gcn/relatorio.md` — **subtreinado** |
| ST-GCN, orçamento de treino do zero | 73,9% | `decisao-arquitetura-modelo.md` |
| Literatura (mesma base, mesmo protocolo) | 93-94% | Alves 2024; dos Santos 2025 |

### ⚠️ Sobre o "GCN em 95,6%"

`docs/extracao-landmarks-plano.md` e `docs/sign-boundary-detector-plano.md` (em `dev`)
afirmam que o GCN mede **95,6%**, que os 44,6% vinham de um bug de pontos, e concluem que
o GCN é o alvo do `.tflite`.

**Esse número não foi localizado em nenhuma branch do repositório.** A fonte citada
(`resultados-gcn/relatorio.md`) diz 44,6% nas sete branches. A explicação registrada para
os 44,6% é **orçamento de treino**, não bug: o relatório mostra `"epocas": 30, "lr": 1e-4`
— config de fine-tuning, ~270 atualizações de peso. Com orçamento de treino do zero
(~2.160), o mesmo modelo foi a 73,9%.

Até existir um `relatorio.md` commitado com 95,6%, **este documento trata o número como
não verificado** e mantém a ResNet como modelo do MVP. Se a execução existir, é ótima
notícia — o GCN é 24× menor — mas ela precisa estar no repositório, e a decisão de
arquitetura muda com ela.

---

## 7. Regras que evitam número falso

Cada uma existe porque o erro correspondente já aconteceu aqui.

1. **Variância de ~1,7 pp entre execuções.** Diferença de média abaixo de ~2 pontos é
   ruído. Olhe a **consistência entre folds**: a imputação subiu só 1,7 pp na média, e o
   que sustentou a conclusão foi 7 de 8 folds acima e nenhum abaixo.
2. **`--semente` ao comparar variantes.** Sem ela cada execução parte de pesos diferentes,
   e o ruído é da mesma ordem do efeito. Semeia por rodada (`semente + i`) — fixar tudo
   igual faria as 8 partições compartilharem a inicialização e reduziria a variância pelo
   motivo errado.
3. **Orçamento de treino antes de comparar arquiteturas.** O GCN "perdeu" por 49 pontos
   com hiperparâmetros de fine-tuning e recuperou 29 com orçamento adequado.
4. **Controle negativo no selftest.** Com rótulos aleatórios, a acurácia tem de ficar na
   chance. Se subir, há vazamento — o erro mais caro, porque produz número bonito e falso.
5. **Proveniência por amostra e por checkpoint.** Cada `.npy` tem sidecar com origem e
   hash; cada checkpoint registra inventário, partição e hash do código.
6. **Testes estocásticos precisam de média.** Um teste sintético de uma rodada passava em
   CPU (100%) e falhava em GPU (41,7%) com o mesmo código.

---

## 8. Onde rodar

| | Background | Sobrevive à queda de conexão |
|---|---|---|
| **Kaggle** (*Save & Run All*) | sim | sim — vira output da versão |
| Colab grátis | não | só com `EXP` no Drive |
| Colab Pro | sim | idem |

Kaggle é a recomendação: grátis, cota semanal de GPU folgada para as ~15 h que o projeto
inteiro precisa, e execução em background. Passo a passo em
[`poc-tres-coordenadas.md`](poc-tres-coordenadas.md).

Os pacotes de landmarks são **privados** e vão em pastas separadas por corpus — nunca
mescladas. V-LIBRASIL é **CC BY-NC-ND**: não publicar vídeos, landmarks, sidecars, pacotes
ou checkpoints derivados. Uso em produto exige resolver a licença, não apenas atingir
acurácia.

---

## 9. O que ainda falta

| Item | Por quê | Estado |
|---|---|---|
| **PoC das 3 coordenadas** | decide se o app extrai 2 ou 3 canais | rodando |
| **Portar o export para 3 canais** | `exportar.py` recusa 3D hoje; se o z ganhar, bloqueia a entrega | **sem dono** |
| Rodar o pré-treino contrastivo no corpus real | implementado e testado, nunca executado em V-LIBRASIL completo | pendente |
| Medir acurácia do modelo quantizado | só o tamanho foi medido | pendente |
| Medir latência no aparelho | nunca medida, em nenhuma arquitetura | pendente |
| Resolver o "GCN 95,6%" | decisão de arquitetura em `dev` apoiada em número não localizado | **bloqueia** |
| Número do ST-GCN com ossos (B2) | implementado, sem medição | na PoC |
| Coleta própria | ver abaixo | **não iniciada** |

### A coleta própria é o item que mais muda o projeto

Todo número deste documento é sobre **vídeo de estúdio, frontal**. A lacuna de ângulo de
câmera — óculos filmando de cima, de lado — **não é mensurável com nenhuma base pública
que temos**. E ~9 termos institucionais (`marcar`, `atendimento`, `senha`, `protocolo`,
`consulta`, `idade`, numerais) não existem em nenhuma base pública com mais de uma pessoa.

Se o time gravar, a decisão que precisa ser tomada **antes da gravação** é a divisão:

- **Um grupo de pessoas fica reservado** e não é tocado até a medição final. É a única
  coisa que responde "funciona no balcão?".
- **O resto entra no fine-tuning.**
- **Calibração sai de graça** de qualquer um dos dois — e é o uso de menor valor, porque
  não melhora acurácia.

Usar os mesmos vídeos para treinar e medir destrói o número. Errar essa divisão na hora da
gravação é caro de desfazer.

---

## 10. Como validar que nada quebrou

```bash
cd computer-vision-model/treino    && python selftest.py    # pipeline de treino
cd computer-vision-model/datasets  && python selftest.py    # receita de ingestão
cd computer-vision-model/PoC       && python src/selftest.py # extração e DTW
cd computer-vision-model/treino    && python test_entrada_poc.py
cd computer-vision-model/treino    && python test_proveniencia.py
```

---

## 11. Princípio

Quase todo erro caro deste projeto veio de **aceitar um número sem perguntar o que ele
mede**. O 44,6% do GCN parecia veredito e era configuração. Os 93,4% parecem precisos e
têm ±1,7. A validação em 0,2% parecia fracasso do pré-treino e era a tarefa sendo
impossível. Os 4-6 pontos do z valiam para o DTW e foram herdados sem teste.

Antes de concluir de um número: *o que exatamente foi medido, com qual orçamento, e quanto
ele varia se eu rodar de novo?*

# `treino/` — o classificador de sinais

Esta pasta treina o modelo que reconhece os sinais. Consome os landmarks que
`../PoC/src/extract.py` produz e responde à única pergunta que importa para o
produto:

> **Qual a acurácia com uma pessoa que o modelo nunca viu?**

Os óculos são institucionais: atendem alguém novo a cada atendimento, sem
calibração. Por isso toda avaliação aqui deixa uma pessoa **inteira** fora do
treino (*leave-one-signer-out*), exatamente como o baseline DTW da PoC.

---

## O que está medido, e o que foi escolhido

| Arquitetura | LOSO | Parâmetros | Orçamento de treino |
|---|---|---|---|
| **ST-GCN + ossos + z recentrado** | **94,6% / 94,9%** | **0,47M** | 1.080 atualizações |
| ResNet-18 + imputação | 95,1% | 11,25M | 540 |
| ResNet-18 (controle da sessão de 10/09) | 93,0% | 11,25M | 270 |
| ST-GCN + ossos | 91,0% / 92,5% | 0,46M | 1.080 |
| ST-GCN (x, y apenas) | 72,1% | 0,46M | 1.080 |
| Baseline DTW 1-NN da PoC (10 sinais, 11 pessoas) | 70,0% | — | — |
| Literatura no MINDS-Libras, mesmo protocolo | 93–94% | — | — |

**O ST-GCN é o modelo de entrega**, decidido em 2026-09-11. Ele **empatou** com a
melhor ResNet — 94,6/94,9 contra 95,1 — usando **24× menos parâmetros**. Exportado,
dá ~1,9 MB em float32 e 0,47 MB em int8, contra 45 MB e 11,3 MB da ResNet. Para um
modelo que precisa virar `.tflite` no celular, empatar com 1/24 do tamanho decide.

Duas ressalvas que precisam acompanhar qualquer citação desses números:

1. **Foi observada variação de ~1,7 ponto entre execuções.** Isso não estabelece
   um corte estatístico de 2 pp; comparar execuções pareadas e respeitar o
   agrupamento por pessoa. Ver a [revalidação](../../docs/validacao-visao-app-2026-09-14.md).
2. **É tudo vídeo de estúdio**, frontal e controlado. Teto otimista. Robustez a
   mudança de ponto de vista segue não medida — nenhuma base pública nossa tem
   vídeo fora do frontal de estúdio.

### O que fez o ST-GCN sair de 72% para 94%

Nada de dado novo: duas features derivadas dos landmarks que já existiam.

- **Ossos** (vetores entre pontos conectados no grafo do esqueleto): +18,9 pontos,
  em 8 de 8 rodadas.
- **z recentrado**: +2,1 pontos, confirmado em segunda semente.

Uma versão anterior deste README afirmava que o ST-GCN estava ~19 pontos atrás da
ResNet, com 0,739 e 0,446. **Esses números eram configuração, não arquitetura**: o
0,446 veio de hiperparâmetros de fine-tuning (~270 atualizações de peso) aplicados
a um modelo que precisa de orçamento de treino do zero, e o 0,739 ainda era sem
ossos e sem z. Ao comparar arquiteturas, confira quantas atualizações cada uma
recebeu. O histórico completo está em
[`../../docs/decisao-arquitetura-modelo.md`](../../docs/decisao-arquitetura-modelo.md).

---

## As duas representações

### ST-GCN — grafo do esqueleto

O modelo de entrega. Trata os 57 pontos como os **nós de um grafo** com a topologia
real do corpo (dedo ligado ao dedo, pulso ao cotovelo, cotovelo ao ombro), e
convolui ao mesmo tempo no espaço (vizinhos no grafo) e no tempo (frames
adjacentes). Consome coordenadas reamostradas para 64 frames; com `--ossos`, os
vetores de osso entram como canais adicionais.

Não é causal: é um modelo para sinais isolados já segmentados, não a rede de
streaming com atenção descrita na arquitetura de produto.

### Skeleton-DML + ResNet-18 — landmarks como imagem

A alternativa medida; ambas as arquiteturas possuem exportação implementada.

```
  .npy de landmarks            imagem Skeleton-DML            ResNet-18
  (T frames, 57 pontos, x/y)   (57 × 2·T/3 × 3) → 224²        (ImageNet)
        |                              |                          |
        +---- empilha tempo e ---------+                          v
              pontos como imagem                          sinal reconhecido
```

Com ~1.000 clipes, treinar uma rede recorrente do zero disputa com uma CNN que já
vem pré-treinada em milhões de imagens. **Skeleton-DML** é o truque que permite usar
essa CNN: empilha a matriz `pontos × frames` (x e y) como se fosse uma imagem RGB —
cada 3 frames consecutivos viram os 3 canais — e redimensiona para 224×224. De
quebra, resolve as durações diferentes sem padding nem máscara.

Detalhe contraintuitivo e medido: **modelo maior piora**. Na ablação de Alves et
al., ResNet-18 (0,93) bate ResNet-50 (0,90), EfficientNet-B6 (0,89) e MobileNetV4
(0,87). Com dataset pequeno, capacidade sobrando vira memorização.

Referências que motivaram essa escolha
([`../../docs/investigacao-expansao-dataset.md`](../../docs/investigacao-expansao-dataset.md),
Achado D):

| Trabalho | Dataset | Protocolo | Modelo | Resultado |
|---|---|---|---|---|
| dos Santos et al. 2025 | MINDS-Libras | LOSO | Skeleton-DML + ResNet-18 | 0,94 |
| Alves et al. 2024 | MINDS-Libras | LOSO | Skeleton-DML + ResNet-18 | 0,93 |

---

## Arquivos

| Arquivo | Papel |
|---|---|
| `dados.py` | carga dos `.npy`, partições leave-one-signer-out, imputação de lacunas de mão |
| `representacao.py` | landmarks → imagem Skeleton-DML; augmentação (rotação, zoom, translação, espelhamento) |
| `modelo.py` | ResNet-18 ImageNet com a cabeça trocada; salvar/carregar checkpoint |
| `gcn.py` | ST-GCN sobre o grafo de 57 pontos — **o modelo de entrega** |
| `treinar.py` | laço LOSO, relatório e checkpoint final |
| [evidencias_loso.py](evidencias_loso.py) | checkpoints e logits por fold, hashes e retomada estrita |
| `pretreinar.py` | pré-treino num corpus grande (V-LIBRASIL), sem medir acurácia |
| `contrastivo.py` | perda SupCon e amostrador P×K, usados pelo pré-treino contrastivo |
| `entrada_poc.py` | leitura dos landmarks MINDS empacotados, para rodar em Kaggle/Colab |
| `exportar.py` | PyTorch → `.tflite` mais o JSON de contrato |
| `selftest.py` | valida o pipeline inteiro com dados sintéticos, em segundos |
| `test_entrada_poc.py`, `test_export_contrato.py`, `test_proveniencia.py` | regressões |
| `notebook_gpu.ipynb` | pré-treino + fine-tuning em GPU (Colab/Kaggle) |
| `notebook_gcn_variantes.ipynb` | ablações do ST-GCN (movimento, adjacência adaptativa, kernel) |
| `notebook_poc_3d.ipynb` | a PoC da terceira coordenada |

---

## Como rodar

```bash
source ../PoC/.venv311/bin/activate

python selftest.py                        # 1º: valida o encanamento (segundos)
python treinar.py --epocas 5 --folds 1    # 2º: uma rodada curta, para ver de pé

# 3º: exemplo de controle sem pré-treino (8 rodadas; execução longa)
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado \
   --epocas 120 --lr 1e-3 --batch 64 --agendador cosseno --wd 1e-4 \
   --semente 20260917 --salvar-evidencias \
   --saida ../../experimentos-privados/controle-s20260917

# 4º: definir política de época/seed e calibrar antes do treino final.
# --final não é avaliação independente e não aceita --salvar-evidencias.
```

Para a ResNet-18, basta omitir `--arquitetura` (é o padrão). Saídas em
`resultados-gcn/` ou `resultados-resnet/`, ajustável por `--saida`: `relatorio.md`
(acurácia por rodada e por sinal, pares confundidos), `matriz_confusao.npy` e
`modelo_final.pt`.

`modelo.carregar(caminho)` reconstrói a arquitetura salva sem baixar pesos ImageNet.
Checkpoints novos guardam a configuração do GCN (largura, canais, nós, dropout);
os antigos continuam aceitos.

> Os relatórios versionados em `resultados-gcn/` e `resultados-resnet/` são de
> execuções **anteriores** à configuração de entrega — `resultados-gcn/relatorio.md`
> registra os 44,6% da sessão de 09/09. Os números atuais estão em
> [`../../docs/decisao-arquitetura-modelo.md`](../../docs/decisao-arquitetura-modelo.md).

### Evidências LOSO e retomada estrita

**Marco preservado:** o piloto M01 foi executado e seus resultados permanecem
privados. O snapshot anterior à política final é `0b194a1`; reproduzi-lo exige
esse código e o ambiente original, sem reescrever hashes. Situação atual das
pendências em [P1](../../docs/politica-modelo-final-2026-09-14.md) e
[M9](../../docs/m9-diagnostico-roteiro-2026-09-14.md). Android está pausado.

`--salvar-evidencias` preserva, para cada fold concluído, o **melhor modelo
selecionado na validação**, não o último estado da otimização. O JSON de rodada
continua no formato consumido pelos notebooks; os arquivos maiores ficam numa
subpasta para não interferir nos leitores de `rodadas/*.json`:

```text
rodadas/01-M01.json
rodadas/artefatos/01-M01.pt
rodadas/artefatos/01-M01.evidencias.json
```

São registrados logits não calibrados de validação e teste, rótulos na ordem
do modelo, índices verdadeiros/preditos, IDs dos clipes, pessoas e partições,
melhor época (base 1), configuração, representação e hashes de dados, código,
backbone e ambiente. O checkpoint carrega com `modelo.carregar()` e fornece os
metadados exigidos pelo exportador. Não publicar esses artefatos privados.

O marcador de rodada é publicado **por último**, após os dois artefatos,
por substituição de arquivos temporários no mesmo filesystem. Isso protege contra
marcadores incompletos em interrupções usuais; não é transação com garantia contra
queda de energia. Não executar dois escritores na mesma saída. Uma interrupção
antes do marcador exige refazer o fold inteiro: não há retomada de otimizador ou
de época intermediária.

Ao repetir o comando com a flag, todos os folds selecionados já presentes passam
por conferência de identidade, partição, hashes, logits e predições antes de novo
treino. Legados sem evidências e arquivos ausentes/adulterados são recusados, sem
apagá-los. Escolher uma saída nova em vez de misturar protocolos. Uma execução
que já tem evidências não pode ser reaproveitada omitindo a flag. Sem ela, saídas
legadas continuam com suas verificações antigas, não com garantia equivalente.

Mudanças em código-fonte, versões, dados, backbone ou parâmetros (inclusive
dispositivo, threads e workers) invalidam a identidade estrita. `--folds` e
`--saida` não alteram essa identidade; caminhos de dados/backbone podem mudar se
o conteúdo inventariado continuar igual. Para retomar, conservar o snapshot de
código/ambiente original. Hash não é assinatura contra adulteração maliciosa.

Receita candidata com pré-treino (a partir desta pasta; **oito folds completos
não foram executados nesta implementação**, apenas o fold M01 do piloto;
substituir os caminhos por insumos aprovados):

```bash
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado \
   --fontes minds --landmarks /caminho/privado/landmarks-minds \
   --inicializar /caminho/privado/backbone_gcn.pt \
   --epocas 120 --lr 1e-3 --wd 1e-4 --batch 64 --agendador cosseno \
   --semente 20260917 --kernel-temporal 9 --folds 0 \
   --dispositivo cuda --threads 2 --workers 2 --salvar-evidencias \
   --saida ../../experimentos-privados/candidato-s20260917
```

O backbone deve corresponder à receita V-LIBRASIL+MALTA, extras=0, sem WLASL do
[plano vigente](../../docs/validacao-visao-app-2026-09-14.md), não apenas ter um
nome semelhante. Um controle pareado mantém tudo igual exceto inicialização e
saída. O notebook da PoC não foi convertido automaticamente para essa receita.

Regressões em [test_evidencias_loso.py](test_evidencias_loso.py), também chamadas
pelo [selftest.py](selftest.py): recarga e logits, igualdade exata de pesos/RNG,
retomada sem retreino, compatibilidade do glob legado e rejeição de inconsistências.

### Política do ajuste final (P1)

`--final` exige agora `--politica-final ultima` e `--semente` explícita; salva
o último estado do orçamento, sem validação sobreposta e sem avaliação de teste.
Saída deve ser nova/vazia. Comandos antigos sem a política falham antes de treinar.
Para a receita de entrega, a [política v1](../../docs/politica-modelo-final-2026-09-14.md)
fixa seed **20260917**, orçamento **120 épocas** e o backbone já aprovado, não
escolhidos pela acurácia do final. Não combinar com `--salvar-evidencias`.
Metadados identificam seleção/época e ausência de avaliação independente.
**O treino real final ainda não foi executado; 96,6% LOSO não é sua acurácia medida.**
Guardas em [test_politica_final.py](test_politica_final.py).

O [notebook final](notebook_treino_final.ipynb) agora exige SHA completo do código
aprovado (`LIBRAS_COMMIT_FINAL` ou `COMMIT_APROVADO`), snapshot limpo, preparação
MINDS estrita via [entrada_final.py](entrada_final.py) e `--inventario-final`.
Não há fallback para branch/HEAD nem mistura de extrações. A guarda repete a
conferência dos bytes e dos clipes efetivamente carregados antes do modelo.
Publicar as correções e informar seu SHA antes do Kaggle; nenhuma run foi iniciada.
Ver [procedimento e testes](../../docs/preparacao-final-e-calibracao-experimental-2026-09-14.md).

### Calibração experimental de confiança

[calibracao.py](calibracao.py) recebe `--evidencias` (repetível), `--acc-minima`
(padrão 0,90) e `--saida` nova. Exige cada bundle completo descrito acima:
evidências, checkpoint e marcador de rodada. Confere hashes, contexto histórico,
partições, IDs/pessoas e rótulos; concatena **somente validação**. Não desserializa
pesos para ler logits. Duplicatas e contextos diferentes são recusados.

O JSON produzido usa **schema 2**, com hashes das fontes, checkpoint, contexto e
partição; caminhos absolutos para reabrir as fontes; temperatura; limiar e
métricas do ajuste. Temperatura deve ser positiva, finita e representável como
float32 normal. Alvos fracionários/booleanos, dados vazios ou não finitos são
recusados. Sem limiar que atinja a meta, `limiar_sugerido=null` e
`meta_nao_atingida=true`: limiar 1 não significa rejeição total.

- Um fold: `escopo=checkpoint_loso`, vinculado aos bytes desse checkpoint.
- Vários folds: `escopo=pool_loso_analise`, **não exportável** para outro modelo.
- Sempre `avaliacao_independente=false`, `aprovado_entrega=false` e
   `metricas_medidas_em=mesmos_dados_do_ajuste`. A validação já selecionou a época;
   ECE/cobertura/acurácia aqui não aprovam o final nem a demo.

[exportar.py](exportar.py) aceita `--calibracao` schema 2 de um único
fold com limiar viável, mesmo checkpoint e mesma ordem de rótulos. Reabre fontes,
confere identidades e recalcula a política para T fornecido antes da conversão e
da escrita do sidecar; não reajusta T. Fontes precisam estar acessíveis nos
caminhos registrados. JSON legado deve ser preservado como histórico; uma nova
análise exige saída nova, não troca manual de hashes ou schema.

Sem a flag, o export não adiciona calibração. O limiar sugerido **não é aplicado
automaticamente no app**. Não transferir o pool para o checkpoint final treinado
com todas as pessoas MINDS. O caminho externo experimental descrito abaixo é
separado; não transforma dados expostos em teste independente.
Nenhuma calibração real nova foi executada nesta correção.
Regressões sintéticas em [test_calibracao.py](test_calibracao.py),
[test_calibracao_guardas.py](test_calibracao_guardas.py) e
[test_export_contrato.py](test_export_contrato.py), este com conversor simulado.

### Calibração externa do final — experimental (schema 3)

Por decisão do usuário, preservar os 50 clipes existentes, sem novas pessoas ou
refazer o backbone agora. SupCon usa rótulos; V03 selecionou a época do backbone.
O [manifesto](calibracao_naovista_manifesto.json) declara essa exposição prévia,
sem avaliação independente ou aprovação de entrega.

[calibracao_externa.py](calibracao_externa.py) oferece `protocolo`, `inferir` e
`ajustar`: critérios explícitos de acurácia/cobertura **antes** da inferência,
logits do checkpoint final com pré-processamento do treino e ajuste rastreável.
Saída schema 3, `escopo=checkpoint_final_experimental`; métricas nos mesmos dados
do ajuste. Exportação somente float32, mesmo checkpoint, rótulos e fontes íntegros.
Sem política viável, export recusado. Schema 2 e ausência de calibração continuam
com seus comportamentos anteriores. Não aplicar automaticamente o limiar no app.

Argumentos, limites e ordem operacional no
[procedimento](../../docs/preparacao-final-e-calibracao-experimental-2026-09-14.md#3-calibração-externa-sem-converter-dados-expostos-em-teste-independente).
Testes em [test_calibracao_externa.py](test_calibracao_externa.py): inferência com
pesos sintéticos, guarda de fontes e conversão **simulada**, não validação TFLite real.

### Pré-treino

```bash
python pretreinar.py --auditar --corpus <dir>      # auditoria obrigatória, sem treinar
python pretreinar.py --corpus <dir> --objetivo contrastivo --pessoa-val V03
python treinar.py --fontes minds --inicializar <checkpoint>
```

O primeiro pré-treino por classificação **falhou** — validação em 0,2% contra 0,07%
de chance — e o motivo é estrutural: 1.353 classes com 3 clipes cada, um por
articulador, é tarefa quase não-aprendível. A mesma estrutura, porém, é ideal para
aprendizado **contrastivo**: três execuções da mesma palavra por três pessoas
diferentes formam exatamente o par que ensina *invariância a sinalizante*, que é o
requisito central do produto.

Nenhum número do pré-treino vai para lugar nenhum: o produto dele são os pesos. A
acurácia que vale sai do `treinar.py`, sobre o MINDS, com uma pessoa inteira fora.
Ver [`../../docs/protocolo-pretreino.md`](../../docs/protocolo-pretreino.md).

### PoC: negativos extras no contrastivo

A branch `poc/contrastivo-negativos-extras` acrescenta `--negativos-extras N`
a [pretreinar.py](pretreinar.py), somente com `--objetivo contrastivo`.
O padrão **0 mantém o comportamento anterior**. Com N > 0:

- Clipes de classes raras não são descartados pelo mínimo global antes da
   partição. Apenas os da parte de **treino**, em classes com menos de K clipes
   de treino, podem entrar como extras. MINDS, reservas, proveniência, frames e
   exclusão de rótulos WLASL conflitantes continuam sujeitos às mesmas verificações.
- Cada lote tem **P×K + N** clipes: o núcleo P×K habitual e **um clipe de cada
   uma de N classes extras distintas**. Nenhum rótulo extra se repete no lote.
   Com K > 2, isso continua obrigatório mesmo que a classe rara tenha dois clipes.
- Extras não são âncoras da SupCon, pois não têm pares no lote; entram no
   denominador das âncoras e recebem gradiente como negativos. A perda não mudou.
   Não há garantia de visitar todo o pool por época. Classes podem ser reutilizadas
   entre lotes; um pedido N maior que o número de classes disponíveis é erro.
- O RNG dos extras é separado do núcleo: ativar a opção não muda o sorteio de
   classes/exemplos do núcleo com a mesma semente e o mesmo conjunto de âncoras.
- **Validação e galeria são as mesmas do controle** no mesmo corpus, mínimo e
   pessoa reservada. Raros recuperados não expandem a métrica de recuperação usada
   para selecionar o checkpoint, nem entram em validação.
- A proveniência distingue `ancoras_elegiveis` e `negativos_extras_elegiveis`.
   `otimizacao_elegiveis` inclui ambas: receber gradiente como negativo também é
   participar da otimização. Essas listas são pools elegíveis, não um registro de
   cada amostra sorteada. Os logs mostram o lote e as atualizações por época.

**Comparação:** mantenha corpus, representação, pessoa V03, sementes, P, K,
épocas e hiperparâmetros iguais entre controle N=0 e candidato N>0; use saídas
distintas. O lote maior altera memória, estatísticas de BatchNorm e custo por
passo — mesmo número de atualizações não significa mesmo custo computacional.
O LOSO MINDS é a avaliação final; a recuperação interna não prova ganho no alvo.

**Kaggle:** o notebook desta branch já ativa a PoC — `NEGATIVOS_EXTRAS` e
`PRE_ARGS` na célula 7, `NOME_EXPERIMENTO` distinto. A célula 3 (`BRANCH`)
aponta para `poc/contrastivo-negativos-extras`; ao integrar/mesclar a PoC
noutra branch (ex.: `dev`), atualize `BRANCH` de novo — esse valor não segue
sozinho e um clone na branch errada falha rápido no `--auditar`, não em
silêncio. Não reutilize checkpoints ou folds produzidos pela implementação
anterior de negativos extras. O manifesto do notebook detecta mudanças de
código e argumentos. Não há validação GPU desta PoC nem lançamento automático
de runs.

Os testes em [test_negativos_extras.py](test_negativos_extras.py), também chamados
pelo selftest, exercitam K=2/K=3, reciclagem, gradientes, sementes, parâmetros
inválidos e CLI contrastiva completa com V-LIBRASIL/MALTA/WLASL sintéticos.

### Em GPU

A extração de landmarks é CPU e não acelera em GPU — fica na máquina local. O
treino vai para o Colab/Kaggle por `notebook_gpu.ipynb`, que encadeia auditoria
obrigatória, pré-treino contrastivo e fine-tuning no MINDS. A flag de auditoria e os
sidecars de proveniência são pré-requisitos: sem eles, o fluxo aborta.

São necessários dois pacotes **privados**: `landmarks-minds.tar.gz` e
`landmarks-vlibrasil.tar.gz`, este último incluindo cada `.npy` e seu
`*.npy.proveniencia.json`. A V-LIBRASIL é CC BY-NC-ND; landmarks não eliminam as
restrições da fonte. Não publique dados, sidecars, pacotes ou checkpoints derivados.

### Custo em CPU (12 núcleos, sem GPU)

~20 imagens/s no treino da ResNet → ~35 s por época, ~17 min por rodada, **~2,3 h**
para as 8 rodadas da LOSO. `--threads` deixa folga de núcleos para a máquina seguir
usável (padrão 10 de 12).

---

## Exportação para TFLite (`exportar.py`)

O caminho PyTorch → `.tflite` está implementado para **ResNet-18 e ST-GCN**.
A cabeça GCN inclui ossos, z recentrado, imputação e reamostragem. O smoke
valida conversão e contrato, não acurácia do modelo treinado no app. Ver
[plano integrado de validação](../../docs/validacao-visao-app-2026-09-14.md).

```bash
python exportar.py --smoke                      # valida o toolchain, sem checkpoint
python exportar.py --checkpoint resultados-resnet/modelo_final.pt \
                   --saida ../models/sinal_classifier.tflite
python exportar.py --checkpoint ... --quantizacao float16
```

O backend `ai-edge` tenta `litert_torch` e mantém compatibilidade com
`ai_edge_torch`. A trilha do app registrou torch 2.13 + litert-torch 0.9.4 +
torchvision 0.28 em 13/09; isso não significa que qualquer combinação instalada
funcione. Registrar versões e executar paridade antes de entregar cada artefato.

### O contrato de entrada é `landmarks`, não imagem

O `.tflite` recebe `(1, T, P, D)`, com D=2 para a ResNet e D=2 ou 3 para o GCN,
conforme a representação salva, e **P derivado do checkpoint** (mapa ordenado de
pose mais 42 pontos de mãos; atualmente 15 + 21 + 21 = **57**), e devolve os logits:
a montagem do Skeleton-DML vai **dentro do grafo**. O modo `--modo imagem` existe,
mas joga para o app a tarefa de reproduzir transposição, empilhamento de 3 frames por
canal, clip em ±2,0, mapeamento para [0,1] e resize; errar qualquer um desses passos
não gera erro, só piora a classificação em silêncio.

`--pontos` é opcional e serve como conferência: se discordar do mapa do checkpoint, o
export aborta antes de converter. Checkpoints antigos com mapa de sete pontos de pose
continuam com 49, sem reinterpretação. Sem mapa, o export exige `--pontos` explícito
e marca o layout como **não verificado** — contagem não demonstra ordem.

O JSON registra a pose em uma lista ordenada, os índices das mãos, as coordenadas, o
limite de escala e o contrato temporal. O shape e o dtype efetivos do interpretador
precisam concordar com esse contrato, e a saída precisa ter um logit por rótulo.
Arquivos só devem ser entregues se o comando terminar com sucesso. O lado do app está
em [contrato de integração no companion](../../mobile-app-companion/README.md#5-contrato-do-classificador-tflite).

**T é fixo no grafo exportado** (padrão 96 frames). O GCN reamostra internamente
para 64 e pode imputar mãos; a ResNet usa a representação Skeleton-DML e resize.
Normalização por ombros e segmentação ficam no app. O sidecar descreve o caminho
efetivamente exportado, não um contrato único de ResNet aplicado ao GCN.
O app imputa antes de reamostrar pelo tempo; o efeito da composição com a cabeça
precisa ser medido com dados reais, inclusive lacunas e frames irregulares.

**Exportação 3D:** suportada no GCN com as flags explícitas do checkpoint.
A restrição de coordenadas 3D permanece no caminho ResNet, não no GCN.

### Paridade medida com `--smoke`

Pesos aleatórios, 20 classes, comparando cada saída contra o PyTorch no mesmo tensor:

| `--quantizacao` | Tamanho | Tensores de peso | Maior diferença de logit | Top-1 discordante |
|---|---|---|---|---|
| `nenhuma` (padrão) | 45,0 MB | float32 | 1,8e-07 | 0/8 |
| `float16` | 22,5 MB | 22 em float16 | 4,1e-04 | 0/8 |
| `dinamica` | 11,3 MB | 22 em int8 | 5,5e-03 | 0/8 |

`dinamica` é int8 **só nos pesos** (ativações em float), por isso não precisa de
dataset de calibração. Os tamanhos batem com a aritmética de 11,2M parâmetros.

**Tamanho é fato; efeito na acurácia não foi medido** — `--smoke` usa pesos
aleatórios. Antes de mandar um modelo quantizado para o aparelho, rode a LOSO com ele.
Latência no aparelho também segue não medida. Os números desta tabela pertencem ao
smoke de commits anteriores, não a uma medição nova com 57 pontos.

### Duas armadilhas barradas no código

Ambas do tipo "converte, roda e classifica errado sem avisar":

1. **`--backend onnx` com `--modo landmarks`** é recusado. O `onnx2tf` converte tudo
   para NHWC e elimina o `permute` da cabeça achando que é troca de layout — a ResNet
   passa a convoluir nos eixos trocados (medido: logits divergem 3,6e-01, top-1 muda).
   A ResNet sozinha converte bem por ONNX (4,8e-07); o defeito é a cola.
2. **Flag de quantização ignorada.** Pedir float16 pela chave aninhada
   `target_spec.supported_types` não surte efeito e devolve int8 dinâmico. Por isso
   `_conferir_precisao` abre o arquivo gerado e confere os tipos dos tensores contra o
   que foi pedido.

---

## O que o self-test garante

Além das formas e faixas, ele roda um **controle negativo**: com rótulos aleatórios,
ResNet e GCN não devem obter acurácia alta. Se subir, é sinal de vazamento entre
treino e teste — o erro mais caro possível aqui, porque produz um número bonito e
falso.

Também verifica salvar e carregar GCN e ResNet, preservação das predições e dos
metadados, variantes do GCN e compatibilidade com checkpoints antigos, sem downloads
durante o carregamento.

---

## Dataset

20 sinais do MINDS-Libras, 8 pessoas, 5 repetições (800 clipes). A V-LIBRASIL fica de
fora do treino por padrão (`--fontes minds`): ela tem sempre os mesmos 3 articuladores,
então serve melhor como teste de domínio diferente do que como treino. Ver
[`../datasets/README.md`](../datasets/README.md) e
[`../../docs/vocabulario-mvp-proposta.md`](../../docs/vocabulario-mvp-proposta.md).

## Próximo passo previsto

1. **Validação do ST-GCN treinado no app** — preservar folds/logits, verificar
   paridade com pesos reais e comparar Holistic × Tasks conforme o
   [plano integrado](../../docs/validacao-visao-app-2026-09-14.md).
2. **Fine-tuning no vocabulário de atendimento**, com vídeos próprios (coleta com a
   Associação de Surdos de Goiânia). O `--final` salva o checkpoint pré-treinado
   justamente para isso: se o fine-tuning não render, o modelo geral continua de pé.
   `modelo.construir(congelar_ate=N)` existe para essa etapa, quando houver pouco dado
   por classe.

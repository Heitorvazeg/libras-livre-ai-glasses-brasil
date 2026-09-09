# Extração de landmarks — unificação mobile + treino, e orquestração com o reconhecimento

> Plano de implementação da extração/pré-processamento de landmarks que o app
> vai rodar **on-device**, alimentando `SignBoundaryDetector`
> (`docs/sign-boundary-detector-plano.md`) e o classificador GCN `.tflite`
> (mesmo documento, §5). A pergunta que este documento responde: **como
> replicar, no Kotlin do celular, exatamente o mesmo pipeline de
> pré-processamento que `computer-vision-model/treino/` usa em Python** —
> porque um descompasso aqui não quebra com erro, quebra silenciosamente
> (o modelo recebe uma distribuição diferente da que aprendeu).

---

## 1. Objetivo

Dois problemas, uma causa raiz comum (o repo tem duas versões conflitantes de
"como extrair landmarks", uma morta e uma real):

1. **Alinhar o código e a documentação com o pipeline que está de fato ativo**
   (`PoC/src/extract.py` + `treino/`), deprecando o scaffold que nunca saiu do
   lugar (`computer-vision-model/src/` + `scripts/01-03`) e corrigindo
   documentação que ainda descreve o esquema antigo.
2. **Desenhar o pré-processamento on-device no app mobile** — hoje a
   normalização só existe no servidor da PoC (`PoC/api/server.py`), que este
   projeto está deixando de usar pra classificação de sinal
   (`sign-boundary-detector-plano.md` §5). Precisa migrar pro celular, byte a
   byte compatível com o que o modelo foi (e será re-)treinado pra esperar.

## 2. Fonte de verdade — o que `treino/` realmente faz

Não é o que o `README.md` raiz do `computer-vision-model` descreve (ver §6).
Verificado direto no código:

### 2.1 Estrutura do vetor de pontos (`treino/gcn.py`)

**57 nós**, nesta ordem exata:

```
0-14   pose  (config.pose_indices, PoC/config.yaml)
15-35  mão esquerda (21 pontos, MediaPipe)
36-56  mão direita (21 pontos, MediaPipe)
```

Os 15 pontos de pose, na ordem do dicionário `pose_indices`:

| Grupo | Pontos | Índices dentro do bloco de pose (0-14) |
|---|---|---|
| Face (7) | nariz, olho_esq, olho_dir, orelha_esq, orelha_dir, boca_esq, boca_dir | 0-6 |
| Tronco (4) | ombro_esq, ombro_dir, quadril_esq, quadril_dir | 7,8,13,14 |
| Braços (4) | cotovelo_esq, cotovelo_dir, pulso_esq, pulso_dir | 9,10,11,12 |

Total: **42 (mãos) + 4 (tronco) + 4 (braços) + 7 (face) = 57 pontos.**

### 2.2 Grafo (arestas anatômicas)

Cada mão usa a topologia padrão de 21 pontos do MediaPipe (dedos + palma). A
pose conecta nariz-olhos-orelhas-boca, cabeça-ombros, ombros entre si,
braço-a-braço, e tronco (ombros-quadris). **O que conecta pose às mãos**: o
pulso da pose (índices 11 e 12 dentro do bloco de pose) liga ao punho de cada
mão (nó 0 de cada bloco de mão) — sem essa aresta, as mãos ficariam
componentes isoladas do grafo.

### 2.3 Canais e tempo

- **Hoje: 2 canais (x, y), sem z.** `treino/dados.py` carrega o `.npy` (que
  tem 3 dims) e descarta a terceira na leitura: `arr[:, :, :2]`.
  `treino/gcn.py` constrói o modelo com `canais_ent=2` por padrão.
- **Este plano usa 3 canais (x, y, z)** — decisão explícita (§3, item 2),
  contra o que está medido hoje (ver a ressalva ali).
- **Tempo reamostrado pra 64 frames fixos** (`T_FIXO`), por interpolação
  linear, **não causal** — o modelo classifica o clipe inteiro de uma vez, só
  depois de completo. Isso casa bem com o `SignBoundaryDetector`: ele entrega
  um segmento fechado (do boundary anterior até o atual), e só then a
  reamostragem acontece.

### 2.4 Normalização (`PoC/src/extract.py`, §5.2)

1. Converte coordenadas normalizadas do MediaPipe (0..1) pra pixels:
   `x·W`, `y·H`, `z·W` (usa a largura pro z também — mantém a mesma escala
   entre os dois eixos horizontais).
2. Origem = ponto médio entre os ombros.
3. **Escala = distância entre os ombros, medida só em x,y** (o z de pose é
   descrito como "ruidoso demais para servir de escala" — mesmo usando z nos
   pontos, §3 item 2, a escala continua vindo de x,y).
4. Cada ponto vira `(ponto − origem) / escala`.
5. Frame descartado se um dos ombros tiver `visibility < min_visibilidade`
   (0.5 no config) — sem ombro confiável não dá pra normalizar.

### 2.5 Imputação de lacunas de mão (`treino/dados.py`, `imputar_maos`)

Etapa que **nenhum documento mobile tinha até agora**. Medido: 51,7% dos
frames chegam sem nenhuma mão detectada; como ausência vira zero (a própria
origem), cada aparecimento/desaparecimento "teleporta" a mão pro meio do
peito — um salto de ~30% da amplitude total dos dados, que o modelo via como
se fosse parte do sinal. A correção: lacunas **curtas** (≤5 frames) são
interpoladas linearmente entre as duas detecções vizinhas; lacunas **longas**
continuam zeradas (ausência real, ex.: sinal de uma mão só).

### 2.6 Dataset e resultado medido — por que GCN, mesmo underperformando

`treino/README.md`: 20 sinais do MINDS-Libras, 8 pessoas, 5 repetições (800
clipes) — vocabulário já expandido, igual ao `config.yaml`/`PoC/config.yaml`
atuais (20 palavras, não mais 10).

Dois modelos já foram treinados e avaliados nesse protocolo (leave-one-signer-out):

| Modelo | Acurácia medida | Parâmetros | Formato de entrada |
|---|---|---|---|
| Skeleton-DML + ResNet-18 (`resultados-resnet/relatorio.md`) | **93,4%** (bate com a literatura) | ~11M | imagem 224×224×3 (`treino/representacao.py`) |
| **GCN** (`resultados-gcn/relatorio.md`) | **44,6%** | ~0,4M | grafo (2, 64, 57) — o que este documento assume |

GCN é o alvo deste plano **apesar** do resultado atual (decisão registrada em
§3, item 1) — o conserto do modelo (por que 44,6% e não ~93%, igual ResNet)
é dependência bloqueante, mas **fora do escopo deste documento**, que é só
extração/pré-processamento mobile.

## 3. Decisões tomadas

1. **GCN é o alvo pro `.tflite`, mesmo com 44,6% de acurácia medida hoje.**
   Motivo: é o único candidato pequeno o bastante pro celular (~0,4M
   parâmetros vs. ~11M da ResNet-18, que teria viabilidade de rodar em
   `.tflite` não avaliada). O conserto da acurácia (bug de treino,
   hiperparâmetro, mais épocas — não investigado) é trabalho de
   `computer-vision-model/treino/`, tratado aqui como dependência externa
   bloqueante pra Fase de integração real (§7), não como algo a resolver
   neste plano.

2. **Usar x, y, z (3 canais), não só x, y — decisão consciente contra um dado
   medido.** `PoC/config.yaml` documenta, medido em 430 clipes: desligar o z
   melhorou a acurácia do baseline DTW em ~4-6 pontos (64,0% → 68,7%),
   porque o z da mão é relativo ao punho e o z da pose é relativo ao quadril
   — referenciais diferentes somados no mesmo vetor viravam ruído pro DTW.
   **Essa medição foi no DTW, não no GCN** — um modelo com peso aprendido por
   aresta pode, em tese, aprender a calibrar essa diferença de referencial de
   um jeito que uma métrica de distância fixa não consegue. Não está
   validado que o GCN se beneficia do z; também não está validado que ele
   sofre o mesmo problema do DTW. Fica registrado como aposta explícita, a
   confirmar quando houver `.tflite` de verdade pra comparar (ver §8).

3. **57 pontos = 42 mãos (as DUAS) + 4 tronco + 4 braços + 7 face —
   `computer-vision-model/src/` (1 mão só) está morto e não é a referência.**
   Esse scaffold (`src/data/landmark_extraction.py`,
   `src/data/dataset.py`, `scripts/01_extract_landmarks.py`) nunca terminou
   de ser implementado — `extract_dir()` e as três funções de
   `dataset.py` são `NotImplementedError`; o driver script quebra se
   rodado. Só 3 commits tocaram esses arquivos desde a carga inicial, e o
   próprio `config.yaml` raiz documenta o problema sem nunca corrigi-lo
   ("Corrigir lá... ANTES de subir este número" — nunca corrigido). Fonte de
   verdade real é `PoC/src/extract.py` + `treino/` (§2).

4. **Normalização, imputação de lacunas e reamostragem migram pro celular**,
   deixando de existir só no servidor da PoC. Motivo:
   `sign-boundary-detector-plano.md` (§5) já decidiu que a classificação vira
   local (GCN `.tflite`), então não tem mais servidor no caminho pra fazer
   esse trabalho — precisa acontecer no app.

5. **Normalização é um componente COMPARTILHADO entre `SignBoundaryDetector`
   e o classificador, não duplicada.** `sign-boundary-detector-plano.md` §4.1
   já antecipava precisar da mesma normalização (pra medir deslocamento em
   unidades comparáveis entre pessoas/distâncias de câmera). Implementar duas
   vezes é como duas heurísticas divergindo silenciosamente com o tempo —
   mesmo risco que já foi identificado antes neste projeto (ver
   `sign-boundary-detector-plano.md` §2).

## 4. O pipeline a implementar no app (`libras/`)

Substitui o que hoje só existe no servidor. Ordem exata, replicando §2:

```
FrameLandmarks (pose[33] + leftHand[21]? + rightHand[21]?)
        │  já é o que LandmarkExtractor.kt produz hoje — sem mudança aqui
        ▼
① Subconjunto de pose → 15 pontos, na ordem de pose_indices (§2.1)
        │  novo: hoje esse subconjunto só existe no servidor (frame_normalizado)
        ▼
② Conversão pra pixel (x·W, y·H, z·W) usando width/height do frame
        │  já disponíveis no payload hoje (LandmarkApi.kt já manda width/height)
        ▼
③ Normalização: origem = meio dos ombros, escala = distância entre ombros (x,y)
        │  aplica aos 57 pontos, incluindo z (§3, item 2)
        ▼
④ Vetor de 57 pontos por frame: [pose(15) | mão_esq(21) | mão_dir(21)]
        │  mão ausente → zeros (convenção já usada hoje)
        ▼
⑤ Imputação de lacunas curtas de mão (§2.5) — NOVO, não existe em lugar nenhum hoje
        ▼
⑥ (SignBoundaryDetector consome a sequência daqui pra medir deslocamento —
        já documentado em sign-boundary-detector-plano.md §4.1)
        │
        │  quando o SignBoundaryDetector fecha um segmento (boundary):
        ▼
⑦ Reamostragem pra 64 frames fixos (interpolação linear, por ponto e canal)
        ▼
⑧ Tensor (3, 64, 57) → SignClassifier.classify() → `.tflite`
```

### 4.1 Onde isso vive

`libras/LandmarkNormalizer.kt` (novo) — cobre ①-⑤, chamado pelo
`LandmarkPipeline.kt` no mesmo ponto onde os frames já são extraídos, antes
de `SignBoundaryDetector.onFrame()` e antes de acumular pro classificador.
Único dono da normalização — nem `SignBoundaryDetector` nem `SignClassifier`
reimplementam esse cálculo (§3, item 5).

`libras/TemporalResampler.kt` (novo, ou função dentro de `SignClassifier.kt`)
— cobre ⑦, chamado só quando um segmento fecha (não frame a frame).

### 4.2 Ambiguidades a resolver durante a implementação, não neste documento

- Exatamente como `MediaPipe Pose` (33 pontos) mapeia pros índices que
  `pose_indices` espera (0,2,5,7,8,9,10,11,12,13,14,15,16,23,24 — conferir
  contra a tabela do `config.yaml`) — mecânico, mas precisa de teste unitário
  comparando contra a saída do `extract.py` num mesmo frame.
- `visibility` só existe nos 33 pontos de Pose (`LandmarkExtractor.kt` já
  extrai isso) — os pontos de mão do MediaPipe Hands não têm visibility;
  confirmar que `min_visibilidade` (§2.4) se aplica só aos ombros, como o
  `PoC/api/README.md` já documenta pro fluxo antigo.

## 5. O que fica fora deste plano (parte "arrumar o repo")

Achados da reanálise, genuinamente sobre extração de landmarks e divergentes
do código real, mas que são **correção de documentação/remoção de código
morto**, não desenho de pipeline novo — tratados como fases separadas e mais
simples (§7, Fases 0-2), não misturados com o desenho do pipeline mobile:

1. `computer-vision-model/src/` + `scripts/01_extract_landmarks.py` —
   deprecar (scaffold morto, nunca funcionou — §3, item 3).
2. `README.md` raiz do `computer-vision-model` — tabela "Pipeline principal"
   descreve o scaffold morto ("MediaPipe Hands, 21 pontos") como se fosse a
   trilha ativa, **não menciona `treino/` em nenhum lugar**, e lista o
   vocabulário antigo (10 sinais, real é 20).
3. `PoC/src/extract.py` — docstring desatualizado ("49 pontos × 3 = 147
   valores", `pose_subset` antigo de 7 pontos) — o código funciona certo (lê
   `pose_indices` dinamicamente), só o comentário ficou pra trás da expansão
   de 2026-09-08.
4. `PoC/api/README.md` — lista incompleta de índices que o servidor indexa
   (só cita 5 dos 15 pontos de pose reais).

## 6. Plano de implementação faseado

### Fase 0 — Corrigir documentação e remover código morto (baixo risco)
- [ ] Deprecar/remover `computer-vision-model/src/` e
  `scripts/01_extract_landmarks.py`, `02_train.py`, `03_export_tflite.py`
  (todos dependem do scaffold morto) — decisão de deletar vs. arquivar em
  aberto (§8).
- [ ] Corrigir `PoC/src/extract.py` (docstring: 57 pontos, `pose_subset`
  atual).
- [ ] Corrigir `PoC/api/README.md` (lista completa dos 15 índices de pose).
- [ ] Corrigir `README.md` raiz do `computer-vision-model` (tirar a tabela
  que descreve o scaffold morto como pipeline principal; apontar pra
  `treino/`; atualizar vocabulário pra 20 sinais).
- **Critério de sucesso**: nenhum documento do repo descreve landmark
  extraction de um jeito que diverge do `PoC/extract.py`/`treino/`.

### Fase 1 — `LandmarkNormalizer.kt` isolado
- [ ] Implementar ①-④ (§4): subconjunto de pose, conversão pra pixel,
  normalização por ombros, montagem do vetor de 57 pontos.
- [ ] Teste comparando saída contra `extract.py` rodado no mesmo frame
  (mesmo vídeo, mesmo timestamp) — paridade numérica, não só estrutural.
- **Critério de sucesso**: diferença desprezível (definir tolerância) entre
  a normalização em Kotlin e a em Python pro mesmo frame de entrada.

### Fase 2 — Imputação de lacunas (§2.5)
- [ ] Portar `imputar_maos` pra Kotlin — mas **online**: `treino/dados.py`
  interpola sabendo o clipe inteiro (pode olhar pra frente); no app, a
  lacuna só pode ser fechada quando a mão reaparece, então a imputação tem
  que ser aplicada em retrospecto sobre o buffer já acumulado, não em tempo
  real frame a frame. Mesma diferença online/causal já registrada em
  `sign-boundary-detector-plano.md` §6 — vale a pena reler antes de
  implementar.
- **Critério de sucesso**: sequência processada bate com a versão offline
  (Python) pro mesmo clipe gravado.

### Fase 3 — `TemporalResampler.kt`
- [ ] Implementar a reamostragem linear pra 64 frames (equivalente a
  `treino/gcn.py:para_sequencia`).
- **Critério de sucesso**: mesma saída (tolerância numérica) que a versão
  Python pro mesmo segmento de entrada.

### Fase 4 — Integração com `SignBoundaryDetector` e `SignClassifier`
- [ ] Ligar `LandmarkNormalizer` como fonte dos dados que
  `SignBoundaryDetector` usa pra medir deslocamento (§3, item 5) — hoje
  aquele documento assume normalização, mas não especifica de onde ela vem.
- [ ] Ligar `TemporalResampler` + o tensor `(3, 64, 57)` como entrada do
  `SignClassifier` (`sign-boundary-detector-plano.md` §5.2) — **bloqueado
  pela exportação do `.tflite`** (mesma dependência já registrada lá).
- [ ] **Bloqueado por decisão externa**: se o treino migrar pra 3 canais
  (§3, item 2), `treino/dados.py` (`carregar()`, hoje `arr[:,:,:2]`) e
  `treino/gcn.py` (`canais_ent=2` por padrão) precisam mudar em conjunto —
  não é trabalho deste plano, mas sem isso o `.tflite` exportado é
  incompatível com o tensor de 3 canais que este pipeline vai produzir.
- **Critério de sucesso**: um sinal capturado ponta a ponta (câmera →
  landmarks → normalização → boundary → reamostragem → tensor) bate
  numericamente com o mesmo clipe processado pelo pipeline Python.

## 7. Decisões e riscos em aberto

1. **z ajuda ou atrapalha o GCN?** Não validado (§3, item 2) — só dá pra
   medir com o `.tflite` treinado nos dois formatos.
2. **MediaPipe Holistic pode estar disponível pro Android agora** —
   pesquisa recente indica que "Holistic Landmark Detection" está listado
   como suportado em Android na documentação atual do MediaPipe Tasks, o que
   eliminaria a divergência Pose+Hands separados vs. Holistic (usada no
   treino) de uma vez. **Não verificado contra a versão exata do SDK fixada
   no projeto** (`com.google.mediapipe:tasks-vision:0.10.14`,
   `app/build.gradle.kts`) — precisa confirmar API/classe disponível antes
   de decidir trocar `LandmarkExtractor.kt`.
3. **Correção de acurácia do GCN (44,6% → esperado ~93%)** — dependência
   externa bloqueante pra Fase 4, mas fora do escopo deste documento (é
   trabalho de `computer-vision-model/treino/`, não de extração/mobile).
4. **Deletar vs. arquivar o scaffold morto** (`src/`, `scripts/01-03`) —
   ação destrutiva, decisão de produto/repo mais que técnica.
5. **Formato exato do tensor de entrada/saída do `.tflite`** só será
   conhecido quando a exportação acontecer (mesma dependência já registrada
   em `sign-boundary-detector-plano.md` §5.1/§5.4) — o desenho aqui assume
   `(3, 64, 57)` seguindo `gcn.py`, mas o processo de exportação pode expor
   detalhes (nome dos tensores, quantização) que mudam a interface exata do
   `SignClassifier`.

## 8. Referências

- `computer-vision-model/treino/gcn.py` — estrutura do grafo, `T_FIXO=64`,
  `canais_ent`, resultado medido (`resultados-gcn/relatorio.md`, 44,6%).
- `computer-vision-model/treino/dados.py` — carga dos `.npy`, imputação de
  lacunas de mão, descarte do z na leitura atual.
- `computer-vision-model/treino/resultados-resnet/relatorio.md` — 93,4%,
  a referência de que o pipeline de dados está correto (a defasagem é do
  modelo GCN, não dos landmarks).
- `computer-vision-model/PoC/src/extract.py` §5.2 — algoritmo de
  normalização (fonte de verdade, ainda que o docstring esteja desatualizado
  — §5, item 3).
- `computer-vision-model/PoC/config.yaml` (`pose_indices`, `normalizacao`) —
  os 15 índices de pose e a medição documentada do efeito do z.
- Commit `871b9f7` (B1) — precedente da imputação de lacunas, adaptado aqui
  pra versão online (§6, Fase 2).
- `docs/sign-boundary-detector-plano.md` — consumidor direto deste pipeline
  (`SignBoundaryDetector` mede deslocamento sobre a saída normalizada;
  `SignClassifier` consome o tensor reamostrado).
- `docs/orquestracao-dialogo-audio-plano.md` — consome o resultado final
  (sinais reconhecidos), sem depender de nenhum detalhe deste documento.

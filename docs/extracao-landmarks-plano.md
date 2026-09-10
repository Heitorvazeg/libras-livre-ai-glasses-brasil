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

## 0. Revisão de 2026-09-10 — Fases 1/2 feitas, 2 canais (não 3), GCN em 95,6%

1. **Fases 1 e 2 implementadas e commitadas**: `libras/LandmarkNormalizer.kt`
   (normalização por ombros) e `libras/HandGapImputer.kt` (imputação online de
   lacunas de mão, portagem causal de `treino/dados.py:imputar_maos`) —
   ambos com testes JVM (`app/src/test/`, sem emulador). Ver §6.
2. **2 canais (x, y), não 3 — reverte a decisão do §3 item 2.** O modelo em
   treino usa `canais_ent=2` (`treino/gcn.py`) e `arr[:, :, :2]`
   (`treino/dados.py`) **de verdade**, não é uma aposta em z como a versão
   original deste documento registrava. `LandmarkNormalizer` não calcula
   nem carrega z em lugar nenhum. Isso também **remove** a dependência
   externa que a Fase 4 (§6) registrava ("se o treino migrar pra 3
   canais...") — não tem migração nenhuma a esperar, porque 2 canais já é
   o que existe.
3. **A acurácia do GCN medida é 95,6%, não 44,6%.** O resultado antigo
   (§2.6, §3 item 1) vinha de um bug que efetivamente só populava ~21
   pontos do vetor de 57, não de limitação do modelo em si. O GCN deixa de
   ser "o alvo apesar do resultado atual" — é a escolha validada.
4. **Fase 0 (§6) está fora do escopo de quem implementou isto** — mexe em
   documentação/código de `computer-vision-model/`, pasta em que outra
   pessoa está trabalhando. Não foi feita, e não deveria ser feita por
   quem só mexe em `mobile-app-companion/`.
5. **`SignBoundaryDetector` (Fase 4) foi implementado** — ver
   `docs/sign-boundary-detector-plano.md` (revisado na mesma data, §0
   daquele documento). A integração descrita no §6 Fase 4 abaixo está
   **feita**, com uma ressalva: o classificador ligado hoje é
   `PlaceholderSignClassifier` (`.tflite` real ainda não existe — mesma
   dependência bloqueante já registrada aqui e lá), e `TemporalResampler.kt`
   (Fase 3, reamostragem pra 64 frames) **não foi implementado** — o
   placeholder não precisa de tensor de tamanho fixo, só o classificador
   real vai precisar (ver §6 Fase 3/4 revisadas).

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

- **2 canais (x, y), sem z — [REVISADO, §0 item 2] confirmado como a
  implementação final, não mais uma decisão em aberto.** `treino/dados.py`
  carrega o `.npy` (que tem 3 dims) e descarta a terceira na leitura:
  `arr[:, :, :2]`. `treino/gcn.py` constrói o modelo com `canais_ent=2` por
  padrão. A versão original deste documento registrava uma decisão
  consciente de usar 3 canais mesmo assim (aposta em z ajudar o GCN, ver
  §3 item 2 original) — revertida: `LandmarkNormalizer.kt` implementa só
  x,y.
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
| **GCN** (`resultados-gcn/relatorio.md`) | ~~44,6%~~ **95,6%** [REVISADO, §0 item 3] | ~0,4M | grafo (2, 64, 57) — o que este documento assume |

**[REVISADO, §0 item 3]** O número de 44,6% abaixo vinha de um bug que
populava só ~21 dos 57 pontos do vetor, não de limitação do GCN — corrigido,
o resultado real é 95,6%, acima da ResNet. O parágrafo original ("GCN é o
alvo apesar do resultado atual") fica registrado por transparência, não
vale mais:

GCN é o alvo deste plano **apesar** do resultado atual (decisão registrada em
§3, item 1) — o conserto do modelo (por que 44,6% e não ~93%, igual ResNet)
é dependência bloqueante, mas **fora do escopo deste documento**, que é só
extração/pré-processamento mobile.

## 3. Decisões tomadas

1. **[REVISADO, §0 item 3] GCN é o alvo pro `.tflite` — 95,6% de acurácia
   medida, não mais 44,6%.** Motivo original ainda vale (é o único candidato
   pequeno o bastante pro celular: ~0,4M parâmetros vs. ~11M da ResNet-18),
   mas deixou de ser "apesar do resultado atual": o resultado baixo vinha de
   um bug que populava só ~21 dos 57 pontos, não de limitação do modelo —
   corrigido, o GCN bate a ResNet (93,4%). A exportação pra `.tflite`
   continua sendo trabalho de `computer-vision-model/treino/`, tratada aqui
   como dependência externa bloqueante só pra ter um classificador REAL
   (`docs/sign-boundary-detector-plano.md` §5.4) — não bloqueia mais fechar
   a orquestração (ver item 2 do §0 daquele documento).

2. **[REVISADO, §0 item 2] Usar só x, y (2 canais) — a aposta em z do
   parágrafo original foi revertida.** `PoC/config.yaml` documenta, medido
   em 430 clipes: desligar o z melhorou a acurácia do baseline DTW em ~4-6
   pontos (64,0% → 68,7%). O parágrafo original especulava que o GCN
   poderia se beneficiar do z mesmo assim (peso aprendido por aresta vs.
   métrica de distância fixa do DTW) e registrava isso como aposta a
   confirmar. Não foi confirmada nem refutada por medição — a decisão final
   foi simplesmente alinhar com o que já está em produção no treino
   (`canais_ent=2`, `arr[:, :, :2]`), sem introduzir uma divergência entre
   o que o app monta e o que o modelo realmente espera.

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
② Conversão pra pixel (x·W, y·H) usando width/height do frame — [REVISADO,
        │  §0 item 2] sem z·W, ver §2.3
        ▼
③ Normalização: origem = meio dos ombros, escala = distância entre ombros (x,y)
        │  aplica aos 57 pontos — só x,y (§0 item 2, era "incluindo z")
        ▼
④ Vetor de 57 pontos por frame x 2 canais: [pose(15) | mão_esq(21) | mão_dir(21)]
        │  mão ausente → zeros (convenção já usada hoje)
        ▼
⑤ Imputação de lacunas curtas de mão (§2.5)
        ▼
⑥ (SignBoundaryDetector consome a sequência daqui pra medir deslocamento —
        docs/sign-boundary-detector-plano.md §4.1 — FEITO, §0 item 5)
        │
        │  quando o SignBoundaryDetector fecha um segmento (boundary):
        ▼
⑦ Reamostragem pra 64 frames fixos (interpolação linear, por ponto e canal)
        │  NÃO IMPLEMENTADO (§0 item 5) — só necessário pro classificador
        │  REAL; PlaceholderSignClassifier aceita qualquer tamanho de segmento
        ▼
⑧ Tensor (2, 64, 57) → SignClassifier.classify() → `.tflite`
```

**[REVISADO, §0]** Status de cada passo: ①-⑥ implementados e em uso
(`LandmarkNormalizer.kt`, `HandGapImputer.kt`,
`SignBoundaryDetector.kt` — este na branch/plano irmão); ⑦
(`TemporalResampler.kt`) não implementado, não bloqueia o fluxo hoje porque
o classificador ligado é o placeholder (§5.2 de
`docs/sign-boundary-detector-plano.md`) — só passa a ser necessário quando
o `.tflite` real (que exige tensor de tamanho fixo) for integrado.

### 4.1 Onde isso vive

`libras/LandmarkNormalizer.kt` **(feito)** — cobre ①-④, chamado pelo
`LandmarkPipeline.kt` no mesmo ponto onde os frames já são extraídos, antes
de `SignBoundaryDetector.onFrame()` e antes de acumular pro classificador.
Único dono da normalização — nem `SignBoundaryDetector` nem `SignClassifier`
reimplementam esse cálculo (§3, item 5).

`libras/HandGapImputer.kt` **(feito)** — cobre ⑤, mesmo ponto de integração.

`libras/TemporalResampler.kt` (novo, ou função dentro de `SignClassifier.kt`)
— cobre ⑦, chamado só quando um segmento fecha (não frame a frame).
**Ainda não implementado** — ver nota de status acima.

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
**[REVISADO, §0 item 4] Fora do escopo de quem implementa em
`mobile-app-companion/` — mexe só em `computer-vision-model/`, pasta de
outra pessoa. Não feita, registrada aqui só pra quem trabalhar naquela
pasta puder retomar.**
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

### Fase 1 — `LandmarkNormalizer.kt` isolado — **[FEITA, §0 item 1]**
- [x] Implementar ①-④ (§4): subconjunto de pose, conversão pra pixel,
  normalização por ombros, montagem do vetor de 57 pontos x 2 canais.
- [x] Teste (`LandmarkNormalizerTest`, `app/src/test/`) — **ressalva**: é
  fórmula-contra-valores-calculados-à-mão, não paridade cross-language
  executando `extract.py` de verdade (este ambiente não tem
  numpy/mediapipe instalados). Rodar `extract.py` num frame real e
  comparar byte a byte continua sendo o próximo passo de validação
  recomendado antes de confiar nisto em produção.
- **Critério de sucesso**: parcialmente atingido — a fórmula e o
  mapeamento de índices estão verificados; paridade numérica cross-language
  de verdade continua pendente.

### Fase 2 — Imputação de lacunas (§2.5) — **[FEITA, §0 item 1]**
- [x] Portar `imputar_maos` pra Kotlin (`HandGapImputer.kt`) — **online**:
  `treino/dados.py` interpola sabendo o clipe inteiro (pode olhar pra
  frente); no app, a lacuna só é fechada quando a mão reaparece, aplicada
  em retrospecto sobre o buffer já acumulado. Mesma diferença online/causal
  já registrada em `sign-boundary-detector-plano.md` §6.
- [x] Teste (`HandGapImputerTest`) — porta o fixture exato de
  `treino/selftest.py::teste_imputacao_maos` (lacuna curta preenchida,
  longa preservada, pose intacta).
- **Critério de sucesso**: atingido pro fixture sintético portado; não
  validado contra um clipe real gravado (mesma ressalva de dado real da
  Fase 1).

### Fase 3 — `TemporalResampler.kt` — **não implementada**
- [ ] Implementar a reamostragem linear pra 64 frames (equivalente a
  `treino/gcn.py:para_sequencia`).
- **Por que não foi feita agora**: só é necessária pra alimentar um
  classificador que exige tensor de tamanho fixo — o `.tflite` real, que
  não existe (§5.4 item 1 de `sign-boundary-detector-plano.md`). O
  `PlaceholderSignClassifier` ligado hoje aceita segmentos de qualquer
  tamanho, então fechar a Fase 4 (abaixo) não dependeu disto.
- **Critério de sucesso**: mesma saída (tolerância numérica) que a versão
  Python pro mesmo segmento de entrada — continua válido pra quando for
  implementada.

### Fase 4 — Integração com `SignBoundaryDetector` e `SignClassifier` — **[FEITA, §0 item 5, com ressalva]**
- [x] Ligar `LandmarkNormalizer` como fonte dos dados que
  `SignBoundaryDetector` usa pra medir deslocamento — `LandmarkPipeline.kt`
  chama `LandmarkNormalizer.normalize()` e alimenta o resultado tanto pro
  `HandGapImputer` (acumula o segmento) quanto pro `SignBoundaryDetector`
  (mede deslocamento), na mesma chamada.
- [x] Ligar `SignClassifier` — hoje `PlaceholderSignClassifier`, recebendo
  `List<Array<FloatArray>>` (a saída do `HandGapImputer`) diretamente, sem
  passar por `TemporalResampler` (ver Fase 3).
- [ ] **Ainda bloqueado**: trocar `PlaceholderSignClassifier` por um
  `TfliteSignClassifier` de verdade — depende da exportação do `.tflite`
  (fora deste repo/branch) e, quando isso acontecer, também da Fase 3
  (`TemporalResampler`), que o tensor de tamanho fixo do `.tflite` vai
  exigir.
- ~~Bloqueado por decisão externa: se o treino migrar pra 3 canais~~ — **não
  se aplica mais** (§0 item 2): o pipeline já usa 2 canais, o mesmo que o
  treino já usa hoje. Não tem migração nenhuma a esperar.
- **Critério de sucesso**: parcialmente atingido — o ciclo boundary→
  classificar(placeholder)→acumular→falar roda de ponta a ponta (ver
  `docs/sign-boundary-detector-plano.md` Fase 3a); bater numericamente com
  o pipeline Python só é verificável quando houver `.tflite` real e
  `TemporalResampler` pra comparar.

## 7. Decisões e riscos em aberto

1. ~~z ajuda ou atrapalha o GCN?~~ **[RESOLVIDO/MOOT, §0 item 2]** Não
   estamos usando z de jeito nenhum — a pergunta deixou de fazer sentido
   pra este pipeline. Fica só como nota histórica: se algum dia alguém
   quiser reabrir a aposta em z, esta era a pergunta original.
2. **MediaPipe Holistic pode estar disponível pro Android agora** —
   pesquisa recente indica que "Holistic Landmark Detection" está listado
   como suportado em Android na documentação atual do MediaPipe Tasks, o que
   eliminaria a divergência Pose+Hands separados vs. Holistic (usada no
   treino) de uma vez. **Não verificado contra a versão exata do SDK fixada
   no projeto** (`com.google.mediapipe:tasks-vision:0.10.14`,
   `app/build.gradle.kts`) — precisa confirmar API/classe disponível antes
   de decidir trocar `LandmarkExtractor.kt`. Ainda em aberto, sem mudança.
3. ~~Correção de acurácia do GCN (44,6% → esperado ~93%)~~ **[RESOLVIDO,
   §0 item 3]** — medida em 95,6%, acima da ResNet. Não é mais uma
   dependência bloqueante pra fechar a orquestração (só pra ter o
   classificador REAL, que segue dependendo da exportação `.tflite`).
4. **Deletar vs. arquivar o scaffold morto** (`src/`, `scripts/01-03`) —
   ação destrutiva, decisão de produto/repo mais que técnica. **Fora do
   escopo de quem implementou esta revisão** (§0 item 4, mesmo motivo da
   Fase 0) — quem mexer em `computer-vision-model/` decide.
5. **Formato exato do tensor de entrada/saída do `.tflite`** só será
   conhecido quando a exportação acontecer (mesma dependência já registrada
   em `sign-boundary-detector-plano.md` §5.1/§5.4) — o desenho aqui assume
   `(2, 64, 57)` **[REVISADO, §0 item 2 — era (3, 64, 57)]** seguindo
   `gcn.py`, mas o processo de exportação pode expor detalhes (nome dos
   tensores, quantização) que mudam a interface exata do `SignClassifier`.
   Ainda em aberto — depende da exportação, não feita.

## 8. Referências

- `computer-vision-model/treino/gcn.py` — estrutura do grafo, `T_FIXO=64`,
  `canais_ent`, resultado medido (`resultados-gcn/relatorio.md`, 95,6% —
  ver §0 item 3, corrige o 44,6% registrado na versão original deste doc).
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
  `SignClassifier` consome os frames acumulados) — implementado, ver §0
  item 5 deste documento e §0 daquele.
- `docs/orquestracao-dialogo-audio-plano.md` — consome o resultado final
  (sinais reconhecidos), sem depender de nenhum detalhe deste documento.

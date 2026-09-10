# `SignBoundaryDetector` — detecção automática de início/fim de sinalização

> Plano de implementação do componente descrito em `docs/libras-livre-arquitetura.md`
> §4.2 como "heurístico simples baseado em velocidade/presença da mão no
> quadro" (MVP). Este documento é a implementação concreta dessa heurística —
> não é um segmentador novo nem paralelo a nada que já exista. Também cobre o
> que consome cada boundary detectado: classificação local do sinal (§5) —
> necessário pra implementar e testar este componente de ponta a ponta, não
> só a heurística isolada.

---

## 0. Revisão de 2026-09-10 — unificado com a extração/normalização, fecha a orquestração sem esperar o `.tflite`

Registrado aqui porque muda a leitura de várias seções abaixo, não só um
detalhe isolado:

1. **`docs/extracao-landmarks-plano.md` foi mergeado nesta branch.** Esta
   branch agora tem `libras/LandmarkNormalizer.kt` (normalização por
   ombros) e `libras/HandGapImputer.kt` (imputação de lacunas de mão) — o
   `SignBoundaryDetector` (§4) passa a **consumir a saída do
   `LandmarkNormalizer`**, não recalcular origem/escala por conta própria
   (ver §4.1 revisado).
2. **2 canais (x, y), não 3.** Mesma correção do plano de extração: o
   modelo em treino usa `canais_ent=2`/`arr[:, :, :2]` de verdade — o
   deslocamento (§4.1) é medido só em x,y.
3. **A acurácia do GCN medida hoje é 95,6%** (57 pontos populados
   corretamente — o resultado de 44,6% registrado em §5.1 vinha de um bug
   que efetivamente só populava ~21 pontos, não da arquitetura em si). Isso
   remove a ressalva "GCN apesar do resultado atual" que este documento
   carregava — é a escolha validada, não mais uma aposta.
4. **`SignClassifier` vira uma interface, com uma implementação-placeholder
   enquanto o `.tflite` não existe** — mesmo padrão já usado pro
   `WakeWordDetector`/`SttEngine` na branch de orquestração de áudio:
   destrava a integração ponta a ponta agora, troca pelo modelo de verdade
   depois sem mexer em quem chama (ver §5.2, §5.4 revisados).
5. **A pergunta que os dois planos puncionavam um pro outro — como uma
   sequência de palavras reconhecidas vira uma frase falável — fica
   resolvida aqui com um placeholder explícito**, já que não existe
   implementação nenhuma disso em lugar nenhum do repo hoje (nem a tabela
   `combinacoesConhecidas` citada em `mobile-app-companion/README.md`, que
   segue no checklist como não feita): `DialogOrchestrator` acumula uma
   palavra por boundary e junta com espaço ao "encerrar" (ver §5.3
   revisado). Não é a resolução linguística real, é o que destrava o
   ciclo ponta a ponta sem esperar por ela.
6. **Fases 0 e 1 (§7) não são executáveis neste ambiente de
   desenvolvimento** — confirmado ao revisar: não há câmera/emulador
   disponível pra gerar curvas reais, e `PoC/data/landmarks/` está vazio
   neste checkout (o dataset da PoC não está commitado no repo). Os
   parâmetros do §4.4 ficam como valores de partida **não calibrados**,
   explicitamente marcados assim no código — calibração fica pra quando
   houver dado real disponível.

---

## 1. Objetivo

Decidir, quadro a quadro, **sem intervenção manual**, quando uma sequência de
landmarks corresponde a uma pessoa efetivamente sinalizando (ao contrário de
mão parada, fora de quadro, ou gesto residual entre sinais). Duas decisões
distintas, que este componente cobre:

1. **Início**: quando vale a pena começar a acumular frames pra classificar
   (evita rodar o pipeline caro sem necessidade — custo de bateria/CPU).
2. **Fim**: quando a pessoa parou de sinalizar — disparando a classificação
   (local, GCN `.tflite` — §5) do segmento acumulado.

Este documento cobre as duas, mas o **fim** é o mais crítico de calibrar —
cortar cedo demais trunca o sinal, cortar tarde demais mistura o próximo gesto
com o classificador.

## 2. Relação com o plano de diálogo por áudio

Este componente foi originalmente desenhado como parte de
`docs/orquestracao-dialogo-audio-plano.md`. A primeira versão daquele plano
rejeitava a detecção automática em favor de wake word a cada sinal; isso foi
revisado — ver `orquestracao-dialogo-audio-plano.md` §4, item 3. Hoje a
relação é de **dependência direta, não mais de planos desacoplados**:

- O plano de diálogo por áudio decide **quando uma sessão de captura abre e
  fecha** (via as wake words "Libras Livre, iniciar"/"encerrar") — isso é
  todo o escopo dele.
- Dentro dessa sessão, **este documento** decide onde cada sinal individual
  começa e termina, e entrega cada um já classificado. O plano de áudio só
  consome o resultado (a sequência de palavras reconhecidas), sem saber como
  ela foi produzida.

Isso também é, sem mudança nenhuma, o caminho já descrito pro **Produto** de
longo prazo (`libras-livre-arquitetura.md` §2 e §4.2 — "sinalização
contínua", sem exigir nenhum gatilho, nem manual nem por voz): o mesmo
componente serve os dois propósitos, só muda quem decide os limites externos
da sessão (o toque, a wake word, ou nada — no Produto).

## 3. Onde vive

`libras/SignBoundaryDetector.kt`, consumido por `LandmarkPipeline.kt` no mesmo
ponto onde os frames já são acumulados hoje — mas alimentado pela saída **já
normalizada** de `libras/LandmarkNormalizer.kt` (§0, item 1), não pelo
`FrameLandmarks` cru:

```kotlin
// LandmarkPipeline.kt, dentro do listener do ImageReader:
if (collecting) {
  val ts = nextTimestampMs()
  val fl = extractor?.extract(image, ts)
  if (fl != null) {
    synchronized(collectLock) { collected.add(fl) }
    val normalizado = LandmarkNormalizer.normalize(fl, frameW, frameH)
    if (normalizado != null) boundaryDetector.onFrame(normalizado)   // <- novo
  }
}
```

`LandmarkNormalizer.normalize()` devolve `null` nos mesmos dois casos que já
descarta frame pra classificação (ombro pouco visível, escala degenerada,
ver `extracao-landmarks-plano.md` §2.4) — `SignBoundaryDetector` simplesmente
não recebe esses frames, não precisa reimplementar o descarte.

**A wake word substituiu `toggleSignCapture()`** (ver
`docs/orquestracao-dialogo-audio-plano.md`, já mergeado) — hoje é
`DialogOrchestrator` quem controla início/fim da sessão inteira via
"Libras Livre, iniciar/encerrar" (ou os botões de fallback). O detector,
dentro dessa sessão, decide onde cada sinal individual começa e termina
(§4) — não é mais "botão vs. detector", é "sessão (áudio) contém N sinais
(detector)", exatamente a relação descrita em §2.

## 4. O algoritmo (heurística MVP)

### 4.1 Sinal de entrada: deslocamento das mãos **e** dos braços (pose)

Descoberta recente que muda este parágrafo (ver revisão de landmarks do
`computer-vision-model`, 2026-09-08): o `FrameLandmarks` que o
`LandmarkExtractor.kt` já produz **não é só mãos** — o campo `pose` traz os
33 pontos crus do MediaPipe Pose, incluindo cotovelos e pulsos (índices 13,
14, 15, 16), sem precisar de nenhum modelo novo no app. `SignBoundaryDetector`
deve usar esse mesmo `pose`, não só `leftHand`/`rightHand` — um sinal envolve
o braço inteiro se movendo, não só os dedos, e medir deslocamento só da mão
perde esse sinal quando a configuração da mão muda pouco mas o braço se
desloca (ou vice-versa).

Reusar os **mesmos índices** que `computer-vision-model/PoC/config.yaml`
(`pose_indices`) já usa pro classificador — não inventar um subconjunto
próprio. **[REVISADO, §0 item 1]** Como a entrada agora é a saída de
`LandmarkNormalizer` (57 pontos, não mais os 33 crus do MediaPipe Pose), os
índices relevantes são os do vetor JÁ normalizado
(`LandmarkNormalizer.POSE_SUBSET`/`OFFSET_MAO_ESQ`/`OFFSET_MAO_DIR`):

| Grupo usado no deslocamento | Índices no vetor de 57 pontos | Papel aqui |
|---|---|---|
| Mãos (42) | 15-35 (mão_esq), 36-56 (mão_dir) | sinal principal — configuração/movimento fino |
| Braços (4) | 9,10 (cotovelo_esq/dir), 11,12 (pulso_esq/dir) | movimento grosso do braço inteiro |
| Tronco (4) | 7,8 (ombro_esq/dir), 13,14 (quadril_esq/dir) | **não** entra no deslocamento — já é só âncora de normalização dentro do próprio `LandmarkNormalizer` |
| Face (7) | 0-6 (nariz, olhos, orelhas, boca) | **não** entra no deslocamento nesta heurística — cabeça costuma ficar relativamente parada durante um sinal; ver nota abaixo |

```
deslocamento(frame_t) = combinação, sobre mãos + braços presentes,
                         da distância euclidiana entre frame_t e frame_(t-1)
```

Detalhes que importam:
- Cada mão/braço é tratado **separadamente** e depois combinado (soma ou
  máximo, não média) — um sinal de um lado só não pode ser diluído pelo lado
  parado.
- Como mãos e braços têm escalas de movimento diferentes (um pulso se desloca
  menos, em unidades de ombro, que os dedos abrem/fecham), a combinação
  provavelmente precisa de peso diferente por grupo — parâmetro a calibrar
  (§4.4), não assumir peso igual sem medir.
- **[REVISADO, §0 item 1] Ponto ausente não conta como "deslocamento zero"
  — e isso agora é responsabilidade de `LandmarkNormalizer`, não deste
  detector.** `LandmarkNormalizer.normalize()` já representa mão ausente
  como o vetor `[0,0]` (a própria origem — ver `extracao-landmarks-plano.md`
  §2.4), então `SignBoundaryDetector` precisa saber olhar pra esse caso
  como "sem dado", não como "mão parada exatamente na origem" — checar
  ausência do MESMO jeito que `HandGapImputer` faz (bloco inteiro de 21
  pontos × 2 canais somando zero — `HandGapImputer.kt`), não confundir com
  a lacuna que ele PREENCHE: aqui a regra é a oposta, é **não contar** o
  ponto na conta do deslocamento enquanto ausente, nunca inventar um valor
  pra ele. Mesma lição já aprendida do lado do dataset
  (`computer-vision-model`, commit B1: zero != ausência).
- **[REVISADO, §0 item 1] Já é trabalho de `LandmarkNormalizer` normalizar
  pela escala dos ombros** — o deslocamento é medido diretamente sobre a
  saída dele (`Array(57) { FloatArray(2) }`, já em unidades de "distância
  entre ombros"), não sobre coordenadas 0..1 cruas do MediaPipe. Não tem
  cálculo de origem/escala duplicado aqui.
- **Face fica de fora do deslocamento por agora**: cabeça parada durante o
  sinal é a norma (movimento de cabeça entra na "gramática facial", fora de
  escopo do MVP — `libras-livre-arquitetura.md` §2). Se a Fase 2 (§7) mostrar
  sinais que envolvem movimento de cabeça sendo cortados cedo demais, isso é
  candidato a revisão — não descartar de vez, só não incluir de saída.

### 4.2 Estado "sinalizando" vs. "parado"

Nomeação alinhada com o "Produto" já descrito em `libras-livre-arquitetura.md`
§4.2 ("um classificador binário leve e contínuo — 'sinalizando'/'parado'"):
esta heurística MVP é o standin do mesmo binário, só que por regra fixa em
vez de modelo treinado.

```
SINALIZANDO ⇄ PARADO
```

- **SINALIZANDO → PARADO**: deslocamento sustentado abaixo de
  `LIMIAR_VELOCIDADE` por `JANELA_SUSTENTACAO_MS`, com mãos/braços visíveis
  (pausa real) — **ou** mãos ausentes (oclusão) por ≥ `TETO_OCLUSAO_MS`
  (ver §4.3). É este exato momento que dispara a classificação — ver §5.
- **PARADO → SINALIZANDO**: deslocamento volta a ultrapassar
  `LIMIAR_VELOCIDADE` — é essa borda que habilita o modo "Produto" (início
  automático, sem gatilho nenhum, não só o fim).

Não é uma única amostra cruzando o limiar que decide a troca de estado —
sinais reais têm micro-pausas internas (troca de configuração de mão) que não
podem disparar `PARADO` no meio de um sinal só.

### 4.3 Ausência de mão (oclusão) não é "parado" imediato

Uma mão que sai de quadro ou fica ocluída (comum em Libras — mão passando na
frente do rosto ou da outra mão) não deve, sozinha e na hora, empurrar o
estado pra `PARADO`. A oclusão entra como **uma das duas condições** que leva
a `SINALIZANDO → PARADO` (§4.2) — com um teto de tolerância próprio
(`TETO_OCLUSAO_MS`), maior que o de uma pausa real com mãos visíveis. Sem essa
distinção, qualquer sinal com uma oclusão no meio (muito comum) corta a
captura precocemente.

### 4.4 Parâmetros a calibrar

| Parâmetro | Papel | Ponto de partida sugerido |
|---|---|---|
| `LIMIAR_VELOCIDADE` | abaixo disso, o combinado mãos+braços é "parado" | calibrar empiricamente — ver §7 |
| `PESO_MAO` / `PESO_BRACO` | quanto cada grupo pesa na combinação do deslocamento (§4.1) | começar 1:1, ajustar depois de ver a série real na Fase 0 (§7) |
| `JANELA_SUSTENTACAO_MS` | quanto tempo parado até considerar fim | começar por ~600-800ms, calibrar |
| `TETO_OCLUSAO_MS` | quanto tempo sem detectar mão ainda é "só oclusão" | referência: PoC não define isso porque não segmenta em tempo real — este é território novo |
| `DURACAO_MINIMA_MS` | não classifica captura mais curta que isso (ruído/falso início) | referência: PoC, "Frames por frame por clipe: min 77" a ~24-30fps ≈ 2.5-3.2s — mas isso é o clipe inteiro já cortado; a duração mínima de um sinal real costuma ser bem menor no início do gesto |
| `DURACAO_MAXIMA_MS` | teto de segurança — força fim mesmo sem detectar pausa | evita travar indefinidamente se a heurística falhar |

Nenhum desses tem valor "certo" a priori — todos exigem medição com dado real
(§7). Não adivinhar e codificar como constante definitiva sem testar.

**[REVISADO, §0 item 6]** As Fases 0 e 1 (§7), que calibrariam estes valores
com dado real, não são executáveis no ambiente onde este plano foi
implementado (sem câmera/emulador, sem o dataset da PoC no checkout). A
implementação usa os "pontos de partida sugeridos" acima como constantes
default, **marcadas explicitamente no código como não calibradas** — o
detector funciona (compila, roda, produz boundaries), mas ninguém validou
ainda que esses valores separam bem sinal de pausa em dado real. Calibrar
é a Fase 0/1 completa, não deste plano — fica pra quando houver acesso a
device/dataset.

## 5. Classificação a cada boundary — GCN local (`.tflite`)

O que acontece toda vez que `SignBoundaryDetector` reporta
`SINALIZANDO → PARADO` (§4.2): o segmento de frames acumulado desde o
boundary anterior é classificado **na hora**, local no celular — sem chamada
de rede. Documentado aqui porque é o consumidor direto do boundary, e a
Fase 3 (§7) não é implementável sem definir esse contrato.

### 5.1 Por que local, e por que GCN

O classificador deixa de ser o `POST /classify` da API da PoC (DTW 1-NN
contra centenas de referências, ~30s de timeout, dependente de rede). Aquela
API sempre foi documentada como "andaime de validação, não produção"
(`computer-vision-model/PoC/api/README.md`) — o objetivo declarado do projeto
sempre foi on-device/offline. O candidato é o modelo GCN de
`computer-vision-model/treino/gcn.py`, exportado pra `.tflite`.

**[REVISADO, §0 item 3]** A versão anterior deste documento registrava o GCN
medido em 44,6% (contra 93,4% da ResNet-18) como "o alvo apesar do resultado
atual" — uma aposta com ressalva. Isso mudou: o resultado real, com os 57
pontos populados corretamente, é **95,6%** — acima da ResNet. Não é mais uma
aposta, é a escolha validada; a única coisa que falta é a exportação em si
(abaixo), não uma dúvida sobre se o modelo é bom o suficiente.

Classificar por sinal (a cada boundary), em vez de uma vez só ao fim de uma
sessão inteira, só é viável porque a inferência é local e barata — contra a
API remota, que não daria pra chamar dezenas de vezes numa sessão sem
acumular latência.

**Essa exportação pra `.tflite` ainda não existe** — `treino/README.md` diz
explicitamente: "Exportação para TFLite e robustez a mudanças de ponto de
vista ainda precisam ser validadas." Continua sendo uma dependência real
deste plano — mas, com a mudança do §5.2, deixou de ser um bloqueador pra
*fechar a orquestração*: só bloqueia ter uma classificação de verdade.

### 5.2 `SignClassifier.kt` — interface, com placeholder enquanto não há `.tflite`

**[REVISADO, §0 item 4]** A versão original deste documento desenhava
`SignClassifier` como uma classe concreta carregando o `.tflite` — o que
deixava a Fase 3 inteira bloqueada até a exportação existir (§5.4, item 1
da versão anterior). Revisado pro mesmo padrão já usado em
`orquestracao-dialogo-audio-plano.md` (`WakeWordDetector`, `SttEngine`):
interface trocável, com uma implementação-placeholder que destrava a
integração ponta a ponta desde já.

```kotlin
interface SignClassifier {
  fun classify(frames: List<FrameLandmarks>): String   // devolve a palavra reconhecida
  fun close()
}
```

- `TfliteSignClassifier` (implementação real, carrega o `.tflite` via
  TensorFlow Lite `Interpreter`) — **não implementável ainda**, é o que
  fica bloqueado pela exportação (§5.1, §5.4).
- Uma implementação-placeholder mínima destrava tudo o mais: recebe o
  segmento delimitado por `SignBoundaryDetector` e devolve algo previsível
  (não precisa "acertar" nada) — o objetivo dela é só provar que o fio
  boundary → classificar → acumular → falar (§5.3) funciona de ponta a
  ponta antes de existir modelo nenhum. Trocar pela real depois é só trocar
  a implementação injetada, sem mexer em `SignBoundaryDetector`,
  `LandmarkPipeline` ou `DialogOrchestrator`.

Recebe exatamente o segmento que `SignBoundaryDetector` delimitou (do
boundary anterior até o atual) — este componente só resolve *o que* é o
sinal, não *onde* ele está; a fronteira é sempre responsabilidade do
detector (§4).

Substitui o papel que `libras/LandmarkApi.kt` tinha nesse fluxo — esse
arquivo deixa de ser chamado pra classificação de sinal.

### 5.3 Contrato com quem chama — resolvido: buffer de palavras no `DialogOrchestrator`

**[REVISADO, §0 item 5]** A versão anterior deste documento (e também
`orquestracao-dialogo-audio-plano.md` §6.5) empurravam "como uma sequência
de palavras reconhecidas vira uma frase falável" um pro outro, citando uma
tabela `combinacoesConhecidas` que **não existe implementada em lugar
nenhum do repo** (`mobile-app-companion/README.md` lista como item de
checklist ainda não feito). Pra fechar o ciclo ponta a ponta sem esperar
por essa resolução linguística, a decisão é:

- `SignBoundaryDetector` + `SignClassifier` reportam uma palavra por
  boundary — continua exatamente como descrito antes.
- `DialogOrchestrator` (que já é o dono da sessão via
  `orquestracao-dialogo-audio-plano.md`) acumula cada palavra recebida
  num buffer da sessão em curso (estado `CAPTURANDO_SINAIS`) — **não fala
  a cada palavra**, só acumula.
- Ao "Libras Livre, encerrar", o buffer é juntado com espaço
  (`palavras.joinToString(" ")`) e É ESSA frase que entra no estado
  `FALANDO` (`Speaker.speakAndAwait`) — não mais o resultado de uma única
  classificação de sessão inteira, como no desenho anterior (que datava de
  antes deste componente existir).
- **Isto é um placeholder explícito**, não a tabela `combinacoesConhecidas`
  real (concordância, ordem, elisão de palavras repetidas etc. — trabalho
  de linguística/produto, fora do escopo técnico deste plano). Trocar o
  `joinToString(" ")` pela resolução de verdade é contido a um único ponto
  quando essa tabela existir.

Caso de borda a não esquecer (mantido da versão anterior): se a sessão
fechar (wake word "encerrar") enquanto o estado ainda é `SINALIZANDO`
(segmento em aberto, sem boundary ainda), `DialogOrchestrator` deve forçar
a classificação do que tiver acumulado até ali antes de montar a frase —
não perder silenciosamente o último sinal.

### 5.4 Decisões em aberto

1. **Exportação do GCN pra `.tflite`** — segue sem existir (§5.1). Com a
   revisão do §5.2, isso não bloqueia mais fechar a orquestração
   (`TfliteSignClassifier` real entra depois, trocando só a implementação
   injetada) — mas segue bloqueando ter uma classificação que funcione de
   verdade em produção.
2. **Peso/latência de classificar por sinal, não por sessão.** Mais
   invocações do modelo por sessão do que no desenho anterior (uma vez só, ao
   fim) — não medido se é desprezível (típico de um GCN pequeno em `.tflite`)
   ou se compete por CPU com o próprio `SignBoundaryDetector` rodando no mesmo
   loop de frames. Só dá pra medir com um `.tflite` de verdade em mãos.
3. **[NOVO] Resolução real de `combinacoesConhecidas`** (§5.3) — o
   `joinToString(" ")` é deliberadamente ingênuo (não trata concordância,
   repetição, ordem). Fica como trabalho separado, de produto/linguística,
   não deste plano.

## 6. Diferença crítica: isto é online/causal, não offline

Vale repetir porque é fácil confundir com processamento de dataset: este
detector só pode olhar **o passado** (frames já recebidos), nunca o futuro —
decide em tempo real, frame a frame, enquanto o stream chega. Isso é
estruturalmente diferente de um eventual segmentador que corte vídeos
**gravados** em sinais isolados pra gerar dataset de treino (que pode olhar o
clipe inteiro, inclusive pra frente). As duas tarefas parecem a mesma ideia
("detectar pausa"), mas não compartilham implementação — um algoritmo
offline pode usar suavização bidirecional (olhando antes E depois do ponto),
o online não.

## 7. Plano de validação (isolando risco)

**[REVISADO, §0 item 6]** Fases 0 e 1 (calibração com dado real) ficam
registradas como estavam — continuam o caminho certo quando houver
device/dataset disponível — mas **não são o que foi implementado** nesta
revisão: sem câmera/emulador e sem `PoC/data/landmarks/` neste checkout,
não dá pra executá-las aqui. O trabalho desta revisão é o que dá pra fazer
sem esses dois recursos: implementar o algoritmo inteiro (com parâmetros
placeholder) e fechar a integração ponta a ponta com um classificador-stub
(Fase 2 e a nova Fase 3a, abaixo) — ficando pronto pra calibrar (Fase 0/1)
e pra trocar o modelo real (Fase 3b) assim que esses dois bloqueios forem
resolvidos por quem tiver acesso a eles.

### Fase 0 — Instrumentação, sem decidir nada ainda (bloqueada neste ambiente)
- [ ] Adicionar log/telemetria do `deslocamento(frame_t)` calculado — **mãos e
  braços separados**, antes de combinar — sem tomar nenhuma decisão de corte,
  só observar as séries temporais.
- [ ] Capturar essas séries em sinais reais conhecidos (usando o
  `MockDeviceKit` com os vídeos de referência da PoC, ou captura real se
  disponível) — visualizar as curvas ao longo de um clipe completo de um
  sinal.
- **Bloqueio**: precisa de device/emulador com câmera — indisponível no
  ambiente onde esta revisão foi feita.
- **Critério de sucesso**: conseguir ver visualmente, no gráfico, onde o sinal
  "começa" e "para" em pelo menos uma das duas curvas (mãos ou braços) — se
  nenhuma mostrar um vale claro, a heurística de deslocamento pode não ser
  suficiente sozinha (ver §8). Também dá o primeiro indício de quanto pesar
  cada grupo em `PESO_MAO`/`PESO_BRACO` (§4.4).

### Fase 1 — Calibração offline (bloqueada neste ambiente)
- [ ] Rodar a mesma métrica (mãos+braços) sobre os `.npy` já extraídos pela
  PoC (`computer-vision-model/PoC/data/landmarks/`) — dado real, 430 clipes,
  11 pessoas, sem precisar de captura nova. Os `.npy` já trazem o `pose_subset`
  de 15 pontos (`PoC/config.yaml`), então os índices de braço já estão lá.
- [ ] Escolher `LIMIAR_VELOCIDADE`, `PESO_MAO`/`PESO_BRACO` e
  `JANELA_SUSTENTACAO_MS` que separem bem o "miolo" do sinal (onde mãos/braços
  se movem) das bordas do clipe (onde presumivelmente estão mais parados, por
  ser um clipe já cortado).
- **Bloqueio**: `computer-vision-model/PoC/data/landmarks/` está vazio neste
  checkout — o dataset não está commitado no repo.
- **Ressalva**: os clipes da PoC já vêm pré-segmentados (um sinal por
  arquivo) — não têm a transição real entre sinais que o detector vai
  enfrentar em produção. Esta fase calibra a ordem de grandeza dos
  parâmetros, não os valores finais.

### Fase 2 — `SignBoundaryDetector` isolado
- [ ] Implementar o algoritmo completo do §4 (deslocamento mãos+braços sobre
  a saída de `LandmarkNormalizer`, estado SINALIZANDO⇄PARADO, tolerância a
  oclusão) — parâmetros do §4.4 como constantes **não calibradas** (Fase
  0/1 bloqueadas, ver acima).
- [ ] Teste unitário (JVM puro, sem emulador — mesmo padrão de
  `HandGapImputerTest`/`LandmarkNormalizerTest`): sequências sintéticas de
  deslocamento simulando sinal→pausa→sinal, oclusão curta vs. longa,
  conferindo que os boundaries saem nos pontos esperados dado os parâmetros
  atuais — não é validação empírica (isso é Fase 0/1), é conferir que a
  máquina de estados do §4.2/§4.3 está implementada certo.
- **Critério de sucesso** (o que dá pra validar sem device): boundaries
  corretos pra sequências sintéticas com transição clara. Validar contra
  sinalização real (falsos cortes, cortes tardios) continua pendente de
  device — critério original mantido pra quando isso for possível.

### Fase 3a — Fecha a orquestração com `SignClassifier` placeholder [NOVO]
- [ ] Implementar a interface `SignClassifier` (§5.2) + a
  implementação-placeholder.
- [ ] Ligar `SignBoundaryDetector` a `SignClassifier`: a cada boundary,
  classifica o segmento.
- [ ] Reestruturar `LandmarkPipeline`/`DialogOrchestrator` pro modelo de
  buffer de palavras (§5.3) — substitui o `stopCollectingAndClassify()`
  de sessão única.
- [ ] Cobrir o caso de borda do §5.3 (sessão fecha com segmento em aberto)
  com um teste.
- **Critério de sucesso**: o ciclo sessão→boundary→classificar(placeholder)→
  acumular→"encerrar"→frase→TTS roda de ponta a ponta (pode ser testado com
  `MockDeviceKit`/segmentos sintéticos, não precisa de sinalização real nem
  do `.tflite`) — é isto que "fecha a orquestração" sem o modelo.

### Fase 3b — Troca pro modelo real (bloqueada — fora deste repo/branch)
- [ ] **Pré-requisito, fora deste repo/branch**: exportar o modelo GCN de
  `computer-vision-model/treino/gcn.py` pra `.tflite` (§5.1) — hoje não
  existe. Bloqueia só esta sub-fase, não a 3a.
- [ ] Implementar `TfliteSignClassifier` (§5.2) carregando o `.tflite` via
  TensorFlow Lite `Interpreter`, trocando a implementação-placeholder da
  Fase 3a.
- [ ] Testar com sinalização contínua real (vários sinais em sequência) —
  confirmar que cada um é classificado corretamente e perto do momento em
  que terminou.
- [ ] Rodar como um modo alternável (flag) inicialmente, comparando com a
  versão de referência (ex.: `LandmarkApi`/DTW) antes de trocar o padrão.
- **Critério de sucesso**: uma sequência de 3+ sinais reconhecidos
  corretamente, sem tocar a tela, cada classificação acontecendo perto do
  momento em que o sinal correspondente terminou.

## 8. Risco em aberto: a heurística pode não ser suficiente sozinha

Vale registrar sem resolver agora: "velocidade/presença de mãos e braços" é a
heurística do **MVP** por ser simples de implementar, não porque seja
robusta. Sinais que têm uma pausa breve no meio do próprio gesto (ex.:
soletração, ou sinais com duas partes) podem confundir o limiar de pausa. Se a
Fase 2 mostrar taxa de erro alta, as opções (não aprofundadas aqui) são: um
limiar adaptativo por sinal, ou pular direto pro classificador binário
contínuo já previsto como "Produto" em `libras-livre-arquitetura.md` §4.2 —
que aprende a diferença em vez de depender de um limiar fixo.

## 9. Referências

- `docs/libras-livre-arquitetura.md` §4.2 — a decisão original (MVP vs.
  Produto) que este documento implementa.
- `docs/extracao-landmarks-plano.md` (mergeado nesta branch, §0) —
  `LandmarkNormalizer`/`HandGapImputer`, fonte da entrada já normalizada
  que `SignBoundaryDetector` consome (§3, §4.1).
- `docs/orquestracao-dialogo-audio-plano.md` (já mergeado) —
  `DialogOrchestrator`, dono do buffer de palavras/frase (§5.3) e de
  quando a sessão abre/fecha (§2, §3).
- `mobile-app-companion/app/src/main/java/.../libras/LandmarkPipeline.kt` —
  ponto de integração do detector.
- `computer-vision-model/PoC/src/extract.py` §5.2 — normalização por
  distância entre ombros, reusada aqui pra tornar o deslocamento comparável
  entre pessoas/distâncias de câmera diferentes.
- `computer-vision-model/PoC/config.yaml` (`pose_indices`) — os 15 pontos de
  pose (7 face + 4 tronco + 4 braços) que o classificador usa hoje; este
  documento reusa os índices de braço (13,14,15,16) de lá (§4.1). Ampliado em
  2026-09-08 — ver `docs/investigacao-expansao-dataset.md`, Achado D.
- `computer-vision-model/PoC/api/README.md` — a API DTW que este plano
  substitui na prática pra classificação de sinal (§5.1); "andaime de
  validação, não produção".
- `computer-vision-model/treino/README.md`, `treino/gcn.py` — o modelo GCN
  que vira o `.tflite` consumido por `SignClassifier` (§5.2); exportação
  ainda pendente (§5.4, item 1).
- Commit `871b9f7` (B1, `computer-vision-model`) — precedente de que
  "ausência" e "zero" não podem ser tratados como a mesma coisa; mesma lição
  se aplica aqui em §4.3.
- `docs/orquestracao-dialogo-audio-plano.md` (branch
  `refac/orquestracao-dialogo-audio`) — plano irmão, que hoje resolve
  **quando uma sessão abre/fecha** (wake word); depende deste documento pra
  saber o que acontece **dentro** da sessão (§2 deste documento).

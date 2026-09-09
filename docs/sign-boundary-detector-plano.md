# `SignBoundaryDetector` — detecção automática de início/fim de sinalização

> Plano de implementação do componente descrito em `docs/libras-livre-arquitetura.md`
> §4.2 como "heurístico simples baseado em velocidade/presença da mão no
> quadro" (MVP). Este documento é a implementação concreta dessa heurística —
> não é um segmentador novo nem paralelo a nada que já exista. Também cobre o
> que consome cada boundary detectado: classificação local do sinal (§5) —
> necessário pra implementar e testar este componente de ponta a ponta, não
> só a heurística isolada.

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
ponto onde os frames já são acumulados hoje:

```kotlin
// LandmarkPipeline.kt, dentro do listener do ImageReader:
if (collecting) {
  val ts = nextTimestampMs()
  val fl = extractor?.extract(image, ts)
  if (fl != null) {
    synchronized(collectLock) { collected.add(fl) }
    boundaryDetector.onFrame(fl)   // <- novo
  }
}
```

Não substitui `toggleSignCapture()` — complementa. No modo manual (hoje), o
usuário ainda controla início/fim pelo botão; o detector, quando ligado, pode
**adiantar o fim automaticamente** (ex.: parar de acumular e classificar assim
que detectar pausa, sem esperar o toque) ou, no modo contínuo do Produto,
**assumir o controle inteiro** do ciclo início→fim.

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
próprio:

| Grupo usado no deslocamento | Pontos | Papel aqui |
|---|---|---|
| Mãos (42) | 21 pontos × 2 mãos | sinal principal — configuração/movimento fino |
| Braços (4, de `pose`) | cotovelo_esq/dir (13,14), pulso_esq/dir (15,16) | movimento grosso do braço inteiro |
| Tronco (4, de `pose`) | ombro_esq/dir (11,12), quadril_esq/dir (23,24) | **não** entra no deslocamento — fica só como âncora de normalização (já é isso hoje) |
| Face (7, de `pose`) | nariz, olhos, orelhas, boca | **não** entra no deslocamento nesta heurística — cabeça costuma ficar relativamente parada durante um sinal; ver nota abaixo |

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
- Ponto ausente num frame (`null`, ou mão não detectada) não conta como
  "deslocamento zero" — é ausência, tratamento diferente de "parado" (ver
  §4.3). Mesma lição já aprendida do lado do dataset (`computer-vision-model`,
  commit B1: zero != ausência, ausência virando zero cria descontinuidade
  artificial).
- Trabalhar em coordenadas normalizadas (0..1) é sensível à distância da
  câmera — quanto mais perto, maior o deslocamento aparente pro mesmo
  movimento físico. Vale normalizar o deslocamento pela mesma escala que a
  API usa pra classificar (distância entre ombros — ver
  `computer-vision-model/PoC/src/extract.py` §5.2), não usar o valor cru do
  MediaPipe.
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
`computer-vision-model/treino/gcn.py` (0,93-0,94 na literatura, contra 0,70 do
baseline DTW — ver `treino/README.md`), exportado pra `.tflite`.

Classificar por sinal (a cada boundary), em vez de uma vez só ao fim de uma
sessão inteira, só é viável porque a inferência é local e barata — contra a
API remota, que não daria pra chamar dezenas de vezes numa sessão sem
acumular latência.

**Essa exportação pra `.tflite` ainda não existe** — `treino/README.md` diz
explicitamente: "Exportação para TFLite e robustez a mudanças de ponto de
vista ainda precisam ser validadas." É uma dependência real deste plano, não
um detalhe (ver Fase 3, §7, e decisões em aberto, §5.4).

### 5.2 `SignClassifier.kt`

```kotlin
class SignClassifier(context: Context) {
  fun classify(frames: List<FrameLandmarks>): String   // devolve a palavra reconhecida
  fun close()
}
```

Carrega o `.tflite` via TensorFlow Lite `Interpreter`, roda local. Recebe
exatamente o segmento que `SignBoundaryDetector` delimitou (do boundary
anterior até o atual) — este componente só resolve *o que* é o sinal, não
*onde* ele está; a fronteira é sempre responsabilidade do detector (§4).

Substitui o papel que `libras/LandmarkApi.kt` tinha nesse fluxo — esse
arquivo deixa de ser chamado pra classificação de sinal.

### 5.3 Contrato com quem chama (ex.: `DialogOrchestrator`)

Quem integra `SignBoundaryDetector` + `SignClassifier` (hoje,
`orquestracao-dialogo-audio-plano.md` §6.5) recebe uma palavra reconhecida por
boundary, não uma vez só por sessão. Acumular essas palavras numa sequência
falável (buffer por sessão, quando a sessão começa/termina) é responsabilidade
de quem chama — este documento só garante que cada sinal individual chega
classificado assim que termina, independente de quanto tempo depois disso a
sessão externa (seja lá o que a estiver delimitando) leva pra fechar.

Caso de borda a não esquecer: se a sessão externa fechar enquanto o estado
ainda é `SINALIZANDO` (segmento em aberto, sem boundary ainda), quem chama
deve forçar a classificação do que tiver acumulado até ali antes de
descartar — não perder silenciosamente o último sinal.

### 5.4 Decisões em aberto

1. **Exportação do GCN pra `.tflite`** — bloqueador real, não decidido nem
   feito ainda (§5.1). Fase 3 (§7) não começa sem isso.
2. **Peso/latência de classificar por sinal, não por sessão.** Mais
   invocações do modelo por sessão do que no desenho anterior (uma vez só, ao
   fim) — não medido se é desprezível (típico de um GCN pequeno em `.tflite`)
   ou se compete por CPU com o próprio `SignBoundaryDetector` rodando no mesmo
   loop de frames. Só dá pra medir com um `.tflite` de verdade em mãos.

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

### Fase 0 — Instrumentação, sem decidir nada ainda
- [ ] Adicionar log/telemetria do `deslocamento(frame_t)` calculado — **mãos e
  braços separados**, antes de combinar — sem tomar nenhuma decisão de corte,
  só observar as séries temporais.
- [ ] Capturar essas séries em sinais reais conhecidos (usando o
  `MockDeviceKit` com os vídeos de referência da PoC, ou captura real se
  disponível) — visualizar as curvas ao longo de um clipe completo de um
  sinal.
- **Critério de sucesso**: conseguir ver visualmente, no gráfico, onde o sinal
  "começa" e "para" em pelo menos uma das duas curvas (mãos ou braços) — se
  nenhuma mostrar um vale claro, a heurística de deslocamento pode não ser
  suficiente sozinha (ver §8). Também dá o primeiro indício de quanto pesar
  cada grupo em `PESO_MAO`/`PESO_BRACO` (§4.4).

### Fase 1 — Calibração offline
- [ ] Rodar a mesma métrica (mãos+braços) sobre os `.npy` já extraídos pela
  PoC (`computer-vision-model/PoC/data/landmarks/`) — dado real, 430 clipes,
  11 pessoas, sem precisar de captura nova. Os `.npy` já trazem o `pose_subset`
  de 15 pontos (`PoC/config.yaml`), então os índices de braço já estão lá.
- [ ] Escolher `LIMIAR_VELOCIDADE`, `PESO_MAO`/`PESO_BRACO` e
  `JANELA_SUSTENTACAO_MS` que separem bem o "miolo" do sinal (onde mãos/braços
  se movem) das bordas do clipe (onde presumivelmente estão mais parados, por
  ser um clipe já cortado).
- **Ressalva**: os clipes da PoC já vêm pré-segmentados (um sinal por
  arquivo) — não têm a transição real entre sinais que o detector vai
  enfrentar em produção. Esta fase calibra a ordem de grandeza dos
  parâmetros, não os valores finais.

### Fase 2 — Teste online isolado (sem classificar ainda)
- [ ] Implementar o `SignBoundaryDetector` chamando só um log
  ("detectei início/fim aqui"), sem classificar de verdade.
- [ ] Testar com sinalização contínua real (uma pessoa fazendo vários sinais
  em sequência), comparando os cortes automáticos contra onde um humano
  marcaria o início/fim.
- **Critério de sucesso**: falsos cortes (no meio de um sinal) e cortes
  tardios (dois sinais grudados) abaixo de um limiar aceitável — a definir
  junto com quem for revisar os resultados.

### Fase 3 — Integração real: classificação por boundary
- [ ] **Pré-requisito, fora deste repo/branch**: exportar o modelo GCN de
  `computer-vision-model/treino/gcn.py` pra `.tflite` (§5.1) — hoje não
  existe. Bloqueia esta fase inteira.
- [ ] Implementar `SignClassifier.kt` (§5.2) carregando o `.tflite` via
  TensorFlow Lite `Interpreter`.
- [ ] Ligar `SignBoundaryDetector` a `SignClassifier`: a cada boundary,
  classifica o segmento (§5.3).
- [ ] Testar com sinalização contínua real (vários sinais em sequência) —
  confirmar que cada um é classificado separadamente e perto do momento em
  que terminou, não acumulado até um fim de sessão externo.
- [ ] Rodar como um modo alternável (flag), não substituindo o botão manual
  de cara — permite comparar os dois lado a lado antes de trocar o padrão.
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

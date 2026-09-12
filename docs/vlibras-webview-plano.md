# Integração VLibras — avatar 3D em WebView nativa

> Plano de implementação do estado ⑦ da sessão de diálogo (`GERANDO_AVATAR`, em
> `docs/orquestracao-dialogo-audio-plano.md` §5): o texto transcrito da resposta do
> atendente vira glosa e é animado por um avatar 3D, para que a pessoa surda leia
> a resposta em Libras.
>
> **Revisado em 2026-09-12**, contra os quatro repositórios oficiais já clonados,
> contra o estado atual do app, e contra uma **sonda executada de verdade** num
> emulador Android (§0.7). A revisão está em §0; o que foi medido reescreve §4.1,
> §4.2, a tabela de riscos §5 e as Fases 2 e 3.

---

## 0. Revisão de 2026-09-12 — o que mudou desde a primeira versão

Registrado aqui porque altera a leitura de todas as seções abaixo.

### 0.1 O backend próprio não é necessário. O endpoint público funciona.

A versão anterior exigia subir `vlibras-translator-api` + `vlibras-translator-text-core`
+ MongoDB + RabbitMQ via Docker, e listava como custo permanente "depende de manter
backend próprio rodando (sem endpoint público oficial pronto para terceiros)".

**Isso está errado.** O app oficial da Play Store
(`vlibras-mobile-cross-platform/src/services/translate.ts:52`) chama um endpoint
público, sem autenticação. Verificado nesta máquina em 2026-09-12:

```
POST https://traducao2.vlibras.gov.br/translate
Content-Type: application/json
{"text": "você precisa marcar a consulta na recepção"}

200 OK
VOCÊ PRECISAR MARCAR&REGISTRAR CONSULTAR&PESQUISAR RECEPÇÃO
```

Resposta é **texto puro**, não JSON. Três variantes de URL foram testadas, e só uma
serve:

| URL | Resultado |
|---|---|
| `traducao2.vlibras.gov.br/translate` | **200** — é esta |
| `traducao2.vlibras.gov.br/dl/translate` | 401 (exige autenticação) |
| `traducao2-dth.vlibras.gov.br/dl/translate` | 503 |

Atenção: `vlibras-player-webjs/src/config.js` aponta para
`https://traducao2-dth.vlibras.gov.br/dl/translate` — **a URL que dá 503**. O
`config.js` do player está desatualizado e precisa ser sobrescrito.

**Consequência:** a Fase 1 inteira (backend local) sai do caminho crítico e vira
contingência (§7.3). `text-core`, `translator-api`, MongoDB, RabbitMQ e Docker saem
da lista de dependências.

### 0.2 O build Unity já vem pronto no repositório

A Fase 0 anterior tratava isso como incógnita ("verificar se os binários Unity já
vêm prontos ou exigem build a partir de um projeto Unity fonte"). **Vêm prontos**,
em `vlibras-player-webjs/src/target/`:

| Arquivo | Tamanho |
|---|---|
| `playerweb.data.unityweb` | 10,4 MB |
| `playerweb.wasm.code.unityweb` | 3,2 MB |
| `playerweb.wasm.framework.unityweb` | 77 KB |
| `UnityLoader.js` | 157 KB |
| `playerweb.json` | 434 B |
| **total** | **~13,5 MB** |

Nenhum Unity Editor é necessário.

### 0.3 O avatar NÃO é offline — e isto é o achado mais importante

A versão anterior não menciona o dicionário de sinais uma única vez. Ele é o ponto
que mais conflita com a premissa do projeto.

`Player.js:47` executa `playerManager.setBaseUrl(config.dictionaryUrl)`, e
`config.js` aponta para `https://dicionario2-dth.vlibras.gov.br/2018.3.1/WEBGL/`.
**Cada sinal é um AssetBundle baixado da rede em tempo de execução.** Verificado:

```
GET https://dicionario2.vlibras.gov.br/2018.3.1/WEBGL/BR/BOM_DIA
  -> 301 -> /static/BUNDLES/2018.3.1/WEBGL/BR/BOM_DIA  ->  200, 22.576 bytes
```

O app oficial faz o mesmo (`src/services/unity.ts:5`): empacota o player nos
assets (25 MB) e **busca os sinais na rede**.

Isto contradiz diretamente o produto: os óculos atendem num balcão onde a internet
pode faltar, e todo o resto do sistema — reconhecimento, contextualização, TTS, STT —
foi construído para rodar on-device. Um avatar que só funciona online é a única
peça online do fluxo, e ela está justamente na ponta que atende a pessoa surda.

**A boa notícia é que espelhar é barato.** Dez sinais amostrados na produção:

```
BANCO 16,8 KB · DINHEIRO 25,1 KB · ESQUINA 17,8 KB · VOCÊ 19,8 KB · PRECISAR 30,0 KB
MARCAR 19,0 KB · CONSULTAR 30,5 KB · RECEPÇÃO 27,2 KB · OI 21,7 KB · OBRIGADO 17,1 KB
```

Média ~22 KB por sinal. Um vocabulário de atendimento de 300 sinais custa ~6,6 MB —
perfeitamente empacotável. Isso vira a **Fase 3.5** (§6).

### 0.4 O ponto de entrada já existe no código, e não é uma caixa de texto

A versão anterior desenhava o fluxo como "usuário digita texto na UI nativa do app".
Não é mais assim. Desde a orquestração de diálogo, o app já tem a costura pronta:

```kotlin
// DialogOrchestrator.kt:62
// Handoff pro pipeline texto->glosa->avatar (docs/vlibras-webview-plano.md) — ainda não
// implementado nesta branch, por isso é só um callback injetado (ver §6.5 do plano).
private val onAvatarText: (String) -> Unit,
```

```kotlin
// CameraViewModel.kt:214 — hoje só loga
onAvatarText = { text -> Log.d(TAG, "Texto pronto pro avatar (handoff pendente): \"$text\"") },
```

**A entrada é a transcrição do STT** (Vosk pt-BR) da resposta do atendente, entregue
no estado ⑥ → ⑦. Implementar este plano é preencher esse callback, não construir uma
tela de digitação. A tela de digitação continua útil — como bancada de teste (§6,
Fase 4).

### 0.5 A glosa do VLibras não é a glosa do nosso léxico

O projeto agora tem uma trilha de contextualização com `lexico-glosas.json` (41
glosas). **São coisas diferentes e não devem ser confundidas:**

| | nosso léxico | glosa do VLibras |
|---|---|---|
| Direção | glosa → português (pessoa surda fala) | português → glosa (atendente responde) |
| Notação | glosa simples, minúscula | maiúscula, com desambiguação por `&` (`BANCO&DINHEIRO`) |
| Produzido por | o classificador de sinais | `traducao2.vlibras.gov.br/translate` |
| Consumido por | `GlossContextualizer` (nosso) | `player.play(glosa)` (Unity) |

São os dois sentidos do diálogo. Nenhum artefato é compartilhado entre eles.

### 0.6 Licença: o player é LGPLv3

`vlibras-player-webjs/LICENSE` é **LGPLv3**, e a versão anterior do plano não
menciona licenciamento. Empacotar os assets do player no APK é distribuição. Como
são arquivos JS/WASM carregados em runtime pela WebView — não linkados
estaticamente ao nosso código — a obrigação prática é: manter o aviso de licença,
não modificar os arquivos do player sem publicar as modificações, e permitir a
substituição da biblioteca. Precisa de uma decisão registrada antes de publicar
qualquer APK, junto das já tomadas em `docs/decisao-datasets-e-licencas.md`.

---

### 0.7 Sonda executada: o que foi medido, não suposto

Uma sonda Android mínima — WebView + `WebViewAssetLoader` + os binários Unity do
repositório, **sem o wrapper `vlibras.js`** — foi construída e executada em
2026-09-12 num emulador Pixel 7 (API 33, `-gpu host`, passthrough Vulkan para
Intel UHD 770, WebView Chromium 148).

**Ressalva de leitura, que vale para toda esta seção:** é x86_64 com GPU de
desktop. Resultado positivo aqui **não prova** que roda num celular ARM; resultado
negativo seria conclusivo. O teste no aparelho que de fato acompanha os óculos
continua obrigatório.

#### WebGL 2.0: funciona

```
WEBGL2=true  WEBGL1=true
ctx=webgl2  version="WebGL 2.0 (OpenGL ES 3.0 Chromium)"  maxTexture=4096
UnityLoader.SystemInfo.hasWebGL=2  mobile=true  ->  compatibilityCheck: ACEITO
```

O avatar Ícaro renderizou dentro da WebView e **animou a glosa de ponta a ponta**,
confirmado visualmente.

#### Tempo de carga: 6 a 9 segundos

```
UnityLoader.js carregado           ~0,5-0,9 s
download + descompressão 100%      ~4,0 s
### UNITY PRONTO ###               9,1 s (primeira vez) / 6,1 s (cache quente)
```

**Este número decide o ciclo de vida** (§4.2). Não é um detalhe de refinamento.

#### O dicionário remoto funciona, e os tokens com `&` também

O achado que mais muda a tabela de riscos. Com
`setBaseUrl("https://dicionario2.vlibras.gov.br/2018.3.1/WEBGL/BR/")` e a glosa
real devolvida pela API, o console do Chromium registrou:

```
[BundleLoader]: BUNDLE (CONSULTAR&PESQUISAR) CARREGADO COM SUCESSO
Bundle "MARCAR&REGISTRAR" loaded!
Bundle "RECEPÇÃO" loaded!
```

Três coisas ficam provadas de uma vez: **não há problema de CORS** ao buscar o
dicionário a partir da origem `https://appassets.androidplatform.net`; o player
resolve sozinho os tokens com `&` de desambiguação, sem nenhum trabalho nosso; e
acentuação no nome do bundle passa intacta. O risco que a revisão anterior
classificava como "o mais subestimado" está, em grande parte, **morto**.

#### Os `.unityweb` são servidos como `text/plain`, e isso não importa

```
SERVE playerweb.json                -> mime=application/json
SERVE playerweb.data.unityweb       -> mime=text/plain
SERVE playerweb.wasm.code.unityweb  -> mime=text/plain
```

O `AssetsPathHandler` não conhece a extensão e devolve `text/plain`. Carregou
assim mesmo, porque o `UnityLoader` baixa por XHR como *arraybuffer* e descomprime
sozinho. **Não é preciso handler de MIME customizado nem `Content-Encoding`.**

#### Memória: o número real, por processo

| Processo | PSS em repouso | PSS animando |
|---|---|---|
| processo do app | 87–101 MB | ~178 MB |
| `webview:sandboxed_process0` (Unity) | 307 MB | 335 MB |
| `webview:webview_service` | 60 MB | 35 MB |
| **total** | **~455 MB** | **~548 MB** |

O heap Java do app ficou em **3–5 MB de 192 MB** o tempo inteiro. Fica confirmado
que o Unity **não** pressiona o heap do app: ele vive num processo separado, e
`destroy()` devolve os 300+ MB de uma vez.

Mas a versão anterior deste plano dizia "256 MB", lendo o `TOTAL_MEMORY` do
`playerweb.json`. **O custo real de sistema é ~1,8× isso**, somando renderer e
`webview_service`. Para referência, o emulador tinha `totalMem=1965MB`,
`availMem=771MB` e `threshold=216MB` (o ponto em que o Android começa a matar
processos).

#### Dirigir o Unity sem o wrapper gera erros

A sonda chamou `SendMessage("PlayerManager","playNow",glosa)` direto. A animação
rodou, mas o console acusou, repetidamente:

```
ReferenceError: CounterGloss is not defined
ReferenceError: onPlayingStateChange is not defined
```

O Unity chama de volta funções globais de JS que **só o `vlibras.js` define**
(via `PlayerManagerAdapter`). Sem elas, os eventos se perdem — inclusive
`gloss:end`, que é justamente o gancho para saber que ⑦ terminou. **Conclusão:
usar o wrapper oficial, não dirigir o Unity na mão.**

#### O build do player funciona em Node moderno

Contra a expectativa (webpack 1.12, de 2016), `npm install` (458 pacotes) e
`npx webpack` rodaram limpos em **Node 24**, produzindo `build/vlibras.js` (72 KB)
com o `target/` copiado junto. A Fase 2 não precisa de contorno de toolchain.

#### Checagens estáticas no app real: limpas

`AndroidManifest.xml` não declara `android:hardwareAccelerated` — com
`targetSdk 36` o padrão é `true` — e não existe nenhuma chamada a `setLayerType`
no código. As duas causas mais comuns de "WebGL não existe na WebView" estão
descartadas.

---

## 1. Objetivo

Exibir a resposta falada do atendente em Libras, por um avatar 3D, dentro do app
Android, reaproveitando a infraestrutura oficial do VLibras em vez de construir um
motor de tradução e animação do zero.

Recorte: este documento cobre **texto → glosa → avatar animado**. Quem produz o
texto é o `SttEngine` (`docs/orquestracao-dialogo-audio-plano.md` §6.4); onde o
avatar aparece na tela é decisão de UI, tratada na Fase 5.

---

## 2. O que de fato é necessário

Dos 15 repositórios da organização `spbgovbr-vlibras`, depois da revisão §0:

| Repositório | Papel | Necessário? |
|---|---|---|
| `vlibras-player-webjs` | player JS + build Unity WebGL do avatar | **Sim** — é o único que entra no APK |
| `vlibras-mobile-cross-platform` | app oficial da Play Store | Referência de arquitetura (e das URLs corretas) |
| `vlibras-translator-text-core` | texto → glosa, em Python | Não — só se formos auto-hospedar (§7.3) |
| `vlibras-translator-api` | orquestração REST + Mongo + RabbitMQ | Não — idem |
| `vlibras-dictionary-*` | dicionário de sinais | Não diretamente; os bundles vêm do CDN (§0.3) |

**Não existe SDK nativo Android** para renderizar o avatar. O próprio app oficial é
Ionic/Capacitor, ou seja, também WebView. A rota de WebView não é um atalho — é a
arquitetura que o governo usa.

---

## 3. Arquitetura

```
  ⑥ TRANSCREVENDO                    ⑦ GERANDO_AVATAR
  Vosk pt-BR                         │
  "você precisa marcar a consulta"   │
         │                           │
         └── onAvatarText(texto) ────┤
                                     ▼
                    ┌─────────────────────────────────┐
                    │  GlosaTranslator (Kotlin)        │
                    │  POST traducao2.vlibras.gov.br   │──► rede (com cache local)
                    │  /translate                      │
                    └────────────┬────────────────────┘
                                 │ "VOCÊ PRECISAR MARCAR&REGISTRAR ..."
                                 ▼
                    ┌─────────────────────────────────┐
                    │  AvatarWebView (WebView)         │
                    │  WebViewAssetLoader              │
                    │   https://appassets.android...   │
                    │     /assets/vlibras/index.html   │
                    │     /assets/vlibras/target/*     │  ◄── 13,5 MB no APK
                    │     /assets/vlibras/dic/BR/*     │  ◄── espelho do dicionário
                    │            │                     │
                    │  evaluateJavascript              │
                    │    player.play(glosa)            │
                    │            ▼                     │
                    │  UnityLoader → WASM → canvas     │
                    │  avatar anima                    │
                    └─────────────────────────────────┘
```

**A regra que não muda:** a animação depende da GPU de quem assiste, então o player
roda obrigatoriamente no dispositivo. O que é negociável é de onde vêm a glosa e os
bundles de sinal — e a resposta do projeto, pela premissa de balcão, é "do próprio
aparelho sempre que possível".

---

## 4. Detalhes técnicos que decidem a implementação

### 4.1 O formato `.unityweb` — resolvido, medido

Os três binários começam com os bytes `6b 8d 00 55 6e 69 74 79` — o cabeçalho
`UnityWeb`, não gzip cru. O `UnityLoader.js` descomprime em JS, baixando por XHR
como *arraybuffer*.

**Confirmado na sonda (§0.7):** o `AssetsPathHandler` serve os `.unityweb` como
`text/plain` e o Unity carrega normalmente. Não é preciso negociar
`Content-Encoding: gzip`, nem servir `application/wasm`, nem escrever handler de
MIME customizado. Este risco está fechado.

### 4.2 Memória e ciclo de vida — corrigido pela medição

`playerweb.json` declara `TOTAL_MEMORY: 268435456` (256 MB). **Esse número
subestima o custo real.** Medido na sonda (§0.7), em três processos:

| Processo | Repouso | Animando |
|---|---|---|
| processo do app | 87–101 MB | ~178 MB |
| `webview:sandboxed_process0` (Unity) | 307 MB | 335 MB |
| `webview:webview_service` | 60 MB | 35 MB |
| **total** | **~455 MB** | **~548 MB** |

Duas consequências, e a segunda corrige um erro da revisão anterior.

**1. O Unity não pressiona o heap do app.** Ele vive num processo separado e
sandboxado — o heap Java do app ficou em 3–5 MB de 192 MB durante todo o teste.
Não haverá `OutOfMemoryError`. Em compensação, o sistema pode matar o processo do
renderer a qualquer momento, e **sem tratamento isso derruba o app inteiro**:

```kotlin
override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
  (v.parent as? ViewGroup)?.removeView(v); v.destroy()
  return true   // sem este return, o processo do app é terminado
}
```

Isto não é refinamento: é obrigatório desde a primeira linha de WebView.

**2. O ciclo de vida é por atendimento, não por estado.** A revisão anterior dizia
"criar a WebView em ⑦ e destruir ao sair". **Está errado**, e a medição mostra por
quê: o Unity leva **6 a 9 segundos** para ficar pronto. O estado ⑦ acontece a cada
resposta do atendente; destruir e recriar por turno faria a pessoa surda esperar
esse tempo a cada fala.

A granularidade certa é o atendimento, e o gancho já existe — o encerramento por
inatividade de 1 minuto que o `DialogOrchestrator` já implementa:

```
primeiro ⑦ do atendimento    -> cria a WebView e carrega o Unity (custo pago uma vez)
entre turnos (⑦ -> ① -> ⑦)   -> player.stop() + onPause() + pauseTimers()
atendimento encerra           -> destroy()  (devolve os 300+ MB de uma vez)
pressão de memória            -> destroy() via onTrimMemory, se não estiver em ⑦
```

Três níveis de "parar", e só o terceiro é caro de desfazer:

| Nível | Custo para voltar | Quando |
|---|---|---|
| `player.stop()` | zero | entre sinais |
| `onPause()` + `pauseTimers()` | desprezível | entre turnos do mesmo atendimento |
| `destroy()` | 6–9 s | fim do atendimento, ou pressão de memória |

**3. O que pode sair de cena para abrir espaço.** A visão já é liberada antes de
⑦ por construção: na transição ②→③ o orquestrador chama `deactivateCamera` →
`stopStreaming()` → `landmarkPipeline.stop()`, que fecha MediaPipe, decoder e
`ImageReader`. MediaPipe e Unity **nunca coexistem**.

O que ainda não é sob demanda são os motores criados no construtor do
`CameraViewModel` e vivos até `onCleared()`:

| Recurso | Necessário em | Ação sugerida |
|---|---|---|
| Vosk (STT, ~40 MB) | ⑤⑥ apenas | **criar em ⑤, liberar ao sair de ⑥** — maior ganho, menor risco |
| Piper (TTS, ~21 MB) | ③ (e talvez ⑦) | manter: liberar economiza pouco e custa latência de fala |
| Contextualizador `.tflite` | ②→③ apenas | manter — ver abaixo |

Sobre o contextualizador: **os 46 MB do arquivo não são 46 MB de RAM residente.**
O `build.gradle.kts` tem `noCompress += "tflite"` justamente para que o
`Interpreter` o leia por *mmap* — fica mapeado do APK, paginado sob demanda e
descartável sob pressão. Há ainda um comentário explícito no código pedindo que
ele não seja recriado por sessão. É o maior número e o menos urgente.

**Onde essa lógica mora.** O `DialogOrchestrator` já é dono das transições e já
liga/desliga a câmera por dois callbacks injetados. Os análogos entram no mesmo
padrão, sem espalhar lógica pelo ViewModel:

```kotlin
private val ensureAvatarReady: suspend () -> Boolean,
private val releaseAvatar: () -> Unit,
```

**Medir, não adivinhar.** `adb shell dumpsys meminfo <pacote>` por estado — e
somando o processo do renderer, senão o Unity fica invisível no relatório.

### 4.3 A API JS que vamos dirigir

De `Player.js` e `PlayerManagerAdapter.js`:

| Chamada | O que faz |
|---|---|
| `player.play(glosa)` | anima uma glosa **já pronta** — não chama a rede |
| `player.translate(texto)` | traduz **e** anima; usa o `config.js` desatualizado (§0.1) |
| `player.setSpeed(n)`, `pause()`, `stop()`, `repeat()` | controle de reprodução |
| `player.toggleSubtitle()` | legenda em português sobre o avatar |
| evento `gloss:end` | animação terminou — é o gancho para voltar de ⑦ a ① |
| evento `load` | player pronto para receber glosa |

**Use o wrapper `vlibras.js`, não o Unity na mão.** A sonda dirigiu o Unity direto
por `SendMessage` e a animação rodou, mas o console acusou repetidamente
`ReferenceError: CounterGloss is not defined` e `onPlayingStateChange is not
defined`: o Unity chama de volta funções globais que só o `PlayerManagerAdapter`
do wrapper define. Sem elas os eventos se perdem — inclusive `gloss:end`, que é o
gancho para saber que ⑦ terminou.

**Usamos `play()`, não `translate()`.** Traduzir no Kotlin, e não dentro do player,
dá três coisas de graça: a URL correta sem editar o `config.js`, cache da glosa
entre sessões, e um ponto único para tratar a falta de rede.

Por baixo, `play()` vira `SendMessage("PlayerManager", "playNow", glosa)` para o
Unity. O `setBaseUrl` é o que aponta de onde vêm os bundles — é o botão que a
Fase 3.5 usa para servir o dicionário local.

---

## 5. Riscos, recalibrados pela medição

A sonda de §0.7 fechou ou reduziu quatro dos seis riscos da revisão anterior.

| # | Risco | Estado após a sonda |
|---|---|---|
| 1 | WebGL 2.0 não funciona na WebView | **muito reduzido** — funcionou (WebGL 2.0, Chromium 148), e as checagens estáticas do app real estão limpas. Resta confirmar em GPU ARM |
| 2 | Memória derruba o app | **médio, agora quantificado** — ~455 MB em repouso, ~548 MB animando, em 3 processos. Mitigado por ciclo de vida por atendimento e `onRenderProcessGone` (§4.2) |
| 3 | O espelho do dicionário não cobre a glosa (tokens com `&`) | **em grande parte morto** — o player resolve `MARCAR&REGISTRAR` e `CONSULTAR&PESQUISAR` sozinho, sem CORS, direto de `appassets.androidplatform.net`. Resta medir o comportamento quando um bundle **não existe** localmente |
| 4 | O endpoint público sai do ar ou passa a exigir autenticação | **inalterado** — contingência §7.3 continua necessária |
| 5 | MIME/`Content-Encoding` dos `.unityweb` | **fechado** — `text/plain` funciona (§4.1) |
| 6 | Tamanho do APK e licença LGPLv3 | **inalterados** — decisões, não incógnitas técnicas |

O maior risco **remanescente** deixou de ser técnico: é o tempo de carga de 6–9 s
do Unity contra a expectativa de uma conversa de balcão. O ciclo de vida por
atendimento resolve entre turnos, mas a **primeira** resposta de cada atendimento
paga esse custo. Vale decidir se o avatar pré-carrega em ① (custo de memória o
tempo todo) ou se a primeira resposta aceita a espera com um indicador na tela.

## 6. Plano de implementação

A lógica é a mesma da versão anterior — isolar risco, validar cada camada sozinha —
mas as fases mudaram.

### Fase 0 — Concluída na revisão de 2026-09-12

Os três itens em aberto foram respondidos em §0: o build Unity vem pronto, o
endpoint público funciona sem backend próprio, e o dicionário é remoto. Nada a
fazer aqui.

### Fase 1 — Tradução em Kotlin (sem WebView, sem Android)

Substitui a antiga "Fase 1 — Backend local", que saiu de escopo.

- [ ] `VLibrasGlosaTranslator`: `suspend fun traduzir(texto: String): String?`,
      `POST https://traducao2.vlibras.gov.br/translate`, corpo `{"text": ...}`,
      resposta **texto puro** (não JSON).
- [ ] Timeout de 30 s (é o que o player oficial usa) e retorno nulo em falha — quem
      chama decide o fallback.
- [ ] Cache em disco por texto normalizado: a mesma pergunta de balcão se repete o
      dia inteiro, e cache é o que torna o modo offline parcialmente útil.
- [ ] Teste JVM puro com respostas gravadas, no padrão de `app/src/test/`.
- [ ] **Aceite:** teste passa sem emulador e sem rede.

### Fase 2 — Player isolado — parcialmente concluída

- [x] `npm install` + `npx webpack` em `~/vlibras-player-webjs` — funciona em Node 24,
      gera `build/vlibras.js` (72 KB) com `target/` copiado.
- [x] Carregar o Unity e confirmar que o avatar renderiza e anima (feito direto na
      sonda Android, §0.7 — pulou a etapa de desktop).
- [x] Registrar quais bundles o player pede: ele monta a URL a partir do
      `setBaseUrl` mais o token da glosa, **incluindo os tokens com `&` e a
      acentuação, sem transformação nossa**.
- [ ] Rodar o `demo/index.html` no Chrome desktop com o DevTools aberto, para ter a
      **lista completa de URLs** de um vocabulário de atendimento inteiro — é o
      insumo da Fase 3.5.

### Fase 3 — Empacotamento Android — validada pela sonda

O caminho está provado (§0.7); falta trazê-lo para o app real.

- [x] `WebViewAssetLoader` + `AssetsPathHandler` servindo de
      `https://appassets.androidplatform.net/assets/vlibras/` — funciona, e resolve
      o MIME dos `.unityweb` sem handler customizado.
- [x] Medições de carga e memória (§0.7).
- [ ] `implementation(libs.androidx.webkit)` no `build.gradle.kts` do app real — a
      dependência ainda não existe lá. `INTERNET` já está no manifesto.
- [ ] **Decidir onde os 13,5 MB vivem.** O projeto já tem o padrão:
      `download-assets.sh` + `assets/.gitignore`, que mantém fora do git o `.tflite`
      de 46 MB, o Vosk de 40 MB e o TTS de 21 MB. O player deve seguir o mesmo
      caminho e não entrar no histórico.
- [ ] `onRenderProcessGone` tratado (§4.2) — obrigatório.
- [ ] **Repetir a medição num celular ARM real**, o que de fato acompanha os óculos.
      Este é o único item da Fase 3 que a sonda não pode substituir.

### Fase 3.5 — Espelho offline do dicionário

Não existia na versão anterior. É o que reconcilia o avatar com a premissa do
produto (§0.3). A sonda simplificou o trabalho: como o player monta a URL a partir
do `setBaseUrl` mais o token da glosa sem transformação nossa, **espelhar é copiar
os mesmos caminhos**.

- [ ] Escrever `baixar-dicionario-vlibras.sh` no padrão do `download-assets.sh`:
      recebe uma lista de sinais, baixa de `dicionario2.vlibras.gov.br`, grava em
      `assets/vlibras/dic/BR/` preservando o nome exato (acentos e `&` inclusos).
- [ ] Derivar a lista das respostas prováveis do atendente no balcão — o mesmo
      trabalho de `docs/vocabulario-mvp-proposta.md`, no sentido inverso. Passar as
      frases pelo `/translate` e coletar os tokens é o caminho mais direto.
- [ ] Apontar `setBaseUrl` para
      `https://appassets.androidplatform.net/assets/vlibras/dic/BR/`.
- [ ] **Medir o que acontece quando o bundle não existe.** É o único ponto do risco
      3 que a sonda não respondeu, e ele decide se um espelho parcial é aceitável:
      o player provavelmente cai na datilologia (soletrar), o que "funciona" sem
      erro e entrega algo muito pior que o sinal. Confirmar por observação.
- [ ] **Aceite:** avatar anima uma frase inteira com o Wi-Fi desligado.

### Fase 4 — Bridge Kotlin ↔ JS e bancada de teste

- [ ] Embutir o `build/vlibras.js` do wrapper oficial e instanciar `VLibras.Player`
      — **não** dirigir o Unity por `SendMessage` na mão (§4.3): sem o wrapper, os
      callbacks globais que o Unity espera não existem e os eventos se perdem.
- [ ] `evaluateJavascript("player.play('$glosa')")`, com escape de aspas e acentos
      (a glosa vem acentuada e com `&`).
- [ ] `addJavascriptInterface` para os eventos `load` e `gloss:end` — este último é o
      sinal de que ⑦ terminou e a sessão pode voltar a ①.
- [ ] Tela de debug com campo de texto, atrás do mesmo menu do `MockDeviceKit`:
      digitar → traduzir → animar, sem depender de óculos nem de wake word.
- [ ] **Aceite:** digitar uma frase na tela de debug anima o avatar correspondente.

### Fase 5 — Ligar no estado ⑦

- [ ] Preencher `onAvatarText` em `CameraViewModel`, substituindo o `Log.d` atual.
- [ ] Criar a WebView sob demanda e destruí-la ao sair de ⑦ (§4.2).
- [ ] Decidir a apresentação: o avatar é para a **pessoa surda**, e o celular está
      com o atendente — a mesma tensão que `docs/libras-livre-arquitetura.md` §5 já
      levanta. Virar a tela para a pessoa surda é o caminho mais simples e deve ser
      o do MVP, registrando a limitação.
- [ ] Sem rede e sem cache: falar o texto por TTS e mostrar a legenda, em vez de
      travar em ⑦.
- [ ] **Aceite:** ciclo completo — sinalizar, ouvir a frase, responder por voz, ver
      o avatar animar a resposta — funcionando com o `MockDeviceKit`.

### Fase 6 — Refinamento

- [ ] Reavaliar Play Asset Delivery com o tamanho final do APK somado ao que já
      existe (46 + 40 + 21 + 13,5 MB, mais o espelho).
- [ ] Testar em aparelho com WebView antiga.
- [ ] Decisão de licença LGPLv3 registrada (§0.6).
- [ ] Medir quantas vezes, em uso real, a glosa vem do cache e não da rede.

---

## 7. Decisões e contingências

### 7.1 Traduzir no Kotlin, não no player

Custa uma classe a mais e resolve três problemas: o `config.js` do player aponta
para uma URL morta, precisamos de cache, e precisamos de um ponto único para o
comportamento sem rede.

### 7.2 O avatar é a única peça com dependência de rede

E isso precisa estar escrito em qualquer apresentação do projeto. O sentido
surdo → ouvinte é 100% on-device; o sentido ouvinte → surdo depende de rede para
traduzir, e do espelho da Fase 3.5 para animar. A Fase 3.5 fecha metade dessa
lacuna; a outra metade — texto → glosa offline — só fecha com o `text-core` embarcado,
que é trabalho de outra ordem de grandeza (§7.3).

### 7.3 Contingência: auto-hospedar o tradutor

Se o endpoint público cair ou passar a exigir autenticação, o caminho está mapeado e
os repositórios já estão clonados:

- `vlibras-translator-text-core` (Python, `rule_translation` da lib `vlibras-translate`)
  produz a glosa;
- `vlibras-translator-api` (Node) expõe o REST, e exige MongoDB + RabbitMQ;
- ambos têm `docker-compose.yml`.

É o plano antigo, reclassificado de pré-requisito para plano B. Um plano C mais
ambicioso — rodar o `rule_translation` on-device — resolveria §7.2 de vez, mas exige
portar uma biblioteca Python para o Android e não cabe no prazo atual.

### 7.4 O que continua valendo da versão anterior

- Chamar o app oficial por Intent/deep link: descartado, sem API pública.
- Lib nativa Android para o avatar: não existe.
- Servidor gerar vídeo pronto: o `video-core` não é público, e a API de vídeo
  (`/video`, `/video/status/:id`) é assíncrona com fila — pior latência que animar
  ao vivo, para o mesmo resultado.
- WebView + `vlibras-player-webjs`: continua sendo a única rota viável, e é a que o
  app oficial usa.

---

## 8. Referências

- [Organização spbgovbr-vlibras](https://github.com/spbgovbr-vlibras)
- [vlibras-player-webjs](https://github.com/spbgovbr-vlibras/vlibras-player-webjs) — LGPLv3
- [vlibras-mobile-cross-platform](https://github.com/spbgovbr-vlibras/vlibras-mobile-cross-platform) — referência de arquitetura e das URLs corretas
- [vlibras-translator-text-core](https://github.com/spbgovbr-vlibras/vlibras-translator-text-core) — contingência §7.3
- [vlibras-translator-api](https://github.com/spbgovbr-vlibras/vlibras-translator-api) — contingência §7.3
- [VLibras Cross Platform App — Dev Guide (PDF oficial)](https://vlibras.gov.br/files/Dev_VLibras_CrossPlatform_App.pdf)
- [Android WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
- `docs/orquestracao-dialogo-audio-plano.md` §5, §6.4 — de onde vem o texto de ⑦
- `docs/libras-livre-arquitetura.md` §5 — a saída para a pessoa surda

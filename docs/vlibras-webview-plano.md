# Integração VLibras — avatar 3D em WebView nativa

> Plano de implementação do estado ⑦ da sessão de diálogo (`GERANDO_AVATAR`, em
> `docs/orquestracao-dialogo-audio-plano.md` §5): o texto transcrito da resposta do
> atendente vira glosa e é animado por um avatar 3D, para que a pessoa surda leia
> a resposta em Libras.
>
> **Revisado em 2026-09-12**, contra os quatro repositórios oficiais já clonados e
> contra o estado atual do app. A revisão está em §0 e muda as Fases 0, 1 e 5, além
> de acrescentar uma fase nova que a versão anterior não previa.

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

### 4.1 O formato `.unityweb` e o que ele evita

Os três binários começam com os bytes `6b 8d 00 55 6e 69 74 79` — o cabeçalho
`UnityWeb`, não gzip cru. **O `UnityLoader.js` descomprime em JS**, o que significa
que não precisamos negociar `Content-Encoding: gzip` no `WebViewAssetLoader`, e que
a preocupação da versão anterior com o MIME `application/wasm` é menos crítica do
que parecia: quem instancia o WASM é o loader, a partir de um buffer já
descomprimido, não o `fetch()` do navegador direto num `.wasm`.

O que ainda precisa ser conferido na Fase 3 é que o `AssetsPathHandler` não estrague
os bytes (nenhuma reescrita de charset) e que sirva `.unityweb` como
`application/octet-stream`.

### 4.2 Memória

`playerweb.json` declara `TOTAL_MEMORY: 268435456` — **256 MB de heap** só para o
Unity, além do custo da própria WebView. Num celular modesto, isso é o suficiente
para o sistema matar o app em segundo plano. Duas consequências de projeto:

- a WebView do avatar deve ser criada **sob demanda** (estado ⑦) e destruída ao
  voltar para ①, não mantida viva a sessão inteira;
- medir consumo real é critério de aceite da Fase 3, não refinamento da Fase 6.

O build também exige **WebGL 2.0** (`graphicsAPI` no `playerweb.json`), com
fallback declarado para 1.0. WebGL em WebView Android é historicamente irregular;
é o principal risco de compatibilidade do plano.

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

**Usamos `play()`, não `translate()`.** Traduzir no Kotlin, e não dentro do player,
dá três coisas de graça: a URL correta sem editar o `config.js`, cache da glosa
entre sessões, e um ponto único para tratar a falta de rede.

Por baixo, `play()` vira `SendMessage("PlayerManager", "playNow", glosa)` para o
Unity. O `setBaseUrl` é o que aponta de onde vêm os bundles — é o botão que a
Fase 3.5 usa para servir o dicionário local.

---

## 5. Riscos, na ordem em que podem matar o plano

| # | Risco | Probabilidade | Como descobrimos cedo |
|---|---|---|---|
| 1 | WebGL 2.0 não funciona na WebView do aparelho de teste | média | Fase 3, primeiro dia |
| 2 | 256 MB de heap derrubam o app com o pipeline de visão ativo | média | Fase 3, medição obrigatória |
| 3 | O espelho do dicionário não cobre a glosa que o tradutor devolve | **alta** | Fase 3.5 — a glosa usa formas flexionadas e `&` |
| 4 | O endpoint público sai do ar ou passa a exigir autenticação | média | contingência §7.3 pronta antes da entrega |
| 5 | 13,5 MB + dicionário estouram o orçamento de APK | baixa | Fase 3, junto da medição |
| 6 | LGPLv3 impede a distribuição pretendida | baixa | decisão registrada antes do APK |

O risco 3 é o mais subestimado: `"VOCÊ PRECISAR MARCAR&REGISTRAR CONSULTAR&PESQUISAR
RECEPÇÃO"` tem cinco tokens, um deles com `&`. Saber **qual arquivo de bundle** cada
token pede é a primeira coisa a descobrir na Fase 3.5, e não está documentado em
lugar nenhum — sai de observar as requisições do player.

---

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

### Fase 2 — Player isolado, no navegador desktop

- [ ] `cd ~/vlibras-player-webjs && npm install && npm run build`.
- [ ] Servir `demo/index.html` por HTTP local (**não** abrir por `file://`).
- [ ] No console: `player.play('OI TUDO BEM')` e observar o avatar animar.
- [ ] Registrar no DevTools **quais URLs de bundle** o player pede, e com que nome
      exato — é o insumo da Fase 3.5 (risco 3).
- [ ] **Aceite:** avatar anima no Chrome desktop, e a lista de URLs de bundle está
      anotada.

### Fase 3 — Empacotamento Android

- [ ] `implementation(libs.androidx.webkit)` no `build.gradle.kts` (o projeto ainda
      não tem dependência de WebView). `INTERNET` já está no manifesto.
- [ ] Copiar o `dist/` para `app/src/main/assets/vlibras/`.
- [ ] **Decidir onde os 13,5 MB vivem.** O projeto já tem um padrão para isso:
      `download-assets.sh` + `assets/.gitignore`, que mantém fora do git o `.tflite`
      de 46 MB, o Vosk de 40 MB e o TTS de 21 MB. O player deve seguir o mesmo
      padrão, não entrar no histórico do git.
- [ ] `WebViewAssetLoader` com `AssetsPathHandler`, servindo de
      `https://appassets.androidplatform.net/assets/vlibras/` — nunca `file://`.
- [ ] Conferir que `.unityweb` chega íntegro e que o loader instancia o WASM.
- [ ] **Medir:** tempo até o avatar aparecer, pico de RAM do processo, e o mesmo com
      o `LandmarkPipeline` rodando em paralelo.
- [ ] **Aceite:** avatar carrega dentro do app, sem glosa ainda, com os números
      medidos anotados. Se WebGL falhar aqui, o plano para e reavalia.

### Fase 3.5 — Espelho offline do dicionário (nova)

Não existia na versão anterior. É o que reconcilia o avatar com a premissa do
produto (§0.3).

- [ ] A partir das URLs anotadas na Fase 2, escrever `baixar-dicionario-vlibras.sh`
      no padrão do `download-assets.sh`: recebe uma lista de sinais, baixa os
      bundles de `dicionario2.vlibras.gov.br`, grava em `assets/vlibras/dic/BR/`.
- [ ] Derivar a lista de sinais das respostas prováveis do atendente no cenário de
      balcão — o mesmo trabalho de vocabulário de
      `docs/vocabulario-mvp-proposta.md`, no sentido inverso.
- [ ] `player.setBaseUrl('https://appassets.androidplatform.net/assets/vlibras/dic/')`
      para servir localmente.
- [ ] **Definir o comportamento quando falta um sinal**: o player provavelmente cai
      na datilologia (soletrar). Confirmar por observação, não por suposição — é o
      que decide se um espelho parcial é aceitável.
- [ ] **Aceite:** avatar anima uma frase inteira com o Wi-Fi desligado.

### Fase 4 — Bridge Kotlin ↔ JS e bancada de teste

- [ ] `evaluateJavascript("player.play('$glosa')")`, com escape de aspas e acentos.
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

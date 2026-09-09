# Integração VLibras no App Mobile — Documentação e Plano de Implementação

> Documento de referência técnica consolidando a pesquisa sobre a arquitetura oficial do VLibras e o plano de implementação da renderização do avatar via WebView no app Android.

---

## 1. Objetivo

Integrar tradução de texto (PT-BR → Libras) com **avatar 3D animado** dentro do app Android, exibido em **foreground**, reaproveitando a infraestrutura open-source oficial do VLibras (organização `spbgovbr-vlibras` no GitHub) em vez de construir um motor de tradução/animação do zero.

---

## 2. Arquitetura oficial do VLibras

A organização `spbgovbr-vlibras` mantém **15 repositórios públicos**. Os relevantes para este projeto:

| Repositório | Linguagem | Função | Necessário? |
|---|---|---|---|
| [`vlibras-translator-text-core`](https://github.com/spbgovbr-vlibras/vlibras-translator-text-core) | Python | Traduz **texto PT-BR → glosa** usando a lib `vlibras-translate` | ✅ Sim |
| [`vlibras-translator-api`](https://github.com/spbgovbr-vlibras/vlibras-translator-api) | Node.js | Orquestra o backend, expõe a REST API consumida pelo app | ✅ Sim |
| [`vlibras-player-webjs`](https://github.com/spbgovbr-vlibras/vlibras-player-webjs) | JS + Unity WebGL | **Renderiza o avatar 3D** a partir da glosa, em tempo real | ✅ Sim |
| [`vlibras-mobile-cross-platform`](https://github.com/spbgovbr-vlibras/vlibras-mobile-cross-platform) | Ionic/Capacitor (TS) | App oficial da Play Store — usado aqui como **referência de arquitetura** | 📖 Referência |
| `vlibras-dictionary-*` (5 repos) | vários | Dicionário de vídeos de sinais **isolados por palavra** | ❌ Não se aplica ao caso de frases dinâmicas |

### Achados importantes da investigação

- **Não existe SDK/lib nativa Android** oficial para renderizar o avatar. O próprio app oficial (`vlibras-mobile-cross-platform`) é construído em **Ionic + Capacitor** — ou seja, também usa WebView internamente, não renderização nativa.
- **Não existe repositório público `vlibras-translator-video-core`.** Ele é citado apenas como *nome* de dependência no README do `translator-api`, mas seu código não é aberto. Isso não bloqueia o projeto: a geração de vídeo é uma **feature opcional** (flag `videoTranslator` no app oficial), separada do fluxo principal de animação ao vivo.
- **Não existe API pública para "chamar" o app oficial do VLibras** de outro app (sem intents/deep links documentados). Essa rota foi descartada.
- **O fluxo principal não depende de renderização de vídeo**: `texto → glosa → player anima ao vivo` é suficiente e é o que o player-webjs consome diretamente.

### Fluxo de dados real

```
vlibras_translate.rule_translation("Maria comprou por três parcelas de 35,50 reais")
   → "MARIA COMPRAR POR 3 PARCELA 35 VÍRGULA 50 REAL AQUELE LOJA [PONTO]"
```

Essa glosa é o "contrato" entre o backend e o player — é isso que o `vlibras-player-webjs` recebe para decidir quais sinais animar.

---

## 3. Rotas avaliadas (e por que só uma restou)

| Opção | Viável? | Motivo |
|---|---|---|
| Chamar o app oficial do VLibras via Intent/deep link | ❌ | Sem API pública documentada; dependeria de engenharia reversa frágil e não suportada |
| Lib nativa Android própria para o avatar | ❌ | Não existe SDK nativo; o próprio app oficial usa WebView |
| Servidor gera vídeo pronto, app só toca (ExoPlayer) | ❌ | O componente que geraria o vídeo (video-core) não é público |
| **WebView + `vlibras-player-webjs` embutido no app** | ✅ | É exatamente a arquitetura usada pelo app oficial da Play Store |

---

## 4. Arquitetura final

```
┌──────────────────────────┐          ┌───────────────────────────────┐
│      Backend (remoto)     │          │         App Android             │
│                            │          │                                 │
│  vlibras-translator-api    │◄──HTTP───│  1. Usuário digita texto        │
│         │                  │  texto   │  2. App chama a API             │
│         ▼                  │          │                                 │
│  text-core (Python)        │──glosa──►│  3. App recebe a glosa           │
│  texto → GLOSA              │          │        │                        │
│                            │          │        ▼                        │
│  MongoDB + RabbitMQ         │          │  4. WebView carrega player-webjs │
│  (infraestrutura)           │          │     (empacotado nos assets)     │
│                            │          │        │                        │
└──────────────────────────┘          │        ▼                        │
                                        │  5. JS Bridge injeta a glosa    │
                                        │        │                        │
                                        │        ▼                        │
                                        │  6. Avatar 3D anima              │
                                        │     (Unity WebGL no canvas)      │
                                        └───────────────────────────────┘
```

**Regra de ouro**: o backend pode estar em qualquer lugar (nuvem, servidor próprio). O player, por depender de renderização em tempo real na GPU de quem está assistindo, **tem que rodar localmente no dispositivo**.

---

## 5. Os 10 conceitos técnicos fundamentais

| # | Conceito | Resumo |
|---|---|---|
| 1 | **Glosa** | Formato intermediário entre PT-BR e Libras; é o que o player recebe para animar |
| 2 | **WebGL** | API do navegador para desenhar gráficos 3D via GPU dentro de um `<canvas>` |
| 3 | **WebAssembly (WASM)** | Formato binário que roda código compilado (o Unity gera isso no build WebGL) quase em velocidade nativa |
| 4 | **WebView** | Componente Android que embute um motor Chromium dentro de uma tela nativa |
| 5 | **CORS / restrição de `file://`** | O Chromium bloqueia/trata de forma inconsistente `fetch()` feito a partir de `file://`, o que quebra o loader do WASM |
| 6 | **JavaScript Interface (Bridge)** | Mecanismo Android (`addJavascriptInterface`/`evaluateJavascript`) que conecta Kotlin ↔ JS dentro da WebView |
| 7 | **RabbitMQ (fila de mensagens)** | Broker que permite processamento assíncrono entre `translator-api` e `text-core` — a resposta pode não ser instantânea |
| 8 | **MongoDB** | Banco NoSQL usado pelo `translator-api` para persistência; obrigatório para a API subir |
| 9 | **Docker / docker-compose** | Empacota e sobe múltiplos serviços (Mongo, RabbitMQ, API) juntos com um único comando |
| 10 | **Play Asset Delivery** | Mecanismo do Google Play para entregar assets grandes (o build WebGL pode ter dezenas de MB) sob demanda, sem inflar o APK inicial |

---

## 6. Plano de implementação

O plano segue a lógica de **isolar riscos**: validar cada camada separadamente antes de integrar tudo, evitando debugar múltiplos problemas ao mesmo tempo.

### Fase 0 — Preparação e validação de premissas

- [ ] Clonar `vlibras-player-webjs` e verificar se os binários Unity (`.wasm`, `.data`, `.framework.js`) já vêm prontos no repositório ou se exigem build a partir de um projeto Unity fonte (não confirmado na pesquisa).
- [ ] Ler o README de `vlibras-translator-text-core` e `vlibras-translator-api` na íntegra antes de instalar qualquer coisa.
- [ ] Confirmar, olhando o código-fonte de `translator-api` (`src/`), se o endpoint principal de tradução por texto depende de alguma fila relacionada a vídeo — isso ainda não foi verificado com 100% de certeza.

### Fase 1 — Backend local (isolado, sem Android)

- [ ] Subir `docker-compose` com MongoDB + RabbitMQ.
- [ ] Rodar `vlibras-translator-text-core` localmente (`npm run dev` ou equivalente Python).
- [ ] Rodar `vlibras-translator-api` (`npm install && npm run dev`).
- [ ] Testar via `http://localhost:3000/docs` (Swagger) que uma requisição de texto retorna a glosa esperada.
- [ ] **Critério de sucesso**: conseguir mandar `curl`/Postman com um texto e receber a glosa de volta, sem depender do Android ainda.

### Fase 2 — Player isolado (ainda sem Android)

- [ ] Buildar `vlibras-player-webjs` (`npm install` + build).
- [ ] Abrir o `dist/index.html` resultante direto num navegador desktop (Chrome/Firefox).
- [ ] Testar se o avatar carrega e anima corretamente passando uma glosa manualmente via console do navegador.
- [ ] **Critério de sucesso**: ver o avatar 3D animando no navegador, confirmando que o player funciona antes de complicar com WebView/Android.

### Fase 3 — Empacotamento Android (assets)

- [ ] Copiar o `dist/` do player para `app/src/main/assets/vlibras/`.
- [ ] Medir o tamanho total — se for muito grande (dezenas de MB), avaliar Play Asset Delivery em vez de assets padrão.
- [ ] Configurar `WebViewAssetLoader` (não usar `file://` diretamente):

```kotlin
val assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
    .build()

webView.webViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return assetLoader.shouldInterceptRequest(request.url)
    }
}

webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true
webView.loadUrl("https://appassets.androidplatform.net/assets/vlibras/index.html")
```

- [ ] Validar que o `.wasm` é servido com MIME type `application/wasm` (checar se o `AssetsPathHandler` já resolve isso ou se precisa de handler customizado).
- [ ] **Critério de sucesso**: avatar carrega dentro do WebView do app (mesmo sem receber glosa ainda).

### Fase 4 — Bridge JS ↔ Kotlin

- [ ] Implementar `addJavascriptInterface` para receber callbacks do player (ex: "avatar pronto", "animação terminou").
- [ ] Implementar chamada `evaluateJavascript` para injetar a glosa recebida do backend:

```kotlin
webView.addJavascriptInterface(object {
    @JavascriptInterface
    fun onSignReady() {
        // avatar carregado e pronto para receber glosa
    }
}, "AndroidBridge")

fun enviarGlosa(glosa: String) {
    webView.evaluateJavascript("player.translate('$glosa')", null)
}
```

- [ ] **Critério de sucesso**: conseguir digitar um texto na UI nativa do app e ver o avatar animando a glosa correspondente.

### Fase 5 — Integração ponta a ponta

- [ ] Conectar a chamada HTTP real ao `translator-api` (Retrofit ou similar).
- [ ] Tratar o caso assíncrono do RabbitMQ (loading state / polling se necessário).
- [ ] Testar o ciclo completo: usuário digita → app chama API → recebe glosa → injeta na WebView → avatar anima.

### Fase 6 — Refinamento

- [ ] Avaliar necessidade de Play Asset Delivery com base no tamanho final do app.
- [ ] Testar em dispositivos Android mais antigos (WebView desatualizada é o principal risco de compatibilidade WebGL).
- [ ] Medir performance (tempo de carregamento inicial do WASM, uso de RAM).
- [ ] Definir estratégia de cache/fallback caso o backend esteja indisponível.

---

## 7. Trade-offs assumidos

| Vantagem | Custo |
|---|---|
| Reaproveita 100% o trabalho de tradução e animação já pronto do governo | Mais pesado que um player nativo (WebView + WebGL consome mais CPU/RAM) |
| Mesma arquitetura validada pelo app oficial da Play Store | Sem controle fino sobre o avatar (só a API JS que o player expõe) |
| Não exige treinar modelo de tradução nem modelar avatar 3D | Depende de manter backend próprio rodando (sem endpoint público oficial pronto para terceiros) |

---

## 8. Referências

- [Organização spbgovbr-vlibras no GitHub](https://github.com/spbgovbr-vlibras)
- [vlibras-translator-api](https://github.com/spbgovbr-vlibras/vlibras-translator-api)
- [vlibras-translator-text-core](https://github.com/spbgovbr-vlibras/vlibras-translator-text-core)
- [vlibras-player-webjs](https://github.com/spbgovbr-vlibras/vlibras-player-webjs)
- [vlibras-mobile-cross-platform](https://github.com/spbgovbr-vlibras/vlibras-mobile-cross-platform)
- [vlibras-translate (PyPI)](https://pypi.org/project/vlibras-translate/)
- [VLibras Cross Platform App — Dev Guide (PDF oficial)](https://vlibras.gov.br/files/Dev_VLibras_CrossPlatform_App.pdf)
- [Android WebViewAssetLoader (documentação oficial)](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)

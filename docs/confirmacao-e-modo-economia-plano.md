# Confirmação do reconhecimento pro surdo + modo economia de bateria

> Plano de implementação de dois pontos do feedback da banca (2026-09-15), registrados aqui
> porque não são óbvios a partir do código — são decisões de produto/arquitetura, com o motivo.
> Os dois reaproveitam infraestrutura que já existe (`AvatarPlayer`/`AvatarScreen` e os eventos
> de erro que `DeviceSession`/`Stream` já emitem), em vez de construir caminho novo.

---

## 1. Ponto 5 da banca — a pessoa surda precisa ver o que o sistema entendeu

> "Hoje só o atendente sabe o que foi traduzido. Usem a tela que já existe no desenho para
> mostrar à pessoa surda o que foi reconhecido, com o avatar sinalizando de volta a frase
> entendida (e o texto como apoio), para que ela confirme ou corrija."

### 1.1 Estado antes deste plano

O avatar VLibras (`libras/avatar/AvatarPlayer.kt` + `ui/AvatarScreen.kt`,
`docs/vlibras-webview-plano.md`) já existe e funciona — mas só é acionado no estado **⑦
GERANDO_AVATAR**, isto é, só no sentido **atendente → surdo**. O sentido **surdo → atendente**
fala a frase reconhecida só pro atendente, em `DialogOrchestrator.endSignSession()`:

```kotlin
val resultado = contextualizer.contextualize(glosas)
if (resultado.texto.isNotBlank()) speaker.speakAndAwait(resultado.texto)
```

A pessoa surda nunca via o que foi entendido. `libras-livre-arquitetura.md` §9 já previa
"reconhecimentos abaixo de um limiar de confiança são exibidos para confirmação", mas isso não
estava implementado — nem para baixa confiança, nem (o que a banca pede) sempre.

### 1.2 Decisão: reaproveitar o avatar de ⑦, não construir um segundo

`resultado.texto` (a saída do `GlossContextualizer`) já é uma frase em PT-BR — exatamente o
formato que `CameraViewModel.playAvatar(text: String)` já sabe consumir (traduz pro VLibras,
anima, mostra a legenda). **Não existe avatar novo**: é a mesma função, chamada de um novo
ponto do fluxo. A tela (`AvatarScreen`) também não muda — ela já reage só a `avatarState` /
`avatarLegenda` / `avatarVisivel`, sem saber em que estado do diálogo está.

### 1.3 Decisão: como a pessoa confirma ou corrige, sem teclado nem botão

A pessoa surda não tem dispositivo próprio (premissa de `libras-livre-arquitetura.md` §1). A
única entrada que ela tem é sinalizar de novo. Duas rotas foram consideradas:

| Rota | Por que não (sozinha) |
|---|---|
| Sinal `sim`/`não` dedicado | `sim`/`não` são candidatos de vocabulário (`vocabulario-mvp-proposta.md`:64), mas o classificador em produção hoje é `PlaceholderSignClassifier` — não reconhece palavra nenhuma de verdade ainda (`libras/reconhecimento/SignClassifier.kt`). Depender do CONTEÚDO do sinal pra decidir confirmação não é testável nem funcional com o classificador atual. |
| Só timeout (auto-confirma) | Não é confirmação real — a banca pediu "confirme ou corrija", não só "veja". |

**Decisão adotada — não depende do classificador saber o que foi sinalizado, só de saber que
ALGO foi sinalizado:** depois de mostrar a frase reconhecida, abre-se uma janela curta em que a
sessão de captura continua aberta. Qualquer sinal detectado nessa janela — reconhecido ou não —
é tratado como "não é isso, vou de novo": descarta a frase mostrada e reabre
`CAPTURANDO_SINAIS`, com o sinal que acabou de disparar a correção já entrando como o primeiro
da nova tentativa (quando foi classificado com sucesso) ou como pedido de recomeço do zero
(quando falhou). **Silêncio pela janela inteira = confirmação implícita**, e a frase segue pro
atendente. Isto funciona hoje, mesmo com o classificador placeholder — o gatilho é o boundary
detector (que já roda de verdade), não o conteúdo da classificação. Quando o `.tflite` real
entrar (`sign-boundary-detector-plano.md`) e `sim`/`não` estiverem no vocabulário, dá pra trocar
o "qualquer sinal = correção" por uma leitura real de `sim`/`não`, sem mudar a máquina de
estados — só o que `onSignRecognized` faz com o texto durante a confirmação.

### 1.4 Máquina de estados — novo estado ②.5

```
① AGUARDANDO SINAL
        │ "Libras Livre, iniciar"
        ▼
② CAPTURANDO SINAIS            ← inalterado
        │ "Libras Livre, encerrar" (ou timeout de 1 min)
        ▼
②.5 CONFIRMANDO RECONHECIMENTO  ← NOVO
        │  Mostra pro SURDO o que foi entendido: mesmo playAvatar() do ⑦, com a legenda em
        │  texto. A sessão de sinais continua aberta (câmera ligada) durante uma janela curta
        │  (CONFIRMATION_WINDOW_MS) depois que o avatar termina de sinalizar.
        │
        │  ── sinalizou de novo dentro da janela ──▶ volta pra ② com o sinal novo já
        │                                             acumulado (ou zerado, se não reconhecido)
        │
        │ silêncio pela janela inteira = confirmado
        ▼
③ FALANDO (TTS → atendente)     ← como antes, só que agora depois da confirmação
        ▼
        ...  (④⑤⑥⑦ inalterados)
```

Não renumerei ①-⑦ nos comentários existentes do código pra não gerar um diff gigante em
`docs/orquestracao-dialogo-audio-plano.md` e nos comentários do `DialogOrchestrator` — o novo
estado é referenciado como "②.5" nos comentários novos.

`CONFIRMANDO_RECONHECIMENTO` **não** entra em `WAKE_WORD_ACTIVE_STATES` — mesmo padrão de
③⑥⑦: é uma fase automática, não um estado de espera por wake word.

### 1.5 Onde a rotulagem muda (pequeno, mas importa pra clareza)

A legenda do `AvatarScreen` tinha o rótulo fixo "O atendente disse" — certo pra ⑦, errado pra
②.5. `CameraViewModel.playAvatar()` agora decide o rótulo olhando
`dialogOrchestrator.state.value` no momento da chamada (que já reflete o estado correto, porque
`DialogOrchestrator` sempre chama `setState()` antes de invocar `playAvatar`): "Você sinalizou"
em ②.5, "O atendente disse" em ⑦.

### 1.6 Riscos e o que fica pendente de validação em hardware real

- **MediaPipe e o avatar (Unity) passam a coexistir por até a duração da janela de
  confirmação** (`CONFIRMATION_WINDOW_MS`, alguns segundos) — a invariante "MediaPipe e Unity
  nunca coexistem", registrada em `vlibras-webview-plano.md` §4.2, deixa de valer aqui, do mesmo
  jeito que já deixava de valer durante o pré-carregamento do avatar em ②-⑥. A câmera só volta a
  ligar depois que o avatar já terminou de animar (não durante), o que limita a janela de
  coexistência ao tempo da confirmação, não ao da animação inteira. **Precisa medir memória em
  aparelho real** antes de considerar isso resolvido — mesma pendência que o resto do avatar já
  tinha.
- **A correção descarta o sinal usado pra pedir correção.** A pessoa precisa sinalizar de novo
  o sinal que disparou a correção quando ele não foi classificado; quando foi, ele já entra como
  primeiro sinal da nova tentativa (ver §1.3). Simplificação deliberada — sem isso, precisaria
  reaproveitar estado interno do `SignBoundaryDetector` entre sessões, o que não existe hoje.
- **`CONFIRMATION_WINDOW_MS` é um palpite (6 s), não medido.** Curto demais frustra quem
  precisa de mais tempo pra reagir; longo demais atrasa todo atendimento sem sinal errado. Só
  teste com pessoas surdas reais decide o valor certo — está isolado numa constante só, fácil
  de ajustar.

---

## 2. Ponto 10 da banca — bateria

> "O plano de desligar a câmera com bateria baixa é bom. Meçam quanto consome um atendimento
> contínuo (stream+HFP) e levem o número pra banca. [...] Atualmente a câmera não desliga com
> bateria baixa — teria que modificar o fluxo, pulando as etapas de visão, com a comunicação
> ficando só no falado."

### 2.1 Estado antes deste plano

`libras-livre-arquitetura.md` §8 já promete o comportamento ("com bateria baixa, a câmera é
desligada e o sistema mantém apenas a legenda da fala [...], o usuário é avisado da mudança de
modo"), mas **nada no código fazia isso**. `grep` por `battery`/`bateria` no app não encontrava
nada além de um comentário.

**Achado que decide a implementação:** o SDK DAT (`meta-wearables-dat-android`) não expõe uma
API de **percentual** de bateria dos óculos — nem no core, nem em nenhuma das três skills que
documentam sessão/stream/câmera. O que existe são **eventos tipados de erro**, quando a bateria
já está baixa/crítica (`CHANGELOG.md` do SDK):

- `DeviceSessionError.BATTERY_CRITICAL` — na sessão (`com.meta.wearable.dat.core.types`)
- `StreamError.BATTERY_LOW` — no stream (`com.meta.wearable.dat.camera.types`)

Os dois **já chegam** ao app hoje, em `CameraViewModel.kt`:

```kotlin
// observeSession()
session.errors.collect { error ->
  Log.e(TAG, "Session error: ${error.description}")
  wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
}
// setupStreamListeners()
stream.errorStream.collect { error ->
  Log.e(TAG, "Stream error: ${error.description}")
  wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
}
```

Mas só viram um snackbar genérico — nada desliga a câmera. O idioma de comparação
(`error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`) está confirmado contra o
código real de outro sample do mesmo SDK (`DisplayAccess/.../DisplayViewModel.kt:492`); o caso
`StreamError.BATTERY_LOW` segue o mesmo padrão dos outros tipos do pacote
`com.meta.wearable.dat.camera.types` (`StreamState`, `StreamConfiguration`), mas **não tem um
uso confirmado em nenhum sample** — só o nome do `CHANGELOG.md`. Ver §2.4.

### 2.2 Decisão: reagir a eventos de limiar, não tentar poll de percentual

Como não há streaming de %, a estratégia não é "monitorar a bateria" — é reagir ao evento
quando ele chega, exatamente como o app já reage a outros erros de sessão/stream. Isso também
responde à pergunta de produto de forma mais simples: não precisamos decidir um limiar nosso, o
SDK já decidiu (`BATTERY_LOW`/`BATTERY_CRITICAL`) baseado em dados que só ele tem (telemetria
real dos óculos).

### 2.3 Decisão: modo economia é on-off por atendimento, pula ①②③ inteiros

Ao receber o evento:

1. `DialogOrchestrator.onBatteryLow()` liga uma flag `economiaBateria` (exposta como
   `StateFlow<Boolean>` pra UI), termina a sessão de sinais em curso se houver uma (②/②.5) sem
   tentar salvar o que já foi capturado — bateria crítica é urgente, não há tempo pra terminar
   graciosamente — e desliga a câmera.
2. **"Libras Livre, iniciar" em ① deixa de abrir `CAPTURANDO_SINAIS` e passa a abrir
   `ESCUTANDO_ATENDENTE` direto** (mesmo destino de "iniciar" em ④) — pula ②/②.5/③ inteiros.
   Isso é literalmente "as etapas de visão são puladas e a comunicação fica só no falado": o
   sentido atendente→surdo (fala, STT, avatar) continua funcionando normalmente; só o sentido
   surdo→atendente (que depende da câmera) para de existir pro resto do atendimento.
3. O atendente é avisado por voz (`Speaker`) da mudança de modo.

**Por que não tentar voltar ao normal sozinho:** o SDK não documenta nenhum evento de "bateria
recuperada" — só os dois limiares de baixa/crítica. Sem um sinal de volta, `economiaBateria`
fica ligada pelo resto do atendimento (reiniciar o app, ou um evento futuro do SDK, são os
únicos jeitos de sair do modo hoje). Registrado como limitação conhecida, não como bug.

### 2.4 API confirmada por inspeção direta do `.aar` real

Diferente da maior parte do projeto (que não tem Android SDK/rede pra compilar — ver
`orquestracao-dialogo-audio-plano.md`, várias pendências "sem Android SDK no ambiente"), esta
sessão teve rede disponível: `./gradlew :app:compileDebugKotlin` baixou as dependências reais, o
que permitiu abrir os `.aar` (`~/.gradle/caches/.../mwdat-core-0.9.0.aar`,
`mwdat-camera-0.9.0.aar`) e rodar `javap` direto nas classes. Confirmado:

```
com.meta.wearable.dat.core.types.DeviceSessionError extends Enum<...>  — tem BATTERY_CRITICAL
com.meta.wearable.dat.camera.types.StreamError       extends Enum<...>  — tem BATTERY_LOW
```

Os dois são `enum class` de verdade (não sealed class/objects) implementando `DatError`
(`getDescription()`, `getLocalizedDescription(Context)`) — `==`/`when` funcionam normalmente, e
o código deste plano foi compilado contra o SDK real antes de ser considerado pronto (não só
revisado visualmente).

### 2.5 Medição pra banca

Sem % contínuo dos óculos exposto pelo SDK, a medição fica em duas pernas manuais, a cada 10
min durante um atendimento contínuo com stream+HFP ligados:

- **Celular:** `adb shell dumpsys battery | grep level` — scriptável, sem depender de UI.
- **Óculos:** não há atalho programático; ler o % no app oficial Meta AI/companion no mesmo
  intervalo.

Vale registrar pra banca, explicitamente, que a plataforma só oferece os dois eventos de
limiar — não dá pra apresentar uma curva de % dos óculos, só os pontos em que
`BATTERY_LOW`/`BATTERY_CRITICAL` dispararam durante o teste.

---

## 3. O que foi implementado nesta sessão

| Arquivo | Mudança |
|---|---|
| `libras/dialogo/DialogState.kt` | novo `CONFIRMANDO_RECONHECIMENTO` |
| `libras/dialogo/DialogOrchestrator.kt` | ②.5 completo (§1); `economiaBateria` + `onBatteryLow()` (§2) |
| `camera/CameraViewModel.kt` | `session.errors`/`stream.errorStream` chamando `onBatteryLow()`; rótulo dinâmico da legenda do avatar |
| `camera/CameraUiState.kt` | `bateriaBaixa`, `avatarConfirmacaoDoSurdo` |
| `ui/AvatarScreen.kt` | rótulo da legenda por parâmetro (`confirmacaoDoSurdo`) |
| `ui/CameraScreen.kt` | novo `BateriaBaixaBanner`, persistente enquanto `bateriaBaixa` |
| `res/values/strings.xml` | `avatar_caption_label_confirmacao`, `battery_low_banner` |

**Validado nesta sessão, não só revisado visualmente:** diferente da maior parte do projeto
(sem SDK/rede — ver `orquestracao-dialogo-audio-plano.md`), esta sessão teve rede disponível.
`./gradlew :app:compileDebugKotlin`, `:app:testDebugUnitTest` e `:app:assembleDebug` rodaram
**limpos** contra o SDK real com todas as mudanças deste plano — inclusive a leitura de
`DeviceSessionError.BATTERY_CRITICAL`/`StreamError.BATTERY_LOW`, confirmada por `javap` direto
nos `.aar` baixados (§2.4). O que falta é só o que precisa de hardware/pessoas reais (§4).

## 4. Pendente (não feito nesta sessão)

- [ ] Testar em hardware real: janela de confirmação, coexistência MediaPipe+Unity, e os dois
  eventos de bateria (não há como forçar `BATTERY_LOW`/`BATTERY_CRITICAL` sem óculos reais com
  bateria baixa de verdade, ou um mock do DAT que os simule — `mwdat-mockdevice` foi checado só
  por nome nesta sessão, não confirmado se simula esses dois erros).
- [ ] Medição de consumo (§2.5) — depende de sessão real com os óculos.
- [ ] Calibrar `CONFIRMATION_WINDOW_MS` com pessoas surdas reais.
- [ ] Quando o classificador real (`.tflite`) e o vocabulário `sim`/`não` existirem, avaliar
  trocar "qualquer sinal = correção" por leitura do conteúdo (§1.3).
- [ ] Testes automatizados do `DialogOrchestrator` — não existiam antes deste plano nem foram
  adicionados agora (a classe depende de `LandmarkPipeline`/`Context` real, o que pede fakes que
  não existem hoje; ficaria maior que o resto desta mudança). Risco registrado, não ignorado.

---

## 5. Referências

- `docs/libras-livre-arquitetura.md` §5, §8, §9 — saída pra pessoa surda, eficiência energética,
  confirmação por limiar de confiança (as três seções que este plano implementa).
- `docs/vlibras-webview-plano.md` §4.2 — a invariante de coexistência MediaPipe/Unity que este
  plano relaxa por uma janela curta (§1.6).
- `docs/orquestracao-dialogo-audio-plano.md` §5, §6.5 — a máquina de estados original e o
  `DialogOrchestrator`, que este plano estende.
- `docs/vocabulario-mvp-proposta.md`:64 — `sim`/`não` como candidatos de vocabulário (§1.3).
- SDK: `CHANGELOG.md` de `meta-wearables-dat-android` — `DeviceSessionError`/`StreamError`,
  casos de bateria/térmico/peak-power.

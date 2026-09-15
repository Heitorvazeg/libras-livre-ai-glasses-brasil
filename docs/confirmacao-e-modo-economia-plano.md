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

### 1.3 Decisão: botão, não sinal de correção — revisado em 2026-09-15

**Versão original desta seção (substituída, registrada por transparência):** a primeira
implementação não dependia de botão nenhum — mantinha a sessão de captura aberta por uma janela
curta depois do avatar mostrar a frase, e tratava *qualquer* sinal detectado nessa janela como
"não é isso, de novo" (o gatilho era o boundary detector, não o conteúdo da classificação, já
que o classificador em produção ainda é o `PlaceholderSignClassifier` — ver
`libras/reconhecimento/SignClassifier.kt`). Funcionava, mas era mais mecanismo do que o
problema pedia.

**Revisão (pedido do time, 2026-09-15):** simplificar pra dois botões — "Confirmar" e
"Corrigir" — na própria `AvatarScreen`, junto da legenda "Você sinalizou". O operador (atendente,
que segura o celular) aperta um dos dois depois de mostrar a tela pra pessoa surda e ela
confirmar com um aceno/gesto que não precisa ser um sinal reconhecível pelo sistema.
"Confirmar" fala a frase pro atendente e segue o ciclo; "Corrigir" descarta e reabre a captura
de sinais do zero.

**O que isso troca, e por quê:**

- **A câmera não precisa mais ficar ligada durante a confirmação.** Sem sinal pra detectar, não
  há razão pra manter `LandmarkPipeline`/MediaPipe rodando — `endSignSession()` agora desliga a
  câmera **assim que entra** em ②.5, não só quando a confirmação termina. Isso também **fecha o
  risco de coexistência MediaPipe+Unity** que a versão anterior desta seção registrava (§1.6
  antiga) — não existe mais, porque a câmera simplesmente não liga nesse estado.
- **Não depende mais do classificador reconhecer nada.** A versão anterior já não dependia do
  classificador saber *o que* foi sinalizado, só que *algo* foi — mas ainda dependia da câmera e
  do boundary detector estarem rodando. Com botão, nem isso: funciona igual antes ou depois do
  `.tflite` real entrar.
- **`CONFIRMATION_WINDOW_MS` (a constante de 6 s) foi removida.** Em vez de um timer dedicado e
  curto, `confirmarReconhecimento()`/`corrigirReconhecimento()` reaproveitam o
  `IDLE_TIMEOUT_MS` (60 s) que ②/⑤ já usam como timeout de segurança: se ninguém apertar nenhum
  dos dois botões, o app confirma sozinho depois de 1 minuto — mesmo papel que o timeout já
  cumpre nos outros dois estados ativos, pra ②.5 nunca travar o atendimento se o operador largar
  a tela. Isso é uma decisão nova desta revisão, não parte do pedido original — ver §1.6.
- **`corrigirReconhecimento()` reaproveita `beginSignSession()`** (religa a câmera, reseta o
  timeout, reabre `LandmarkPipeline`) em vez de duplicar essa lógica.

### 1.4 Máquina de estados — novo estado ②.5

```
① AGUARDANDO SINAL
        │ "Libras Livre, iniciar"
        ▼
② CAPTURANDO SINAIS            ← inalterado
        │ "Libras Livre, encerrar" (ou timeout de 1 min)
        ▼
②.5 CONFIRMANDO RECONHECIMENTO  ← NOVO — câmera já desligada ao entrar aqui
        │  Mostra pro SURDO o que foi entendido: mesmo playAvatar() do ⑦, com a legenda em
        │  texto. Dois botões na tela: "Confirmar" e "Corrigir".
        │
        │  ── botão "Corrigir" ──▶ volta pra ②, religando a câmera (beginSignSession())
        │
        │ botão "Confirmar", OU timeout de 1 min sem nenhum dos dois = confirmado
        ▼
③ FALANDO (TTS → atendente)     ← como antes, só que agora depois da confirmação
        ▼
        ...  (④⑤⑥⑦ inalterados)
```

Não renumerei ①-⑦ nos comentários existentes do código pra não gerar um diff gigante em
`docs/orquestracao-dialogo-audio-plano.md` e nos comentários do `DialogOrchestrator` — o novo
estado é referenciado como "②.5" nos comentários novos.

`CONFIRMANDO_RECONHECIMENTO` **não** entra em `WAKE_WORD_ACTIVE_STATES` — mesmo padrão de
③⑥⑦: as duas wake words não têm ação nesse estado, só os dois botões.

### 1.5 Onde a rotulagem muda (pequeno, mas importa pra clareza)

A legenda do `AvatarScreen` tinha o rótulo fixo "O atendente disse" — certo pra ⑦, errado pra
②.5. `CameraViewModel.playAvatar()` agora decide o rótulo olhando
`dialogOrchestrator.state.value` no momento da chamada (que já reflete o estado correto, porque
`DialogOrchestrator` sempre chama `setState()` antes de invocar `playAvatar`): "Você sinalizou"
em ②.5, "O atendente disse" em ⑦. O mesmo booleano (`confirmacaoDoSurdo`) também decide se os
botões "Confirmar"/"Corrigir" aparecem — só em ②.5.

### 1.6 Riscos e o que fica pendente de validação em hardware real

- **Modo economia (§2) e "Corrigir" interagem — resolvido, mas vale registrar.** Se a bateria
  ficar crítica enquanto a pessoa está em ②.5 e o operador aperta "Corrigir", não há como religar
  a câmera. `corrigirReconhecimento()` checa `economiaBateria` e cai pra
  `confirmarReconhecimento()` como melhor esforço (fala o que já foi reconhecido em vez de deixar
  o botão sem efeito) — ver §2.3.
- **O timeout de segurança de 1 min (§1.3) é uma adição desta revisão, não um pedido explícito.**
  Sem ele, um operador que largasse a tela de confirmação travaria o atendimento indefinidamente
  — o mesmo problema que o timeout de ②/⑤ já existe pra evitar. Se o time preferir sem
  timeout aqui (só os dois botões, sem rede de segurança), é uma linha para remover
  (`resetIdleTimeout` no fim de `endSignSession()`).
- **Sem teste de UX real ainda.** Quem aperta o botão — o atendente, depois de perguntar/olhar
  pra pessoa surda — é uma decisão de fluxo que só teste com usuários reais confirma que
  funciona bem na prática (mesma ressalva que `libras-livre-arquitetura.md` §10 já registra
  para a tela virada pro visitante, em geral).

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
   `StateFlow<Boolean>` pra UI), termina a sessão de sinais em curso se houver uma (só ②
   segura a câmera aberta agora — ②.5 já desliga sozinha ao entrar, ver §1.3) sem tentar salvar
   o que já foi capturado — bateria crítica é urgente, não há tempo pra terminar graciosamente —
   e desliga a câmera. Se o evento chegar durante ②.5, a confirmação em curso segue seu rumo
   normal (botão ou timeout); só uma eventual "Corrigir" depois disso cai pro melhor esforço
   descrito em §1.6.
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
| `libras/dialogo/DialogOrchestrator.kt` | ②.5 por botão (§1); `confirmarReconhecimento()`/`corrigirReconhecimento()` públicos; `economiaBateria` + `onBatteryLow()` (§2) |
| `camera/CameraViewModel.kt` | `confirmarReconhecimento()`/`corrigirReconhecimento()` repassando pro orquestrador; `session.errors`/`stream.errorStream` chamando `onBatteryLow()`; rótulo dinâmico da legenda do avatar |
| `camera/CameraUiState.kt` | `bateriaBaixa`, `avatarConfirmacaoDoSurdo` |
| `ui/AvatarScreen.kt` | rótulo da legenda por parâmetro (`confirmacaoDoSurdo`); novo `ConfirmacaoRow` (botões Confirmar/Corrigir) |
| `ui/CameraScreen.kt` | novo `BateriaBaixaBanner`, persistente enquanto `bateriaBaixa` |
| `res/values/strings.xml` | `avatar_caption_label_confirmacao`, `avatar_confirmacao_confirmar`, `avatar_confirmacao_corrigir`, `battery_low_banner` |

**Validado nesta sessão, não só revisado visualmente:** diferente da maior parte do projeto
(sem SDK/rede — ver `orquestracao-dialogo-audio-plano.md`), esta sessão teve rede disponível.
`./gradlew :app:compileDebugKotlin`, `:app:testDebugUnitTest` e `:app:assembleDebug` rodaram
**limpos** contra o SDK real com todas as mudanças deste plano — inclusive a leitura de
`DeviceSessionError.BATTERY_CRITICAL`/`StreamError.BATTERY_LOW`, confirmada por `javap` direto
nos `.aar` baixados (§2.4). O que falta é só o que precisa de hardware/pessoas reais (§4).

## 4. Pendente (não feito nesta sessão)

- [ ] Testar em hardware real: os botões "Confirmar"/"Corrigir" fim a fim (religar a câmera ao
  corrigir, timeout de segurança), e os dois eventos de bateria (não há como forçar
  `BATTERY_LOW`/`BATTERY_CRITICAL` sem óculos reais com bateria baixa de verdade, ou um mock do
  DAT que os simule — `mwdat-mockdevice` foi checado só por nome nesta sessão, não confirmado se
  simula esses dois erros).
- [ ] Medição de consumo (§2.5) — depende de sessão real com os óculos.
- [ ] Decidir se o timeout de segurança de 1 min em ②.5 (§1.3, §1.6) é desejado ou se a
  confirmação deve ficar só nos dois botões, sem rede de segurança — não foi pedido
  explicitamente, foi uma adição de engenharia desta revisão.
- [ ] Testes automatizados do `DialogOrchestrator` — não existiam antes deste plano nem foram
  adicionados agora (a classe depende de `LandmarkPipeline`/`Context` real, o que pede fakes que
  não existem hoje; ficaria maior que o resto desta mudança). Risco registrado, não ignorado.

---

## 5. Referências

- `docs/libras-livre-arquitetura.md` §5, §8, §9, §10 — saída pra pessoa surda, eficiência
  energética, confirmação por limiar de confiança, e a ressalva de UX não validada que também se
  aplica aqui (§1.6).
- `docs/orquestracao-dialogo-audio-plano.md` §5, §6.5 — a máquina de estados original e o
  `DialogOrchestrator`, que este plano estende.
- SDK: `CHANGELOG.md` de `meta-wearables-dat-android` — `DeviceSessionError`/`StreamError`,
  casos de bateria/térmico/peak-power.

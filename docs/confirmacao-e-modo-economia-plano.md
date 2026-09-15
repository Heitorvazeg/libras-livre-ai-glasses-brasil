# Confirmação do reconhecimento pro surdo + modo economia de bateria

> Plano de implementação de dois pontos do feedback da banca (2026-09-15), registrados aqui
> porque não são óbvios a partir do código — são decisões de produto/arquitetura, com o motivo.
> **Revisado em 2026-09-15** para reintegrar as duas features em cima do refactor de
> "prontidão de demo" que chegou pela `dev` no mesmo dia (`docs/prontidao-demo/`) — ver §0.

---

## 0. Revisão — integrado ao refactor de prontidão de demo da `dev`

A implementação original deste plano (registrada nas seções abaixo) foi escrita e testada
**antes** de a branch `dev` trazer um refactor grande da mesma máquina de estados
(`docs/prontidao-demo/01` a `11`, "ondas" 1-4): `DialogOrchestrator.kt` ganhou um avaliador de
confiança por frase (`AvaliadorDeFrase`), um painel de conversa (`Conversa.kt`), uma faixa de
avisos priorizada (`Avisos.kt`), regras de transição puras (`Transicoes.kt`) e um botão
principal único por estado (`BotaoPrincipal`). Um merge textual das duas branches teria
conflitos grandes nos mesmos quatro arquivos (`DialogOrchestrator.kt`, `CameraViewModel.kt`,
`AvatarScreen.kt`, `CameraScreen.kt`) sem produzir nada coerente — então, em vez de mesclar às
cegas, as duas features foram **reescritas em cima da arquitetura da `dev`**, tomando a versão
dela como base.

**Achado importante: nenhuma das duas features ficou redundante.**

- **Ponto 5 (confirmação pro surdo):** o `AvaliadorDeFrase` da `dev` (`docs/prontidao-demo/02
  §2.8`) é um filtro de **confiança por frase** — abaixo do limiar, fala "não consegui entender,
  repita" **pro atendente** e conta uma rejeição. Isso é controle de qualidade do
  reconhecimento, não confirmação visual pro SURDO do que foi entendido. A `dev` nunca mostra a
  frase reconhecida pra pessoa surda antes de falar pro atendente — o que a banca pediu não
  existia lá.
- **Ponto 10 (bateria):** o item 7.2/7.4 de `docs/prontidao-demo/07-bateria-e-temperatura.md` lê
  `PowerManager.isPowerSaveMode` — o modo economia **do celular**, não a bateria dos óculos — e
  só mostra um aviso informativo, sem desligar a câmera. O próprio documento registra "A
  verificar: se o SDK dos óculos expõe o nível de bateria" como pendência em aberto — a `dev` não
  sabia que `DeviceSessionError.BATTERY_CRITICAL`/`StreamError.BATTERY_LOW` existem (confirmado
  por `javap` direto no `.aar` real, §2.4).

### 0.1 Onde cada feature encaixou

- **②.5 CONFIRMANDO_RECONHECIMENTO** entra exatamente onde o `AvaliadorDeFrase` decide `Falar`:
  antes, `endSignSession()` ia direto de `Falar(glosas)` para contextualizar e falar
  (`falarFrase`); agora `Transicoes.estadoAposDecisao(Falar)` aponta pra
  `CONFIRMANDO_RECONHECIMENTO`, e uma função nova (`iniciarConfirmacao`) contextualiza, mostra o
  avatar pro surdo e só then fala — reaproveitando a mesma função `playAvatar` que o ⑦ já usa
  (tetos, "Pular" incluídos, docs/prontidao-demo/09-avatar.md §9.1). Isso significa que a
  confirmação **herda de graça** os tetos de tradução/animação e o fallback de legenda que a
  `dev` já tinha construído pro sentido atendente→surdo.
- **Modo economia de bateria** deixou de ser uma flag própria checada em vários lugares e virou
  **um caso a mais de `FalhaCamera`** (`FalhaCamera.BATERIA_BAIXA`) — o mesmo tipo que já cobre
  câmera sem dispositivo, permissão pendente, stream pausado etc. (`docs/prontidao-demo/03
  §3.4`). `CameraViewModel.ensureCameraActiveForLibras()` devolve essa causa quando
  `economiaBateria` está ligada, **antes** de qualquer outra checagem — daí pra frente, "iniciar"
  e "Corrigir" são bloqueados pelo mesmo caminho que qualquer outra falha de câmera já usa
  (`onFalhaCamera` → `Avisos`/`FaixaDeEstado`), sem lógica nova na tela.

### 0.2 Decisões novas desta integração (não estavam na versão original do plano)

- **Confirmar/Corrigir viraram parte do modelo de botão único da `dev` (4.7).** "Confirmar" é
  `AcaoBotao.CONFIRMAR`, tratado pelo `BotaoPrincipalGrande` normal (rótulo, habilitado/
  desabilitado, tudo herdado). "Corrigir" **não** entrou nesse modelo — é um botão pequeno à
  parte, no mesmo padrão que "Cancelar atendimento" já usa (um botão secundário fora do "um botão
  só por estado"), porque ②.5 é a única tela com duas ações válidas ao mesmo tempo.
- **`Transicoes.estadoAposDecisao(Falar)` mudou de `ESCUTANDO_ATENDENTE` para
  `CONFIRMANDO_RECONHECIMENTO`** — `TransicoesTest.kt` foi atualizado (era uma asserção
  explícita). É uma mudança de contrato documentado, não um efeito colateral silencioso.
- **`TipoAviso.BATERIA_OCULOS_BAIXA` (nível ATENÇÃO)** foi adicionado à faixa de estado — avisa
  persistentemente assim que o evento de bateria chega, além do bloqueio que
  `FalhaCamera.BATERIA_BAIXA` já dispara quando alguém tenta iniciar/corrigir.
- **`corrigirReconhecimento()` cai pra `confirmarReconhecimento()`** se `ensureCameraActive()`
  devolver `FalhaCamera.BATERIA_BAIXA` (ou qualquer outra falha) no meio da correção — falar o
  que já foi reconhecido é melhor que deixar o botão sem efeito.
- **Timeout de segurança de 60 s em ②.5** (`TETO_CONFIRMACAO_MS`, reaproveitando a mesma ordem de
  grandeza dos outros tetos de estado ativo da `dev`) — se ninguém apertar nenhum botão, confirma
  sozinho. Adição de engenharia desta revisão, não pedido explícito da banca (ver §1.6).

---

## 1. Ponto 5 da banca — a pessoa surda precisa ver o que o sistema entendeu

> "Hoje só o atendente sabe o que foi traduzido. Usem a tela que já existe no desenho para
> mostrar à pessoa surda o que foi reconhecido, com o avatar sinalizando de volta a frase
> entendida (e o texto como apoio), para que ela confirme ou corrija."

### 1.1 Estado antes deste plano

O avatar VLibras (`libras/avatar/AvatarPlayer.kt` + `ui/AvatarScreen.kt`,
`docs/vlibras-webview-plano.md`) já existe e funciona — mas só era acionado no estado **⑦
GERANDO_AVATAR**, isto é, só no sentido **atendente → surdo**. O sentido **surdo → atendente**
falava a frase reconhecida só pro atendente. A pessoa surda nunca via o que foi entendido.

### 1.2 Decisão: reaproveitar o avatar de ⑦, não construir um segundo

A frase contextualizada (saída do `GlossContextualizer`) já é o formato que
`CameraViewModel.playAvatar(text: String): DesfechoAvatar` sabe consumir (traduz pro VLibras,
anima, mostra a legenda, respeita os tetos de 9.1). **Não existe avatar novo**: é a mesma
função, chamada de um novo ponto do fluxo (`DialogOrchestrator.iniciarConfirmacao`). A tela
(`AvatarScreen`) também não precisou de estrutura nova — só um parâmetro (`confirmacaoDoSurdo`)
que troca o rótulo da legenda e mostra o botão "Corrigir".

### 1.3 Decisão: botão, não sinal de correção — revisado em 2026-09-15

**Versão original desta seção (substituída, registrada por transparência):** a primeira
implementação mantinha a sessão de captura aberta por uma janela curta depois do avatar mostrar
a frase, e tratava *qualquer* sinal detectado nessa janela como "não é isso, de novo". Funcionava
(o gatilho era o boundary detector, não o conteúdo da classificação — o classificador em
produção na época era só um placeholder), mas era mais mecanismo do que o problema pedia.

**Revisão (pedido do time, 2026-09-15):** dois botões — "Confirmar" e "Corrigir" — na própria
`AvatarScreen`, junto da legenda "Você sinalizou". O operador (atendente, que segura o celular)
aperta um dos dois depois de mostrar a tela pra pessoa surda e ela confirmar com um aceno/gesto
que não precisa ser um sinal reconhecível pelo sistema. "Confirmar" fala a frase pro atendente e
segue o ciclo (a escuta abre sozinha, como a `dev` já fazia pra `Falar`); "Corrigir" descarta e
reabre a captura de sinais do zero.

**O que isso troca, e por quê:**

- **A câmera não precisa mais ficar ligada durante a confirmação.** `endSignSession()` desliga a
  câmera **assim que entra** em ②.5 (mesmo ponto em que a `dev` já desligava pra falar
  direto), não só quando a confirmação termina — sem sinal pra detectar, não há razão pra manter
  `LandmarkPipeline`/MediaPipe rodando. Isso também fecha, de graça, o risco de coexistência
  MediaPipe+Unity que a versão anterior desta seção registrava.
- **Não depende do classificador reconhecer nada** — nem do conteúdo (já não dependia, na versão
  anterior), nem de o boundary detector estar rodando (agora nem isso).
- **`corrigirReconhecimento()` reaproveita o mesmo caminho de `ensureCameraActive()`** que
  "iniciar" usa (religa a câmera, mesma checagem de `FalhaCamera`, mesmo aviso na tela se falhar)
  em vez de duplicar essa lógica.

### 1.4 Máquina de estados — novo estado ②.5

```
① AGUARDANDO SINAL
        │ "Libras Livre, iniciar" / botão / tecla de volume
        ▼
② CAPTURANDO SINAIS            ← inalterado (AvaliadorDeFrase decide Falar/PedirRepeticao/Desistir/Ignorar)
        │ decisão = Falar(glosas)
        ▼
②.5 CONFIRMANDO RECONHECIMENTO  ← NOVO — câmera já desligada ao entrar aqui
        │  Mostra pro SURDO o que foi entendido: mesmo playAvatar() do ⑦, com a legenda em
        │  texto ("Você sinalizou"). Botão principal "Confirmar" (AcaoBotao.CONFIRMAR) + botão
        │  pequeno "Corrigir" (função direta, fora do modelo de botão único).
        │
        │  ── "Corrigir" ──▶ volta pra ②, religando a câmera (mesmo ensureCameraActive() do
        │                     "iniciar"; cai pra "Confirmar" se a câmera não religar — ex. modo
        │                     economia de bateria)
        │
        │ "Confirmar", OU timeout de 60 s sem nenhum dos dois = confirmado
        ▼
③ FALANDO (TTS → atendente)     ← como a dev já fazia, só que agora depois da confirmação
        ▼
        ...  (⑤⑥⑦ inalterados — a escuta abre sozinha depois de falar, pulando ④)
```

`CONFIRMANDO_RECONHECIMENTO` **não** entra em `Transicoes.ESTADOS_COM_WAKE_WORD` — mesmo padrão
de ③⑥⑦: as duas wake words não têm ação nesse estado, só os dois botões.

### 1.5 Onde a rotulagem muda

A legenda do `AvatarScreen` tinha o rótulo fixo "O atendente disse" — certo pra ⑦, errado pra
②.5. `ui/CameraScreen.kt` calcula `confirmacaoDoSurdo = ui.dialogState ==
DialogState.CONFIRMANDO_RECONHECIMENTO` e repassa pra `AvatarScreen`, que troca o rótulo da
legenda ("Você sinalizou") e mostra o botão "Corrigir". O estado também ganhou um rótulo em
português na faixa (`estado_confirmando`, `rotuloDoEstado`).

### 1.6 Riscos e o que fica pendente de validação em hardware real

- **O timeout de segurança de 60 s (§0.2) é uma adição desta revisão, não um pedido explícito.**
  Sem ele, um operador que largasse a tela de confirmação travaria o atendimento indefinidamente
  — o mesmo problema que os tetos de ②/⑤/atendimento ocioso da `dev` já existem pra evitar. Se o
  time preferir só os dois botões, sem rede de segurança, é a chamada a `scope.launch` dentro de
  `iniciarConfirmacao()` que sai.
- **Se o operador fechar a tela do avatar ("Fechar" só esconde, não libera) durante ②.5,** o
  botão "Corrigir" some da tela até reabrir o avatar — o "Confirmar" continua acessível pelo
  botão principal da tela de baixo (`ControlesDoAtendimento`), mas não há um "Corrigir" fora da
  tela do avatar. Gap de UX pequeno, não corrigido nesta revisão.
- **Sem teste de UX real ainda.** Quem aperta o botão — o atendente, depois de perguntar/olhar
  pra pessoa surda — só teste com usuários reais confirma que funciona bem na prática (mesma
  ressalva que `libras-livre-arquitetura.md` §10 já registra pra tela virada pro visitante, em
  geral).

---

## 2. Ponto 10 da banca — bateria

> "O plano de desligar a câmera com bateria baixa é bom. Meçam quanto consome um atendimento
> contínuo (stream+HFP) e levem o número pra banca. [...] Atualmente a câmera não desliga com
> bateria baixa — teria que modificar o fluxo, pulando as etapas de visão, com a comunicação
> ficando só no falado."

### 2.1 Estado antes deste plano

`libras-livre-arquitetura.md` §8 já promete o comportamento, mas nada no código fazia isso.
`docs/prontidao-demo/07-bateria-e-temperatura.md` (item 7.2/7.4, da `dev`) trata só o modo
economia **do celular** (`PowerManager.isPowerSaveMode`) e registra como pendência em aberto se o
SDK dos óculos expõe bateria.

**Achado que decide a implementação:** o SDK DAT não expõe uma API de **percentual** — só
**eventos tipados de erro**, quando a bateria já está baixa/crítica:

- `DeviceSessionError.BATTERY_CRITICAL` — na sessão (`com.meta.wearable.dat.core.types`)
- `StreamError.BATTERY_LOW` — no stream (`com.meta.wearable.dat.camera.types`)

### 2.2 Decisão: reagir a eventos de limiar, não tentar poll de percentual

Como não há streaming de %, a estratégia é reagir ao evento quando ele chega — o mesmo caminho
que `session.errors`/`stream.errorStream` já usam pra qualquer outro erro do SDK
(`definirAviso(TipoAviso.ERRO_OCULOS, ...)`, na `dev`). Não precisamos decidir um limiar nosso: o
SDK já decidiu, com telemetria que só ele tem.

### 2.3 Decisão: `FalhaCamera.BATERIA_BAIXA` — o modo economia é um caso de falha de câmera

Em vez de uma flag própria checada em vários pontos do `DialogOrchestrator` (como a primeira
versão deste plano fazia), o modo economia virou **mais um valor do enum `FalhaCamera`** que a
`dev` já usa pra "por que a câmera não subiu" (3.4):

1. `CameraViewModel.onBateriaBaixa()` liga `economiaBateria`, mostra um aviso persistente
   (`TipoAviso.BATERIA_OCULOS_BAIXA`) e chama `DialogOrchestrator.onBateriaBaixa()`.
2. `DialogOrchestrator.onBateriaBaixa()`: se `CAPTURANDO_SINAIS` estiver em curso, encerra na
   hora (mesmo padrão de `encerrarCapturaPorPausaLonga`, já existente pra stream pausado) —
   bateria crítica é urgente, não tenta preservar o que já foi capturado. Fora disso, só marca
   uma flag interna (idempotência + guarda de `corrigirReconhecimento`).
3. `ensureCameraActiveForLibras()` devolve `FalhaCamera.BATERIA_BAIXA` **antes de qualquer outra
   checagem**, sempre que `economiaBateria` estiver ligada — dali em diante, "iniciar" (①→②) e
   "Corrigir" (②.5→②) são bloqueados pelo **mesmo caminho** que qualquer outra falha de câmera já
   usa (`onFalhaCamera` → aviso na faixa de estado), sem UI nova.

Isso é literalmente "as etapas de visão são puladas e a comunicação fica só no falado": o
sentido atendente→surdo (fala, STT, avatar) continua funcionando normalmente; só o sentido
surdo→atendente (que depende da câmera) para de existir pro resto do atendimento.

**Por que não tentar voltar ao normal sozinho:** o SDK não documenta nenhum evento de "bateria
recuperada" — só os dois limiares de baixa/crítica. `economiaBateria` fica ligada pelo resto do
processo (reiniciar o app, ou um evento futuro do SDK, são os únicos jeitos de sair do modo
hoje). Limitação conhecida, não bug.

### 2.4 API confirmada por inspeção direta do `.aar` real

Esta sessão teve rede disponível (incomum pra este projeto — ver
`orquestracao-dialogo-audio-plano.md`, várias pendências "sem Android SDK no ambiente"):
`./gradlew :app:compileDebugKotlin` baixou as dependências reais, o que permitiu abrir os `.aar`
(`~/.gradle/caches/.../mwdat-core-0.9.0.aar`, `mwdat-camera-0.9.0.aar`) e rodar `javap` direto
nas classes. Confirmado:

```
com.meta.wearable.dat.core.types.DeviceSessionError extends Enum<...>  — tem BATTERY_CRITICAL
com.meta.wearable.dat.camera.types.StreamError       extends Enum<...>  — tem BATTERY_LOW
```

Os dois são `enum class` de verdade implementando `DatError` (`getDescription()`,
`getLocalizedDescription(Context)`) — `==`/`when` funcionam normalmente. O código deste plano foi
compilado contra o SDK real (`compileDebugKotlin`, `testDebugUnitTest`,
`compileDebugAndroidTestKotlin`, `assembleDebug`) antes de ser considerado pronto.

### 2.5 Medição pra banca

Sem % contínuo dos óculos exposto pelo SDK, a medição fica em duas pernas manuais, a cada 10 min
durante um atendimento contínuo com stream+HFP ligados:

- **Celular:** `adb shell dumpsys battery | grep level` — scriptável. A `dev` já loga a bateria
  do **celular** (`sistema.bateriaPct`, docs/prontidao-demo/03 §3.8) no painel de métricas e no
  CSV do gravador de sessão — reaproveitável direto pra essa medição.
- **Óculos:** não há atalho programático; ler o % no app oficial Meta AI/companion no mesmo
  intervalo.

Vale registrar pra banca, explicitamente, que a plataforma só oferece os dois eventos de
limiar — não dá pra apresentar uma curva de % dos óculos, só os pontos em que
`BATTERY_LOW`/`BATTERY_CRITICAL` dispararam durante o teste.

---

## 3. O que foi implementado

| Arquivo | Mudança |
|---|---|
| `libras/dialogo/DialogState.kt` | novo `CONFIRMANDO_RECONHECIMENTO` |
| `libras/dialogo/Transicoes.kt` | `AcaoBotao.CONFIRMAR`/`RotuloBotao.CONFIRMAR`; `botaoPrincipal()` e `estadoAposDecisao()` cobrindo ②.5 |
| `libras/dialogo/Avisos.kt` | novo `TipoAviso.BATERIA_OCULOS_BAIXA` |
| `libras/dialogo/DialogOrchestrator.kt` | `iniciarConfirmacao`/`confirmarReconhecimento`/`corrigirReconhecimento` (②.5, §1); `onBateriaBaixa()` (§2) |
| `camera/FalhaCamera.kt` | novo `BATERIA_BAIXA` |
| `libras/TextosLibras.kt` | `falhaCamera()` cobrindo `BATERIA_BAIXA` |
| `camera/CameraViewModel.kt` | `economiaBateria` + `onBateriaBaixa()`; `session.errors`/`stream.errorStream` chamando `onBateriaBaixa()`; `ensureCameraActiveForLibras()` checando `economiaBateria` primeiro; `corrigirReconhecimento()` repassando pro orquestrador |
| `ui/AvatarScreen.kt` | `confirmacaoDoSurdo` (rótulo da legenda) + botão "Corrigir" |
| `ui/CameraScreen.kt` | `rotuloDoEstado`/`rotuloDoBotao` cobrindo os novos casos; `AvatarScreen(...)` repassando `confirmacaoDoSurdo`/`onCorrigirReconhecimento` |
| `res/values/strings.xml` | `avatar_caption_label_confirmacao`, `avatar_confirmacao_confirmar`, `avatar_confirmacao_corrigir`, `estado_confirmando`, `falha_camera_bateria_baixa` |
| `.../dialogo/TransicoesTest.kt` | `estadoAposDecisao(Falar)` e os mapas exaustivos por `DialogState` atualizados pro novo estado |

**Validado, não só revisado visualmente:** `./gradlew :app:compileDebugKotlin`,
`:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin` e `:app:assembleDebug` rodaram
**limpos** contra o SDK real, incluindo o merge completo do refactor de prontidão de demo da
`dev` (142 arquivos) com as duas features reintegradas por cima. Todos os testes JVM existentes
(`AvaliadorDeFraseTest`, `ConversaTest`, `AvisosTest`, `TransicoesTest` incluso) continuam
passando.

## 4. Pendente

- [ ] Testar em hardware real: os botões "Confirmar"/"Corrigir" fim a fim (religar a câmera ao
  corrigir, timeout de segurança), e os dois eventos de bateria (não há como forçar
  `BATTERY_LOW`/`BATTERY_CRITICAL` sem óculos reais com bateria baixa de verdade, ou um mock do
  DAT que os simule).
- [ ] Medição de consumo (§2.5) — depende de sessão real com os óculos.
- [ ] Decidir se o timeout de segurança de 60 s em ②.5 (§1.3, §1.6) é desejado ou se a
  confirmação deve ficar só nos dois botões, sem rede de segurança.
- [ ] O gap de UX do "Corrigir" sumir quando a tela do avatar é fechada manualmente (§1.6).
- [ ] Testes automatizados do `DialogOrchestrator`/`CameraViewModel` — não existem hoje (dependem
  de `LandmarkPipeline`/`Context` reais); os testes novos que existem (`TransicoesTest`) cobrem
  só as regras puras que este plano tocou.

---

## 5. Referências

- `docs/libras-livre-arquitetura.md` §5, §8, §9, §10 — saída pra pessoa surda, eficiência
  energética, confirmação por limiar de confiança, e a ressalva de UX não validada.
- `docs/prontidao-demo/02-classificador.md` §2.8 — `AvaliadorDeFrase`, o filtro de confiança que
  este plano encadeia (não substitui).
- `docs/prontidao-demo/03-captura-e-landmarks.md` §3.4 — `FalhaCamera`, reaproveitado pro modo
  economia de bateria (§2.3).
- `docs/prontidao-demo/04-turnos-wake-word-e-botoes.md` — `Transicoes`/`BotaoPrincipal`, o modelo
  de botão único que ②.5 estende com "Corrigir".
- `docs/prontidao-demo/07-bateria-e-temperatura.md` §7.2, §7.4 — o modo economia do celular
  (diferente do que este plano cobre) e a pendência "SDK expõe bateria dos óculos?" que este
  plano fecha.
- `docs/prontidao-demo/09-avatar.md` §9.1, §9.2 — os tetos e o botão principal do avatar, que
  ②.5 reaproveita via `playAvatar`.
- `docs/prontidao-demo/10-tela.md` §10.1, §10.2 — painel de conversa e faixa de estado.
- `docs/orquestracao-dialogo-audio-plano.md` §5, §6.5 — a máquina de estados original.
- SDK: `CHANGELOG.md` de `meta-wearables-dat-android` — `DeviceSessionError`/`StreamError`,
  casos de bateria/térmico/peak-power.

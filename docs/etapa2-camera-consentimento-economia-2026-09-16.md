# Etapa 2 — abertura de câmera, consentimento e economia

> **Atualização 17/09:** implementação compilada e regressão JVM executada;
> [resultados consolidados e limites](integracao-infraestrutura-fechamento-2026-09-17.md).
> As referências abaixo a “não executado” descrevem a entrega inicial, não o estado atual.

Implementação no worktree `/home/walisson/libras-livre-integracao-modelo-app`, base
`07bc365`. Sem commit/push. **Build e testes não executados** por solicitação.
Revisão feita somente por leitura do código/diff e consulta aos diagnósticos do editor;
isso não comprova compilação nem integração com DAT.

## Escopo e arquivos

Os caminhos de código abaixo são relativos a
`mobile-app-companion/app/src`, no pacote
`com.meta.wearable.dat.externalsampleapps.cameraaccess`.

| Arquivo | Mudança |
|---|---|
| `main/.../camera/PoliticaCamera.kt` | Autoridade compartilhada de consentimento/economia; geração monotônica e tokens. |
| `main/.../camera/CameraViewModel.kt` | Proteção de todas as entradas de stream, cancelamento de permissão/abertura, esperas sensíveis à geração e teardown por identidade. |
| `main/.../PermissaoExterna.kt`, `main/.../MainActivity.kt` | Ponte retida com launcher atual associado/dissociado por dono no lifecycle; fila guarda entrada, não lambda da Activity antiga. |
| `main/.../stream/ControleDonoStreaming.kt`, `main/.../stream/StreamingService.kt` | Ownership por VM, revisão monotônica e reconciliação do estado desejado ao entregar intents tardios. |
| `test/.../PermissaoExternaTest.kt`, `test/.../stream/ControleDonoStreamingTest.kt` | Regressões de rotação/fila e teardown entre VMs/ordenação de intents, adicionadas sem execução. |
| `main/.../libras/dialogo/DialogOrchestrator.kt` | Revogação/cancelamento no Recusar; economia cobre abertura e repetição; preview com consentimento explícito. |
| `main/.../libras/dialogo/CapturaDialogo.kt` | Três funções do pipeline como porta para testes JVM. |
| `test/.../camera/PoliticaCameraTest.kt` | Tokens antigos, parada sem stream, recusa/novo aceite, economia e retorno tardio de permissão. |
| `test/.../libras/dialogo/DialogOrchestratorCameraTest.kt` | Orquestrador real com portas falsas, coroutines e relógio virtual. |
| `androidTest/.../InstrumentationTest.kt` | Helper sample aceita consentimento antes de pedir permissão DAT; teste negativo de preview sem consentimento. |

Além disso, `mobile-app-companion/app/build.gradle.kts` declara
`kotlinx-coroutines-test:1.10.2` somente para testes JVM. O orquestrador usa a interface
`TtsEngine`, recebendo a mesma instância `vozEmCadeia` à qual `Speaker` já delegava.
Não houve mudança de motor/saída de áudio.

Treino, pesos, assets, thresholds, avaliador de frase e contextualização não foram alterados.

## Lifecycle/callers inspecionados antes das alterações

- `CameraScreen`: `BottomBar` → `SessionControlRow` → `startStreaming`/`stopStreaming`;
  diálogo de permissão → confirmar/cancelar redirect. Surface apenas anexa o decoder.
- `CameraViewModel`: `ensureCameraActiveForLibras` chama a abertura interna; os únicos
  callers de `beginStream` são o retorno da consulta de permissão e o do redirect.
- `DialogOrchestrator`: Aceitar/Corrigir chamam ensure; confirmação, desistência,
  cancelamento, pausa longa e bateria chamam deactivate; repetição chama `iniciarCaptura`
  diretamente depois de TTS, mantendo a câmera do turno anterior.
- Sessão DAT: criação, start/stop, collectors, erros críticos e cleanup. Stream DAT:
  listeners, STARTING/STOPPED/CLOSED, parada, gravação e limpeza assíncrona.
- `onCleared`, `MainActivity` e seu pedido de permissão suspenso, `CameraAccessScaffold`,
  lifecycle do avatar e `StreamingService`. Não foi adicionado desligamento em `ON_PAUSE`:
  o redirect ao app Meta precisa sobreviver à troca de app, e o serviço suporta background.
- Callers de testes sample: abertura inicial, permissão negada, restart, foto/gravação,
  fold, pausa/retomada e fim de sessão.

## Design e invariantes

1. **Consentimento não é DialogState.** `PoliticaCamera` é uma única instância, passada
   explicitamente ao orquestrador. Só permite abrir se houver consentimento e não houver
   economia. A permissão DAT concedida não substitui consentimento.
2. **Geração, não apenas Job.** Recusar, cancelar atendimento, parar stream, cancelar
   redirect, terminar sessão e cleanup invalidam a abertura. A economia é travada até
   outro ViewModel. Um novo Aceitar não torna válido um callback da geração anterior.
3. **Revalidação nas fronteiras assíncronas.** Consulta de permissão, retorno do redirect,
   aquisição da Camera e esperas de sessão/stream validam token. O redirect consome seu
   token original uma única vez. Uma Camera devolvida a um pedido inválido é fechada,
   sem iniciar seu stream nem o serviço.
4. **Cancelamento/timeout.** `stopStreaming` cancela abertura e limpa pedido de redirect
   mesmo sem `hasStream`/Camera. A espera de ensure observa a geração junto do UI state;
   uma invalidação a acorda mesmo sem transição nominal DAT. Timeout/finally cancela a
   abertura somente se ainda for seu token, nunca o trabalho de um novo pedido.
5. **Teardown.** Uma Camera existente recebe stop imediatamente, inclusive em STARTING.
   A limpeza não depende de um evento terminal que talvez não venha nessa janela.
   A gravação existente ainda é finalizada antes de liberar recursos/serviço; o cleanup
   suspenso usa identidade de Stream e não pode limpar um sucessor. Collectors antigos
   não atualizam sessão/stream novos.
6. **Aceitar → Recusar.** Revoga política, incrementa geração do diálogo, cancela seu Job
   e sempre chama deactivate. `finally` antigo não libera a guarda de um Aceitar novo.
7. **Economia.** Desliga preview/câmera e invalida abertura em qualquer estado. Interrompe
   captura, Aceitar em voo e finalização/repetição em FALANDO. A guarda final de
   `iniciarCaptura` consulta a política novamente. Fala confirmada/contextualização não
   são confundidas com aviso de repetição.

Todas as mutações da política e os efeitos DAT são serializados na main, como os callers
existentes. A geração usa `StateFlow` para acordar esperas, não para conceder autorização
por recomposição da UI.

## Preview sample e consentimento

- Primeiro Start Preview sem consentimento pede Aceitar/Recusar pelo fluxo existente;
  não consulta permissão nem liga stream antes de Aceitar.
- O pedido marca **somente preview**: após Aceitar/ensure, mantém stream para foto/vídeo
  sample, sem iniciar reconhecimento nem exigir aquecimento do classificador.
- Stop Preview invalida a abertura, mas não revoga o consentimento; restart no mesmo
  atendimento/sessão funciona. Recusar, Cancelar atendimento, End Session ou encerramento
  por inatividade revogam. Um novo Iniciar de Libras para o preview e pede consentimento
  novamente, preservando a regra conservadora da base.
- O preview consentido volta a AGUARDANDO_SINAL e fica sujeito ao teto ocioso existente
  de 60 segundos; ao encerrar atendimento, câmera também é desligada. Isso deve ser
  considerado ao ensaiar gravações sample longas.

### Testes sample atualizados

`InstrumentationTest.startSessionAndStream` agora aceita consentimento antes do redirect.
Isso adapta: `startSessionThenStreaming`, `stopStreamingKeepsSession`, `endSession`,
`endSessionAvailableWhileStreaming`, `videoRecordStartAndStop`, `videoPreviewShowsShare`,
`photoCaptureWhileRecording`, `photoCaptureAndDismiss`, `foldingGlassesStopsPreview`,
`singleTapPausesPreviewThenResumes`, `stopRecordingEnabledWhilePaused` e
`recordingSavesAcrossPauseResume`. `cameraPermissionDeniedKeepsSessionReady` também foi
adaptado, pois iniciava preview diretamente. O restart de `stopStreamingKeepsSession`
continua sem pedir outro consentimento. O novo
`previewSemConsentimentoNaoPedePermissaoENaoCaptura` verifica recusa e novo pedido.

Os testes de fluxo Libras que já apertavam Aceitar continuam usando o caminho de captura,
não o modo somente preview. Não foi criada flag de bypass para testes/builds.

## Pendências e riscos para revisão

- **Corrigir/fallback/timeout preservados.** Falha de câmera em Corrigir ainda fala a frase
  pendente por melhor esforço; timeout de confirmação ainda confirma sozinho. Isso exige
  decisão de produto/acessibilidade (especialmente falar uma frase que o operador quis
  corrigir), mas esta etapa não muda essa decisão. Há teste de regressão para bateria
  durante Corrigir e para confirmação por timeout em economia.
- **Voz somente:** não foi criado modo novo nem novo caminho de entrada para escuta.
  A fala/escuta já alcançada continua; um novo Iniciar de sinais é bloqueado em economia.
- **Texto de consentimento:** continua sendo placeholder, inclusive “Nada é gravado”,
  incompatível com uso real dos controles sample de foto/vídeo/diagnóstico. Esta proteção
  técnica não valida esse texto nem autoriza gravação de pessoas. Revisão jurídica,
  comunidade surda e definição do produto seguem pendentes; uso apenas em ensaio.
- **Permissão real/DAT:** os testes JVM cobrem política e lógica do orquestrador, não
  instanciam `CameraViewModel`/SDK. Ainda requer validar redirect real, bateria durante
  handshake, câmera em STARTING, fechamento físico e finalização de gravação no aparelho.
  O contrato de resultado da Activity continua sendo o DAT; a revisão adicional abaixo
  corrige a ponte de ownership, pois só o token no VM não bastava para impedir A → B.
- **Background/rotação:** a revisão final abaixo acrescenta ownership do launcher e do
  serviço entre VMs; não redesenha a sessão DAT nem desliga stream em `ON_PAUSE`.
  Política não persiste em disco; um ViewModel novo começa sem consentimento.
- **Sem comprovação de build:** a dependência de teste e a compilação precisam ser
  verificadas pelo operador. Diagnósticos vazios do editor não são evidência de compilação.

## Comandos sugeridos (não executados)

JVM focado, com caminhos absolutos para não usar acidentalmente o outro worktree:

```bash
/home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion/gradlew \
  -p /home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion \
  :app:testDebugUnitTest \
  --tests '*PoliticaCameraTest' --tests '*DialogOrchestratorCameraTest' \
  --tests '*FalhaCameraTest' --tests '*TransicoesTest' --tests '*AvaliadorDeFraseTest'
```

Suíte JVM completa:

```bash
/home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion/gradlew \
  -p /home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion \
  :app:testDebugUnitTest
```

Instrumentados, com aparelho/emulador, credenciais DAT e assets requeridos já preparados:

```bash
/home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion/gradlew \
  -p /home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.meta.wearable.dat.externalsampleapps.cameraaccess.InstrumentationTest,com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.ConfirmacaoReconhecimentoTest
```

Manualmente: permissão negada → Aceitar → Recusar antes do retorno; repetir com bateria
baixa/crítica durante handshake; repetir durante AVISO_REPITA; consentir novamente e
entregar retorno do pedido antigo; parar durante STARTING; End Session durante redirect;
validar captura após consentimento e Corrigir sem economia. Observar estado físico dos
óculos/LED, stream e serviço, não apenas o texto da tela.

## Revisão adicional — quatro achados confirmados por leitura

Aplicada no mesmo worktree, preservando as alterações anteriores. **Nenhum build, teste,
commit ou push executado.** Os diagnósticos consultados no editor não apontaram erros,
mas não substituem compilação nem a execução dos testes adicionados.

### 1. Espera humana fora do prazo técnico

- `aguardandoPermissaoCamera` cobre confirmação local, espera na fila do launcher e
  decisão externa no Meta AI. Não é desligada ao apenas esconder o diálogo local.
- `aguardarCameraSemContarPermissao` limita a consulta técnica e, após a decisão,
  a abertura técnica a 8 s por fase; não contabiliza os segundos da espera humana.
- Cancelamento da coroutine e invalidação da política continuam acordando a espera
  imediatamente. Os tetos de sessão, pausa, confirmação e ociosidade não foram alterados.

### 2. Resultado externo mantém dono até ser consumido

- `PermissaoExterna` é uma ponte sem dependências Android. Cada launcher tem seu próprio
  destino de resultado; os dois compartilham exclusão para não sobrepor pedidos externos.
- O deferred de cada pedido não é filho da coroutine solicitante. Cancelar A retorna
  prontamente para seu chamador, mas NÃO libera o slot externo. B só lança depois do
  callback A, cujo resultado é consumido/descartado exclusivamente para A.
- A ponte limpa o destino antes de acordar coroutines, aceita callback síncrono e libera
  o slot se `launch` lançar uma exceção antes de estabelecer o pedido.
- `PermissoesPendentesViewModel` retém essa ponte durante recriação por configuração da
  Activity. As operações da ponte/launcher são confinadas à main.

### 3. Confirmar vence Corrigir ainda abrindo câmera

- `confirmarReconhecimento` incrementa a geração, cancela a abertura, libera sua guarda
  e chama `deactivateCamera` antes de começar a fala. Isto invalida também o token da
  permissão/abertura física, não apenas o próximo passo do diálogo.
- Retorno/finally antigo não pode iniciar captura nem alterar a guarda de novo Aceitar.
- O fallback de Corrigir continua chamando a mesma confirmação quando a câmera falha,
  inclusive por bateria. Não mudou a frase falada, a folga nem o timeout do produto.

### 4. Drenagem antes de teardown e reutilização

- `cancel()` isolado não interrompe `handleVideoFrame` síncrono. Todos os caminhos de
  encerramento, inclusive falha de `Stream.start`, passam a esperar `cancelAndJoin` do
  produtor em outro job antes de liberar decoders/pipeline/CSD/recorder.
- O stream permanece ocupado em STOPPING até terminar a drenagem e a limpeza. Uma
  abertura subsequente não pode reutilizar esses consumidores durante esse intervalo.
- A finalização da gravação ainda antecede a liberação do serviço. Start/stop de gravação
  usam exclusão própria e terminam seu IO iniciado mesmo ao cancelar o solicitante; a
  limpeza não disputa com uma preparação do muxer ainda em voo. Após drenar o produtor,
  não se espera um keyframe que já não pode chegar.
- `onCleared` agenda a limpeza em escopo próprio, pois `viewModelScope` já foi cancelado.
  Não usa `runBlocking`, não faz join sob `decoderLock`, lock do muxer ou do gravador.
- Ownership verificado: `VideoRecorder` usa o scope do VM para timer (não o job de vídeo),
  `VideoCaptureHandler` serializa muxer internamente, e `GravadorSessao` recebe callbacks
  do ImageReader e enfileira escrita em executor próprio. O encerramento final espera a
  disposição do pipeline antes de encerrar o gravador; a espera das threads fica fora
  da main e sem locks de callbacks.

### Testes adicionados (não executados)

- `PermissaoExternaTest`: A cancelado/B aguardando, cancelamento na fila e depois do
  callback, resultado sem dono, falha/sucesso síncrono do launcher, destinos câmera/áudio.
- `EsperaCameraTest`: decisão humana acima de 8 s, prazo técnico após decisão e na
  consulta, invalidação e cancelamento durante a espera humana. Relógio virtual.
- `DialogOrchestratorCameraTest`: Corrigir → Confirmar cooperativo/não cooperativo,
  retorno antigo durante novo Aceitar, e Corrigir bem-sucedido com Confirmar atrasado.
  Permanecem os testes anteriores de fallback de bateria e timeout de confirmação.
- `DrenagemVideoTest`: frame síncrono bloqueado por latch (sem sleep), cancelamento sem
  teardown precoce, main livre para callback, finalização suspensa antes do sucessor
  e caminho sem produtor. O prazo real do latch é apenas proteção contra teste travado.

### Limitações remanescentes

- Se o app externo nunca devolver o resultado, o slot permanece reservado por segurança;
  não há reutilização automática por timeout/cancelamento. Morte do processo não retém
  esse ViewModel; restauração de resultado/launchers precisa de validação Android real.
- Os testes novos verificam helpers e o orquestrador real com portas falsas, não executam
  `CameraViewModel`, ActivityResultRegistry, DAT, codecs ou muxer reais. Ainda é necessário
  ensaiar rotação, retorno externo tardio e parar/reabrir durante frame e gravação no aparelho.
- Se código síncrono do SDK/codec ficar bloqueado indefinidamente, a drenagem também
  espera: não libera recursos sob um produtor ainda vivo. A abertura seguinte pode
  atingir seu prazo técnico, sem reutilizar esses recursos antes da limpeza.
- O lifecycle interno do MediaPipe/ImageReader não foi redesenhado: `stop` ainda usa
  `quitSafely` e `dispose` ainda tem o join limitado já existente. A barreira nova cobre
  o produtor de frames comprimidos; não é prova de eliminação de todo callback tardio
  de inferência. O ownership do serviço entre ViewModels distintos é tratado na revisão
  final abaixo, mas ainda depende de validação Android real.

## Revisão final — launcher atual e serviço com dono

Os dois achados foram confirmados **por leitura**, inclusive do `StreamingService`
inteiro: a ponte recebia uma lambda com launcher da Activity antiga antes de esperar
o mutex; e a drenagem assíncrona de `onCleared` chamava um STOP global depois que outro
VM já poderia ter iniciado o serviço. Correções aplicadas somente neste worktree,
preservando as alterações anteriores. **Build, testes e commit não executados.**

### Launcher associado ao lifecycle

- `solicitar` recebe somente a entrada tipada. A ponte retida resolve o launcher atual
  **depois** de adquirir o mutex, sem carregar callback da Activity antiga na fila.
- `onStart` associa os launchers; `onStop`/`onDestroy` dissociam com identidade de dono.
  Uma dissociação tardia da Activity antiga não remove a associação da nova.
- Sem Activity ativa, o pedido aguarda associação, com cancelamento cooperativo. Mesmo
  após ser acordado, relê a associação antes de lançar, pois ela pode já ter parado.
  Leitura e lançamento não suspendem entre si e continuam confinados à main.
- Dissociar não cancela nem transfere o pedido externo em voo. O resultado A continua
  pertencendo exclusivamente ao deferred A, mesmo se A foi cancelado ou se o registry
  entregar pela Activity recriada. Só então B pode lançar usando o launcher atual.
  Resultados câmera/áudio continuam com destinos distintos e mutex compartilhado.
- Regressões adicionadas: troca de launcher enquanto B espera o mutex; resultado A
  não resolve B; intervalo sem Activity; associação que para antes de retomar; destroy
  antigo não remove associação nova; cancelar espera sem Activity libera o mutex.

### Serviço por dono e estado desejado, não por ordem de entrega

- Cada `CameraViewModel` tem um UUID próprio usado em start, refresh de microfone e stop,
  inclusive no teardown assíncrono. STOP de outro dono ou repetido não envia Intent nem
  cria um serviço apenas para encerrá-lo. Refresh continua podendo iniciar a proteção
  de áudio sem stream, como antes, agora com o dono do VM correspondente.
- `ControleDonoStreaming` mantém o último estado desejado no processo, fora da instância
  do Service. Cada publicação tem revisão monotônica e ownership é atualizado antes
  do envio; uma rejeição síncrona do envio restaura o estado anterior sem recuar a revisão.
- `onStartCommand` não aplica cegamente START/STOP do Intent: reconcilia com o dono/estado
  atual. START A tardio depois de STOP A não ressuscita A; STOP A já enfileirado depois
  de START B não encerra B; restart do mesmo VM também não aceita STOP de revisão anterior.
- `startForeground` continua sendo chamado primeiro, inclusive para STOP/intents antigos,
  para satisfazer a promessa de `startForegroundService`. Sem dono ativo, libera wake lock
  e usa `stopSelfResult(startId)`; só remove foreground se não houver startId posterior
  pendente no sistema. A falha ao entrar em foreground também usa parada por startId.
- Retorna `START_NOT_STICKY`: após morte do processo não existe VM/stream a restaurar.
  Intent antigo ou nulo não recebe ownership por si só e não mantém serviço órfão.
  Reinício automático da sessão DAT não foi implementado; novo VM exige consentimento.
- Testes do helper adicionados para teardown A após start B, STOP A já enviado, START
  atrasado após STOP, revisão anterior no mesmo dono, STOP duplicado/sem dono, refresh
  de áudio, processo novo/Intent nulo e rejeição síncrona de envio. **Não executados.**

### Ressalvas e validação pendente

- Helpers puros não exercitam ActivityResultRegistry, restrições de foreground service,
  ordenação real de startIds, recriação de Service, notificações nem wake locks Android.
  `@MainThread` documenta o confinamento do serviço; não é sincronização para futuros
  callers em background. O serviço continua no mesmo processo do app.
- Ensaio pendente: lançar A, cancelar solicitante, enfileirar B, rotacionar e só então
  devolver A; repetir com intervalo sem Activity ativa. B deve usar o launcher novo e
  só concluir com seu próprio resultado. Também sair/reabrir durante drenagem de vídeo:
  o teardown A não deve remover serviço/notificação/wake lock de B.
- Ainda ensaiar STOP imediatamente após START, stop/start rápido do mesmo VM, refresh de
  áudio sem câmera e morte do processo. Sem retorno do app externo, o slot continua
  reservado por segurança; recuperação após morte do processo não é garantida pela ponte.
- As restrições/permissões Android para FGS continuam valendo; ownership não garante
  que o sistema aceite start/refresh/stop em background, nem recupera uma falha de
  `startForeground`. Não há alegação de correção validada em aparelho ou por compilação.
- Fallback de Corrigir, confirmação automática por timeout, frase falada, motores de
  voz e texto placeholder de consentimento foram preservados. As ressalvas de produto
  e acessibilidade anteriores continuam abertas.
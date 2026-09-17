# Item "tela completa e ciclo de sessões" — validado; RECUSADO validado; acesso ao modelo real investigado

**16/09/2026 — cartão de diagnóstico e limiar conferidos na tela de câmera real
(SIMULADO e RECUSADO), com alternância de turno/sessão e com fechar/reabrir o app.
Sem o classificador real: só o smoke de pesos aleatórios existe nesta máquina.
Ver "Onde paramos" abaixo. Não é aprovação do modelo e não testa hardware.**

Fecha a pendência anotada no fim de
[integração: vídeo e infraestrutura visual](integracao-video-infraestrutura-2026-09-15.md):
"validar o diagnóstico ligado à tela completa e o ciclo de sessões". Continuação do
[plano](integracao-modelo-app-plano-2026-09-15.md) e da
[validação inicial](integracao-modelo-app-validacao-2026-09-15.md).

Branch `feat/integracao-modelo-app`, diretório `mobile-app-companion` (sem worktree
separado desta vez: a integração anterior já havia sido trazida para cá).
Sem treino, ajuste de limiar de entrega, seleção do modelo final, push ou distribuição.

## Onde paramos

**Feito nesta sessão** (detalhes nas seções abaixo):

- Cartão de diagnóstico e limiar validados na tela de câmera real (não isolados),
  com alternância de turno e de sessão.
- Fechar/reabrir o app de verdade (`ActivityScenario`, não `recreate()`) confirmado:
  modo recomputado do zero e limiar persistido pelo `SharedPreferences`.
- Descoberto que o passo 1 do protocolo (assets obrigatórios, `verificarAssets`
  sem bypass) já estava fechado nesta máquina, sem eu perceber nas sessões
  anteriores.
- Regressão completa (`connectedDebugAndroidTest` sem filtro): 49 testes, 1 falha
  não reproduzível isolada (flake pré-existente, não deste projeto), 7 pulados
  esperados.
- Caminho RECUSADO (hash bate, `.tflite` inválido) validado na tela real pela
  primeira vez, com um pacote privado fabricado só para isso — sem depender de
  nenhum checkpoint real. No processo, corrigi um entendimento errado meu sobre
  `bloqueiaIniciar` (não trava "iniciar" para sempre; libera com ✓ **ou** ✗ — a
  proteção contra glosa fabricada está no tratamento de erro por segmento, não
  numa trava de sessão).

**Por que paramos aqui:** o classificador de sinais (visão) que existe neste
repositório é só o `smoke_sinal_classifier.tflite` — mesmo contrato do modelo
real, **pesos aleatórios**, não reconhece sinal nenhum. O `.tflite` treinado de
verdade (baseline `final-s20260917-v1`) e os vídeos MINDS para teste só existem
na máquina do Walisson / num notebook Kaggle privado (`walissonfagundes/...`).
Sem eles, bloqueados:

- Vídeo controlado com sinais reais em modo REAL_EXPERIMENTAL, e as métricas
  por estágio dessa rodada.
- Persistência de identidade/hash do modo real através de fechar/reabrir.
- Qualquer sessão em celular/óculos físicos.
- Avaliação de qualidade linguística/generalização e calibração — que, mesmo com
  o modelo em mãos, são avaliações à parte, feitas no lado de treino, não pela
  integração do app.

**Próximo passo concreto, de quem não sou eu:** Walisson adicionar sua conta
Kaggle como colaboradora no kernel privado (`walissonfagundes/teste-epocas-30`
ou o mais recente da etapa 3), ou copiar o checkpoint `.pt` + alguns vídeos MINDS
brutos para `experimentos-privados/` nesta máquina. Qualquer um dos dois destrava
o resto do protocolo (vídeo real → hardware → relatório).

Nada foi commitado. `git status`: dois arquivos de doc modificados
(`docs/README.md`, `docs/integracao-video-infraestrutura-2026-09-15.md`), este
arquivo novo e três testes novos em `mobile-app-companion/app/src/androidTest/`.

## Lacuna de ambiente encontrada

O pacote privado do baseline (`app-baseline-v1`, hashes documentados na validação
inicial) e o SDK usado nas rodadas de 15/09 ficavam em `/home/walisson/...`, outra
máquina. Não estão disponíveis aqui. Por isso esta rodada usa o **build padrão**
(sem `librasLivre.classificadorPrivado`): o classificador carregado é o SIMULADO
(placeholder), não o baseline real. Isso é aceitável para o que estava pendente —
"tela completa e ciclo de sessões" é sobre o cartão/estado persistirem corretamente
na navegação real, não sobre acurácia do modelo — mas a persistência de
**identidade/hash do modo REAL_EXPERIMENTAL** através de um fechar/reabrir segue
pendente até o pacote privado estar acessível neste ambiente.

## O que foi implementado

Dois testes instrumentados novos em
`mobile-app-companion/app/src/androidTest/.../libras/reconhecimento/`:

- [`DiagnosticoClassificadorTelaCompletaTest`](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/DiagnosticoClassificadorTelaCompletaTest.kt):
  pareia óculos simulados, inicia sessão, espera aquecimento e confirma o cartão de
  diagnóstico (`CartaoClassificador`) na tela de câmera real — não mais isolado como
  em `DiagnosticoClassificadorUiTest`. Abre o diálogo de identidade a partir do cartão
  de verdade. Muda o limiar pelo menu "Configurações de demo" real (não um mock) e
  confere que o cartão reflete o novo valor. Alterna turno (iniciar → capturando →
  cancelar → aguardando sinal) duas vezes e depois alterna sessão (encerrar e
  iniciar de novo), conferindo que modo e limiar seguem estáveis em cada etapa.
- [`DiagnosticoClassificadorReaberturaTest`](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/DiagnosticoClassificadorReaberturaTest.kt):
  usa `ActivityScenario.launch(...).use { }` duas vezes — fechar de verdade
  (`isFinishing=true`, `ViewModelStore` limpo), não `recreate()` de mudança de
  configuração, que preservaria o `CameraViewModel` e não provaria nada. O
  pareamento (`MockDeviceKit`, singleton por processo) sobrevive ao fechar/reabrir,
  como óculos já pareados sobreviveriam de verdade. No primeiro lançamento, muda o
  limiar para 0.83 pelo menu de demo; fecha o app; no segundo lançamento, confirma
  que o cartão mostra modo SIMULADO (recomputado do zero, mesmo resultado) e limiar
  0.83 (não recomputado — vem do `SharedPreferences` gravado antes de fechar).

Ambos os testes resetam as configurações de demo ao padrão pelo próprio botão
"voltar ao padrão" da tela antes de assumir o valor 0.6 como ponto de partida:
sem isso, o limiar gravado por uma execução anterior (destes testes ou de qualquer
outro que grave no mesmo `SharedPreferences` do app) vazaria entre execuções.

### Decisão de não fechar o menu de debug durante os testes

A folha modal "Configurações de demo" (`ModalBottomSheet` com
`skipPartiallyExpanded = true`) não foi fechada entre os passos. Duas tentativas
falharam antes desta decisão:

1. Botão voltar do sistema (`onBackPressedDispatcher.onBackPressed()` e, depois,
   `UiDevice.pressBack()`): nesta versão do Material3, a folha não intercepta o
   voltar — a primeira tentativa **encerrou a Activity** (nenhum callback
   registrado), a segunda (via `UiDevice`, evento bruto) simplesmente não fechou
   nada dentro do timeout.
2. Toque no scrim fora da folha, mirando por cima do cartão de diagnóstico (que
   fica no topo da tela): também não fechou — `skipPartiallyExpanded = true` deixa
   a folha praticamente em tela cheia, sem área de scrim confiável para tocar.

Como as consultas do Compose (`onNodeWithTag`/`onNodeWithText`) miram a árvore de
semântica de cada nó diretamente — não a pilha de janelas do Android —, elas
continuam enxergando e interagindo com o cartão de diagnóstico e os botões da tela
de câmera por baixo da folha aberta. Os testes passaram a confiar nisso em vez de
insistir em fechar a folha. Isso testa a integração de estado (ViewModel ↔ tela ↔
`SharedPreferences`) corretamente; não é uma validação de que um usuário real
consegue fechar esse menu de debug — esse menu só existe em build debug, para
quem opera a demo, e fechar/abrir a folha em si não fazia parte da pendência.

## Evidência da execução

Emulador `Pixel_7` (AVD local, API 33, x86_64, sem GPU/áudio de verdade — sem os
mesmos módulos MediaPipe/áudio baixados na rodada de 15/09; build padrão não exige).

- `:app:connectedDebugAndroidTest` restrito às duas classes novas: **2 testes,
  2 aprovados, zero falhas/erros**, 22.671 s no total.
- Regressão: `:app:testDebugUnitTest` completo — **178 testes, 177 aprovados,
  1 ignorado, zero falhas/erros** (mesmo número da rodada de 15/09; nenhuma
  mudança em código de produção nesta rodada).
- Regressão instrumentada direcionada: `DiagnosticoClassificadorUiTest`,
  `LandmarkPipelineTurnosTest`, `ClassificadorSmokeTest` (4 casos) — **6 testes,
  6 aprovados, zero falhas/erros**.

Nenhum arquivo de produção foi alterado nesta rodada — só os dois testes novos.
`git status` mostra apenas os dois arquivos novos como não rastreados.

## Limites e próxima pendência

- Só o classificador SIMULADO foi exercitado. A persistência de modo/identidade/hash
  do REAL_EXPERIMENTAL através de fechar/reabrir o app segue **não testada**: exige
  o pacote privado (`librasLivre.classificadorPrivado`) disponível neste ambiente,
  ou repetir esta rodada na máquina/worktree onde ele existe.
- Sem MediaPipe/áudio/VLibras baixados nesta rodada (o build padrão não exige); o
  item 1 (vídeo controlado) segue com sua própria pendência de assets auxiliares,
  descrita em [integração: vídeo e infraestrutura visual](integracao-video-infraestrutura-2026-09-15.md).
- Nenhuma sessão em celular/óculos físicos. Protocolo de continuidade (assets
  auxiliares → mídia curada → vídeo controlado real → tela completa com modelo real
  → hardware → relatório) segue como descrito na
  [validação inicial](integracao-modelo-app-validacao-2026-09-15.md), passos 1–7.

## Passo 1 do protocolo: já fechado, sem saber

`mobile-app-companion/app/src/main/assets/` nesta máquina já tinha os onze
assets obrigatórios (MediaPipe, TTS Piper, Vosk, wake word, VLibras, modelo de
contextualização) antes desta sessão — provavelmente de `download-assets.sh`
rodado em preparo anterior. `./gradlew :app:verificarAssets`, executado sem
`-PlibrasLivre.permitirAssetsFaltando`, roda (não fica em cache) e passa. Os
builds da sessão anterior (tela completa/reabertura) já tinham essa verificação
ativa sem eu perceber — nenhum deles usou a flag de bypass. Hash do APK debug
sem opt-in privado: `23939ed401fa4fb29b6fa46cc113c90403c5d60f7e663f4073821bf53bec9f28`
(igual em ambas as sessões: nada nos assets mudou entre elas).

## Regressão completa com os assets reais

`:app:connectedDebugAndroidTest` sem filtro de classe: **49 testes, 42 no
relatório principal (1 falha, 7 pulados), 0 erros**. A falha
(`InstrumentationTest.endSessionAvailableWhileStreaming`, teste do app-base da
Meta, não deste projeto) não se repetiu isolada — típica de disputa de recursos
do emulador depois de quase quatro minutos de suíte com vários aquecimentos de
180 s em sequência, não uma regressão. Os sete pulados são esperados: três do
`ClassificadorPrivadoAppTest` (exigem build privado, `assumeTrue` de guarda),
dois do `VideoClassificadorPrivadoTest` (exigem opt-in
`videoClassificadorPrivado=true`), um do `FluxoOnda4Test` (`assumeTrue` de WebGL — este emulador não carrega o
avatar) e um do `FluxoCompletoTest`. Nenhum arquivo de produção mudou nesta
sessão.

## Investigação: dá pra montar o acesso privado nesta máquina?

Pergunta do usuário: reproduzir aqui (não na máquina do Walisson) o que gera o
pacote privado (`app-baseline-v1`) e os clipes MINDS para teste. Busquei por:

- Checkpoint treinado (`*.pt`) em qualquer lugar do repositório: **nenhum**.
- `experimentos-privados/` (destino padrão dos artefatos privados, citado em
  vários scripts): **não existe** nesta máquina.
- Vídeos brutos da MINDS-Libras (`computer-vision-model/data/raw/`, path do
  `config.yaml`): **vazio**, só `.gitkeep`.
- Credenciais/CLI do Kaggle (`~/.kaggle/kaggle.json`, `kaggle` no PATH):
  **ausentes**.

As referências a "Kaggle" em `docs/protocolo-treinamento.md`,
`docs/protocolo-pretreino.md`, `docs/teste-epocas-30-protocolo-2026-09-15.md`
e nos commits recentes ("aceitar extras já extraídos pelo Kaggle na etapa 3",
"reconhecer hierarquia MINDS no Kaggle") indicam que o treino roda num notebook
Kaggle — não localmente em nenhuma das duas máquinas de desenvolvimento. O
pacote privado e os vídeos MINDS provavelmente chegaram à máquina do Walisson
por download manual desse notebook, não por um processo reproduzível aqui.

`scripts/download_libras_gap_videos.py` (o "script pronto" mais próximo de
"baixar clipes") é um fluxo **diferente e não usa MINDS**: só baixa mídia de
um catálogo com atestado de licença por item, e esse catálogo está vazio de
propósito (nenhuma fonte nova foi aprovada ainda). Não serve para obter
clipes MINDS.

**Conclusão:** não consegui montar o acesso privado (nem checkpoint, nem
vídeos MINDS, nem export do classificador) só com o que existe nesta máquina —
falta a ponte com o Kaggle ou uma cópia dos artefatos. Meios de destravar,
qualquer um resolve os dois pendências de uma vez (export do classificador
privado + clipes reais para teste):
1. Credenciais Kaggle (`kaggle.json`) para eu baixar checkpoint/vídeos direto
   do notebook/dataset.
2. Cópia do checkpoint (`.pt`) e de alguns vídeos MINDS brutos para esta
   máquina (local combinado, ex. `experimentos-privados/`).

## O que dava para avançar sem isso: o caminho RECUSADO na tela real

Sem checkpoint nem clipes reais, sobrava um pedaço nunca exercitado na tela de
câmera de verdade: RECUSADO. `DiagnosticoClassificadorUiTest` só testa esse
modo com um `DiagnosticoClassificador` montado à mão; `CarregadorClassificadorTest`
testa a política pura, sem tela. O caminho completo — pacote privado que passa
nas checagens de hash do Gradle e do app, mas cujo `.tflite` não é um flatbuffer
válido — nunca tinha rodado ponta a ponta.

Gerei um pacote privado **inválido de propósito**, sem nenhum checkpoint real:
bytes aleatórios no lugar do `.tflite`, com sidecar e identidade cujos hashes
batem entre si (então passam nas checagens do Gradle e da política de
carregamento). Script Python gerado nesta sessão fora do repositório (não
salvo); pacote resultante fora do Git em
`experimentos-privados/pacote-recusado-teste/` (`.gitignore` da raiz já cobre
`experimentos-privados/`).

Novo teste
[`DiagnosticoClassificadorRecusadoTelaCompletaTest`](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/DiagnosticoClassificadorRecusadoTelaCompletaTest.kt),
rodando com `-PlibrasLivre.classificadorPrivado=.../pacote-recusado-teste`
(build separado do padrão — os dois builds não coexistem no mesmo APK):

- Confirma RECUSADO no cartão de diagnóstico e no diálogo de identidade da tela
  real, com o motivo de verdade produzido pelo TFLite:
  `Modelo de sinais recusado: ByteBuffer is not a valid TensorFlow Lite model flatbuffer`.
- **Achado que corrigiu uma suposição errada minha:** eu esperava que
  `bloqueiaIniciar=true` na etapa do classificador travasse o botão "iniciar"
  para sempre quando ela falha. Rodar na tela real mostrou o oposto — o botão
  libera. Lendo `Aquecimento.kt` depois: é assim de propósito ("o botão
  principal fica habilitado quando as etapas que bloqueiam terminarem — com ✓
  **ou** ✗"). A garantia contra glosa de placeholder não está em travar a
  sessão; está em `LandmarkPipeline.onSegmento`, que trata a exceção de
  `classifier.classify()` como `error`/`onRecognitionFailed()`, nunca como uma
  `Classificacao` fabricada. Esse teste não exercita esse segundo caminho (exige
  captura e segmentação reais, como o `VideoClassificadorPrivadoTest`); só a
  parte de diagnóstico na tela.

`:app:connectedDebugAndroidTest` restrito a essa classe: 1 teste, 1 aprovado.
Depois, rebuild do build padrão (sem `classificadorPrivado`) e reexecução dos
dois testes da sessão anterior — SIMULADO — confirmando que nada ficou preso
na configuração privada.

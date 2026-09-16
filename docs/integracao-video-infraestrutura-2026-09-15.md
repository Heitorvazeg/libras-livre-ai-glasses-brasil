# Item 1 — infraestrutura visual com vídeo controlado

**15/09/2026 — teste por componentes reais executado no emulador; não é aprovação
do modelo, teste de acurácia ou ensaio do aplicativo inteiro.**

Continuação do [plano](integracao-modelo-app-plano-2026-09-15.md) e da
[validação inicial](integracao-modelo-app-validacao-2026-09-15.md).
Worktree `libras-livre-integracao-modelo-app`, branch `feat/integracao-modelo-app`.
Baseline privado mantido como artefato de teste. Sem treino, ajuste de limiar,
seleção do modelo final, commit, push ou distribuição. FILHO permanece encerrado.

## Implementação

Novo [VideoClassificadorPrivadoTest](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/VideoClassificadorPrivadoTest.kt):

- Opt-in `videoClassificadorPrivado=true`; fora dele, o teste é ignorado.
  Quando solicitado, pacote/MediaPipe/mídia ausentes são **falhas**, não skips.
- Modelo carregado de `targetContext.assets` pela mesma fábrica do ViewModel.
  Exige `REAL_EXPERIMENTAL`; nenhuma classificação ou glosa é fabricada.
- Vídeo HEVC, CSD e amostras extraídos por `MediaExtractor`; alimentação da
  `LandmarkPipeline` via `feedCompressedFrame`, com cadência dos PTS originais.
- Decoder → ImageReader/YUV → ARGB → MediaPipe → normalização → segmentação →
  imputação → classificador real. Isso demonstra passagem pelo imputador, não
  demonstra preenchimento de uma lacuna específica nessa mídia.
- Parâmetros novos **padrão**, sem alterar preferências persistidas do app:
  segmentação entrada 0.7/saída 0.4, demais valores de `ParametrosSegmentacao`;
  confiança 0.60, temperatura do classificador 1.
- Encerramento manual aguarda classificações; `AvaliadorDeFrase` recebe os
  resultados reais e o léxico do alvo. Sua decisão é conferida contra confiança
  e léxico. Somente `Falar` chama `criarGlossContextualizer` com a política padrão.
  O contextualizador neural continua desligado; o template é o componente real
  ativo do app, não um mock introduzido pelo teste.
- Saída sem landmarks/mídia: UUID, estado, hashes, envios tentados/concluídos,
  extrações, pose/mãos, eventos, classificações, decisão e eventual texto.
  Relatórios do dispositivo ficam em `files/video-classificador-privado/`.
  Gradle pode desinstalar o alvo após a execução; logcat deve ser preservado.

Mudanças pequenas de observabilidade em
[HevcDecoder](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/HevcDecoder.kt)
e [LandmarkPipeline](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/LandmarkPipeline.kt):
callback opcional de falha do codec encaminhado como `falha_decoder`, e evento
`falha_extracao`. O teste reprova esses eventos inclusive na limpeza, em vez de
confundir algumas extrações bem-sucedidas com ausência de erros. O preview mantém
o callback vazio padrão. Não se alteraram algoritmos ou parâmetros de inferência.

## Assets e ambiente

Baixados somente os dois modelos MediaPipe, de URLs com versão **1**, nos assets
ignorados pelo Git deste worktree. Não foi executado o download geral de áudio/avatar.

| Asset | SHA-256 |
|---|---|
| Pose lite | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |
| Mãos | `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1` |

Fontes:
- https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
- https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task

AVD `libras36`, API 36, dois núcleos, 2 GB, SwiftShader. Biblioteca MediaPipe
x86_64 presente no APK **e executada**, não apenas presumida por inspeção.
Teste existente de extração em três turnos passou sem skip; seu comentário antigo
que excluía x86_64 foi corrigido.

APK principal desta rodada: SHA-256
`83d8cb289e7c9d0017b40b38164c142d7bfc56f25988417caaefbb66bc6db19e`.
Difere do APK da rodada anterior por assets de visão e observabilidade.
Identidade do pacote segue
`8f6ce7ae2539bc1e123f85ba197e4f45b2b0721cae7964e32e997a4da4897b63`;
modelo segue `616d1e1c91d4d081f39e25143c9781c18ee19f460ef7023a6b03e3eb0393da4c`.

## Evidência da execução final

XML Android: timestamp `2026-09-15T23:54:50`, **4 testes aprovados, zero skips,
falhas ou erros**, duração total 43.423 s. Inclui dois testes novos de vídeo,
regressão existente de três turnos e cartão de diagnóstico. Não confundir com
os quatro testes de paridade/diagnóstico da rodada anterior.

JVM reexecutada após as mudanças de produção: **178 testes, 177 aprovados,
1 ignorado, zero falhas/erros**. O ignorado continua sendo a guarda que procura
sidecar nos assets convencionais de fontes, não no diretório debug gerado.

| Medida | Movimento sintético | Controle sem pessoa |
|---|---:|---:|
| Amostras HEVC enviadas | 387 | 266 |
| Frames extraídos | 185 | 190 |
| Frames com pose normalizável | 185 | 32 |
| Frames com alguma mão | 185 | 0 |
| Classificações reais | 3 | 4 |
| Falhas técnicas/classificação | 0 / 0 | 0 / 0 |
| Decisão da frase | Falar | PedirRepeticao |
| Contextualização executada | Sim, TEMPLATE | Não |

Movimento: UUID `c86409eb-37e7-41bf-b6d1-e70b21716ec3`; fixture SHA-256
`c5611ea8b0902d68ae470eb5d5121ce0d032e934341de1ade23e5a605424f505`.
Três previsões `america`, confiança aproximadamente 0.9905; texto produzido
“A américa a américa a américa.”. **O vídeo é uma deformação sintética de imagem,
não três sinais de “América”. O resultado só comprova transporte até o template.**

Controle: UUID `b5a455b0-0e8c-475f-b4da-0a577ec03f9a`; fixture SHA-256
`6f4ce9eee5d69b2ea9ca308e582d545c3f603320f6c34a582c9e0d54f0c8762a`.
Previsões `aproveitar` (0.2502), `espelho` (0.9917), `aproveitar` (0.2010) e
`espelho` (0.3710). A menor confiança da frase aciona rejeição. **Uma previsão
falsa foi de alta confiança: este resultado NÃO comprova rejeição geral de OOV.**
Não houve emissão de texto pelo teste, mas também não foram exercitados TTS/UI.

### Primeira tentativa e correção do critério

A primeira versão exigia zero poses/classificações no controle. Falhou com 18
poses falsas e uma classificação, rejeitada pelo avaliador. Era uma exigência
de detector perfeito, não um contrato de infraestrutura. O teste passou a medir
falsos positivos e exigir bloqueio da frase **nesta fixture**, sem alterar pesos
ou limiares. A ocorrência não foi apagada nem tratada como ausência de inferência.
A versão revisada e a execução final passaram, com contagens diferentes devido
à subamostragem/temporização. Não se exigiu um número fixo de sinais para aprovar.

## Revisão independente e correções

Subagente revisou teste e APIs reais; depois outro passe verificou as correções:
- Falhas anteriormente apenas logadas agora são eventos verificáveis.
- Registro inicial com UUID/status impede interpretar um sucesso antigo como atual;
  pré-condições falhas registram `FALHOU`. Estado `ENCERRANDO` não significa sucesso.
- Contagem separa amostras da fixture, tentativas de envio e envios concluídos.
- Exceções de cleanup/persistência não impedem as demais tentativas de liberação
  nem substituem a exceção primária quando esta existe.
- Decisão conferida contra confiança/léxico, sem asserção tautológica.
- Hook opcional preserva uso de `HevcDecoder()` pelo preview.

As falhas de disco cheio e travamento nativo foram examinadas estaticamente,
não reproduzidas. Nenhuma revisão estática foi usada como prova de compilação:
Gradle e instrumentação foram executados depois das correções.

## Reprodução e limites do item 1

Usar o wrapper e `-p` com caminhos **absolutos do worktree isolado**, SDK local,
`--max-workers=2`, `ANDROID_SERIAL=emulator-5554` e tarefa
`:app:connectedDebugAndroidTest`. Propriedades:

- `librasLivre.classificadorPrivado`: diretório privado `app-baseline-v1`.
- `librasLivre.permitirAssetsFaltando=true`: exceção explícita para este teste
  por componentes; áudio/wake word/avatar continuam ausentes. Guarda normal de
  assets do build não foi removida nem enfraquecida.
- `android.testInstrumentationRunnerArguments.videoClassificadorPrivado=true`.
- `android.testInstrumentationRunnerArguments.class`: pacote
  `com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento`,
  classes `VideoClassificadorPrivadoTest`, `LandmarkPipelineTurnosTest` e
  `DiagnosticoClassificadorUiTest`.
- **Não usar `classificadorFixtures` nesta execução**: essa opção substitui os
  assets androidTest e retiraria as mídias padrão. A paridade privada é outra rodada.

Logs da execução: `/tmp/libras-video-final.log` e
`/tmp/libras-video-final-evidencias.log` (este inclui também eventos de rodadas
anteriores no buffer; usar os UUIDs finais acima). Relatórios Gradle são locais
e sobrescritos por execuções posteriores.

**Item 1 validado no nível dos componentes reais, com as duas mídias existentes.**
Não valida captura DAT/MockDeviceKit, UI/ViewModel/Orchestrator completos,
preferências persistidas, TTS/STT/avatar, celular/óculos, condições reais de
sinalização, generalização ou o candidato final de modelo. A cadeia foi montada
no teste com os componentes usados pelo app; não é prova de seu wiring na UI.

O decoder não expõe EOS/idle: há janela de quiescência limitada, não garantia de
processamento de todos os frames. A pipeline usa relógio real e descarta frames
intermediários; contagens e classificações podem variar com carga. `close()` nativo
não é interrompível pelo timeout de coroutine. Não há benchmark ARM nem garantia
de ausência de vazamento em sessões longas. A próxima pendência é validar o
diagnóstico ligado à tela completa e o ciclo de sessões, sem depender do modelo final.
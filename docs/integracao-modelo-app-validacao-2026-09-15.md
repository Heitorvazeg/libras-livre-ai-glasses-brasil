# Integração privada: evidências e limites

**15/09/2026 — baseline experimental, não aprovado para entrega.**

**Atualização posterior:** a ausência de MediaPipe descrita nesta fotografia foi
resolvida e vídeo foi validado por componentes no emulador. Ver
[relatório do item 1](integracao-video-infraestrutura-2026-09-15.md), com novo hash
do APK. Os resultados e pendências abaixo descrevem a rodada inicial.

Complementa o [plano de integração](integracao-modelo-app-plano-2026-09-15.md).
Implementação no worktree isolado `libras-livre-integracao-modelo-app`, branch
`feat/integracao-modelo-app`, base `d65a05d5aad3df6b5b2661816a7fc6ace2bc1d31`.
Alterações ainda não commitadas. Sem push, publicação, treino ou ajuste de limiar.
FILHO permanece encerrado; o candidato de treino não foi selecionado nesta frente.

## 1. Resultado por etapa

| Etapa | Evidência observada | Limite |
|---|---|---|
| Pacote | 5 testes Python aprovados; modelo e sidecar preservados byte a byte | Cabeçalho TFL3 não prova validade do grafo; execução Android testa o baseline específico |
| Build privado | 1 teste Gradle com múltiplos cenários aprovado; APK inspecionado e toggle completo executado | Bloqueio de release testado por recusa do grafo, não por geração de release |
| Carregamento | 11 testes JVM do carregador aprovados; fábrica real usada no APK alvo | Não constitui avaliação de reconhecimento |
| Diagnóstico | 3 testes JVM e 1 teste Compose Android aprovados | Teste visual exercita o cartão isolado, não navega pela tela de câmera completa |
| Android real | 3 testes do classificador aprovados, sem skips | Entradas de landmarks sintéticas; não há extração de vídeo nesse teste |
| Vídeo/celular/óculos | Não executado nesta validação | Assets auxiliares ausentes; nenhum dispositivo físico conectado |

### Suíte JVM

`testDebugUnitTest`: **178 testes, 177 aprovados, 1 ignorado, zero falhas/erros**.
O ignorado em `ValidacaoClassificadorTest` exige o sidecar nos assets convencionais;
não foi adaptado para confundir assets de fontes com o pacote debug gerado.
O teste Android de identidade supre a verificação do modelo efetivamente empacotado.

### Suíte instrumentada final

AVD `libras36`, Android 16/API 36, emulador limitado a dois núcleos e 2 GB,
renderização SwiftShader. Selecionadas somente as duas classes novas:

- `ClassificadorPrivadoAppTest.pacoteDoAlvoTemIdentidadeFixadaELabelsConhecidas`:
  usa `targetContext.assets` e a mesma `FabricaClassificadorApp` do ViewModel;
  exige modo real, verifica identidade, 20 labels e aquecimento. A exceção lexical
  explícita de `maca` permanece; o teste não certifica cobertura linguística total.
- `caminhoDeLandmarksNoAlvoReproduzReferenciaPrivada`: três sequências privadas,
  normalização, imputação e reamostragem do app; compara cada logit contra
  `logits_app_pytorch` com tolerância absoluta `2e-3`. Referência vinculada aos
  hashes de modelo e checkpoint. Pesos vêm do APK principal, não da fixture.
- `inferenciasConcorrentesEEncerramentoSaoSerializados`: resultados iguais entre
  chamadas concorrentes, fechamento idempotente e erro explícito após fechamento.
  O agendamento não garante sobreposição determinística com `close()`.
- `DiagnosticoClassificadorUiTest.modoLimiarEIdentidadeSaoVisiveisSemMetricas`:
  real/simulado/recusado, mudança de limiar e abertura do diálogo de identidade.
  A existência do hash no diálogo foi verificada; não se realizou inspeção manual
  de todos os tamanhos de tela ou do percurso configuração → ViewModel → câmera.

Resultado final: **4 testes, 4 aprovados, zero ignorados/falhas/erros**.
Relatório XML local: timestamp `2026-09-15T23:17:22`, duração da suíte `4.842 s`.
Esse tempo inclui testes e infraestrutura; **não é benchmark de inferência**.

## 2. Identidade do artefato final

APK principal privado:
[app-debug.apk](../mobile-app-companion/app/build/outputs/apk/debug/app-debug.apk).
O hash abaixo identifica a reconstrução final após o teste de toggle; não o APK
anterior. Os quatro testes Android foram repetidos nessa reconstrução.

| Artefato | SHA-256 |
|---|---|
| APK debug final | `615b3585b94c59200e891b236bb2c2e1642985f2af1e6594e51a7c7037558e1c` |
| Modelo | `616d1e1c91d4d081f39e25143c9781c18ee19f460ef7023a6b03e3eb0393da4c` |
| Sidecar | `392228a9302712992f8d6b970305e7f3701b0c739924d9f6db1c2b107454b4ed` |
| Identidade | `8f6ce7ae2539bc1e123f85ba197e4f45b2b0721cae7964e32e997a4da4897b63` |
| Checkpoint de origem | `c7851d8ae9dd46b9aaf222ada2e02f158d69938cfd906aafda4d24779e2ef610` |

Experimento `final-s20260917-v1`; contrato float32 `[1,96,57,3]`; 20 classes;
sem bloco de calibração, temperatura 1. Limiar manual existente preservado.

Inspeção do ZIP confirmou exatamente os três arquivos previstos do classificador,
iguais ao pacote privado. Não há checkpoint `.pt/.pth`, vídeo `.mp4`, arrays
`.npy/.npz` ou fixture de paridade no APK principal. Outros assets normais do app
permanecem presentes. A fixture privada pertence somente ao APK de instrumentação,
que também não deve ser distribuído.

Foi montado um APK **sem opt-in**, conferindo ausência dos três arquivos,
`CLASSIFICADOR_PRIVADO_OBRIGATORIO=false` e hash vazio no `BuildConfig` gerado.
Depois foi restaurado o build privado e reconferido o conteúdo. O teste Android
final verifica o hash fixado no build em execução, além da inspeção estática.
O opt-in não é uma política de distribuição nem um certificado de aprovação.

## 3. Reprodução e rastros locais

Executar no projeto Android do worktree de integração, usando caminhos absolutos
para o wrapper Gradle e seu argumento `-p`. Não depender do diretório inicial de
terminais em segundo plano, que podem iniciar no worktree original.

- SDK local: `/home/walisson/android-sdk`; Gradle com `--max-workers=2`.
- Pacote: diretório privado `app-baseline-v1` neste worktree.
- Referências: diretório privado `paridade-real-final-s20260917-v1` no worktree
  original, consumido somente para leitura.
- Propriedades: `librasLivre.classificadorPrivado`,
  `librasLivre.classificadorFixtures` e, **somente nesta validação focada**,
  `librasLivre.permitirAssetsFaltando=true`.
- Tarefas: `:app:testDebugUnitTest`, `:app:assembleDebug`,
  `:app:assembleDebugAndroidTest`, `:app:connectedDebugAndroidTest`.
- Instrumentação: `ANDROID_SERIAL=emulator-5554`; argumento `class` limitado a
  `ClassificadorPrivadoAppTest` e `DiagnosticoClassificadorUiTest`, no pacote
  `com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento`.
- Python selecionado 3.14.6: discovery dos dois scripts de teste; regressão Gradle
  requer `LIBRAS_TESTAR_BUILD_PRIVADO=1`. Últimas execuções: 5 testes em 0.006 s
  e 1 teste em 5.842 s. Nenhum pacote Python adicional instalado.

Relatórios gerados, locais e descartáveis:
[JVM](../mobile-app-companion/app/build/reports/tests/testDebugUnitTest/index.html)
e [Android](../mobile-app-companion/app/build/reports/androidTests/connected/debug/index.html).
Logs temporários da sessão: `/tmp/libras-integracao-build.log`,
`/tmp/libras-integracao-toggle.log`, `/tmp/libras-integracao-restauracao.log` e
`/tmp/libras-integracao-android-final.log`. Novos builds podem substituir resultados.

Uma primeira tentativa instrumentada usou por engano o build do worktree original
e falhou ao localizar as classes novas. Foi descartada como evidência da integração
e corrigida com wrapper/projeto absolutos; os resultados acima são do worktree
correto. Isso gerou saídas de build no original, mas não alterou fontes do treino.

## 4. Revisões independentes

Dois subagentes realizaram revisões somente leitura do estado atual, sem executar
build/testes nem modificar arquivos:

1. **Pacote e Gradle:** nenhum bug concreto encontrado. Observou lacunas na
   automação: teste Gradle inspeciona diretório gerado, não ZIP/BuildConfig;
   pacote não muta individualmente shape/dtype/ordem dos pontos.
   ZIP e toggle do APK foram verificados separadamente nesta sessão, sem atribuir
   essa cobertura ao teste automatizado existente. Colisões de source sets não
   tiveram reprodução negativa dedicada nesta sessão.
2. **Kotlin/UI/lifecycle:** nenhum bug concreto encontrado. Confirmou ausência
   de fallback no privado, fechamento pela pipeline, lock comum entre inferência
   e fechamento e uso dos assets do alvo. Limites de cobertura visual e de
   concorrência estão explicitados acima.

Revisão estática não substitui execução. As conclusões foram confrontadas com
arquivos atuais e com os relatórios reais; não se usou relato de revisão como
prova de compilação ou aprovação do modelo.

## 5. Próxima etapa: vídeo controlado e hardware

**Bloqueada nesta sessão.** Faltam MediaPipe pose/mãos, voz Piper, Vosk,
melspectrograma/embedding do wake word e runtime VLibras. O APK foi gerado com
exceção explícita e não pode ser descrito como app completo operacional. Não
foram baixados assets grandes nem conectado dispositivo físico nesta frente.

Protocolo de continuidade, sem alterar pesos ou ajustar limiares:

1. Disponibilizar os assets auxiliares pelo procedimento existente e reconstruir
   **sem** `permitirAssetsFaltando`; a verificação obrigatória deve passar. Fixar
   e registrar novo hash do APK, modelo, sidecar, identidade e configurações.
2. Separar mídias autorizadas de teste em local privado: sinais no vocabulário,
   mãos ausentes, transições entre sinais e movimentos fora do vocabulário.
   Definir a seleção antes de executar; não escolher somente acertos depois.
3. Exercitar vídeo controlado pelo caminho de captura/MockDeviceKit do app,
   extração, normalização, imputação, segmentação, classificação, avaliação e
   contextualização atuais. Exigir `REAL_EXPERIMENTAL`, sem glosas injetadas.
   O teste antigo `FluxoCompletoTest` usa roteiro de placeholder e, sem adaptação,
   **não vale como validação de reconhecimento real**.
4. Registrar frames recebidos/extraídos, mãos presentes, segmentos emitidos,
   rejeições e erros por estágio, glosas previstas e textos efetivos. Separar
   falha técnica de previsão incorreta; não exigir frase fixa de roteiro como
   condição de sucesso. Não confundir confiança com acurácia.
5. Reabrir o app e alternar sessão/turno; conferir persistência do modo/identidade
   e limiar efetivo. Avaliar o cartão integrado em tela pequena, não só isolado.
6. Coordenar instalação privada em celular e óculos disponíveis. Repetir sessões
   curtas com aquecimento separado de inferência; medir p50/p95, memória, perda
   de frames e rejeições. Registrar aparelho/ABI e condições de captura. Não
   extrapolar latência do emulador para ARM ou desempenho do modelo no campo.
7. Emitir relatório separado com denominadores de acertos/erros/rejeições e
   limitações da amostra. Sem novo treino, calibração ou aprovação automática.

Aceite técnico dessa próxima etapa: fluxo real observável sem substituição
silenciosa, estágios rastreáveis e ausência de crash/vazamento durante sessões e
encerramento. Aceite linguístico, generalização e rejeição de desconhecidos são
avaliações distintas e continuam pendentes. Nenhuma dessas pendências é resolvida
pelos quatro testes instrumentados registrados aqui.
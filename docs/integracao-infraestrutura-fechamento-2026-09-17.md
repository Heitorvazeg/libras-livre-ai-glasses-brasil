# Infraestrutura de integração — fechamento da rodada de 17/09/2026

> Registro histórico da rodada validada. O estado posterior, com novas pendências
> aprovadas e trabalho parcial salvo para push, está no
> [checkpoint de retomada](integracao-checkpoint-retomada-2026-09-17.md).
> Modelo e evidências sem APKs tiveram publicação pública posteriormente autorizada:
> [escopo e limites](integracao-publicacao-artefatos-2026-09-17.md).

## Escopo e estado

Worktree `libras-livre-integracao-modelo-app`, branch `feat/integracao-modelo-app`,
base `07bc365`. Alterações desta rodada ainda não commitadas nem enviadas.
O worktree de treino não foi modificado por esta execução.

**Objetivo:** transportar, carregar e executar um classificador compatível no app,
encaminhar suas saídas/falhas e encerrar os recursos corretamente. O baseline
`final-s20260917-v1` é somente o artefato experimental de teste, não uma escolha
do modelo final. Não houve treino, calibração ou ajuste de limiar de reconhecimento.

Esta atualização prevalece sobre frases históricas de “não executado” nos documentos
de implementação das etapas 2–5. Elas descrevem a entrega inicial dos subagentes;
os resultados abaixo foram conferidos pelo executor em logs/XML/JSON efetivos.

## Resultados por etapa

| Etapa | Resultado verificado | Limite |
|---|---|---|
| Base | Atualizada para `07bc365`, com consentimento e confirmação | Sem novos commits/push nesta rodada |
| Câmera | Política compartilhada, invalidação de aberturas antigas, permissão/serviço por dono e regressões JVM | DAT/permissões/bateria físicos não ensaiados |
| Atendimento privado | 2 testes Android novamente aprovados no APK final: recusar sem stream e aceitar vídeo até decisão/cancelamento | Teste aceita ramos explicitamente distintos; não comprova confirmação/fala acústica |
| Persistência REAL | Duas invocações de instrumentação, processos distintos, limiar relido antes de alteração, identidade rederivada dos assets | Não liga stream; estímulo de limiar é temporário e restaurado |
| RECUSADO por segmento | Vídeo/decoder/MediaPipe reais com pacote inválido reproduzível; falhas chegam ao orquestrador sem glosas | Modelo negativo não é classificador treinado; não mede rejeição de desconhecidos |
| APK | 8 fases com conteúdo/fontes/BuildConfig e bloqueios esperados aprovados | Fonte Java gerada não é prova de DEX/runtime; este é verificado separadamente na etapa 4 |

### Testes finais

- **JVM:** 236 testes, **235 aprovados e 1 ignorado**, zero falhas/erros. Inclui
  seis testes com threads/latches da conclusão de callbacks do codec. O ignorado
  continua dependendo de sidecar nos assets convencionais, não nos gerados privados.
- **Python:** 40 testes focados aprovados: protocolo da etapa 4, gerador negativo,
  pacote privado e helpers/orquestração da regressão APK (23 destes pertencem à etapa 5).
- **Atendimento Android:** XML de `AtendimentoClassificadorPrivadoTest`, 2 testes,
  zero falhas/erros/skips, timestamp `2026-09-17T15:42:51`, duração `39.702 s`.
  Build terminou com sucesso. Não se executou toda a suíte instrumentada sem filtro
  nesta rodada; não se declara resolvido o timeout histórico da suíte completa.
- **Persistência final:** UUID `1a67873a-76aa-414c-8771-5fe8ac8997d3`, PID `19808`
  seguido de `19970`, starts Android `144880410` e `144887202` ms, limiar temporário
  `0.83` conferido nas duas provas. Fase 2 confirma `cleanup=true`. Não houve
  reinstalação/limpeza de dados entre fases. O valor original foi restaurado.
- **Recusa após drenagem final:** UUID `fdb68206-0200-4bfc-8b59-64bd7429c7d0`,
  53 frames processados, 2 tentativas/2 recusas, **0 glosas, 0 contextualizações**,
  `teardown_pipeline_concluido=true`, `cleanup=true`, status `APROVADO`.
  Somente avisos TTS de repetição/desistência são permitidos pelo teste; nenhuma
  tradução. Não se verificou som acústico nem fidelidade linguística desses avisos.

O ensaio de atendimento aceita decisão de rejeição como evidência desse ramo,
não como confirmação bem-sucedida. Neste fechamento o XML comprova os dois métodos,
mas não se recuperou seu JSON privado por ramo: UTP pode desinstalar e apagar o
sandbox. Não se inferem frases, contagens ou ramo a partir de XML aprovado.
As provas da etapa 4 são devolvidas ao host e arquivadas com os APKs, evitando
depender de recuperar arquivos depois de uma desinstalação UTP.

## Regressão APK — conteúdo, não qualidade do modelo

UUID `6963df8b-3b49-4ab0-8b7e-692ae5d9bb67`, execução de
`2026-09-17T15:06:28.821109+00:00` a `2026-09-17T15:09:06.136355+00:00`.
Status global `APROVADO_CONTEUDO_NAO_RUNTIME`, sem erro original ou de restauração.

1. A selecionado, com bytes/hash exatos e fixtures padrão.
2. B sintético negativo: arquivos e hashes diferentes, sem resíduo de A.
3. Sem pacote: nenhum asset privado e BuildConfig false/hash vazio.
4. A restaurado.
5. Fixtures substituídas somente no APK androidTest; sentinel ausente no principal.
6. Colisão via source set externo recusada pelo motivo esperado.
7. Release com opção privada recusado no grafo (`--dry-run`).
8. Ambos APKs recompostos com A e fontes padrão, verificadas contra o inventário.

Todas as invocações exigiram assets obrigatórios, sem bypass. A/B são apenas
seleções de transporte; B não foi instalado nem executado. Fontes do app e script
Gradle permaneceram iguais durante a regressão. Os snapshots são privados/ignored.

**APK final:** [app-debug.apk](../mobile-app-companion/app/build/outputs/apk/debug/app-debug.apk).
SHA-256 `a28fece5cc1575f96c2f98caea1b5d77d450dbf3db68b97cd8fb841c3acf3142`.
Foi usado no atendimento final e na persistência final. O APK de testes arquivado
na restauração tem SHA-256 `c74571034c4de8d92956a209df2f4225d6ef79ab3182cdda778892abbf0c77be`.
Hashes completos de modelo/sidecar/identidade constam nos relatórios privados;
o modelo selecionado mantém SHA-256
`616d1e1c91d4d081f39e25143c9781c18ee19f460ef7023a6b03e3eb0393da4c`.

## Correções e revisões independentes

- Etapas 2/3 receberam revisão independente e correções de ownership de câmera,
  permissões, serviço, decoder e imagens ainda em uso. Falhas técnicas não foram
  excluídas das asserções para obter aprovação.
- A primeira fase REAL falhou porque não preparava o dispositivo mock antes da
  Activity. Corrigido o setup em cada processo, sem reset de preferências. Essa
  execução permanece `FALHOU`; não foi promovida retroativamente.
- A revisão da etapa 4 encontrou snapshot de preferências apenas em memória no
  teste RECUSADO. Corrigido com snapshot durável e recuperação por UUID dono,
  separada da recuperação REAL. A recuperação após crash foi implementada, mas
  não se provocou crash deliberado para validá-la em Android nesta rodada.
- A revisão também encontrou observadores removidos antes da conclusão de
  `dispose()` assíncrono. Acrescentada barreira no teste, e outra revisão encontrou
  a janela de publicação tardia dos callbacks do codec. A produção agora retém
  decoders aposentados e drena thread/publicações antes de concluir o descarte.
  Revisão final dessa correção não encontrou bug concreto. A barreira suspende,
  não bloqueia a main; callback/native travado pode impedir sua conclusão — não
  existe promessa de latência máxima de driver.
- Etapa 5: corrigida indentação antes dos testes; 23 testes aprovados, revisão
  independente sem achado concreto, seguida da execução Gradle real de oito fases.

Revisores fizeram análise estática: suas respostas não foram tratadas como provas
de execução. Resultados são vinculados às execuções descritas acima.

As revisões foram delegadas a subagentes nesta conversa, não a revisores humanos
nem a branches de review. A revisão documental final pelo subagente `Explore`,
em 17/09, confrontou este relatório com JSON/XML e não encontrou contradições
factuais; apontou a falta de referências persistentes das revisões anteriores.
As respostas dessas revisões permanecem no histórico da conversa, não em um
registro autônomo versionado. O executor também reconferiu diretamente os 236
resultados JVM e os hashes dos dois APKs depois dessa revisão.

## Localização das evidências e reprodução

Ambiente: Linux, SDK `/home/walisson/android-sdk`, AVD `libras36`, Android 16/API 36,
serial exclusivo `emulator-5554`; Python selecionado 3.14.6, somente stdlib para os
scripts. Gradle chamado por wrapper e `-p` absolutos, `--max-workers=2`.

Diretórios privados neste worktree, não distribuir:

- `experimentos-privados/etapa4/evidencia-real-01`: falha inicial de setup.
- `experimentos-privados/etapa4/evidencia-real-02`: persistência antes da última
  correção de drenagem; substituída como referência final por `evidencia-real-final-03`.
- `experimentos-privados/etapa4/evidencia-recusado-02`: recusa depois da drenagem final.
- `experimentos-privados/etapa5/evidencia-01`: oito fases, logs/fontes/BuildConfig/APKs.

Logs de fechamento: `/tmp/libras-fechamento-integracao-20260917.log` e
`/tmp/libras-etapa4-real-final.log`. Logs em /tmp e relatórios Gradle são descartáveis;
os snapshots das etapas 4/5 não dependem desses nomes temporários.

Procedimentos: [etapa 3](etapa3-atendimento-classificador-privado-2026-09-16.md),
[etapa 4](etapa4-persistencia-recusado-2026-09-16.md) e
[etapa 5](etapa5-regressao-apk-privado-2026-09-17.md).
Não executar builds concorrentes durante troca de pacote nem instalar B.

## Pendências explícitas — não ampliar esta rodada

**Hardware (não conectado nesta execução):** instalar privadamente em aparelho
coordenado e verificar permissão DAT/redirect, LED/câmera físicos, bateria durante
abertura, parada/reinício de captura, sessões prolongadas, memória e p50/p95 de
latência. Separar aquecimento de inferência; não extrapolar tempos do emulador.
Óculos não são necessários para selecionar/testar o contrato de outro pacote,
mas são necessários para comprovar a captura física e seu encerramento.

**Cobertura/operacional:** regressão instrumentada completa no estado final;
confirmação explícita/Corrigir/timeout seguidos de TTS/STT reais; recuperação após
crash provocado; layout por toque normal e sessões prolongadas. O teste privado
atual cancela antes de falar e não comprova o atendimento bidirecional inteiro.
**Atualização de 17/09:** a suíte instrumentada completa passou a rodar (um arquivo de
androidTest não compilava), e TTS/STT passaram a ser exercitados de forma artificial no
emulador — ver [rodada de 17/09](integracao-video-minds-e-calibracao-2026-09-17.md).
Áudio acústico, hardware e crash provocado seguem pendentes.

**Produto (preservado, não decidido aqui):** texto de consentimento provisório,
fallback sem avatar, autoconfirmação e fala da frase anterior quando Corrigir não
consegue reabrir câmera, eventual entrada de modo somente voz. Não transformar
esses comportamentos em aprovação de uso com pessoas por testes de infraestrutura.
**Atualização de 17/09:** a autoconfirmação por teto e a fala da frase anterior no Corrigir
foram resolvidas depois desta rodada — nenhuma das duas fala mais
([rodada de 17/09](integracao-video-minds-e-calibracao-2026-09-17.md)). Consentimento,
fallback sem avatar e modo somente voz continuam abertos.

**Modelo:** seleção do modelo final, qualidade linguística, generalização e rejeição
de desconhecidos são outra frente. Não são pré-requisitos para concluir os testes
de transporte/inferência da infraestrutura aqui registrados. FILHO permanece fechado.
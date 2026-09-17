# Etapa 3 — atendimento Android com baseline privado

> **Atualização 17/09:** os dois testes de atendimento passaram novamente no APK final,
> após as correções de lifecycle. [Evidências e limites](integracao-infraestrutura-fechamento-2026-09-17.md).
> O relato de falha/não execução abaixo é histórico; não atribui aprovação retroativa.

Implementado no worktree de integração, preservando a etapa 2. A primeira execução
real do operador em 16/09 falhou no aceite; investigação e correção do lifecycle descritas
abaixo. **Nenhum build, teste, commit ou push executado nesta correção.** Diagnósticos
do editor não comprovam compilação nem aprovação do patch.

## Escopo

Nova classe `com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.AtendimentoClassificadorPrivadoTest`:

- `recusarConsentimentoNaoAbreStreamComClassificadorReal`: MainActivity → VM real →
  sessão DAT simulada → consentimento → Recusar. Exige diagnóstico REAL_EXPERIMENTAL,
  identidade válida e classificador TFLite efetivamente ligado ao pipeline; verifica
  ausência de stream, frames, classificações, contextualização e chamadas TTS do diálogo.
- `aceitarVideoRealObservaDecisaoECancelaSemConfirmarFrase`: Aceitar → MockDeviceKit
  com [vídeo existente](../mobile-app-companion/app/src/androidTest/assets/sinais.mp4)
  → MediaPipe/boundary/classificador reais → primeira decisão natural do orquestrador.
  Exige frames, pose, mãos, segmento e classificação real no turno avaliado, não uma
  quantidade fixa de acertos/glosas. Ausência de segmento/classificação ou falha técnica
  **falha**, não vira sucesso de rejeição.

No ramo `Falar`, observa a chamada ao contextualizador **já criado pelo app**, sua
origem/texto e a tela de confirmação; exige câmera parada (incluindo drenagem), botões
Confirmar/Corrigir disponíveis e nenhuma chamada TTS. Antes de marcar
`confirmacao_exibida`, exige o texto contextualizado exato em um descendente visível
de `avatar_legenda` na árvore semântica não mesclada: label/valor no VM não bastam.
Fecha a sobreposição e clica
**Cancelar atendimento**, sem clicar Confirmar. No ramo REPITA/DESISTIU, aceita a
rejeição avaliada, cancela e registra separadamente os possíveis avisos TTS reais do
app. Não autoriza contextualização/fala da frase rejeitada.

Nos dois ramos do aceite, após a primeira parada, observa por 1,5 s o estado
AGUARDANDO_SINAL, referências/flags de câmera paradas e a restrição de TTS do ramo.
Essa janela acontece **antes** do segundo cancelamento defensivo no `finally` e é
registrada como `observacao_pos_cancelar` (início/fim/conclusão).

Esta correção altera o lifecycle de `HevcDecoder` em produção, compartilhado por
preview e inferência. Não altera assets, pesos ou configuração de reconhecimento.
O teste preserva os valores existentes de segmentação, limiar e tetos: não usa
`voltarAoPadrao`. Desliga somente comando de voz externo e gravador de sessão durante
o teste e restaura o snapshot completo ao terminar. Não produz CSV de landmarks.

## Execução pelo operador

Usar dispositivo/emulador com ABI compatível, DAT/credenciais e demais assets do app
já preparados, sem outra instrumentação em paralelo. O pacote baseline existente é
selecionado pela propriedade de build abaixo. **Não usar classificadorFixtures:**
modelo, sidecar, identidade e léxico são lidos dos assets do APK alvo; apenas o vídeo
vem dos assets normais do APK de testes.

```bash
/home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion/gradlew \
  -p /home/walisson/libras-livre-integracao-modelo-app/mobile-app-companion \
  :app:connectedDebugAndroidTest \
  -PlibrasLivre.classificadorPrivado=/home/walisson/libras-livre-integracao-modelo-app/experimentos-privados/app-baseline-v1 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.AtendimentoClassificadorPrivadoTest \
  -Pandroid.testInstrumentationRunnerArguments.atendimentoClassificadorPrivado=true
```

Filtros individuais: acrescente à classe `#recusarConsentimentoNaoAbreStreamComClassificadorReal`
ou `#aceitarVideoRealObservaDecisaoECancelaSemConfirmarFrase`. Sem opt-in, ambos são
ignorados **antes de lançar a Activity**. Com opt-in e APK sem BuildConfig privado,
falham antes da Activity; assets inválidos, modo SIMULADO/RECUSADO ou identidade ausente
também falham. A propriedade de instrumentação sozinha não transforma um APK público
em privado. Não alterar limiares/tetos para fazer o vídeo passar.

## Evidência privada e interpretação

No sandbox do APK alvo, `files/atendimento-classificador-privado/recusa.json` e
`files/atendimento-classificador-privado/aceite.json`. Extrair com `adb exec-out run-as
com.meta.wearable.dat.externalsampleapps.cameraaccess cat` seguido do caminho relativo
do relatório; guardar a saída somente em diretório privado. O próprio JSON informa
seu caminho absoluto, o APK e a cópia temporária do vídeo (removida na limpeza).

Somente quando o método começa, passa pelo `assume` de opt-in e consegue escrever,
sobrescreve seu relatório com UUID/início e `EM_EXECUCAO`, antes de validar
**BuildConfig/assets** e lançar Activity. Isso não acontece antes do build Gradle:
falha de build/instalação, método não selecionado, instrumentação que não chegou ao
método ou `assume` sem opt-in **não invalidam um relatório antigo** que ainda exista.
Falha na primeira escrita também não garante invalidação. Conferir caso, UUID e
horários contra a execução atual e seu XML; arquivo aprovado isoladamente não basta.

Após a primeira escrita bem-sucedida, registra `ENCERRANDO`, e só após limpeza
`APROVADO_NO_RAMO_OBSERVADO` ou `FALHOU`. Morte/timeout **depois dessa primeira escrita**
não conserva sucesso antigo daquele método. O relatório do **outro método** não é
renovado por filtro individual. Erro de escrita/limpeza falha o teste.

**Retenção:** o executor desta primeira execução usou `uninstall_after_test: true`
para os dois APKs. A desinstalação removeu também os relatórios do sandbox, portanto
o comando `run-as` depois dela não os recupera. Para uma próxima execução autorizada,
garantir retenção do APK/dados ou coleta privada antes da desinstalação. Reinstalar
não recupera o JSON anterior. Não exportar automaticamente os textos/glosas para
logcat ou diretório compartilhado.

O schema 3 inclui SHA-256 do vídeo, modelo, sidecar e identidade fixada no BuildConfig; identidade
experimental/checkpoint; diagnóstico, configurações anterior/efetiva, limites, eventos,
classificações e contextualizações, além das chamadas de TTS observadas. Não guarda
mídia, landmarks ou áudio no relatório. Os textos/glosas ainda são dados privados.

Eventos `falha_*` registram `estado_no_evento` e `falha_tecnica=true`; o relatório
inclui `falhas_tecnicas`. O evento original e seu detalhe não são descartados.
O schema 3 remove a categoria/contagem de callbacks tardios tolerados do schema 2:
**qualquer `falha_*` reprova**, inclusive `codec is released already` na limpeza.

Ramos principais:

| Ramo | Evidência permitida |
|---|---|
| `CONSENTIMENTO_RECUSADO_SEM_STREAM` | Recusa sem captura no app/mock. |
| `FALAR_CONFIRMACAO_EXIBIDA_CANCELADA_SEM_TTS` | Frase real contextualizada e mostrada, cancelada **sem confirmar/falar**. |
| `AVALIADO_REJEITADO_REPITA` / `AVALIADO_REJEITADO_DESISTIU` | Reconhecimento real avaliado e rejeitado; confirmação/contextualização não exercitadas. |

`FALADA` é o nome **legado** da decisão `Falar` no painel, não evidência de frase
confirmada/falada. `confirmacao_explicita_executada` e `audio_real_verificado` continuam
false. A conclusão exige status final aprovado **e** leitura do ramo; não chamar uma
rejeição de sucesso da confirmação.

## Investigação da primeira execução real (16/09)

Evidências existentes consultadas: log do operador em /tmp, XML e logcat arquivados
pelo AndroidTest, configuração/log UTP e consultas ADB somente de leitura. XML:
2 métodos, recusa aprovada, aceite reprovado em `executar` com
`AssertionError: Falha técnica na pipeline`.

Sequência comprovada no logcat (horário local):

- 16:16:24.865: decisão `Falar`, dois sinais e zero falhas de classificação;
- 16:16:24.883–24.896: solicitação de parada do stream e contextualização TEMPLATE;
- 16:16:25.134–25.160: serviço de streaming para/libera WakeLock/é destruído;
- 16:16:25.837 e 25.849: `HevcDecoder.onInputBuffer` tenta `queueInputBuffer`
  e recebe `IllegalStateException: codec is released already`;
- 16:16:27.213: primeiro Cancelar em CONFIRMANDO_RECONHECIMENTO;
- 16:16:28.743: segundo Cancelar em AGUARDANDO_SINAL (limpeza após a asserção);
- 16:16:29.786: runner registra a falha genérica do teste.

O código do decoder explica a corrida: `onInputBuffer` espera `poll(1s)` e,
mesmo com `active=false`, tenta devolver um buffer vazio ao codec que `stop()` já
parou/liberou em outra thread. O decoder de inferência encaminha a exceção como
`falha_decoder`, detalhe `entrada: codec is released already`; a reprovação desse
evento era correta. O snapshot de lifecycle agora é diagnóstico, nunca uma isenção.
Os dois erros de logcat vêm dos decoders; não é possível atribuir cada thread a
preview/inferência nem reconstruir a lista exata de eventos privados sem o JSON.

**JSON original indisponível:** o UTP registrou a desinstalação dos dois APKs;
`run-as` responde `unknown package`, inclusive sem pacote retido em `pm list packages -u`.
Não foi encontrada cópia local nos dois worktrees ou em /tmp. Portanto, a causa da
corrida está demonstrada por logcat e código, mas **não foi possível ler o JSON
solicitado nem confirmar seus snapshots/contagens**. Não se atribui aprovação
retroativa ao teste que falhou.

### Causa corrigida no código, sem exceção admitida no teste

A tolerância recém-adicionada ao observador foi **retirada**. Mantidos o assert da
frase visível na UI, a janela pós-Cancelar antes da limpeza e os snapshots/eventos
JSON. O teste exige zero falhas técnicas tanto no corpo quanto na limpeza.

O [decoder](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/HevcDecoder.kt)
agora separa dois locks, com ordem única lifecycle → ownership:

- `lifecycleLock` serializa `start`, parsing/enqueue/ativação e `stop`. Callbacks não
  o adquirem. Só `start` cria o codec; `activateDecoder` usa a identidade já criada,
  sem `reset`/recriação implícita. Falha na ativação encerra os recursos parcialmente criados.
- [CodecCallbackOwner](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/CodecCallbackOwner.kt)
  protege identidade referencial e estados NEW/PREPARED/RUNNING/STOPPED com lock curto.
  Entrada valida antes do `poll(1s)` **fora de ambos os locks**, revalida depois e só
  então obtém/copia/enfileira o input buffer sob a mesma guarda. O fallback vazio
  também fica sob a guarda. Saída, erro e mudança de formato validam identidade/estado.
- `stop` retira a identidade sob a guarda **antes** de parar/liberar nativamente.
  `stop/release` não mantêm o lock de callbacks; nenhuma espera/join de thread está
  sob essa guarda. `release` é tentado em `finally` mesmo se `codec.stop` falhar;
  um codec criado mas nunca iniciado é apenas liberado. A thread também é encerrada.
- `stop` é terminal e idempotente por instância, inclusive antes de `start`.
  `decodeFrame`/`start` tardios não a ressuscitam. Os callers atuais (`CameraViewModel`
  e `LandmarkPipeline`) já criam outra instância por stream/surface, sem mudança de API.
- Erros reais são capturados sob a guarda e notificados **fora dos locks**, sem filtro
  por texto ou pelo estado posterior. Callbacks de uma identidade já invalidada não
  acessam o codec nem fabricam eventos de falha por tentar usar recurso liberado.

Adicionados [testes JVM do helper usado em produção](../mobile-app-companion/app/src/test/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/CodecCallbackOwnerTest.kt):
identidade (não `equals`), estado preparado, callback suspenso na espera durante stop,
atomicidade get/queue antes da liberação, callback obsoleto durante liberação sem
deadlock e stop terminal/idempotente. São testes de contrato com latches, não emulação
do MediaCodec nem prova de comportamento dos drivers.

**Validação pendente:** nenhum build/teste foi executado nesta correção. A causa
comprovada está corrigida na implementação, mas a eficácia em Android ainda requer
nova execução autorizada da etapa 3 e repetição de captura/parada nos decoders de
preview e inferência. Chamadas nativas de setup/teardown continuam síncronas no caller;
não há promessa de latência máxima ou de ausência de defeitos do driver. O patch não
comprova aprovação, acurácia linguística nem valida o JSON perdido da execução anterior.

## Limites intencionais

- Esperas: 180 s de aquecimento, 120 s pela primeira decisão, 20 s por transição/parada;
  janelas de observação de 1,5 s. São limites **do teste**, não ajustes do reconhecimento.
  O histórico de três classificações em VideoClassificadorPrivadoTest é referência,
  não promessa de repetir número/confiança no stream DAT.
- Cancelar foi escolhido em vez de Corrigir: o fallback de Corrigir, **nesta rodada**, podia
  falar quando a câmera não reabria, e o teto de 60 s **confirmava sozinho**. Os dois
  comportamentos mudaram depois, em 17/09 (não falam mais; ver
  [rodada de 17/09](integracao-video-minds-e-calibracao-2026-09-17.md)); a escolha deste teste
  descreve o estado de então. Este teste verifica ausência de TTS na janela observada e cancela antes;
  **não certifica confirmação exclusivamente explícita por tempo ilimitado**. Essa
  garantia exigiria uma mudança de produto fora do escopo. Não deixa o timeout expirar
  nem simula um clique Confirmar para fabricar cobertura.
- Observadores instalados por reflexão somente em androidTest: delegam argumentos,
  retornos e efeitos originais de pipeline, contextualizador e TTS, sem injetar glosas.
  Não trocam o classificador, avaliador ou o VM. Mudança de campo/owner deve falhar.
  Fluxos de estado podem conflar transições; os callbacks registram frames/classificações/
  decisões, e a parada também verifica referências de câmera/stream/drenagem na main.
- Testa integração com **MockDeviceKit**, não LED/desligamento físico, redirect no Meta
  AI real, qualidade linguística, acurácia ou calibração. Não valida áudio acústico,
  rota Bluetooth, microfone, STT nem resposta completa do atendente. TTS de aviso em
  rejeição pode tocar; contagem de chamada não comprova áudio audível.
- Contextualizador padrão permanece o selecionado pelo app (atualmente modelo neural
  desligado); origem é registrada, sem exigir saída MODELO ou frase de roteiro. Avatar
  pode degradar para legenda; não é critério de qualidade de LIBRAS.
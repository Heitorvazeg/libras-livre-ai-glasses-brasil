# Etapa 4 — persistência entre processos e recusa por segmento

> **Atualização 17/09:** persistência REAL em dois processos e recusa por segmento
> executadas e aprovadas, com restauração. [Provas, correções e limites](integracao-infraestrutura-fechamento-2026-09-17.md).
> “Não executado” e “execução futura” abaixo referem-se à entrega inicial do código.

**Revisão em 2026-09-17: correções implementadas, ainda NÃO compiladas nem executadas.**
Há uma execução anterior à revisão: a fase 1 REAL **falhou**, não foi aprovada.
O [log privado fase1Gravar](../experimentos-privados/etapa4/evidencia-real-01/fase1Gravar.log)
registra `ComposeTimeoutException` esperando `mais_controles` por 20 s, status JUnit
`-2` e `FAILURES!!!`. O setup anterior não preparava o MockDeviceKit para chegar à
CameraScreen. Essa execução não comprova persistência entre processos nem fase 2.

Nesta revisão não houve build, adb, testes (nem Python), instalação, instrumentação,
commit ou push. Houve leitura/revisão estática e consulta aos diagnósticos do editor;
ausência de diagnóstico não comprova compilação ou aprovação dos testes. Os resultados
da tentativa anterior não validam as correções abaixo.

Escopo exclusivo: `/home/walisson/libras-livre-integracao-modelo-app`.
Os arquivos previamente modificados/não rastreados das etapas 2/3 foram preservados.
Não se editou código de produção, Gradle, configuração de thresholds, assets ou
testes da etapa 3. Os valores 0,83/0,77 abaixo são estímulos temporários **somente
do teste de persistência**, restaurados ao final, não calibração/recomendação de uso.

## Arquivos novos

- [Gerador negativo stdlib](../scripts/gerar_pacote_classificador_recusado.py) e
  [testes unitários do gerador](../scripts/test_gerar_pacote_classificador_recusado.py).
- [Host Android stdlib](../scripts/executar_etapa4_android.py) e
  [testes unitários do protocolo instrumentation](../scripts/test_executar_etapa4_android.py).
- [Suporte exclusivo de androidTest](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/Etapa4Suporte.kt).
- [Journal e recuperação exclusiva de RECUSADO](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/EstadoRecusadoEtapa4.kt).
- [Persistência REAL_EXPERIMENTAL](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/RealExperimentalEntreProcessosTest.kt).
- [Recusa com vídeo e orquestrador reais](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/RecusadoVideoOrquestradorTest.kt).

## 1. Persistência REAL_EXPERIMENTAL: dois processos, não duas Activities

Referência: [teste de reabertura existente](../mobile-app-companion/app/src/androidTest/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/DiagnosticoClassificadorReaberturaTest.kt).
A etapa 4 usa `createEmptyComposeRule` + `ActivityScenario<MainActivity>` e recupera
**o CameraViewModel criado por CameraScreen**, com factory que falha se ele não
existir. Não cria um VM/classificador alternativo para obter diagnóstico favorável.

**Correção do setup:** cada chamada de `comActivity` concede permissões e prepara
MockDeviceKit com câmera concedida e óculos pareados/ligados/vestidos/abertos **antes**
de lançar MainActivity. O pareamento é refeito no processo da fase 2, não presumido
persistente após force-stop. O helper não acessa ConfiguracoesDemo: a fase 2 continua
verificando disco e primeiro carregamento do singleton antes desse setup. Não inicia
sessão/stream nem configura feed de vídeo no teste REAL; as verificações exigem
ausência de stream/câmera e mantêm o `TfliteSignClassifier` do VM original.
Ao fechar, aguarda o escopo de encerramento existente antes de desabilitar o mock.

### Fase 1: `fase1Gravar`

1. Exige opt-in, APK debug privado e UUIDs canônicos do host.
2. Confere o SHA da identidade contra BuildConfig **e contra o pacote escolhido no
   host**; confere os hashes dos bytes de modelo/sidecar do APK alvo.
3. Recusa marcador de execução anterior pendente (REAL ou RECUSADO): não limpa nada por padrão.
4. Salva snapshot exato das strings/ausências de ConfiguracoesDemo, UUID da execução,
   marcador da invocação, PID, `Process.getStartElapsedRealtime()`, UUID estático do
   processo, identidade/hashes completos e limiar original/novo em SharedPreferences
   temporário `etapa4_persistencia_test_owned`.
5. Abre MainActivity; exige `REAL_EXPERIMENTAL`, identidade completa correspondente,
   `TfliteSignClassifier` efetivamente instalado no pipeline e diagnóstico visível.
6. Muda o limiar por `ConfiguracoesDemo.de(context).atualizar`, nunca gravando a
   chave de produção diretamente. Usa `commit()` síncrono no mesmo SharedPreferences
   como barreira das escritas `apply()` e verifica a chave e a UI atualizada.
7. Fecha a Activity, persiste estado `GRAVADO` e devolve prova ao host. **Não restaura
   preferências nem apaga marcador no sucesso da fase 1.** Em exceção normal, tenta
   restaurar; crash/force-stop externo pode exigir recuperação explícita.

### Fase 2: `fase2VerificarAntesDeAlterar`

É outra invocação `adb am instrument`, depois de force-stop dirigido ao app alvo.
Antes de qualquer `atualizar`, confere:

- registro persistido presente, estado `GRAVADO` e mesmo UUID dono;
- marcador de invocação novo e diferente do marcador da fase 1;
- **PID diferente OU start do processo diferente** (aceita reutilização de PID);
- UUID estático do processo diferente, como guarda adicional — um UUID criado por
  método/Activity não substitui o PID/start do Android;
- hash da identidade, modelo, sidecar, checkpoint, experimento e calibração iguais;
- limiar da preferência e do primeiro carregamento de ConfiguracoesDemo iguais ao
  gravado, antes de lançar/verificar MainActivity novamente;
- classificador do pipeline ainda real, modo/hash/limiar da UI correspondentes.

O `finally` da fase 2 restaura o snapshot anterior e limpa **apenas** o marcador
test-owned. Falha de restauração reprova o método; não publica prova de sucesso.
Rodar fase 2 isolada, sem marcador, no mesmo processo, com UUID errado ou marcador
repetido é falha, não skip. UUID dono errado não autoriza limpar estado alheio.

**Precisão da evidência:** produção persiste o limiar. A identidade do classificador
é rederivada dos assets a cada processo; o teste persiste os hashes no seu marcador
e compara com a identidade efetivamente carregada. Não foi criada uma nova
persistência de hash no armazenamento de produção. Este teste não liga stream,
não mede qualidade linguística e não substitui inferência/paridade do modelo.

## 2. Fixture negativa reproduzível, sem modelo real

O gerador usa apenas stdlib e funções do
[pacote privado existente](../scripts/pacote_classificador_privado.py).
Produz exatamente modelo, sidecar e identidade; bytes determinísticos, sem relógio,
random, pickle, checkpoint, rede ou bibliotecas de treinamento.

- Modelo: oito bytes `ff ff ff 7f 54 46 4c 33`. Identificador `TFL3` em `[4:8]`, mas
  root offset `0x7fffffff` fora do buffer: não existe grafo válido.
- Contrato: float32 `[1,96,57,3]`, pose na ordem do app, 20 rótulos únicos sintéticos
  `fixture_nao_linguistica_00` a `fixture_nao_linguistica_19`.
- Hashes coerentes em todos os arquivos, identidade experimental não aprovada e
  sem bloco de calibração. SHA de checkpoint é hash de uma frase sintética, **não
  identidade de pesos reais**.
- Saída exige diretório absoluto privado (fora do repositório ou em
  experimentos-privados). Recusa symlinks/caminhos versionados e reserva o destino
  com criação exclusiva: nem diretório vazio existente é substituído.
- Em falha de escrita, pode sobrar um diretório parcial reservado. Não apaga
  automaticamente, não tenta completar nem sobrescrever; use outro destino novo.

Os testes unitários cobrem reprodução byte a byte, hashes/contrato, root offset
inválido, adulteração, caminhos/symlinks e não sobrescrita. Eles **não carregam
LiteRT**: a recusa nativa é responsabilidade do teste Android.

## 3. RECUSADO: vídeo → segmento → classify → falha → decisão

O teste novo é separado de AtendimentoClassificadorPrivadoTest e não altera seus
helpers/asserções. Adota o padrão de observadores delegantes/reflexão test-only,
restrito aos componentes já criados pelo VM.

Caminho exercitado quando executado:

1. Exige opt-in distinto, APK privado inválido e fixture exata. Confere hashes,
   sidecar de 20 classes e disponibilidade nativa `TensorFlowLite.runtimeVersion()`.
2. O snapshot durável é salvo antes de desligar comando de voz/gravador. MockDeviceKit
  é preparado e recebe o vídeo de sinais do APK de testes antes de lançar Activity.
  MainActivity carrega o pacote de verdade e deve apresentar RECUSADO por grafo
  inválido (não por ausência de biblioteca, arquivo ou hash incorreto).
3. Sessão, consentimento e captura são acionados pela UI existente.
4. Decoder, MediaPipe, normalização, imputação e segmentação são os do app.
5. O observador envolve **por delegação** o `ClassificadorRecusado` criado pelo
   carregador: conta a chamada com segmento não vazio/tempo crescente, chama o
   original e relança a mesma exceção. Não fornece uma falha sintética no lugar dele.
6. Observa `segmento`, `falha_classificacao` com o motivo exato, callback real
   `onRecognitionFailed` e primeira decisão natural do DialogOrchestrator contendo
   `falhas>=1`, `sinais=0`, REPITA/DESISTIU correspondente ao painel.
7. Reprova zero frames/pose/mãos, zero tentativas, timeout sem segmento, falhas de
   decoder/extração (inclusive teardown), classificação/glosa, contextualização,
   confirmação ou TTS de tradução. TTS real continua delegado; somente
   `AVISO_REPITA`/`AVISO_DESISTIR` são permitidos.
8. Cancela pela UI, espera câmera/classificação pararem e observa 1,5 s sem retomada
  de câmera, novo TTS ou glosas. Fecha Activity e aguarda a barreira final real de
  teardown antes de validar/remover observadores. Desabilita mock, restaura snapshot
  de configurações e remove a cópia temporária do vídeo.

**Não é pipeline stub nem “full vídeo” simulado por chamadas diretas.** Não chama
`onSegmento`, `endSession`, `onSignRecognized` ou `onSignRecognitionFailed` para
fabricar evidência. A decisão termina naturalmente, sem mudar thresholds,
segmentação ou tetos. Os únicos seletores temporários são desligar comando de voz
externo e gravador de sessão; são restaurados no final.

O relatório Android fica no diretório privado test-owned `etapa4-recusado`, sob
UUID novo. Guarda contagens/eventos/hashes, não coordenadas de landmarks. O host
recebe o mesmo relatório via resultados da instrumentação; não depende de
recuperar arquivos após UTP/desinstalação.

### 3.1 Snapshot durável RECUSADO e isolamento de dono

Antes do primeiro `configs.atualizar(false)`, o helper grava com `commit()` síncrono
o journal `etapa4_recusado_test_owned`: versão, tipo RECUSADO, UUID dono, hash da
identidade e mapa exato de strings de todas as preferências, inclusive ausência de
chaves. Tipos inesperados falham antes de alterar seletores. Um journal já existente
não é sobrescrito, mesmo se o UUID for repetido.

O `finally` restaura pelo journal, não por uma variável que sumiria no crash.
O parser de ConfiguracoesDemo é reutilizado sobre armazenamento somente leitura do
snapshot para restaurar **todos os valores do singleton**, inclusive flags originais
ou defaults de chaves ausentes. Depois de `atualizar`, repõe o mapa exato no disco
com `commit()` (desfaz materialização de defaults). Confere mapa e valores antes de
remover apenas `registro` do journal RECUSADO. Erro de restauração reprova o teste;
enquanto não restaurado, mantém o snapshot para tentativa explícita posterior.

Salvar/restaurar RECUSADO recusa marcador REAL presente sem alterar seus dados.
REAL também recusa journal RECUSADO pendente. UUID errado, journal ausente/corrompido
ou pacote diferente falham sem limpar estado alheio. Não há promessa de segurança
para duas instrumentações concorrentes: o protocolo exige emulador dedicado e uso
serial. A recuperação só restaura preferências, não apaga relatórios/vídeos de crash.

### 3.2 Barreira de teardown, sem completar produção pelo teste

`scenario.close()` retorna antes de terminar o trabalho assíncrono de `onCleared`.
`BarreiraEncerramentoEtapa4` captura, por reflexão test-only, o `Job` do
`encerramentoScope` do VM original **antes** de fechar a Activity. Depois do close,
fora da main, espera `join()` desse dono (incluindo todos os filhos) com limite de
30 s. O caminho existente drena o produtor de vídeo, fecha readers na fila serial
de `DonoLeitorLandmarks`, espera extração/classificações e conclui `LandmarkPipeline.dispose`.

Além do término do Job, exige `recursosFechados=true` e referências nulas de decoder,
reader, dono, extrator e fila. Cancelamento sozinho não conta como descarte exitoso.
Não chama `dispose()`/`stop()` extra, não substitui dispatcher e não força callbacks
para fabricar conclusão. Os observadores permanecem instalados durante todo o
teardown. Só após a barreira válida ocorre a última `obs.validar()` e a remoção dos
delegados; erros de decoder/extração continuam reprovando. Timeout/falha de descarte
reprova e mantém observadores até o processo terminar; não publica aprovação.

O host passa a exigir `teardown_pipeline_concluido=true` e `cleanup=true` na prova
RECUSADO. Essas guardas foram implementadas; ainda não foram verificadas no emulador.

## 4. Seleção explícita — não rodar as classes juntas num build qualquer

Prefixo das classes: `com.meta.wearable.dat.externalsampleapps.cameraaccess.libras`.

| APK alvo | Classe # método | Opt-in |
|---|---|---|
| Real privado | `RealExperimentalEntreProcessosTest#fase1Gravar` | `etapa4RealPersistencia=true` |
| Mesmo APK real, outro processo | `RealExperimentalEntreProcessosTest#fase2VerificarAntesDeAlterar` | `etapa4RealPersistencia=true` |
| Inválido gerado | `RecusadoVideoOrquestradorTest#segmentoRealRecusadoNaoGeraGlosaConfirmacaoOuTraducao` | `etapa4RecusadoVideo=true` |
| Recuperação explícita; não comprova persistência | `RealExperimentalEntreProcessosTest#limparEstadoInterrompido` | `etapa4RecuperarPersistencia=true` |
| Inválido gerado; recuperação RECUSADO sem vídeo/Activity | `RecusadoVideoOrquestradorTest#recuperarEstadoRecusadoInterrompido` | `etapa4RecuperarRecusado=true` |

Sem opt-in, os métodos não lançam Activity. **Com opt-in, precondição errada falha.**
O host rejeita assumption/skip mesmo que o adb saia zero. Não execute a classe
inteira de persistência numa única instrumentação: a ordem JUnit não é contrato e
isso não fornece processos diferentes.

## 5. Comandos para execução futura da revisão — NÃO executados nesta revisão

Pré-requisitos: Python 3.10+ (stdlib), Android SDK com `adb` e **aapt clássico**,
emulador explicitamente selecionado e dedicado, assets/dependências locais já
preparados, credenciais necessárias ao build existentes. Não usar telefone real,
Gradle connected-test/UTP ou Android Test Orchestrator para este protocolo.

### 5.1 Variáveis e testes Python (futuros)

```bash
REPO=/home/walisson/libras-livre-integracao-modelo-app
PYTHON=/usr/bin/python3
SERIAL=emulator-5554
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"  # ajustar à versão instalada
PRIVADO="$REPO/experimentos-privados/etapa4"
PACOTE_REAL=/caminho/absoluto/privado/pacote-real
PACOTE_INVALIDO="$PRIVADO/pacote-recusado-v1"
APP_APK="$REPO/mobile-app-companion/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$REPO/mobile-app-companion/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$REPO/scripts" "$PYTHON" -m unittest \
  test_gerar_pacote_classificador_recusado test_executar_etapa4_android \
  test_pacote_classificador_privado -v
```

### 5.2 APK real + duas fases

O build abaixo é uma **ação futura autorizada separadamente**, não parte executada
desta entrega. Não reutilize um APK antigo só porque o nome do arquivo é o mesmo.

```bash
"$REPO/mobile-app-companion/gradlew" -p "$REPO/mobile-app-companion" \
  -PlibrasLivre.classificadorPrivado="$PACOTE_REAL" \
  :app:assembleDebug :app:assembleDebugAndroidTest

"$PYTHON" "$REPO/scripts/executar_etapa4_android.py" --modo real \
  --serial "$SERIAL" --aapt "$AAPT" --pacote "$PACOTE_REAL" \
  --app-apk "$APP_APK" --test-apk "$TEST_APK" \
  --saida "$PRIVADO/evidencia-real-02"
```

O host arquiva snapshots dos dois APKs e seus SHA-256, valida package/debug e
target/runner da instrumentação com aapt, compara assets com o pacote explícito,
instala os snapshots com `adb -s SERIAL install -r -t`, confere SHA-256 dos APKs
instalados via `pm path`/`sha256sum` e então executa:

1. force-stop **somente** do package alvo naquele emulador;
2. `am instrument -w -r -e class CLASSE#fase1Gravar`, com UUIDs/hash/opt-in;
3. force-stop **somente** do mesmo package alvo;
4. outra chamada `am instrument -w -r -e class CLASSE#fase2VerificarAntesDeAlterar`.

Não há reinstalação, reset, limpeza de dados ou substituição do modelo entre fases.
Os argumentos concretos completos de cada chamada ficam no JSON de execução.

### 5.3 Pacote inválido + build próprio + vídeo real

Execute apenas depois de terminar/restaurar a execução real. É **outro build** com
o mesmo applicationId; instalar esse APK muda a identidade do app. Não o use entre
as fases de persistência. O gerador e destino de evidências não sobrescrevem.

```bash
"$PYTHON" "$REPO/scripts/gerar_pacote_classificador_recusado.py" \
  --saida "$PACOTE_INVALIDO"

"$REPO/mobile-app-companion/gradlew" -p "$REPO/mobile-app-companion" \
  -PlibrasLivre.classificadorPrivado="$PACOTE_INVALIDO" \
  :app:assembleDebug :app:assembleDebugAndroidTest

"$PYTHON" "$REPO/scripts/executar_etapa4_android.py" --modo recusado \
  --serial "$SERIAL" --aapt "$AAPT" --pacote "$PACOTE_INVALIDO" \
  --app-apk "$APP_APK" --test-apk "$TEST_APK" \
  --saida "$PRIVADO/evidencia-recusado-01"
```

Não passar `librasLivre.classificadorFixtures`: essa propriedade substitui os
assets de androidTest e pode retirar o vídeo padrão. Não usar
`librasLivre.permitirAssetsFaltando=true` para reivindicar cobertura vídeo real.

### 5.4 Recuperação de execução interrompida

Uma interrupção entre fases pode deixar limiar alterado e snapshot pendente.
O host imprime o UUID dono **antes** das operações e também o grava no relatório.
Novo modo real falha deliberadamente diante desse marcador; não faz limpeza
silenciosa. A fase 2 normal já restaura no finally quando consegue executar.

Para recuperar um crash, use os APKs reais arquivados naquela execução (ou build
compatível) e o **mesmo pacote real e UUID dono**; escolha nova saída:

O APK de testes precisa conter esta revisão: APKs anteriores não têm o novo método
RECUSADO nem os campos de prova de recuperação exigidos pelo host atualizado.
Para recuperar uma execução antiga, prepare o APK de testes atualizado/compatível;
não reutilize o snapshot antigo de androidTest. Não substitua o pacote real por
fixture negativa para recuperar REAL. O host usa `install -r`, não limpeza de dados.

```bash
"$PYTHON" "$REPO/scripts/executar_etapa4_android.py" --modo recuperar \
  --execucao UUID_DONO_DA_EXECUCAO_INTERROMPIDA \
  --serial "$SERIAL" --aapt "$AAPT" --pacote "$PACOTE_REAL" \
  --app-apk "$PRIVADO/evidencia-real-01/app-atual.apk" \
  --test-apk "$TEST_APK" \
  --saida "$PRIVADO/recuperacao-real-01"
```

Recuperação é `RECUPERADO_SEM_EVIDENCIA`, nunca aprovação da persistência.
UUID errado ou registro ausente falha e não limpa preferências alheias. Não usar
`pm clear`/uninstall como “recuperação”: perderia o snapshot original.

### 5.5 Recuperação exclusiva de RECUSADO

Crash depois do snapshot e antes do finally pode deixar `comandoDeVoz=false` e
`gravadorSessao=false` temporários. Use **o mesmo UUID dono** impresso pelo host na
execução RECUSADO e a fixture negativa exata; não use `--modo recuperar` (é REAL).
Requer que a execução interrompida já usasse o novo journal; versões antigas que
guardavam original somente em memória não permitem reconstruir o snapshot perdido.

```bash
"$PYTHON" "$REPO/scripts/executar_etapa4_android.py" --modo recuperar-recusado \
  --execucao UUID_DONO_DA_EXECUCAO_RECUSADO_INTERROMPIDA \
  --serial "$SERIAL" --aapt "$AAPT" --pacote "$PACOTE_INVALIDO" \
  --app-apk "$APP_APK" --test-apk "$TEST_APK" \
  --saida "$PRIVADO/recuperacao-recusado-01"
```

Aqui os APKs devem ser do build negativo compatível com esta revisão. O host chama
somente `recuperarEstadoRecusadoInterrompido`, com opt-in distinto. O método não lança
Activity, não prepara mock, não inicia vídeo e não altera/apaga marcador REAL. Exige
UUID/tipo/hash corretos e restaura flags mais snapshot exato de ausências. A prova deve
ter `RECUPERADO_SEM_EVIDENCIA`, `tipo=RECUSADO` e `cleanup=true`: nunca é aprovação do
teste de segmento nem persistência REAL. O modo de recuperação não exige vídeo no
APK de testes, porque não o utiliza.

## 6. Critério do host e limitações

- Nunca escolhe device padrão: exige serial `emulator-NNNN`, `get-state=device`,
  serial correspondente e `ro.kernel.qemu=1`; reconfere antes de cada force-stop.
  Não executa kill-server, wipe, uninstall, pm clear ou force-stop de outro pacote.
- O `install -r` altera o app/test APK desse emulador; as permissões de teste são
  concedidas e não revertidas. Não equivale a sandbox de um emulador compartilhado.
- Exige APKs monolíticos, app debug e runner padrão. Snapshot + hashes comprovam
  **quais bytes foram instalados**, não que foram compilados do HEAD atual. Cabe
  ao operador montar os dois APKs a partir desta árvore antes de usar o host.
  BuildConfig/hash/UUID e seleção de método impedem confundir outro pacote ou
  ausência do teste com sucesso. Não executar builds/instalações concorrentes.
- `adb` frequentemente retorna 0 mesmo com falha JUnit. O host exige saída sem
  failures/erro/crash, `numtests=1`, `current=1`, classe/método exatos, status `[1,0]`,
  resumo `OK (1 test)`, **INSTRUMENTATION_CODE=-1** e prova JSON com UUID/fase/hash
  correspondentes. Status -2/-3/-4, zero testes, teste errado, prova velha/ausente
  ou fim ausente falham. O parser é conservador: outro formato de runner precisa
  revisão explícita, não relaxamento para “adb exit 0”.
- O host guarda logs brutos, comandos, snapshots, hashes e provas no diretório
  privado novo. Falha/timeout permanece FALHOU, nunca herda sucesso anterior.
  Timeouts: comando comum 120 s, instalação 300 s, cada fase 600 s. Timeout do
  cliente adb não garante que o processo remoto terminou: inspecione o emulador
  antes de recuperar/repetir; não há limpeza automática destrutiva.
- Recusa exige assinatura de erro nativo relacionada a FlatBuffer/modelo inválido.
  Mudança de mensagem da versão LiteRT pode exigir ajuste fundamentado na saída
  real. Ausência de ABI/MediaPipe/vídeo/segmento não conta como recusa aprovada.
- Os observadores usam reflexão em campos privados, somente em androidTest, e
  falham se o campo/instalação/delegado não corresponder. Não constituem API pública.
  Nenhuma aprovação foi observada nesta entrega: compilação/reflexão/Compose,
  geração de segmento e término natural ainda precisam ser validados no emulador.
- Os testes Python adicionados exercitam parser do novo modo, seleção separada de
  classe/método/opt-in, UUID canônico obrigatório, pacote negativo exato e recusa de
  prova incompleta/de outro tipo/hash ou marcada APROVADO na recuperação. Não foram
  executados nesta revisão; não provam semântica SharedPreferences nem teardown Android.
- Validação Android ainda pendente: repetir REAL em dois processos; exercitar
  RECUSADO com flags originais true/false e chaves ausentes; interromper após snapshot
  e recuperar pelo dono; recusar UUID errado/REAL pendente sem mudar preferências;
  confirmar que falhas tardias de extração/decoder e timeout de teardown reprovam.
- Cobertura de vídeo é do **MockDeviceKit**, não óculos físicos. TTS observado é
  chamada ao motor real delegado, não saída acústica comprovada. Não há prova de
  acurácia, tradução linguística, reconhecimento de pessoa ou prontidão de entrega.
- Os testes não gravam coordenadas e desligam o gravador no caso de vídeo. Logs,
  nomes de pacote, caminhos locais e hashes continuam privados; não versionar os
  APKs/pacotes/relatórios gerados.

Após executar futuramente, registrar aqui apenas resultados confirmados, UUIDs e
referências privadas às evidências; não transformar “implementado” em “aprovado”
com base em leitura estática.
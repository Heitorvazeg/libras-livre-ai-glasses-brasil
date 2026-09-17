# Etapa 5 — regressão opt-in do empacotamento APK privado

**Atualização 17/09: 23 testes unitários e oito fases Gradle aprovados.**
Ver [execução, hashes e limites](integracao-infraestrutura-fechamento-2026-09-17.md).
O sucesso é de conteúdo/fontes, não de inferência do pacote B nem aprovação do modelo.
Nenhum commit, instalação, force-stop, instrumentação ou alteração de preferências.

Escopo: worktree `/home/walisson/libras-livre-integracao-modelo-app`.
Somente três arquivos novos:

- [CLI stdlib](../scripts/executar_regressao_apk_privado.py).
- [Unitários sem SDK](../scripts/test_executar_regressao_apk_privado.py).
- Este documento.

O [teste Gradle anterior](../scripts/test_build_classificador_privado.py) fica
intacto: cobre a tarefa de preparação, não substitui a inspeção dos APKs desta
etapa. Nenhuma modificação nas etapas 2/3/4 ou em produção foi necessária.
As alterações preexistentes no worktree foram preservadas. Não se escreve no
worktree original `/home/walisson/libras-livre-ai-glasses-brasil`.

## 1. Interface e autorização

Python **3.10+**, somente stdlib e o
[validador de pacote existente](../scripts/pacote_classificador_privado.py).
O executor usa `fcntl`/grupos de processos: destinado ao host Linux deste projeto.
Não importa o executor nem o gerador da etapa 4, TensorFlow, LiteRT, NumPy ou SDK
Python. Nenhuma execução de pickle, treino, inferência ou download pelo script.

| Argumento | Contrato |
|---|---|
| `--executar` | Única autorização para iniciar builds/escritas. Sem ele, apenas plano JSON `NAO_EXECUTADO`, sem subprocessos, SDK ou criação da saída. |
| `--pacote` | Obrigatório; caminho absoluto privado de A, com os três arquivos exatos. Pode estar fora do repositório. Não há baseline fixo obrigatório. |
| `--saida` | Obrigatório; diretório absoluto **novo**, abaixo de `experimentos-privados/` deste worktree. Deve estar ignored e sem arquivos rastreados. Nem diretório vazio existente é reutilizado. |
| `--gradle` | Opcional; wrapper absoluto executável deste worktree. Default derivado de `__file__`, nunca do diretório corrente. Wrapper de outro worktree é recusado. |

Na execução autorizada, `ANDROID_HOME` deve indicar SDK absoluto existente.
O ambiente filho recebe esse caminho também em `ANDROID_SDK_ROOT`. O projeto
é sempre passado por `-p` absoluto; usa `--max-workers=2`, console plain,
sem daemon persistente solicitado, build cache ou configuration cache.

Não existe flag para ignorar assets reais. Toda invocação força
`librasLivre.permitirAssetsFaltando=false`; a auditoria verifica o valor efetivo.
Não são usados exclusões `-x`, substituição de modelos obrigatórios ou tarefas JVM.
Assets reais, dependências, credenciais, JDK e SDK devem estar preparados pelo
operador. O Gradle ainda pode resolver/baixar suas dependências usuais.

O destino mais restrito dentro da pasta ignored é intencional: os snapshots
contêm modelo selecionado e APKs completos, portanto **não distribuir**. Usam
diretório `0700` e umask `077`; pacotes/fontes de origem são somente lidos.
O script não edita a configuração de ignore para aceitar um destino inadequado.

## 2. Sequência de builds e asserções

As fases positivas solicitam **somente** `:app:assembleDebug` e
`:app:assembleDebugAndroidTest`, além das dependências normais dessas tarefas.
Isso compila o APK principal e o APK de testes; não executa testes JVM/Android.

| Fase | Conteúdo/asserções esperados |
|---|---|
| `A_padrao` | Pacote A selecionado: bytes dos três assets idênticos ao snapshot de entrada; BuildConfig gerado `true` e hash da identidade A; fixtures padrão somente no APK de testes. |
| `B_negativo_transporte` | B diferente de A nos três arquivos e hashes; APK e BuildConfig devem refletir B, não cache/resíduo de A. |
| `sem_pacote` | Propriedade privada omitida; namespace privado ausente no APK e diretório gerado vazio; BuildConfig `false` e string vazia. |
| `A_restaurado` | Novo build com A: bytes/hash de A, flag/hash gerados corretos. |
| `fixtures_substituidas` | A no alvo; fonte androidTest substituída pelo diretório temporário com smoke copiado + sentinel exclusivo. Somente estes dois assets no APK de testes; nenhum deles no alvo. |
| `colisao_bloqueada` | Init script temporário adiciona diretório externo ao source set main; erro não zero deve mencionar a colisão nesse caminho exato. |
| `release_bloqueado` | `:app:assembleRelease --dry-run` com A deve ser recusado pela guarda de task graph, antes de tarefas release. Exige retorno não zero e mensagem específica de release proibido. |
| `restauracao_final_A_padrao` | Em `finally`, recompõe ambos APKs com A e **sem propriedades de fixtures/init de colisão**, mesmo após falha anterior. Confere conteúdo, BuildConfig e fontes padrão restauradas. |

O bloqueio release é uma verificação de **grafo**, não geração de APK release.
Um erro de SDK, autenticação ou dependência não vale como bloqueio correto.
O ciclo A → B → sem pacote → A é incremental: não se executa `clean` nem se
limpam intermediários para esconder resíduos. Excluem-se apenas os dois APKs
de saída e a fonte BuildConfig debug conhecida antes de cada invocação.

### B não é modelo final nem evidência de recusa em runtime

B é gerado deterministicamente a partir dos bytes de A. Começa com offset raiz
impossível e marcador `TFL3`, seguido de payload sintético dependente do SHA de A.
O sidecar mantém contrato de entrada e recebe rótulos não linguísticos, hash do
modelo B e origem sintética. A identidade é experimental, não aprovada, sem
calibração e sem checkpoint real; todos os hashes são recalculados e validados.

Passar pelo contrato de transporte **não** torna o FlatBuffer válido. Nenhum
Interpreter é carregado; estes bytes nunca devem ser apresentados como modelo
treinado, final ou calibrado. O APK B só é arquivado privadamente, nunca instalado.

## 3. ZIP, fontes e BuildConfig

- Allowlist privada exata: modelo, sidecar e identidade, sem arquivos extras no
  namespace `sinal_classifier*`, inclusive em subpastas. ZIPs com nomes repetidos,
  caminhos não canônicos ou bytes divergentes reprovam. Não se extrai o ZIP.
- Com pacote, os três assets são comparados **byte a byte**, além de seus hashes.
  O modelo precisa estar `ZIP_STORED` para o contrato de mmap. Sem pacote, nenhum
  arquivo desse namespace pode permanecer; a mesma ausência vale para androidTest.
- A allowlist do **diretório gerado** também deve ser exata ou vazia quando off.
  A allowlist não restringe os demais assets reais obrigatórios do APK principal.
- As fixtures androidTest padrão são inventariadas por caminho/hash, sem cópia
  das fontes. Cada APK de testes padrão deve conter exatamente esse inventário;
  a fase substituída deve conter exatamente smoke e sentinel. Assets inesperados
  de bibliotecas também reprovam: não há exceção silenciosa à seleção de fontes.
- O sentinel usa UUID fresco. Ele e as fixtures padrão não podem aparecer no
  APK principal, inclusive pelo mesmo basename dentro de subpastas.
- Init Groovy novo por fase audita os source sets efetivos com token fresco,
  valores das propriedades e diretórios canônicos; arquiva JSON e registra o
  token no log. Não modifica nenhum script Gradle do projeto.
- A fonte `androidTest` precisa ser exatamente o diretório selecionado, não soma
  com o padrão. Nas fases padrão, compara-se também o mapa de fontes com o
  primeiro mapa padrão observado. Fontes temporárias residuais são recusadas.
- Na colisão, um arquivo textual sintético com o **nome** do modelo fica em um
  diretório temporário externo a `src`, dentro da saída ignored. O init adiciona
  esse diretório aos assets de `main`. Nenhum modelo/dado privado é escrito em
  `src`; não se altera [Gradle de produção](../mobile-app-companion/app/build.gradle.kts).
- A fonte Java BuildConfig debug é recriada, copiada e validada no snapshot:
  declaração única de flag/hash selecionados, ou `false`/vazio quando off.
  **Não é leitura de DEX nem prova do valor carregado por uma Activity.**
- Ao final, hashes de todos os arquivos em `app/src` e do Gradle do app devem
  permanecer iguais ao inventário inicial. Se outro agente os alterar, reprova e
  registra, mas não sobrescreve arquivos alheios para simular restauração.

## 4. Evidência atual, falhas e restauração

O relatório raiz `evidencia.json` é atualizado atomicamente após cada fase,
com UUID, tempos UTC, origem de A, hashes A/B, inventários de fontes, comandos
absolutos, retorno, log próprio, SHA do log, token/fontes auditadas, hashes dos
snapshots e resultados das asserções. Logs e init scripts ficam na mesma saída
privada. O conteúdo temporário de fixtures/colisão é removido ao sair; ficam
os hashes, scripts de auditoria, logs e APKs arquivados para inspeção.

Antes de cada invocação, os hashes dos artefatos descartados são registrados.
Depois, exige retorno correto e artefatos recriados: não se aceita um APK apenas
porque existe ou porque tem mtime recente. As cópias são conferidas por hash
antes/depois contra a origem, e as asserções de APK usam os snapshots.

Se o comando falhar, não valida nem promove qualquer APK que tenha sobrado.
Se uma asserção falhar após copiar um APK, o snapshot continua marcado como
**não aceito**. Saídas parciais são listadas separadamente por presença/hash.
Um `FALHOU` nunca é convertido em aprovação por um APK velho ou por restauração
posterior bem-sucedida.

Estados por fase: `EM_EXECUCAO`, `APROVADO_CONTEUDO`,
`BLOQUEIO_ESPERADO_CONFIRMADO` ou `FALHOU`; `artefatos_aceitos` só vira true após
todas as verificações positivas. Estado global de sucesso:
`APROVADO_CONTEUDO_NAO_RUNTIME` — não aprovação de entrega/modelo final.

Depois de começar a sequência, o `finally` sempre tenta reconstruir A com
fontes androidTest padrão e valida ambos APKs, inclusive após `KeyboardInterrupt`.
O erro original é preservado; falha da restauração aparece separadamente em
`erro_restauracao`, mantendo causa encadeada. Restauração falha sozinha também
reprova a execução. Falha de preflight antes da sequência não requer reconstrução
porque ainda não houve alteração de artefatos pelo executor.

## 5. Limitações e separação da etapa 4

- Os 23 unitários usam casos sintéticos de ZIP/BuildConfig, comandos, caminhos,
  ciclo e falhas/finally com subprocessos simulados. A execução Gradle separada
  confirmou as oito fases no ambiente local; não certifica outras versões de AGP.
- O layout de artefatos é o atual: APK debug monolítico e BuildConfig Java no
  caminho conhecido. Mudança de flavors/splits/buildDir deve ser revisada; não
  se escolhe silenciosamente um APK via glob. Build redirecionado por symlink
  é recusado. O hash do APK completo pode variar mesmo com pacote A idêntico.
- Use worktree dedicado, sem builds/edições concorrentes. O lock advisory
  serializa apenas este CLI; não bloqueia Android Studio ou comandos externos.
  O script é um verificador, não uma sandbox contra plugins/init globais hostis.
- Na interrupção capturada, sinaliza apenas o grupo do Gradle criado pelo host e
  aguarda o launcher antes do restore. SIGKILL, queda de energia, processo Java
  destacado que sobreviva ou outra interrupção durante o restore não permitem
  garantir restauração. `EM_EXECUCAO`, retorno sem relatório final ou erro de
  restore exigem inspeção manual; não instalar o APK intermediário B.
- Os builds escrevem normalmente em `build/` e caches Gradle. Snapshots adicionais
  ficam **apenas** em `--saida` ignored. Não há limpeza automática de arquivos
  privados arquivados, upload ou publicação.
- Sem `adb`, emulador, instalação, force-stop, prefs, Activity, câmera ou
  inferência. Estas assertions de conteúdo/fontes/BuildConfig **não substituem**
  os [testes runtime da etapa 4](etapa4-persistencia-recusado-2026-09-16.md):
  persistência entre processos, `REAL_EXPERIMENTAL`, recusa de segmento e cleanup
  Android continuam com status e evidências independentes.
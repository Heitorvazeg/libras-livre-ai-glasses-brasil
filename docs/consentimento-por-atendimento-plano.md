# Consentimento por atendimento — plano de implementação

**16/09/2026 — implementado e testado nesta branch, com decisões em aberto ainda
não resolvidas (ver §6).** Escrito antes do código, como
[o plano de confirmação/bateria](confirmacao-e-modo-economia-plano.md), que serviu de
modelo estrutural pra este (mesmo tipo de mudança: um estado novo na máquina de
diálogo, reaproveitando o avatar).

- Branch: `feat/consentimento-por-atendimento`, a partir da `dev` (já com a integração
  do classificador privado e a confirmação ②.5/bateria mergeadas).
- `git status`: um doc modificado (`docs/README.md`), este plano, um teste
  instrumentado novo (`ConsentimentoTest.kt`) e as mudanças de produto/teste listadas
  no §6. Nada commitado ainda.

## 0. O requisito, e por que ele está pendente

`docs/libras-livre-arquitetura.md` §7 (Privacidade e conformidade/LGPD) já registra,
desde a escrita original do documento:

> "Consentimento obtido no início de cada atendimento, junto da própria pessoa surda —
> não uma configuração feita uma vez pelo atendente ou pela instituição em nome de
> todos os visitantes. O fluxo de abertura de sessão deve comunicar de forma clara e
> acessível (em Libras, não só em português) o que o sistema faz antes de captar
> qualquer sinal."

**Isso nunca virou tarefa.** Não há estado de consentimento em `DialogState`, nenhuma
string relacionada em `strings.xml`, nenhuma menção em `riscos-demo-2026-09-13.md`,
`pendencias-entrega-2026-09-14.md` ou em qualquer arquivo de `prontidao-demo/`. O app
vai direto de parear os óculos pra captar sinais — sem perguntar nada. Não é um bug de
UX: é ausência total da funcionalidade, numa área (LGPD, base legal = consentimento do
titular) que não é opcional.

Requisito adicional, trazido pelo usuário nesta sessão e ausente até da arquitetura: a
**recusa precisa ser real** — leva o atendimento pra bilhete/intérprete, sem prejuízo
pra pessoa surda nem para o atendente. Se recusar custasse o atendimento (ex.: travar o
app, exigir reiniciar o pareamento, insistir repetidamente), o consentimento não seria
livre — só marcaria uma caixa.

## 1. Onde encaixa na máquina de estados

Novo estado **①.5 PEDINDO_CONSENTIMENTO**, entre ① AGUARDANDO_SINAL e ② CAPTURANDO_SINAIS:

```
① AGUARDANDO SINAL
        │ "Libras Livre, iniciar" / botão / tecla de volume
        ▼
①.5 PEDINDO CONSENTIMENTO   ← NOVO — câmera continua desligada aqui
        │  Mostra pra pessoa surda, em Libras (mesmo playAvatar() do ⑦/②.5), uma
        │  explicação curta do que o sistema faz. Dois botões igualmente visíveis:
        │  "Aceitar" e "Recusar" (ver §2.2 — NÃO é o modelo de botão único).
        │
        │  ── "Recusar" ──▶ AGUARDANDO_SINAL, com aviso "atendimento segue por
        │                    bilhete ou intérprete" — sem travar, sem repetir a
        │                    pergunta sozinho, sem nenhum dado de sinal captado.
        │
        │  ── "Aceitar" ──▶ ② CAPTURANDO SINAIS, como o "iniciar" já fazia direto
        ▼
② CAPTURANDO SINAIS             ← inalterado daqui pra frente
```

`PEDINDO_CONSENTIMENTO` **não** entra em `Transicoes.ESTADOS_COM_WAKE_WORD` — mesmo
padrão de ③⑤.5⑥⑦: as wake words não têm ação nesse estado, só os dois botões (evita
"Libras Livre, iniciar" acidental decidir consentimento por ninguém).

## 2. Decisões de design (com o porquê)

### 2.1 Apresentação em Libras: avatar reaproveitado, não um recurso novo

Mesmo `playAvatar()` que ②.5 e ⑦ já usam — sem avatar novo, sem pipeline novo. O texto
canônico da explicação (curto, em português, como fonte pro VLibras) é uma constante
nova, não configurável nas configurações de demo: o conteúdo de um aviso de
consentimento não é um parâmetro de UX, é texto que precisa de revisão (jurídica e da
comunidade surda) antes de mudar.

**Risco que o plano de confirmação não tinha, e este tem:** se o avatar não sobe
(Unity morto, WebGL indisponível — já visto no emulador), `playAvatar()` cai pro piso
de acessibilidade existente: a legenda em texto. Pra ②.5/⑦ isso é aceitável (a pessoa
já está no meio de um atendimento que começou com voz). Pra **consentimento**, texto
só em português pode não satisfazer "comunicar de forma clara e acessível, em Libras"
pra quem não lê português fluente — o degradado vira, na prática, voltar a não haver
consentimento acessível. **Decisão em aberto, não técnica:** se o avatar falhar aqui,
o app deve (a) seguir pro texto mesmo assim, avisando o atendente do degradado, ou
(b) recusar prosseguir e orientar bilhete/intérprete diretamente, tratando "avatar
indisponível no momento do consentimento" como equivalente a "consentimento não
obtido de forma acessível". A opção (b) é mais conservadora e mais alinhada ao
espírito do requisito; fica pra o time decidir antes da implementação.

### 2.2 Dois botões iguais, não um principal + um pequeno

②.5 usa "Confirmar" (botão principal, grande) + "Corrigir" (botão pequeno, à parte,
fora do modelo de uma ação só por estado) — e isso foi uma decisão correta *lá*,
porque as duas ações não são simetricamente prováveis (confirmar é o caminho comum,
corrigir é a exceção). **Aqui a simetria importa**: "Aceitar" maior ou mais destacado
visualmente que "Recusar" empurra a decisão, o que é o oposto de consentimento livre.
Os dois botões devem ter o mesmo tamanho, o mesmo peso visual, e nenhum deve ser o
"padrão" pré-selecionado. Isso quer dizer **não** usar `BotaoPrincipal`/`AcaoBotao`
como estão hoje (pensados pra uma ação privilegiada por estado) — precisa de um
componente de tela novo e pequeno (dois botões lado a lado), não uma extensão do
modelo existente.

### 2.3 Sem timeout de auto-aceite — e o timeout de segurança, se existir, recusa

②.5 tem `TETO_CONFIRMACAO_MS` (60s) que **confirma sozinho** se ninguém decidir — bom
lá, porque "a frase mostrada era isso mesmo" tende a ser verdade por padrão. Aqui a
lógica se inverte: silêncio não é consentimento (LGPD exige manifestação
inequívoca). Se existir uma rede de segurança pra não travar o app caso o atendente
largue a tela, ela só pode **recusar por padrão** (voltar a ①, como se "Recusar"
tivesse sido apertado) — nunca assumir aceite. Vale considerar não ter timeout nenhum
aqui (esperar indefinidamente até uma decisão explícita) e deixar essa proteção pro
mecanismo de "atendimento ocioso" que já existe pra ① — precisa decidir qual dos dois
o time prefere; ambos são defensáveis, o que não é defensável é um timeout que aceita.

### 2.4 Câmera continua desligada em ①.5

`ensureCameraActive()` só deve ser chamado depois de "Aceitar" — hoje o "iniciar"
já liga a câmera antes de qualquer captura útil (aquecimento à parte). Colocar
①.5 **antes** do `ensureCameraActive()` (não depois) é o que faz "antes de captar
qualquer sinal" ser verdade de fato, não só na intenção. Custo: um "iniciar" com
óculos que não vão subir a câmera (bateria baixa, permissão pendente) só descobre
isso **depois** de aceitar o consentimento, não antes. Alternativa (chamar
`ensureCameraActive()` antes de ①.5, mostrando "Corrigir"-como-em-②.5 se falhar) foi
descartada: acoplaria o consentimento a uma falha de hardware que não tem nada a ver
com a decisão da pessoa, e adicionaria um terceiro desfecho (nem aceitar nem recusar,
"câmera não sobe") a uma tela que já tem dois.

### 2.5 Recusa não é beco sem saída

Voltar direto a ① com um aviso (`TipoAviso`, mesmo padrão de `BATERIA_OCULOS_BAIXA`)
— não uma tela de erro, não um estado preso, não repetição automática da pergunta.
O aviso é informativo ("atendimento segue por bilhete ou intérprete"), não um bloqueio
como `FalhaCamera`. A pessoa pode, no atendimento seguinte (ou se mudar de ideia no
mesmo atendimento), apertar "iniciar" de novo e ver a pergunta de novo — recusar uma
vez não marca nada permanente.

### 2.6 Reset obrigatório, nunca persistido

A decisão de consentimento é do atendimento atual, do jeito que
`libras-livre-arquitetura.md` §7 já exige pro resto do estado ("nenhum dado... pode
vazar ou influenciar o atendimento seguinte"). Fica em memória no
`DialogOrchestrator` (um campo como `confirmacaoPendente` de ②.5), nunca em
`SharedPreferences`/`ConfiguracoesDemo` — não é uma preferência do aparelho, é uma
decisão da pessoa, válida só enquanto ela está na frente da câmera.

### 2.7 Registrar o evento, sem registrar a pessoa

`onEvento("consentimento", "aceito")` / `onEvento("consentimento", "recusado")` —
mesmo mecanismo que já registra `atendimento_cancelado`, `bateria_baixa` etc. no CSV
do gravador de sessão (quando ligado, 1.9): timestamp e um booleano, sem nome, sem
imagem, sem transcrição. **Tensão a resolver com o time, não decisão técnica:** a
instituição (controladora dos dados, §7) provavelmente precisa de *algum* jeito de
demonstrar que perguntou — mas "sem retenção dos dados da conversa em qualquer
configuração" (mesma seção) é uma linha vermelha explícita. Um evento sem conteúdo,
já do tamanho dos outros que o gravador registra, parece o meio-termo certo; fica
como decisão do time antes de implementar, não deste plano.

## 3. O que muda (arquivo por arquivo, esperado)

| Arquivo | Mudança |
|---|---|
| `libras/dialogo/DialogState.kt` | novo `PEDINDO_CONSENTIMENTO` |
| `libras/dialogo/Transicoes.kt` | `PEDINDO_CONSENTIMENTO` fora de `ESTADOS_COM_WAKE_WORD`; **não** entra no modelo de `BotaoPrincipal` (§2.2) |
| `libras/dialogo/Avisos.kt` | novo `TipoAviso` pro aviso de recusa (bilhete/intérprete) |
| `libras/dialogo/DialogOrchestrator.kt` | intercepta o "iniciar" (wake word/botão/volume) pra ir a ①.5 em vez de ②; `aceitarConsentimento()`/`recusarConsentimento()`; texto canônico da explicação |
| `camera/CameraViewModel.kt` | expõe `aceitarConsentimento()`/`recusarConsentimento()` pro orquestrador, mesmo padrão de `corrigirReconhecimento()` |
| `ui/AvatarScreen.kt` ou tela nova | os dois botões simétricos (§2.2) — provavelmente **não** cabe dentro de `AvatarScreen` como está (que assume `botaoPrincipal` singular); avaliar um parâmetro novo tipo `acoesSimetricas: Pair<Botao, Botao>?` ou uma tela irmã |
| `ui/CameraScreen.kt` | `rotuloDoEstado` cobrindo o estado novo; faixa de estado mostrando "Pedindo consentimento" |
| `res/values/strings.xml` | texto do consentimento, rótulos dos dois botões, aviso de recusa |
| Testes | `TransicoesTest` (regra pura); um teste instrumentado no padrão de `ConfirmacaoReconhecimentoTest` — aceitar segue pra ②, recusar volta a ① sem captura nenhuma |

## 4. Riscos e o que fica pendente de validação

- **O texto do consentimento em si não é uma decisão de engenharia.** Precisa de
  revisão jurídica (o que precisa estar dito pra valer como consentimento informado
  sob LGPD) e da comunidade surda (se a glosa em Libras comunica o mesmo que o
  português pretende) antes de qualquer implementação. Este plano não propõe o texto.
- **Degradação do avatar no momento do consentimento (§2.1)** é uma decisão de produto
  em aberto, não só um detalhe técnico — repetido aqui porque é o risco mais sério do
  plano.
- **Sem teste de UX real.** Só teste com atendentes e pessoas surdas reais confirma se
  a pergunta é compreendida e se "Recusar" de fato não é sentido como custoso — a
  mesma ressalva que `libras-livre-arquitetura.md` §10 já registra em geral.
- **"Bilhete ou intérprete" é processo institucional, fora do app.** O app só precisa
  não impedir esse caminho nem fingir que o atendimento continua normalmente sem o
  reconhecimento de sinais — ele não implementa bilhete nem aciona intérprete.
- **Registro de auditoria do consentimento (§2.7)** depende de decisão do time/jurídico
  sobre o que a instituição precisa conseguir demonstrar.
- **Fechar a tela do avatar ("X") durante ①.5 esconde "Aceitar"/"Recusar" sem decidir
  nada** — o mesmo gap que `confirmacao-e-modo-economia-plano.md` §1.6 já registrava
  pra "Corrigir" em ②.5, herdado aqui. Achado numa revisão desta sessão, não corrigido
  (mesmo tratamento do original: gap de UX pequeno, fora do escopo desta revisão).

## 5. O que foi implementado (16/09/2026)

Tudo do §3 saiu como planejado, com um ajuste: `AvatarScreen.kt` **não** precisou de
tela irmã nem de um tipo novo tipo `Pair<Botao, Botao>` — os dois botões simétricos
reaproveitam o `CapturePill` que já existia (mesmo componente do "Corrigir" de ②.5,
usado duas vezes lado a lado com `Modifier.weight(1f)`), condicionados a
`pedindoConsentimento: Boolean`, que também substitui `botaoPrincipal` inteiro nesse
estado (não só o esconde).

- `DialogOrchestrator.beginSignSession()` foi dividido: primeiro `pedirConsentimento()`
  (mostra o avatar, não liga câmera), depois `aceitarConsentimento()`/
  `recusarConsentimento()` — a última chama `ensureCameraActive()` só depois de aceitar.
- Três avisos novos ligados via callback (mesmo padrão de `onFalhaCamera`/
  `onAvatarUnavailable`): `onConsentimentoPedido` (limpa avisos do atendimento
  anterior), `onConsentimentoRecusado`, `onConsentimentoSemLibras`.
- **Sete arquivos de teste existentes precisaram de ajuste** — toda vez que um turno
  começa pela primeira vez num atendimento, um teste que clicava "iniciar" e esperava
  "Capturando" direto agora fica preso em ①.5 sem ninguém apertar "Aceitar". Corrigido
  em `FluxoOnda1Test`, `FluxoOnda2Test`, `FluxoOnda4Test`, `FluxoCompletoTest`,
  `ConfirmacaoReconhecimentoTest` e `DiagnosticoClassificadorTelaCompletaTest` — todos
  passam a aceitar o consentimento antes de esperar a captura. "Repita"/"Encerrar
  agora" dentro do mesmo atendimento **não** precisaram de ajuste (consentimento é por
  atendimento, não por turno — não passam por ①.5 de novo).
- Novo teste instrumentado `ConsentimentoTest.kt`: confirma que "Recusar" nunca mostra
  "Capturando" (câmera nunca liga) e mostra o aviso de bilhete/intérprete; confirma que
  "Aceitar" liga a câmera e segue pro fluxo normal.

**Achado numa autorrevisão antes do commit:** `aceitarConsentimento()` só saía de
PEDINDO_CONSENTIMENTO depois de `ensureCameraActive()` (suspend) resolver — um duplo
toque rápido em "Aceitar" passava pela mesma checagem de estado duas vezes e podia
disparar `ensureCameraActive()`/`iniciarCaptura()` em duplicata. Corrigido com uma
guarda dedicada (`ligandoCameraAposConsentimento`), já que `startingSignSession`
sozinho não servia (fica true desde antes do consentimento ser pedido). Aproveitei
pra também fazer `recusarConsentimento()` incrementar a geração, invalidando um
`aceitarConsentimento()` concorrente pelo mesmo mecanismo que `cancelarAtendimento()`/
`onBateriaBaixa()` já usam — não só uma checagem de estado local.

**Evidência:** `:app:testDebugUnitTest` — 178 testes, 0 falhas, 1 ignorado (guarda
pré-existente, não relacionada). `:app:connectedDebugAndroidTest` completo — 54
testes, 0 falhas reais (1 falha por timeout de sessão não reproduzida isolada, mesmo
padrão de disputa de recursos do emulador já visto nesta máquina em sessões
anteriores), 8 pulados esperados (build privado / opt-in de vídeo / WebGL ausente).

**O que continua em aberto, sem decisão minha:**

- `DialogOrchestrator.TEXTO_CONSENTIMENTO_PLACEHOLDER` é exatamente isso — um texto
  provisório, marcado no código como não revisado, só para o fluxo ser exercitável.
  Não usar em atendimento real.
- §2.1 (avatar indisponível no momento do consentimento): implementei a opção mais
  simples — degrada pra legenda em texto (mesmo mecanismo de sempre) e acende
  `TipoAviso.CONSENTIMENTO_SEM_LIBRAS` pro atendente decidir. **Não** implementei a
  opção mais conservadora (bloquear e orientar bilhete/intérprete direto) — ainda é
  uma decisão de produto, não tomei por conta própria.
- §2.3 (timeout): implementei **sem nenhum timeout** em ①.5 — se ninguém decidir, o
  app espera indefinidamente (só "Cancelar atendimento" sai de lá). Sem rede de
  segurança, ao contrário de ②.5.
- §2.7 (registro de auditoria): o evento `consentimento=aceito/recusado` vai pro
  gravador de sessão (CSV, quando ligado) via `onEvento`, mesmo mecanismo de outros
  eventos sem conteúdo pessoal. Não validei com jurídico se isso basta pra instituição
  demonstrar que perguntou.

## 6. Referências

- `docs/libras-livre-arquitetura.md` §7 — o requisito original, nunca implementado.
- `docs/confirmacao-e-modo-economia-plano.md` — modelo estrutural deste plano; §1.3
  em particular, sobre por que ②.5 usa botão principal + botão pequeno (o oposto da
  decisão do §2.2 aqui, e por quê).
- `libras/dialogo/DialogOrchestrator.kt`, `Transicoes.kt`, `Avisos.kt` — onde as
  mudanças da tabela do §3 entram.

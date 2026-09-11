# Contextualização glosa → português — seq2seq local (`.tflite`)

> Plano de implementação do estágio descrito em
> `docs/libras-livre-arquitetura.md` §4.4 ("Contextualização (sinais →
> linguagem natural)"), que já prevê literalmente este documento: *"a
> arquitetura deve prever, desde já, a evolução para um modelo
> sequência-a-sequência (ex.: encoder-decoder)"*. Não é um estágio novo —
> é a substituição de um **placeholder explícito que existe hoje no
> código**: `DialogOrchestrator.kt:131`, `palavrasReconhecidas.joinToString(" ")`.
>
> Também fecha (ou aposenta) o item `combinacoesConhecidas` que está no
> checklist de `mobile-app-companion/README.md:170` e que
> `docs/sign-boundary-detector-plano.md` §5.3 e
> `docs/orquestracao-dialogo-audio-plano.md` §6.5 punçam um pro outro sem
> nunca implementar.

---

## 0. O que este documento decide — e o que não decide

**Decide:**

1. A **interface** (`GlossContextualizer`) e o ponto de plugue exato no
   `DialogOrchestrator` — implementáveis hoje, sem modelo nenhum (§3).
2. A **estratégia de corpus**, que é o problema real deste plano: não
   existe corpus paralelo glosa→PT de Libras em tamanho útil (§5).
3. A **forma do modelo** (T5 minúsculo com vocabulário próprio, não
   T5-small genérico) e por quê (§6).
4. Como **quantização e delegação se relacionam** — e por que "int8 +
   GPU" é, na prática, escolher um dos dois (§6.2, §7).
5. As **guardas de segurança** que precisam ser estruturais, não
   promessas — contexto de saúde/atendimento não tolera alucinação (§8).

**Não decide (e não pode, hoje):**

- O modelo em si — não existe. Por isso §3 vem primeiro: o app fica
  pronto pra recebê-lo antes de ele existir, mesmo padrão já usado pro
  `SignClassifier`/`PlaceholderSignClassifier`
  (`libras/reconhecimento/SignClassifier.kt`) e pro
  `WakeWordDetector`/`SttEngine`.
- O vocabulário final — `docs/vocabulario-mvp-proposta.md` ainda é
  rascunho, pendente da Associação de Surdos de Goiânia. O corpus (§5)
  depende dele, mas a interface (§3) não.

---

## 1. Objetivo e recorte

O `LandmarkPipeline` entrega **uma palavra por boundary**
(`SignBoundaryDetector`), e o `DialogOrchestrator` acumula isso em
`palavrasReconhecidas`. O que sai no fim da sessão é um **glossário**: uma
lista de termos do vocabulário fechado, na ordem em que foram sinalizados.

Isso não é português. Libras tem ordem, marcação e estrutura próprias, e
não existe correspondência linear entre a sequência de sinais e a frase
equivalente em PT-BR. Hoje o app fala o glossário cru:

```
glosas:  [eu, dor, cabeça]      → TTS diz "eu dor cabeça"
alvo:                             "estou com dor de cabeça"

glosas:  [banheiro, onde]       → TTS diz "banheiro onde"
alvo:                             "onde fica o banheiro?"

glosas:  [eu, querer, marcar, vacina]
alvo:                             "eu gostaria de marcar uma vacina"
```

**Recorte:** transformar essa lista em uma frase falável em PT-BR,
localmente, **uma vez por sessão**, entre o "Libras Livre, encerrar" e o
TTS.

### 1.1 A restrição que muda todas as decisões de engenharia

Este modelo roda **uma vez por sessão**, sobre 2 a 8 tokens de entrada,
produzindo ~5 a 15 tokens de saída. Não é inferência por frame como o
`LandmarkExtractor`/`SignClassifier`.

Consequências, que voltam em §6.3 e §7:

- O custo total por sessão é minúsculo em FLOPs — o gargalo não é
  throughput, é **latência percebida de uma única invocação**, incluindo
  qualquer custo de inicialização que não esteja amortizado.
- Um delegate de GPU/NPU cuja criação custa centenas de milissegundos pode
  custar **mais que a inferência inteira** se for criado por sessão. Se
  for usado, o intérprete precisa nascer com o app e viver até o
  `dispose()`, como o `SignClassifier` já faz
  (`LandmarkPipeline.kt`, comentário do `stop()`/`dispose()`).
- Decodificação autoregressiva = N invocações pequenas e sequenciais, não
  uma grande. Isso é o pior formato possível pra GPU (§7).

---

## 2. Onde entra no fluxo

```
 sessão de sinais (estado ② CAPTURANDO_SINAIS)
   LandmarkPipeline → onRecognized(palavra) → palavrasReconhecidas.add()   [N vezes]
              │
   "Libras Livre, encerrar"
              ▼
   DialogOrchestrator.endSignSession()
     landmarkPipeline.endSession()            ← espera classificações em voo
     ┌──────────────────────────────────────┐
     │  AQUI: GlossContextualizer           │ ← este documento
     │  List<String> → String               │
     └──────────────────────────────────────┘
     speaker.speakAndAwait(frase)             ← estado ③ FALANDO
              ▼
   TTS → saída de áudio → A2DP → óculos
```

O caminho até os óculos **não muda**: quem roteia A2DP↔HFP é o
`AudioSessionManager`, e o `Speaker` já entrega no dispositivo ativo.
Este estágio é texto→texto, anterior a tudo isso.

### 2.1 O diff no `DialogOrchestrator`

```kotlin
// endSignSession(), hoje (DialogOrchestrator.kt:131-134):
      val frase = palavrasReconhecidas.joinToString(" ")
      palavrasReconhecidas.clear()
      if (frase.isNotBlank()) {
        speaker.speakAndAwait(frase)
      }

// depois:
      val glosas = palavrasReconhecidas.toList()
      palavrasReconhecidas.clear()
      if (glosas.isNotEmpty()) {
        val resultado = contextualizer.contextualize(glosas)
        Log.i(TAG, "glosas=$glosas -> \"${resultado.texto}\" (${resultado.origem})")
        speaker.speakAndAwait(resultado.texto)
      }
```

É só isso. O `DialogOrchestrator` continua sendo o dono da costura
sinal→frase (como o header dele já declara) — ele passa a **delegar** a
resolução em vez de fazer `joinToString`.

### 2.2 Precisa de um estado novo (`CONTEXTUALIZANDO`)?

**Recomendação: não, no começo.** O `setState(DialogState.FALANDO)` já
acontece em `endSignSession()` *antes* do `scope.launch`, então a wake
word já está pausada durante a contextualização e nenhuma transição fica
inconsistente. Adicionar um estado ⑧ custaria mexer em
`WAKE_WORD_ACTIVE_STATES`, na UI e no `DialogState` inteiro.

**Quando reconsiderar:** se a medição da Fase 5 mostrar latência > ~1s.
Aí o usuário fica olhando pra um estado que diz "FALANDO" sem ninguém
falando, e vale o estado próprio só pra UI poder mostrar "montando a
frase…".

---

## 3. A interface — implementável hoje, sem modelo

Mesmo padrão do `SignClassifier.kt`: a interface destrava o fio inteiro
antes do `.tflite` existir, e a troca depois não mexe em quem chama.

**Onde vive:** `libras/contextualizacao/` — subpacote novo, irmão de
`libras/reconhecimento/` e `libras/audio/`, seguindo a reorganização do
commit `8149651`.

```kotlin
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.contextualizacao

/**
 * Resultado da contextualização. `origem` não é enfeite: é o que permite medir em campo
 * quantas sessões realmente usaram o modelo e quantas caíram no fallback (§8, §10).
 */
data class Contextualizacao(
    val texto: String,
    val origem: Origem,
) {
  enum class Origem { MODELO, TEMPLATE, PASSTHROUGH }
}

interface GlossContextualizer {
  /**
   * Transforma o glossário da sessão numa frase falável em PT-BR.
   *
   * NÃO lança: o usuário já sinalizou a sessão inteira: uma falha aqui não pode comer o
   * resultado. Implementações devem tratar erro internamente e degradar (ver
   * [GuardedGlossContextualizer]). `suspend` porque a implementação real faz inferência.
   */
  suspend fun contextualize(glosas: List<String>): Contextualizacao

  fun close()
}
```

### 3.1 As três implementações

| Implementação | O que faz | Quando |
|---|---|---|
| `PassthroughGlossContextualizer` | `glosas.joinToString(" ")` | Fase 0 — **comportamento idêntico ao de hoje**, então a Fase 0 é um refactor de risco zero |
| `TemplateGlossContextualizer` | Regras/tabela sobre o vocabulário fechado | Fase 1 — baseline de medição **e** fallback permanente |
| `TfliteGlossContextualizer` | Encoder-decoder quantizado | Fase 4+ |

```kotlin
/** Fase 0 — exatamente o que DialogOrchestrator.kt:131 faz hoje, extraído pra trás da interface. */
class PassthroughGlossContextualizer : GlossContextualizer {
  override suspend fun contextualize(glosas: List<String>) =
      Contextualizacao(glosas.joinToString(" "), Contextualizacao.Origem.PASSTHROUGH)

  override fun close() {}
}
```

### 3.2 A guarda — estrutural, não uma promessa

O ponto mais importante do desenho. Um seq2seq pode inventar, omitir ou
**inverter** conteúdo (§8.1). Em contexto de atendimento/saúde isso não é
um bug de qualidade, é um bug de segurança. Então a guarda não fica
"dentro do modelo": fica num decorator que o `DialogOrchestrator` recebe.

```kotlin
/**
 * Envolve um contextualizador e só deixa o resultado passar se ele satisfizer as guardas de
 * §8.1. Caso contrário, cai no [fallback] — que no limite é o passthrough, ou seja, o
 * comportamento de hoje. Nunca lança, nunca fica sem resposta.
 */
class GuardedGlossContextualizer(
    private val primario: GlossContextualizer,
    private val fallback: GlossContextualizer,
    private val lexico: LexicoGlosas,     // glosa -> formas aceitáveis em PT (§5.4)
    private val timeoutMs: Long = 1_500L,
) : GlossContextualizer {

  override suspend fun contextualize(glosas: List<String>): Contextualizacao {
    val candidato =
        runCatching { withTimeout(timeoutMs) { primario.contextualize(glosas) } }
            .onFailure { Log.w(TAG, "contextualizador primário falhou — fallback", it) }
            .getOrNull()

    if (candidato != null && lexico.cobreTodoConteudo(glosas, candidato.texto)) return candidato
    return fallback.contextualize(glosas)
  }

  override fun close() {
    primario.close()
    fallback.close()
  }
}
```

`cobreTodoConteudo` é a verificação de §8.1: toda glosa reconhecida
precisa aparecer no texto de saída em alguma forma aceitável, e negação
não pode sumir. É uma checagem barata sobre uma tabela pequena — é
justamente o tipo de coisa que o vocabulário fechado do MVP torna viável.

### 3.3 Montagem (no `CameraViewModel`, onde o `DialogOrchestrator` nasce)

```kotlin
val contextualizer = GuardedGlossContextualizer(
    primario = TfliteGlossContextualizer(context),   // ou Template, ou Passthrough
    fallback = TemplateGlossContextualizer(lexico),
    lexico = lexico,
)
```

E `dispose()` do ViewModel chama `contextualizer.close()`, como já faz com
o pipeline.

---

## 4. Por que um seq2seq — e por que a tabela de regras mesmo assim

**A ressalva honesta:** pro vocabulário do MVP (`vocabulario-mvp-proposta.md`:
~20 sinais na Camada 1, ~40-60 somando as três camadas) e sessões de 2-8
sinais, uma tabela de regras cobre uma fração grande dos casos reais de
balcão, com custo perto de zero, zero risco de alucinação e zero MB de
APK. Um seq2seq de 8M de parâmetros treinado em corpus sintético é
*provavelmente* pior que 200 linhas de regras nesse recorte específico.

**Por que fazer o seq2seq assim mesmo:** o recorte fechado é explicitamente
temporário (`libras-livre-arquitetura.md` §10: "o recorte de vocabulário
fechado e enunciados isolados do MVP é uma escolha deliberada para tornar
o problema tratável no prazo do programa"). A tabela não escala — ela
explode combinatoriamente com o vocabulário e não generaliza pra nenhuma
combinação não prevista. O modelo é o caminho pro vocabulário aberto, e o
trabalho de infraestrutura (interface, export, loop de decodificação,
delegação, guardas) é o mesmo independente do tamanho do modelo.

**A conclusão prática, que muda a ordem das fases:** a tabela de regras
**não é descartada, é pré-requisito**. Ela é (a) o baseline sem o qual não
existe como afirmar que o modelo melhorou alguma coisa, e (b) o fallback
permanente de §3.2. Por isso a Fase 1 vem antes de qualquer treino, e o
critério de aceite do modelo em §10 é *"bate o template no conjunto
humano"* — não *"tem BLEU X"*.

---

## 5. O corpus — o problema real deste plano

Não existe corpus paralelo glosa→português de Libras, público e anotado,
em tamanho que treine um encoder-decoder. Isto é o que decide se este
plano vive ou morre; o resto é engenharia conhecida.

### 5.1 VLibras invertido (recomendado)

O projeto **já depende** de um tradutor PT-BR → glosa: o
`vlibras-translator-api`, na branch
`feat/Empacota-player-vlibras-em-webview-nativa`
(`docs/vlibras-webview-plano.md`, citado em
`orquestracao-dialogo-audio-plano.md` §6.5 e no glossário §7 daquele
documento). Ele existe pro sentido inverso — texto do atendente → glosa →
avatar.

**É exatamente a função inversa da que precisamos.** Então:

```
corpus PT-BR (frases de atendimento)
        │
        ▼  vlibras-translator-api  (PT → glosa, determinístico)
   pares (glosa, PT)
        │
        ▼  inverte a direção
   treino: glosa → PT
```

Isso é *back-translation* clássica, e tem duas vantagens específicas
aqui: reusa um componente que o time já vai integrar de qualquer jeito, e
gera volume arbitrário a partir de texto PT-BR simples (que é abundante).

**As duas ressalvas, que precisam estar no documento e não só na cabeça
de quem treina:**

1. O modelo aprende a **inverter um sistema de regras**, não a língua
   real. Ele herda todas as escolhas e todos os vieses do VLibras. Onde o
   VLibras gera uma glosa que um sinalizante não usaria, o modelo aprende
   a mapear de volta uma construção que nunca vai aparecer na entrada real.
2. A entrada real do modelo **não vem do VLibras** — vem do
   `SignClassifier`, cujo output é o rótulo cru do dataset de treino
   (minúsculo, sem marcação: `banheiro`, `ruim`, `cinco`). O corpus tem
   que ser normalizado pra **esse** formato, não pro formato de glosa do
   VLibras. Essa normalização (maiúsculas, marcadores, classificadores) é
   um passo obrigatório do pipeline de corpus, não um detalhe.

### 5.2 Restrição ao vocabulário e ruído deliberado

Filtrar o corpus sintético pros pares cuja glosa cai inteira dentro do
vocabulário do MVP. E então — isto é importante — **injetar o ruído que o
modelo vai encontrar de verdade**:

- **Glosa faltando.** `DialogOrchestrator.onSignRecognitionFailed()`
  registra o log e segue: *"Um segmento da sessão não foi reconhecido —
  seguindo o resto da sessão"*. E `LandmarkPipeline.classificarSegmentoAtual()`
  descarta silenciosamente segmentos com menos de 5 frames. Ou seja: **o
  glossário chega com buracos**, por construção. Treinar com dropout
  aleatório de glosas.
- **Glosa trocada.** O classificador tem erro (95,6% medido em condições
  de dataset — não em balcão, com pessoa nova). Substituir glosas por
  vizinhas confundíveis.
- **Ordem.** Pequenas permutações, porque a segmentação automática pode
  emitir dois sinais próximos fora de ordem.

Sem isso o modelo é treinado numa distribuição que nunca vai ver.

### 5.3 Conjunto humano — só teste, nunca treino

Um conjunto pequeno (alvo: 200-500 pares) de glossários reais anotados
com a tradução que uma pessoa surda/intérprete considera correta, vindo
do mesmo contato da Associação de Surdos de Goiânia que já é dependência
do vocabulário (`vocabulario-mvp-proposta.md`).

**Este é o único conjunto que vale como evidência.** Métrica medida em
corpus sintético mede a capacidade de inverter o VLibras, não de traduzir
Libras. Mesma lógica do signer-independent de
`libras-livre-arquitetura.md` §4.3: nunca validar na distribuição que
gerou o treino.

### 5.4 `LexicoGlosas` — o artefato que serve a três coisas

Uma tabela `glosa → formas aceitáveis em PT` (`dor → [dor, dói, doendo,
doloroso]`), construída à mão sobre o vocabulário fechado. É pequena e
dá pra fazer hoje. Ela é usada por:

1. `TemplateGlossContextualizer` (§4) — o baseline/fallback;
2. `GuardedGlossContextualizer.cobreTodoConteudo` (§3.2) — a guarda;
3. A métrica de *content-word recall* (§10) — a métrica de segurança.

Um artefato, três usos. É o item de maior retorno por esforço deste plano
inteiro, e não depende de modelo, corpus nem GPU.

**Onde vive:** `app/src/main/assets/lexico-glosas.json`, versionado junto
do vocabulário.

---

## 6. O modelo

### 6.1 T5 pequeno, mas com vocabulário próprio

A intuição "usar um T5 muito otimizado" está certa; o detalhe que decide
o tamanho não é a profundidade, é a **tabela de embeddings**.

`t5-small`: ~60M parâmetros, `d_model=512`, `vocab=32128`. Só a matriz de
embedding (compartilhada entre encoder, decoder e a projeção de saída em
T5 v1.0) é `32128 × 512 ≈ 16,4M` — mais de um quarto do modelo, dedicado
a um vocabulário de propósito geral. Num domínio onde a **entrada tem ~60
tokens possíveis** (o vocabulário fechado) e a saída é um subconjunto
pequeno do português de atendimento, isso é desperdício quase puro.

**Trilha A (recomendada) — T5 minúsculo treinado do zero, vocabulário
próprio:**

| Item | Valor |
|---|---|
| Tokenizer | SentencePiece treinado no corpus, `vocab ≈ 4.000` |
| Config | `d_model=256`, `d_ff=1024`, 4 camadas enc + 4 dec, 4 cabeças |
| Parâmetros | ~8M (embeddings ~1M, encoder ~3,1M, decoder ~4,2M) |
| `.tflite` int8 | ~8-10 MB |

Com corpus sintético em domínio fechado, um modelo desse tamanho não está
subdimensionado — está dimensionado pro problema. E torna a discussão de
quantização/delegação quase acadêmica, o que é um resultado bom.

**Trilha B (comparação) — `ptt5-small` (unicamp-dl) fine-tunado.** Traz
conhecimento de português de verdade, o que ajuda na fluência e em
palavras fora do corpus sintético. Custa ~60M de parâmetros (~60MB em
int8) e um tokenizer de propósito geral. *Confirmar disponibilidade e
licença do checkpoint antes de planejar em cima dele.*

**Ordem:** construir a Trilha A primeiro — o pipeline de export/decode/
guarda (§6.3, §3.2) é idêntico nas duas, e a A itera em minutos. A B
entra como comparação se a A não bater o template em §10.

### 6.2 Quantização — e o conflito com a GPU

| Modo | Pesos / ativações | Precisa de dataset representativo | Roda em | Ressalva |
|---|---|---|---|---|
| fp32 | fp32 | não | CPU, GPU | baseline de qualidade |
| fp16 | fp16 / fp32 | não | CPU, **GPU** | metade do tamanho, perda desprezível |
| **int8 dynamic-range** | int8 / fp32 | **não** | CPU (XNNPACK) | melhor razão ganho/risco; **recomendado começar aqui** |
| int8 full-integer | int8 / int8 | **sim** | CPU, NPU | maior perda; exige calibração |

**O ponto que precisa ficar explícito:** o delegate de GPU aceita modelos
quantizados, mas os executa **dequantizando os pesos pra ponto flutuante
internamente**. O int8 ali rende tamanho de arquivo, não velocidade. Ou
seja, "int8 **e** GPU" não somam — são duas pistas:

- **pista CPU:** int8 dynamic-range + XNNPACK multi-thread;
- **pista GPU:** fp16.

A decisão entre as duas é de **medição** (Fase 5), não de preferência — e
§7 explica por que a aposta a priori é na pista CPU pra este modelo
específico.

### 6.3 Decodificação autoregressiva — o trabalho real de export

TFLite não tem `model.generate()`. Um encoder-decoder exportado ingênuo
como um único grafo ou não converte, ou converte num grafo com laço
dinâmico frágil. O caminho que funciona:

**Exportar duas assinaturas e dirigir o laço do Kotlin.**

```
assinatura "encode":
   in : input_ids [1, S]
   out: encoder_hidden [1, S, d_model]

assinatura "decode_step":
   in : decoder_input_ids [1, 1], encoder_hidden, kv_cache_in
   out: logits [1, 1, V], kv_cache_out
```

```kotlin
// TfliteGlossContextualizer — esboço do laço guloso.
private fun gerar(idsEntrada: IntArray): IntArray {
  val enc = interpreter.runSignature(mapOf("input_ids" to idsEntrada), saidasEnc, "encode")
  var token = ID_INICIO
  var cache = cacheVazio()
  val saida = mutableListOf<Int>()
  repeat(MAX_TOKENS_SAIDA) {                       // limite fixo, definido no export
    val r = interpreter.runSignature(
        mapOf("decoder_input_ids" to intArrayOf(token), "encoder_hidden" to enc, "kv_cache_in" to cache),
        saidasDec, "decode_step")
    token = argmaxRestrito(r.logits)               // decodificação restrita — §8.1
    cache = r.kvCacheOut
    if (token == ID_FIM) return@repeat
    saida.add(token)
  }
  return saida.toIntArray()
}
```

Três consequências que precisam entrar no orçamento da Fase 4:

1. **`MAX_TOKENS_SAIDA` é fixado no export**, não em runtime — o KV cache
   tem shape estático. Escolher com folga sobre o percentil alto do
   corpus (frases de atendimento são curtas; ~24 tokens deve sobrar).
2. **N invocações sequenciais**, uma por token. É isso que estraga a GPU
   (§7).
3. **Greedy, não beam.** Beam search multiplica invocações por largura de
   feixe e ganha pouco em frases de 5-10 palavras. Se a qualidade exigir,
   beam vai no laço Kotlin — não muda o `.tflite`.

`argmaxRestrito` é o gancho de decodificação restrita: mascarar logits
fora do léxico esperado antes do argmax. Em vocabulário fechado isso é
barato e elimina uma classe inteira de alucinação — ver §8.1.

**Onde vive o treino:** `computer-vision-model/contextualizacao/`, irmão
de `computer-vision-model/treino/` (corpus, treino, export, avaliação).

---

## 7. Delegação — o que a estrutura precisa prever

A intuição está certa em geral ("senão fica muito pesado pra CPU do
celular"), mas ela vale pro **`SignClassifier`** (GCN, roda por sinal,
sobre sequências de dezenas de frames) muito mais do que pra **este**
modelo. Vale registrar a diferença, porque ela inverte a prioridade.

### 7.1 A ordem honesta

1. **XNNPACK (CPU, multi-thread) — a baseline, e provavelmente
   suficiente.** ~8M de parâmetros, ~15 invocações de um passo de decoder
   por sessão, uma vez por sessão. Medir antes de otimizar. É plausível
   que o total caiba em dezenas de ms num celular mediano — nesse caso
   toda a discussão de delegate é otimização de algo que já é invisível.

2. **GPU delegate — a apostar contra, para *este* modelo.** Dois motivos
   estruturais, não de implementação:
   - **Custo de criação do delegate** (compilação de shaders, upload de
     pesos) na ordem de centenas de ms. Numa inferência por sessão, isso
     só se paga se o intérprete viver pelo app inteiro — o que é possível
     (mesma vida do `SignClassifier`), mas então o custo aparece no
     *startup* do app.
   - **O laço autoregressivo é o pior formato pra GPU:** N kernels
     minúsculos com sincronização CPU↔GPU a cada passo. A latência de
     sincronização domina o cálculo.
   - Além disso, §6.2: usar GPU custa desistir do int8.

3. **NNAPI — não construir em cima.** Depreciada no Android 15 (API 35);
   `targetSdk = 36` no `app/build.gradle.kts`. O delegate NNAPI do TFLite
   seguiu junto. É um beco sem saída.

4. **Delegates de fornecedor (Qualcomm QNN, MediaTek NeuroPilot) via
   LiteRT** — é pra onde o ecossistema aponta depois da NNAPI, mas cada um
   é uma integração separada, e o celular companheiro dos óculos **não é
   hardware fixo** (é o aparelho do usuário/instituição). Só faz sentido
   se a medição mostrar necessidade real e houver um aparelho-alvo
   definido.

### 7.2 O que isso pede da estrutura

Não escolher um acelerador no código — tornar a escolha **configuração**,
com fallback e medição embutidos:

```kotlin
enum class Acelerador { CPU_XNNPACK, GPU, AUTO }

/**
 * Cria o Interpreter tentando os aceleradores em ordem, caindo pro próximo quando o
 * delegate não existe no aparelho (GPU indisponível não é erro — é o caso comum).
 * Registra qual pegou: sem isso, uma medição em campo não é interpretável.
 */
class InterpreterFactory(private val preferencia: Acelerador = Acelerador.AUTO) { /* ... */ }
```

E o intérprete nasce **uma vez** e vive até o `close()`, nunca por sessão.

### 7.3 Dependências de build

Ainda não existem no `gradle/libs.versions.toml` (hoje só
`mediapipe-tasks-vision`). A adicionar na Fase 4:

```toml
litert       = { group = "com.google.ai.edge.litert", name = "litert",        version.ref = "litert" }
litert-gpu   = { group = "com.google.ai.edge.litert", name = "litert-gpu",    version.ref = "litert" }
```

> LiteRT é o nome atual do runtime do TFLite. O `.tflite` é o mesmo
> formato; a API Java é compatível. Confirmar a versão corrente no
> momento da Fase 4 — e confirmar se o `tasks-vision` do MediaPipe já
> traz um runtime conflitante no mesmo APK antes de somar as duas.

E o `.tflite` não pode ser comprimido no APK:

```kotlin
androidResources { noCompress += "tflite" }
```

---

## 8. Riscos em aberto

### 8.1 Alucinação em contexto de saúde — o risco que domina

O cenário de uso é atendimento, possivelmente posto de saúde
(`vocabulario-mvp-proposta.md`). Um seq2seq pode inventar conteúdo,
omitir conteúdo ou **inverter o sentido**. Os três modos, com o que a
guarda de §3.2 faz sobre cada um:

| Falha | Exemplo | Mitigação |
|---|---|---|
| **Negação perdida** | `[eu, não, querer, vacina]` → *"eu quero a vacina"* | Regra dura: glossário com glosa de negação e saída sem marca de negação ⇒ **rejeita, cai no fallback**. Sem exceção. |
| **Conteúdo omitido** | `[dor, cabeça]` → *"estou com dor"* | `cobreTodoConteudo`: toda glosa precisa de forma correspondente na saída (`LexicoGlosas`, §5.4) |
| **Conteúdo inventado** | `[banheiro]` → *"onde fica o banheiro feminino?"* | Decodificação restrita (§6.3) + revisão humana na Fase 6 |

A assimetria que justifica ser conservador: falar o glossário cru
("banheiro onde") soa robótico mas **não mente**. Falar uma frase fluente
e errada mente com confiança. O fallback tem que ser o default em
qualquer dúvida — o `GuardedGlossContextualizer` existe pra tornar isso
uma propriedade do código, não uma intenção.

### 8.2 O corpus sintético inverte um sistema de regras, não a língua

Ver §5.1. Só o conjunto humano (§5.3) mede o que importa. **Nenhum
número medido em corpus sintético deve aparecer em apresentação ou
documentação do projeto sem essa qualificação** — mesma disciplina que
`vocabulario-mvp-proposta.md` aplica aos 3 articuladores da V-LIBRASIL.

### 8.3 Erro do classificador entra aqui

O modelo recebe a saída de um classificador imperfeito, com buracos por
construção (§5.2). Um seq2seq fluente sobre entrada errada produz uma
frase *convincente* e errada — é pior que passthrough, que ao menos expõe
o erro. Reforça §8.1.

### 8.4 Latência percebida

Entre o "encerrar" e a primeira sílaba do TTS. Orçamento proposto:
**≤ 300ms p50, ≤ 800ms p95**; o `timeoutMs` de §3.2 (1,5s) é o teto
absoluto antes do fallback. A medir na Fase 5 — não há hardware pra isso
neste ambiente.

### 8.5 Dois `.tflite` no mesmo app

Este modelo soma ao `.tflite` do GCN (`sign-boundary-detector-plano.md`
§5.4), aos modelos do MediaPipe em `assets/` e ao runtime. Somar tamanho
de APK e pico de memória **antes** de escolher entre Trilha A (~10MB) e
Trilha B (~60MB) — a diferença pode decidir sozinha.

### 8.6 Uma frase por sessão é o recorte certo?

A sessão inteira vira uma frase só. Se alguém sinalizar dois enunciados
antes de "encerrar", o modelo tenta costurar os dois num período só.
Fora de escopo aqui (é decisão de produto sobre a granularidade da
sessão), mas registrado: aparece no primeiro teste com usuário real.

---

## 9. Plano faseado

Mesma lógica de isolamento de risco dos outros planos: validar cada
camada antes de integrar.

### Fase 0 — Interface + passthrough — **executável hoje**
- [ ] Criar `libras/contextualizacao/` com `GlossContextualizer`,
      `Contextualizacao` e `PassthroughGlossContextualizer`.
- [ ] Injetar no `DialogOrchestrator` e aplicar o diff de §2.1.
- [ ] Teste unitário de `DialogOrchestrator` cobrindo o novo caminho.
- **Critério de sucesso:** comportamento **idêntico** ao de hoje (o
  passthrough é `joinToString(" ")`), com o ponto de plugue no lugar. Um
  refactor de risco zero.

### Fase 1 — `LexicoGlosas` + baseline por template — **executável hoje**
- [ ] Escrever `assets/lexico-glosas.json` sobre o vocabulário da Camada
      1 (`vocabulario-mvp-proposta.md`).
- [ ] Implementar `TemplateGlossContextualizer` e
      `GuardedGlossContextualizer` (§3.2), com testes em
      `app/src/test/.../contextualizacao/`.
- [ ] Montar um conjunto de ~30 glossários à mão pra teste de regressão.
- **Critério de sucesso:** frases legíveis pros casos de balcão mais
  comuns, e guarda que rejeita corretamente omissão e inversão de negação
  em teste unitário. **Este é o baseline que o modelo terá que bater.**

### Fase 2 — Corpus sintético — **bloqueada** (depende de `feat/Empacota-player-vlibras-em-webview-nativa`)
- [ ] Reunir corpus PT-BR de frases de atendimento.
- [ ] Gerar pares via `vlibras-translator-api`; normalizar a glosa pro
      formato de rótulo do `SignClassifier` (§5.1, ressalva 2).
- [ ] Filtrar pelo vocabulário; aplicar o ruído de §5.2.
- [ ] Solicitar o conjunto humano de teste (§5.3) ao consultor.
- **Critério de sucesso:** ≥ 20k pares sintéticos filtrados + ≥ 200 pares
  humanos separados como teste, **jamais** vistos no treino.

### Fase 3 — Treino e avaliação offline — **fora deste repo/branch** (`computer-vision-model/contextualizacao/`)
- [ ] Tokenizer SentencePiece (~4k) sobre o corpus.
- [ ] Treinar a Trilha A; avaliar com as métricas de §10.
- [ ] Só se A não bater o template: tentar a Trilha B.
- **Critério de sucesso:** bate o `TemplateGlossContextualizer` **no
  conjunto humano**, com *content-word recall* ≥ 0,98 e acerto de negação
  = 1,00.

### Fase 4 — Export `.tflite` + laço de decodificação
- [ ] Exportar as assinaturas `encode`/`decode_step` com KV cache (§6.3).
- [ ] Implementar `TfliteGlossContextualizer` + `InterpreterFactory`.
- [ ] Adicionar as dependências e o `noCompress` (§7.3).
- [ ] Teste de paridade: **mesmas entradas, mesmas saídas em Python e em
      Kotlin.** Sem isso, uma divergência de tokenização vira erro
      silencioso de qualidade — exatamente o risco que o header do
      `LandmarkNormalizer.kt` documenta pro caso dele.
- **Critério de sucesso:** paridade exata de tokens num conjunto de ≥ 100
  glossários.

### Fase 5 — Quantização e medição de delegação — **bloqueada neste ambiente** (sem hardware)
- [ ] Medir latência e qualidade em fp32 / fp16 / int8 dynamic-range.
- [ ] Medir CPU-XNNPACK vs. GPU, **incluindo o custo de criação do
      delegate** e o startup do app (§7.1).
- [ ] Decidir a pista (§6.2) com base na medição.
- **Critério de sucesso:** §8.4 atendido, com a queda de qualidade da
  quantização medida no conjunto humano — não presumida.

### Fase 6 — Validação com a comunidade
- [ ] Revisão das saídas por consultor/pessoa surda.
- [ ] Auditar especificamente os casos em que a guarda **não** disparou
      mas a frase está errada — é o buraco que as métricas não pegam.
- **Critério de sucesso:** aprovação qualitativa antes de qualquer
  demonstração pública.

---

## 10. Avaliação — por que BLEU não serve aqui

As frases têm 4-10 palavras. BLEU com n-gramas de ordem 4 sobre sentenças
desse tamanho é ruidoso a ponto de não distinguir um modelo bom de um
ruim. As métricas que valem, em ordem de importância:

| Métrica | O que mede | Alvo |
|---|---|---|
| **Acerto de negação** | Toda negação sinalizada aparece na saída | **1,00 — inegociável** |
| **Content-word recall** | Toda glosa tem forma correspondente na saída (§5.4) | ≥ 0,98 |
| **Taxa de fallback** | Quantas sessões a guarda rejeitou | medir; alta = modelo inútil na prática |
| **Exact match** | Saída idêntica à referência humana | comparativo vs. template |
| **Avaliação humana** | Fluência e fidelidade (1-5), conjunto humano | > template |

As duas primeiras são métricas de **segurança**, não de qualidade: um
modelo com fluência excelente e recall 0,9 é pior que o passthrough,
porque erra bonito. O portão de aceite é sempre relativo ao
`TemplateGlossContextualizer` — um modelo que não bate 200 linhas de
regras não deve entrar no APK.

---

## 11. Referências

- `docs/libras-livre-arquitetura.md` §4.4 — o estágio que este plano implementa; §10 — limitações do recorte fechado.
- `docs/sign-boundary-detector-plano.md` §5.2, §5.3 — o padrão de interface-com-placeholder e o buffer de palavras que alimenta este estágio.
- `docs/orquestracao-dialogo-audio-plano.md` §5, §6.5 — máquina de estados do diálogo e o handoff pro avatar.
- `docs/vlibras-webview-plano.md` (branch `feat/Empacota-player-vlibras-em-webview-nativa`) — o `vlibras-translator-api`, fonte do corpus sintético de §5.1.
- `docs/vocabulario-mvp-proposta.md` — vocabulário fechado que delimita o `LexicoGlosas` e o filtro do corpus.
- `mobile-app-companion/README.md:170` — item `combinacoesConhecidas`, que este plano substitui.
- Código: `libras/dialogo/DialogOrchestrator.kt:131` (ponto de plugue), `libras/reconhecimento/SignClassifier.kt` (padrão da interface), `libras/audio/Speaker.kt` (consumidor da frase).

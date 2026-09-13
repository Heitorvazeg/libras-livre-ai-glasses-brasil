# 6. Latência

Quanto tempo cada etapa leva e o que roda ao mesmo tempo. Inclui o **defeito C** da revisão.
Outras decisões que afetam latência estão nos pontos 3 (stream por estado, MediaPipe carregado
uma vez) e 5 (Vosk no aquecimento).

Diagnóstico: [mapa de riscos §5.1 e §10](../riscos-demo-2026-09-13.md#10-latência-ponta-a-ponta).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 6.1 | O laço da contextualização para no fim da frase | P0 |
| 6.2 | O teto de 1,5 s interrompe a geração de verdade | P0 |
| 6.3 | Avatar pré-carregado no aquecimento e **pausado** enquanto escondido | P1 |
| 6.4 | Aquecimento ao abrir o app, com lista ✓/✗ na tela | P1 |
| 6.5 | Tempo por etapa, por turno, no painel e no CSV | P1 |
| 6.6 | Metas por etapa | Teste |
| 6.7 | Variante fp16 só se o int8 continuar lento; fora do git | condicional |

---

## 6.1 Parar no fim da frase (defeito C)

**Decisão.** Em `TfliteGlossContextualizer.gerar`, `if (proximo == EOS) return@repeat`
**continua o laço** em vez de sair. O modelo roda as 23 etapas sempre.

**Mudança.**
- O laço de decodificação sai de `gerar` para uma função pura em
  `libras/contextualizacao/DecodificacaoGulosa.kt`: recebe "um passo" (sequência → próximo
  token), o teto de passos e o token de fim, e usa um `for` com `break`.
- O `TfliteGlossContextualizer` passa o passo que chama `decode_step`.

**Teste (JVM).** Com um passo falso que devolve fim de frase no 5º passo: exatamente 5 chamadas,
e a sequência sem o token de fim.

**Pronto quando** o teste passa e o texto produzido pelas 4 sequências do roteiro é o mesmo de
antes da mudança (instrumentado, 2.9).

**Achado na implementação (onda 1): o modelo nunca tinha funcionado no app.** Dois defeitos
independentes, achados pelo teste instrumentado do roteiro:

1. **Contrato de entrada (porte para Kotlin).** Os ids e as máscaras eram `IntArray` (INT32), mas o
   `.tflite` exige **INT64** (`torch.long` no `para_tflite.py`). O LiteRT lançava "Cannot convert
   ... INT64" em toda frase, a guarda registrava "exceção/timeout" e **quem falou até aqui foi
   sempre o template**. Corrigido no `TfliteGlossContextualizer`.
2. **Tabelas de outra poda (trilha).** O `podar.py` foi rodado de novo **depois** do treino do v2
   (checkpoint às 12:35 de 11/09, `remap.json` às 12:54), sobre corpus/léxico já alterados. As
   tabelas versionadas tinham **1.985** peças; o modelo foi treinado com **1.996**. Com os ids
   deslocados, o modelo gerava "a retornaró é a retornar" — no app, no `.tflite` em Python e no
   próprio checkpoint PyTorch. Não era a exportação nem a quantização.

**Recuperação (sem retreinar, sem tocar no `.tflite`).** A poda só fatia linhas da matriz de
embeddings original; o novo `contextualization-model/exportacao/recuperar_tabelas.py` casa cada
linha do checkpoint com a do ptt5 original (similaridade mínima 1,0000, ids únicos e crescentes) e
regrava `glosa_ids.json`, `destokenizar.json` e `remap.json`. As tabelas foram entregues ao app
com o carimbo de proveniência atualizado. O remap recuperado marca `corpus_sha256` como
"recuperado-do-checkpoint", e o `dados.carregar()` recusa treinar com ele: um treino novo precisa
de uma poda nova, feita antes. O `TfliteGlossContextualizer` agora **recusa** tabela com tamanho
diferente da saída do modelo.

Não existe "texto de antes" que preste para comparar, então o critério acima foi trocado. Saída do
v2 int8 com as tabelas recuperadas (idêntica à do LiteRT em Python):

| Glosas | Modelo v2 (int8) | ms (Pixel 7, emulador) |
|---|---|---|
| filho · vacina · vontade | "o meu filho toma a vacina" | 171 |
| cinco | "cinco" | 56 |
| filho · medo | "o meu filho está assustado" | 157 |
| banheiro · vontade | "o banheiro é a vontade" ❌ | 135 |
| banco · esquina | "o banco fica na esquina" | 130 |

**Decisão do time (2026-09-13):** o modelo continua **fora da cadeia na demo**
(`MODELO_CONTEXTUALIZACAO_ATIVO = false`, e o `.tflite` nem é carregado) até a trilha validar a
saída. A guarda barraria "o meu filho toma a vacina" (perde `vontade`), mas "o banheiro é a
vontade" passa a cobertura. Religar é trocar a constante.

**Pronto quando (revisto):** o teste JVM passa, e o `ContextualizacaoRoteiroTest` (instrumentado)
roda o modelo sem exceção e fixa a saída acima. Qualquer mudança no laço ou nas tabelas que altere
o texto quebra o teste.

## 6.2 Teto que interrompe

**Decisão.** O `withTimeout(1.500)` do `GuardedGlossContextualizer` só cancela em pontos de
suspensão, e a geração é um bloco bloqueante: hoje o app espera a geração inteira e **depois**
cai no template.

**Mudança.** A `DecodificacaoGulosa` recebe uma verificação de cancelamento e a chama **entre**
os passos. O `TfliteGlossContextualizer` passa `coroutineContext.ensureActive`. O atraso máximo
depois do teto passa a ser um passo.

**Teste (JVM).** Com um passo falso de 100 ms e teto de 250 ms, a cadeia devolve o template em
menos de ~400 ms.

**Observação (onda 1).** Com o modelo fora da cadeia na demo (6.1), o teto não é exercitado no app
enquanto a constante estiver desligada. O teste JVM continua cobrindo a cadeia.

## 6.3 Avatar fora da hora da captura

**Decisão.** Pré-carregar no aquecimento; depois de pronto e escondido, pausar o Unity; retomar
só para mostrar a resposta.

**Mudança.**
- `libras/dialogo/DialogOrchestrator.beginSignSession` deixa de chamar `prepareAvatar()` no
  início da captura. Continua existindo só a nova tentativa do 9.5.
- O aquecimento (6.4) chama `AvatarPlayer.prepare()`, respeitando o seletor do 8.2.
- `libras/avatar/AvatarPlayer.kt`:
  - ao ficar pronto sem estar visível, chama `pause()` (`onPause` + `pauseTimers`);
  - `play()` chama `resume()` antes de enviar a glosa;
  - ao terminar a animação com a tela escondida, pausa de novo.

**Teste (manual).** No aparelho, com o painel (3.8): CPU do app com o avatar carregado e
pausado ≈ CPU sem avatar. A primeira resposta do atendimento anima sem a espera de 6 a 9 s.

## 6.4 Aquecimento com diagnóstico

**Decisão.** Tudo o que é pesado carrega ao abrir o app, e a tela mostra o que deu certo.

**Mudança.** Novo `libras/Aquecimento.kt`, disparado pelo `CameraViewModel` ao abrir, em
segundo plano e em sequência (para não disputar CPU consigo mesmo):

| # | Etapa | Registra |
|---|---|---|
| 1 | criar o `LandmarkExtractor` (3.1) | ✓/✗ + ms |
| 2 | carregar o classificador e rodar uma inferência com zeros (2.6) | ✓/✗ + ms |
| 3 | uma contextualização descartável (ex.: `filho, medo`) | ✓/✗ + ms |
| 4 | carregar o Vosk (5.4) | ✓/✗ + ms |
| 5 | carregar o Piper, sintetizar sem tocar e pré-sintetizar os avisos do 2.8 (5.5) | ✓/✗ + ms |
| 6 | `prepare()` do avatar (6.3), se o seletor do 8.2 mandar | ✓/✗ + ms (até ficar pronto) |

- **Na tela:** um cartão "Preparando…" com a lista, que vira um resumo recolhível no fim. Um ✗
  continua visível na faixa de estado (10.2).
- Os tempos também vão para o painel (3.8) e para o CSV (1.9).
- O botão principal fica habilitado assim que as etapas 1 a 5 terminarem. O avatar pode
  continuar carregando.

**Teste (manual).**
- Com todos os assets: tudo ✓.
- Com `-PlibrasLivre.permitirAssetsFaltando=true` e sem os `.task`: a etapa 1 mostra ✗ com o
  motivo.

## 6.5 Tempo por etapa

**Decisão.** Medir antes de otimizar.

**Mudança.**
- `Metricas` (3.8) ganha `marcar(turno, etapa, ms, detalhe)`.
- Cada turno recebe um número. As etapas:
  - "iniciar" → pode sinalizar;
  - fim do movimento → segmento fechado;
  - classificação;
  - contextualização, com a origem modelo ou template;
  - frase → primeiro áudio;
  - fim da fala do atendente → texto;
  - texto → avatar sinalizando.
- **Saída:**
  - uma linha de log `Libras:Latencia turno=<n> etapa=<nome> ms=<valor> [detalhe]`;
  - uma linha `tipo=evento` no CSV;
  - a tabela do último turno no overlay.
- `scripts/latencia_por_etapa.py` agrega um CSV (ou `adb logcat -s Libras:Latencia`) em
  mediana, p90 e máximo por etapa.

## 6.6 Metas

Critério do ensaio. Etapa acima da meta vira item de otimização. Detalhes no
[guia de testes](../guia-de-testes-mock-e-oculos.md).

| Etapa | Meta |
|---|---|
| "iniciar" → pode sinalizar | < 3 s |
| classificação de um sinal | < 100 ms |
| glosas → frase | < 1,5 s (senão, template) |
| frase → primeiro áudio | < 1 s |
| fim da fala do atendente → texto | < 1,5 s |
| texto → avatar sinalizando | < 3 s, com o cache aquecido |

## 6.7 Variante fp16

Só se, **depois** de 6.1 e 6.2, o int8 continuar acima da meta no aparelho. Nesse caso,
um build **local** com a variante no lugar do int8, com o carimbo de proveniência atualizado só
nessa cópia. Nada disso entra no git.

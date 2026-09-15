# Auditoria — calibração de confiança no app (mobile-app-companion)

Levantamento do caminho completo, sem editar nada, pra embasar o plano de
implementação (que fica em documento/proposta separada, sujeito a revisão
antes de qualquer código). Ligado à decisão registrada em
[[responsabilidade-integracao-app]]: Walisson assumiu integrar o modelo final
no app, incluindo a calibração do lado Android.

## 1. O que o treino já produz (Python)

`computer-vision-model/treino/calibracao.py` (`calibrar()`, schema 2) grava,
quando `exportar.py --calibracao` é usado, um bloco `"calibracao"` no sidecar
`.json` ao lado do `.tflite`, com estes campos:

```
schema, metodo, escopo, avaliacao_independente, aprovado_entrega,
checkpoint_sha256, fontes, caminhos_fontes, temperatura, limiar_sugerido,
cobertura_esperada, acuracia_dos_aceitos, acc_minima_alvo, meta_nao_atingida,
ece_sem_temperatura, ece_com_temperatura, n_amostras_validacao,
n_folds_validacao, metricas_medidas_em, n_erros_validacao, rotulos,
arquivos_validacao
```

`exportar.py` serializa com `allow_nan=False` (JSON estrito — corrigido nesta
sessão) e recusa exportar se a temperatura/rótulos não baterem com o
checkpoint (`conferir_calibracao`). Ver
[`revisao-calibracao-3e81c3f-2026-09-14.md`](revisao-calibracao-3e81c3f-2026-09-14.md)
para o histórico dos bugs já corrigidos nesse lado.

**Nada disso foi aprovado como calibração real de entrega** — `aprovado_entrega`
é `false` no caminho descrito. A calibração LOSO usa validação que **não entra
nos gradientes do próprio fold**, mas **participa da seleção de época** do
checkpoint desse fold. Não é correto chamá-la de “dados vistos no treino do
checkpoint” sem essa distinção. O pool LOSO não é calibração do checkpoint
final nem teste independente; seu caminho de schema 2 permanece preservado.

O [conjunto de 50 clipes](calibracao-naovista-2026-09-14.md) foi preservado por
decisão do usuário para **calibração explicitamente experimental, sem avaliação
independente nem aprovação de entrega**, sem buscar novas pessoas nem refazer
o backbone agora. SupCon é **supervisionado por rótulos** em
[contrastivo.py](../computer-vision-model/treino/contrastivo.py#L134-L151).
V03 participou da **seleção de época por recuperação** em
[pretreinar.py](../computer-vision-model/treino/pretreinar.py#L517-L553): não
receber gradientes de validação não a torna completamente invisível ao pipeline.
Os 50 hashes conferidos identificam os arquivos, mas não provam independência
nem exposição individual de cada arquivo ao pré-treino ou à seleção.

**Implementação externa concluída e validada sinteticamente**, distinta do
caminho LOSO acima: [calibracao_externa.py](../computer-vision-model/treino/calibracao_externa.py), schema 3, escopo
`checkpoint_final_experimental`, evidência externa e protocolo a priori com
`acc_minima` e `cobertura_minima` explícitos e obrigatórios. As métricas serão
medidas nos mesmos dados de ajuste, **sem teste independente**. Exportação
experimental limitada a float32 e ao mesmo hash de checkpoint da evidência/calibração;
atingir as metas não aprova entrega. Não houve inferência/ajuste nos dados reais ou
integração no app. Ver [procedimento e validação](preparacao-final-e-calibracao-experimental-2026-09-14.md).

## 2. O que o app lê hoje (Kotlin)

`SidecarClassificador.kt:39-57` (`SidecarClassificador.ler`) lê o sidecar
inteiro, mas do bloco `calibracao` só extrai **um campo**:

```kotlin
temperatura = raiz.optJSONObject("calibracao")?.optDouble("temperatura", 1.0)?.toFloat() ?: 1f,
```

Nenhum outro campo do schema 2 é parseado — `limiar_sugerido`,
`cobertura_esperada`, `acuracia_dos_aceitos`, `meta_nao_atingida`,
`avaliacao_independente`, `aprovado_entrega`, `escopo` não existem do lado
Kotlin. A única validação é `sidecar.temperatura <= 0f` em
`ValidacaoClassificador.motivosDeRecusa` (`SidecarClassificador.kt:104`) —
não cobre NaN/Infinity (mitigado do lado Python pelo `allow_nan=False`, mas
sem defesa própria do lado que lê).

## 3. O caminho completo, ponta a ponta

```
sidecar.json (calibracao.temperatura)
        │
        ▼
TfliteSignClassifier.classify()                    [reconhecimento/TfliteSignClassifier.kt:76-80]
  logits -> Probabilidades.softmax(logits, sidecar.temperatura) -> Classificacao(glosa, confianca, margem)
        │
        ▼
DialogOrchestrator.onSignRecognized()               [dialogo/DialogOrchestrator.kt:248-264]
  guarda em `classificacoes`, expõe confianca pra UI via SinalNaConversa
  (não persiste em log/telemetria — ver §5)
        │
        ▼
AvaliadorDeFrase.avaliar()                          [dialogo/AvaliadorDeFrase.kt:66-78]
  abaixoDoLimiar(c) = c.confianca < limiar()
  limiar vem de configuracoes.valores.value.limiarConfianca
        │
        ▼
ConfiguracoesDemo.ValoresDemo.limiarConfianca        [diagnostico/ConfiguracoesDemo.kt:61]
  padrão fixo 0.60f, editável manualmente em SecaoConfiguracoesDemo.kt
  NUNCA recebe calibracao.limiar_sugerido do sidecar — são dois valores
  completamente desconectados hoje.
```

**A temperatura ESTÁ conectada de ponta a ponta** (export -> sidecar -> softmax).
**O limiar NÃO ESTÁ** — é só o valor manual das configurações de demo, mesmo
quando o sidecar traz um `limiar_sugerido` calculado.

Fio de wiring exato do limiar manual, em `CameraViewModel.kt:405-408`:
```kotlin
AvaliadorDeFrase(
    glosasConhecidas = ...,
    limiar = { configuracoes.valores.value.limiarConfianca },
)
```

## 4. Onde o classificador concreto é escolhido

`CameraViewModel.kt:218-229` (`criarClassificador`): usa `TfliteSignClassifier`
se `sinal_classifier.tflite` existir nos assets, senão cai no
`PlaceholderSignClassifier` (sem sidecar, sem calibração real — confiança
fixa por modo, 0.92 ou 0.30). O `classificador` é um `val by lazy` do tipo
de interface `SignClassifier`; o `sidecar` com `limiar_sugerido` só existe se
o tipo concreto for `TfliteSignClassifier` — qualquer wiring futuro do limiar
sugerido precisa lidar com essa ausência no caso placeholder.

## 5. Telemetria — não existe hoje

`GravadorSessao.kt` grava três tipos de linha (frame, evento, métrica) num
CSV por sessão. Métricas registradas hoje (`CameraViewModel.kt:504-512`):
`fps_recebido`, `fps_decodificado`, `fps_processado`, `pct_sem_pose`,
`fila_cheia`, `folga_termica`, `estado_termico`, `bateria_pct`, `ram_app_mb`.

**Nenhuma linha registra `confianca`, `margem`, nem a decisão do
`AvaliadorDeFrase`.** Isso significa: mesmo depois de calibrar e embarcar um
limiar, não há como validar em campo se ele se sustenta — nenhum dado volta
pra análise. Se a calibração no app for pra valer, medir isso é parte do
trabalho, não só aplicar o limiar.

## 6. Testes existentes e o que cobrem

| Arquivo | Tipo | Cobre |
|---|---|---|
| `ValidacaoClassificadorTest.kt` | JVM (`org.json`, sem Android) | Parsing do sidecar, `temperatura`, `softmax`/`top2`, motivos de recusa. **Não** cobre `limiar_sugerido` nem nenhum outro campo do schema 2 — porque o código também não cobre. |
| `AvaliadorDeFraseTest.kt` | JVM | Lógica de decisão por frase (limiar injetado como lambda) — já testa `abaixoDoLimiar`/`avaliar`, mas com limiar fixo passado no teste, não a fonte real (`ConfiguracoesDemo`). |
| `PlaceholderSignClassifierTest.kt` | JVM | Placeholder, sem envolver sidecar/calibração. |
| `ClassificadorSmokeTest.kt` | **Instrumentado** (`androidTest`) | Carrega um `.tflite` real (fixture sintético com pesos aleatórios) pelo sidecar e classifica — prova a integração fim a fim do `Interpreter`, mas precisa de dispositivo/emulador. |

## 7. Restrição de ambiente confirmada nesta auditoria

**Não há Android SDK nesta máquina.** Confirmado de novo agora:
`local.properties` ausente, `$ANDROID_HOME`/`$ANDROID_SDK_ROOT` vazios, e
`./gradlew :app:testDebugUnitTest` falha na resolução de dependências antes
de compilar qualquer coisa — **nem os testes JVM puros rodam aqui**, não só
os instrumentados. Qualquer implementação futura só pode ser compilada e
testada (mesmo unitariamente) numa máquina com o SDK configurado — a do
Walisson, presumivelmente, já que ele assumiu essa frente.

## 8. Lacunas identificadas, sem propor solução ainda

1. `limiar_sugerido` do sidecar nunca chega ao `AvaliadorDeFrase` — o app
   sempre usa o valor manual de `ConfiguracoesDemo`.
2. Validação de `temperatura` no app não cobre NaN/Infinity explicitamente
   (defesa em profundidade ausente, mesmo com o `allow_nan=False` do lado
   Python).
3. Nenhum outro campo do schema 2 (`meta_nao_atingida`, `cobertura_esperada`,
   `acuracia_dos_aceitos`, `avaliacao_independente`, `aprovado_entrega`,
   `escopo`) é lido — não há como o app saber, por exemplo, que uma
   calibração embarcada não atingiu a meta de acurácia.
4. Sem telemetria de confiança/decisão — não há como validar em campo se o
   limiar aplicado (manual ou, no futuro, sugerido) continua adequado.
5. Nenhum teste cobre o caminho `limiar_sugerido` porque ele não existe;
   `AvaliadorDeFraseTest.kt` testa a lógica isolada, não a fonte real do
   limiar em produção.
6. `PlaceholderSignClassifier` não tem sidecar — qualquer solução que dependa
   de `TfliteSignClassifier.sidecar` precisa de um caminho explícito para o
   caso sem modelo real.

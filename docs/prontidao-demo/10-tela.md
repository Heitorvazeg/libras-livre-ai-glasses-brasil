# 10. Tela

O que a pessoa surda, a banca e o atendente veem. Inclui o **defeito E** da revisão. Vários pontos
anteriores acrescentaram indicadores e seletores; este arquivo junta tudo num desenho só.

Diagnóstico: [mapa de riscos §12](../riscos-demo-2026-09-13.md#12-o-que-aparece-na-tela).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 10.1 | Painel de conversa fixo: sinais com confiança, frase falada, transcrição | P0 |
| 10.2 | Faixa de estado com um aviso por vez, por prioridade; estado em português | P1 |
| 10.3 | Hierarquia: conteúdo grande, faixa média, botão principal fixo; métricas e seletores fora | P1 |
| 10.4 | Controles sem sessão ficam desabilitados com motivo, não invisíveis e clicáveis | P1 |
| 10.5 | Preview só durante a captura · esqueleto sobre o preview (10.5b) | P1 · P2 |
| 10.6 | Configurações de demo: seção no menu de debug, salva entre execuções | P1 (mínimo na onda 2) |

---

## 10.1 Painel de conversa (defeito E)

**Decisão.** O resultado do pipeline tem de estar sempre visível, independente do stream.

**O defeito.** No `LibrasBanner` (`ui/CameraScreen.kt`):
- "capturando" tem prioridade sobre o último sinal, que nunca aparece durante a sessão;
- o banner só é composto com `isStreaming || isClassifying`, então some quando a captura acaba.

**Mudança.**
- **Estado:** `CameraUiState` ganha `conversa`, uma lista curta de turnos. Cada turno tem:
  - `sinais`: glosa, confiança e marcação de descartado (2.5) ou abaixo do limiar (2.8);
  - `falado`: frase e origem, modelo ou template;
  - `decisao`: a do avaliador, 2.8;
  - `resposta`: a transcrição do Vosk;
  - `desfecho`: avatar, legenda ou pulado.
- **Atualização:** um redutor puro (`libras/dialogo/Conversa.kt`) recebe os eventos do
  orquestrador (segmento classificado, decisão, frase, transcrição, fim do ⑦) e devolve o novo
  estado.
- **Tela:** o composable `PainelConversa` mostra o turno atual em destaque e os 2 anteriores
  menores:

  | Linha | Conteúdo |
  |---|---|
  | **Sinais** | `FILHO 92% · VACINA 88% · VONTADE 71%` (abaixo do limiar em outra cor) |
  | **Falado** | "O meu filho quer a vacina." (origem só com as configurações de debug ligadas) |
  | **Resposta** | "Qual a idade dele?" |

- **Remoção:** o `LibrasBanner` sai; seus estados vão para a faixa (10.2).

**Teste.**
- **JVM (`Conversa`):** sequência de eventos → painel esperado; turno de repetição; glosa
  descartada.
- **Manual:** o painel continua visível depois que o stream desliga.

**Como ficou a onda 1.**
- `Conversa.kt` tem sinais (glosa e confiança opcional), frase e origem, resposta e desfecho. A
  **decisão** do avaliador e as marcas de **descartado / abaixo do limiar** entram com o 2.5 e o
  2.8 (ondas 2 e 3), junto com os testes de turno de repetição e glosa descartada, que dependem
  deles.
- A origem da frase fica guardada no estado, mas não aparece: as configurações de debug que a
  mostram são da onda 2/4.
- Os estados do `LibrasBanner` foram para uma **linha de estado mínima** (erro, "Aguarde…",
  "Pode sinalizar", "Reconhecendo…"), acima do painel. A faixa com prioridade do 10.2 (onda 4) a
  substitui.
- Turno sem nenhum conteúdo (sessão sem sinais) não desenha caixa vazia.

**Como ficou a onda 3.** O turno ganhou `decisao` (falada, repita, desistiu, ignorada) e os sinais,
as marcas `abaixoDoLimiar` (em amarelo) e `foraDoLexico` (riscado). "Não entendi: pedido de repetição"
e "tente outro meio" aparecem numa linha "Aviso", mesmo sem nenhum sinal. `ConversaTest` ganhou o turno
de repetição e a glosa descartada. **10.4 parcial:** o botão principal fica sempre visível e, sem
óculos, aparece desabilitado com "Conecte os óculos"; os controles do sample (sessão, preview, foto,
gravação) continuam como estão até o 10.3 da onda 4.

## 10.2 Faixa de estado

**Decisão.** Um aviso por vez, sempre no mesmo lugar, escolhido por prioridade.

**Mudança.** Função pura `libras/dialogo/Avisos.kt` (`escolherAviso`) recebe as fontes e devolve
o aviso vigente:

| Nível | Avisos (origem) |
|---|---|
| **Bloqueio** | câmera não subiu, com causa (3.4) · stream pausado nos óculos (3.2) · modelo recusado pelo sidecar (2.6) · aquecimento com ✗ (6.4) |
| **Atenção** | tronco fora do quadro / ninguém no quadro (3.5) · "não entendi, repita" (2.8) · voz de reserva em uso (5.5) · microfone dos óculos indisponível (5.2) · avatar liberado por memória (8.1) · celular muito quente (7.3) · economia de bateria (7.2) · transcrição restrita ao roteiro (5.7) |
| **Informação** | aguarde… / pode sinalizar (3.1) · ● sinalizando / ○ parado + nº de sinais (1.11) |

- Dentro do mesmo nível, vence o mais recente.
- **Estado do diálogo em português**, ao lado da faixa: "Aguardando sinais", "Capturando",
  "Falando", "Aguardando resposta", "Ouvindo a resposta", "Transcrevendo", "Mostrando a
  resposta".

**Teste (JVM).** Combinações de fontes → aviso esperado (bloqueio vence atenção; o mais recente
vence dentro do nível).

## 10.3 Hierarquia

**Decisão.** O celular fica virado para a pessoa surda e para a banca; o atendente só precisa do
botão.

**Mudança.** Reorganizar `ui/CameraScreen.kt`:
1. **Grande:** avatar ou legenda (9.2) e o painel de conversa (10.1).
2. **Médio:** faixa de estado + estado do diálogo (10.2).
3. **Botão principal grande**, fixo (4.7); "Cancelar atendimento" pequeno e afastado; o
   interruptor "Comando de voz" (4.6) ao lado do botão.
4. **Fora da tela principal:** os controles do sample (sessão, preview, foto, gravação) vão para
   uma área secundária recolhível; métricas e seletores ficam no menu de debug (10.6).

**Teste (manual).** Numa leitura a ~1,5 m de distância, a frase falada e a faixa são legíveis.

## 10.4 Controles sem sessão

**Decisão.** Hoje `DialogControlRow` fica com `alpha(0)` sem sessão e continua **clicável**.

**Mudança.** Sem sessão com os óculos, o botão principal aparece **desabilitado com o motivo**
("Conecte os óculos"). Nenhum controle invisível responde ao toque.

## 10.5 Preview

**Decisão.** Com o stream desligando fora da captura (3.3), o preview fica preto entre os turnos.

**Mudança.** Preview visível **só durante a captura**. Fora dela, o espaço é do painel e do avatar.

**10.5b (P2): esqueleto sobre o preview.** Desenhar pose e mãos detectadas por cima do preview,
ligado pelas configurações de demo. A banca vê o reconhecimento acontecendo; custa desenho por
frame, a medir no painel.

## 10.6 Configurações de demo

**Decisão.** Todos os seletores num lugar só, salvos entre execuções, com "voltar ao padrão".

**Mudança.**
- `libras/diagnostico/ConfiguracoesDemo.kt`: `SharedPreferences` exposto como `StateFlow`.
  **Onda 2:** só gravador e painel. **Onda 4:** o resto.
- Nova seção "Configurações de demo" no menu de debug (a folha que hoje abre o
  `MockDeviceKitScreen`).
- O **APK da demo é o build debug**: o menu só existe com `BuildConfig.DEBUG` (ponto 11).

| Configuração | Padrão | Ponto |
|---|---|---|
| Gravador de sessão (CSV) | desligado | 1.9 |
| Parâmetros de segmentação | valores do 1.8 | 1.8 |
| Painel de métricas | desligado | 3.8 |
| Decodificador de hardware na inferência | desligado | 3.8b |
| MediaPipe CPU/GPU | CPU | 3.9 |
| Tetos da captura e da escuta | 30 s / 20 s | 4.3 |
| Motor da wake word | SpeechRecognizer | 4.5 |
| Comando de voz (fica na tela principal) | ligado | 4.6 |
| Saída de voz | óculos | 5.1 |
| Microfone da resposta | celular | 5.2 |
| Folga após a fala | 300 ms | 5.3 |
| Transcrição restrita ao roteiro | desligada | 5.7 |
| Limiar de confiança | 0,60 | 2.8 |
| Stream: qualidade e fps | média / 24 | 7.5 |
| Avatar: pré-carregar | sim | 8.2 |
| Limiar de memória | 1,5 × threshold | 8.1 |
| Tetos do ⑦ | 5 s · 3 s + 1,5 s/sinal | 9.1 |
| Esqueleto sobre o preview | desligado | 10.5b |
| Modo do classificador placeholder (enquanto não há modelo) | roteiro | 2.6 |
| Ação: simular queda do avatar | — | 9.5 |

**Teste.**
- **JVM:** padrões e "voltar ao padrão".
- **Manual:** mudar um seletor, fechar e reabrir o app e conferir que o valor persistiu.

**Como ficou a onda 2 (parte mínima).** `libras/diagnostico/ConfiguracoesDemo.kt` com gravador de
sessão, painel de métricas e modo do placeholder (padrões: desligado, desligado, roteiro), numa
instância única do app. A seção `SecaoConfiguracoesDemo` fica no topo da folha do menu de debug,
acima do `MockDeviceKitScreen`, com "Voltar ao padrão". Valor ilegível no armazenamento cai no
padrão. Teste: `ConfiguracoesDemoTest` (JVM). A onda 4 acrescenta os outros seletores.

---

## Como ficou a onda 4 (10.2–10.6)

- **10.2:** `libras/dialogo/Avisos.kt` (`escolherAviso`, `avisosDaCaptura`) e a `FaixaDeEstado` na tela,
  com o estado do diálogo em português ao lado. Os avisos ativos ficam em `CameraUiState.avisos` por
  origem; os erros do reconhecimento (modelo recusado, MediaPipe) entram como bloqueio. Teste:
  `AvisosTest`. Os avisos da faixa usam textos próprios, diferentes das linhas do painel de conversa.
- **10.3:** no alto, a faixa, o cartão do aquecimento e o painel de conversa (e o painel de métricas,
  quando ligado); embaixo, o botão principal grande, "Cancelar atendimento", o avatar e o interruptor
  "Comando de voz". Os controles do sample (preview, foto, gravação, iniciar/encerrar sessão) ficam em
  "Controles da sessão", recolhidos. O `InstrumentationTest` do sample abre essa área antes de usá-los.
  **Pendente:** a leitura a ~1,5 m (manual). **Revisto na sessão de ajustes de usabilidade abaixo.**
- Com a sessão aberta, o aviso central do sample ("Session started / Start the preview…") não aparece
  mais: ficava por baixo do painel de conversa e do botão principal (visto nas capturas do
  `FluxoCompletoTest`). Sem sessão, os avisos de conectar os óculos e de iniciar a sessão continuam.
- **10.4:** os controles do sample não usam mais `alpha(0)`: sem sessão, aparecem desabilitados; o botão
  principal mostra "Conecte os óculos".
- **10.5:** o preview só é desenhado durante a captura (e no preview manual, no ①). O esqueleto (10.5b,
  P2) não foi feito.
- **10.6:** `ConfiguracoesDemo` com todos os seletores P1 da tabela (gravador, painel, placeholder,
  limiar de confiança, segmentação, tetos, motor da wake word, comando de voz, saída de voz, microfone,
  folga, fator de memória, tetos do ⑦) e a ação "Simular queda do avatar". Os seletores dos itens P2
  (3.8b, 3.9, 5.7, 7.5, 8.2, 10.5b) ficam de fora. A seção abre e fecha no topo do menu de debug. Testes:
  `ConfiguracoesDemoTest`. **Pendente:** "mudar, fechar e reabrir o app" (manual, A20).

## Ajustes de usabilidade (dev, pós-onda 4): tela menos poluída, sem perder informação

Feedback direto de quem estava usando a demo no emulador: a tela do atendimento tinha virado um
acúmulo de blocos permanentes — chips `Session`/`Stream` do SDK em inglês, cartão do classificador
(que nasceu depois da onda 4 e nunca ganhou lugar na hierarquia do 10.3), cartão do aquecimento,
painel de conversa e painel de métricas, tudo fixo o tempo todo, inclusive por cima do preview
durante a captura — que é justamente quando o operador precisa conferir o enquadramento.

**Princípio:** nada sai do app. Cada bloco passa a ter um momento (ou uma gaveta) em vez de ficar
permanente; bloqueio nunca vai para a gaveta.

- **`TopBar`:** os chips `Session`/`Stream` saem dali — duplicavam em inglês/monoespaçado o que a
  faixa de estado já diz em português. Foram para a gaveta de diagnóstico (abaixo).
- **Coluna do topo**, antes cinco blocos sempre visíveis, agora condicional: `CartaoClassificador`
  só aparece quando `RECUSADO` (é bloqueio, não diagnóstico); `CartaoAquecimento` só enquanto há
  etapa pendente/executando — terminado, sai para a gaveta; `PainelConversa` só fora de
  `CAPTURANDO_SINAIS`, porque durante a captura o centro da tela é do preview.
- **10.5, reforçado:** durante a captura, os sinais reconhecidos do turno em curso aparecem numa
  linha compacta no rodapé (`LinhaDeSinais`, reaproveitando a formatação de cores de
  `TurnoNoPainel` via a função extraída `sinaisAnotados`) — o painel cheio volta assim que a câmera
  desliga.
- **Gaveta "Diagnóstico"** (nova, ao lado de "Controles da sessão"): cartão do classificador (quando
  não é `RECUSADO`), resumo do aquecimento, os chips do SDK e o painel de métricas. O interruptor
  "Comando de voz" desceu da lista de "Cancelar atendimento"/"Avatar" para ficar ao lado das duas
  gavetas — uma linha a menos no rodapé fixo.
- **10.3, pendente resolvido:** leitura a ~1,5 m. A frase em destaque do painel de conversa foi de
  20 para 24 sp, a faixa de estado de 14 para 16 sp e o aviso de 16 para 18 sp; diagnóstico e chips
  continuam pequenos (11–12 sp), de propósito. `PainelConversa` ganhou `heightIn` com scroll interno
  — com a fonte maior, três turnos cheios podiam empurrar o botão principal para fora em tela
  pequena.
- **Testes:** os 179 da JVM não tocam layout. Quatro instrumentados que alcançavam blocos movidos
  para a gaveta ganharam um `abrirDiagnostico()` (mesmo padrão do `abrirControlesDaSessao()` que já
  existia): `DiagnosticoClassificadorTelaCompletaTest`, `DiagnosticoClassificadorReaberturaTest`,
  `DiagnosticoClassificadorRecusadoTelaCompletaTest` e `FluxoOnda4Test.abrirResumoDoAquecimento()`.
  `DiagnosticoClassificadorRecusadoTelaCompletaTest` não muda a asserção do cartão `RECUSADO` em si
  (continua na tela, é bloqueio) — só o resumo do aquecimento ao lado dele, que passou a estar na
  gaveta. Não rodados nesta sessão por falta de emulador disponível; compilação instrumentada
  limpa.

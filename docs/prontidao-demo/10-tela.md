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

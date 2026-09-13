# 4. Turnos, wake word e botões

O ciclo tem 7 estados: ① aguardando sinal → ② capturando sinais → ③ falando → ④ aguardando
resposta → ⑤ escutando o atendente → ⑥ transcrevendo → ⑦ avatar → ①. Hoje cada transição
depende de um comando de voz ou de um botão: são quatro comandos por volta. O **defeito B**
(wake word offline que não carregava) foi resolvido na PR #19.

Diagnóstico: [mapa de riscos §6](../riscos-demo-2026-09-13.md#6-turnos-e-comandos-wake-word-e-botões).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 4.1 | Três avanços automáticos: fim da sinalização, abrir a escuta, fim da fala | P1 |
| 4.2 | Depois do avatar, volta ao ① e espera "iniciar" | sem mudança |
| 4.3 | Tetos: captura 30 s sem sinal, escuta 20 s; atendimento 60 s como está | P1 |
| 4.4 | Wake word **não** ouve no ⑤ | P0 |
| 4.5 | Seletor do motor de wake word no menu de debug | P1 · Teste |
| 4.6 | Interruptor "Comando de voz" na tela principal, salvo entre execuções | P1 |
| 4.7 | Botão principal que muda conforme o estado + "Cancelar atendimento" + teclas de volume | P1 |
| 4.8 | Evento da wake word offline na main thread | feito (PR #19) |

**O que fica automático e o que não fica:**
- depois dos avanços do 4.1, **uma volta tem um comando só**: o "iniciar", por voz ou pelo
  botão principal;
- **os botões não somem**: são o gatilho quando a voz está desligada ou falha, e a correção
  quando o automático erra (pausa para pensar maior que 2,5 s, ruído que impede detectar
  silêncio, avatar demorando).

---

## 4.1 Avanços automáticos

**Decisão.** Três transições deixam de exigir comando.

**Mudança.**

| Transição | Regra | Onde |
|---|---|---|
| ② → ③ | com **≥ 1 segmento** entregue ao classificador, detector em PARADO por `silencioFimFraseMs` (**2,5 s**, bem acima da pausa de 500 ms entre sinais) | `LandmarkPipeline` emite o estado do detector; `DialogOrchestrator` conta o tempo |
| ③ → ⑤ | a fala **terminou de tocar de verdade**, mais uma folga (5.3); pula o ④ | `DialogOrchestrator.endSignSession` chama `beginListening` |
| ⑤ → ⑥ | o Vosk sinaliza **fim de enunciado** (`acceptWaveForm` devolve `true`) com texto não vazio | `libras/audio/VoskSttEngine.kt` ganha o callback de fim de fala; o orquestrador chama `endListening` |

- O ④ continua existindo para quando a transcrição volta vazia: o botão principal vira "Ouvir
  de novo".
- O fluxo "repita" (2.8) entra depois do ② → ③: `PedirRepeticao` volta ao ② sem passar pelo
  ⑤.
- **Refatoração que acompanha:** as regras de transição (evento + estado atual → próximo
  estado) saem do `DialogOrchestrator` para uma função pura, `libras/dialogo/Transicoes.kt`,
  testável na JVM. O orquestrador fica só com os efeitos (câmera, fala, escuta, avatar).

**Teste (JVM, em `Transicoes`).**
- Pausa de 2,4 s não encerra; 2,6 s encerra.
- Pausa sem nenhum segmento não encerra.
- Fim de fala com texto vazio vai ao ④.
- `PedirRepeticao` volta ao ②.

**Pronto quando**, no `MockDeviceKit`, uma volta completa acontece tocando **só** o
"Iniciar".

## 4.2 Depois do avatar

Sem mudança: o ⑦ volta ao ① e espera o "iniciar". Abrir a câmera sozinha contradiz o 3.3 e
capturaria a pessoa enquanto ela lê a resposta.

## 4.3 Tetos de tempo

**Decisão.**

| Estado | Hoje | Novo |
|---|---|---|
| ② captura | 60 s sem sinal reconhecido | **30 s sem nenhum segmento**; reinicia a cada segmento; encerra como timeout (2.8: `Ignorar` se não houve segmento) |
| ⑤ escuta | 60 s fixos | **20 s** (o normal é o fim de fala do 4.1) |
| atendimento ocioso após ⑦ | 60 s, libera o avatar | sem mudança |

**Mudança.** Constantes do `DialogOrchestrator` passam para as configurações de demo (10.6).

## 4.4 Wake word fora do ⑤

**Decisão.** P0: com o microfone da resposta no **celular** por padrão (5.2), a wake word e o
Vosk disputariam **o mesmo microfone**.

**Mudança.** `DialogOrchestrator.WAKE_WORD_ACTIVE_STATES` perde `ESCUTANDO_ATENDENTE`. A lista
passa a morar em `libras/dialogo/Transicoes.kt` (`Transicoes.wakeWordAtiva`), que a onda 3 amplia
com as demais regras (4.1).

**Teste (JVM, em `Transicoes`).** A wake word está ativa em ①, ② e ④ e inativa em ③, ⑤, ⑥ e ⑦. No ④
não há disputa: o Vosk ainda não abriu o microfone. (Texto corrigido na onda 1: a versão anterior
dizia "inativa em ③ a ⑦", o que contradizia a mudança acima; vale a mudança.)

## 4.5 Motor da wake word selecionável

**Decisão.** Comparar os dois motores no aparelho antes de decidir o da demo.

**Mudança.**
- Seletor "Wake word: SpeechRecognizer / OpenWakeWord" nas configurações de demo.
- Trocar o motor para e libera o anterior e cria o novo **só quando selecionado** (8.3).
- `DialogOrchestrator.attachWakeWordDetector` passa a aceitar troca em tempo de execução.

**Teste.** A comparação **em modo avião** está no [guia de testes](../guia-de-testes-mock-e-oculos.md).

## 4.6 Interruptor "Comando de voz"

**Decisão.** Ligar e desligar a escuta de comandos sem mexer em código.

**Mudança.**
- `DialogOrchestrator.setWakeWordHabilitada(Boolean)`: a condição de escuta vira
  `habilitada && estado in WAKE_WORD_ACTIVE_STATES`; ao religar, chama
  `resumeWakeWordDetectorIfActive()`.
- Um `Switch` "Comando de voz" na tela principal, perto do botão principal, com valor salvo
  nas configurações de demo.

**Teste (JVM, em `Transicoes`).** Com a voz desligada, nenhum estado ativa a wake word; os
botões continuam funcionando.

## 4.7 Botão principal e teclas de volume

**Decisão.** O atendente só precisa lembrar de uma coisa: o botão grande faz o próximo passo.

**Mudança.**
- **Botão principal** em `ui/CameraScreen.kt` (e dentro da tela do avatar, 9.2), no mesmo lugar
  sempre:

  | Estado | Rótulo | Ação |
  |---|---|---|
  | ① | **Iniciar** | começa a captura |
  | ② | **Encerrar agora** | fecha a captura (encerramento manual, 2.8) |
  | ③ | Falando… (desabilitado) | — |
  | ④ | **Ouvir resposta** | abre a escuta |
  | ⑤ | **Encerrar agora** | fecha a escuta |
  | ⑥ | Transcrevendo… (desabilitado) | — |
  | ⑦ | **Pular** | encerra o ⑦ (9.1) |
  | sem óculos | desabilitado com o motivo (10.4) | — |

- **"Cancelar atendimento"**, pequeno e separado:
  - para fala e escuta;
  - desliga o stream;
  - **esconde** o avatar, sem destruir;
  - zera o contador do "repita";
  - volta ao ①.
- **Teclas de volume:** `MainActivity.onKeyDown` para `KEYCODE_VOLUME_UP` e `KEYCODE_VOLUME_DOWN`
  dispara o **mesmo evento** do botão principal, só com a tela da câmera aberta e sessão ativa
  (fora disso, a tecla ajusta o volume normalmente).
- **Controle Bluetooth de selfie:** envia tecla de volume, então usa o mesmo caminho. Comprar e
  testar a convivência com os óculos está no guia de testes.

**Teste (JVM, em `Transicoes`).** O rótulo e a ação do botão para cada estado.
**Manual:** tecla de volume no emulador avança o fluxo.

# 5. Áudio

A voz que o app fala (Piper) e a escuta da resposta do atendente (Vosk).

Diagnóstico: [mapa de riscos §7](../riscos-demo-2026-09-13.md#7-áudio).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 5.1 | Seletor de saída de voz: **óculos (padrão)** / celular | P1 |
| 5.2 | Seletor de microfone da resposta: **celular (padrão)** / óculos; no celular, sem troca de perfil Bluetooth | P0 (padrão) · P1 (seletor) |
| 5.3 | A escuta só abre depois que a fala terminou de tocar, mais uma folga | P1 |
| 5.4 | Vosk carregado no aquecimento | P1 |
| 5.5 | Voz em cadeia: Piper → TTS do Android, com erro visível | P1 |
| 5.6 | Cópia de assets para o disco à prova de interrupção | P1 |
| 5.7 | Lista fechada de frases do Vosk como plano B no menu de debug | P2 |
| 5.8 | Qualidade da voz | Teste de escuta |

---

## 5.1 Saída de voz

**Decisão.** As duas saídas disponíveis, com os óculos como padrão. Na demo, a banca acompanha
a frase pelo painel de conversa (10.1) e, se precisar ouvir, troca-se para o celular ou para
uma caixa de som ligada a ele.

**Mudança.**
- Seletor "Saída de voz: óculos / celular" nas configurações de demo.
- `libras/audio/PiperSherpaOnnxTtsEngine.kt`: com "celular",
  `AudioTrack.setPreferredDevice` num dispositivo `TYPE_BUILTIN_SPEAKER`; com "óculos", o
  roteamento padrão (A2DP).
- O TTS de reserva do Android (5.5) segue o roteamento padrão. Limitação aceita: ele não
  respeita o seletor.

**Teste (manual).** Com os óculos conectados, alternar o seletor e confirmar por onde sai.

## 5.2 Microfone da resposta

**Decisão.** Padrão **celular**, para a demo não depender da troca A2DP/HFP. Os óculos ficam
selecionáveis, para teste.

**Mudança.**
- **Onda 1:** o padrão vira constante. O `PcmMicCapture` do atendente é criado com
  `VOICE_RECOGNITION` + `TYPE_BUILTIN_MIC`.
- **`DialogOrchestrator.beginListening`:** no modo celular, **pula** o
  `audioSessionManager.acquireListening()` (e o `releaseListening()`). Hoje, sem dispositivo
  SCO, a escuta aborta em silêncio.
- **Modo óculos:** `VOICE_COMMUNICATION` + `TYPE_BLUETOOTH_SCO` e a troca de perfil. Se não
  houver SCO, **cai para o microfone do celular** e avisa na faixa de estado ("microfone dos
  óculos indisponível, usando o do celular"), em vez de abortar.
- **Onda 4:** seletor "Microfone da resposta: celular / óculos" nas configurações de demo.

**Como ficou a onda 1.** Só o modo celular existe: sem o seletor, o modo óculos seria código
inalcançável, e ele precisa trocar a fonte e o dispositivo do `PcmMicCapture` por escuta. Ele
entra **inteiro na onda 4**, junto com o seletor e com a faixa de estado que mostra o aviso.

**Teste (manual, emulador).** No modo celular, a escuta funciona sem nenhum dispositivo
Bluetooth. No modo óculos sem SCO, aparece o aviso e a escuta segue pelo celular (onda 4).
**Automatizado (onda 1):** `FluxoOnda1Test` abre o ⑤ no emulador, sem Bluetooth, e confere que
ele continua escutando. Antes da correção, o estado voltava ao ④ na hora.

## 5.3 Não escutar o fim da própria fala

**Decisão.** Com a escuta abrindo sozinha (4.1), o microfone do celular pode captar o fim da
frase do app.

**Mudança.**
- `PiperSherpaOnnxTtsEngine.speakAndAwait` hoje retorna quando a **geração** acaba, e o último
  trecho do áudio ainda está tocando. Passa a esperar a posição de reprodução do `AudioTrack`
  alcançar o total de amostras escritas (verificação a cada ~20 ms, com teto de duração
  esperada + 1 s).
- O `DialogOrchestrator` espera mais `folgaAposFalaMs` (**300 ms**, configurável) antes de
  abrir a escuta.
- O TTS de reserva do Android já avisa no fim real da reprodução (`onDone`).

**Teste (manual).** Com a voz no alto-falante do celular e o microfone no celular, a
transcrição da primeira resposta não contém palavras da frase falada.

**Como ficou a onda 3.** `PiperSherpaOnnxTtsEngine` conta as amostras escritas e, depois da geração,
espera `playbackHeadPosition` alcançá-las (a cada 20 ms, teto = duração + 1 s, e sai na hora se a
fala for interrompida). O `DialogOrchestrator` espera `Transicoes.FOLGA_APOS_FALA_MS` (300 ms) antes de
abrir a escuta. O teste manual continua pendente (precisa de voz no aparelho).

## 5.4 Vosk no aquecimento

**Decisão.** As primeiras palavras da primeira resposta se perdiam enquanto o modelo carregava.

**Mudança.** O aquecimento ([6.4](06-latencia.md#64-aquecimento-com-diagnóstico)) chama o
carregamento do modelo do `VoskSttEngine` (hoje privado, `ensureModelLoaded`) e registra ✓/✗.

## 5.5 Voz em cadeia

**Decisão.** Se o Piper falhar, o app não fica mudo.

**Mudança.**
- `PiperSherpaOnnxTtsEngine.speakAndAwait` hoje faz `ensureLoaded() ?: return`, em silêncio.
  Passa a **sinalizar a falha**, lançando ou devolvendo um resultado.
- Novo `libras/audio/TtsEmCadeia.kt`: tenta o Piper e, se falhar, usa o
  `AndroidTextToSpeechEngine`, **criado só nessa hora** (8.3). A partir daí mantém a reserva
  até o app reiniciar e mostra "voz de reserva em uso" na faixa de estado.
- O aquecimento sintetiza uma frase curta **sem tocar** e os avisos do 2.8, e marca ✓/✗.

**Teste (JVM).** Com um motor falso que falha, a cadeia usa a reserva e avisa uma vez só.

## 5.6 Cópia de assets à prova de interrupção

**Decisão.** Uma cópia interrompida hoje deixa a pasta incompleta **para sempre**.

**Mudança.**
- A cópia usada por `VoskSttEngine.loadModel` e
  `PiperSherpaOnnxTtsEngine.copyEspeakDataToFilesystem` passa para uma função única,
  `libras/audio/CopiaDeAssets.kt`.
- Ela grava um arquivo marcador `.completo` **só depois** de copiar tudo. Pasta sem marcador é
  apagada e copiada de novo.
- A função recebe "listar" e "abrir" como parâmetros, para ser testável sem `AssetManager`.

**Teste (JVM).** Uma cópia que falha no meio seguida de uma nova chamada termina com todos os
arquivos e o marcador.

## 5.7 Lista fechada de frases (P2)

**Decisão.** Plano B, desligado por padrão.

**Mudança.** Interruptor "Transcrição restrita ao roteiro" nas configurações de demo. Ligado, o
`VoskSttEngine` cria o `Recognizer` com uma gramática contendo as respostas do atendente do
roteiro (2.9). Aparece "restrita ao roteiro" na faixa de estado, para ninguém esquecer ligado.

## 5.8 Qualidade da voz

Teste de escuta: comparar a voz atual (`pt_BR-edresson-low`) com uma pt-BR "medium" do Piper,
se houver uma disponível para o sherpa-onnx (a confirmar). Só troca se a diferença for grande.
Está no [guia de testes](../guia-de-testes-mock-e-oculos.md).

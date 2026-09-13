# 8. Memória RAM

Decisões anteriores deixam mais coisa carregada o tempo todo: o MediaPipe desde a abertura
(3.1) e o avatar pré-carregado no aquecimento (6.3). O único número medido é de **emulador**:
app ~100 MB + processo do Unity ~307 MB + serviço da WebView ~60 MB = **~455 MB**, sem os
modelos de reconhecimento. O hardware da demo é incerto.

Diagnóstico: [mapa de riscos §11](../riscos-demo-2026-09-13.md#11-memória-ram).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 8.1 | Reagir à falta de memória liberando primeiro o avatar | P1 |
| 8.2 | Seletor "Avatar: pré-carregar / carregar no primeiro uso" | P2 |
| 8.3 | Motores de reserva e alternativos criados só quando usados | P1 |
| 8.4 | Memória por estado e requisito mínimo de RAM | Teste |
| 8.5 | APK de ~496 MB | operação (ponto 11) |

---

## 8.1 Reação à falta de memória

**Decisão.** Liberar em ordem de sacrifício: **primeiro o avatar** (a legenda cobre), depois os
motores de reserva ociosos. Reconhecimento, fala e escuta **nunca** são liberados no meio de um
atendimento.

**O que existe hoje.** Nada. O comentário de `AvatarPlayer.release()` cita `onTrimMemory`, mas
nenhum código implementa. O único tratamento é o `onRenderProcessGone`, que evita que a morte do
processo do Unity derrube o app.

**Mudança.**
- **Dois gatilhos**, porque um só não basta:
  1. `ComponentCallbacks2.onTrimMemory`, registrado pelo `CameraViewModel` na `Application`.
     **A verificar:** a partir do Android 14, os níveis de "rodando com pouca memória" deixam
     de ser entregues a apps em primeiro plano, e este gatilho pode nunca disparar durante a
     demo.
  2. **Verificação ativa** na amostragem por segundo do painel (3.8): se
     `ActivityManager.MemoryInfo.availMem` cair abaixo de `1,5 × threshold` (ou `lowMemory`
     ficar verdadeiro), dispara a mesma liberação.
- **A liberação** (`libras/diagnostico/PressaoDeMemoria.kt`):
  1. se o avatar **não** estiver animando: `AvatarPlayer.release()` e aviso informativo na
     faixa de estado ("avatar liberado por falta de memória; resposta em legenda");
  2. se estiver animando: deixa terminar e libera em seguida;
  3. motores de reserva ociosos: o TTS do Android, se não estiver em uso, e o motor de wake word
     não selecionado.
- O avatar volta a carregar no próximo "iniciar" pela regra do 9.5, se a memória tiver voltado
  acima do limiar.

**Teste (JVM).** A decisão (o que liberar, dado o estado e a memória disponível) é uma função
pura: não libera no meio da animação; libera a reserva só se estiver ociosa.
**Manual:** `adb shell am send-trim-memory <pacote> RUNNING_LOW` (quando o aparelho entregar)
e o limiar forçado baixo pelas configurações de demo.

## 8.2 Avatar pré-carregado ou no primeiro uso (P2)

**Decisão.** Manter o pré-carregamento como padrão. Deixar a opção para um aparelho fraco.

**Mudança.** Seletor "Avatar: pré-carregar / carregar no primeiro uso" nas configurações de demo.
Em "primeiro uso", o aquecimento pula a etapa 6 e o avatar carrega no primeiro ⑦. A espera de 6
a 9 s aparece como "carregando avatar…" com a legenda já visível.

## 8.3 Nada carregado em dobro

**Decisão.** Um motor de cada tipo carregado por vez.

**Mudança.**
- **Voz:** o `TtsEmCadeia` (5.5) cria o `AndroidTextToSpeechEngine` só quando o Piper falha.
- **Wake word:** trocar o motor (4.5) libera o anterior (`stop()`) antes de criar o novo.
- **Transcrição:** o `AndroidSpeechRecognizerSttEngine` continua sem ser instanciado. Só o Vosk
  existe.

**Teste.**
- **JVM:** a cadeia de voz não cria a reserva enquanto o Piper funciona.
- **Manual:** trocar o motor de wake word duas vezes e conferir no `dumpsys meminfo` que a memória
  não sobe a cada troca.

## 8.4 Memória por estado

Teste: `dumpsys meminfo` em cada estado (aguardando, capturando, falando, escutando, avatar),
incluindo o processo da WebView. Dessa medição sai o **requisito mínimo de RAM** do aparelho da
demo. Comandos e tabela no [guia de testes](../guia-de-testes-mock-e-oculos.md).

## 8.5 Tamanho do APK

Operação: instalar o APK final com antecedência e manter uma cópia no notebook
([ponto 11](11-operacao-de-palco.md)). Reduzir o APK de verdade, baixando os modelos externos
na primeira execução, fica para depois da demo.

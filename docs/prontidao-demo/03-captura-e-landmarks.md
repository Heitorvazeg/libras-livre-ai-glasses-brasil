# 3. Captura e landmarks

O caminho do vídeo dos óculos até os landmarks normalizados. Inclui o **defeito A** da revisão.

Diagnóstico: [mapa de riscos §1 e §2](../riscos-demo-2026-09-13.md#1-captura-de-vídeo-óculos--celular).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 3.1 | MediaPipe carregado **uma vez**, ocioso; sessão não depende do primeiro frame; indicador "pode sinalizar" | P0 |
| 3.2 | Tratar o stream pausado pelo toque na haste | P1 |
| 3.3 | Stream **liga e desliga por estado da sessão**, como hoje | sem mudança · Teste |
| 3.4 | Causa de falha da câmera na tela | P1 |
| 3.5 | Indicador "tronco fora do quadro" | P1 |
| 3.6 | Descartar mãos longe dos pulsos; ficar com a pessoa mais próxima | P1 |
| 3.7 | Rotação do vídeo | Teste (roteiro com os óculos) |
| 3.8 | Painel de métricas (tela + CSV) · decodificador de hardware só se preciso (3.8b) | P1 · P2 |
| 3.9 | Seletor CPU/GPU do MediaPipe no menu de debug | P2 |

---

## 3.1 A corrida do "iniciar" (defeito A)

**Decisão.** Opção (a): o MediaPipe é carregado ao abrir o app e fica **ocioso na memória**,
sem gastar CPU nem bateria. O processamento continua acontecendo só durante a captura, e o
stream continua ligando e desligando por estado (3.3).

**O defeito.**
1. O `LandmarkExtractor` é criado no primeiro frame de vídeo e fechado a cada fim de stream
   (`clearStreamResources` → `landmarkPipeline.stop()`).
2. O `beginSignSession` abre a sessão assim que o stream fica `STREAMING`, que chega antes do
   primeiro frame.
3. O `startSession()` encontra o extrator nulo, grava "Modelos do MediaPipe não carregaram" e
   **retorna sem ligar a coleta**.

**Mudança.**
- `libras/reconhecimento/LandmarkPipeline.kt`:
  - o extrator é criado no aquecimento ([6.4](06-latencia.md#64-aquecimento-com-diagnóstico)),
    ou na primeira chamada, **fora** da thread de frames;
  - o `stop()` **não** fecha mais o extrator; ele só é fechado no `dispose()`, depois de a
    thread do `ImageReader` terminar. Isso também elimina o possível crash de fechar o
    MediaPipe no meio de uma extração;
  - o `startSession()` liga a coleta mesmo sem frame e marca "aguardando o primeiro frame
    válido";
  - o primeiro frame normalizado da sessão (pose e ombros válidos) muda `LibrasState` para
    **pode sinalizar**.
- A faixa de estado ([10.2](10-tela.md#102-faixa-de-estado)) mostra "Aguarde…" e depois "Pode
  sinalizar".
- Se o extrator não carregar (asset ausente), o erro aparece no aquecimento, e não no meio da
  sessão.

**Teste (manual, `MockDeviceKit`).**
1. **Antes da correção:** sessão com os óculos iniciada **sem** preview, tocar Iniciar e ver o
   erro falso. Isso confirma o defeito.
2. **Depois:** o mesmo roteiro coleta frames, e o CSV do gravador (1.9) tem linhas.
3. **Repetir por três turnos seguidos.** O defeito aparecia a partir do segundo.

**Pronto quando** três turnos seguidos iniciados por botão, sem preview, coletam frames e
mostram "pode sinalizar".

**Como ficou a onda 1.**
- `LandmarkPipeline.carregarModelos()` é chamado pelo `CameraViewModel` ao abrir o app (a etapa 1
  do aquecimento do 6.4, antes de o `Aquecimento` existir). Se ainda não terminou no "iniciar", o
  `startSession()` liga a coleta mesmo assim e dispara uma nova tentativa fora da thread de frames.
- "Aguarde…" e "Pode sinalizar" aparecem na linha de estado mínima da onda 1 (ver 10.1); a faixa
  do 10.2 a substitui na onda 4.
- `framesExtraidosNaSessao` conta os frames que passaram pelo MediaPipe na sessão (base do 3.5 e
  do 3.8).
- **Testes automatizados:**
  - `LandmarkPipelineTurnosTest`: três turnos seguidos, cada um com a sessão aberta antes do
    primeiro frame e `stop()` no fim, alimentados com os frames HEVC do `plant.mp4`. Todos coletam
    frames, sem erro;
  - `FluxoOnda1Test`: "Iniciar" pela tela, sem preview, mostra "Aguarde…" e não mostra o erro
    falso.

**Achado depois da onda 1: o MediaPipe nunca produziu landmarks, em aparelho nenhum.** Dois
defeitos, corrigidos antes da onda 2:
1. **Formato da imagem.** O `LandmarkExtractor` entregava o frame YUV_420_888 pelo
   `MediaImageBuilder`, e o `AndroidPacketCreator` do MediaPipe (conferido no bytecode da 0.10.14 e
   em execução na 0.10.35) só aceita `android.media.Image` em RGBA_8888: **toda** extração lançava,
   e o `LandmarkPipeline` só registrava no log. Pôr o `ImageReader` em RGBA não serve (o
   decodificador entrega YV12 e o `ImageReader` recusa). Correção: `YuvParaArgb` (função pura, com
   teste JVM) converte para um `Bitmap` ARGB reaproveitado, entregue pelo `BitmapImageBuilder`.
   Custo medido no emulador, frame 540x960: conversão com mediana de 5 ms; pose + mãos, 35 ms.
2. **Versão.** A 0.10.14 não traz a biblioteca nativa para x86_64 (o emulador do guia não a
   carregava) e a de arm64 é alinhada a 4 KB (não carrega em aparelhos com página de 16 KB).
   Subiu para **0.10.35**, que traz x86_64 e arm64 alinhadas a 16 KB, sem mudança de API no app.

**Testes automatizados (revistos):** o `LandmarkPipelineTurnosTest` roda no emulador com o
`pessoa.mp4` (uma pessoa de corpo inteiro, ver `androidTest/assets/pessoa.LEIAME.txt`): nos três
turnos, todos os frames passam pelo MediaPipe e "pode sinalizar" acende. Na medição, 115 de 115
extrações com pose.

## 3.2 Stream pausado pelo toque na haste

**Decisão.** Tratar o estado `PAUSED`, com plano completo.

**Mudança.**
- **Detectar:** o `CameraViewModel` repassa ao `DialogOrchestrator` as mudanças para `PAUSED` e
  a volta para `STREAMING`.
- **Mostrar:** a faixa de estado exibe "Stream pausado nos óculos — toque na haste para
  retomar".
- **Durante a captura (②):**
  - o detector **congela**: a pausa não fecha o sinal em andamento, não conta como
    inatividade e não conta como falha do fluxo "repita";
  - ao retomar, o indicador volta a "aguarde o primeiro frame válido".
- **Pausa longa:** passando de `tetoPausaMs` (30 s), a sessão encerra com aviso na tela e
  estado ①, sem travar.
- **No "iniciar":** se o stream estiver pausado, `ensureCameraActive` mostra a mensagem e
  espera a retomada até o teto, em vez de chamar `startStreaming()` (que sai cedo) e desistir
  em silêncio depois de 8 s.

**Teste (manual, `MockDeviceKit`).** O menu de debug tem **tap** (pausa/retoma):
- pausa no meio de um sinal e retoma: o sinal não é cortado;
- pausa de mais de 30 s: a sessão encerra com aviso;
- "iniciar" com o stream pausado: aparece a mensagem.

## 3.3 Stream por estado

Sem mudança de código: continua ligando no "iniciar" e desligando ao fim da captura. O custo,
esperar o stream subir a cada turno, fica **visível** com o 3.1 e é medido no 6.5. O tempo
"iniciar → pode sinalizar" vai para o [guia de testes](../guia-de-testes-mock-e-oculos.md).

## 3.4 Causa de falha da câmera na tela

**Decisão.** Toda falha ao subir a câmera aparece na tela com a causa.

**Mudança.**
- `camera/CameraViewModel.ensureCameraActiveForLibras` passa a devolver um resultado com
  motivo, em vez de `Boolean`:

  | Motivo | Mensagem |
  |---|---|
  | sem dispositivo ativo | "Óculos não conectados" |
  | permissão de câmera pendente | "Permissão de câmera dos óculos pendente" |
  | atualização obrigatória | "Os óculos pedem atualização" |
  | sessão sem ficar pronta a tempo | "Óculos não responderam (dobrados ou fora do rosto?)" |
  | stream sem ficar pronto a tempo | "Câmera não subiu (bateria ou temperatura dos óculos?)" |
  | pausado | ver 3.2 |

- Os erros do SDK que hoje só vão para a snackbar também passam pela faixa de estado.

**Teste (manual, `MockDeviceKit`).** Desligar o dispositivo simulado, **doff** e **fold** antes
do "iniciar": cada um mostra a mensagem correspondente.

## 3.5 Tronco fora do quadro

**Decisão.** Indicar quando a normalização descarta frames por falta de ombros.

**Mudança.** Durante a captura, o `LandmarkPipeline` conta, numa janela deslizante de 1 s, os
frames processados e os descartados. Mais de 50% descartados por mais de 1 s mostra:
- "Afaste-se: tronco fora do quadro", se há pose sem ombros visíveis;
- "Ninguém no quadro", se não há pose.

As contagens também vão para o painel (3.8) e para o CSV (1.9).

**Teste (manual).** Vídeo no `MockDeviceKit` com a pessoa cortada nos ombros.

## 3.6 Mãos e corpos que não são da pessoa surda

**Decisão.** Descartar mãos longe dos pulsos da pose e ficar com a pessoa mais próxima.

**Mudança.** `libras/reconhecimento/LandmarkExtractor.kt`:
- **Pose:** `setNumPoses(2)` e escolher a de **maior distância entre ombros** (a mais próxima
  da câmera).
- **Mãos:** detectar até **4** (`setNumHands(4)`). Com 2, as duas mãos do atendente podem
  ocupar as vagas e esconder as da pessoa surda.
- **Filtro:** manter só as mãos cujo punho esteja a menos de `raioPulso` (inicial: 0,5
  largura de ombro) de um pulso da pose escolhida. A atribuição de lado é a do 2.4.
- **Custo:** mais pessoas e mãos detectadas custam processamento. Medir no painel (3.8); se
  pesar, voltar a 2 mãos mantendo o filtro.

**Teste (JVM).** A função pura de filtro e atribuição cobre:
- mão do atendente (longe dos pulsos) é descartada;
- duas pessoas: fica a de ombros mais afastados;
- mão da pessoa surda com rótulo trocado.

## 3.7 Rotação do vídeo

Não é código agora: é verificação do primeiro teste com os óculos. Se o vídeo vier girado, o
ajuste é na rotação passada ao MediaPipe no `LandmarkExtractor`. Está no
[guia de testes](../guia-de-testes-mock-e-oculos.md).

## 3.8 Painel de métricas

**Decisão.** Uma estrutura que permita **medir na demo**, no aparelho que houver.

**Mudança.** Novo `libras/diagnostico/Metricas.kt`, um coletor único usado também pelos pontos
6, 7 e 8:

| Métrica | Fonte |
|---|---|
| fps recebido dos óculos | `CameraViewModel.handleVideoFrame` |
| fps decodificado para inferência | callback do `ImageReader` |
| fps processado pelo MediaPipe e % de frames descartados (3.5) | `LandmarkPipeline` |
| ocorrências de "fila do decodificador cheia" | contador no `stream/HevcDecoder.kt` |
| tempo por etapa | 6.5 |
| folga e estado térmico | `PowerManager.getThermalHeadroom` / `currentThermalStatus` (7.3) |
| bateria do celular | `BatteryManager` |
| RAM do app | `ActivityManager` / `Debug` (8.4) |
| bateria dos óculos | **se o SDK expuser** — a verificar |

- **Na tela:** um overlay com amostragem por segundo, ligado pelas configurações de demo.
- **Em arquivo:** linhas `tipo=metrica` no CSV da sessão (1.9), uma por segundo.

**3.8b (P2): decodificador de hardware.** Se o painel mostrar fps processado baixo ou fila
cheia, o `HevcDecoder` ganha uma opção para usar o decodificador de hardware **só** no caminho
da inferência, selecionável nas configurações de demo. O preview continua em software.

**Pronto quando** uma sessão no emulador gera o overlay e as linhas de métrica no CSV.

**Como ficou a onda 2.** `libras/diagnostico/Metricas.kt` (contadores atômicos, uma amostra por
segundo, marcas por etapa do 6.5) e `LeitorSistema.kt` (folga e estado térmico, bateria, RAM do app
por `Debug.getPss`). O `CameraViewModel` amostra a cada segundo; a leitura do sistema só roda com o
painel ou o gravador ligados. O overlay `PainelMetricas` aparece abaixo do painel de conversa com
"Painel de métricas" ligado nas configurações de demo, e o gravador escreve uma linha `metrica` por
valor. "Fila do decodificador cheia" soma o decoder de inferência (acumulado entre streams) e o do
preview. A bateria dos óculos não entra: o SDK não a expõe (7.4). Teste: `MetricasTest` (JVM).

## 3.9 MediaPipe em CPU ou GPU (P2)

**Decisão.** O hardware da demo é incerto, então a comparação é feita no aparelho.

**Mudança.** Um seletor "MediaPipe: CPU / GPU" nas configurações de demo. O `LandmarkExtractor`
é recriado com `BaseOptions.setDelegate(GPU)` quando o seletor muda, **fora** de uma sessão
ativa. Se a GPU falhar ao criar, volta para CPU e avisa.

---

## Como ficou a onda 4 (3.2, 3.4, 3.5, 3.6)

- **3.2:** o `CameraViewModel` repassa `PAUSED`/retomada ao `DialogOrchestrator.onStreamPausado`. Na
  captura, a pausa cancela os relógios de silêncio e de teto; na volta, o detector desconta o intervalo
  sem frames (`SignBoundaryDetector.descontarPausa`: a pausa não fecha o sinal nem vira oclusão) e o
  indicador volta a "Aguarde…". Passando de 30 s (`TETO_PAUSA_MS`), a captura encerra sem decisão (não
  conta para o "repita"), com aviso, e o diálogo volta ao ①. No "iniciar" com o stream pausado, a
  faixa mostra a mensagem e o app espera a retomada até o teto. Teste instrumentado
  (`FluxoOnda4Test`): tap no meio da captura mostra "Stream pausado nos óculos" e mantém "Capturando";
  o segundo tap tira o aviso. **Limitação:** o recorte de um sinal que atravessa a pausa inclui o
  intervalo de tempo parado na reamostragem, e uma pausa maior que a duração máxima do sinal (3,5 s) no
  meio dele fecha o sinal por duração máxima na volta; a pausa de 30 s não foi testada automaticamente.
- **3.4:** `ensureCameraActiveForLibras` devolve `FalhaCamera` (sem dispositivo, permissão pendente,
  atualização obrigatória, sessão sem resposta, stream que não subiu, pausa longa) e a causa vai para a
  faixa. Os erros de sessão e de stream do SDK também. Testes: `FalhaCameraTest` (JVM) e
  `FluxoOnda4Test` (permissão negada sem confirmar: aparece "Permissão de câmera dos óculos pendente").
  **Pendente:** power off, doff e fold no `MockDeviceKit` (A14) — sem óculos ativos, o botão principal
  já fica desabilitado com "Conecte os óculos".
- **3.5:** `JanelaEnquadramento` (janela de 1 s, > 50% descartados por > 1 s); `ResultadoFrame` distingue
  sem pose de sem ombros. O estado vai para `LibrasState.enquadramento`, a faixa e o CSV (evento
  `enquadramento`); a porcentagem sem pose já estava no painel. Teste: `JanelaEnquadramentoTest`.
- **3.6:** `setNumPoses(2)` com a pose de ombros mais afastados; `setNumHands(4)` com o filtro de punho a
  menos de 0,5 largura de ombro de um pulso; a atribuição de lado continua a do 2.4. Teste:
  `AtribuicaoMaosTest` (mão do atendente descartada, duas pessoas, rótulo trocado). O custo das 2 poses
  e 4 mãos precisa ser medido no painel do aparelho real.

# 1. Segmentação: `SignBoundaryDetector`

Decide, frame a frame, se a pessoa está sinalizando ou parada, e portanto onde cada sinal
começa e termina. Se o corte sai errado, o classificador erra mesmo sendo bom.

Diagnóstico completo: [mapa de riscos §3](../riscos-demo-2026-09-13.md#3-segmentação-signboundarydetector).

**Premissas confirmadas pelo time:**

- quem sinaliza volta ao **repouso natural de Libras** entre os sinais, com as mãos
  **visíveis** no quadro;
- todos os datasets de treino são **um sinal por clipe, começando e terminando em repouso**.
  O segmento que o app entrega precisa ter essa mesma forma.

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 1.1 | Segmento só do movimento, com margem de repouso antes e depois | P0 |
| 1.2 | Velocidade medida numa janela fixa de tempo, em ombros/s | P0 |
| 1.3 | Média por mão; máximo entre os articuladores | P0 |
| 1.4 | Histerese (dois limiares) e suavização curta | P0 |
| 1.5 | Duração mínima medida no movimento | P0 |
| 1.6 | Oclusão como caminho próprio, teto de 900 ms | P0 |
| 1.7 | Protocolo: repouso entre os sinais | operação (ponto 11) |
| 1.8 | Valores iniciais estimados; editáveis no menu de debug | P0 (valores) · P1 (edição) |
| 1.9 | Gravador de sessão em CSV, ligado pelo menu de debug | P0 |
| 1.10 | Calibração rápida com os óculos garantida; busca em grade se sobrar tempo | P1 (script de estatísticas) · P2 (grade) · Teste |
| 1.11 | "● sinalizando / ○ parado" e contador de sinais na tela | P1 |

---

## 1.1 Segmento só do movimento

**Decisão.** O classificador recebe o sinal com uma margem de repouso, não todo o tempo
desde o sinal anterior.

**Mudança.**
- `libras/reconhecimento/LandmarkPipeline.kt`: um **buffer circular** guarda os frames
  normalizados dos últimos `preRollMs` (com timestamp). Ao entrar em SINALIZANDO, o
  segmento começa com esse pré-roll. Ao fechar, corta tudo depois de
  `tsUltimoMovimento + posRollMs`.
- O detector passa a informar o intervalo do segmento (`inicio`, `fimDoMovimento`) no
  callback de boundary, em vez de só "terminou".
- A imputação de mãos roda sobre o segmento recortado, na linha do tempo real (ver 2.2/2.3).
- Valores iniciais: `preRollMs = 250`, `posRollMs = 150`. Serão ajustados pela medição do
  repouso nas bordas dos clipes do MINDS
  ([modelo de visão](../modelo-visao-pontos-de-teste.md), item "forma do segmento").

**Teste (JVM).** Sequência sintética com 6 s parado + 1 s de sinal + pausa: o segmento
entregue dura ~1 s + margens, e não ~7,7 s.

**Pronto quando** o segmento nunca contém o repouso anterior ao pré-roll.

## 1.2 Velocidade numa janela fixa de tempo

**Decisão.** O limiar deixa de ser "deslocamento entre dois frames" e passa a ser
velocidade, independente do fps processado.

**Mudança.** `SignBoundaryDetector` mantém os frames recentes com timestamp e compara o frame
atual com o **mais recente que tenha pelo menos `janelaVelocidadeMs` (100–120 ms) de idade**,
dividindo pelo intervalo real. Unidade: larguras de ombro por segundo.

**Teste (JVM).** O mesmo movimento amostrado a 24 fps e a 12 fps produz as mesmas transições
de estado (tolerância de um frame).

**Pronto quando** o teste de fps passa e nenhum limiar no código depende de "por frame".

## 1.3 Média por mão, máximo entre articuladores

**Decisão.** A soma de 42 distâncias acumulava tremor e mudava de escala com o número de mãos
visíveis.

**Mudança.** Por mão: média da velocidade dos 21 pontos, só se a mão está presente nos dois
frames comparados. Pulsos: velocidade dos pontos de pulso da pose. Velocidade final: **máximo**
entre mão esquerda, mão direita e pulsos.

**Teste (JVM).**
- Mãos paradas com tremor gaussiano (σ = 0,014 ombro por ponto por frame) nunca entram em
  SINALIZANDO.
- Um sinal de uma mão só, com a outra ausente, dá a mesma velocidade que com a outra parada.

## 1.4 Histerese e suavização

**Decisão.** Dois limiares e média móvel curta.

**Mudança.**
- Média móvel exponencial sobre a velocidade (α inicial 0,5).
- `limiarEntrada` para PARADO → SINALIZANDO.
- `limiarSaida` (menor) para contar "ainda em movimento".

**Teste (JVM).** Movimento lento oscilando entre os dois limiares depois de começar não fecha o
segmento.

## 1.5 Duração mínima medida no movimento

**Decisão.** Hoje a duração inclui a pausa de 700 ms e qualquer espasmo passa do mínimo. É
pré-requisito do fluxo "repita" (2.8).

**Mudança.** `duracaoMovimento = tsUltimoMovimento − tsInicio`. Abaixo de `duracaoMinimaMs`, o
segmento é **descartado em silêncio**: não classifica e **não** chama
`onRecognitionFailed` (não conta como falha).

**Teste (JVM).** Espasmos de 1 a 4 frames não disparam boundary. Um sinal de 300 ms dispara.

## 1.6 Oclusão como caminho próprio

**Decisão.** Com repouso visível, quem fecha o sinal é a pausa. A oclusão cobre só perda de
detecção no meio do sinal (borrão, mão na frente do rosto). Teto inicial de 900 ms, a ajustar
com testes reais.

**Mudança.**
- Enquanto as duas mãos estão ausentes, o relógio da pausa **não avança**.
- O segmento fecha por oclusão só quando a ausência passa de `tetoOclusaoMs`.

**Teste (JVM).** Ausência de 600 ms no meio do sinal não fecha. Ausência de 1.000 ms fecha.

## 1.7 Protocolo de repouso

Não é código. Vai para o roteiro de palco e para o ensaio ([ponto 11](11-operacao-de-palco.md)).

## 1.8 Valores iniciais, editáveis

**Decisão.** Começar com os valores estimados, marcados no código como **estimados, não
calibrados**, com referência a este arquivo.

| Parâmetro | Valor inicial |
|---|---|
| `limiarEntrada` | 0,7 ombros/s |
| `limiarSaida` | 0,4 ombros/s |
| `janelaVelocidadeMs` | 100–120 ms |
| α da média móvel | 0,5 |
| `pausaMs` (fecha o sinal) | 500 ms → **800 ms** (calibrado, ver nota) |
| `tetoOclusaoMs` | 900 ms |
| `duracaoMinimaMs` (do movimento) | 250 ms |
| `duracaoMaximaMs` | 3.500 ms |
| `preRollMs` / `posRollMs` | 250 / 150 ms |

**Calibração de `pausaMs` (17/09/2026).** O valor inicial de 500 ms partia um sinal em dois nos
clipes reais do MINDS: holds internos acumulam ~480 ms com `limiarSaida` em 0,4, e o relógio da
pausa não zera nos frames sem mão (só não avança). Passou a 800 ms, medido nos traços do gravador
e confirmado nos seis clipes — [rodada de 17/09](../integracao-video-minds-e-calibracao-2026-09-17.md).
Os limiares de velocidade e o teto de oclusão **não** foram calibrados: dependem do protocolo
R1/R2 com os óculos, porque o piso de ruído do emulador não é o do hardware.

**Mudança (P1).** Os parâmetros ficam num `ParametrosSegmentacao` (data class) lido das
[configurações de demo](10-tela.md#106-configurações-de-demo). Assim a calibração rápida com os
óculos ajusta os valores **sem gerar outro APK**, com um botão "voltar ao padrão".

**Por que isso importa para 2.2:** com `duracaoMaximaMs` de 3,5 s e margens de 0,4 s, um
segmento tem no máximo ~94 frames a 24 fps, abaixo dos 96 do contrato do classificador. Se a
edição permitir durações maiores, o app registra no log os segmentos acima de 96 frames.

## 1.9 Gravador de sessão (CSV)

**Decisão.** CSV, ligado por interruptor no menu de debug.

**Mudança.**
- Novo `libras/diagnostico/GravadorSessao.kt`: um arquivo por sessão em
  `getExternalFilesDir(null)/sessoes/<AAAAMMDD-HHMMSS>.csv`, recuperável sem root com
  `adb pull /sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes`.
- **Linhas de frame:** `ts_ms`, estado do detector, velocidades (mão esq, mão dir, pulsos,
  final suavizada), presença (pose, mão esq, mão dir) e os 57 pontos × 3 coordenadas
  normalizadas.
- **Linhas de evento**, no mesmo arquivo com `tipo=evento`: início e fim de segmento,
  descarte por duração mínima, classificação (glosa, confiança, margem) e decisão do
  avaliador (2.8).
- A escrita roda numa thread própria com buffer, **nunca** na thread do `ImageReader`.
- Nas ondas 1–3, o interruptor mora num armazenamento mínimo de configurações
  (`ConfiguracoesDemo`, `SharedPreferences`), que o 10.6 completa.

**Teste.**
- **JVM:** o formatador de linha escreve o número certo de colunas e escapa corretamente.
- **Manual:** gravar uma sessão no `MockDeviceKit` e abrir o CSV no Python.

**Pronto quando** uma sessão gravada no emulador abre no pandas com uma linha por frame
processado.

## 1.10 Calibração

**Decisão.** A **calibração rápida** (~10 min com os óculos) é garantida. As gravações com os
óculos e a busca em grade entram se sobrar tempo. Os clipes do MINDS e as sequências
sintéticas ficam para depois da demo.

**Mudança.**
- **P1:** `scripts/calibracao_fronteiras.py` lê os CSVs do 1.9 e calcula:
  - o piso de ruído (p95 da velocidade com as mãos paradas);
  - os limiares sugeridos (saída ≈ 1,5–2× o piso; entrada ≈ 2,5–3×);
  - a maior pausa interna dos sinais;
  - a duração das perdas de detecção.
- **P2:** porte do detector para Python e busca em grade sobre gravações anotadas. Escolha
  pela sequência de glosas acertada de ponta a ponta, calibrando com duas pessoas e testando
  na terceira.

**Teste.** O procedimento está no [guia de testes](../guia-de-testes-mock-e-oculos.md), parte
"óculos em mãos".

## 1.11 Feedback visual

**Decisão.** Mostrar "● sinalizando / ○ parado" e o número de sinais capturados na sessão.

**Mudança.** O estado do detector e o contador sobem para `LibrasState` e são desenhados na
faixa de estado ([10.2](10-tela.md#102-faixa-de-estado)).

**Pronto quando** quem sinaliza vê a transição em menos de ~200 ms.

---

## Testes existentes afetados

`SignBoundaryDetectorTest.kt` testa a máquina de estados antiga (limiar por frame, pausa de
700 ms) e **precisa ser reescrito** junto com 1.2–1.6. Os casos novos estão listados em cada
subponto acima.

---

## Como ficou a onda 2

- **1.1:** `libras/reconhecimento/Segmentador.kt` junta o detector e uma janela de frames com
  timestamp (retenção limitada ao maior segmento possível) e entrega cópias do recorte
  `[inicio − preRollMs, fimDoMovimento + posRollMs]`. O `LandmarkPipeline` imputa as mãos sobre
  essas cópias (2.3). Teste: `SegmentadorTest` (6 s parado + 1 s de sinal → segmento de 1,0–1,8 s).
- **1.2–1.6:** `SignBoundaryDetector` reescrito; `SignBoundaryDetectorTest` reescrito com os casos
  do plano (24 × 12 fps, tremor σ = 0,014, uma mão só, histerese, espasmos de 1 a 4 frames, sinal
  de 300 ms, oclusão de 600 × 1.000 ms, duração máxima, fechamento forçado).
- **Divergência no 1.5 (como medir a duração):** `ultimoMovimento − inicio`, literal, faz um
  espasmo de 4 frames medir ~290 ms e passar do mínimo, porque a velocidade compara com um frame de
  ~110 ms atrás (o movimento continua "visível" por essa janela) e a média móvel estica o fim. A
  duração mínima passou a ser medida na velocidade **bruta**: do primeiro frame da sequência que
  levou à entrada até o último frame em movimento, menos `idade da referência − um frame`. Estado,
  pausa e recorte continuam na velocidade suavizada. Resultado nos testes: espasmo de 4 frames
  ≈ 167 ms (descartado), sinal de 300 ms ≈ 251 ms (aceito).
- **Fechamento forçado** (fim da sessão) também aplica a duração mínima: um espasmo no fim da
  sessão continua não sendo sinal.
- **1.8:** `ParametrosSegmentacao` com os valores da tabela, marcados como estimados. A edição nas
  configurações de demo é da onda 4.
- **1.9:** `libras/diagnostico/GravadorSessao.kt`. Um CSV por **sessão com os óculos** (abre quando a
  sessão começa com o gravador ligado, ou quando o interruptor liga no meio dela). Colunas: `tipo`,
  `ts_ms`, `turno`, `estado`, as cinco velocidades, presença de pose e mãos, `nome`, `detalhe` e
  `p00_x … p56_z`. Linhas `frame` (uma por frame que passou pelo MediaPipe, com os pontos vazios sem
  pose), `evento` (segmento, descarte, classificação, latência) e `metrica` (3.8). Formatação e
  escrita numa thread própria. Testes: `GravadorSessaoTest` (colunas, escape, arquivo) e o
  `LandmarkPipelineTurnosTest` instrumentado (uma linha por frame processado).

---

## Como ficou a onda 4 (1.8 edição, 1.10 script, 1.11)

- **1.8:** os dez parâmetros do `ParametrosSegmentacao` são editáveis em "Configurações de demo →
  Segmentação (valores estimados)", salvos entre execuções e lidos a cada nova captura. Uma combinação
  inválida (ex.: limiar de saída ≥ entrada) é recusada e o valor anterior fica. Teste:
  `ConfiguracoesDemoTest` (persistência e recusa).
- **1.10:** `scripts/calibracao_fronteiras.py` (só biblioteca padrão) lê os CSVs R1/R2 e imprime o piso
  de ruído (p95 de `v_suavizada` em R1), os limiares sugeridos (meio das faixas 1,5–2× e 2,5–3×), a
  checagem do p10 dentro dos sinais de R2, a maior pausa interna e a maior perda de mãos. Sai com
  código 1 se a checagem falha. Teste: `scripts/test_calibracao_fronteiras.py` (CSVs sintéticos no
  formato do gravador). A busca em grade (P2) não foi feita.
- **1.11:** `LibrasState` ganhou `estadoSinalizacao` e `sinaisNaSessao`; a faixa de estado mostra
  "● sinalizando · N sinais" / "○ parado · N sinais". O critério de ~200 ms depende do fps do aparelho
  (a mudança sai no frame em que o detector muda de estado) e fica para o teste com os óculos.

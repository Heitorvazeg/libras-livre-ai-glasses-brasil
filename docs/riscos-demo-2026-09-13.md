# Riscos da demo: o que pode falhar, por funcionalidade, e como resolver

**Data:** 2026-09-13 · **Prazo do hackathon:** 16/09/2026 · **Branch:** `dev`

Este documento existe para planejar **antes do primeiro APK de demo**. Ele parte da
premissa de que o classificador de visão já está exportado (`sinal_classifier.tflite`
com o sidecar `.json`) e pergunta: *o que ainda impede a demo de funcionar diante de
uma banca, com óculos reais, num ambiente que não controlamos?*

> **Decidido depois (2026-09-13).** O time revisou este mapa ponto a ponto. As decisões, o
> plano de implementação e as prioridades estão em [`prontidao-demo/`](prontidao-demo/README.md);
> onde divergirem das recomendações daqui, **vale o plano**. A lista das divergências está no fim
> do [README do plano](prontidao-demo/README.md#onde-este-plano-diverge-do-mapa-de-riscos). Este
> arquivo continua como a fotografia do diagnóstico.

## Como ler

Cada risco traz uma etiqueta de evidência. É a mesma disciplina de
[`README.md`](README.md#como-ler-os-números-deste-projeto): não confundir o que foi
lido no código com o que é suposição.

| Etiqueta | Significa |
|---|---|
| **[código]** | verificado lendo o código nesta data, com o arquivo e a linha citados |
| **[medido]** | número medido e registrado em outro documento, que é citado |
| **[hipótese]** | provável, mas **só medição em aparelho confirma** |

Caminhos de código abreviados: `libras/` = `mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/`,
`camera/` = o pacote irmão `.../cameraaccess/camera/`.

---

## 0. Resumo: os riscos em ordem de estrago

> **Revisão completa do código (2026-09-13, mesma data).** Uma segunda leitura, arquivo por
> arquivo, achou defeitos que **quebram a demo mesmo com tudo calibrado**. Eles entram no
> topo da tabela (linhas A a E) e têm seção própria abaixo.

| # | Risco | Funcionalidade | Estrago na demo | Custo de mitigar |
|---|---|---|---|---|
| A | Quando o "iniciar" liga a câmera, o reconhecimento **não liga**: corrida entre o stream subir e o MediaPipe carregar | Captura (§1.4) | **bloqueia**: a sessão abre, nenhum sinal é capturado | baixo |
| B | Os classificadores de wake word treinados **não carregam** no app (pesos em `.onnx.data`, lidos como bytes) | Turnos (§6.3) | **bloqueia** a wake word offline, em silêncio | baixo |
| C | O prazo de 1,5 s da contextualização não corta nada, e o laço de decodificação **ignora o fim de frase** | Contextualização (§5.1) | alto: toda frase paga o pior caso de latência | baixo |
| D | O ⑦ pode travar a conversa por até ~105 s (timeouts de rede e animação), com os botões desabilitados | Avatar (§8.4) | alto: a demo congela | baixo |
| E | A tela nunca mostra o sinal reconhecido nem a frase falada | Tela (§12) | alto: a banca não vê o que o sistema entendeu | baixo |
| 1 | O segmento classificado inclui o repouso antes do sinal, o limiar depende do fps e acumula ruído, e qualquer espasmo vira sinal | Segmentação (§3) | **alto**: o classificador erra mesmo sendo bom | médio |
| 2 | O contrato de entrada do `.tflite` diverge do app (z, T fixo, rótulos, mão esquerda/direita) | Classificação (§4) | **alto**: erra **em silêncio** | baixo a médio |
| 3 | O ponto de vista dos óculos corta os ombros ou pega as mãos de outra pessoa | Captura e landmarks (§1, §2) | **alto**: nenhum sinal é reconhecido | baixo (operacional) |
| 4 | Quatro comandos de voz por turno, disputa de microfone no ⑤, wake word dependente de rede | Turnos (§6) | médio-alto: a demo trava entre os turnos | **baixo** |
| 5 | A fala sai nos óculos (a banca não ouve); o HFP pode vir a 8 kHz | Áudio (§7) | médio: a funcionalidade existe e ninguém percebe | **baixo** |
| 6 | Câmera religada a cada turno, Vosk carregado só na hora, Unity carregando durante a captura | Latência (§10) | médio: pausas longas e o primeiro sinal perdido | baixo |
| 7 | A tela do celular apaga, a bateria dos óculos bloqueia a câmera, aquecimento | Bateria e temperatura (§9) | médio: falha no meio da apresentação | baixo (operacional) |
| 8 | Memória: ~455 MB só de app mais avatar, medidos em emulador x86; nunca medido em ARM | Memória (§11) | médio: o sistema mata o renderer ou o app | baixo (escolher o aparelho) |
| 9 | O avatar depende de rede | Avatar (§8) | médio: a legenda assume | baixo |

**Recomendação central:** montar uma **configuração de demo** que corte as dependências
mais frágeis sem apagar a arquitetura de produto:

- áudio todo no celular (sem troca para HFP);
- avanço automático entre os turnos, com os botões como piso;
- stream mantido ligado durante o atendimento;
- tudo pré-carregado ao abrir o app;
- protocolo de sinalização com pausa entre os sinais.

As seções abaixo justificam cada item.

---

## 1. Captura de vídeo (óculos → celular)

### 1.1 A câmera religa a cada turno — [código]

`DialogOrchestrator.endSignSession` desliga o stream ao fim do ② (`deactivateCamera()`),
e `beginSignSession` religa no próximo "iniciar". Os tetos de espera são 6 s para a
sessão e 8 s para o stream (`camera/CameraViewModel.kt:105-106`).

Depois que o stream sobe, o decoder de inferência ainda precisa de um *keyframe* para
entregar o primeiro frame (`LandmarkPipeline.ensurePipeline`, comentário do CSD). Nada
avisa a pessoa surda de que **já pode sinalizar**. Quem começa logo após o "iniciar"
perde o primeiro sinal.

**Resolução:**
- **Na demo:** manter o stream ligado durante o atendimento inteiro e desligar só no fim
  (o mesmo gatilho que hoje libera o avatar por inatividade). Custa bateria dos óculos
  (§9), mas elimina uma espera a cada turno.
- **Sinal de "pode sinalizar":** acender um indicador no banner quando chega o **primeiro
  frame normalizado** da sessão, e não quando o stream diz que subiu.

### 1.2 Rotação do feed — [código] e [hipótese]

A rotação está assumida como 0 (`libras/README.md`, "Ressalvas de paridade"). Se o vídeo
dos óculos vier girado, o MediaPipe não encontra a pose e o `LandmarkNormalizer` descarta
todos os frames. O sintoma é "nada acontece".

**Resolução:** é o primeiro teste com os óculos na cabeça. Abrir o preview e confirmar
que há landmarks. Se vier girado, ajustar `ImageProcessingOptions` no `LandmarkExtractor`.

### 1.3 Enquadramento em primeira pessoa — [hipótese], é o maior risco de produto

O `LandmarkNormalizer` **descarta o frame** se qualquer um dos ombros tiver visibilidade
baixa (`LandmarkNormalizer.kt:80`). Numa câmera presa na cabeça do atendente, a
pessoa surda muito perto pode ficar com os ombros fora do quadro, ou o atendente
pode olhar para baixo. Todos os números do modelo são sobre vídeo de estúdio frontal
(`CONTEXTO.md` §2).

**Resolução:**
- **Protocolo de palco:** marcar no chão a distância da pessoa surda (tronco inteiro no
  quadro) e pedir ao atendente que olhe para ela durante a sinalização. Medir essa
  distância no primeiro teste.
- **Indicador de enquadramento:** mostrar no banner "tronco fora do quadro" quando a
  taxa de frames descartados passar de um limiar. Transforma uma falha silenciosa em
  instrução.
- Gravar vídeos com os óculos já no primeiro teste. Eles alimentam o `MockDeviceKit`, a
  calibração (§3) e a avaliação do modelo com o ponto de vista real.

### 1.4 O "iniciar" liga a câmera, mas não liga o reconhecimento — [código]

**A sequência de hoje**, quando a câmera está desligada (sempre, a partir do segundo turno,
porque o fim do ② a desliga):

1. `beginSignSession` chama `ensureCameraActive()`, que espera `isStreaming`
   (`StreamState.STREAMING`, `camera/CameraViewModel.kt:780-787`) e devolve `true`.
2. Logo em seguida chama `landmarkPipeline.startSession()`.
3. `startSession()` exige o `LandmarkExtractor` pronto. Se ele for `null`, grava o erro
   "Modelos do MediaPipe não carregaram" e **retorna sem ligar a coleta**
   (`LandmarkPipeline.startSession`).
4. Só que o extrator é criado **no primeiro frame de vídeo que chega**
   (`feedCompressedFrame` → `ensurePipeline`), numa outra thread. E a cada fim de stream,
   `clearStreamResources` → `landmarkPipeline.stop()` **fecha e anula** o extrator.

O estado `STREAMING` chega antes do primeiro frame (a UI tem até um `hasReceivedFirstFrame`
separado por isso), e carregar Pose + Hands leva centenas de milissegundos. O resultado
provável: **o orquestrador fica em `CAPTURANDO_SINAIS`, o banner mostra um erro falso de
modelos ausentes e nenhum frame é coletado**. O roteiro de teste atual (`libras/README.md`,
"Como testar sem óculos") liga o **Preview antes** do Iniciar, e por isso não expõe a corrida.

**Como confirmar agora, no emulador:** `MockDeviceKit` com vídeo, sessão iniciada **sem**
preview, tocar Iniciar e olhar o banner.

**Correção:**
- Criar o `LandmarkExtractor` **uma vez** (no aquecimento, §10.3) e fechá-lo só no
  `dispose()`, não a cada `stop()`. Isso também elimina a recarga do MediaPipe a cada turno.
- `startSession()` não deve depender do primeiro frame: liga a coleta e deixa os frames
  entrarem quando chegarem.
- O indicador "pode sinalizar" (§1.1) acende no primeiro frame normalizado.

### 1.5 Outros jeitos de a câmera não subir — [código] e [hipótese]

| Situação | O que o app faz hoje | Resolução |
|---|---|---|
| **Um toque na haste dos óculos pausa o stream** (`CameraUiState.isPaused`: "paused by the device (single cap-touch tap)") | nada trata `PAUSED`: no ② os frames param em silêncio; no próximo "iniciar", `startStreaming()` sai cedo (`stream != null`) e o "iniciar" é ignorado após 8 s | instruir quem usa os óculos a não tocar na haste; mostrar "stream pausado nos óculos" no banner; tratar `PAUSED` no `ensureCameraActive` |
| **Permissão de câmera não concedida** | abre um diálogo que redireciona ao app Meta AI; o `ensureCameraActive` espera 8 s e desiste | conceder a permissão **antes** da demo e conferir no ensaio |
| **Atualização obrigatória do firmware ou do app nos óculos** (`WearablesViewModel`, `DEVICE_UPDATE_REQUIRED`) | a tela troca os controles por "atualização necessária" | congelar atualizações automáticas do app Meta AI e dos óculos na semana da demo; testar tudo no D-1 com as versões finais |
| **Sessão com os óculos pausada** (óculos dobrados, fora do rosto) — [hipótese] | `hasSession` é verdadeiro, `startStreaming()` falha com "inicie a sessão primeiro" e o "iniciar" espera 8 s | mensagem explícita; manter os óculos no rosto durante a apresentação |

---

## 2. Extração de landmarks

### 2.1 Mão esquerda/direita pode estar trocada em relação ao treino — [código] e [hipótese]

> É uma **suspeita a confirmar**, não um bug comprovado. A correção proposta vale a pena
> nos dois casos.

**Por que importa.** O modelo tem blocos separados na entrada para "mão esquerda" e
"mão direita" (`LandmarkNormalizer.OFFSET_MAO_ESQ`/`OFFSET_MAO_DIR`). Se as mãos chegam
invertidas, é como se a pessoa sinalizasse com a outra mão. Muitos sinais de Libras mudam
de significado ou ficam irreconhecíveis assim.

**Por que a suspeita existe: treino e app decidem "qual mão é qual" de jeitos
diferentes.**

| | Ferramenta | Como decide esquerda/direita |
|---|---|---|
| **Treino** (Python, `PoC/src/extract.py:99-100`) | MediaPipe **Holistic** | acha o corpo primeiro e pega a mão que está **no pulso esquerdo do corpo** e a que está no direito; decide **pela posição no corpo** |
| **App** (Kotlin, `LandmarkExtractor.kt:94-99`) | MediaPipe **HandLandmarker**, separado da pose | acha as mãos soltas e diz "parece uma mão esquerda"; decide **pelo formato da mão** |

A documentação do MediaPipe diz que o palpite do HandLandmarker assume **imagem
espelhada**, como a câmera frontal de selfie. O vídeo dos óculos **não** é espelhado.
Espelhar inverte esquerda e direita, então, se essa convenção valer aqui, o app rotula ao
contrário do treino.

O teste de paridade atual não pegaria isso: ele compara a **normalização** a partir de
landmarks já extraídos, não a **extração** sobre o mesmo vídeo.

**Resolução (resolve também o §2.2):** ignorar o rótulo do HandLandmarker e fazer como o
Holistic. A mão mais próxima do **pulso esquerdo da pose** (ponto 15) vai para o bloco
"esquerda", e a mais próxima do pulso direito (ponto 16), para "direita". A paridade com o
treino passa a valer por construção, com qualquer convenção de espelhamento.

**Verificação:** passar o mesmo vídeo pelo `extract.py` e pelo app (com o dump do §3.4)
e comparar qual mão cai em qual bloco.

### 2.2 Mãos que não são da pessoa surda — [código] e [hipótese]

`setNumHands(2)` pega **quaisquer** duas mãos do quadro, e `setNumPoses(1)` pega uma
pessoa qualquer (`LandmarkExtractor.kt:50,60`). Numa câmera em primeira pessoa, as
**mãos do próprio atendente** entram no quadro (gesticulando, segurando papel). Alguém
na fila atrás também pode virar a pose escolhida.

**Resolução:**
- A associação pelo pulso do §2.1 **descarta mãos longe dos dois pulsos da pose**. A mão do
  atendente, que está perto da câmera e longe do corpo da pessoa surda, deixa de entrar como
  se fosse dela.
- `setNumPoses(2)` e escolher a pose de **maior distância entre ombros**, ou seja, a
  pessoa mais próxima.
- No palco: fundo sem gente e atendente com as mãos abaixo do campo de visão.

### 2.3 Custo por frame e descarte — [código] e [hipótese]

Pose e Hands rodam em CPU (nenhum `setDelegate` em `LandmarkExtractor.kt`), a cada frame,
em 24 fps (`camera/CameraViewModel.kt:100`). Quando a inferência não acompanha,
`acquireLatestImage` **descarta frames** (`LandmarkPipeline.kt`). Isso é aceitável para o
classificador, que reamostra no tempo, mas **quebra o detector de fronteiras** (§3.1).

**Dois decodificadores HEVC em software** — [código]. O `HevcDecoder` **prefere o
decodificador de software** (`stream/HevcDecoder.kt`, `createHevcDecoder`, com uma lista de
decodificadores de hardware bloqueados). Existem dois deles rodando a 24 fps: um para o preview
e outro para a inferência (`LandmarkPipeline`). É CPU gasta antes de o MediaPipe começar.

**Fila cheia pausa o decodificador até o próximo quadro-chave** — [código] e [hipótese] de
ocorrer. Quando a fila de entrada enche, `enqueuePrivate` faz `active = false`, e o
decodificador só volta no próximo *keyframe* (`HevcDecoder.kt`, "Decoder queue full"). Se o
MediaPipe segurar as imagens do `ImageReader` (máx. 3) por tempo demais, a saída trava, a
fila enche e o reconhecimento perde um **trecho inteiro entre quadros-chave**, possivelmente
no meio de um sinal.

**Resolução:**
- Medir no aparelho de demo os fps efetivamente processados (log por segundo) e procurar
  "Decoder queue full" no logcat durante uma sessão.
- Se o fps ficar muito abaixo de 24: primeiro testar o decodificador de **hardware** só no
  caminho da inferência (o preview pode continuar em software); depois o delegate de GPU do
  MediaPipe.
- Durante o ②, avaliar esconder o preview: é um decodificador a menos. Se a banca precisa
  ver a câmera, manter.

### 2.4 Fechar o MediaPipe no meio de uma extração — [hipótese]

`LandmarkPipeline.stop()` fecha o extrator (`extractor?.close()`) da thread que encerra o
stream, enquanto a thread do `ImageReader` pode estar **no meio** de um `detectForVideo`.
`quitSafely()` não espera o trabalho em curso. Fechar um detector nativo em uso pode
derrubar o app inteiro (erro nativo, sem exceção Kotlin). A janela é pequena, mas acontece
a cada fim de turno.

**Resolução:** fechar o extrator **na própria thread do `ImageReader`** (postar o `close()`
no `Handler` antes do `quitSafely()`). Com a correção do §1.4 (extrator vivo o app inteiro),
o problema desaparece junto.

---

## 3. Segmentação: `SignBoundaryDetector`

O detector é o elo mais frágil **entre** um modelo bom e uma demo boa: se o corte vem
errado, o classificador recebe lixo e erra com confiança. Estado atual: parâmetros
"pontos de partida" não calibrados (`SignBoundaryDetector.kt:29-45`).

### 3.1 Problemas encontrados no código — [código]

Esses problemas **precisam ser corrigidos antes de calibrar**, senão a calibração ajusta
números em cima de um defeito.

#### 1. O classificador recebe o tempo parado junto com o sinal

**Como funciona hoje.** Durante a sessão, cada frame vira landmarks e entra num buffer
(`handGapImputer.offer`, no `setOnImageAvailableListener` do `LandmarkPipeline.kt`). O
`SignBoundaryDetector` só observa e avisa quando um sinal terminou. Nesse momento,
`classificarSegmentoAtual` manda **o buffer inteiro** ao classificador e começa um buffer
novo.

**O problema.** O buffer recebe frames **o tempo todo**, inclusive com a pessoa parada. Ele
não começa quando o sinal começa, e sim quando **o sinal anterior terminou**.

**Exemplo:**

```
tempo:   0s ─────────────────── 6s ──── 7s ── 7,7s
pessoa:  parada, mãos baixas        SINAL   pausa (janela de sustentação)
buffer:  [██████ repouso ██████████][sinal][pausa]  → vai inteiro para o modelo
```

O modelo recebe 7,7 s de sequência e reamostra tudo para T frames fixos. O sinal, que durou
1 s, ocupa só ~13% da entrada, e o resto é gente parada. No treino, cada clipe do MINDS é
**recortado em torno de um sinal**: há alguma borda de repouso, de tamanho ainda não
medido (§3.2, conjunto A), mas o sinal ocupa a parte principal. O modelo vê algo muito
diferente do que aprendeu e erra, **mesmo sendo bom**.

**Correção.** Guardar frames só **a partir do momento em que o detector percebe
movimento**, com uma pequena margem antes (*pré-roll* de ~200–300 ms, num buffer circular,
para não cortar o começo do sinal), e cortar a pausa do final. O tamanho das margens deve
imitar **quanto repouso existe nas bordas dos clipes do MINDS**, medido nos `.npy`. O que
importa é o segmento se parecer com o que o modelo viu no treino.

#### 2. O limiar de movimento depende de quantos frames o celular consegue processar

**Como funciona hoje.** Para decidir se a pessoa está sinalizando, o detector mede quanto as
mãos e os braços andaram **entre um frame e o seguinte**. Acima de `limiarVelocidade = 0.5f`
(em unidades de largura dos ombros) é movimento.

**O problema.** Essa distância depende do **intervalo** entre os dois frames comparados, e
esse intervalo não é fixo: quando o MediaPipe não dá conta, o `acquireLatestImage`
**descarta frames** (§2.3).

**Exemplo, com a mão se movendo sempre à mesma velocidade:**

| Situação | Frames processados | Tempo entre frames | Distância entre dois frames | Decisão |
|---|---|---|---|---|
| Celular frio | 24/s | ~42 ms | 0,3 | abaixo do limiar: **"parado"** |
| Celular quente, descartando | 12/s | ~83 ms | 0,6 | acima do limiar: **"sinalizando"** |

É o mesmo gesto com duas decisões diferentes. Uma calibração feita com o celular frio deixa
de valer depois de 10 minutos de demo.

**Correção.** Medir **velocidade numa janela fixa de tempo**: comparar o frame atual com o
frame de ~100–120 ms atrás e dividir pelo intervalo real. O limiar passa a ser em
**larguras de ombro por segundo**, e não muda com o fps.

Dividir só o deslocamento entre frames vizinhos pelo `dt` não basta, e é por isso que a
janela precisa ser fixa. O tremor do MediaPipe (*jitter*) é aproximadamente constante **por
frame**, então, convertido em "por segundo", ele cresce com o fps. Numa janela fixa, o
movimento real cresce com o tempo e o tremor não.

#### 2b. A métrica soma 42 pontos, e o ruído soma junto

`calcularDeslocamento` **soma** a distância de cada um dos 21 pontos de cada mão
(`SignBoundaryDetector.kt`, `deslocamentoMao`). Distâncias são sempre positivas, então o
tremor dos 42 pontos **se acumula em vez de se cancelar**. Estimativa de ordem de grandeza
(§3.2): um tremor de ~0,014 ombro por ponto por frame, com as duas mãos visíveis, soma
~0,6 por frame, **acima do limiar atual de 0,5**. Mãos paradas podem parecer "sinalizando".

A soma também muda de escala conforme o número de mãos visíveis: uma mão sumir divide o
valor por dois.

**Correção.** Por mão, usar a **média** da velocidade dos pontos (e não a soma). Entre os
articuladores (mão esquerda, mão direita, pulsos), usar o **máximo**. O resultado passa a
ter leitura física ("quanto anda a mão mais rápida") e não depende de quantas mãos
aparecem.

#### 3. Sem histerese nem suavização

Um único limiar para entrar e sair faz o estado **oscilar** com o *jitter* do MediaPipe
sobre vídeo comprimido: os landmarks tremem alguns pixels mesmo com a mão parada, e um
movimento lento fica alternando acima e abaixo do limiar.

**Correção.** Média móvel exponencial de 3 a 5 frames sobre a velocidade, e **dois
limiares**: começar exige mais velocidade do que continuar.

#### 4. Sinalização contínua não tem pausa

Em frase natural, quem sinaliza **não volta ao repouso** entre sinais. A heurística de
velocidade junta os sinais num segmento só, e o próprio plano registra isso
(`sign-boundary-detector-plano.md` §8). Não tem solução barata até a demo.

**Protocolo da V1:** a pessoa sinaliza com uma **pausa curta, de mãos paradas**, entre os
sinais. É uma limitação a declarar na apresentação, não a esconder.

#### 5. A duração mínima não filtra nada

`fecharSegmento` calcula a duração como `timestampMs - tsInicioSegmento`, e o fechamento por
pausa só acontece `janelaSustentacaoMs` (700 ms) **depois** do último movimento. Todo
segmento fechado por pausa dura, portanto, **pelo menos 700 ms**, sempre acima dos 300 ms de
`duracaoMinimaMs`.

**Exemplo:** a pessoa ajeita o cabelo por um único frame. O detector entra em SINALIZANDO,
fecha 700 ms depois com "duração" de 700 ms, e o segmento vai para o classificador. Como o
buffer carrega todo o repouso (problema 1), ele também passa do
`MIN_FRAMES_PARA_CLASSIFICAR = 5`. **Qualquer espasmo vira um sinal**, e o modelo sempre
responde uma das 20 classes (§4.3).

**Correção:** medir a duração **do movimento**: `tsUltimoMovimento - tsInicioSegmento`.

#### 6. O teto de oclusão nunca dispara

Durante a oclusão total, `tsUltimoMovimento` para de ser atualizado. A pausa sustentada
dispara aos 700 ms, antes do `tetoOclusaoMs` de 1.200 ms. A intenção documentada ("oclusão não
é parado imediato", plano §4.3) não acontece: **mãos sumidas por 700 ms fecham o sinal**.

**Por que importa.** Com o tronco inteiro no quadro, as mãos em repouso continuam visíveis, e
quem fecha o sinal é a **pausa**, não a oclusão. A oclusão é o caso de **perda de detecção no
meio do sinal**:
- borrão de movimento rápido;
- mão cruzando na frente do rosto ou do corpo;
- sinal amplo que sai pela borda do quadro.

Com o comportamento atual, o sinal é **cortado em dois** se isso durar 700 ms. O perigo é o
contrário do que o parâmetro pretendia.

**Correção:** durante a oclusão total, **não contar o tempo como pausa**. Fechar por oclusão
só depois de um teto próprio, **maior** que a pausa (é a intenção original do plano §4.3).
Perdas curtas, de até 5 frames, o `HandGapImputer` já preenche.

Se a gravação com os óculos mostrar que as mãos **saem do quadro** no repouso (depende do
enquadramento, §1.3), a oclusão vira um marcador de fim legítimo e o teto deve ser
reduzido. Isso se decide com medição, na calibração rápida do §3.2.

### 3.2 Como calibrar bem

A calibração é feita **fora do app**, sobre dados gravados, com uma métrica escolhida
antes. Mexer em constante e reinstalar o APK não converge em três dias.

**O que calibrar significa aqui.** O detector toma uma decisão por frame ("está sinalizando
ou parado?") a partir de alguns números fixos. Calibrar é escolher esses números para que os
cortes caiam onde um humano cortaria, **medindo o resultado em gravações**, e não no olho
durante um teste.

#### Passo 0: valores iniciais estimados sem dados — [hipótese]

Sem gravações, dá para estimar a **ordem de grandeza** a partir de física e de propriedades
conhecidas da sinalização. Os valores abaixo assumem as correções 1, 2, 2b, 5 e 6 do §3.1:
velocidade em ombros por segundo, janela fixa, média por mão e máximo entre articuladores.
**Não são válidos para o código atual.**

Premissas, todas aproximadas:
- largura dos ombros ≈ 0,38 m, então 1 ombro/s ≈ 0,38 m/s;
- a mão em movimento de sinal anda ~0,3 a 1,5 m/s, ou **~0,8 a 4 ombros/s**; o
  deslocamento mais lento de um sinal fica perto da ponta de baixo;
- dentro de um mesmo sinal há *holds* e inversões de sentido (sinais repetidos) de
  ~100–300 ms, em que a velocidade cai quase a zero **sem o sinal ter acabado**;
- o sinal isolado (forma de citação, como no MINDS) dura ~0,5–1,5 s de movimento;
- tremor do MediaPipe sobre vídeo comprimido, com a pessoa ocupando uma parte modesta do
  quadro: ~0,01–0,02 ombro por ponto por frame. **É a premissa mais incerta**, porque
  depende de resolução, distância e luz. Numa janela de ~100 ms, isso vira um piso de
  ruído de ~0,15–0,35 ombros/s.

| Parâmetro | Hoje | Estimativa inicial | Raciocínio |
|---|---|---|---|
| limiar de **entrada** | 0,5 por frame (soma) | **0,7 ombros/s** (~0,27 m/s) | ~2–3× o piso de ruído; abaixo da parte lenta de um sinal |
| limiar de **saída** | o mesmo | **0,4 ombros/s** | ~60% da entrada (histerese), ainda acima do ruído |
| janela de velocidade | 1 frame | **100–120 ms** | longa o bastante para o tremor não dominar, curta para não atrasar |
| `janelaSustentacaoMs` (pausa) | 700 | **500 ms** | acima dos *holds* e inversões internos (até ~300 ms) com folga; abaixo da pausa instruída |
| teto de **oclusão** (caminho próprio) | 1.200 (sem efeito) | **900 ms** | maior que a pausa (500 ms), para uma perda de detecção no meio do sinal não o cortar; reduzir só se as mãos saírem do quadro no repouso |
| `duracaoMinimaMs` (do **movimento**) | 300 (sem efeito) | **250 ms** | abaixo do sinal mais curto; acima de espasmos de 1 a 4 frames |
| `duracaoMaximaMs` | 8.000 | **3.500 ms** | um sinal isolado passa raramente de ~2 s; acima de 3,5 s, provavelmente são dois sinais juntos |
| pré-roll | 0 (buffer inteiro) | **250 ms** | a mão leva ~100–200 ms acelerando antes de passar do limiar |
| pós-roll | pausa inteira | **150 ms** | mantém o fim do movimento e descarta o resto da pausa |

**Até onde isso vale:** as estimativas de movimento (sinais, *holds*, durações) são
razoavelmente estáveis entre pessoas. **O piso de ruído não é**, e ele decide os dois
limiares. Por isso a primeira medição com os óculos é a do ruído, e não a dos sinais.

#### Calibração rápida com os óculos (~10 minutos)

Antes de qualquer gravação anotada, dois registros com o gravador de debug (§3.4) já trocam
as premissas mais incertas por medições:

1. **Ruído:** 20 s de alguém em frente aos óculos, na distância do palco, com as **mãos
   visíveis e paradas**, e mais 20 s com as mãos em repouso na posição que o protocolo pede.
   → O percentil 95 da velocidade vira o **piso de ruído**. Limiar de saída ≈ 1,5–2× o
   piso; limiar de entrada ≈ 2,5–3× o piso.
2. **Sinais:** os 20 sinais, uma vez cada, com pausa entre eles.
   → O percentil 10 da velocidade **dentro** dos sinais precisa ficar acima do limiar de
   saída. Se não ficar, o ruído está alto demais e a solução é enquadramento (pessoa mais
   perto) ou suavização maior, não limiar mais baixo.
   → A maior pausa **interna** de um sinal (inversões, *holds*) define o mínimo da
   `janelaSustentacaoMs`.
   → **As mãos em repouso continuam visíveis?** Se sim, o teto de oclusão fica maior que
   a pausa. Se somem, a oclusão vira marcador de fim e o teto cai.
   → A maior perda de detecção **dentro** de um sinal (borrão, mão na frente do rosto) é o
   mínimo do teto de oclusão.

Isso produz valores "bons o bastante para ensaiar" em uma tarde. Os passos abaixo são a
calibração que produz números **defensáveis**.

**Passo 1: instrumentar (Fase 0 do plano).** Um gravador de debug (§3.4) grava por frame:
timestamp, deslocamento das mãos, deslocamento dos braços, estado do detector e o frame
normalizado.

**Passo 2: montar três conjuntos de dados, do mais barato ao mais fiel.**

| Conjunto | O que é | Dá o quê | Limitação |
|---|---|---|---|
| A. Clipes do MINDS (`.npy`) | um sinal por clipe, com repouso nas bordas | a ordem de grandeza da velocidade dentro do sinal contra a borda; o repouso típico de borda (para o pré-roll e pós-roll) | já vem cortado, sem transição |
| B. Sequências sintéticas | clipes da **mesma pessoa** concatenados, com repouso entre eles | fronteiras com gabarito exato, em volume | a transição é artificial |
| C. **Gravação com os óculos** | 3 pessoas do time, roteiros de 2 a 4 sinais dos 20, com pausa; 2 distâncias, 2 iluminações | a única amostra do cenário real | pouco volume; exige anotação |

Anotar o conjunto C é barato: para cada vídeo, uma linha CSV com início e fim de cada
sinal, marcados assistindo em câmera lenta. Uns 30 a 40 vídeos curtos bastam para
escolher parâmetros.

**Passo 3: portar o detector para Python** (são ~100 linhas de lógica) e fazer busca em
grade sobre os dados gravados:

| Parâmetro | Faixa a varrer |
|---|---|
| limiar de entrada (ombros/s) | a partir da distribuição medida no conjunto A |
| razão da histerese (saída/entrada) | 0,5 – 0,8 |
| `janelaSustentacaoMs` | 400 – 900 |
| `duracaoMinimaMs` | 250 – 500 |
| alfa da suavização | 0,3 – 0,7 |
| pré-roll / pós-roll | a partir do repouso medido no conjunto A |
| teto de oclusão | 600 – 1.200 (ou 250 – 600, se as mãos saírem do quadro no repouso) |

As faixas giram em torno das estimativas do Passo 0. O limiar de entrada é varrido em
torno do piso de ruído medido na calibração rápida.

**Passo 4: medir o que importa, na ordem certa.**

1. **Sequência de glosas ponta a ponta** (detector + classificador real): acerto exato da
   sequência e distância de edição em glosas. **É por esta métrica que se escolhe.**
2. Diagnóstico das fronteiras: precisão e recall com tolerância de ±200 ms, e contagem
   separada de **super-segmentação** (1 sinal → 2), **sub-segmentação** (2 → 1) e
   **sinal perdido**. Cada erro tem uma causa diferente no parâmetro.
3. Latência adicionada: a janela de sustentação é atraso puro na resposta.

**Passo 5: separar calibração de teste por pessoa.** Calibrar com duas pessoas do
conjunto C e medir na terceira. É a mesma premissa de *leave-one-signer-out* do
projeto: calibrar e medir na mesma pessoa produz um número que não vale no dia da demo.

**Passo 6: congelar os valores** no código com um comentário apontando para o relatório
da calibração, como o projeto já faz com os números do modelo.

### 3.3 Feedback visual — barato e muda a demo

Mostrar no banner o estado ao vivo (`● sinalizando` / `○ parado`) e o contador de sinais
capturados. A pessoa que sinaliza aprende o ritmo de pausa em segundos, e a banca **vê**
a segmentação acontecendo, o que é demonstração técnica por si só.

### 3.4 O gravador de debug é pré-requisito de quase tudo

Um `LandmarkSessionRecorder` ativado pelo menu de debug grava cada sessão em
`getExternalFilesDir(null)/sessoes/<timestamp>.csv` (ou `.npy`). É o mesmo diretório que o
Vosk e o Piper já usam, e ele pode ser puxado sem root:
`adb pull /sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes`.
(O `filesDir` interno, `/data/data/...`, não é acessível por `adb pull` num aparelho comum.)
Ele serve para:
- calibrar o detector (§3.2);
- avaliar o classificador com o ponto de vista real, **offline, no Python**;
- verificar a paridade de mãos (§2.1);
- montar um vídeo de bastidor para a apresentação.

---

## 4. Classificação

### 4.1 Contrato de entrada — [código]

- **z:** o modelo de entrega usa z (3 canais), mas `LandmarkNormalizer.N_CANAIS = 2`
  descarta o z (`LandmarkNormalizer.kt:57`). O `FrameLandmarks` recebe o z do MediaPipe,
  então é só parar de descartá-lo.
- **T fixo:** o grafo exportado tem entrada de T frames (padrão `--frames 96`) e reamostra
  internamente para `T_FIXO = 64` (`treino/gcn.py:44`). O app precisa entregar **exatamente**
  T frames, reamostrando o segmento com o **mesmo método** do `para_sequencia`.
- **Imputação dupla:** o sidecar declara `imputacao_embutida`. Se for `true`, o
  `HandGapImputer` do app roda **além** da imputação do grafo. Decidir um lado só.
- **Teste:** uma sequência de referência passada pelo Python e pelo `TfliteSignClassifier`
  em teste instrumentado, comparando os logits. É o mesmo padrão de paridade que o projeto
  já usa para a normalização.

### 4.2 Rótulos do classificador contra o léxico da contextualização — [código]

**O contexto.** São dois modelos em sequência, e cada um nomeia os sinais do seu jeito:

1. O **classificador** devolve um rótulo que vem dos nomes do treino
   (`datasets/selecao.yaml`). Lá o sinal está como **`maca`**, sem acento, como nome de
   arquivo.
2. A **contextualização** consulta `assets/lexico-glosas.json` para saber o que cada glosa
   significa. As guardas (`Guardas.kt`) usam o mesmo arquivo para conferir se a frase gerada
   não perdeu nenhuma palavra. Lá a entrada é **`maçã`**.

**O problema.** Para o computador, `maca` e `maçã` são palavras diferentes, e cada camada
da contextualização descarta o desconhecido **em silêncio**:

- o modelo ignora glosas fora do `glosa_ids.json` (`TfliteGlossContextualizer.montarEntrada`);
- a guarda não acha `maca` no léxico e rejeita o modelo;
- o template não tem `maca` nos seus mapas e **a remove da frase**
  (`TemplateGlossContextualizer.montar`).

**Exemplos:** *filho + maca* é falado como **"O meu filho."**, e o sinal some. Sozinho,
`maca` volta cru e o TTS fala **"maca"**, que em português é a cama de hospital. As outras
19 classes batem com o léxico e com o template.

**Correção.** Uma tabela explícita *rótulo do classificador → glosa do léxico* no app, e um
teste que **falha se algum rótulo do sidecar não existir no léxico**. Quando o vocabulário
crescer, o teste avisa sobre o próximo caso. Conferir no `.json` do modelo exportado se o
rótulo sai mesmo como `maca`.

### 4.3 Conjunto aberto: o modelo sempre responde uma das 20 — [hipótese]

Coçar o nariz, ajeitar o cabelo ou qualquer gesto fora do vocabulário vira um dos 20
sinais, e o boundary dispara do mesmo jeito.

**Resolução decidida (2026-09-13): confiança por frase, com repetição.** A frase só é
falada se **todos** os sinais tiverem confiança ≥ limiar. Se não tiverem, o **atendente**
ouve um aviso para pedir a repetição. Na terceira frase rejeitada seguida, ele ouve "tente
outro meio de comunicação" e a sessão é encerrada.

**Estrutura:**

1. **`SignClassifier` devolve glosa e confiança** (`Classificacao(glosa, confianca,
   margem)`). O `.tflite` devolve *logits* (`exportar.py:458`); o softmax fica no app. O
   placeholder ganha modos de debug (alta, baixa, aleatória) para testar o fluxo sem o
   modelo.
2. **A regra fica numa classe Kotlin pura** (`AvaliadorDeFrase`), sem Android. Ela recebe
   os sinais da sessão e o motivo do encerramento, guarda o contador e devolve uma decisão:

   | Situação | Decisão | Contador |
   |---|---|---|
   | todos os sinais ≥ limiar | `Falar(glosas)` | zera |
   | algum sinal < limiar, ou falha de classificação | `PedirRepeticao` | +1 |
   | 3ª rejeição seguida | `Desistir` | zera |
   | sessão vazia, fechada por **timeout** | `Ignorar` (volta ao ①) | não muda |
   | sessão vazia, fechada **manualmente** | `PedirRepeticao` | +1 |

   Por frase, o critério é o **mínimo** e não a média: um único sinal errado muda o sentido
   da frase.
3. **O `DialogOrchestrator` só executa a decisão.** Os avisos tocam no estado `FALANDO`, em
   que a wake word já fica pausada. Na repetição:
   - **a câmera não é desligada** (hoje `endSignSession` desliga antes de contextualizar;
     a decisão precisa vir antes);
   - a nova sessão do pipeline só abre **depois** que o aviso termina, para não capturar a
     reação da pessoa enquanto o atendente pede a repetição;
   - volta direto ao ②, com o indicador de "pode sinalizar" (§1.1).
4. **O contador zera** quando uma frase é aceita, quando há desistência e quando o
   atendimento termina por inatividade.
5. **Os avisos são pré-sintetizados** pelo Piper no aquecimento (§10.3).

**Pré-requisitos, senão a regra encerra sessões à toa:**
- **Espasmos não podem virar sinal** (§3.1, item 5). Com o defeito atual, um movimento de
  um frame vira um segmento de confiança baixa e **reprova a frase inteira**. Segmento
  curto demais continua sendo descartado em silêncio (`MIN_FRAMES_PARA_CLASSIFICAR` já não
  chama `onRecognitionFailed`) e **nunca** conta como falha.
- **O repouso não pode entrar no segmento** (§3.1, item 1). Um segmento diluído derruba a
  confiança de sinais corretos.

**O limiar de 60% é provisório — [hipótese].** A confiança do softmax costuma ser mal
calibrada (geralmente alta demais), e a quantização int8 muda os valores. O valor certo sai
das previsões *leave-one-signer-out*: escolher o limiar em que os sinais aceitos acertam,
por exemplo, ≥90%. Se as probabilidades vierem confiantes demais, *temperature scaling* no
Python corrige isso barato. No app, o limiar é **uma constante injetada** no avaliador, e
cada segmento registra `glosa`, `confianca`, `margem` e a decisão no log, para calibrar com
os dados do conjunto C (§3.2).

### 4.4 O vocabulário da demo são 20 sinais — fato de escopo

Com `acontecer, amarelo, aluno, america, aproveitar, bala, banco, banheiro, barulho,
cinco, conhecer, espelho, esquina, filho, maçã, medo, ruim, sapo, vacina, vontade`, as
frases de atendimento possíveis são poucas: *filho vacina*, *banheiro*, *banco esquina*,
*medo*. Escrever o **roteiro da demo** agora, com 3 ou 4 sequências, e ensaiar
exatamente essas. Elas também são os roteiros da gravação do conjunto C.

---

## 5. Contextualização (glosas → português)

### 5.1 Latência do seq2seq: dois defeitos e nenhuma medição — [código] e [hipótese]

O `.tflite` (~46 MB) roda um laço autorregressivo (`encode` + até 23 chamadas de
`decode_step`, 4 threads com XNNPACK). Cada `decode_step` recebe a sequência inteira de 24
posições, sem cache. Nunca foi medido no aparelho de demo. Dois defeitos pioram isso:

1. **O laço não para no fim de frase** — [código]. Em `gerar()`, o fim de frase faz
   `if (proximo == EOS) return@repeat`. Em Kotlin, `return@repeat` **pula para a próxima
   volta**, não sai do `repeat`. A sequência para de crescer (o texto sai certo), mas o laço
   roda as **23 chamadas sempre**. Uma frase de 5 tokens paga o preço de uma de 23.
   → Trocar por um laço `while`/`for` com `break`.
2. **O prazo de 1,5 s não interrompe nada** — [código]. O prazo existe
   (`GuardedGlossContextualizer`, `timeoutMs = 1_500L`), mas o `withTimeout` do Kotlin só
   cancela **em pontos de suspensão**, e `gerar()` é uma chamada bloqueante do começo ao fim.
   Se o modelo levar 4 s, a cadeia espera os 4 s e **depois** cai no template: o pior dos dois
   mundos.
   → Checar o cancelamento **entre** os passos de decodificação (`ensureActive()` ou uma
   flag), o que limita o atraso a um passo.

**Resolução complementar:**
- **Aquecimento:** o `Interpreter` já é criado ao abrir o app (`criarGlossContextualizer`,
  na construção do ViewModel), mas a **primeira inferência** ainda paga as alocações. Rodar
  uma inferência descartável no aquecimento (§10.3).
- Existe uma variante fp16 em `contextualization-model/artefatos/`. Medir as duas no
  aparelho antes de escolher.
- **Limite de entrada:** o encoder aceita 16 tokens (`S_ENC`) e corta o resto, incluindo o
  marcador de fim. Frases de 3 ou 4 glosas cabem, mas vale um teste com a frase mais longa
  do roteiro.

### 5.2 A qualidade é sintética — [medido]

O template ganha do modelo em F1 na validação sintética (`CONTEXTO.md` §5). Para as
3 ou 4 frases do roteiro, **conferir à mão** a saída do modelo e do template, e fixar o
que soar melhor. Na demo, previsibilidade vale mais que generalidade.

---

## 6. Turnos e comandos: wake word e botões

### 6.1 Quatro comandos por turno — [código]

Pela máquina de estados (`DialogOrchestrator.onWakeWord`), uma volta completa exige:
*iniciar* (①→②), *encerrar* (②→③), *iniciar* (④→⑤) e *encerrar* (⑤→⑥). Tudo é dito ou
tocado **pelo atendente**, que não sabe quando a pessoa surda terminou de sinalizar.

**Resolução, em avanços automáticos:**
- **②→③ automático:** com ≥1 sinal reconhecido e o detector em PARADO por N segundos
  (N calibrado junto com o §3; 2,5 a 3 s como ponto de partida), encerrar sozinho. O
  timeout atual é de 60 s (`DialogOrchestrator.kt:100`), longo demais para demo.
- **③→⑤ automático:** ao terminar a fala, abrir a escuta direto, sem o *iniciar* do ④.
- **⑤→⑥ por silêncio:** o `Recognizer` do Vosk sinaliza fim de enunciado
  (`acceptWaveForm` devolve `true`). Encerrar ao primeiro resultado final não vazio, com
  teto de duração.

Com os três, **a voz só abre o atendimento**, e os botões ficam como correção manual.

### 6.2 Disputa de microfone no ⑤ — [código]

A wake word fica ativa em `ESCUTANDO_ATENDENTE` (`DialogOrchestrator.kt:83-89`), o mesmo
estado em que o Vosk captura com `VOICE_COMMUNICATION` via SCO. O próprio
`SpeechRecognizerWakeWordDetector.kt:23-30` registra a concorrência como não validada.

**Resolução:** tirar `ESCUTANDO_ATENDENTE` de `WAKE_WORD_ACTIVE_STATES`, uma linha. Com o
§6.1 (fim por silêncio), a wake word deixa de ser necessária ali.

### 6.3 Os dois motores de wake word têm problema — [código] e [hipótese]

O `SpeechRecognizer` do Android "historicamente depende de rede em muitos aparelhos"
(`SpeechRecognizerWakeWordDetector.kt:16-18`). Num local com rede ruim, a voz simplesmente
não dispara. Outros dois riscos dele — [hipótese]:
- ele reinicia a escuta a cada poucos segundos, e em muitos aparelhos **cada início toca um
  bipe** do sistema, audível pela banca e no microfone;
- ele disputa foco de áudio com a fala do app.

**O motor offline não funciona hoje** — [código, testado]. O `OpenWakeWordDetector` tem
recall 0,69/0,58 e ~0,5 falso positivo por hora, contra meta de 0,2/h
(`libras/README.md`). Mas os classificadores versionados guardam os pesos num arquivo
separado (`libras_livre_*.onnx.data`), e o runner lê o `.onnx` **como bytes**
(`OnnxModelRunner.createSession`: `env.createSession(modelBytes)`). Assim, o ONNX Runtime não
tem como achar o `.data`. Reproduzido no desktop com `onnxruntime`: carregar por bytes falha com
*"External data path validation failed … model loaded from bytes"*; carregar pelo caminho
funciona. No app, a falha vira um log e o detector simplesmente não sobe.

→ **Correção:** reexportar os dois classificadores com os pesos **embutidos** no `.onnx`
(são ~270 KB; `onnx.save(..., save_as_external_data=False)`), ou copiar `.onnx` e `.onnx.data`
para o disco e abrir pelo caminho. Adicionar um teste instrumentado que só carrega os dois.

→ **Segundo defeito, para quando ele subir:** o `OpenWakeWordDetector` chama `onWakeWord` a
partir de `Dispatchers.Default`, e o `DialogOrchestrator` não é seguro entre threads (estado e
lista de glosas são mexidos na main). Entregar o evento na main thread.

**Resolução:** corrigir o carregamento, depois testar os dois motores no aparelho de demo,
**em modo avião**, e escolher pelo resultado. Se nenhum for confiável, a demo roda por botão
(§6.4) e a wake word vira um slide de "próximo passo", sem vergonha nenhuma.

### 6.4 Onde fica o botão — [hipótese] de uso

Na montagem da demo, o celular fica virado para a **pessoa surda** (é ali que o avatar aparece). O
botão na tela fica longe do atendente.

**Resolução, em ordem de custo:**
1. Botões grandes na tela, com o celular num suporte que os dois alcançam.
2. **Botões físicos de volume do celular** mapeados para avançar o turno (`onKeyDown` →
   `onWakeWordButton`).
3. **Controle remoto Bluetooth de selfie** (poucos reais), que envia tecla de volume. O
   atendente segura no bolso ou na mão. Conferir se ele convive com os óculos no mesmo
   celular (são perfis Bluetooth diferentes, mas é preciso testar).

### 6.5 Ligar e desligar a wake word pela tela, sem mexer no código

Hoje, desligar a escuta por voz exige mudar código e gerar outro APK
(`attachWakeWordDetector`, `camera/CameraViewModel.kt:278`). Um **interruptor na tela**
resolve isso, e o encanamento já existe:

- **Os botões já não dependem do detector** — [código]: `onWakeWordButton` chama o
  orquestrador direto (`camera/CameraViewModel.kt:724`). Desligar a voz não afeta os
  botões.
- **Um único ponto liga e pausa o detector** — [código]: `DialogOrchestrator.setState`
  chama `start()` ou `pause()` conforme o estado. Basta uma flag `wakeWordHabilitada`
  entrar na mesma condição: `habilitada && estado in WAKE_WORD_ACTIVE_STATES`.
- **Ligar de novo já tem caminho** — [código]: `resumeWakeWordDetectorIfActive()` religa se
  o estado atual espera wake word.

Desenho:

1. `DialogOrchestrator.setWakeWordHabilitada(Boolean)`: guarda a flag e chama `pause()` ou
   `resumeWakeWordDetectorIfActive()`.
2. A flag sai no `CameraUiState` e aparece como um `Switch` "Comando de voz" junto do
   `DialogControlRow`, visível para o atendente.
3. Persistir a escolha (`SharedPreferences`), para ela sobreviver a reiniciar o app no palco.
4. Opcional: um seletor do motor no menu de debug (`SpeechRecognizer` ou `OpenWakeWord`),
   para fazer a comparação do §6.3 no aparelho sem gerar dois APKs.

⚠️ O interruptor **não substitui** o §6.2. Com a voz ligada, o detector continua ativo no
⑤ e disputa o microfone com a transcrição. Tirar `ESCUTANDO_ATENDENTE` dos estados ativos
continua necessário.

---

## 7. Áudio

### 7.1 A banca não ouve a fala — [código]

**Como funciona hoje.** O Piper toca a voz como **áudio de mídia**, igual a uma música
(`USAGE_MEDIA`, `PiperSherpaOnnxTtsEngine.kt:196`). O Android manda áudio de mídia para o
fone Bluetooth conectado, que aqui são **os óculos**.

**Para o produto está certo.** Quem usa os óculos é o atendente, e é ele que precisa ouvir
a tradução, perto do ouvido.

**Para a demo é um problema.** A banca não usa os óculos. O momento principal (a pessoa
sinaliza e o sistema fala *"meu filho precisa de vacina"*) acontece em volume baixo, dentro
dos óculos. Para quem assiste, **parece que nada aconteceu**.

**Resolução:** chave no menu de debug para a saída de voz no **alto-falante do celular**
(`AudioTrack.setPreferredDevice` com `TYPE_BUILTIN_SPEAKER`), ou uma caixa de som
Bluetooth/P2 ligada ao celular.

### 7.2 HFP: troca de perfil e taxa de amostragem — [código] e [hipótese]

Ligar HFP derruba o A2DP (`mobile-app-companion/README.md` §3.3). O `PcmMicCapture` pede
16 kHz, assumindo mSBC wideband (`PcmMicCapture.kt:26-29`). Se o par negociar narrowband de
8 kHz, o Android normalmente **reamostra** a captura para 16 kHz: o provável é perder
qualidade (som de telefone, menos acerto do Vosk), não receber lixo. Mas nada disso foi
validado em hardware.

Há também um caminho que falha em silêncio — [código]. Se não houver dispositivo SCO,
`acquireListening()` devolve `null` e o orquestrador **volta ao ④ sem escutar e sem avisar**
(`DialogOrchestrator.beginListening`).

**Resolução para a demo:** **STT pelo microfone do celular** (`VOICE_RECOGNITION` +
`TYPE_BUILTIN_MIC`, que é a mesma classe com outros dois parâmetros em
`camera/CameraViewModel.kt`). O celular fica entre os dois, perto de quem fala. Isso elimina
de uma vez a troca A2DP/HFP, a questão da taxa e a disputa do §6.2. Nesse modo, o
orquestrador precisa **pular** o `acquireListening()`; senão ele continua exigindo o SCO e
aborta a escuta. O microfone dos óculos fica como teste separado, não como caminho da
apresentação.

### 7.3 O Vosk carrega na primeira escuta — [código]

`VoskSttEngine.start` chama `ensureModelLoaded()` na hora (`VoskSttEngine.kt:67-75`), e a
captura só começa depois que o modelo (52 MB em disco) termina de carregar. Na **primeira**
resposta de cada execução do app:

```
atendente começa a falar ──► app ainda carregando o Vosk ──► captura começa
          └──── estas palavras não são transcritas ────┘
```

É o mesmo erro do §8.2: carregar algo pesado **no momento em que ele é necessário**, e não
antes.

**Resolução:** carregar o modelo ao abrir o app, junto com o aquecimento do §10.3.

### 7.4 Se o Piper falhar, o app fica mudo — [código]

`PiperSherpaOnnxTtsEngine.speakAndAwait` faz `ensureLoaded() ?: return`: se o modelo não
carregar (asset faltando, erro nativo), **não sai som nenhum, e não há fallback**. O
`AndroidTextToSpeechEngine` existe mas só entra trocando código (`camera/CameraViewModel.kt:133`).
A tradução acontece e ninguém ouve.

**Resolução:** um `TtsEngine` em cadeia (Piper, e o TTS do Android se o Piper falhar) e o
erro visível no banner. Conferir no aquecimento (§10.3) que o Piper carregou.

### 7.5 A primeira execução copia ~70 MB, e uma cópia interrompida quebra para sempre — [código]

O Vosk e os dados do eSpeak do Piper são copiados dos assets para o disco na primeira
execução (`VoskSttEngine.loadModel`, `PiperSherpaOnnxTtsEngine.copyEspeakDataToFilesystem`).
Os dois só checam **se a pasta existe** (`if (!destDir.exists())`). Se o app for fechado no
meio da cópia (instalação às pressas, app morto pelo sistema), a pasta fica **incompleta e
nunca mais é recopiada**: o STT ou o TTS quebram até alguém limpar os dados do app.

**Resolução:**
- Copiar para uma pasta temporária e renomear no fim, ou gravar um arquivo marcador só
  depois de a cópia terminar.
- **Operação:** depois de instalar o APK final, abrir o app e esperar o aquecimento completo
  uma vez, **antes** do dia da demo.

### 7.6 O Vosk pequeno erra em ambiente barulhento — [hipótese]

O `vosk-model-small-pt-0.3` é o modelo compacto. Num salão com conversa ao fundo, é de
esperar trocas de palavras, e o avatar sinaliza **o que foi transcrito**, não o que foi dito.
Hoje ninguém vê a transcrição antes do avatar (§12).

**Resolução:**
- Microfone perto de quem fala: celular próximo ou um microfone de lapela.
- Mostrar a transcrição na tela assim que ela sai (§12).
- Respostas do atendente curtas e ensaiadas. Uma opção a declarar com transparência: o
  Vosk aceita uma **gramática restrita** (lista de frases), o que aumenta muito o acerto,
  mas limita a demo ao roteiro.

---

## 8. Avatar (VLibras)

### 8.1 Depende de rede — [código] e [medido]

A tradução para glosa usa o endpoint público do VLibras, com cache em disco, e o player
busca as animações dos sinais na rede (`vlibras-webview-plano.md`). Não existe espelho
offline.

**Resolução:**
- **Aquecer o cache no local:** rodar as respostas do roteiro uma vez na rede do evento
  antes de apresentar. O `GlosaCache` guarda a tradução.
- Levar um roteador 4G ou usar o hotspot de **outro** celular.
- A legenda já é o piso (`onAvatarUnavailable`): ensaiar esse caminho também, para que a
  falha pareça planejada.

### 8.2 O Unity carrega junto com a captura de sinais — [código]

**Como funciona hoje.** O avatar é um motor 3D que leva 6 a 9 s para ficar pronto e ocupa
~307 MB no processo do renderer. Para estar pronto quando a resposta chegar, o app começa a
carregá-lo cedo, mas no lugar errado: `prepareAvatar()` é chamado **no início do ②**
(`DialogOrchestrator.beginSignSession`).

```
"iniciar" ──► câmera liga + MediaPipe começa + Unity começa a carregar
                        (os três disputando a CPU)
              pessoa surda sinalizando AQUI ◄── o momento mais sensível
```

**O problema.** Com a CPU disputada, o MediaPipe processa menos frames. Isso agrava o
problema 2 do §3.1 e pode estragar a detecção do primeiro sinal. É pior no **primeiro
atendimento**, quando o Unity ainda não carregou nenhuma vez.

**Resolução:** chamar `prepare()` ao **abrir o app ou conectar os óculos**, antes de qualquer
sessão. O ciclo por atendimento continua valendo para o `release`.

### 8.3 Nunca medido em ARM — [medido]

As medições de memória e WebGL são de emulador x86 com GPU de desktop
(`vlibras-webview-plano.md`, ressalva da seção de medição). Primeiro teste do avatar no
aparelho de demo: ele abre, anima e **quanto tempo leva**.

### 8.4 O ⑦ pode prender a conversa por até ~105 s — [código]

No ⑦, o orquestrador fica em `GERANDO_AVATAR` até `playAvatar` devolver. Nesse estado a wake
word está pausada e **os botões Iniciar e Encerrar ficam desabilitados**
(`DIALOG_STATES_WHERE_*_ACTS`). Os tetos somados:

| Etapa | Teto | Fonte |
|---|---|---|
| tradução no endpoint do VLibras | 30 s de conexão + 30 s de leitura | `VLibrasGlosaTranslator.kt`, `TIMEOUT_MS` |
| animação terminar (`gloss:end`) | 45 s | `camera/CameraViewModel.kt`, `AVATAR_TIMEOUT_MS` |

Com rede ruim, ou um sinal cuja animação não chega a baixar (o Unity busca cada sinal na
rede), a demo **congela por um minuto ou mais**. A legenda aparece logo no início, mas a
conversa não anda.

**Resolução:**
- Tetos de demo: **~5 s** para a tradução e um teto de animação proporcional ao tamanho da
  glosa (por exemplo, 3 s + 1,5 s por sinal).
- Um botão **"pular"** na tela do avatar, que encerra o ⑦ e volta ao ①.
- Um prazo total para o ⑦, independente das etapas.

---

---

## 9. Bateria e temperatura

### 9.1 Óculos

- **O stream de câmera é o maior consumo** — [hipótese]. O app já liga a câmera só no ②
  (§1.1), mas isso cobra latência a cada turno.
- **Modo de economia bloqueia a câmera:** com bateria baixa, os óculos param de entregar
  o stream. Para o app, isso aparece como `ensureCameraActive` estourando o teto. Hoje o
  resultado é um log e o "iniciar" ignorado (`DialogOrchestrator.beginSignSession`),
  sem nada na tela.

**Resolução:**
- **Operação:** óculos no estojo carregador entre ensaios; começar a apresentação com carga
  cheia; medir num ensaio **quantos minutos de stream contínuo** a bateria aguenta e
  planejar o tempo de palco em cima disso.
- **Mensagem explícita:** quando a câmera não subir, mostrar "óculos sem câmera (bateria ou
  temperatura?)" no banner em vez de só logar.
- **Escolha consciente de modo:** em ensaio, stream ligado o atendimento inteiro (menos
  latência). Se o ensaio mostrar a bateria no limite, voltar ao liga/desliga por turno de
  hoje. As duas estratégias devem existir atrás de uma flag.
- **Menos pixels e menos fps** — [hipótese]: `VideoQuality.MEDIUM` a 24 fps
  (`camera/CameraViewModel.kt:100,476`). Antes de baixar, **medir o efeito no modelo
  offline**: decimar os `.npy` do MINDS para 12 ou 15 fps e rodar a avaliação. É barato
  e diz se economizar custa acurácia.

### 9.2 Celular

- **A tela apaga** — [código]: não existe `FLAG_KEEP_SCREEN_ON`/`keepScreenOn` no app. Com
  a tela apagada, a Activity vai para segundo plano. O stream e o microfone têm um
  foreground service (`AndroidManifest.xml:79-83`, tipo `connectedDevice|microphone`), então
  o que se perde é o **visual**: banner, botões e o avatar, que é a resposta para a pessoa
  surda. Uma linha resolve: manter a tela ligada enquanto o atendimento estiver ativo.
- **Economia de bateria do Android** reduz o desempenho da CPU e restringe trabalho em
  segundo plano — [hipótese] no aparelho de demo. **Operação:** celular na tomada ou em
  power bank, economia de bateria desligada e o app fora da otimização de bateria.
- **Aquecimento com o tempo** — [hipótese]: MediaPipe em CPU, dois decoders HEVC em software (§2.3), Unity e
  TFLite. O aquecimento reduz o fps processado, e sem a correção do §3.1 isso muda o
  comportamento do detector.
  → Registrar `PowerManager.getThermalHeadroom` no log e fazer **um ensaio de 15 a 20
  minutos contínuos**. A lição 5 do `CONTEXTO.md` §6 vale aqui: a temperatura estável leva
  minutos para aparecer.

### 9.3 Burst de IA: gastar só quando há sinal

O pipeline já faz o burst grosso: o MediaPipe só roda com a sessão aberta
(`LandmarkPipeline.kt`, cabeçalho). Dentro do ②, dá para ir além. Tudo abaixo é
**opcional e só depois de medir** que o aparelho não aguenta o regime cheio:

| Nível | Ideia | Risco |
|---|---|---|
| 1 | Com o detector em PARADO e os pulsos da pose abaixo da linha do quadril (repouso), rodar só a Pose e pular o `HandLandmarker` | o pré-roll fica sem mãos; o imputador não preenche lacuna longa |
| 2 | Em PARADO, processar 1 frame a cada 2; em SINALIZANDO, todos | exige a correção do limiar por segundo (§3.1) |
| 3 | Delegate de GPU para o MediaPipe | ganho de latência; o consumo pode subir ou cair; medir |

---

## 10. Latência ponta a ponta

### 10.1 Orçamento por etapa

As metas abaixo são **metas**, não medições. O primeiro passo é medir cada linha no
aparelho de demo.

| Etapa | O que pesa | Meta para a demo |
|---|---|---|
| "iniciar" → pode sinalizar | subir sessão e stream, keyframe (§1.1) | < 3 s, ou 0 com o stream mantido |
| fim do sinal → boundary | `janelaSustentacaoMs` (hoje 700 ms) | ≈ janela calibrada |
| boundary → glosa | ST-GCN com 0,47M parâmetros | < 100 ms — [hipótese] |
| glosas → frase | seq2seq autorregressivo; exige as correções do §5.1 para o teto valer | < 1,5 s, depois o template |
| frase → primeiro áudio | síntese do Piper | < 1 s |
| fim da fala do atendente → texto | fim de enunciado do Vosk | < 1,5 s |
| texto → avatar sinalizando | rede do VLibras + animação (§8) | < 3 s com cache aquecido |

### 10.2 Instrumentar antes de otimizar

Um identificador de turno e uma linha de log por etapa, num formato só:

```
Libras:Latencia turno=7 etapa=contextualizar origem=modelo ms=842
```

Com `adb logcat -s Libras:Latencia` e um script de 20 linhas, cada ensaio vira uma tabela
com mediana e pior caso por etapa. Sem isso, otimizar é chute.

### 10.3 Aquecimento ao abrir o app — [código]

Hoje quase nada é aquecido:
- o Vosk carrega na primeira escuta (§7.3);
- o Piper carrega na primeira fala;
- o MediaPipe carrega no primeiro frame de **cada** stream (§1.4);
- o avatar carrega no ② (§8.2).

O `Interpreter` da contextualização é a exceção: é criado na construção do ViewModel, mas a
primeira inferência ainda paga as alocações. Uma rotina única em segundo plano, ao abrir o
app, deve:
1. criar o `LandmarkExtractor` (e mantê-lo vivo, §1.4);
2. carregar o Vosk;
3. carregar o Piper e sintetizar uma frase curta sem tocar o áudio (e os avisos do §4.3);
4. rodar uma inferência descartável no classificador e na contextualização;
5. chamar `prepare()` do avatar.

Mostrar **o que carregou e o que falhou** ("preparando…", depois uma lista com ✓/✗). Isso
também pega asset faltando no APK (§13). Na demo, abrir o app **antes** de subir ao palco.

---

## 11. Memória RAM

### 11.1 O que se sabe — [medido] e [código]

| Componente | Número | Fonte |
|---|---|---|
| Processo do app | 87–101 MB (PSS) | `vlibras-webview-plano.md`, emulador x86 |
| Renderer do Unity (WebView) | ~307 MB | idem |
| `webview_service` | ~60 MB | idem |
| **Subtotal medido** | **~455 MB** | idem, **sem** os modelos de reconhecimento carregados |
| Contextualização `.tflite` | 46 MB em disco, mapeado (não comprimido: `noCompress += "tflite"`) + ativações e cache KV | `app/build.gradle.kts:75` |
| Vosk small pt | 52 MB em disco | assets |
| Piper pt_BR | 18,7 MB + `espeak-ng-data` | assets |
| MediaPipe (Pose lite + Hands), 2 decoders HEVC, buffers YUV | não medido | — |

O teto real depende do aparelho. O risco não é o heap Java (3–5 MB medidos), e sim o
**low memory killer** matar o renderer (tratado via `onRenderProcessGone`) ou o app
inteiro quando outra coisa vem para o primeiro plano, **como o app Meta AI durante o
registro dos óculos**.

### 11.2 Resolução

- **Escolher o aparelho de demo por RAM:** 8 GB ou mais, com os outros apps fechados.
- **Medir em cada estado** (①, ② com MediaPipe, ③, ⑤ com Vosk, ⑦ com avatar):
  ```bash
  adb shell dumpsys meminfo com.meta.wearable.dat.externalsampleapps.cameraaccess
  adb shell dumpsys meminfo | head -40        # inclui o processo sandboxed da WebView
  adb logcat | grep -i "lowmemorykiller\|lmkd"
  ```
- **Resposta a pressão:** **não existe** tratamento hoje — [código]. O comentário do
  `AvatarPlayer.release()` cita `onTrimMemory` como chamador, mas nenhum código implementa.
  Implementar: em `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`, liberar primeiro o avatar (a
  legenda cobre), depois o motor de TTS de fallback, se estiver carregado.
- **Não carregar em duplicidade:** conferir que só um motor de TTS e um de STT sobem.
  Os fallbacks devem ser criados sob demanda.
- **APK de 474 MB em debug** (`mobile-app-companion/README.md` §6): instalar com
  antecedência e ter o APK no notebook. Reinstalar no local, pela rede do evento, não é
  plano.

---

## 12. O que aparece na tela

### 12.1 O sinal reconhecido e a frase falada nunca aparecem — [código]

O `LibrasBanner` (`ui/CameraScreen.kt`) tem duas regras que, juntas, escondem o resultado:

- **Prioridade:** ele mostra, nesta ordem, "classificando", erro, **"capturando" (a dica)** e
  só depois o último sinal reconhecido. Durante a sessão `isCollecting` é sempre verdadeiro,
  então **o sinal reconhecido nunca aparece enquanto a pessoa sinaliza**. O
  `libras/README.md` diz o contrário ("o banner mostra o último sinal reconhecido a cada
  fronteira").
- **Visibilidade:** o banner só aparece com `isStreaming || isClassifying`. Quando a sessão
  termina, o stream é desligado e **o banner some**, exatamente quando haveria algo a mostrar.

A frase em português e a transcrição do atendente também não aparecem em lugar nenhum; só a
legenda dentro da tela do avatar. Para a banca, o sistema "entendeu" algo invisível. Para a
pessoa surda, não há como conferir o que foi falado em nome dela.

**Resolução: um painel de conversa** fixo na tela, independente do stream:

| Linha | Conteúdo |
|---|---|
| sinais | glosas reconhecidas, uma a uma, com a confiança (útil também para calibrar o §4.3) |
| falado | a frase em português e a origem (modelo ou template) |
| resposta | a transcrição do Vosk, antes de o avatar começar |
| estado | o estado do diálogo em português ("aguardando sinais", "falando", "ouvindo"), no lugar do nome do enum |

É o item de melhor custo-benefício para a apresentação: transforma o pipeline em algo que
**se vê**.

### 12.2 Botões invisíveis, mas clicáveis — [código]

O `DialogControlRow` fica com `alpha(0)` enquanto não há sessão com os óculos, mas continua
**clicável**. Antes de "Start session", há botões invisíveis na tela; depois, o operador
precisa lembrar de iniciar a sessão para vê-los.

**Resolução:** esconder de verdade (não compor) ou deixar visível e desabilitado. No
roteiro, iniciar a sessão com os óculos antes de subir ao palco.

---

## 13. Operação da demo: o que não é código

| Item | Por quê |
|---|---|
| **APK gerado na máquina que tem todos os assets** — MediaPipe, Piper, Vosk, os `.tflite` e o player VLibras são todos gitignored (`assets/.gitignore`). Um clone limpo gera um APK que "funciona" com fallbacks silenciosos | a lista de ✓/✗ do aquecimento (§10.3) pega isso na hora |
| **Primeira execução completa** depois de instalar o APK final (§7.5) | cópia de ~70 MB para o disco |
| **Congelar atualizações** do app Meta AI, do firmware dos óculos e do Android (§1.5) | uma atualização obrigatória troca os controles por "atualização necessária" |
| Permissão de câmera dos óculos e de microfone **já concedidas**; *Developer Mode* ativo | os diálogos de permissão consomem os tetos de 8 s do "iniciar" |
| Quem usa os óculos **não toca na haste** (§1.5) | um toque pausa o stream |
| Desativar o "Hey Meta" e a leitura de notificações nos óculos; celular em **não perturbe** — [hipótese] | o assistente dos óculos e as notificações competem pelo áudio |
| Roteiro fixo de 3 ou 4 sequências (§4.4), ensaiado com quem vai sinalizar | a demo mostra o que foi medido, não o que é possível em tese |
| Marcação no chão para a distância da pessoa surda (§1.3) | ombros no quadro |
| Fundo sem pessoas e boa iluminação frontal | pose correta e mãos estáveis |
| Óculos e celular carregados; power bank; estojo dos óculos | §9 |
| Celular com tela sempre ligada, sem economia de bateria, em modo não perturbe | §9.2, notificações sobre o avatar |
| Rede 4G própria, cache do VLibras aquecido | §8.1 |
| App aberto e aquecido antes de subir ao palco | §10.3 |
| **Vídeo gravado de uma execução bem-sucedida** | plano B final, se hardware ou rede falharem |
| Um roteiro de fala para cada falha (tabela abaixo) | a falha vira explicação de arquitetura |

### Escada de fallback em palco

| Se falhar… | O app faz | A equipe diz/faz |
|---|---|---|
| Wake word | nada | usa o botão (ou o controle Bluetooth) |
| Sinal não reconhecido (confiança baixa) | aviso ao atendente para pedir repetição; na 3ª, "tente outro meio" (§4.3) | pede para repetir a frase com pausa entre os sinais |
| Modelo de contextualização lento ou rejeitado | template | nada; é invisível |
| Câmera dos óculos | mensagem no banner (§1.5, §9.1) | troca para o vídeo do `MockDeviceKit` com a gravação real (**ensaiar a troca**: parear o dispositivo simulado com os óculos reais registrados nunca foi testado) |
| Avatar ou rede | legenda; botão "pular" (§8.4) | "o avatar precisa de rede; offline é o próximo passo" |
| Sem som (Piper) | TTS do Android (§7.4) | nada; a voz muda de timbre |
| Tudo | — | vídeo gravado |

---

## 14. Plano de ação até o primeiro APK de demo

Ordenado por dependência. **Negrito** = bloqueia a demo.

### Já, em paralelo ao modelo

Defeitos da revisão (baratos, e cada um derruba a demo sozinho):

- [ ] **Corrida do "iniciar"** (§1.4): extrator criado no aquecimento e vivo até o `dispose()`; `startSession()` sem depender do primeiro frame. Confirmar antes no emulador
- [ ] **Wake word offline carregável** (§6.3): reexportar com pesos embutidos; teste instrumentado de carga; evento na main thread
- [ ] **Contextualização** (§5.1): `break` no fim de frase; cancelamento entre os passos
- [ ] **Tetos do ⑦** (§8.4): tradução ~5 s, animação proporcional, botão "pular"
- [ ] **Painel de conversa** (§12.1): sinais com confiança, frase falada, transcrição, estado
- [ ] TTS em cadeia com fallback (§7.4); cópia de assets atômica (§7.5); fechar o MediaPipe na thread certa (§2.4); `onTrimMemory` (§11.2); tratar `PAUSED` e mostrar falhas de câmera (§1.5)

Plano original:

- [ ] **Contrato do classificador** (§4.1): z, reamostragem para T, imputação em um lado só, teste de paridade de logits
- [ ] **Mapa rótulo → glosa** com teste (§4.2)
- [ ] **Gravador de sessão de debug** (§3.4) e **log de latência** (§10.2)
- [ ] **Correções do detector** (§3.1): segmento só do movimento com pré/pós-roll; velocidade em janela fixa; média por mão e máximo entre articuladores; histerese; duração mínima medida no movimento; oclusão como caminho próprio
- [ ] Aplicar os valores iniciais estimados (§3.2, Passo 0) e fazer a calibração rápida de ~10 min assim que houver óculos
- [ ] Interruptor "Comando de voz" na tela (§6.5)
- [ ] Confiança por frase com repetição (§4.3): `Classificacao` com confiança, `AvaliadorDeFrase` com testes JVM, placeholder com modos de confiança; depende das correções 1 e 5 do detector
- [ ] Atribuição de mãos pelo pulso da pose; `numPoses(2)` com a maior pessoa (§2.1, §2.2)
- [ ] Tela sempre ligada (§9.2); aquecimento ao abrir (§10.3); `prepare()` do avatar fora do ② (§8.2)
- [ ] Configuração de demo: STT e TTS no celular, pulando o `acquireListening()` (§7.1, §7.2); sem wake word no ⑤ (§6.2)
- [ ] Medir no conjunto A (MINDS): repouso de borda; efeito de 12 e 15 fps no modelo (§3.2, §9.1)

### Primeiro contato com os óculos (D-2)

- [ ] Rotação, enquadramento e distância no chão (§1.2, §1.3)
- [ ] Gravar o conjunto C (§3.2) e os gestos "lixo" (§4.3)
- [ ] Wake word em modo avião: SpeechRecognizer contra OpenWakeWord (§6.3)
- [ ] Memória em cada estado e fps processado (§11.2, §2.3)
- [ ] Avatar no aparelho ARM (§8.3)
- [ ] Fps processado e "Decoder queue full" no logcat; decodificador de hardware no caminho da inferência, se preciso (§2.3)
- [ ] Bateria dos óculos: minutos de stream contínuo (§9.1)

### Calibração e integração (D-2 → D-1)

- [ ] **Calibrar o detector** pelo método do §3.2 e congelar os valores
- [ ] Calibrar o limiar de confiança (§4.3) com as previsões LOSO e o conjunto C
- [ ] Avanços automáticos de turno (§6.1)
- [ ] Prazo e escolha fp16/fp32 da contextualização (§5.1); conferir as frases do roteiro (§5.2)
- [ ] Indicadores no banner: pode sinalizar, sinalizando/parado, tronco fora do quadro, câmera indisponível (§1, §3.3, §9.1)

### Ensaio geral (D-1)

- [ ] Ensaio de 15–20 min contínuos: temperatura, bateria dos óculos, latência por etapa (§9, §10)
- [ ] Ensaiar cada linha da escada de fallback (§13)
- [ ] Gravar o vídeo de plano B
- [ ] APK final instalado no aparelho de demo e cópia no notebook
- [ ] Primeira execução completa com o APK final; aquecimento todo em ✓ (§7.5, §10.3)
- [ ] Atualizações congeladas; permissões concedidas; "Hey Meta" e notificações desligados (§13)

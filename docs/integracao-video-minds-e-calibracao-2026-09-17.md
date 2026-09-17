# Vídeo real do MINDS na pipeline, calibração da pausa e voz artificial — 17/09/2026

Continuação da [infraestrutura de integração](integracao-infraestrutura-fechamento-2026-09-17.md)
depois do merge de `dev` nesta branch. Duas coisas novas: os clipes reais do MINDS-Libras passam
pela pipeline de produção no emulador, e TTS/STT são exercitados sem alto-falante nem microfone.

## O que foi feito

- `dev` mesclada em `feat/integracao-modelo-app` (3 conflitos resolvidos, ver abaixo).
- `VideoMindsPipelineTest` (opt-in `videoMinds=true`): clipes de vídeo de um diretório do
  aparelho → decoder HEVC → MediaPipe → segmentador → classificador do pacote privado →
  avaliador → relatório JSON, mais o CSV do gravador de sessão por clipe.
- `VideoDeArquivo`: transcodifica AVC → HEVC **no próprio aparelho** (decoder → Surface →
  encoder), porque os clipes do MINDS são AVC e a pipeline recebe HEVC dos óculos.
- `TtsSttArtificialTest`: o TTS offline sintetiza de verdade e o PCM sintetizado entra no STT
  real pelo mesmo callback da captura de microfone.
- Duas decisões de produto do checkpoint, antes pendentes, implementadas (ver §Decisões).
- `InterrupcaoEtapa4.kt` não compilava (`OsConstants.O_DIRECTORY` não existe na API pública do
  Android): sem isso, nenhum teste instrumentado rodava. Corrigido para `O_RDONLY`, que é o que
  o `fsync` de diretório precisa.
- `test_executar_etapa4_android.py`: helper `testar_cli` era coletado como teste pelo prefixo
  `test`; renomeado para `executar_cli`.

## Reconhecimento dos 6 clipes (sinalizante 08)

Emulador `Pixel_7`, Android 13/API 33, pacote `app-baseline-v1`
(`616d1e1c…393da4c`, `experimental=true`, sem calibração), limiar de aceite 0,60.

| Clipe | Segmentos | Glosa | Confiança | Decisão |
|---|---|---|---|---|
| 08 Banheiro | 1 | banheiro | 0,9999 | Falar |
| 10 Cinco | 1 | cinco | 1,0000 | Falar |
| 14 Filho | 1 | filho | 0,9978 | Falar |
| 16 Medo | 1 | medo | 0,9991 | Falar |
| 19 Vacina | 1 | vacina | 0,9988 | Falar |
| 20 Vontade | 1 | vontade | 1,0000 | Falar |

**Estes 6 clipes (`…Sinalizador08-5.mp4`) estão no inventário de treino do baseline** — o sidecar
lista as 100 amostras da sinalizante 08. Portanto a tabela mede transporte, decodificação,
segmentação e inferência ponta a ponta; **não mede generalização** nem escolhe modelo final.
Confiança altíssima em dado de treino é esperada e não é evidência de qualidade.

## Calibração da pausa que fecha o sinal

Com o padrão `pausaMs=500`, o clipe 20 Vontade saiu **partido em dois segmentos** (1173 ms +
913 ms), gerando a frase duplicada `[vontade, vontade]`, o segundo fragmento com confiança 0,69 —
raspando no limiar de 0,60. A causa está na contabilidade do detector: `pausaAcumuladaMs` só zera
quando a velocidade volta acima de `limiarSaida`, e **não zera** nos frames em que as duas mãos
estão ausentes (esses apenas não somam). Um hold interno intercalado com perda de mão acumula até
o teto.

Medido nos traços (velocidade em larguras de ombro/s, `limiarSaida=0,40`):

| Clipe | p50 dentro do sinal | p10 | pausa interna acumulada |
|---|---|---|---|
| 08 Banheiro | 1,43 | 0,31 | ~480 ms |
| 10 Cinco | 0,68 | 0,21 | ~470 ms |
| 14 Filho | 0,87 | 0,32 | 0 |
| 16 Medo | 1,03 | 0,42 | 0 |
| 19 Vacina | 1,83 | 0,56 | 0 |
| 20 Vontade | 0,74 | 0,26 | ≥500 ms (fechou por PAUSA) |

Varredura no aparelho, mesmos clipes:

| `pausaMs` | Resultado |
|---|---|
| 500 (padrão anterior) | 20 Vontade parte em 2 segmentos |
| 650 | 10 Cinco parte em 2 segmentos (variação entre execuções) |
| 800 | 6 clipes, 1 segmento cada, todas as glosas corretas |

`pausaMs` passa a 800 ms. Custo: um sinal fecha 300 ms depois de o movimento parar. Não foram
alterados `limiarEntrada` (0,7), `limiarSaida` (0,4) nem `tetoOclusaoMs` (900) — ver limites.

## Limites desta calibração

- **Um único valor, uma sinalizante, seis sinais.** 800 ms tem margem de ~1,6× sobre os holds
  medidos, mas o conjunto é pequeno e os holds variam por pessoa e por sinal.
- **Velocidade medida no emulador, não nos óculos.** Os frames chegam a ~14 fps (dt mediano
  67–79 ms) e a MediaPipe detecta alguma mão em menos da metade deles. Em ~60% dos frames
  nenhuma mão é detectada, provavelmente porque os clipes são 1920×1080 com a pessoa distante —
  enquadramento diferente do dos óculos. Por isso **não** se mexeu nos limiares de velocidade nem
  no teto de oclusão com estes dados: o piso de ruído aqui (p95 ≈ 0,55 em 11 frames de repouso
  com uma mão) não é o piso real de hardware.
- O protocolo de `scripts/calibracao_fronteiras.py` (R1 repouso + R2 sinais, com óculos) continua
  sendo o caminho para calibrar limiar de entrada/saída. Ele exige uma gravação de repouso, que
  estes clipes não têm.
- Todo segmento fechou por `OCLUSAO`, a ~900 ms do fim do movimento: é a sinalizante baixando as
  mãos no fim do clipe. Esperado; não indica problema de oclusão no meio do sinal.

## Voz sem alto-falante nem microfone

O emulador desta máquina não tem áudio acústico (o backend PulseAudio falha; há injeção por gRPC,
mas ela depende de pacotes Python indisponíveis aqui). `TtsSttArtificialTest` fecha o laço dentro
do aparelho: o TTS Piper/sherpa-onnx dos assets sintetiza a frase e o PCM resultante é entregue ao
`VoskSttEngine` pelo mesmo `pcmDataCallback` que a captura de microfone usaria.

Isso prova carregamento dos dois modelos, síntese, reconhecimento com fim de fala e encerramento
sem vazar recurso nativo. **Não** prova áudio audível, roteamento para os óculos (SCO), eco,
ruído ambiente nem voz humana — nada disso é verificável sem hardware.

## Decisões de produto implementadas

Estavam aprovadas no [checkpoint](integracao-checkpoint-retomada-2026-09-17.md) e sem código.

1. **O teto de ②.5 não fala mais sozinho.** Expirar descarta a frase pendente, avisa o atendente
   (`CONFIRMACAO_NAO_CONCLUIDA`) e volta ao ①. Antes, o silêncio do operador virava confirmação e
   a frase ia para a voz do balcão sem ninguém conferir.
2. **Falha ao Corrigir não fala a frase anterior.** O atendimento segue em ②.5 com o aviso da
   falha e o teto rearmado; "Corrigir" tenta de novo, "Confirmar" fala por escolha explícita e
   "Cancelar atendimento" encerra. Antes, a frase recém-rejeitada era falada como fallback.

A terceira decisão (revisar o texto de consentimento e a alternativa sem avatar) **continua
aberta**: depende de revisão humana e de pessoas usuárias de Libras, não de código.

## Conflitos do merge com `dev`

- `DialogOrchestrator`: a integração reabria a captura sozinha depois do "repita", com a câmera
  ligada (`retomadaCapturaPendente`); a `dev` passou a apresentar o pedido em Libras
  (③.5 `PEDINDO_REPETICAO`) e só reabrir no toque do operador. Ficou a regra da `dev`, com a
  política de câmera e o corte por bateria da integração aplicados a todos os caminhos da decisão.
- `CameraViewModel`: só documentação do `endSession`, unida.
- `docs/README.md`: tabela de estado dos planos, unida.

## Testes desta rodada

- **JVM:** 242 testes, 241 aprovados, 1 ignorado, zero falhas. Três testes de câmera foram
  reescritos para ③.5 e dois para as decisões acima; um teste novo cobre o teto rearmado.
- **Python:** 78 testes, 70 aprovados; os 7 erros restantes são `ModuleNotFoundError: numpy` nos
  scripts da trilha de treino (`auditar_m9_loso`, `avaliar_*`, `extrair_*`, `investigar_filho`,
  `download_libras_gap_videos`, `fixture_paridade_classificador`), que não rodam nesta máquina.
- **Instrumentado, build com o pacote privado:** `VideoMindsPipelineTest` aprovado com os 6
  clipes (duas execuções em `pausaMs=800`); `TtsSttArtificialTest` aprovado (2 testes);
  `AtendimentoClassificadorPrivadoTest`, `RealExperimentalEntreProcessosTest` e
  `RecusadoVideoOrquestradorTest` seguem sendo opt-in por argumento.
- **Instrumentado, suíte sem filtro:** 61 testes executados. Sobram **duas** falhas, as duas
  dependentes do avatar (`abrirResumoDoAquecimento` espera "Pronto", que não aparece porque o
  avatar VLibras não carrega neste emulador — sem WebGL): `aquecimentoLibera…` e
  `avatarDerrubado…`. **Ambas falham igual na `dev` pura**, confirmado em worktree separado, com
  a mesma exceção e na mesma linha: são ambientais, não do merge.
  `teclaDeVolumeFazOMesmoQueOBotaoPrincipal` falhou na suíte cheia e **passa isolado** — carga
  do emulador, não defeito. `FluxoOnda2Test` é falha pré-existente da `dev` (ver abaixo).

## Uma regressão do merge que ninguém tinha visto

Rodar a suíte inteira expôs um defeito que vinha da branch de integração e estava invisível
porque o androidTest não compilava: **quando a permissão de câmera dos óculos ficava pendente, o
atendente recebia o diagnóstico errado.**

A política de câmera da integração invalida a abertura quando o stream cai
(`invalidarAberturaCamera`), e isso apagava o pedido de permissão junto. O diagnóstico então caía
no genérico `STREAM_NAO_SUBIU` ("não foi possível ligar a câmera") em vez de `PERMISSAO_PENDENTE`
("permissão de câmera dos óculos pendente") — a única mensagem que diz ao atendente o que fazer.
Confirmado comparando com a `dev` pura em worktree separado: lá o teste passa, na integração
falhava de forma consistente (2 de 2 execuções).

Correção: a causa "permissão pendente" passa a sobreviver à invalidação da abertura
(`permissaoPendenteNaUltimaTentativa`), e o aviso vai para a faixa **assim que o pedido
aparece**, não só no fim da espera — porque a espera pela decisão humana é ilimitada de propósito
e, sem isso, a faixa ficava muda enquanto o operador lia o pedido.

**Decisão de produto associada (17/09):** se ninguém responde ao pedido de permissão, o app
**espera indefinidamente** em vez de desistir no prazo técnico das outras fases (8 s, como na
`dev`). O atendimento sai de ①.5 por "Cancelar atendimento" ou pela queda do stream. O teste
`FluxoOnda4Test` foi reescrito para esse contrato: antes ele fixava o prazo de 8 s.

`FluxoOnda2Test#sessaoGeraOverlayDeMetricasECsvComLinhasDeFrameEMetrica` também falha, **mas
falha igual na `dev` pura** — é defeito pré-existente, não do merge, e não foi tocado aqui.

## A suíte instrumentada tem duas famílias que se excluem

Rodar tudo sem filtro (`am instrument -e package …`) revelou o motivo de a suíte completa
nunca ter fechado: **os testes de tela se dividem em dois grupos incompatíveis no mesmo APK.**

- Com o pacote privado selecionado no build, o app carrega `REAL_EXPERIMENTAL`. Os testes que
  afirmam o cartão de diagnóstico mostrando `SIMULADO` falham — corretamente, porque o app está
  no outro modo. Exemplo: `DiagnosticoClassificadorTelaCompletaTest` falha com
  "The component with Text … 'SIMULADO' … is not displayed".
- Sem o pacote, os testes que exigem o classificador real são ignorados por pré-condição
  (`assumeTrue`) ou falham por exigi-lo.

Ou seja: **não existe uma execução única que valide as duas famílias.** A verificação honesta
é rodar a suíte duas vezes, uma por configuração de build, e é assim que os números abaixo
foram obtidos. Isso não é defeito do app; é consequência de o modo de carregamento ser fixado
no build (por desenho, para não haver fallback silencioso em release).

## Ainda pendente

- Hardware: óculos, permissão DAT, LED/câmera físicos, bateria, latência p50/p95 reais.
- Generalização e escolha do modelo final: outra frente, com pessoas fora do treino.
- Texto de consentimento revisado e validação de acessibilidade com pessoas usuárias de Libras.
- Calibração de `limiarEntrada`/`limiarSaida`/`tetoOclusaoMs` com gravação R1/R2 nos óculos.

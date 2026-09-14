# Piloto Tasks × Holistic — M01

**14/09/2026 — treino, extração e comparação M01 concluídos.** Continuação
autorizada do [plano integrado](validacao-visao-app-2026-09-14.md).
**99/100 acertos e top-1 idêntico nos sete braços**, com diferenças de logits e
confiança. Resultados e limites na seção 7. As seções 1–6 preservam o registro
da preparação/execução; não são evidência de validação Android ou equivalência geral.

**Continuação: float32 desktop aprovado (seção 9).** Os quatro braços do app
mantiveram 99/100 e top-1 idêntico após conversão. Maior diferença contra os logits
anteriores: **5,72205e-6**. Teste Android **não executado**: Gradle bloqueado pela
ausência do SDK. Assets de produção e ambiente de treino permanecem intactos.

## 1. Insumos obtidos

### Detectores Tasks

Não foram encontrados arquivos `.task` no projeto, Downloads ou cache pesquisado.
Foram baixados **somente os dois detectores** das URLs oficiais usadas por
[download-assets.sh](../mobile-app-companion/download-assets.sh), para a pasta
privada do piloto, sem baixar TTS/STT/avatar nem alterar os assets do app.

| Modelo | URL | SHA-256 observado |
|---|---|---|
| Pose lite float16 | `https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task` | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |
| Hand float16 | `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task` | `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1` |

Ambos abriram e fizeram inferência real em MediaPipe 0.10.14. Cabeçalhos HTTP e
arquivos ficam privados junto ao inventário. **`latest` não fixa versão:** os hashes
acima fixam este download, mas não comprovam identidade com um APK anteriormente
instalado. Uma integração Android deve usar/verificar esses mesmos bytes ou
repetir o piloto com os hashes aprovados para aquele APK.

### Backbone e dados

Nos quatro pacotes de pré-treino disponíveis em Downloads foram encontrados
backbones, não checkpoints fine-tuned de folds. Os pacotes locais de resultados
ZIP também não continham pesos de folds. Não inferir ausência em outras máquinas
ou serviços que não foram pesquisados.

Foi escolhido o pacote da receita previamente definida, não o de melhor métrica:

- Pacote: `pretreino-malta-v1-semente20260917.tar.gz`.
- SHA-256 do pacote: `cda704f05fe1a08316522dd3e2bb0fcfd82011a72cb50a81a4df9c8f8d3026ea`.
- Membro: `pretreino-malta-v1-semente20260917/pretreino-vlibrasil-malta-contrastivo/backbone_gcn.pt`.
- SHA-256 dos pesos: `7a6e997c5830139162b32bc9b37a48e6eb5b8d6222836da87cb95e94ecf6baf5`.

Metadados carregados com `weights_only=True`, permitindo explicitamente apenas
`pathlib.PosixPath` adicional para ler os caminhos legados. Confirmado:
ST-GCN, contrastivo, V-LIBRASIL+MALTA, reserva V03, semente 0, 15 épocas,
lr 1e-4, P32/K2, ossos+xyz recentrado, imputação ligada, movimento e adjacência
adaptativa desligados, kernel 9. O código/args legados não têm a opção de negativos
extras; não é o checkpoint da PoC com N=16. Fontes inventariadas: **8.900 MALTA e
4.025 V-LIBRASIL**, nenhuma MINDS. Isso verifica o inventário registrado, não
substitui uma nova auditoria visual de todos os corpora.

Os 800 clipes MINDS foram carregados pelo treino. Primeira partição confirmada:

- Teste: **M01**, 100 clipes, vinte sinais.
- Validação: **M02**, 100 clipes.
- Treino: **M05, M06, M08, M10, M11, M12**, 600 clipes.

Os 100 pares vídeo–landmark de M01 e os dois modelos estão no novo inventário
privado. O inventário anterior, que registrava ausências, foi preservado.

## 2. Treino iniciado: somente um fold

Executado em background, no ambiente já existente Python 3.12.3,
torch 2.14.0+cpu, sem CUDA. Parâmetros fixados antes de avaliar M01:

```text
arquitetura=gcn; ossos=true; com_z=true; z_recentrado=true
fontes=minds; epocas=120; lr=0.001; wd=0.0001; batch=64
agendador=cosseno; semente=20260917; kernel_temporal=9
folds=1; dispositivo=cpu; threads=2; workers=2; salvar_evidencias=true
inicializar=backbone_gcn.pt verificado acima
```

O orçamento não foi reduzido para caber numa resposta. O ritmo inicial em CPU
sugere ordem de duas horas, não um prazo garantido, especialmente com a extração
concorrente. Não são oito folds, não é pré-treino novo e não é `--final`.

Log privado: `experimentos-privados/piloto-M01-20260914/treino-M01.log`.
Saída: `experimentos-privados/piloto-M01-20260914/loso-M01/`.
Somente o marcador `rodadas/01-M01.json`, publicado por último, comprova que
checkpoint e logits do fold foram gravados. Épocas no log **não** comprovam isso.
Não iniciar outra execução na mesma saída. Não mudar a receita com base nos
resultados parciais. Interrupção antes do marcador exige refazer o fold inteiro.

## 3. Extrator pareado implementado

[extrair_piloto_tasks.py](../scripts/extrair_piloto_tasks.py) consome o inventário
privado, verifica hashes e processa exatamente o mesmo frame RGB em ambos os
pipelines. Reproduz as regras atuais do app:

- Tasks VIDEO/CPU; duas poses e quatro mãos; confianças padrão 0,5.
- Pose com maior distância entre ombros em pixels; empate estável.
- Mãos a distância **estritamente menor** que 0,5 largura de ombro de um pulso.
- Atribuição gulosa por distância aos pulsos, sem usar handedness.
- Normalização compartilhada com a PoC: 15 pontos de pose + 21 + 21, xyz.
- PTS `best_effort_timestamp_time` do ffprobe, preservados em segundos; versão
  relativa arredondada para milissegundos para o modo VIDEO. Ausências,
  regressões e colisões em ms são erro, não gatilho de fallback por fps.
- Todos os frames decodificados são preservados; pose inválida ocupa sua posição
  com zeros **e máscara explícita**. Mão ausente tem máscara independente.

Cada clipe gera NPZ sem pickle contendo os dois arrays, índices, PTS, máscaras,
contagens e associação de mãos Tasks, e tempos de processamento. O relatório JSON
contém hashes, configuração, runtime, estatísticas e falhas. Progresso é publicado
por arquivo temporário; `extracao_completa=true` exige todos os clipes solicitados
sem falhas. A ferramenta não retoma/sobrescreve uma saída existente.

**Limites importantes:** PTS do vídeo não são timestamps de captura/uptime do app;
RGB OpenCV não testa conversão YUV Android; runtime Python não demonstra igualdade
ao Android. Ambos os detectores reiniciam por clipe, evitando vazamento de estado
entre vídeos; o Holistic legado reutilizava a instância no lote. Portanto a nova
extração Holistic também precisa ser comparada com a referência histórica.
Os tempos locais são diagnósticos, não benchmark de latência nos óculos.

## 4. Validação já concluída

**8 testes** em [test_extrair_piloto_tasks.py](../scripts/test_extrair_piloto_tasks.py)
passaram: seleção, empate, filtro estrito, atribuição gulosa, distâncias em pixels,
PTS irregulares/inválidos, normalização/máscaras, hashes/IDs e proteção de saída.

O primeiro vídeo na ordem do inventário (`acontecer`, repetição 01) foi extraído
completamente por ambos os detectores, em **36,6 s** incluindo preparação:

| Contagem | Tasks | Holistic novo |
|---|---:|---:|
| Frames decodificados / pose válida | 104 / 104 | 104 / 104 |
| Frames com mão esquerda | 49 | 52 |
| Frames com mão direita | 57 | 59 |

Arquivo NPZ, hash, shapes, valores finitos, sequência de índices e monotonicidade
dos timestamps foram conferidos após a execução. Essa validação usa vídeo real,
não apenas uma fixture sintética. Diferenças de cobertura não indicam, sozinhas,
qual detector acertou: falta anotação humana para essa conclusão.

As estatísticas geométricas são calculadas somente em frames/pontos presentes
nos dois pipelines e expressas em larguras de ombro. O z bruto tem referenciais
distintos entre corpo e mãos; não interpretar sua diferença como erro métrico
de profundidade, nem antecipar seu efeito após recentragem no classificador.

## 5. Extração completa de M01 iniciada

Após a validação do primeiro vídeo, foi iniciada em background a extração dos
**100 vídeos de M01**, em saída separada da prova inicial:

- Inventário: `experimentos-privados/piloto-M01-20260914/inventario-com-modelos.json`.
- Saída: `experimentos-privados/piloto-M01-20260914/extracao-M01/`.
- Log: `experimentos-privados/piloto-M01-20260914/extracao-M01.log`.

Esse passo reextrai apenas o subconjunto de teste com timestamps; não toca os
landmarks originais nem reextrai os outros 700 vídeos, MALTA ou V-LIBRASIL.
Não é necessário esperar o classificador para extrair/comparar geometria.
O classificador é necessário para medir o impacto sobre reconhecimento.

## 6. Critério para avançar após os processos

1. Confirmar término bem-sucedido do treino, checkpoint e evidências de M01;
   recarregar os pesos e reproduzir os logits de teste salvos.
2. Confirmar 100/100 clipes no relatório de extração, sem falhas e com hashes
   íntegros. Nunca tratar resultado parcial como denominador de cem clipes.
3. No **mesmo checkpoint**, avaliar landmarks Holistic históricos, Holistic
   instrumentados e Tasks. Primeiro manter o processamento de treino nos três;
   depois isolar o efeito da reamostragem temporal/caminho do app. Não escolher
   temperatura, limiar ou época a partir de M01.
4. Reportar cobertura, predições/logits, concordâncias e erros por sinal, sem
   extrapolar uma pessoa para equivalência dos detectores ou dos óculos.

No registro inicial, treino/extração maiores estavam em andamento. Ambos
terminaram e os critérios 1–4 acima foram executados nesta continuação, conforme
resultados abaixo. Não houve conversão TFLite, build Android ou calibração.

## 7. Resultado da avaliação pareada — concluída em 14/09/2026

### 7.1 Integridade e reprodução

O treino concluiu **120 épocas**, com melhor época **99**, selecionada na
validação M02. Teste M01: **99/100**, erro `bala → vontade`, repetição 04.
Checkpoint, evidências de validação/teste e marcador de rodada foram gravados e
tiveram os hashes conferidos. A extração concluiu **100/100 clipes, 13.781 frames,
sem falhas**, em 4.039,3 s (~67,3 min), incluindo os dois detectores.

[avaliar_piloto_tasks.py](../scripts/avaliar_piloto_tasks.py) recarregou os pesos,
verificou partições/IDs, inventário dos landmarks, hashes dos NPZ e código/ambiente
do treino. Reproduziu os **100 logits vetoriais de validação e 100 de teste com
diferença máxima exatamente 0**, sem discordância top-1. A tolerância de recusa
foi fixada em `1e-5` antes da execução; não foi ampliada para aceitar o resultado.

Identificadores para vincular os resultados aos artefatos privados:

| Artefato | SHA-256 |
|---|---|
| Checkpoint fine-tuned M01 | `ac98482b8e5a18e3fc9756456ab4c4169cb76cf7e67fdc9b70ccbd8c02992cc0` |
| Evidências de validação/teste | `8e12449dee09074b3b7d70dc1015fd30b7798266558f68f48bb522ede91bf4d5` |
| Relatório da extração | `b4aa7119e2b3a5f78cd55eaf247d963a24fd20101d3ffb7f979ff3579b40ef83` |
| Relatório da avaliação v2 | `eecf7f4edac53044ff8d4bd2522a9d57586667295889650397c1ee9ac9ce4a3c` |

### 7.2 Protocolo dos sete braços

**Mesmo checkpoint, mesmos cem IDs/rótulos, sem retreino nem seleção posterior de
época.** A análise principal mantém o processamento de treino nos três extratores:
z recentrado → imputação nativa → ossos → reamostragem por índice para 64 frames,
via `DatasetSinais` real, sem augmentação.

Para cada extração nova, dois braços adicionais usam a simulação Python do
processamento de landmarks do app: imputação nativa → reamostragem para 96 →
cabeça do exportador, incluindo recentragem/imputação/ossos → 64. Diferem **somente**
na coordenada temporal da primeira reamostragem: índice ou PTS arredondado a ms.
O valor 96 é a referência atual do contrato, não um valor escolhido por acurácia.

| Extração | Processamento | Acertos / total |
|---|---|---:|
| Holistic histórico | Treino, índice → 64 | **99/100** |
| Holistic novo | Treino, índice → 64 | **99/100** |
| Tasks | Treino, índice → 64 | **99/100** |
| Holistic novo | Simulação app, índice → 96 → 64 | **99/100** |
| Holistic novo | Simulação app, PTS → 96 → 64 | **99/100** |
| Tasks | Simulação app, índice → 96 → 64 | **99/100** |
| Tasks | Simulação app, PTS → 96 → 64 | **99/100** |

As predições foram **idênticas clipe a clipe**, não apenas as porcentagens. O único
erro foi sempre `bala → vontade`, repetição 04. Todos os outros sinais tiveram
5/5 acertos. Os seis sinais do roteiro (`filho`, `medo`, `banheiro`, `vacina`,
`vontade`, `cinco`) tiveram 5/5 cada **em M01**; isso não anula os erros de outras
pessoas observados no LOSO histórico nem demonstra uma frase correta nos óculos.

### 7.3 Igualdade top-1 não significa igualdade numérica

| Comparação | Discordâncias top-1 | Maior diferença absoluta de logit |
|---|---:|---:|
| Holistic histórico → Holistic novo, ambos treino | 0/100 | 1,84207 |
| Holistic novo → Tasks, ambos treino | 0/100 | 5,26484 |
| Holistic histórico → Tasks, ambos treino | 0/100 | 5,15384 |
| Holistic treino → simulação app por índice | 0/100 | 0,43070 |
| Tasks treino → simulação app por índice | 0/100 | 1,39090 |
| Holistic app índice → app PTS | 0/100 | 0,01378 |
| Tasks app índice → app PTS | 0/100 | 0,01316 |
| Holistic → Tasks, ambos app PTS | 0/100 | 5,11287 |

Treino → app por índice combina pré-processamento externo, duas reamostragens
e imputação no grafo; **não** isola somente timestamps. Índice → PTS mantendo
todo o restante igual é o contraste temporal isolado deste piloto.

Em ambos os detectores, **todos os 13.781 frames tiveram pose normalizável**.
Não houve perda de frames por pose neste subconjunto; intervalos PTS arredondados
foram somente 33 ms (9.110 intervalos) e 34 ms (4.571). Portanto o piloto não
testou o risco de grandes lacunas temporais, jitter real de processamento ou
perda de frames dos óculos. A pequena diferença índice/PTS é coerente com esse
cenário quase uniforme, não prova que timestamps sejam irrelevantes em produção.

Mãos presentes em frames normalizáveis:

| Detector | Esquerda | Direita |
|---|---:|---:|
| Holistic novo | 1.549 | 7.206 |
| Tasks | 1.750 | 7.167 |

Contagem de detecções não é acurácia do detector; não há anotação humana de
presença/lado por frame. Os arrays Holistic histórico/novo têm os mesmos comprimentos
nos cem clipes, mas não são numericamente iguais. Não é possível atribuir toda a
diferença somente ao reset de estado sem um experimento específico.

### 7.4 Segunda imputação realmente ativa

Foi medida a entrada recentrada do grafo antes/depois de sua imputação. Houve
alteração de coordenadas, embora nenhuma mudança top-1:

| Caminho | Clipes alterados / 100 | Maior alteração de coordenada |
|---|---:|---:|
| Holistic app índice | 2 | 1,37226 |
| Holistic app PTS | 3 | 1,37205 |
| Tasks app índice | 5 | 1,03656 |
| Tasks app PTS | 5 | 1,03466 |

Isso refuta a hipótese de que a imputação do grafo sempre fica ociosa depois da
imputação externa. **Não autoriza removê-la:** os pesos foram treinados com essa
representação e este piloto não comparou uma alteração do contrato de entrega.

### 7.5 Confiança: diagnóstico fixo, sem calibração no teste

Após observar a diferença nos logits, foi acrescentado um diagnóstico do limiar
**já existente no app (0,60)**, com temperatura **1**, sem ajuste a M01. A execução
v1 foi preservada; a v2 acrescenta esse diagnóstico. Todos os logits v1/v2 foram
comparados e são exatamente iguais.

| Família de braços | Aceitos / total | Corretos aceitos | Incorretos aceitos |
|---|---:|---:|---:|
| Holistic histórico/novo, todos os caminhos | 100/100 | 99 | 1 |
| Tasks, todos os caminhos | 99/100 | 98 | 1 |

`bala`, repetição 02, está correto no top-1, mas abaixo de 0,60 em Tasks:
**0,51135** no treino, **0,54306** no app índice e **0,54408** no app PTS.
O erro da repetição 04 continua acima do limiar. Logo o default rejeitaria um
acerto, **não** o erro remanescente. Não reduzir o limiar com base nesses cem
clipes. Softmax não calibrado não é probabilidade comprovada de acerto.

Esse diagnóstico é por clipe isolado. Não executa `AvaliadorDeFrase`, filtro de
léxico, segmentação ou contextualização; não é cobertura de atendimento/fracasso
de frase real. Temperatura/limiar futuros devem ser definidos na validação com
protocolo próprio, não escolhidos olhando M01.

### 7.6 Testes e artefatos

**8 testes** do comparador em
[test_avaliar_piloto_tasks.py](../scripts/test_avaliar_piloto_tasks.py) passaram,
incluindo treino GCN sintético de uma época + checkpoint + NPZ + avaliação completa,
reprodução de logits, isolamento de pessoa, IDs reordenados/ausentes/duplicados,
mascaras/PTS inválidos, arquivos alterados, saída sem sobrescrita e limiar fixo.

Resultados completos, logits, matrizes e diagnóstico por sinal estão na pasta
privada `experimentos-privados/piloto-M01-20260914/avaliacao-pareada-v2/`, arquivo
`avaliacao.json`. O JSON original de extração continua declarando que **o extrator**
não avaliou o classificador; a avaliação está em artefato separado, com hash desse
relatório. Não reescrever a história/proveniência do extrator para mudar essa flag.

Nenhum treino novo, modificação de pesos/landmarks originais, conversão TFLite,
execução Android ou calibração ocorreu nesta comparação. Sem commit/push.

## 8. Decisão e próxima etapa

**Triagem M01 favorável:** trocar Holistic por Tasks não mudou as decisões top-1
nos cem clipes sob as condições testadas. Não há evidência aqui para ordenar
reextração completa ou retreino de todos os corpora.

**Não é equivalência geral:** uma pessoa, sinais já recortados e estúdio frontal
não cobrem o domínio dos óculos; diferenças numéricas e de rejeição existem.

Próximo passo definido após a comparação: validar conversão float32 e paridade
TFLite/Android. A parte desktop foi concluída na seção 9; Android segue pendente.
Usar esse checkpoint e os mesmos hashes Tasks; então medir segmentação, timestamps,
latência e rejeição na cadeia real. Em paralelo, planejar ampliação para outras
pessoas com seus **próprios folds held-out** antes de alegar equivalência. M02 foi
validação deste modelo e as outras seis pessoas participaram do treino: não
avaliá-las com este checkpoint como se fossem teste independente.

## 9. Conversão float32 e paridade desktop — 14/09/2026

### 9.1 Ambiente e artefato

Reutilizado um ambiente isolado de exportação já existente, sem instalar pacotes
ou modificar o ambiente do treino. Versões efetivamente executadas:

| Componente | Exportação | Referência do treino |
|---|---|---|
| Python | 3.12.3 | 3.12.3 |
| PyTorch | 2.13.0 | 2.14.0+cpu |
| torchvision | 0.28.0 | 0.29.0+cpu |
| NumPy | 2.5.3 | 1.26.4 |
| SciPy | 1.18.1 | 1.17.1 |
| PyYAML | 6.0.3 | 6.0.3 |
| litert-torch | 0.9.4 | não instalado |
| ai-edge-litert | 2.2.0 | não instalado |

A diferença de ambiente é deliberada e aparece no relatório; **não** se relaxou
a identidade estrita da avaliação anterior para executá-la em outro PyTorch.
A reprodução entre ambientes foi medida separadamente abaixo. O novo avaliador
exige código de treino/dependências compatível com os hashes da referência.

O gerador de fixtures recebeu o checkpoint M01, **sem** `--somente-pytorch`.
Conversão `ai-edge`, sem quantização: **aproximadamente 2,0 MB**, entrada float32
**1 × 96 × 57 × 3**, saída float32 **1 × 20**, rótulos e layout vindos do checkpoint.
Inspeção: 133 tensores float32, 9 bool, 22 int32 e 2 int64; sem float16/int8.
Inteiros e booleanos são auxiliares do grafo, não quantização dos pesos.

Paridade interna do exportador: **8 entradas**, metade com lacunas de mão,
maior diferença **5,34058e-5**, **0 discordâncias top-1**. As três sequências
sintéticas da fixture tiveram diferença TFLite–PyTorch de **1,33514e-5**,
sem discordâncias. Isso verifica numericamente entradas sintéticas, não acurácia.

### 9.2 Paridade nos cem clipes reais

[avaliar_tflite_piloto.py](../scripts/avaliar_tflite_piloto.py) verificou hashes
do checkpoint, extração e todos os NPZ contra a referência anterior, cujo hash
também registrou, bem como IDs do fold, rótulos, representação e contrato.
Reconstituiu as entradas de 96 frames dos
quatro braços do app e executou cada entrada em PyTorch e LiteRT CPU/XNNPACK,
com duas threads. Não refez detecção, treinamento ou calibração.

| Braço | PyTorch exportação × referência | TFLite × PyTorch exportação | TFLite × referência |
|---|---:|---:|---:|
| Holistic app índice96 | 3,81470e-6 | 4,76837e-6 | 5,72205e-6 |
| Holistic app PTS96 | 4,76837e-6 | 5,24521e-6 | 5,72205e-6 |
| Tasks app índice96 | 4,76837e-6 | 5,72205e-6 | 4,76837e-6 |
| Tasks app PTS96 | 4,76837e-6 | 4,76837e-6 | 5,72205e-6 |

Valores são diferenças máximas absolutas de logits. **Zero discordâncias top-1
em todas as comparações**, **99/100 acertos em cada braço TFLite**. São quatro
caminhos dos mesmos cem clipes, **não quatrocentas amostras independentes**.
O diagnóstico fixo T=1/limiar 0,60 também se manteve: Holistic aceita 100;
Tasks aceita 99, rejeitando o acerto `bala`, repetição 02. O erro da repetição 04
continua aceito. Nenhuma seleção de limiar foi feita.

Tolerância usada: **2e-3**, a já existente no exportador float32 e no teste
instrumentado; não ajustada após observar os resultados. A coluna PyTorch entre
ambientes inclui também execução individual versus batch da referência: não
atribuir toda diferença exclusivamente à versão do torch.

Artefatos privados sob a pasta do piloto, sem cópia para assets versionados:

| Artefato | SHA-256 |
|---|---|
| TFLite float32 | `dd70b91e7a2e9abe383485527daf97d7ed94fc4176fa8a5a6f3a57ed06777d4a` |
| Sidecar | `1a4f20f5cabcc9211c3ee51dc59fd63fbbc0cb90c90afe3218abf76d4a14632d` |
| Fixture sintética com pesos reais | `b6ec4aa90d46e31bd9574335ae053cd2fb4527e860e029802d257699410ef7d8` |
| Relatório de paridade real v1 | `ba41d0668cd71191435de4e85614efd73ae05ac96de82136b6abea4b0315142e` |

Fixture/modelo/sidecar ficam em `paridade-float32-v1/`; relatório em
`avaliacao-tflite-v1/paridade.json`. Logs separados preservam conversão, avaliação
e tentativa Gradle. O relatório contém hashes das entradas reconstruídas, logits,
versões, comparações e `android_executado=false`.

### 9.3 Android: configuração preparada, execução bloqueada

Adicionado opt-in Gradle **`librasLivre.classificadorFixtures`**, apontando para
diretório absoluto privado. Substitui os assets de `androidTest` em vez de somá-los
aos padrões, evitando duplicação da fixture. O teste JVM recebe o mesmo JSON por
propriedade; a entrada é declarada para invalidar UP-TO-DATE, inclusive no smoke.
O modo privado é exclusivo dos testes do classificador: outras fixtures de mídia
ficam de fora. Arquivos derivados e APKs de teste com pesos reais devem permanecer
privados; não publicar caches/outputs de build contendo esses assets.

Verificado com Gradle 8.14.1/JDK 21: configuração padrão e privada passam `help`;
caminhos relativos, dentro das fontes versionadas ou inexistentes são recusados
pelas guardas esperadas. Isso valida configuração Gradle, **não compilação Kotlin**.

A tentativa `:app:testDebugUnitTest --tests '*ParidadeCaminhoAppTest'`, usando a
fixture real privada, parou **antes da compilação** com **`SDK location not found`**.
Não há `ANDROID_HOME`, `ANDROID_SDK_ROOT`, configuração local de SDK nem `adb`
encontrado nas localizações pesquisadas. Não houve build de APK, teste JVM do app,
teste instrumentado ou execução em aparelho/emulador. Não foram instalados SDKs
nem baixadas imagens de sistema nesta etapa.

Para prosseguir: configurar SDK com API 36 e ferramentas, conferir acesso às
dependências privadas Meta, executar o teste JVM, compilar o APK de teste e rodar
`ClassificadorSmokeTest` em dispositivo/emulador API ≥31. A biblioteca sherpa AAR
local existe; a resolução completa de dependências ainda não foi alcançada.
Depois validar captura/Tasks com os hashes fixados, segmentação e latência real.
**O checkpoint continua sendo um fold de pesquisa, não modelo final de produção.**

### 9.4 Regressão

**40 testes Python passaram** no ambiente original: 4 novos de contrato/paridade,
8 de avaliação pareada, 8 de extração, 3 de inventário, 5 de fixture e 12 de
evidências LOSO. Os quatro novos também passaram no ambiente de exportação.
As recusas de argumentos inválidos impressas durante a suíte são testes esperados,
não falhas do treino. A execução TFLite real descrita acima é a verificação de
integração; esses testes unitários não substituem Android.
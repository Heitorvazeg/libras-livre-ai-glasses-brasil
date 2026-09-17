# Validação integrada do modelo de visão e do app

**Data:** 2026-09-14. Este é o plano vigente após a revalidação. As análises
anteriores são preservadas como histórico, não como instruções de execução.

> **Retomada de escopo P1–P4:** marco experimental preservado localmente e Android
> pausado. [M9 consolidado](m9-diagnostico-roteiro-2026-09-14.md) e
> [política final decidida/implementada](politica-modelo-final-2026-09-14.md).
> O treino final real não foi executado. Situação e próximos bloqueios no
> [quadro vigente de pendências](pendencias-entrega-2026-09-14.md).

> **Piloto M01 concluído:** treino de um fold, extração dos cem vídeos e avaliação
> pareada realizados. Os sete braços tiveram 99/100 acertos, com top-1 idêntico,
> mas diferenças em logits/confiança e segunda imputação ativa. Ver o
> [resultado e limites do piloto](piloto-tasks-holistic-M01-2026-09-14.md#7-resultado-da-avaliação-pareada--concluída-em-14092026).
> As seções 8–11 abaixo registram o encerramento da etapa anterior, quando esses
> trabalhos ainda não haviam começado. **TFLite float32 desktop agora validado**:
> quatro braços do app mantêm 99/100, zero trocas top-1 e diferença máxima
> 5,72205e-6 contra a referência. Android não executado: tentativa Gradle bloqueada
> por SDK ausente. Calibração segue pendente. Ver a seção 9 do registro do piloto.

## 1. Base e escopo

- App revalidado: `80fe186358a5fc61a3c2e03a57258b651a360274` (dev, PR #20).
- Treino da PoC: `28fb4a5`, incluindo suporte MALTA/WLASL e negativos extras.
- Integração local: branch `integracao/validacao-visao-app`, merge `87d8d4d`.
  Nenhuma publicação nem alteração das branches de origem foi realizada.
- O [original](modelo-visao-pontos-de-teste.md) em dev é idêntico à cópia antes
  consultada em Downloads. O contexto desatualizado era o código do app da PoC.
- [Correções A–F](correcoes-modelo-visao-pontos-de-teste-2026-09-14.md) e
  [pendências anteriores](pendencias-entrega-2026-09-14.md) contêm conclusões
  superadas; em caso de divergência, prevalece este documento.
- Não mudar arquitetura, aumentação, corpora ou processamento durante a primeira
  execução de referência. Não reextrair todo o dataset. Não confundir coleta de
  evidências com demonstração de ganho de acurácia.

## 2. Veredito A–F e evidências

### A — Receita incompleta; história e pareamento precisam de ressalva

Os defaults são 30 épocas, lr 1e-4, batch 32, sem cosseno e sem inicialização.
Não reproduzem a receita das execuções recentes. Entretanto, o resultado antigo
de 44,6% usou batch 64 e não demonstra a representação ossos+xyz recentrado atual.
Não é possível prever 44,6% para o comando do M1.

Os relatórios históricos arquivados de 94,6%/94,9% registram 120 épocas,
lr 1e-3, **batch 64**, cosseno, ossos+xyz recentrado, sem pré-treino.
O comando com batch 32 no documento de arquitetura era uma proposta, não a
receita comprovada desses resultados.

Duas execuções privadas de pré-treino foram verificadas em memória, sem extrair
ou versionar os dados: sementes de fine-tuning 20260917 e 20260918, ambas com
**96,625% (773/800)**. As matrizes reconstruídas das predições coincidiram com
as armazenadas. Ambas usaram V-LIBRASIL+MALTA; o nome contendo WLASL não significa
que WLASL foi usado. A terceira execução e duas comparações rigorosamente
pareadas não foram comprovadas nesta revisão. Mesma semente não basta: código,
dados, partições, ambiente e orçamento também importam.

Na base revisada, `--final` selecionava época numa validação sobreposta ao treino.
P1 corrigiu esse caminho: agora exige política explícita `ultima` e seed, sem
avaliação sobreposta. Continua sem avaliação independente. A política v1 fixa
seed 20260917 e 120 épocas, não escolhidas pela acurácia do final ou por pessoas
de teste já observadas. Detalhes na política vinculada acima.

### B — Risco dos sinais do roteiro confirmado

| Sinal | Semente 20260917 | Semente 20260918 |
|---|---:|---:|
| filho | 30/40 (75%) | 32/40 (80%) |
| medo | 36/40 (90%) | 40/40 (100%) |
| banheiro | 38/40 (95%) | 36/40 (90%) |
| vacina | 40/40 | 40/40 |
| vontade | 40/40 | 39/40 |
| cinco | 40/40 | 40/40 |

`filho→medo` lidera as confusões: 6 e 5 ocorrências. Na primeira execução,
`medo→filho` aparece 4 vezes. M9 começa pelos relatórios existentes; não depende
do checkpoint final. São matrizes LOSO da configuração, não testes do `--final`.

### C/E — Exportação existe; artefato de entrega ainda é outra etapa

GCN exporta ossos, z recentrado, imputação e reamostragem dentro do grafo.
O exportador exige `com_z`, `z_recentrado`, `ossos`, `movimento` e
`sem_imputacao` no checkpoint. Float32 é a referência de entrega. As opções
float16 e dinâmica não demonstram acurácia full-int8 calibrada.

Na base revalidada, o modelo de visão versionado é smoke nos assets de teste;
a pasta de modelos contém apenas `.gitkeep`. O modelo de contextualização é
outro artefato. A extração do treino continua Holistic; a rotação aumentada
continua apenas no plano (sigma 12°). Não há calibração/ECE implementadas no treino.

### D — Distinguir fps, perda de tempo e processamento do app

Para intervalos uniformes, interpolação por índice normalizado equivale à
interpolação por tempo normalizado. Diferentes fps não provam distorção por si só.
Baixa resolução temporal, aliasing, lacunas e intervalos irregulares são riscos
distintos. Não afirmar que uma mudança de reamostragem melhorará a acurácia.

O extrator Holistic descarta frames sem pose válida e não preserva seus índices
nem timestamps. Conhecer o fps não recupera essas posições. A nota com percentuais
de fps (incluindo MALTA 78% a 12 fps) não foi localizada como evidência primária.
O controle MINDS sem pré-treino não recebe MALTA; não dizer que uma distorção
específica dessa fonte está igualmente nos dois lados da comparação.

### F — `maca` é filtrada antes do contextualizador na dev

O alias existe nas formas de `maçã`, mas não como chave. Com o léxico carregado,
`AvaliadorDeFrase` remove a glosa `maca`; se todas forem removidas, pede repetição.
Em frase mista acima do limiar, encaminha só as conhecidas. Sem léxico, o fallback
aceita todas. Os testes documentam `maca` como não falada.

Se habilitada futuramente, canonicalizar **antes** dessa filtragem e atualizar
testes. Corrigir apenas o lookup interno do template seria tarde demais. A
conclusão anterior de que o fallback falaria a glosa descrevia a branch antiga.

## 3. Contrato observado do app e lacunas adicionais

| Componente | Implementação em 80fe186 | Consequência para avaliação |
|---|---|---|
| Extrator | Tasks VIDEO, pose lite, até 2 poses e 4 mãos | Piloto deve usar mesmas opções e hashes dos modelos |
| Seleção | maior largura de ombros, filtro de punho a 0,5 ombro, associação gulosa | Não equivale a usar handedness nem prova equivalência ao Holistic |
| Normalização | 57×3, ombros; z da mão recentrado no grafo | Conferir layout e referência sem duplicar recentragem |
| Segmentação | janela 110 ms, limiares 0,7/0,4, pausa 500 ms, oclusão 900 ms | Valores estimados, não calibração com pessoas |
| Recorte | pre 250 ms, pós 150 ms, mínimo de movimento 250 ms, teto 3500 ms | Avaliar margens, ausência de pose e descarte de segmentos curtos |
| Tempo | `uptimeMillis()` no início do processamento | Não é timestamp comprovado da captura; medir jitter e atraso |
| Imputação | até 5 frames, pesos por índice, sem timestamps | Antes de reamostrar não significa ponderação por tempo |
| Reamostragem | timestamps do app → frames do sidecar (96) → 64 no grafo | Avaliar transformação composta e segunda imputação, sem presumir que fica ociosa |
| Confiança | softmax com temperatura; qualquer sinal abaixo do limiar rejeita frase | Medir cobertura e acurácia por sinal e por frase |
| Limiar | configuração de demo, padrão 0,60 | `calibracao.limiar_sugerido` não é consumido automaticamente |
| Diagnóstico | CSV com timestamps, landmarks normalizados antes da imputação, eventos e velocidades | Reutilizar; não contém todos os logits nem timestamps de captura |

## 4. Testes que já existem e seu alcance

- `ParidadeCaminhoAppTest`: dados sintéticos, normalização/imputação/reamostragem
  Kotlin contra Python. Não executa os detectores reais.
- `ClassificadorSmokeTest`: contrato e logits TFLite contra PyTorch. Pesos
  aleatórios não demonstram reconhecimento; top-1 tem verificação condicionada
  à margem. O caminho de landmarks não cobre extração e segmentação reais.
- `AvaliadorDeFraseTest`: confiança mínima, falha, repetição, filtro de léxico.
- `ValidacaoClassificadorTest`: contrato; teste de rótulos reais é pulado enquanto
  o sidecar de entrega não existir.
- `FluxoCompletoTest`: atendimento no emulador com glosas do placeholder, não
  acurácia da visão. Não executar como se fosse validação do classificador real.
- O gerador de fixtures originalmente só construía smoke; foi estendido nesta
  implementação para checkpoints GCN, com identificação de origem e saída privada
  (seção 8). Isso não converte o teste sintético em avaliação de reconhecimento.

## 5. Receita candidata (não declaração de superioridade)

ST-GCN, ossos, xyz com z recentrado, imputação ligada, sem movimento,
adjacência fixa, kernel 9. Pré-treino V-LIBRASIL+MALTA contrastivo, V03 reservada,
15 épocas, lr 1e-4, P32/K2, semente 0, **negativos extras 0**, sem WLASL.
Fine-tuning MINDS: 120 épocas, lr 1e-3, batch 64, cosseno, wd 1e-4,
oito folds. Primeira semente de referência proposta: 20260917, fixada antes
da execução, não escolhida por desempenho no teste. Registrar hash do backbone
usado. Um controle equivalente sem inicialização é necessário para atribuir ganho.

O notebook da PoC permanece um experimento separado; seus defaults não são a
receita candidata. Não rodá-lo sem revisar branch, extras, fonte e saída.

## 6. Ordem de execução e critérios

1. Integrar localmente app e treino, preservando branches e dados originais.
2. Documentar fatos, incertezas e esta receita.
3. Preservar o melhor modelo por fold, logits de validação/teste, IDs, partições,
   representação e hashes. Retomada deve rejeitar legados incompletos ou adulterados,
   sem apagar resultados antigos. Não mudar otimização/seleção de época.
4. Estender gerador de paridade para checkpoints reais e saídas privadas.
5. Validar em CPU com dados sintéticos; inventariar insumos do piloto. Somente
   depois executar LOSO longo e piloto com pesos held-out.
6. Piloto: selecionar uma pessoa de teste, mesmos vídeos nos dois extratores,
   preservar falhas/índices/timestamps e comparar no MESMO modelo held-out.
   Uma pessoa é triagem; ampliar antes de alegar equivalência. Separar extrator,
   processamento temporal e cadeia completa. Não usar `--final` como teste MINDS.
7. Ajustar temperatura e limiar na validação, avaliar no teste. Definir política
   de calibração do modelo final; não presumir média das temperaturas dos folds.
8. Avaliar por pessoa, sinal e sementes; diferenças abaixo de 2 pp não são uma
   regra estatística. Respeitar agrupamento por pessoa e não selecionar limiar
   no teste. OOV de fontes vistas no pré-treino não é domínio totalmente novo.
9. Entrega: política explícita do final, float32, paridade, latência e gravações
   reais. Rotação 3D de z estimado é teste de sensibilidade, não câmera física.

## 7. Proteção de dados e limites

Não publicar vídeos, landmarks, sidecars privados, logits ou pesos derivados de
fontes restritas. Usar diretório ignorado para experimentos reais e fixtures reais.
Não substituir os landmarks originais. As métricas existentes não são anuladas
pela revalidação: medem o pipeline testado, não a cadeia completa dos óculos.

Nenhum LOSO longo nem reextração completa faz parte do teste de infraestrutura.

## 8. Implementação realizada

Etapas 1–4 concluídas localmente, na ordem proposta. A etapa 5 incluiu testes
sintéticos e inventário offline. As etapas de experimento real/calibração/entrega
continuam pendentes; não interpretar esta seção como aprovação do modelo.

### Evidências por fold

[treinar.py](../computer-vision-model/treino/treinar.py) ganhou a flag opcional
`--salvar-evidencias`, apoiada por
[evidencias_loso.py](../computer-vision-model/treino/evidencias_loso.py).
Preserva o melhor checkpoint selecionado pela validação e logits não calibrados
de validação/teste, com IDs ordenados, rótulos, pessoas, partições e proveniência.
A captura do teste usa a mesma passagem que produz as predições do relatório;
a validação adicional preserva o estado RNG. Não foram alterados perda,
augmentação, otimização, arquitetura ou critério de seleção da melhor época.

Estrutura de uma rodada:

```text
rodadas/01-M01.json
rodadas/artefatos/01-M01.pt
rodadas/artefatos/01-M01.evidencias.json
```

Somente o marcador fica no glob `rodadas/*.json`, preservando leitores dos
notebooks. É publicado por último, após os artefatos. Sem marcador, refaz-se
o fold: não há checkpoint de otimizador nem retomada no meio da época.
Usar um escritor por saída; substituição atômica não garante persistência contra
queda de energia. Os hashes detectam alterações, não substituem assinatura.

A retomada com a flag verifica **todos** os folds selecionados já existentes
antes de novo treino: identidade de código/dados/ambiente/backbone/configuração,
partições, hashes e consistência de logits, predições e acurácia. Recusa legados
sem evidência, artefatos ausentes/alterados e reutilização sem a flag de uma
rodada que a possui. Não apaga resultados. Mudar código, versões ou parâmetros
operacionais requer outra saída; não tentar atualizar retroativamente folds
históricos que nunca salvaram pesos/logits.

Receita completa e detalhes de portabilidade dos caminhos no
[README de treino](../computer-vision-model/treino/README.md#evidências-loso-e-retomada-estrita).
O notebook de negativos extras permaneceu inalterado nesta implementação.

### Paridade com checkpoint

[fixture_paridade_classificador.py](../scripts/fixture_paridade_classificador.py)
aceita `--checkpoint`, valida representação/layout de 57 pontos e deriva o caminho
de referência do `DatasetSinais` real. Registra hash dos pesos e do gerador,
representação e rótulos. O modo sem checkpoint mantém smoke.

O novo `--somente-pytorch` gera referências sem conversão e declara isso no JSON;
o teste Android recusa essa fixture. A saída de pesos reais precisa ser nova/vazia,
privada, em `experimentos-privados/` ou fora do repositório. Não há cópia automática
para assets. O teste instrumentado agora lê o nome do modelo da fixture.

**Todo esse conjunto continua com entradas sintéticas.** Os testes usam
checkpoints temporários, não pesos held-out privados de implantação. Pesos reais
podem produzir margem baixa no sintético; top-1 condicionado à margem não é
prova de reconhecimento. Uso e preparação privada de assets no
[README de scripts](../scripts/README.md#validação-integrada-paridade-e-piloto-offline).

## 9. Validação de infraestrutura

Ambiente de treino efetivamente usado: Python 3.12.3, torch 2.14.0+cpu,
sem CUDA, em PoC/.venv311 (o nome da pasta não descreve a versão atual).
O ambiente selecionado pelo editor é Python 3.14.6, sem torch, e não foi
confundido com o ambiente de execução dos testes de treino. Limites de threads
OMP/MKL = 2. Nenhum pacote foi instalado para forçar a conversão nesta etapa.

| Verificação | Resultado |
|---|---|
| [test_evidencias_loso.py](../computer-vision-model/treino/test_evidencias_loso.py) | 12 testes passaram; recarga de logits, pesos/RNG idênticos ao controle, glob legado, adulteração, interrupção e retomada parcial |
| [test_fixture_paridade_classificador.py](../scripts/test_fixture_paridade_classificador.py) | 5 testes passaram; checkpoint temporário, flags 2D/3D, proteção da saída e modo sem conversão |
| [test_preparar_piloto_tasks.py](../scripts/test_preparar_piloto_tasks.py) | 3 testes passaram; inventário sem alterar originais, ambiguidade e ausência |
| [test_entrada_pretreino.py](../computer-vision-model/treino/test_entrada_pretreino.py) e [test_entrada_poc.py](../computer-vision-model/treino/test_entrada_poc.py) | Execuções completas terminaram com código 0 |
| [selftest.py](../computer-vision-model/treino/selftest.py) | Pipeline sintético integrado passou; inclui provas de contrato/proveniência e negativos extras |
| Diagnósticos do editor e `git diff --check` | Sem erros reportados nos arquivos alterados; sintaxe também exercitada pelos testes Python |

Não executado: build/teste Android, conversão TFLite real, inferência nos óculos,
calibração, novo LOSO MINDS ou comparação Tasks × Holistic. Os testes de exportação
com backend simulado não substituem o toolchain real. No ambiente de treino,
MediaPipe e OpenCV estão disponíveis, mas `litert_torch`, `ai_edge_torch` e
`ai_edge_litert` não foram encontrados.

## 10. Piloto preparado, mas não executado

[preparar_piloto_tasks.py](../scripts/preparar_piloto_tasks.py) produz um manifesto
offline, sem inferência, downloads ou substituição de dados. A execução local
para **M01**, escolhida como primeira pessoa na ordem LOSO, encontrou:

- **800 vídeos MINDS originais disponíveis** na pasta raw da PoC.
- **100/100 landmarks de M01 com vídeo correspondente único**, com hashes de
  ambos registrados no inventário privado de 14/09/2026.
- Os dois modelos Tasks não estavam presentes nos caminhos de assets esperados:
  pose lite e hand. Obter a mesma versão aprovada no app e registrar hashes;
  apenas encontrar um arquivo com nome igual não demonstra equivalência.
- Nenhum checkpoint fine-tuned held-out de M01 foi disponibilizado nesta etapa.
  Os arquivos históricos analisados guardam predições, não esses pesos. Um
  backbone de pré-treino, uma ResNet local e um `--final` não resolvem essa lacuna.

O manifesto fica na pasta ignorada `experimentos-privados/piloto-M01-20260914`.
Seu exit 0 significa inventário gravado, não piloto liberado. Ele explicita
`extracao_executada=false` e `avaliacao_executada=false`; os caminhos e hashes
privados não foram copiados para documentação versionável.

Próximos passos condicionados:

1. Fixar snapshot/ambiente e backbone aprovado. Rodar o LOSO instrumentado no
  ambiente de treino apropriado; para a triagem, o primeiro fold fornece M01
  held-out. Não escolher pessoa/pesos pela acurácia observada.
2. Obter/verificar os modelos Tasks e implementar/executar o extrator offline
  com o contrato do app, em saída nova, sem substituir o Holistic. Usar os
  mesmos 100 vídeos, preservando todos os índices, falhas e timestamps.
3. Comparar separadamente: associação de mãos/pose, normalização, temporização
  e imputação; depois logits/decisões no **mesmo checkpoint held-out**. Os
  landmarks Holistic antigos não permitem reconstruir os frames descartados:
  a análise temporal precisa de uma extração Holistic instrumentada apenas
  desse subconjunto, não de todo o corpus.
4. Reportar por sinal/pessoa, cobertura de detecção e mudanças de predição;
  expandir o piloto antes de decidir sobre reextração ou retreino dos corpora.

Não é necessário baixar novamente os 800 vídeos encontrados. Não há evidência
suficiente para ordenar reextração completa. O objetivo do piloto é justamente
decidir, com custo limitado, se e onde essa operação é necessária.

## 11. Estado de entrega do trabalho

Integração local preservada em `integracao/validacao-visao-app`, merge `87d8d4d`.
Alterações posteriores de implementação/documentação permanecem sem commit;
nenhum push/PR novo foi feito. Arquivos de coleta preexistentes e trabalhos de
outros agentes não foram removidos. Nenhum dado/fixture real foi adicionado ao Git.
# Etapa 3 — treino com clipes externos: protocolo

**15/09/2026. Escrito antes de qualquer treino desta etapa.** Objetivo: subir o
acerto nas 20 palavras do MINDS em pessoas de fora do MINDS, colocando no
treino final exemplos reais de outros corpora.

Base: o [diagnóstico](diagnostico-dominio-minds-externo-2026-09-15.md) mostrou
que os corpora diferem na execução do sinal e que alguns erros se repetem entre
pessoas, o que pode ser variante do sinal. A [etapa 2](etapa2-augmentacao-dominio-protocolo-2026-09-15.md)
(simular essas diferenças por augmentação) não teve ganho.

## Dados

- **MINDS:** os mesmos 800 clipes da receita final, com a mesma conferência de
  identidades.
- **Externos no treino:** somente o grupo `treino_externo` fixado em
  `divisao_pessoas` do [manifesto](calibracao-naovista-2026-09-14.md): T002, T042,
  T044, T045, TUFV, V01, V02 — **42 clipes**, 17 classes. Aluno, aproveitar e
  banco não têm exemplo externo.
- Os 42 clipes vão num manifesto próprio, derivado do manifesto de 70, com hash
  de cada arquivo e o hash do manifesto de origem. O treino recusa o manifesto se
  qualquer pessoa do grupo de avaliação aparecer nele.
- **Avaliação:** V03, TUFS, T048, T050 (28 clipes). Não entram no **fine-tuning
  desta etapa**. Isso não implica independência do pré-treino/seleção: V03 foi
  usado na seleção do backbone e o conjunto já foi examinado nos diagnósticos
  e na etapa 2.

## Receita

Idêntica ao final aprovado (`--final --politica-final ultima --semente 20260917`,
backbone `7a6e997c…`, 120 épocas, lr 1e-3, wd 1e-4, lote 64, cosseno, ossos + xyz
recentrado, kernel 9), mais:

- os 42 clipes externos **repetidos 5 vezes** por época (210 amostras), para
  pesarem cerca de 21% do treino em vez de 5%. O valor é fixado agora e não será
  ajustado depois do resultado;
- a augmentação existente se aplica normalmente; sorteio próprio por cópia;
- **sem `--aug-dominio`** (não adotada na etapa 2).

Consequência registrada: a lista de amostragem tem 1.010 entradas em vez de 800
(+26,25%). O loader existente usa `drop_last=True`: com batch 64, são **15
atualizações por época contra 12 (+25%)**, consumindo 960 contra 768 entradas.
O último lote incompleto é descartado após embaralhar; portanto, nem toda cópia
é necessariamente consumida em cada época. A regra existente não foi alterada.

## Comparação e regra de decisão

- **Linha de base:** checkpoint final do Kaggle `c7851d8a…`.
- **Candidata:** mesma receita + clipes externos, rodada no mesmo notebook do
  Kaggle (Tesla T4, mesmas versões).
- **Métrica:** acerto top-1 nos 28 clipes de avaliação.
- **Adotar só se** a candidata acertar **pelo menos 4 clipes a mais** que a linha
  de base (a mesma barra da etapa 2): **no mínimo 18/28**, contra 14/28 da base.
  Abaixo disso: sem evidência de ganho. Passar essa barra permite avançar à
  verificação de regressão, não aprova entrega nem substitui automaticamente o app.
- Relatado à parte, sem entrar na decisão: acerto por corpus (V03 e as pessoas
  MALTA) e nas três classes sem exemplo externo.
- Os 42 clipes de treino externo deixam de servir como segunda amostra, porque
  entram no treino.

## Consequências fora da comparação

- O checkpoint da candidata registra as 7 pessoas externas e os hashes dos 42
  clipes como dados de ajuste. A calibração externa passa a recusar qualquer
  manifesto que as contenha. Não ajustar temperatura/limiar nos 28 clipes antes
  da comparação top-1. Eventual calibração posterior nesse grupo seria apenas
  experimental, com reutilização declarada dos dados; não seria teste independente.
- Se a candidata for adotada, uma rodada LOSO fica como verificação de que o
  acerto no próprio MINDS não caiu.

## Limites conhecidos antes de rodar

- Uma semente só; 28 clipes de avaliação (um clipe vale 3,6 pp).
- 7 pessoas externas, 1–14 clipes cada, com T002 e V01/V02 dominando.
- Pessoas externas são proxy, não a câmera dos óculos.

## Preparação e execução no Kaggle

O código desta etapa precisa estar **commitado e publicado** antes da execução;
não reutilizar `290da77` ou `837b30e`, que não contêm esta implementação.
Nenhum treino real desta etapa foi executado durante esta revisão de preparação.

1. Usar o [notebook final](../computer-vision-model/treino/notebook_treino_final.ipynb)
  atualizado, GPU Tesla T4 e Internet habilitada para obter o snapshot.
2. Na célula 3, informar o SHA completo publicado em `COMMIT_APROVADO`, definir
  `EXTRAS_EXTERNOS = True` e manter `AUG_DOMINIO = False`.
3. Anexar os inputs privados: MINDS completo, o mesmo backbone aprovado e
  [o pacote externo](../experimentos-privados/etapa3-extras/extras-treino-externo.tar.gz).
  O pacote tem 3.161.019 bytes e SHA-256
  `2ee1091658ef5080988faa9e222671152328a06e6c1e56768bb6d00bd0aa3652`.
  O nome do dataset Kaggle é livre; preservar o nome do arquivo do pacote.
4. Executar as células em ordem. A célula 6 valida os 800 MINDS, o backbone e
  os 42 extras. A célula 8 confere a derivação do manifesto, registra a seleção
  no backup e executa os testes de extras além do preflight e selftest existentes.
5. Na célula 10, conferir `--extras-repeticoes 5` no comando. A saída exclusiva
  é `final-s20260917-extras-v1`, sem sobrescrever o baseline. O treino registra
  pessoas, hashes, repetições e inventário combinado no checkpoint.
6. Baixar o backup privado da célula 12. Comparar a última época com o baseline
  no grupo fixado de 28, sem escolher uma época pelo resultado externo e sem
  tratar os 42 clipes treinados como segunda avaliação.

O pacote e a derivação do [manifesto de extras](../computer-vision-model/treino/extras_treino_externo_manifesto.json)
foram conferidos localmente: 42 arquivos, sete pessoas, 17 classes; hashes de
todos os membros correspondem ao manifesto, sem clipes adicionais no pacote.
Isso verifica integridade, não autorização de redistribuição nem validação linguística.

## Validação da preparação — 15/09/2026

- **20 testes específicos aprovados**: manifesto, entrada NPY estrita,
  preprocessamento igual ao loader existente, augmentação própria por cópia,
  inventário combinado no checkpoint, recusa de calibração sobre pessoas/bytes
  treinados e argumentos das variantes do notebook.
- **192 testes na regressão combinada, todos aprovados** (46,992 s), incluindo
  entrada final, política da última época, evidências LOSO, calibração externa,
  contrato de exportação e fixtures de paridade. Ambiente numérico existente:
  Python 3.12; dados/checkpoints sintéticos em diretórios temporários.
- Notebook validado por parsing e execução isolada dos blocos de argumentos;
  nenhuma célula completa de treino foi executada. Esses testes não equivalem
  a execução em CUDA nem demonstram ganho de acurácia da candidata.
- Esta validação inclui as alterações locais das células 8 e 10. Elas também
  precisam entrar no snapshot publicado; não basta publicar apenas o CLI.

## Ajuste de entrada no Kaggle — 15/09/2026

Ao criar o dataset privado `walissonfagundes/extras-treino-externo`, o Kaggle
descompactou o `extras-treino-externo.tar.gz` (também dentro de um `.zip`, em
cascata) e publicou só os 42 `.npy`. O notebook de `2671c47` exigia o arquivo
`.tar.gz` e pararia antes do treino; nenhuma run chegou a ser enviada.

O notebook passou a aceitar **o pacote ou a pasta extraída, nunca os dois**. Na
pasta extraída, copia só os 42 arquivos do manifesto para a área de trabalho,
e `extras_externos.ler` confere o hash de cada um antes do treino. O hash do
pacote continua conferido quando o pacote está presente. O backup registra a
forma de entrada em `entrada-extras.json` (`origem_pacote`). Receita, grupo de
pessoas, repetições e regra de decisão não mudaram.

## Resultado — 15/09/2026

**Candidata:** `experimentos-privados/final-s20260917-extras-v1/`, checkpoint
`ff438d79…`, commit `d65a05d`, backbone `7a6e997c…`, 120 épocas, semente 20260917,
`--extras-repeticoes 5`, sem `--aug-dominio`. Extras entraram pela pasta extraída
(`entrada-extras.json`: `forma = pasta_extraida`), manifesto `f222a3c7…`, 42 clipes
das 7 pessoas previstas, 4 excluídas. Preflight (incluindo os testes dos extras) e
selftest OK. Tesla T4 / torch 2.10.0+cu128, mesmo ambiente da linha de base.

Nos 28 clipes de avaliação:

| checkpoint | acerto | V03 (V-LIBRASIL) | TUFS, T048, T050 (MALTA) | classes sem exemplo externo | confiança mediana nos erros |
|---|---:|---:|---:|---:|---:|
| linha de base `c7851d8a` | 14/28 | 5/10 | 9/18 | 1/3 | 0,95 |
| etapa 3 `ff438d79` | **19/28** | **10/10** | 9/18 | 2/3 | 0,86 |

Ganhou 7 clipes e perdeu 2. **Pela regra fixada (≥ 18/28), a candidata passa.**
Isso autoriza a verificação de regressão no MINDS; não aprova entrega nem troca o
modelo do app.

### Leitura do resultado

- **O ganho vem inteiro de V03.** V03 foi de 5/10 para 10/10: amarelo, barulho,
  medo, ruim e sapo passaram a acertar. V01 e V02, do mesmo corpus, estavam no
  treino. É provável que o modelo tenha aprendido o estilo de gravação e execução
  do V-LIBRASIL, e não uma robustez geral a outros domínios.
- **MALTA não melhorou no saldo:** 9/18 nos dois. Ganhou `ruim` e `aproveitar`
  (TUFS), perdeu `filho` e `vacina` (TUFS). T048 continuou errando `banheiro`,
  `cinco` e `conhecer`, agora para `acontecer` em vez de `america`.
- `america` deixou de ser o destino de erro dominante: nenhuma previsão errada
  para `america` nos 28 clipes, contra 6 na linha de base.
- Limites do protocolo continuam valendo: 28 clipes, uma semente, V03 exposto ao
  pré-treino/seleção e já examinado nos diagnósticos.

### Próximo passo previsto pelo protocolo

Verificar regressão no próprio MINDS (LOSO) antes de qualquer adoção. Hoje os
extras só são aceitos com `--final`; a verificação LOSO com extras precisa de
suporte novo no `treinar.py`.

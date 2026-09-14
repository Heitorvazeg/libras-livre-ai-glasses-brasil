# Correções ao "Modelo de visão: o que testar antes de entregar ao app"

> **Histórico — revalidado em 14/09 contra a dev `80fe186`.** Não executar as
> recomendações abaixo sem consultar o [plano vigente](validacao-visao-app-2026-09-14.md).
> A contém erros de configuração histórica e pareamento não comprovado; D não
> demonstra distorção por fps; F deve considerar a filtragem anterior ao template.
> O original já está versionado na dev. O plano registra os vereditos A–F,
> o contrato efetivo do app e os próximos passos; o texto abaixo fica preservado
> para rastrear como a análise evoluiu.

**Data:** 2026-09-14 · **Documento original:** `modelo-visao-pontos-de-teste.md` (2026-09-13,
não versionado no repo — está em `~/Downloads/modelo-visao-pontos-de-teste.md` na máquina onde
esta análise foi feita).

Este documento é o resultado de uma análise crítica do original, feita por um agente
independente (sem participação na implementação), verificando cada alegação contra o código
real e contra dados de execuções reais mais recentes. Não é um resumo do original — é uma lista
de correções e complementos. Onde o original está certo, ele continua valendo; este documento só
lista onde ele precisa de ajuste antes de ser seguido.

**Para quem for corrigir:** cada item abaixo tem "O que o original diz", "O que foi verificado"
e "O que fazer". Comece pelos itens A e B — são os que mudam o que sai antes da demo.

---

## A. [CRÍTICO] A receita de comando do M1 não reproduz a acurácia que o projeto reporta

**O que o original diz.** M1 manda rodar, sem mais nenhuma flag:
```bash
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado            # LOSO
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado --final    # checkpoint de entrega
```

**O que foi verificado.**
- Os *defaults* de `treinar.py` (linhas ~454-470) são `--epocas 30 --lr 1e-4 --batch 32
  --agendador nenhum`, sem `--inicializar`.
- `docs/decisao-arquitetura-modelo.md` (linhas ~130-167) documenta que essa MESMA arquitetura,
  com esses MESMOS defaults, deu **44,6%** de LOSO — diagnosticado no próprio documento como
  subtreino (acerta 87,5% num sinal e 2,5% em outro, padrão típico de rede que não teve passos
  suficientes).
- Os 94,6%/94,9% que aparecem como referência no README exigiram explicitamente
  `--epocas 120 --lr 1e-3 --batch 32 --agendador cosseno` (decisao-arquitetura-modelo.md:163).
- As execuções mais recentes (96,6% de média LOSO, confirmadas em duas sementes pareadas nesta
  semana) usaram `--epocas 120 --lr 1e-3 --batch 64 --agendador cosseno --inicializar
  <backbone_gcn.pt>` (backbone pré-treinado contrastivo em V-LIBRASIL+MALTA). Nenhuma dessas
  flags está na receita do M1.

**Por que importa.** M1 é "sem ele não há reconhecimento no app". Rodar a receita como está
escrita treina com os defaults do `argparse` — o resultado esperado é algo próximo dos 44,6%
subtreinados, não os 94-96% que justificam a escolha do ST-GCN. A receita também não decide
entre duas entregas possíveis, que o original nem menciona como existirem:
- checkpoint "puro MINDS", sem pré-treino: **94,6%/94,9%** (duas sementes, README).
- checkpoint com backbone pré-treinado (V-LIBRASIL+MALTA, contrastivo): **96,6%** (três
  sementes de fine-tuning, duas delas pareadas contra controle sem pré-treino na mesma semente:
  +2,0pp e +1,7pp).

**O que fazer.**
1. Reescrever a receita do M1 com os hiperparâmetros corretos: `--epocas 120 --lr 1e-3 --batch
   64 --agendador cosseno`.
2. Decidir explicitamente se a entrega usa `--inicializar <backbone pré-treinado>` (recomendado,
   dado o ganho pareado e replicado em duas sementes) e documentar essa decisão no M1, não deixar
   implícita.
3. Registrar qual semente de fine-tuning usar para o `--final` — o modo `--final` treina com
   TODAS as pessoas do MINDS (sem holdout); a acurácia que o justifica é a média LOSO das rodadas
   anteriores, não uma medição direta do checkpoint final. Isso é esperado e aceitável em ML,
   mas vale deixar explícito no M1 que o "--final" não é, ele mesmo, testado por LOSO.

---

## B. [ALTO] M9 já tem evidência de risco nos dados existentes, mas está por último na fila

**O que o original diz.** M9 propõe medir recall e confusões dos 6 sinais do roteiro da demo
(FILHO, VACINA, VONTADE, CINCO, MEDO, BANHEIRO) a partir da `matriz_confusao.npy` do checkpoint
de entrega — e prioriza isso como o último item "antes da demo".

**O que foi verificado**, nos relatórios de duas execuções reais recentes (mesma arquitetura e
representação, com backbone pré-treinado):
- Semente 20260917: **filho 75,0%** (30/40) — um dos piores sinais dos 20. A confusão nº1 de
  todo o relatório é `filho→medo` (6 ocorrências) e `medo→filho` (4). `medo` sozinho: 90,0%.
- Semente 20260918 (execução que por engano não usou WLASL, mas é malta/vlibrasil/minds puro):
  **filho 80,0%** (32/40), de novo com `filho→medo` como confusão nº1 (5 ocorrências).

**Por que importa.** FILHO e MEDO estão na mesma frase do roteiro da demo, e já são o par de
confusão mais frequente do relatório inteiro em duas execuções independentes — não é hipótese
do M9, já está medido. Se persistir no checkpoint de entrega, a correção (trocar uma palavra do
roteiro) tem lead time. Deixar essa checagem por último no cronograma é arriscado.

**O que fazer.** Rodar a checagem de recall/confusão de FILHO/MEDO/BANHEIRO (e dos outros 3 do
roteiro) em paralelo ao M1, usando os checkpoints já existentes — não esperar o checkpoint final
de entrega para descobrir isso.

---

## C. [MÉDIO] O README está mais desatualizado do que o original relata

**O que o original diz.** Na seção "Documentação desatualizada no treino", cita duas passagens
do `computer-vision-model/treino/README.md` como anteriores à PR #12: "Exportação 3D não
suportada" e o trecho sobre não haver reamostragem/imputação embutidas no grafo.

**O que foi verificado.** As duas passagens citadas continuam lá (linhas ~280-289) — confirmado.
Mas há uma alegação mais grave, não citada pelo original, algumas linhas acima (~223-226): o
README ainda afirma que *"o ST-GCN não tem export, e isso é o principal bloqueio do projeto"* e
que *"`exportar.py` só constrói o grafo da cabeça Skeleton-DML"*. Isso é falso: `exportar.py`
suporta `--arquitetura gcn` completo, e `cabeca_gcn.py` (`CabecaGCN`/`ClassificadorGCN`)
implementa ossos, z, imputação e reamostragem dentro do grafo — entregue pela PR #12
(`2bdef1e`, `feat/export-gcn-tflite`).

**Por que importa.** Quem ler só essa parte do README concluiria que o export do GCN nem existe
— um mal-entendido mais caro do que os dois detalhes que o original pede para corrigir.

**O que fazer.** Corrigir as três passagens juntas: a alegação de "sem export" (~223-226) e as
duas que o original já apontou (~280-289).

---

## D. [MÉDIO] M3 (fps) conecta com uma decisão pendente que já afeta o backbone atual

**O que o original diz.** M3: "A taxa de quadros dos vídeos de treino (MINDS, V-LIBRASIL, MALTA)
não está documentada."

**O que foi verificado.** A medição já foi feita (nota de trabalho de 12/09, um dia antes do
documento original): MINDS 30fps consistente; V-LIBRASIL ~30fps com ~20% a 60fps; **MALTA
12fps em 78% dos clipes**; WLASL misto (24/25/30). Não está em nenhum `docs/*.md` do repo —
só em anotação — então a frase "não documentada" é defensável, mas o passo 1 do M3 ("medir e
registrar") já está feito, não precisa ser refeito do zero.

Mais importante: `gcn.py` (`para_sequencia`) e `cabeca_gcn.py` (`Reamostragem`) reamostram por
**índice de frame**, não por tempo real. Isso foi identificado e uma correção foi decidida em
12/09 ("reamostrar por tempo real antes de habilitar MALTA no pré-treino"), mas **nunca
implementada**. Resultado: o backbone pré-treinado que produziu os 96,6% (misturando MALTA a
12fps com MINDS/V-LIBRASIL a ~30fps) já carrega essa distorção. O original discute fps só como
risco futuro do lado do app (câmera dos óculos a 24fps) — não menciona que o mesmo problema já
está dentro do checkpoint mais promissor disponível hoje.

**O que fazer.** Ao decidir o checkpoint de entrega (item A), registrar esse caveat: o ganho de
+1,7/+2,0pp do pré-treino foi medido com essa distorção presente nos dois lados da comparação
(controle e candidato), então o ganho relativo provavelmente se sustenta — mas a acurácia
absoluta de qualquer um dos dois pode estar subestimada ou distorcida de um jeito não medido até
a normalização por tempo real ser implementada.

---

## E. Confirmado sem necessidade de correção

Verificado linha a linha contra o código, sem divergência:
- **M6:** `PoC/src/extract.py` usa só `mp.solutions.holistic.Holistic`; nenhuma referência à
  API Tasks (`pose_landmarker`/`hand_landmarker`) no treino.
- **M7:** `treinar.py` só salva `predicoes`/`verdadeiros`, nunca logits; `exportar.py` não tem
  chave `calibracao` no sidecar; nenhum código de temperature scaling/ECE existe em `treino/`.
- **M10:** `representacao.py` (`_rotacionar`) só manipula x,y — rotação estritamente no plano,
  σ=12°. Nenhuma rotação 3D existe.
- **M11:** `exportar.py` só tem quantização `nenhuma`/`float16`/`dinamica` (int8 só nos pesos);
  nenhuma medição de acurácia com quantização real do GCN existe.
- **Guarda de metadados do M1:** `exportar.py` (`_config_cabeca_gcn`) recusa exportar se faltar
  qualquer uma de `com_z, z_recentrado, ossos, movimento, sem_imputacao` no checkpoint —
  confirmado.
- **Modelo ainda não existe no repo:** `models/` só tem `.gitkeep`; confirma a premissa do M1.

---

## F. [BAIXO] "maca"/"maçã" — a conclusão está certa, a frase é imprecisa

**O que o original diz.** "O rótulo `maca` não existe no léxico da contextualização (lá é
`maçã`)... esse sinal nunca será falado."

**O que foi verificado.** Em `mobile-app-companion/.../lexico-glosas.json`, a entrada canônica
é `"maçã": {"formas": ["maçã", "maca", "maçãs"]}` — `"maca"` **existe** no arquivo, só não como
chave de nível superior. `LexicoGlosas.kt` expõe as chaves (`"maçã"`, com cedilha) e
`TemplateGlossContextualizer.kt` busca por essa chave diretamente. Se o rótulo bruto do
classificador for `"maca"` (como está nos relatórios, um dos 20 sinais do MINDS), a busca por
chave falha mesmo com `"maca"` presente no arquivo — a conclusão prática do original (esse sinal
não é falado) está certa, mas a frase "não existe no léxico" não é exata.

**O que fazer.** Ao mexer nisso, não adicionar uma entrada `"maca"` duplicada — ela já existe
como sinônimo dentro de `"maçã"`. Se for necessário que o classificador aponte para essa chave,
mapear `"maca" → "maçã"` no lookup, não no arquivo de léxico.

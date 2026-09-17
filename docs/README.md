# Documentação do Libras Livre

Este diretório guarda o raciocínio do projeto: por que cada decisão foi tomada,
com que evidência, e o que ainda está em aberto. Os **READMEs** de cada pasta
dizem *como rodar*; os documentos daqui dizem *por quê*.

**Se você está chegando agora, leia [`CONTEXTO.md`](CONTEXTO.md) primeiro.** Ele é
o único documento mantido como resumo vivo do estado do projeto.

---

## Contexto e visão

| Documento | Assunto | Natureza |
|---|---|---|
| [`CONTEXTO.md`](CONTEXTO.md) | estado atual, números medidos, armadilhas já encontradas | **vivo** |
| [`libras-livre-arquitetura.md`](libras-livre-arquitetura.md) | arquitetura de produto, visão longa, privacidade e consentimento | vivo |

## Decisões

Registram uma escolha, a evidência que a sustenta e o que ela custa. Quando uma
decisão é revista, a retratação é escrita **dentro** do próprio documento — o
histórico fica visível em vez de ser apagado.

| Documento | Decide | Data |
|---|---|---|
| [`decisao-arquitetura-modelo.md`](decisao-arquitetura-modelo.md) | ST-GCN como modelo de entrega, em vez de ResNet-18 | 2026-09-11 |
| [`decisao-datasets-e-licencas.md`](decisao-datasets-e-licencas.md) | quais datasets usamos e sob qual enquadramento legal | 2026-09-11 |
| [`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md) | vocabulário de atendimento em 3 camadas | rascunho, aguarda consultor |

## Protocolos

O procedimento a seguir, com as garantias que ele precisa preservar.

| Documento | Assunto |
|---|---|
| [`protocolo-treinamento.md`](protocolo-treinamento.md) | treino ponta a ponta: o que rodar, em que ordem, com quais garantias |
| [`protocolo-pretreino.md`](protocolo-pretreino.md) | isolamento e proveniência do corpus de pré-treino |

## Planos de implementação

Escritos antes do código. Cada um tem um estado de implementação.

| Documento | Estado |
|---|---|
| [consentimento por atendimento](consentimento-por-atendimento-plano.md) | **implementado e testado, mergeado na `dev`** (PR #23) — texto do consentimento é placeholder não revisado; decisões em aberto (avatar indisponível no momento do consentimento, timeout, registro de auditoria) seguem sem resolução do time |
| [confirmação de reconhecimento pro surdo + modo economia de bateria](confirmacao-e-modo-economia-plano.md) | **implementado e testado, mergeado na `dev`** (PR #22) — feedback da banca de 2026-09-15; sem teste em hardware real (os eventos de bateria não têm como ser forçados sem óculos reais ou um mock que os simule) |
| [integração privada do modelo no app](integracao-modelo-app-plano-2026-09-15.md) | **mergeado na `dev`** (PR #21); dentro dele, **vídeo, tela completa, ciclo de sessões e caminho RECUSADO validados** (só SIMULADO/RECUSADO fabricado; o `.tflite` treinado real e os vídeos MINDS só existem na máquina do Walisson/Kaggle, não nesta) — vídeo real e hardware seguem pendentes; [evidências do item 1](integracao-video-infraestrutura-2026-09-15.md), [tela completa, reabertura e acesso ao modelo](integracao-tela-completa-e-reabertura-2026-09-16.md) |
| [`libras-livre-poc-plano.md`](libras-livre-poc-plano.md) | **concluído** — PoC executada; resultado em `computer-vision-model/PoC/results/relatorio.md` |
| [`extracao-landmarks-plano.md`](extracao-landmarks-plano.md) | **implementado** — `libras/reconhecimento/LandmarkNormalizer.kt`, `HandGapImputer.kt`, com testes de paridade |
| [`sign-boundary-detector-plano.md`](sign-boundary-detector-plano.md) | **implementado, não calibrado** — `libras/reconhecimento/SignBoundaryDetector.kt` roda com os valores sugeridos pelo plano |
| [`orquestracao-dialogo-audio-plano.md`](orquestracao-dialogo-audio-plano.md) | **implementado em grande parte** — `libras/dialogo/` e `libras/audio/`; wake word real pendente de treino dos modelos pt-BR |
| [`contextualizacao-glosa-seq2seq-plano.md`](contextualizacao-glosa-seq2seq-plano.md) | **implementado** — `contextualization-model/` e `libras/contextualizacao/` |
| [`contextualizacao-implementacao.md`](contextualizacao-implementacao.md) | runbook do anterior; **executado** até o `.tflite` integrado |
| [`poc-tres-coordenadas.md`](poc-tres-coordenadas.md) | **respondido** — o z ajuda (+2,1 pontos); incorporado à configuração de entrega |
| [`vlibras-webview-plano.md`](vlibras-webview-plano.md) | **implementado até a tela** — avatar VLibras em WebView, fechando o estado ⑦ (Fases 1, 4 e 5). Faltam o espelho offline do dicionário (3.5) e a medição em aparelho ARM |
| [`PLANO-CORRECOES.md`](PLANO-CORRECOES.md) | plano de correções de 2026-09-09, em duas frentes |

## Demo do hackathon (16/09/2026)

| Documento | Assunto |
|---|---|
| [`guia-tecnico-completo-hackathon-2026-09-17.md`](guia-tecnico-completo-hackathon-2026-09-17.md) | leitura única antes de apresentar: como cada trilha funciona (visão, contextualização, wake word, app), checklist operacional e como coletar métricas |
| [`prontidao-demo/`](prontidao-demo/README.md) | **plano de implementação**: decisões do time ponto a ponto, prioridades (P0/P1/P2) e ondas |
| [`guia-de-testes-mock-e-oculos.md`](guia-de-testes-mock-e-oculos.md) | como testar o app com o `MockDeviceKit` e com os óculos em mãos; ensaio geral |
| [`modelo-visao-pontos-de-teste.md`](modelo-visao-pontos-de-teste.md) | o que testar no ST-GCN antes de entregá-lo ao app |
| [`riscos-demo-2026-09-13.md`](riscos-demo-2026-09-13.md) | o diagnóstico que originou o plano (fotografia datada) |

## Investigações e relatórios datados

Fotografias de um momento. Não são atualizados — são consultados pelo que mediram.

| Documento | Assunto | Data |
|---|---|---|
| [`investigacao-expansao-dataset.md`](investigacao-expansao-dataset.md) | estado da arte e opções de expansão do dataset | 2026-09-09 |
| [`auditoria-pretreino-2026-09-10.md`](auditoria-pretreino-2026-09-10.md) | regularização e auditoria do corpus de pré-treino | 2026-09-10 |

Relatórios gerados por execução ficam junto do código que os produziu, não aqui:
`computer-vision-model/PoC/results/`, `computer-vision-model/treino/resultados-*/`
e `contextualization-model/resultados-v*/`.

---

## Como ler os números deste projeto

Quase todo erro caro aqui veio de **aceitar um número sem perguntar o que ele
mede**. Três exemplos reais, todos documentados:

- Os 44,6% do ST-GCN pareciam veredito de arquitetura e eram orçamento de treino.
- Os 93,4% da ResNet parecem precisos e têm ±1,7 ponto de variância.
- A validação em 0,2% parecia fracasso do pré-treino e era a tarefa sendo
  impossível (1.353 classes com 3 clipes cada).

Antes de concluir a partir de um número: *o que exatamente foi medido, com qual
orçamento, e quanto ele varia se eu rodar de novo?*

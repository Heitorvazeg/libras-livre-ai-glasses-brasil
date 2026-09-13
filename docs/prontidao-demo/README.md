# Prontidão da demo: plano de implementação

**Data:** 2026-09-13 · **Prazo do hackathon:** 16/09/2026 · **Branch:** `feat/prontidao-demo`

Este diretório transforma o [mapa de riscos da demo](../riscos-demo-2026-09-13.md) em
trabalho: cada arquivo é um ponto do mapa, com as **decisões tomadas pelo time** subponto
a subponto (2026-09-13), o que muda no código, como testar e quando está pronto.

O mapa de riscos continua sendo a **fotografia do diagnóstico**. Onde uma decisão aqui
diverge da recomendação de lá, **vale o que está aqui** (lista no fim deste arquivo).

Documentos irmãos, escritos na mesma sessão:

| Documento | Para quem |
|---|---|
| [`../modelo-visao-pontos-de-teste.md`](../modelo-visao-pontos-de-teste.md) | quem vai mexer no modelo de visão (ST-GCN) |
| [`../guia-de-testes-mock-e-oculos.md`](../guia-de-testes-mock-e-oculos.md) | quem vai testar o app, com mock e com os óculos |

---

## Os pontos

| # | Arquivo | Assunto | Defeito da revisão |
|---|---|---|---|
| 1 | [`01-segmentacao.md`](01-segmentacao.md) | onde cada sinal começa e termina | — |
| 2 | [`02-classificador.md`](02-classificador.md) | contrato de entrada do `.tflite`, confiança, roteiro | — |
| 3 | [`03-captura-e-landmarks.md`](03-captura-e-landmarks.md) | câmera dos óculos até os landmarks | **A** (corrida do "iniciar") |
| 4 | [`04-turnos-wake-word-e-botoes.md`](04-turnos-wake-word-e-botoes.md) | fluxo automático, wake word, botão principal | B (resolvido na PR #19) |
| 5 | [`05-audio.md`](05-audio.md) | saída de voz e microfone da resposta | — |
| 6 | [`06-latencia.md`](06-latencia.md) | contextualização, aquecimento, medição | **C** (EOS e timeout) |
| 7 | [`07-bateria-e-temperatura.md`](07-bateria-e-temperatura.md) | tela ligada, economia, temperatura | — |
| 8 | [`08-memoria.md`](08-memoria.md) | pressão de memória e motores sob demanda | — |
| 9 | [`09-avatar.md`](09-avatar.md) | tetos do ⑦, tela do avatar, rede | **D** (⑦ trava) |
| 10 | [`10-tela.md`](10-tela.md) | painel de conversa, faixa de estado, menu de debug | **E** (resultado invisível) |
| 11 | [`11-operacao-de-palco.md`](11-operacao-de-palco.md) | checklists, papéis, fallback em palco | — |

Os subpontos mantêm a numeração da discussão (1.1, 1.2, …) para rastrear cada decisão.

## Prioridades

| Marca | Significa |
|---|---|
| **P0** | sem isto a demo quebra ou mente |
| **P1** | deixa a demo robusta e observável |
| **P2** | opcional; só com tempo sobrando, e só depois de todos os P0/P1 |
| **Teste** | não é código: vai para o [guia de testes](../guia-de-testes-mock-e-oculos.md) |

## Ordem de implementação (ondas)

A ordem respeita dependências: primeiro o que quebra a demo e não depende do modelo; depois
a infraestrutura de medição, que o primeiro teste com os óculos precisa; por último o que
torna o fluxo automático e robusto.

| Onda | Conteúdo | Itens | Depende de |
|---|---|---|---|
| **1. Defeitos que quebram** | corrida do "iniciar", contextualização lenta, ⑦ que trava, wake word no ⑤, microfone padrão, tela ligada, painel de conversa | 3.1 · 6.1 · 6.2 · 9.1 · 4.4 · 5.2 · 7.1 · 10.1 | — |
| **2. Segmentação, contrato e medição** | detector corrigido com valores iniciais; contrato do classificador; gravador CSV e painel de métricas | 1.1–1.6 · 1.8 · 1.9 · 2.1–2.4 · 2.6 · 2.7 · 3.8 · 6.5 | onda 1 (3.1) |
| **3. Fluxo do atendimento** | confiança por frase com "repita"; avanços automáticos; botão principal; roteiro | 2.5 · 2.8 · 2.9 · 4.1 · 4.3 · 4.7 · 5.3 · 9.2 | onda 2 (1.1, 1.5, 2.6) |
| **4. Robustez e visibilidade** | aquecimento com diagnóstico; fallbacks; memória; estados de câmera; faixa de estado; configurações de demo | 1.8 (edição) · 1.10 (script) · 1.11 · 3.2 · 3.4–3.6 · 4.5 · 4.6 · 5.1 · 5.2 (seletor) · 5.4–5.6 · 6.3 · 6.4 · 7.3 · 8.1 · 8.3 · 9.5 · 10.2–10.5 · 10.6 | ondas 1–3 |
| **5. Opcionais (P2)** | GPU, lista fechada do Vosk, economia de bateria, fps do stream, avatar sob demanda, avatar offline, esqueleto no preview, decodificador de hardware | 1.10 (busca em grade) · 3.8b · 3.9 · 5.7 · 7.2 · 7.5 · 8.2 · 9.4 · 10.5b | tudo acima |

**Um ponto de atenção sobre o 10.6 (configurações de demo):** vários seletores (5.1, 5.2,
4.5…) moram nele. Nas ondas 1–3 eles entram como **constantes com o padrão decidido**. As
exceções são o gravador (1.9) e o painel de métricas (3.8), que o primeiro teste com os óculos
precisa, e o modo do placeholder (2.6), que os testes do fluxo no mock precisam. A onda 2 cria o
armazenamento mínimo (`ConfiguracoesDemo`) só com esses três. A onda 4 completa o menu, sem mudar
nenhum padrão.

**O modelo de visão ainda não existe no app.** Nada acima espera por ele: o contrato (2.x) é
implementado e testado com o export `--smoke` (pesos aleatórios, mesmo formato), e o fluxo
de confiança (2.8) com o placeholder em modos de confiança. Quando o checkpoint chegar, a
troca é o arquivo `.tflite` + sidecar, validados pelos testes do 2.6/2.7.

## Convenções dos arquivos

Cada subponto traz:

- **Decisão** — o que o time decidiu, em uma frase.
- **Mudança** — o que muda e onde (caminhos abreviados abaixo).
- **Teste** — automatizado (unitário na JVM ou instrumentado) e/ou verificação manual.
- **Pronto quando** — critério objetivo de aceite.

Caminhos abreviados, relativos a `mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/`:
`libras/`, `camera/`, `ui/`, `stream/`.

## Onde este plano diverge do mapa de riscos

| Tema | O mapa recomendava | Decidido |
|---|---|---|
| Stream entre turnos (§1.1 do mapa) | manter ligado o atendimento inteiro | **liga/desliga por estado**, como hoje (3.3) |
| Imputação (§4.1) | decidir um lado só | **o app imputa** na linha do tempo real e reamostra para 96; a imputação do grafo fica ociosa (2.2/2.3) |
| Rótulo `maca` (§4.2) | tabela rótulo → glosa | **glosa fora do léxico não é falada**; frase vazia conta como "não entendi" (2.5) |
| Saída de voz (§7.1) | alto-falante do celular na demo | **seletor**, padrão **óculos** (5.1) |
| Microfone da resposta (§7.2) | celular na demo | **seletor**, padrão **celular** (5.2) |
| Burst de IA (§9.3) | opcional | **esperar os dados** do painel (7.6) |
| Bug `ACONTECER RUIM` → "Aconteceu." | — | **fora** das correções (2.9) |

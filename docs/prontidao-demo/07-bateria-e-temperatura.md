# 7. Bateria e temperatura

O stream já liga e desliga por estado (3.3), então os óculos gastam câmera principalmente
durante a captura. Aqui ficam o que muda no app e o que só o teste responde.

Diagnóstico: [mapa de riscos §9](../riscos-demo-2026-09-13.md#9-bateria-e-temperatura).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 7.1 | Tela ligada com sessão ativa com os óculos; confirmar no teste | P0 · Teste |
| 7.2 | Detectar o modo economia de bateria e avisar na tela | P2 |
| 7.3 | Folga térmica no painel e aviso em estado térmico sério; ensaio longo | P1 · Teste |
| 7.4 | Bateria dos óculos: causa na tela (3.4); nível no painel se o SDK expuser; minutos de captura | Teste |
| 7.5 | Seletor de qualidade e fps do stream no menu de debug; experimento de fps no modelo | P2 · documento do modelo |
| 7.6 | Processar menos quando ninguém sinaliza ("burst de IA") | aguardar dados do painel |

---

## 7.1 Tela ligada

**Decisão.** Sem isso, o avatar e o painel somem quando a tela apaga no meio da demo. O
serviço em primeiro plano mantém o stream e o microfone, mas não a tela.

**Mudança.** Em `ui/CameraScreen.kt`, `Modifier.keepScreenOn()` (ou
`FLAG_KEEP_SCREEN_ON` na janela) ativo enquanto houver sessão com os óculos
(`CameraUiState.hasSession`). Sem sessão, o comportamento normal de apagar volta.

**Teste (manual).** Tempo de tela do aparelho em 30 s, sessão ativa por 2 min sem tocar: a tela
continua ligada. Encerrar a sessão: a tela apaga no tempo configurado.

**Como ficou a onda 1.** `View.keepScreenOn` na view do Compose, por um `DisposableEffect` chaveado em
`hasSession`. Isso equivale à `FLAG_KEEP_SCREEN_ON` e vale só enquanto a tela está visível.
**Automatizado:** o `FluxoOnda1Test` confere o pedido na view e, no `dumpsys window`, que o
`mHoldScreenWindow` é a `MainActivity` com a sessão ativa. Sem sessão, o pedido some. O teste
manual de 2 min continua valendo no aparelho da demo.

## 7.2 Modo economia de bateria (P2)

**Decisão.** Avisar, porque o modo economia reduz o desempenho da CPU.

**Mudança.** Ler `PowerManager.isPowerSaveMode` no aquecimento e ao voltar para o app. Ligado:
aviso informativo na faixa de estado ("economia de bateria ligada: desempenho reduzido") e uma
linha no painel. As medidas de operação estão no [ponto 11](11-operacao-de-palco.md).

## 7.3 Temperatura

**Decisão.** Registrar e avisar; o comportamento real sai do ensaio.

**Mudança.**
- `Metricas` (3.8) amostra por segundo `PowerManager.getThermalHeadroom(10)` e
  `currentThermalStatus`.
- Estado `THERMAL_STATUS_SEVERE` ou pior: aviso na faixa de estado ("celular muito quente:
  desempenho reduzido").

**Teste.** Ensaio de 15 a 20 minutos seguidos, com o painel ligado: temperatura, fps processado
e latência ao longo do tempo ([guia de testes](../guia-de-testes-mock-e-oculos.md)). A
temperatura estável leva minutos para aparecer.

## 7.4 Bateria dos óculos

**Decisão.** Não é código além do que já está decidido.

- A mensagem de causa quando a câmera não sobe, citando bateria e temperatura, é o
  [3.4](03-captura-e-landmarks.md#34-causa-de-falha-da-câmera-na-tela).
- **A verificar:** se o SDK dos óculos expõe o nível de bateria. Se sim, ele entra no painel
  (3.8).
- **Teste:** quantos minutos de captura a bateria aguenta, com o padrão de uso do roteiro.

## 7.5 Qualidade e fps do stream (P2)

**Decisão.** A decisão de baixar fps depende do modelo, e o experimento está no
[documento do modelo de visão](../modelo-visao-pontos-de-teste.md). No app fica só a
capacidade de medir o ganho de bateria e CPU.

**Mudança.**
- Seletor "Stream: qualidade (baixa/média/alta) e fps (12/15/24)" nas configurações de demo.
- `camera/CameraViewModel.beginStream` lê o seletor em vez das constantes
  `VideoQuality.MEDIUM` e `FRAME_RATE = 24`. Vale a partir do próximo "iniciar".

**Por que isso não quebra o contrato:** a reamostragem pelo tempo (2.2) aceita qualquer fps. Os
parâmetros do detector já são em milissegundos (1.2).

## 7.6 Burst de IA

Aguardar os dados. Os três níveis ficam registrados como opção, **só se** o painel mostrar que
o aparelho não aguenta o regime normal:

1. mãos em repouso → rodar só a pose, sem a detecção de mãos;
2. detector em "parado" → processar 1 frame a cada 2;
3. GPU (já é o [3.9](03-captura-e-landmarks.md#39-mediapipe-em-cpu-ou-gpu-p2)).

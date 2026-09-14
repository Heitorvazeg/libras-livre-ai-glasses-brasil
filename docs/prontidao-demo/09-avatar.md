# 9. Avatar

A resposta do atendente em Libras, pelo player do VLibras numa WebView. Inclui o **defeito D** da
revisão. O carregamento fora da captura já está no [6.3](06-latencia.md#63-avatar-fora-da-hora-da-captura).

Diagnóstico: [mapa de riscos §8](../riscos-demo-2026-09-13.md#8-avatar-vlibras).

## Decisões

| # | Decisão | Prioridade |
|---|---|---|
| 9.1 | Tetos curtos para tradução e animação, prazo total do ⑦, "Pular" | P0 |
| 9.2 | Botão principal dentro da tela do avatar; "Fechar" só esconde | P1 |
| 9.3 | Cache aquecido, 4G próprio, legenda como piso | operação (ponto 11) |
| 9.4 | Avatar offline só para os sinais do roteiro | P2 (depois dos essenciais) |
| 9.5 | Avatar que falhou recarrega sozinho no próximo "iniciar" | P1 |
| 9.6 | Avatar num celular ARM | Teste |

---

## 9.1 O ⑦ não prende a conversa (defeito D)

**Decisão.** Hoje o ⑦ pode segurar a conversa por até ~105 s (tradução 30 s + 30 s, animação 45 s),
com os botões desabilitados.

**Mudança.**

| Teto | Hoje | Novo | Onde |
|---|---|---|---|
| tradução (conexão e leitura) | 30 s + 30 s | **5 s** no total | `libras/avatar/VLibrasGlosaTranslator.kt` |
| animação | 45 s fixos | **3 s + 1,5 s por sinal** da glosa | `camera/CameraViewModel.playAvatar` |
| ⑦ inteiro | — | **prazo total** = teto da tradução + teto da animação + 2 s | `CameraViewModel.playAvatar` |

- Os três tetos ficam configuráveis (10.6).
- Estourar qualquer um: a **legenda** fica na tela, o fluxo volta ao ① e o CSV registra qual teto
  estourou.
- Durante o ⑦, o botão principal é **"Pular"** (4.7): cancela a espera, para a animação
  (`window.avatarStop`) e segue o fluxo.

**Teste (JVM).** O cálculo dos tetos a partir da glosa. **Manual:**
- modo avião com cache vazio: o ⑦ termina em ≤ ~7 s com a legenda;
- "Pular" no meio da animação volta ao ① na hora.

**Como ficou a onda 1.**
- `libras/avatar/TetosAvatar.kt` calcula os tetos e define `DesfechoAvatar` (animou, pulado, avatar
  indisponível, sem glosa, teto da animação, teto total). O desfecho vai para o log e para o
  painel de conversa (10.1); o CSV entra com o gravador (1.9, onda 2).
- O teto de 5 s da tradução mora no `VLibrasGlosaTranslator`. No teto, a espera é abandonada sem
  aguardar o socket, e a conexão é derrubada.
- **"Pular"** já existe na onda 1, dentro da tela do avatar e só durante o ⑦. O botão principal
  do 4.7 (onda 3) passa a ser o lugar dele.
- **Testes automatizados:** `TetosAvatarTest` e um caso novo no `VLibrasGlosaTranslatorTest`
  (servidor que aceita a conexão e não responde: `null` em menos de 1 s com teto de 300 ms).
- **Pendente:** os dois testes manuais acima precisam de uma transcrição com texto para chegar ao
  ⑦, o que o emulador sem fala não produz. Ficam para o teste com voz (guia, A7 e A8).

## 9.2 A tela do avatar não esconde os controles

**Decisão.** O atendente começa o próximo turno sem fechar nada, e fechar não destrói o Unity.

**Mudança.** `ui/AvatarScreen.kt` e `ui/CameraScreen.kt`:
- o **botão principal** (4.7) também aparece dentro da tela do avatar: "Pular" enquanto anima,
  "Iniciar" depois;
- **"Fechar" só esconde** a tela (`avatarVisivel = false`), **sem** chamar `release()`;
- o avatar só é liberado por inatividade do atendimento (60 s, como hoje) ou por pressão de
  memória (8.1);
- tocar "Iniciar" dentro da tela do avatar esconde a tela e começa a captura.

**Teste (manual).** Duas respostas seguidas no mesmo atendimento: a segunda anima sem a carga de
6 a 9 s; "Fechar" e reabrir pelo botão do avatar não recarrega.

**Como ficou a onda 3.** `CameraViewModel.fecharAvatar` só esconde a tela; `release()` fica para o
atendimento ocioso (e, na onda 4, a pressão de memória). A tela do avatar recebe o botão principal
(tag `botao_principal_avatar`): "Pular" no ⑦ e "Iniciar" depois, que esconde a tela e começa a
captura (`iniciarPeloAvatar`). O "Pular" solto da onda 1 foi substituído por ele. "Cancelar
atendimento" também pula a animação e esconde o avatar sem destruir. O teste manual (A9) segue
pendente: precisa de duas respostas transcritas.

## 9.3 Rede

Operação ([ponto 11](11-operacao-de-palco.md)):
- aquecer o cache no local com as respostas do roteiro (2.9);
- 4G próprio como reserva;
- ensaiar o caminho da legenda.

## 9.4 Avatar offline para o roteiro (P2)

**Decisão.** Depois de todos os itens essenciais.

**Ideia.** O player busca cada sinal em
`https://dicionario2.vlibras.gov.br/2018.3.1/WEBGL/BR/<SINAL>.bundle` (~22 KB por sinal), e o
`AvatarPlayer` já intercepta os pedidos da WebView (`shouldInterceptRequest`). Servir de
`assets/vlibras/dic/` **só os sinais das 4 respostas do roteiro** faz a demo funcionar sem rede.

**Mudança.**
1. Descobrir quais arquivos cada resposta pede: gravar os pedidos interceptados numa execução com
   rede.
2. O `download-assets.sh` baixa essa lista para `assets/vlibras/dic/` (externo: **não** entra no
   git, a pasta já está no `.gitignore`).
3. O `AvatarPlayer` responde do asset quando o arquivo existe e vai à rede quando não existe.
4. O `GlosaCache` recebe as traduções das 4 respostas pré-carregadas.

**Pronto quando** as 4 respostas do roteiro animam em modo avião.

## 9.5 Nova tentativa automática

**Decisão.** Se o processo do Unity morreu (memória, crash), o avatar não fica "falhou" até alguém
tocar "Tentar de novo".

**Mudança.**
- No `DialogOrchestrator.beginSignSession`, se o `AvatarState` for `FALHOU` ou `OCIOSO` (liberado
  por memória) **e** o seletor do 8.2 for "pré-carregar", chamar `prepare()` em segundo plano.
- Não tentar se o último motivo foi falta de memória e ela ainda estiver abaixo do limiar do 8.1.
- A legenda cobre enquanto isso.

**Mudança de apoio ao teste.** Uma ação "Simular queda do avatar" nas configurações de demo,
que carrega `chrome://crash` na WebView. É a forma suportada de derrubar o processo do renderer
e disparar o `onRenderProcessGone`; `adb shell kill` não alcança esse processo sem root.

**Teste (manual).** Simular a queda e tocar "Iniciar": o avatar volta a carregar sem ação do
operador.

## 9.6 Avatar em ARM

Teste ([guia de testes](../guia-de-testes-mock-e-oculos.md)):
- abre?
- anima?
- quanto tempo leva para ficar pronto?
- quanta memória usa no aparelho real?

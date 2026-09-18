# 11. Operação de palco

O que não é código: preparação, papéis e o que fazer quando algo falha diante da banca. Os
procedimentos de teste que alimentam estas listas estão no
[guia de testes](../guia-de-testes-mock-e-oculos.md).

Diagnóstico: [mapa de riscos §13](../riscos-demo-2026-09-13.md#13-operação-da-demo-o-que-não-é-código).

## Decisões

| # | Decisão |
|---|---|
| 11.1 | APK da demo preparado e aquecido antes do dia |
| 11.2 | Versões congeladas e permissões concedidas na semana da demo |
| 11.3 | Energia e interrupções sob controle |
| 11.4 | Palco montado para o enquadramento e o protocolo de sinalização |
| 11.5 | Rede própria em todo atendimento (o cache do avatar não se aquece) |
| 11.6 | Três papéis: quem sinaliza, atendente, narrador |
| 11.7 | O que fazer em palco quando cada coisa falha, ensaiado |
| 11.8 | Vídeo de plano B gravado no ensaio geral |
| 11.9 | Ensaio geral no D-1 |

---

## Checklist de véspera (D-1)

**11.1 APK**
- [ ] APK **debug** gerado numa máquina com todos os assets (a tarefa `verificarAssets` falha
      se faltar algo). Build debug porque é nele que existem o menu de debug e as configurações
      de demo (10.6).
- [ ] APK instalado no aparelho da demo **com antecedência**; cópia no notebook.
- [ ] Primeira execução completa: aquecimento todo em ✓ (6.4). A cópia de ~70 MB do Vosk e do
      Piper acontece aqui (5.6).
- [ ] Configurações de demo conferidas: microfone no celular, voz nos óculos, motor de wake
      word escolhido no teste, gravador e painel conforme o plano do dia.

**11.2 Versões e permissões**
- [ ] Atualizações automáticas **desligadas**: app Meta AI, firmware dos óculos, Android e
      **Android System WebView** (uma atualização do WebView pode mudar o WebGL do avatar).
- [ ] *Developer Mode* ativo no app Meta AI; app registrado.
- [ ] Permissão de câmera dos óculos e de microfone **já concedidas**.

**11.3 Energia**
- [ ] Óculos com carga cheia; estojo carregador por perto.
- [ ] Celular carregado; power bank ou tomada no palco.
- [ ] Economia de bateria **desligada**; o app fora da otimização de bateria.

**11.5 Rede**
- [ ] 4G próprio (roteador ou hotspot de **outro** celular) testado.

**11.8 e 11.9 Ensaio**
- [ ] Ensaio geral de 15 a 20 min seguidos, com o painel de métricas ligado.
- [ ] Cada linha da tabela de falhas (11.7) ensaiada pelo menos uma vez.
- [ ] Vídeo de plano B gravado: tela do celular (`adb shell screenrecord`) **e** câmera externa
      mostrando a pessoa sinalizando e o atendente.

## Checklist do dia

**11.3 Interrupções**
- [ ] Celular em **não perturbe**.
- [ ] "Hey Meta" e leitura de notificações nos óculos **desligados** (hipótese de interferência
      no áudio; confirmar no teste com os óculos).

**11.5 Rede**
- [ ] ~~Cache do avatar aquecido no local~~ — não existe mais (9.3): o cache de glosa dura só
      um atendimento e é apagado no fim dele. Cada atendimento da demo traduz as respostas na
      rede na primeira vez; conferir a rede (ou o 4G reserva) antes de cada um. Se o 9.4 foi
      feito, conferir as 4 em modo avião.

**11.4 Palco**
- [ ] Marca no chão na distância medida no teste com os óculos (3.5).
- [ ] Fundo sem pessoas; luz frontal sobre quem sinaliza.
- [ ] Quem usa os óculos **não toca na haste** (3.2) e mantém as mãos fora do campo da câmera
      (3.6).
- [ ] Quem sinaliza faz o **repouso natural entre os sinais** (1.7).

**11.1 App**
- [ ] App aberto **antes** de subir ao palco, aquecimento em ✓, sessão com os óculos iniciada.

## 11.6 Papéis

| Papel | Faz |
|---|---|
| **Quem sinaliza** | as 4 sequências do roteiro (2.9), com repouso entre os sinais |
| **Atendente (com os óculos)** | responde com as falas do roteiro; usa o botão principal ou a tecla de volume quando o automático não avança |
| **Narrador** | explica à banca o que acontece nas esperas (stream subindo, pausa de 2,5 s, síntese, avatar); conduz a recuperação quando algo falha |

Uma espera narrada vira explicação da arquitetura, não silêncio.

## 11.7 Quando algo falha em palco

| Se falhar… | O app faz | A equipe faz |
|---|---|---|
| Wake word | nada | botão principal ou tecla de volume |
| Sinal não reconhecido | aviso ao atendente para pedir repetição; na 3ª, "tente outro meio" (2.8) | pede para repetir com pausa entre os sinais |
| Contextualização lenta ou barrada | template (6.2) | nada; é invisível |
| Câmera não sobe ou pausada | mensagem com a causa (3.2, 3.4) | resolve a causa; em último caso, vídeo gravado no `MockDeviceKit` (**ensaiar a troca**) |
| Avatar ou rede | legenda; botão "Pular" (9.1) | narrador: "o avatar usa a rede do VLibras; offline é o próximo passo" |
| Voz do Piper | voz de reserva do Android (5.5) | nada; muda só o timbre |
| Celular muito quente | aviso (7.3) | pausa curta narrada; tirar da capinha / da luz direta |
| Tudo | — | vídeo de plano B (11.8) |

# Etapa 6 — taxa de sucesso por frase: protocolo

**17/09/2026. Escrito antes de olhar o resultado.** O app decide **por frase**,
não por sinal: se qualquer sinal ficar abaixo do corte de confiança, a frase
inteira vira "não entendi" (`AvaliadorDeFrase`). Sabemos o acerto por sinal
(96,6% em LOSO no MINDS), mas **ninguém mediu a taxa por frase** com o modelo de
entrega. É esse número que diz se a demo funciona.

## O que é medido

As quatro sequências do roteiro, como estão no app
(`PlaceholderSignClassifier.ROTEIRO`):

1. FILHO VACINA VONTADE
2. CINCO
3. FILHO MEDO
4. BANHEIRO VONTADE

Para cada pessoa do MINDS e cada sequência, todas as combinações das 5
repetições de cada sinal (125 tentativas numa frase de 3 sinais). Cada tentativa
cai em um de três desfechos, na regra do app:

- **falada certa:** todos os sinais acima do corte **e** todos corretos;
- **pede repetição:** algum sinal abaixo do corte;
- **falada errada:** todos acima do corte, mas algum sinal errado — o pior caso,
  porque o app afirma com confiança algo que a pessoa não disse.

## Dados

Uma execução LOSO completa com `--salvar-evidencias` no commit `47615c3`,
receita de entrega (backbone `7a6e997c…`, semente 20260917, 120 épocas), sem
extras e sem augmentação: kernel `etapa3-loso-evid`, experimento
`loso-s20260917-evid-v1`. Cada rodada guarda os logits do teste, que são de uma
pessoa que o modelo **não viu**. É o melhor cenário plausível: mesma condição de
gravação do treino.

## Como ler (fixado agora)

Não há aqui decisão de adotar ou não adotar nada — é medição. Referências para
interpretar:

- **corte 0,60** é o padrão do app hoje, e é o número que vale para a demo como
  está;
- **corte 0,00** mostra o teto: quanto a frase acerta se o app nunca rejeitar;
- a curva de cortes mostra a troca real: subir o corte reduz "falada errada" e
  aumenta "pede repetição".
- O resultado **não** decide o corte da demo. Se levar a mexer no corte, isso
  vira decisão do usuário, registrada à parte.

## Limites conhecidos antes de rodar

- Mede o domínio MINDS. Fora dele o acerto por sinal cai para ~50%, então a taxa
  por frase real tende a ser **pior** que esta.
- As combinações de repetições não são independentes entre si (a mesma repetição
  aparece em várias tentativas), então as porcentagens descrevem o conjunto, não
  são amostra aleatória de frases futuras.
- A segmentação do app (achar começo e fim de cada sinal em vídeo contínuo) não
  entra aqui: a simulação parte de clipes já recortados.
- Palavras fora das 20 continuam sendo um risco à parte, já medido no
  [teste de rejeição](rejeicao-fora-do-vocabulario-final-s20260917-v1-2026-09-15.md).

## Resultado — 17/09/2026

### Proveniência

Kernel `walissonfagundes/etapa3-loso-evid` (versão 2), concluído às 20:10 UTC.
Commit `47615c3e0b15867ccc2c4601ba12d163443f0fc3`, backbone
`7a6e997c5830139162b32bc9b37a48e6eb5b8d6222836da87cb95e94ecf6baf5`, selftest
"tudo OK", 8 rodadas com `--salvar-evidencias`, sem extras e sem augmentação.
Média LOSO **96,62%** (773/800) — idêntica à referência de 15/09, como esperado
(mesma receita, mesma semente).

Backup privado em `experimentos-privados/loso-s20260917-evid-v1/`; simulação em
`frases.json`. Nada disso vai para o Git.

### Taxa por frase (1440 tentativas: 8 pessoas × 4 frases × todas as combinações de repetições)

| corte | falada certa | pede repetição | **falada errada** |
|------:|-------------:|---------------:|------------------:|
| 0,00 | 78,5% | 0,0% | 21,5% |
| 0,50 | 78,5% | 8,8% | 12,7% |
| **0,60** | **78,5%** | **13,2%** | **8,3%** |
| 0,70 | 76,0% | 17,9% | 6,0% |
| 0,80 | 68,1% | 31,9% | **0,0%** |
| 0,90 | 58,2% | 41,8% | 0,0% |
| 0,95 | 42,4% | 57,6% | 0,0% |

No corte do app hoje (0,60): **4 em cada 5 frases saem certas, 1 em cada 8 pede
repetição e 1 em cada 12 sai errada com confiança**. O teto (corte 0) é 78,5%:
subir o corte de 0 para 0,60 elimina 13 pontos de frase errada sem custar
nenhuma frase certa — a rejeição só descarta tentativas que já estavam erradas.
A partir de 0,80 a frase errada zera, ao preço de 10 pontos de frase certa
virando pedido de repetição.

### Por frase, no corte 0,60

| frase | certa | repetição | errada | n |
|---|---:|---:|---:|---:|
| CINCO | 100,0% | 0,0% | 0,0% | 40 |
| BANHEIRO VONTADE | 95,0% | 5,0% | 0,0% | 200 |
| FILHO MEDO | 75,0% | 15,0% | 10,0% | 200 |
| FILHO VACINA VONTADE | 75,0% | 15,0% | 10,0% | 1000 |

### Por pessoa, no corte 0,60

| pessoa | certa | repetição | errada |
|---|---:|---:|---:|
| M01, M02, M05, M06, M08 | 100,0% | 0,0% | 0,0% |
| M12 | 94,4% | 5,6% | 0,0% |
| M10 | 16,7% | 83,3% | 0,0% |
| M11 | 16,7% | 16,7% | **66,7%** |

### O que o número diz

A média esconde a forma real da falha: **cinco das oito pessoas acertam 100% das
frases** e todo o prejuízo vem de duas. E vem de um único sinal.

**FILHO é o ponto fraco, e o roteiro depende dele.** Acerto por sinal entre os
seis do roteiro: vacina, vontade e cinco 100%; banheiro 95%; medo 90%;
**filho 75%** (30/40). FILHO↔MEDO é o par mais confundido do modelo inteiro —
10 dos 27 erros em 800 clipes (filho→medo 6, medo→filho 4). Para M10 e M11,
FILHO erra nas 5 repetições.

Isso explica cada linha das tabelas acima: FILHO aparece em duas das quatro
frases, e a frase 3 é justamente **FILHO MEDO** — os dois sinais que o modelo
troca entre si. CINCO e BANHEIRO VONTADE, que não usam FILHO, vão a 100% e 95%.

**Os dois modos de falha são diferentes em gravidade.** M10 erra FILHO com
confiança baixa (0,37–0,59), então o corte de 0,60 converte quase tudo em pedido
de repetição — ruim, mas honesto. M11 erra FILHO com confiança **alta**
(0,66–0,79): o app afirma "MEDO" quando a pessoa disse "FILHO". É a única pessoa
com frase errada no corte 0,60, e sozinha responde por quase todo o 8,3%.
Nenhum corte abaixo de 0,80 resolve o caso M11.

### O que o número não diz

- **É o melhor cenário.** Mede o domínio MINDS: mesma câmera, mesmo
  enquadramento, mesmo protocolo de gravação do treino. Em clipes externos o
  acerto por sinal cai para ~50%
  ([diagnóstico](diagnostico-dominio-minds-externo-2026-09-15.md)), então a taxa
  por frase na condição da demo tende a ser bem pior que estes 78,5%.
- **Não é amostra aleatória de frases futuras.** As combinações reaproveitam as
  mesmas repetições, então as porcentagens descrevem este conjunto.
- **Não inclui a segmentação.** A simulação parte de clipes já recortados; achar
  começo e fim de cada sinal em vídeo contínuo é um erro adicional, não medido.
- **Não decide o corte da demo.** Conforme o protocolo, isso é decisão do
  usuário, registrada à parte.

### Leituras possíveis, para decisão do usuário (nada adotado aqui)

1. **Corte.** Subir de 0,60 para 0,80 zera a frase errada e troca 10 pontos de
   frase certa por pedido de repetição. Trocar "o app afirma errado" por "o app
   pede de novo" é uma escolha de produto, não de métrica.
2. **Roteiro.** FILHO carrega sozinho quase toda a falha, e a frase FILHO MEDO
   junta o par mais confundido do modelo. Trocar FILHO por um sinal de 100%
   (vacina, vontade, cinco) mudaria mais a demo do que qualquer ajuste de corte
   testado até aqui.

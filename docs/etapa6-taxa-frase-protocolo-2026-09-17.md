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

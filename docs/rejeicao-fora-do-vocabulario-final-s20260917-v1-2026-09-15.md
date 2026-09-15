# Rejeição fora do vocabulário — checkpoint final s20260917

**15/09/2026.** O modelo classifica 20 sinais. No uso real a pessoa vai
sinalizar outras palavras. Este teste mede se o limiar de confiança do app
impede que essas palavras virem uma glosa errada falada em voz alta.

Nenhum ajuste de pesos, temperatura ou limiar foi feito com estes dados.

## Protocolo (fixado antes de rodar)

- **Checkpoint:** `final-s20260917-v1/modelo_final.pt`
  (`c7851d8a…`), o mesmo da [exportação e calibração](exportacao-e-calibracao-final-s20260917-v1-2026-09-15.md).
- **Fora do vocabulário (OOV):** todos os `rep01` de 58 palavras de atendimento
  da [estratégia de busca](estrategia-busca-atendimento-2026-09-12.md) que já
  estavam extraídos. 245 clipes de 12 pessoas, vindos de `landmarks-malta` (T*)
  e `landmarks-pretreino` (V*). Nenhum descartado por duplicata ou formato.
- **Referência dentro do vocabulário:** os 70 clipes do
  [manifesto de calibração](calibracao-naovista-2026-09-14.md). Mesmos corpora,
  então as duas pontas têm o mesmo domínio de captura.
- **Limiares lidos:** 0,60 (`LIMIAR_PADRAO` do app hoje) e 0,717 (sugerido pela
  calibração de 15/09, que não atingiu o protocolo). A tabela inclui outros
  valores só para mostrar a curva.
- **Temperaturas lidas:** T=1 (o app hoje, sem bloco de calibração) e T=2,82
  (a da calibração de 15/09).
- Mesma sequência de pré-processamento do treino (`recentrar_z` → `imputar_maos`
  → `DatasetSinais` sem augmentação).

Script e saídas privadas em
`experimentos-privados/rejeicao-oov-final-s20260917-v1/` (`teste_rejeicao_oov.py`,
`relatorio.json`, `predicoes_oov.json`, logits).

## Resultado

### Como o app está hoje (T=1, limiar 0,60)

**75,5% das palavras fora do vocabulário seriam faladas como uma glosa errada**
(185 de 245). A confiança mediana de um sinal desconhecido é 0,92. A de um
sinal conhecido é 0,95. 67 clipes OOV passaram de 0,99.

| limiar | OOV falado | in-vocab aceito | acerto dos aceitos | corretos falados |
|---:|---:|---:|---:|---:|
| 0,50 | 88,2% | 88,6% | 50,0% | 44,3% |
| **0,60** | **75,5%** | 78,6% | 54,5% | 42,9% |
| 0,717 | 67,3% | 75,7% | 54,7% | 41,4% |
| 0,80 | 60,8% | 72,9% | 56,9% | 41,4% |
| 0,90 | 51,0% | 55,7% | 66,7% | 37,1% |
| 0,95 | 43,3% | 48,6% | 73,5% | 35,7% |

Separação entre "sinal conhecido e acertado" e "sinal desconhecido" pela
confiança máxima: AUROC 0,70 (0,5 seria sorte pura). Nenhum limiar separa os
dois grupos: subir o limiar corta desconhecidos e acertos quase na mesma
proporção.

### Com a temperatura da calibração de 15/09 (T=2,82)

A temperatura baixa todas as confianças, então menos coisa passa de qualquer
limiar. Não melhora a separação (AUROC 0,66).

| limiar | OOV falado | in-vocab aceito | acerto dos aceitos | corretos falados |
|---:|---:|---:|---:|---:|
| 0,60 | 36,7% | 37,1% | 80,8% | 30,0% |
| 0,717 | 21,6% | 25,7% | 88,9% | 22,9% |
| 0,80 | 12,2% | 18,6% | 92,3% | 17,1% |
| 0,90 | 4,9% | 7,1% | 100,0% | 7,1% |

Mesmo em 0,717, uma em cada cinco palavras desconhecidas ainda seria falada. E
o app só aceitaria 1 em cada 4 sinais conhecidos.

### Para onde vão as palavras desconhecidas (T=1, ≥0,60)

`america` 39, `banheiro` 36, `acontecer` 29, `espelho` 15, `cinco` 10,
`bala` 8, `amarelo` 8, `sapo` 7, `medo` 7, `aproveitar` 6, e outras com menos.

`banheiro` está na frase do roteiro da demo (BANHEIRO VONTADE). Exemplos: `sim`
virou `banheiro` com 1,00 em dois clipes; `nao` virou `banheiro` com 1,00;
`por-favor` virou `medo` com 1,00; `ajudar` virou `vontade` com 1,00.
`america` também era o principal destino dos erros na calibração de 15/09.

Palavras faladas em todos os clipes a 0,60: agora, anos, cansado, dor, entrar,
levar, nervoso, número, onde, por-favor, quando, sair, senha, sentar, entre
outras. Rejeitadas em todos: marcar, perguntar, porque, repetir (1–2 clipes cada).

## O que isso significa

1. **O limiar de confiança não protege contra palavras fora do vocabulário.**
   Com o modelo como está, o softmax dá confiança alta a sinais que ele nunca
   aprendeu. No cenário de atendimento, onde a maior parte do que se sinaliza
   não está nas 20 classes, o app falaria glosas erradas com frequência.
2. **Calibrar a temperatura não resolve.** Ela reduz quantos erros passam, mas
   corta os acertos na mesma medida. O problema é de separação, não de escala.
3. **Isso bate com a queda de acurácia fora do MINDS.** Nos 70 clipes conhecidos
   o acerto é 48,6%. As duas medições apontam o mesmo limite: fora do domínio de
   treino, a confiança do modelo não é informativa.

## Limites deste teste

- **Mesmo domínio de captura dos dois lados (V-LIBRASIL/MALTA), mas não o MINDS
  nem a câmera dos óculos.** No domínio MINDS a separação pode ser outra. Não
  foi medida porque todas as pessoas MINDS estão no treino final.
- **Pessoas expostas ao pré-treino.** As 12 pessoas OOV pertencem aos corpora do
  pré-treino contrastivo, que é supervisionado por rótulo. O modelo pode já ter
  visto essas palavras como negativos. Isso tenderia a favorecer a rejeição, não
  a prejudicar.
- **Rótulos não revisados por especialista.** Uma palavra OOV mal rotulada que
  na verdade seja um dos 20 sinais contaria como falha. Com 245 clipes e 58
  palavras distintas das 20, isso não explica 75%.
- **Decisão por frase no app.** O `AvaliadorDeFrase` rejeita a frase se qualquer
  sinal ficar abaixo do limiar. Uma frase com vários sinais desconhecidos tem
  mais chance de ser rejeitada que um sinal isolado. Não foi simulado.
- `rep01` apenas; variantes numeradas (`nao1`, `nao2`) contam como a mesma palavra.

## Caminhos possíveis (não decididos)

- **Classe explícita de "outro sinal"** no treino, usando as palavras de
  atendimento já extraídas como exemplos negativos. Exige retreino e uma
  avaliação com pessoas separadas.
- **Detector de fora-do-vocabulário** separado do softmax: distância ao
  protótipo de cada classe no espaço do backbone, energia dos logits ou margem
  top-1/top-2. Pode ser testado sobre estes mesmos logits antes de retreinar.
- **Vocabulário do cenário:** treinar as palavras de atendimento como classes.
  Exige dados em volume (5 repetições × várias pessoas por palavra), que hoje
  não existem.

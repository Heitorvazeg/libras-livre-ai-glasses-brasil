# Etapa 3 — verificação de regressão no MINDS (LOSO): protocolo

**15/09/2026. Escrito antes de qualquer run desta verificação.** A candidata da
[etapa 3](etapa3-treino-multidominio-protocolo-2026-09-15.md) passou a barra nos
28 clipes externos (19/28 contra 14/28). Antes de considerar a receita com
extras, conferir se o acerto em pessoas **do MINDS** não caiu.

## Desenho

Duas execuções LOSO completas (8 rodadas), **mesmo commit, mesma semente, mesmo
notebook e mesma GPU** (Kaggle, Tesla T4), rodadas em paralelo:

- **Referência:** receita final sem extras (backbone `7a6e997c…`, semente
  20260917, 120 épocas, lr 1e-3, wd 1e-4, lote 64, cosseno, ossos + xyz
  recentrado, kernel 9; seleção de época pela pessoa de validação, como no LOSO
  existente).
- **Com extras:** a mesma receita + os 42 clipes do grupo `treino_externo` ×5 no
  **treino de cada rodada**. Validação e teste de cada rodada continuam só com
  pessoas do MINDS. Nenhuma pessoa de avaliação externa entra.

As LOSO antigas (96,6% e afins) **não** servem de referência: foram geradas com
outros backbones, commits e ambientes.

Diferenças conhecidas entre os braços, além dos próprios extras:

- a rodada com extras recebe mais atualizações de peso por época (600 + 210
  amostras contra 600 no treino de cada rodada);
- os extras entram no fim da lista de treino, então a augmentação de cada clipe
  MINDS é a mesma nos dois braços (sorteio por índice). Mas a **ordem e a
  composição dos lotes mudam também para os clipes MINDS**, porque a permutação
  do carregador depende do tamanho da lista, e o resto descartado no fim de cada
  época também muda. O ST-GCN usa BatchNorm, sensível à composição do lote. É
  inerente a acrescentar dados; fica registrado como fonte de variação que não
  é causada só pelo conteúdo dos extras.

(Itens acima registrados antes de qualquer resultado, após a revisão
independente da implementação.)

## Critério de regressão (fixado agora)

Comparação pareada, rodada a rodada (mesma pessoa de teste, mesma validação):

- **Regressão** se a acurácia LOSO média com extras ficar **2,0 pp ou mais abaixo**
  da referência, **ou** se qualquer rodada perder **5 pp ou mais** (5 dos 100
  clipes da pessoa de teste).
- Caso contrário: **sem regressão detectada.**
- A variação já observada entre execuções LOSO é da ordem de 1,7 pp; diferenças
  menores que isso não são tratadas como ganho.

## O que o resultado decide

- **Sem regressão:** a receita com extras fica candidata para substituir o final
  atual. Continua sem aprovação de entrega: a evidência de ganho externo é de um
  corpus (V-LIBRASIL), de 28 clipes e de uma semente.
- **Com regressão:** a etapa 3 não é adotada como está.

## Limites conhecidos antes de rodar

- Uma semente por braço.
- LOSO mede generalização entre pessoas do MINDS, não a câmera dos óculos.

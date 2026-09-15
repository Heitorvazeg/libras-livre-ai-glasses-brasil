# Roteiro de gravação para calibrar na condição da demo

**15/09/2026.** Para que serve: hoje só sabemos que o modelo acerta 96,6% dentro
do MINDS (LOSO) e cerca de 50% em pessoas de outros corpora. A condição da demo
está entre esses dois extremos e **não é medida por nada que temos**. O
[levantamento](../experimentos-privados/etapa5-calibracao/levantamento.py)
mostrou que um corte de confiança ajustado num grupo de pessoas não se sustenta
em outro, então calibrar com os corpora existentes não resolve.

Estas gravações servem para duas coisas, nesta ordem de importância:

1. **medir o acerto real na condição da demo**;
2. **calibrar** a temperatura e o corte de confiança nessa condição.

## O que gravar

As **20 palavras** do modelo, todas, mesmo as que não estão na frase da demo:

`acontecer`, `aluno`, `amarelo`, `america`, `aproveitar`, `bala`, `banco`,
`banheiro`, `barulho`, `cinco`, `conhecer`, `espelho`, `esquina`, `filho`,
`maca`, `medo`, `ruim`, `sapo`, `vacina`, `vontade`.

| | pessoas | repetições por palavra | clipes | por pessoa |
|---|---:|---:|---:|---|
| Mínimo | 3 | 2 | 120 | ~5 min |
| **Recomendado** | **4** | **3** | **240** | **~8 min** |
| Confortável | 6 | 5 | 600 | ~15 min |

Pessoas contam mais que repetições: é a variação entre pessoas que derrubou o
corte no levantamento. Com 4 pessoas, duas ajustam e duas conferem.

## Condição de gravação

O valor deste material vem de parecer com a demo. Então:

- **Câmera:** a mesma da demo. Se a demo for com os óculos, gravar com os
  óculos. Se não der, usar o mesmo celular, na mesma posição.
- **Enquadramento:** cabeça e tronco até a cintura, com folga nas laterais para
  as mãos não saírem do quadro. A pessoa de frente para a câmera.
- **Distância e altura:** as da demo; a câmera na altura do peito/rosto.
- **Luz:** ambiente parecido com o da demo, sem contraluz (janela atrás).
- **Fundo e roupa:** fundo liso e roupa de cor diferente da pele ajudam a
  detecção; sem luvas, mangas na altura do cotovelo ou mais curtas.
- **Mesma configuração para todas as pessoas.** Se mudar de câmera no meio,
  anotar; isso vira dois conjuntos diferentes.

## Como executar cada clipe

1. Começar parado, mãos na altura da cintura, por 1 segundo.
2. Fazer o sinal uma vez, em ritmo normal.
3. Voltar à posição inicial e parar por 1 segundo antes de cortar.
4. Cada repetição é um arquivo separado. Clipes de 2 a 4 segundos.
5. Errou? Regravar o arquivo; não deixar duas tentativas no mesmo vídeo.

**Quem sinaliza precisa saber o sinal.** Sem alguém que conheça Libras, o risco
é gravar a execução errada e calibrar em cima de rótulo furado. Como referência,
existem os vídeos originais do MINDS em `computer-vision-model/PoC/data/raw/`
(`pessoaM01_sinal-<palavra>_rep01.mp4`, e assim por diante): dá para assistir
antes de gravar cada palavra. Isso reduz o risco, mas não substitui revisão de
quem é fluente.

## Nomes dos arquivos

Uma pasta por pessoa, e cada arquivo assim:

```
pessoaD01_sinal-filho_rep01.mp4
pessoaD01_sinal-filho_rep02.mp4
pessoaD02_sinal-banheiro_rep01.mp4
```

- `D` de demo, seguido de dois dígitos por pessoa (`D01`, `D02`, …). Não reusar
  `M`, `V`, `T` ou `W`, que já identificam MINDS, V-LIBRASIL, MALTA e WLASL.
- A palavra exatamente como na lista acima, sem acento e em minúsculas.
- `rep01`, `rep02`, … por repetição.
- `.mp4`. Não renomear depois de extrair os landmarks.

## O que acontece depois que você me entregar os vídeos

1. Extraio os landmarks com o mesmo `extract.py` do resto do projeto e comparo a
   taxa de detecção de mãos com a dos outros corpora.
2. Monto um manifesto com o hash de cada arquivo e **fixo a divisão por pessoa
   antes de qualquer medição**: quem ajusta e quem confere.
3. Meço o acerto nessa condição, com o checkpoint de entrega atual.
4. Só então, com esse número na mão, você escolhe as metas de acerto e cobertura,
   e aí rodo a calibração formal (protocolo → inferir → ajustar).

Os vídeos e os landmarks ficam em área privada, fora do Git, como o resto do
material com rosto de pessoas. Vale combinar antes com quem for gravar o uso do
material e onde ele fica guardado.

## Limites conhecidos

- Poucas pessoas: mede a condição da demo, não a população de usuários.
- Se a demo mudar de câmera, sala ou distância depois, a calibração feita aqui
  deixa de valer.
- Isso não corrige o modelo confundir palavras que não conhece: qualquer sinal
  fora das 20 continua virando uma das 20.

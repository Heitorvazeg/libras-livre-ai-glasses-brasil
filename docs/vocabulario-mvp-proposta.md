# Vocabulário do MVP — proposta em 3 camadas

**Status:** rascunho para a conversa com a Associação de Surdos de Goiânia (contato em
andamento, ver mensagem do time de 2026-09-05). Não é lista final — vocabulário errado
invalida a coleta, então nada aqui deve ser gravado antes de um consultor/pessoa surda
confirmar o sinal certo (e a variante regional, quando houver mais de uma).

**Decisão do time (2026-09-08):** priorizar vocabulário que já vem de mesclar bases
públicas, minimizando dependência de coleta própria — que é lenta e depende do consultor.
Isso funciona bem, mas com uma ressalva medida que muda como o vocabulário deve ser
apresentado: nem todo "vocabulário mesclado" tem a mesma confiança de generalização.

**Cenário de atendimento:** ainda em aberto (posto de saúde vs. atendimento
institucional genérico) — por isso a lista evita termos presos a um domínio específico.

**Fora de escopo** (`docs/libras-livre-arquitetura.md`): sinais que dependem de
expressão facial/marcação não-manual para fazer sentido.

## Por que 3 camadas, não uma lista só

Cruzando a lista de atendimento com o que existe nas bases públicas
(`docs/investigacao-expansao-dataset.md`), apareceu uma diferença qualitativa que não dá
pra esconder numa lista única:

- **MINDS-Libras**: cada palavra tem 8-12 **pessoas diferentes** — dá pra medir
  generalização de verdade (leave-one-signer-out com significância real).
- **V-LIBRASIL**: 1.363 palavras, mas os "3 articuladores" são **sempre as mesmas 3
  pessoas em toda a base** (intérpretes profissionais) — não é 3 pessoas novas por
  palavra, é o mesmo trio repetido 1.363 vezes. Serve para vocabulário, mas para essas
  palavras "funciona com pessoa nova" **não está testado** — só sabemos que funciona
  para esses 3 intérpretes específicos.
- **Nada encontrado**: termos institucionais (`marcar`, `atendimento`, `senha`,
  `protocolo`, `esquina`, `idade`, numerais 2-5) não existem em nenhuma base pública com
  mais de 1 pessoa. Mesclar não resolve isso — só coleta própria.

Apresentar essas três como "o vocabulário" sem diferenciar seria uma alegação que não se
sustenta (ex.: declarar 0,93 de acurácia signer-independent e demonstrar com uma palavra
que só tem 3 pessoas conhecidas no mundo gravadas).

## Camada 1 — núcleo validável (MINDS-Libras, 20 sinais)

8-12 sinalizadores cada (pendência: confirmar acesso aos 12 completos, ver
`investigacao-expansao-dataset.md`). Esta é a camada onde dá pra afirmar acurácia
signer-independent com confiança estatística real.

```
acontecer  aluno   amarelo   america   aproveitar
bala       banco   banheiro  barulho   cinco
conhecer   espelho esquina   filho     maçã (maca)
medo       ruim    sapo      vacina    vontade
```

Dos 20, batem direto com atendimento: `banheiro`, `ruim` (descrever estado/sintoma),
`medo`, `filho`, `cinco` (número), `vacina` (se posto de saúde), `esquina` (indicar
direção). Os outros entram como vocabulário geral de demonstração, não como frase de
atendimento.

## Camada 2 — ampliação por V-LIBRASIL (confiança não testada além de 3 pessoas)

Cruzamento feito contra o índice real da base (`remote_zip`, sem baixar vídeo). Achados
exatos + variantes de grafia comuns:

```
obrigado   por-favor  sim        não        esperar
ajuda      ruim*      medo*      voltar     documento
nome       filho*     banheiro*  oi (~olá)  manhã (~bom-dia)
noite      doloroso (~dor)       número
```
`*` já está na Camada 1 — a V-LIBRASIL soma pessoas a mais (3) para essas palavras.

**Como usar na demonstração/apresentação:** rotular explicitamente como "vocabulário
estendido" e não misturar com a métrica de acurácia signer-independent da Camada 1. Se
o modelo errar uma palavra desta camada com uma pessoa nova no palco, isso é esperado e
honesto de admitir — a base nunca teve uma 4ª pessoa para essas palavras.

## Camada 3 — exclusiva de coleta própria (mesclar não resolve)

Não encontrado em nenhuma base pública com mais de 1 pessoa:

```
marcar/agendar   atendimento   consulta   senha   protocolo
esquina*         idade/anos    dois   três   quatro   cinco*
```
`*` já coberto pela Camada 1 (`esquina`, `cinco` existem no MINDS) — a lista real
exclusiva é menor: `marcar/agendar`, `atendimento`, `consulta`, `senha`, `protocolo`,
`idade/anos`, `dois`, `três`, `quatro`.

Estes só existem se o time gravar — é o motivo pelo qual a coleta própria (contato com a
Associação de Surdos de Goiânia) continua sendo trabalho necessário, mesmo priorizando
mesclagem: ela cobre o que nenhuma base pública tem, não é substituível.

## Tamanho total hoje, sem gravar nada

**Camada 1 + Camada 2 = ~35-39 sinais** já disponíveis mesclando bases públicas — maior
do que uma lista construída só por necessidade de atendimento (~28). A Camada 3
(~9 termos exclusivos de atendimento) é o que resta para a coleta própria decidir.

## Perguntas específicas para o consultor

- `marcar/agendar`, `atendimento`, `consulta`, `senha`, `protocolo` — termos
  institucionais têm sinal padronizado ou variam muito por região? Qual usar em
  Goiânia.
- Confirmar se os sinais da Camada 1/2 batem com a variante local, ou se há diferença
  regional que invalide reaproveitar o dado público (mesmo problema já registrado entre
  MINDS e V-LIBRASIL para `maçã`/`medo`/`sapo` — `computer-vision-model/datasets/selecao.yaml`).
- Falta algum sinal essencial de abertura de atendimento que nenhuma das 3 camadas
  cobriu?

## Depois que o cenário de atendimento fechar

Se for posto de saúde: `vacina` (Camada 1) já serve; acrescentar `febre`, `receita`,
`exame` à Camada 3 (coleta própria) e checar se existem em alguma base pública antes de
assumir que precisam ser gravados.

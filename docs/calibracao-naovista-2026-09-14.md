# Conjunto de calibração explicitamente experimental — 2026-09-14

## Atualização 2026-09-15 — manifesto ampliado para 70 clipes

Depois da primeira tentativa real de calibração com os 50 clipes originais
não atingir o protocolo pré-registrado (acc_minima 0,90/cobertura_minima
0,50 — ver [`exportacao-e-calibracao-final-s20260917-v1-2026-09-15.md`](exportacao-e-calibracao-final-s20260917-v1-2026-09-15.md)),
o manifesto foi ampliado: o MALTA já tinha clipes extraídos para as 10
classes que antes só vinham do V-LIBRASIL, e não estavam sendo usados. Sem
download, reextração ou revisão linguística nova — mesmos `.npy` já em
`landmarks-malta/`. **70 clipes, 11 pessoas** (nova: `TUFV`). O manifesto
antigo (50 clipes) e sua `manifesto_sha256` continuam corretos como
referência do resultado de 15/09 já documentado — não foram reescritos
retroativamente; este é um manifesto novo para a próxima tentativa.

## Divisão por pessoa — fixada em 2026-09-15

Registrada em `divisao_pessoas` no manifesto.

| grupo | pessoas | clipes | classes |
|---|---|---:|---|
| **avaliação** | V03, TUFS, T048, T050 | 28 | as 20 |
| **treino externo** | T002, T042, T044, T045, TUFV, V01, V02 | 42 | 17 (sem aluno, aproveitar, banco) |

Regra: a partir de 15/09, as pessoas de avaliação não entram em treino, em
ajuste de temperatura ou limiar, nem em decisões de projeto. As 11 pessoas já
passaram pelos diagnósticos de 15/09 e pelo pré-treino contrastivo, então isto
protege daqui para frente; não é um conjunto limpo. Com 28 clipes, um clipe vale
3,6 pp e a margem de incerteza é da ordem de ±18 pp.

## O que é

Por decisão do usuário, ficam **preservados os clipes já extraídos**, fruto
de dias de trabalho, cobrindo as **20 classes do MINDS** e, após a ampliação
acima, **11 pessoas**: V-LIBRASIL (V01, V02, V03) e MALTA (T002, T042, T044,
T045, T048, T050, TUFS, TUFV). O uso autorizado é **calibração explicitamente
experimental, sem avaliação independente nem aprovação de entrega**. Não
buscar novas pessoas nem refazer o backbone agora. Isso delimita o item 2 da
[ordem vigente](pendencias-entrega-2026-09-14.md), sem resolver os requisitos de entrega.
O [manifesto](../computer-vision-model/treino/calibracao_naovista_manifesto.json)
preserva itens, nomes, hashes e contagens; o nome histórico do arquivo não
significa que suas pessoas sejam completamente não vistas pelo pipeline.

Não requer nenhuma gravação nova, nenhuma reextração: os `.npy` já existiam
nas pastas locais de landmarks de V-LIBRASIL e MALTA.

O manifesto declara `schema: 1`, `finalidade: "calibracao_experimental"`,
`avaliacao_independente: false`, `aprovado_entrega: false` e
`exposicao_previa: "pessoas_expostas_ao_pre_treino_ou_selecao"`.

## Composição (70 clipes, atualizada 2026-09-15)

| pessoa | corpus | clipes | classes |
|---|---|---|---|
| V01, V02, V03 | V-LIBRASIL | 10 cada (30 no total) | acontecer, amarelo, banheiro, barulho, espelho, filho, maca, medo, ruim, sapo |
| T002 | MALTA | 14 | amarelo, america, banheiro, barulho, cinco, conhecer, espelho, esquina, filho, maca, medo, sapo, vacina, vontade |
| TUFS | MALTA | 13 | acontecer, aluno, amarelo, aproveitar, banco, barulho, espelho, esquina, filho, ruim, sapo, vacina, vontade |
| T042 | MALTA | 4 | bala, banheiro, conhecer, medo |
| T048 | MALTA | 4 | america, banheiro, cinco, conhecer |
| TUFV | MALTA | 2 | acontecer, filho |
| T044 | MALTA | 1 | america |
| T045 | MALTA | 1 | cinco |
| T050 | MALTA | 1 | bala |

Gerada a partir do manifesto. Atenção ao desbalanço: **T002 e TUFS somam 27
dos 40 clipes MALTA** — duas pessoas dominam a parte MALTA do conjunto.

O MALTA agora cobre as **20 classes**, não só as 10 complementares ao
V-LIBRASIL — a versão de 14/09 tinha usado só o subconjunto que faltava por
simplicidade; os clipes MALTA das outras 10 classes já existiam extraídos e
não tinham sido incluídos. Nenhuma classe caiu abaixo de 1 pessoa não-MINDS;
as mais rasas continuam `aluno`, `aproveitar`, `banco` (1 pessoa cada, sempre
TUFS) — candidatas naturais a mais diversidade se uma fonte externa (ex.:
YouTube, com revisão linguística e licença resolvidas) entrar no futuro.

## Limites — não é teste independente nem substituto do LOSO

- **1–3 repetições por pessoa/classe** (o LOSO do MINDS usa 5). Serve para uma
  checagem grosseira de calibração/limiar, não para métricas por classe com
  poder estatístico.
- **Corpus diferente do MINDS**: câmera, enquadramento e população de
  sinalizantes distintos. Permite uma análise experimental entre domínios,
  não uma medição independente de generalização nem uma representação da
  distribuição da câmera dos óculos.
- **SupCon é supervisionado por rótulos**: em
  [contrastivo.py](../computer-vision-model/treino/contrastivo.py#L134-L151),
  os rótulos definem os pares positivos da perda. Não é pré-treino “sem rótulo”.
  As demais pessoas pertencem ao conjunto de pré-treino; isso não equivale
  a demonstrar que cada arquivo deste manifesto participou de gradientes.
- **V03 participou da seleção de época por recuperação**, como pessoa
  reservada para validação no pré-treino. A avaliação sem gradientes e a
  escolha dos melhores pesos estão em
  [pretreinar.py](../computer-vision-model/treino/pretreinar.py#L517-L553).
  Logo, V03 não é completamente invisível ao pipeline e seus 10 clipes
  não constituem um subconjunto “100% limpo”.
- **Os 50 hashes conferidos identificam os arquivos**, mas não provam
  independência nem exposição individual de cada arquivo ao pré-treino ou
  à seleção. O aviso de exposição é no nível das pessoas/pipeline, não
  uma auditoria de participação de cada clipe em gradientes.
- **Validação LOSO não entra nos gradientes do próprio fold**, mas participa
  da seleção de época. Seu pool continua sendo análise LOSO, não evidência
  de calibração transferível ao checkpoint final nem teste independente.

## Caminho externo — implementado, sem ajuste real

O módulo [calibracao_externa.py](../computer-vision-model/treino/calibracao_externa.py)
implementa o contrato
é **schema 3, escopo `checkpoint_final_experimental`**, com evidência externa
vinculada ao checkpoint final e **protocolo a priori**: `acc_minima` e
`cobertura_minima` devem ser explícitos e obrigatórios, registrados antes de
examinar os resultados usados no ajuste. Este documento não define valores
para essas metas nem inventa formatos de evidência, protocolo ou comandos.

Temperatura/limiar e suas métricas usarão **os mesmos dados de ajuste**;
mesmo atingir as metas não constitui teste independente ou aprovação de entrega.
A exportação experimental fica limitada a float32 e ao **mesmo hash de checkpoint**
da evidência/calibração. O caminho LOSO de **schema 2 permanece preservado**,
sem autorizar transferência do pool LOSO para o final.

Implementação validada com checkpoints e dados sintéticos; 50 hashes/arrays reais
também conferidos, sem inferência ou ajuste nos clipes reais. O uso depende do
checkpoint final e de metas explícitas. Ver
[procedimento, argumentos e validação](preparacao-final-e-calibracao-experimental-2026-09-14.md).
Não se autoriza treino nesta frente nem se promete novos dados ou retreino.

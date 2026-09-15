# Conjunto de calibração explicitamente experimental — 2026-09-14

## O que é

Por decisão do usuário, ficam **preservados os 50 clipes já extraídos**, fruto
de dias de trabalho, cobrindo as **20 classes do MINDS** e **10 pessoas**:
V-LIBRASIL (V01, V02, V03) e MALTA (T002, T042, T044, T045, T048, T050, TUFS).
O uso autorizado é **calibração explicitamente experimental, sem avaliação
independente nem aprovação de entrega**. Não buscar novas pessoas nem refazer
o backbone agora. Isso delimita o item 2 da
[ordem vigente](pendencias-entrega-2026-09-14.md), sem resolver os requisitos de entrega.
O [manifesto](../computer-vision-model/treino/calibracao_naovista_manifesto.json)
preserva itens, nomes, hashes e contagens; o nome histórico do arquivo não
significa que suas pessoas sejam completamente não vistas pelo pipeline.

Não requer nenhuma gravação nova, nenhuma reextração: os `.npy` já existiam
nas pastas locais de landmarks de V-LIBRASIL e MALTA.

O manifesto declara `schema: 1`, `finalidade: "calibracao_experimental"`,
`avaliacao_independente: false`, `aprovado_entrega: false` e
`exposicao_previa: "pessoas_expostas_ao_pre_treino_ou_selecao"`.

## Composição

| pessoa | corpus | clipes | classes |
|---|---|---|---|
| V01, V02, V03 | V-LIBRASIL | 10 cada (30 no total) | acontecer, amarelo, banheiro, barulho, espelho, filho, maca, medo, ruim, sapo |
| T002 | MALTA | 6 | america, cinco, conhecer, esquina, vacina, vontade |
| TUFS | MALTA | 6 | aluno, aproveitar, banco, esquina, vacina, vontade |
| T042 | MALTA | 2 | bala, conhecer |
| T044, T048 (×2), T045, T050 | MALTA | 1–3 cada | america, cinco, conhecer, bala |

As 10 classes cobertas pelo V-LIBRASIL e as 10 cobertas pelo MALTA são
exatamente complementares — a divisão não foi escolhida, é a interseção real
entre o vocabulário de cada corpus público e as 20 classes do MINDS (o
V-LIBRASIL local tem só essas 10 em comum; as outras 10 simplesmente não
existem nele com esse nome).

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

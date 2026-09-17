# Runbook — concluir a etapa 6 em outra sessão

Para quem pegar esta tarefa depois (outra sessão do Claude Code, outra máquina).
O protocolo está em [`etapa6-taxa-frase-protocolo-2026-09-17.md`](etapa6-taxa-frase-protocolo-2026-09-17.md)
e foi fixado **antes** do resultado: não mude os critérios depois de ver os
números.

## Estado em 17/09/2026

- Branch de trabalho: `feat/treino-modelo-visao` (publicada). Commit do código
  que a run usa: **`47615c3e0b15867ccc2c4601ba12d163443f0fc3`**.
- **A run já foi enviada e está rodando no Kaggle:** kernel privado
  `walissonfagundes/etapa3-loso-evid`, experimento `loso-s20260917-evid-v1`.
  São 8 rodadas LOSO com `--salvar-evidencias`; leva cerca de 1h30.
- Nada mais precisa de GPU. Os passos abaixo só baixam e analisam.

## Pré-requisitos

- Repositório na branch acima (`git fetch && git switch feat/treino-modelo-visao`).
- CLI do Kaggle e credencial do usuário: `computer-vision-model/PoC/.venv311/bin/kaggle`
  usa `~/.kaggle/`. **Em outra máquina isso não existe**; sem a credencial, baixe
  o backup pela interface do Kaggle (aba Output do notebook) e pule para o passo 3.
- Python com numpy/torch: `computer-vision-model/PoC/.venv311/bin/python3`.

## 1. Conferir se a run terminou

```bash
K=computer-vision-model/PoC/.venv311/bin/kaggle
$K kernels status walissonfagundes/etapa3-loso-evid
```

`COMPLETE` segue adiante. `ERROR`/`CANCEL`: baixe o log e leia o fim dele —
```bash
$K kernels output walissonfagundes/etapa3-loso-evid -p /tmp/saida --file-pattern "etapa3-loso-evid.log"
```
Para reenviar do zero (precisa do SHA publicado acima):
```bash
python3 scripts/montar_notebook_loso.py --sha 47615c3e0b15867ccc2c4601ba12d163443f0fc3 \
    --saida /tmp/loso-evid --evidencias
$K kernels push -p /tmp/loso-evid
```

## 2. Baixar e extrair o backup

```bash
E=experimentos-privados
$K kernels output walissonfagundes/etapa3-loso-evid -p /tmp/saida \
    --file-pattern "loso-s20260917-evid-v1.tar.gz"
T=$(find /tmp/saida -name "loso-s20260917-evid-v1.tar.gz" | head -1)
mkdir -p $E/loso-s20260917-evid-v1
tar -xzf "$T" -C $E/loso-s20260917-evid-v1 --strip-components=1
```

`experimentos-privados/` é ignorado pelo Git. **Não commitar backups, logits nem
checkpoints.**

## 3. Conferir a proveniência antes de acreditar no número

```bash
D=experimentos-privados/loso-s20260917-evid-v1
cat $D/inicializacao.json          # commit 47615c3…, backbone 7a6e997c…
tail -1 $D/selftest.log            # "tudo OK"
python3 -c "import json;d=json.load(open('$D/loso-resumo.json'));print(d['media'], len(d['rodadas']), d['evidencias'])"
ls $D/loso/rodadas/artefatos | head  # .evidencias.json por rodada
```

Esperado: 8 rodadas, `evidencias: true`, e média LOSO perto de **96,62%**, que é
a referência medida em 15/09 no mesmo commit e receita
([protocolo da etapa 3](etapa3-verificacao-loso-protocolo-2026-09-15.md)).
Diferença grande aí significa que algo mudou — investigue antes de seguir.

## 4. Rodar a simulação

```bash
computer-vision-model/PoC/.venv311/bin/python3 scripts/simular_frase_roteiro.py \
    experimentos-privados/loso-s20260917-evid-v1/loso \
    --cortes 0.0,0.5,0.6,0.7,0.8,0.9,0.95 \
    --json experimentos-privados/loso-s20260917-evid-v1/frases.json
```

Saída: por corte, a fração de frases **faladas certas**, **pedidos de repetição**
e **faladas erradas**; e o detalhe por frase no corte mais alto. O script recusa
rodar se faltarem evidências, se um clipe não for da pessoa de teste da rodada ou
se o rótulo não bater com o nome do arquivo.

Sanidade já verificada em 17/09: rodando sobre o piloto de uma rodada só
(`experimentos-privados/piloto-M01-20260914/loso-M01`), dá 100% de frases certas
no corte 0,60 para M01, que é a pessoa mais fácil.

## 5. Registrar o resultado

Acrescente ao final do protocolo (`docs/etapa6-taxa-frase-protocolo-2026-09-17.md`)
uma seção `## Resultado — <data>` com:

- proveniência (commit, backbone, média LOSO, selftest);
- a tabela por corte, destacando o corte **0,60**, que é o do app hoje;
- a tabela por frase;
- leitura honesta: o que o número diz e o que não diz. Lembre que isso é o
  **melhor cenário** (domínio MINDS); fora dele o acerto por sinal cai para ~50%.

Commit na branch `feat/treino-modelo-visao`, sem publicar nada além disso.

## Contexto que evita retrabalho

- Etapas 2 (augmentação), 3 (clipes externos) e 4 (ensemble de sementes) foram
  medidas e **nenhuma foi adotada**; a receita de entrega segue o checkpoint
  `c7851d8a` (semente 20260917, 120 épocas).
- A calibração está parada esperando gravações na condição da demo
  ([roteiro](roteiro-gravacao-calibracao-demo-2026-09-15.md)). Não tente calibrar
  com os corpora existentes: um corte ajustado num grupo de pessoas não se
  sustentou em outro.
- O Astra cuida da integração com o app, em paralelo; não mexa em
  `mobile-app-companion/` a partir daqui.

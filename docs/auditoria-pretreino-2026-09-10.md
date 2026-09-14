# Regularização e auditoria do corpus real — 2026-09-10

**Escopo:** pendências 1 e 2 da Frente A. Nenhum pré-treino, fine-tuning ou
experimento em GPU foi executado. A execução completa do notebook (A5) continua pendente.

## Resultado

| Etapa | Resultado observado |
|---|---|
| Inventário original | 4.053 vídeos + 4.053 arrays; nenhum sidecar antes da migração |
| Migração | 4.053 sidecars de vídeo + 4.053 de landmarks criados |
| Integridade | SHA-256 de todos os 8.106 arquivos originais idêntico antes/depois |
| Auditoria do original | **Reprovado**: 14 grupos de vídeos idênticos, 28 registros envolvidos |
| Preparação conservadora | Cópia separada com 4.025 arrays/sidecars; todos os originais preservados |
| Auditoria oficial da cópia | **Aprovada**, sem sobreposição detectada por origem ou hashes exatos |
| Filtro de classes do carregador | 4 classes com um clipe descartadas; restam 4.021 clipes / 1.349 classes |

O diretório liberado localmente é
`computer-vision-model/PoC/data/landmarks-pretreino-auditado`.
**O original `landmarks-pretreino` continua com 4.053 arrays e continua reprovando
por duplicatas: regularizar proveniência não significa aprovar automaticamente.**

Na cópia auditada: V01 = 1.339, V02 = 1.341, V03 = 1.345 clipes.
Há 1.323 classes com 3 clipes, 26 com 2 e 4 com 1. As quatro classes abaixo do
mínimo padrão são `altura`, `as-vezes`, `experiencia` e `preso`.
Os 4.025 arrays têm `(T>=3, 57, 3)`, dtype float32 e valores finitos.
Os 4.021 são o conjunto após filtro de classe, **não** o total de âncoras
elegíveis para SupCon após reservar V03; essa seleção pertence ao próximo experimento.

## Evidência da configuração histórica

- Snapshot: [extracao-vlibrasil-2026-09-08.yaml](../computer-vision-model/datasets/configs/extracao-vlibrasil-2026-09-08.yaml).
- Igualdade do conteúdo YAML e da **ordem dos índices de pose** conferida contra
  `bbf7b2b1b7ee483a9b2a9056dc28b6332d3d9505`, commit de 08/09 às 11:22 (-03:00).
- Os 4.053 arrays têm mtime entre 08/09 às 15:29 e 21:03 (-03:00), depois da
  ampliação para 57 pontos. Todos foram inspecionados: 57 pontos × 3, float32.
- O extrator desse commit grava explicitamente três dimensões, independentemente
  de `normalizacao.usar_z: false` (flag de leitura do DTW).
- Os parâmetros do YAML atual ainda são iguais aos históricos; o snapshot
  separado evita que futuras alterações mudem a declaração desta migração.
- Caminhos efetivos de entrada/saída foram `PoC/data/raw-pretreino` e
  `PoC/data/landmarks-pretreino`, não os defaults `raw`/`landmarks` do YAML.

**Limite explícito:** histórico Git, formato e timestamps sustentam uma declaração
de configuração legada; não são um log assinado da execução de 08/09. Não foi
atribuída uma versão histórica de MediaPipe nem alegada reprodução bit a bit da
extração. Todos os sidecars conservam `config_declarada_para_legado: true`.
Tamanho/CRC dos vídeos foram conferidos contra o índice local do bundle, e os
SHA-256 de vídeos e arrays foram calculados sobre os arquivos reais.

## Colisão de nomes na migração

`Avó` e `Avô` geram o slug `avo`. O migrador antigo abortava até quando a colisão
estava fora do subconjunto solicitado. A correção em
[registrar_legado.py](../computer-vision-model/datasets/registrar_legado.py)
resolve candidatos **somente quando tamanho e CRC identificam um único membro**;
zero correspondências ou empate continuam sendo erro.

Nos três arquivos reais `sinal-avo`, o conteúdo correspondeu a
`Avô_Articulador1/2/3.mp4`, nunca escolhido pela ordem do índice.
Não foram alterados rótulos, bytes nem regras da ingestão nova: ela continua
rejeitando colisões de slug antes de baixar.

## Duplicatas reais e política de exclusão

Foram encontrados **14 grupos de hash de vídeo repetido**, nenhum de origem
repetida e nenhum de hash de landmarks repetido. Arrays diferentes não tornam
dois vídeos idênticos independentes; por isso o hash do vídeo é necessário.

| Registros do mesmo vídeo | Tipo de conflito |
|---|---|
| V01/V03 — altura | articulador |
| V01/V03 — as-vezes | articulador |
| V01/V02 — experiencia | articulador |
| V01/V02 — preso | articulador |
| V01 — envergonhado / ficar-com-vergonha | rótulo |
| V01 — espera / espere | rótulo |
| V01 — lagarta / lagarto | rótulo |
| V01 — pessoa-doente / pessoa-famosa | rótulo |
| V01 — segunda-feira / toda-segunda-feira | rótulo |
| V02 — gastar / pagar | rótulo |
| V02 — leve / peso-leve | rótulo |
| V02 — tia / tio | rótulo |
| V03 — barbear / enviar | rótulo |
| V03 — centro / cereal | rótulo |

Sem revisão humana dos rótulos/pessoas, não há justificativa para escolher um
representante arbitrário. **Todos os 28 registros foram excluídos apenas da cópia
auditada**; nenhum foi apagado ou movido do corpus original.
[preparar_corpus_auditado.py](../computer-vision-model/datasets/preparar_corpus_auditado.py)
gera uma cópia em destino novo, sem sobrescrita, e um `preparacao.json` local com
inventário, hashes, grupos, excluídos e mantidos. O manifesto de avaliação não mudou.

## Auditoria executada

Da raiz do repositório, com o ambiente de treino já instalado:

```bash
computer-vision-model/PoC/.venv311/bin/python computer-vision-model/treino/pretreinar.py \
  --corpus computer-vision-model/PoC/data/landmarks-pretreino-auditado \
  --auditar --dispositivo cpu --threads 2
```

Saída final, código de saída 0:

```text
[pretreino] landmarks-pretreino-auditado: 4025 clipes
[pretreino] 4 classe(s) descartada(s) por ter < 2 clipes (4 clipes)
[auditoria] OK: 4025 amostras sem sobreposição; manifesto 2c6afd2044451d7b34db62c649a709ec6a8e6fd476b72231654dea57eaa81a72
```

Além da auditoria oficial, foram comparados hashes dos vídeos de avaliação locais
diretamente contra os vídeos do pré-treino: nenhuma coincidência. Os 30 membros
reservados V-LIBRASIL também não aparecem por origem; os hashes dos 830 arrays
de avaliação foram consultados pela auditoria. Não se trata de detecção de
quase-duplicatas, cortes ou recodificações, nem de prova de domínio/pessoas inéditos.

## Impressões digitais

| Artefato | SHA-256 |
|---|---|
| Configuração histórica (JSON canônico) | `42976424cafb4e13933493b09efda456b9297eeae0a8a4aa80425fde9171824d` |
| Índice local (arquivo) | `39328f4dd72aa2121edeb2386402f3da147ba35b2a533610872f6ba8dfa0ace9` |
| Índice ordenado do bundle | `ff2d687e0e546ce81902567397d9ee503005cef582c487f2c272b11d13033454` |
| Manifesto de avaliação (arquivo) | `3a7d1ea26a3a497d50027dc51b73029a902ab735e3ebb6a18aa8f95d8294ac97` |
| Inventário de bytes dos 8.106 originais, antes/depois | `67aa2cea0e8dab56ece7eacce8088db63698b6b56a08a6544d4a7fb6055f610f` |
| Inventário de entrada do plano | `531b4633079e1b15122da311974d75423e7d95d99eef112ebe1af13a4b0be904` |
| Plano local (arquivo) | `ccfd718a657e92c0f7f05ffab6c3c30eff09124d77b09d45682c2cf9cb5a612b` |
| Corpus aprovado (auditoria oficial) | `2c6afd2044451d7b34db62c649a709ec6a8e6fd476b72231654dea57eaa81a72` |

O inventário de bytes é `hash_json({caminho_relativo_à_PoC: sha256_do_arquivo})`
para todos os vídeos originais e arrays originais, sem sidecars. A igualdade foi
verificada também depois da preparação da cópia.

## Testes e uso posterior

- 4 testes de colisão/migração, 4 de preparação conservadora e 10 regressões
  de proveniência sem treino passaram; suíte de datasets também passou.
- O teste de checkpoint que treina em dados sintéticos foi intencionalmente
  omitido nesta execução. Nenhuma etapa de treinamento foi iniciada.
- Sidecars e dados permanecem **locais e ignorados pelo Git**, conforme a política
  de compartilhamento privado. Um push não transporta os dados para Kaggle.
- Para o notebook GPU futuro, usar **a cópia auditada**, levando arrays e sidecars.
  O pacote privado do notebook ainda exige raiz interna `landmarks-pretreino/`:
  mapear essa raiz para o conteúdo auditado, sem incluir o `preparacao.json`
  junto dos arrays (o carregador aceita apenas arrays/sidecars). Guardar o plano
  separadamente como evidência da preparação. Repetir a auditoria no ambiente alvo.
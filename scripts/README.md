# Coleta de LIBRAS por fontes documentadas

## Validação integrada: paridade e piloto offline

O [plano vigente](../docs/validacao-visao-app-2026-09-14.md) separa paridade
numérica, avaliação held-out e comparação dos detectores.

**Escopo atual:** marco experimental preservado no snapshot `0b194a1`; Android
pausado. O ajuste de política final mudou o hash do código de treino: reproduzir
M01 nesse snapshot/ambiente original, não regravar evidências para aceitar a versão
nova. [Pendências P1–P4](../docs/pendencias-entrega-2026-09-14.md).

[fixture_paridade_classificador.py](fixture_paridade_classificador.py) aceita
`--checkpoint` GCN confiável. Sem ele, continua smoke com pesos aleatórios.
Exemplo a partir da raiz, num ambiente Python com as dependências de treino:

```bash
python scripts/fixture_paridade_classificador.py \
	--checkpoint /caminho/privado/rodadas/artefatos/01-M01.pt \
	--saida experimentos-privados/paridade-M01 --somente-pytorch
```

A saída real deve estar vazia e dentro de `experimentos-privados/` ou fora do
repositório. A fixture registra hash do checkpoint, representação, rótulos e
`entrada_sintetica=true`. O modo `--somente-pytorch` grava referências, mas declara
`tflite_validado=false` e não gera modelo/sidecar TFLite: não serve para execução
Android. Remover a flag exige ambiente compatível com o conversor e runtime do
exportador; a conversão é conferida antes de declarar sucesso.

O teste instrumentado lê o nome do modelo da fixture, em vez de fixar smoke.
Para uma execução Android privada, usar a propriedade Gradle
`-PlibrasLivre.classificadorFixtures=/caminho/absoluto/privado` com o conjunto
completo de fixture, modelo e sidecar. Exige diretório existente dentro da área
privada do workspace ou fora do repositório; caminhos relativos/versionados são
recusados. Substitui os assets de `androidTest` (não mescla com o smoke) e passa
o mesmo JSON ao teste JVM. O gerador não copia nada para assets versionados.
Não publicar pesos, APKs de teste, caches ou relatórios privados.
Esse opt-in exclui outras fixtures de mídia: filtrar para `ParidadeCaminhoAppTest`
no JVM e `ClassificadorSmokeTest` no instrumentado. Sem a propriedade, mantém smoke.
Mesmo com pesos reais, entradas sintéticas não medem LOSO, detector, segmentação,
robustez ao uso dos óculos ou calibração. Margem baixa pode pular a checagem top-1;
a comparação de logits continua obrigatória.

[preparar_piloto_tasks.py](preparar_piloto_tasks.py) é **inventário offline**, não
extrator. Relaciona os vídeos e landmarks de uma pessoa MINDS pelo ID, calcula
SHA-256 e lista ausências/ambiguidades e modelos Tasks. Não baixa, não sobrescreve
dados originais, não carrega checkpoints, não treina e não faz inferência:

```bash
python scripts/preparar_piloto_tasks.py --pessoa M01 \
	--saida experimentos-privados/piloto-M01/inventario.json
```

Pode receber `--videos`, `--landmarks`, `--pose-model` e `--hand-model` explícitos.
Exige saída privada nova e Python 3.11+. Exit 0 significa inventário escrito,
**não piloto liberado**: conferir `bloqueios`, `extracao_executada=false` e
`avaliacao_executada=false`. Presença/hash do modelo não comprovam validade nem
identidade com o asset aprovado no app. O manifesto contém caminhos privados.

Testes: [test_fixture_paridade_classificador.py](test_fixture_paridade_classificador.py)
e [test_preparar_piloto_tasks.py](test_preparar_piloto_tasks.py).

### Extração real 	pareada de um subconjunto

[extrair_piloto_tasks.py](extrair_piloto_tasks.py) executa Tasks e Holistic nos
mesmos frames, a partir de um inventário com modelos presentes e hashes válidos.
Requer NumPy, OpenCV, MediaPipe com `solutions.holistic` **e** Tasks, e ffprobe.
Validado em MediaPipe 0.10.14/Python 3.12.3, sem instalação de novos pacotes.

```bash
python scripts/extrair_piloto_tasks.py --inventario /privado/inventario-com-modelos.json \
	--saida experimentos-privados/piloto-prova --max-clipes 1
```

Sem `--max-clipes`, processa todos os pares do inventário. A saída deve ser
privada e inexistente; não há retomada implícita. Salva NPZ com os dois pipelines,
PTS/índices de todos os frames e máscaras para ausências, mais JSON com hashes,
contrato, cobertura e falhas. Não remove fontes, não imputa arrays, não treina e
não mede acurácia. Exit 0 e `extracao_completa=true` significam somente que todos
os clipes **solicitados** foram extraídos sem falhas (um limite de 1 não prova 100).

Testes: [test_extrair_piloto_tasks.py](test_extrair_piloto_tasks.py).
Insumos fixados, diferenças para Android/legado e execução M01 no
[registro do piloto](../docs/piloto-tasks-holistic-M01-2026-09-14.md).

### Comparação no mesmo checkpoint held-out

[avaliar_piloto_tasks.py](avaliar_piloto_tasks.py) recebe o **marcador de rodada**,
não um backbone ou `--final`. Confere hashes do checkpoint/evidências, pessoas
disjuntas, IDs completos do teste, configuração e identidade de código/ambiente.
Reproduz os logits históricos de validação e teste com tolerância absoluta `1e-5`
e top-1 idêntico antes de avaliar novas extrações. Exige landmarks históricos das
duas pessoas; não recalcula/seleciona a melhor época.

```bash
python scripts/avaliar_piloto_tasks.py \
	--rodada /privado/loso/rodadas/01-M01.json \
	--extracao /privado/extracao-M01 \
	--landmarks /privado/landmarks-historicos \
	--saida experimentos-privados/comparacao-nova
```

São sete braços: histórico/Holistic novo/Tasks no `DatasetSinais` do treino,
mais Holistic novo/Tasks com imputação externa e reamostragem índice ou PTS para
96 frames antes da cabeça do exportador. Índice96 × PTS96 mantém o restante igual;
treino × índice96 combina operações, não isola somente tempo. É simulação Python
em clipes já recortados, **não execução Android/TFLite/segmentação**.

O relatório privado contém logits, matrizes, erros por sinal, concordâncias,
reprodução histórica, segunda imputação ativa e diagnóstico **fixo** do limiar
0,60 com T=1, não calibrado. Nenhum parâmetro é ajustado ao teste. PTS não são
uptime/captura dos óculos; imputação por clipe não testa estado entre segmentos.

Saída nova e privada obrigatória; só publica resultado quando a avaliação toda
termina. Extração incompleta, pessoa incorreta, IDs faltantes, hash divergente,
NPZ com máscaras/timestamps inválidos ou clipe com menos de três frames válidos
interrompem a avaliação em vez de diminuir silenciosamente o denominador. Uma
política futura de abstenção para esses clipes deve ser explícita.

Testes em [test_avaliar_piloto_tasks.py](test_avaliar_piloto_tasks.py). O piloto M01
foi executado nos cem clipes reais, com reprodução exata das referências; os
[resultados](../docs/piloto-tasks-holistic-M01-2026-09-14.md) não substituem LOSO
completo nem validação nos óculos. O comparador não altera o relatório do extrator:
o hash desse relatório é registrado na avaliação separada.

### Conversão e paridade TFLite com os clipes reais

Usar ambiente **isolado**, com versões compatíveis documentadas no
[piloto, seção 9](../docs/piloto-tasks-holistic-M01-2026-09-14.md),
sem alterar o ambiente que produziu as evidências do treino. Gerar fixture com
`--checkpoint` e **sem** `--somente-pytorch` em uma nova pasta privada.
Depois [avaliar_tflite_piloto.py](avaliar_tflite_piloto.py) compara quatro braços
do app (Holistic/Tasks × índice/PTS) em TFLite CPU, PyTorch de exportação e logits
da avaliação anterior:

```bash
python scripts/avaliar_tflite_piloto.py \
	--referencia /privado/avaliacao-pareada-v2/avaliacao.json \
	--checkpoint /privado/loso-M01/rodadas/artefatos/01-M01.pt \
	--extracao /privado/extracao-M01 \
	--fixtures /privado/paridade-float32-v1 \
	--saida experimentos-privados/avaliacao-tflite-nova
```

Exige referência anterior completa/confiável, hashes do código e artefatos,
mesmos IDs do fold, contrato 1×96×57×3 float32 e saída nova privada.
O ambiente de exportação pode diferir: registra versões e mede a diferença
PyTorch–referência separadamente; não burla a identidade do comparador anterior.
Verifica também as três sequências sintéticas da fixture. Tolerância `2e-3`,
já usada pelo exportador float32; top-1 deve ser idêntico. Se houver divergência
numérica, preserva o relatório com `paridade_aprovada=false` e termina com erro.
Falhas de integridade interrompem antes de publicar um relatório de sucesso.

Não executa Android, segmentação ou calibração e não mede latência de produção.
O diagnóstico T=1/limiar 0,60 é mantido, não otimizado. As quatro avaliações
reutilizam os mesmos cem clipes, não representam quatrocentos testes independentes.
Guardas em [test_avaliar_tflite_piloto.py](test_avaliar_tflite_piloto.py).

**M01 executado:** float32 desktop aprovado, 99/100 nos quatro braços, zero
trocas top-1, maior diferença TFLite–referência 5,72205e-6. Gradle aceitou a
configuração privada e recusou caminhos inválidos; o teste JVM do app foi tentado,
mas bloqueado antes da compilação por SDK ausente. Nenhum teste instrumentado
foi executado. Versões, hashes e pendências na seção 9 do
[registro do piloto](../docs/piloto-tasks-holistic-M01-2026-09-14.md).

### M9 — auditoria dos resultados LOSO existentes

[auditar_m9_loso.py](auditar_m9_loso.py) lê os pacotes tar.gz do protocolo
legado V-LIBRASIL+MALTA. Não extrai arquivos, não desserializa PyTorch, não treina
e não executa Android. Requer Python 3.11+ e NumPy. Recebe `--pacotes` (um ou mais)
e `--saida` (JSON novo privado). Confere hashes de backbone/metadados, receita,
oito folds, denominadores, rótulos e matrizes salvas versus predições.

Produz recall/confusões por sinal/pessoa e hashes dos membros. Não inventa IDs
ausentes nos JSON legados, não calibra confiança e não trata clipes isolados como
frases. Duas execuções podem conter os mesmos vídeos: não somar como amostras
independentes nem chamar diferenças de efeito exclusivo da seed.

Execução concluída com os dois pacotes das seeds17/18; diagnóstico/decisão em
[M9](../docs/m9-diagnostico-roteiro-2026-09-14.md). Testes em
[test_auditar_m9_loso.py](test_auditar_m9_loso.py). O roteiro tem quatro turnos,
oito ocorrências e seis sinais únicos; não é uma frase única de seis sinais.

## Fila do consultor: aproveitar a auditoria existente

[preparar_revisao_libras.py](preparar_revisao_libras.py) lê a
[triagem existente](../external-data/libras-gap/triagem.csv) e prepara uma página
local somente com `veredito=candidato`. Não refaz a classificação por duração,
não baixa, não apaga originais, não recorta e não preenche o catálogo de fontes.
Em 12/09/2026: **121 registros → 18 candidatos em 11 palavras**, 103 exclusões
preservadas; os 18 arquivos foram localizados e sondados por ffprobe.

A página gerada fica em **external-data → libras-gap → revisao-consultor →
index.html**. Abra-a no navegador do sistema (não como texto no editor).
Funciona sem servidor e sem dependências externas. Vídeos são lidos das pastas
originais por caminhos relativos; mover somente o HTML quebra esses caminhos.
O link de fonte é a única ação que abre um site externo, mediante clique.

1. Selecione um candidato e assista ao vídeo completo.
2. Registre consultor, decisão linguística, rótulo confirmado, avaliação regional
	e observações. Não inferir sinalizante pelo canal. Marque início/fim quando
	houver um trecho confirmado; a página apenas registra, sem executar recortes.
3. Permissão fica **pendente**, independentemente da aprovação linguística.
	Evidência documentada exige responsável, referência e escopo. Não inserir
	dados confidenciais no formulário ou no arquivo de exportação.
4. Clique **Salvar avaliação local**, depois **Exportar avaliações JSON**.
	Rascunhos usam o armazenamento do navegador quando disponível; em páginas
	locais esse suporte varia. O JSON exportado é o backup portátil necessário.
5. **Importar avaliações** verifica a versão da fila, os IDs e hashes dos vídeos.
	A importação substitui as avaliações locais após confirmação. Não faz merge.

Regenerar relatórios não modifica a triagem nem os vídeos e não grava por cima
de exportações do navegador. Mudanças na triagem ou nos bytes geram outra versão
da fila: revisões antigas não são aplicadas silenciosamente à nova versão.
Arquivos ausentes/ambíguos ficam explícitos; duplicatas de bytes entre candidatos
são sinalizadas, não removidas. Isso não detecta a mesma gravação reencodada.

Os relatórios locais incluem a fila JSON e as exclusões CSV. Os 103 `descartar`
são vereditos herdados da auditoria, não uma nova conclusão do gerador; recuperar
um vídeo médio exige decisão explícita de curadoria, fora da fila inicial.
Os resultados permanecem ignorados pelo Git. `training_ready` é sempre `false`.
Próxima etapa: receber as decisões do consultor, conferir permissões e só então
considerar recortes e integração ao protocolo de treino.

Testes do gerador: descoberta unittest para
[test_preparar_revisao_libras.py](test_preparar_revisao_libras.py). Regras de decisão
e importação: Node.js test runner em [test_revisao_libras.cjs](test_revisao_libras.cjs).

## Coletor de novas fontes (fluxo separado)

[download_libras_gap_videos.py](download_libras_gap_videos.py) **não pesquisa no
YouTube nem deduz rótulos pelo título**. Só aceita clipes cadastrados com
evidências de rótulo, formato e autorização. Sem fonte elegível, registra lacuna.
Uma URL direta do YouTube pode ser usada como transporte, mas passa pelos mesmos
critérios; um canal institucional, sozinho, não comprova nenhum deles.

## Operação

- Execução sem `--download`: plano offline, sem chamadas de rede. A tarefa
	**LIBRAS: validar coleta de lacunas** do VS Code também apenas planeja.
- `--download`: baixa somente entradas elegíveis do catálogo.
- `--catalog`: catálogo JSON; padrão [libras_gap_sources.json](libras_gap_sources.json).
- `--words`: restringe palavras; expressões são um único argumento.
- `--per-word`: meta total de URLs distintas por palavra, padrão **5**. Não é
	garantia de cinco sinalizantes, nem deduplicação de conteúdo entre URLs distintas.
- `--usage-scope`: `research_noncommercial` (padrão) ou `product`. A autorização
	cadastrada precisa cobrir explicitamente o escopo solicitado.
- `--max-attempts`: limite de tentativas por palavra (5); `--max-duration`:
	duração máxima do vídeo (180 segundos), também validada após transferência.
- `--max-results` foi removido: não há busca nem fallback para vídeos incertos.

Python 3.11+ e dependências de [requirements.txt](requirements.txt). Downloads
exigem FFmpeg e ffprobe; Node.js, quando disponível, auxilia o yt-dlp. Aceita
mídia HTTPS direta e páginas de vídeo individual suportadas pelo yt-dlp,
incluindo streams separados até 720p. Não usa cookies, bypass de restrições,
extração de ZIPs nem recorte automático de aulas/frases.

## Cadastro e revisão da fonte

O catálogo contém `schema_version: 1` e uma lista `entries`, com **uma entrada
por clipe isolado**. O catálogo inicial está vazio intencionalmente: não foram
verificadas novas fontes que cumpram todos os critérios. Os acervos já presentes
no projeto não são recadastrados automaticamente e os vídeos antigos de busca
não são promovidos a fontes aprovadas.

Campos obrigatórios por entrada:

| Campos | Conteúdo e critério |
|---|---|
| `id`, `word` | Identificador único e palavra do vocabulário selecionado. |
| `source_name`, `catalog_url` | Acervo responsável e página institucional do registro, em HTTPS. |
| `source_url` | URL HTTPS do clipe individual ou de sua página de download. |
| `source_label`, `label_evidence_url` | Rótulo original e anotação/dicionário que documente sua correspondência a `word`. Não usar título de busca como evidência. |
| `language`, `clip_type`, `performer_type` | Exatamente `Libras`, `isolated_sign`, `human`. Excluir avatares, frases e compilações. |
| `format_evidence_url`, `format_verified` | Protocolo documentado de captura ou revisão do clipe: sinal completo e mãos, rosto e tronco visíveis; `true` após conferência. |
| `label_verified` | `true` após conferir a anotação confiável e o mapeamento do rótulo. Casos ambíguos permanecem pendentes. |
| `license`, `permission_evidence_url`, `conditions` | Licença/autorização, evidência HTTPS e restrições/atribuição. Acesso público ou licença do código do acervo não bastam. |
| `download_permitted`, `training_permitted` | Booleanos `true` somente quando a autorização documentada cobrir esses usos. |
| `allowed_usage_scopes` | Lista de escopos expressamente cobertos. Autorização para pesquisa não implica produto. |
| `review_status`, `reviewed_by`, `reviewed_at` | `approved`, responsável pela revisão e data `YYYY-MM-DD`. Sem revisão, usar `pending`. |

Campos ausentes, pendentes ou permissões incompatíveis bloqueiam a entrada e
aparecem no relatório com os motivos. URLs equivalentes são deduplicadas;
identificadores repetidos ou a mesma mídia atribuída a palavras conflitantes
invalidam o catálogo. Evidências privadas devem ter um registro de revisão
referenciável sem publicar dados pessoais ou documentos confidenciais.

**Limite importante:** o script verifica a presença e coerência dessas
declarações, não a veracidade do conteúdo dos links, nem emite parecer jurídico
ou linguístico. Não marcar `approved` apenas para desbloquear um download.
Priorizar acervos anotados cuja documentação permita revisar a fonte, em vez de
revisar indiscriminadamente centenas de vídeos de busca.

## Resultados e legado

Os novos resultados ficam na subpasta local **curated**, dentro da pasta de
coleta de lacunas. Contêm plano/relatório de lacunas, manifesto JSONL, erros,
mídias e metadados. O manifesto guarda cópia da entrada, escopo, data, SHA-256
dos bytes e impressão digital do cadastro. Retomadas exigem cadastro atual,
hash igual e vídeo tecnicamente válido. Mudança na revisão invalida o reuso.

Os vídeos e o [manifesto CSV anterior](../external-data/libras-gap/manifest.csv)
permanecem preservados e **não contam para a nova meta**. Não há migração
automática. O status novo `downloaded_from_reviewed_source` não significa
aprovação final do corpus; `training_ready` permanece `false`. Integração exige
o protocolo de auditoria do projeto, inclusive duplicatas, sinalizantes e splits.

Códigos de saída: **0** meta atendida no modo escolhido (fontes elegíveis no
plano, arquivos no download); **1** há lacunas; **2** argumentos/catálogo inválidos.
Um plano com meta atendida não indica que arquivos foram baixados.
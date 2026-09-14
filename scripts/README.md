# Coleta de LIBRAS por fontes documentadas

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
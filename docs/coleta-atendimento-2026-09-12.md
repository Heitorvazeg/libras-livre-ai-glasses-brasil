# Coleta de candidatos de atendimento — 12/09/2026

Execução autorizada da [estratégia de atendimento](estrategia-busca-atendimento-2026-09-12.md).
**Resultado: 30 arquivos baixados; 24 candidatos para revisão; seis excluídos
da fila principal por metadados compostos/ambíguos. Nenhum aprovado para treino.**

## Filas locais prontas

- [Lote A — 16 candidatos de comunicação prática](../external-data/libras-gap/candidatos/2026-09-12-lote-a/revisao-consultor/index.html).
- [Lote B — oito candidatos institucionais](../external-data/libras-gap/candidatos/2026-09-12-lote-b/revisao-consultor/index.html).
- [Fila original — 18 candidatos, preservada](../external-data/libras-gap/revisao-consultor/index.html).

Abrir as páginas locais no navegador. Cada lote tem seu próprio identificador
de fila, armazenamento local e exportação JSON. As avaliações de uma fila não
devem ser importadas em outra. Os quatro vídeos médios precisam de avaliação do
trecho; nenhum recorte foi executado. Também os curtos precisam de validação de
rótulo, humano, execução completa e variante regional.

## Cobertura adquirida — rótulos propostos, não confirmados

| Palavra | Candidatos | Até 6 s | Acima de 6 até 20 s |
|---|---:|---:|---:|
| ruim | 2 | 2 | 0 |
| dor | 1 | 1 | 0 |
| precisar | 2 | 2 | 0 |
| mostrar | 2 | 1 | 1 |
| voltar | 2 | 2 | 0 |
| buscar | 1 | 1 | 0 |
| repetir | 1 | 1 | 0 |
| perguntar | 1 | 1 | 0 |
| ajudar | 1 | 1 | 0 |
| esperar | 1 | 1 | 0 |
| documento | 1 | 1 | 0 |
| por favor | 1 | 1 | 0 |
| atendimento | 2 | 1 | 1 |
| consulta | 1 | 0 | 1 |
| protocolo | 2 | 2 | 0 |
| senha | 1 | 1 | 0 |
| agendar | 1 | 0 | 1 |
| marcar | 1 | 1 | 0 |
| **Total** | **24** | **20** | **4** |

Há 24 hashes distintos nos candidatos e 20 canais. Isso **não comprova 20 pessoas
novas**: republicações/reencodificações e vídeos dos mesmos dicionários ainda
precisam ser comparados visualmente entre si e com MALTA/V-LIBRASIL. O inventário
de deduplicação desta execução cobre os vídeos locais da coleta de lacunas,
não toda a mídia bruta dos corpora de treino.

### Pendências linguísticas relevantes

- `esperar/1uJNBCXJqOc`: título “Aguardar - Libras”; termo auxiliar de descoberta,
  não equivalência linguística aprovada.
- `consulta/TnEkemDlY9I`: “Consulta Médica”; confirmar o rótulo e se há composto/recorte.
- `protocolo/24M6vNFmYY0`: sinalário de Arquivologia/UFPA; conferir se corresponde
  ao sentido administrativo necessário no cenário.
- `senha/TCvq2_aEUZM`: o título não distingue senha da fila de senha de acesso.
- `agendar/Q4Voc10sC0s`: “Agendar (Combinar)”; a descrição informa fixar tempo/prazo,
  mas a execução e a adequação regional continuam sem revisão.
- `ruim/HV15pW8bs5M`: o título menciona Ribeirão Preto, não comprova adequação a Goiânia.

## Exclusões novas preservadas

Os arquivos permanecem na quarentena e o manifesto mantém o evento original de
download e a correção posterior de metadados. Não foram promovidos à fila de
sinais simples, nem apagados. Isso não é parecer linguístico definitivo.

| Palavra proposta | ID | Título / motivo |
|---|---|---|
| dor | ZDAGitWXnxQ | “Dor de Cabeça”: composto, não rótulo simples dor |
| buscar | pNZW61Q5dl4 | “Lupa buscar”: possível sentido de interface |
| repetir | PtH1YYKFpB8 | “REPETIR, DE NOVO, OUTRA VEZ”: vários termos, requer revisão separada |
| perguntar | dUo8WE_VErA | “PERGUNTAR-ME”: forma direcional, não fundida automaticamente |
| consulta | TLSgLfNzxkM | “consulta ou curiosidade”: sentido ambíguo |
| senha | ADGTpwpAo1I | “Segredo ou senha”: não confirma senha de atendimento |

## Licenças e limites

Dois candidatos trazem a declaração do YouTube **Creative Commons Attribution
license (reuse allowed)**:

- [Repetir — Sinalário LSB](https://www.youtube.com/watch?v=Z-kI9gNxCgI).
- [Ruim — Sinalário LSB](https://www.youtube.com/watch?v=6yH1JIE3iGs).

Os outros 22 não trazem licença explícita nos metadados coletados. A declaração
foi registrada, não transformada em autorização de treino/produto: conferir
titularidade, atribuição e escopo independentemente do parecer linguístico.
Todos permanecem com `permission_status=pendente` e `training_ready=false`.

Nenhum login, acordo, contorno de restrição, alteração nos corpora, recorte,
extração de landmarks ou treino foi realizado. Não houve expansão ao lote C:
parada deliberada para manter a revisão humana manejável, sem perseguir cinco
downloads por palavra a qualquer custo.

## Implementação e rastreabilidade

O novo [coletor de candidatos](../scripts/coletar_candidatos_atendimento.py) é
separado do [coletor de fontes documentadas](../scripts/download_libras_gap_videos.py).
O catálogo documentado e seus critérios de aprovação não foram alterados.

- Sem flags: plano offline; `--search`: somente metadados; `--download`: busca e
  download; `--batch a` ou `--batch b` escolhe o lote.
- `--refresh`: revisão offline dos metadados salvos e regeneração da fila; não
  busca nem baixa novos vídeos. Mudança de seleção altera o identificador da fila:
  exportar avaliações antes de regenerá-la depois que o consultor começar.
- Três consultas de dez resultados por palavra, quarta contextual quando há
  menos de três candidatos por metadados; cache de pesquisas; IDs globais excluem
  vídeos já triados/baixados. Duração desconhecida exige metadados individuais.
- Limites: até três candidatos por palavra, dois médios por palavra, quatro
  tentativas por palavra e teto de seleção de 20 (A) / 10 (B). As exclusões
  posteriores reduziram a seleção a 16/8; não se abriu nova rodada para repor vagas.
- Downloads parciais não entram como sucesso. Arquivos finais são sondados com
  ffprobe; hashes e proveniência são registrados antes de gerar as filas.
- Títulos são filtros de descoberta, não validação. Casos com barras, vírgulas,
  contrastes ou alternativas são retidos fora da seleção automática. É um filtro
  conservador, sujeito a falsos negativos; os resultados permanecem no log para
  eventual reconsideração explícita.

Cada lote guarda cache de busca, resultados de descoberta, manifesto de eventos,
resumo, CSV de triagem própria e página de revisão. Os caminhos principais são:

- [Resumo A](../external-data/libras-gap/candidatos/2026-09-12-lote-a/summary.json)
  e [manifesto A](../external-data/libras-gap/candidatos/2026-09-12-lote-a/manifest.jsonl).
- [Resumo B](../external-data/libras-gap/candidatos/2026-09-12-lote-b/summary.json)
  e [manifesto B](../external-data/libras-gap/candidatos/2026-09-12-lote-b/manifest.jsonl).

## Verificações executadas

- Nove testes offline do novo coletor e 16 testes do coletor documentado passaram.
- Os 24 candidatos têm mídia presente, hash correspondente, stream de vídeo,
  dimensões positivas e duração real dentro de 20 segundos.
- Filas geradas sem arquivos faltantes ou erros de sondagem.
- Checksums anteriores e posteriores confirmam preservação byte a byte da
  triagem original de 121 linhas e da fila original de 18 candidatos.
- Não houve avaliação visual automatizada nem validação linguística dos 24 vídeos.
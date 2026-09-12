# Déficits de atendimento e estratégia de busca de candidatos

**Data:** 12/09/2026. **Escopo:** levantamento e plano; nenhuma busca/download
novo executado nesta etapa. A lista é proposta de aquisição, não vocabulário
linguístico aprovado nem mudança nas classes do modelo.

## 1. Evidência e interpretação

Foram cruzados:

- [Proposta do MVP](vocabulario-mvp-proposta.md), cujo cenário ainda admite
  atendimento genérico ou saúde;
- [Mapa anterior](mapa-vocabulario-e-sinalizantes.md), que contém uma fotografia
  de 11/09 e não deve substituir a contagem atual;
- nomes dos arquivos `.npy` em `computer-vision-model/PoC/data/landmarks`,
  `landmarks-pretreino-auditado` e `landmarks-malta`;
- [Triagem de 121 registros](../external-data/libras-gap/triagem.csv): 18 candidatos
  originais em 11 palavras, sem tratá-los como novas amostras de treino aprovadas.

Método da medição: reconhecer `pessoa<ID>_sinal-<rótulo>_rep<N>.npy`, agrupar por
corpus e contar IDs distintos e arquivos. Para **prospecção** foi removido o
dígito final do rótulo (`marcar1`, `marcar2` → `marcar`). Essa união é otimista:
variantes/sentidos não são classes intercambiáveis. Não houve leitura dos arrays,
nova auditoria de qualidade dos landmarks ou confirmação visual de identidade.
WLASL/ASL e a quarentena YouTube ficam fora da contagem de cobertura local.

Há ingestão concorrente: resultados são um retrato, não total imutável. Exemplos
que explicam diferenças do mapa anterior: `T045` aparece em `agendar` e `precisar`,
`T044` em `dor` e `TUFS` em `ruim`. Não se inferiu origem nem ineditismo apenas
desses IDs. Recontar antes de iniciar lotes se a ingestão avançar.

**Déficit não é uma coisa só:**

1. ausência de rótulo no acervo medido;
2. poucas execuções/IDs por rótulo;
3. pouca diversidade de pessoas e cenários, ainda que haja vários arquivos;
4. ausência do sentido de atendimento ou da variante local, não detectável por nome.

Não usar ausência por string para concluir que não existe um sinal equivalente.
Não somar candidatos YouTube aos dados auditados, nem tratar canais como pessoas.

## 2. Ausências e baixa cobertura: primeira prioridade

Todos os termos desta tabela têm **zero no MINDS e no V-LIBRASIL reservado/pré**
na correspondência medida. A cobertura indicada é exclusivamente MALTA.
`IDs/clipes` significa identificadores distintos e arquivos, não validação linguística.

| Termo-alvo | IDs/clipes | Função no atendimento / risco de sentido |
|---|---:|---|
| atendimento | 0/0 | Atendimento ao público, não só atendimento médico ou verbo atender |
| consulta | 0/0 | Consulta médica; não consulta a banco de dados ou pesquisa |
| protocolo | 0/0 | Registro/comprovante de solicitação; não protocolo de rede |
| senha | 1/1 | Número da fila; distinguir senha de acesso/segredo |
| marcar | 1/2 | Marcar horário; variantes `marcar1`, `marcar2` não fundidas no treino |
| agendar | 2/2 | Agendamento; não assumir equivalência automática a marcar |
| dor | 2/2 | Sintoma, não discurso sobre lombalgia; dor/doer precisa de decisão |
| voltar | 2/5 | Retornar ao local; quatro rótulos encontrados incluindo variantes |
| precisar | 2/2 | Expressar necessidade; não adjetivo preciso/exato |
| mostrar | 2/3 | Mostrar documento; distinguir apresentação de pessoa |
| buscar | 1/1 | Buscar objeto/documento; não procurar na internet por pressuposto |
| levantar | 1/3 | Levantar-se; não cair, levantar objeto ou sentido emocional |
| perguntar | 2/2 | Pedido de informação |
| querer | 2/2 | Expressar desejo; busca pode usar “quero”, sem nova classe automática |
| repetir | 1/1 | Pedir repetição para reparar comunicação |
| entender | 3/3 | Entender/compreender; não converter “não entendi” em positivo |
| ler | 3/4 | Ler instrução/documento; variantes exigem revisão |
| assinar | 2/2 | Assinar documento; não sinalizar em LIBRAS |
| depois | 2/3 | Sequência temporal / retorno |
| hoje | 3/4 | Orientação temporal |
| idade | 2/4 | Cadastro; variantes numeradas |
| anos | 2/2 | Idade em anos, não fundir automaticamente com ano |
| dois | 2/4 | Numeral, não sequência de contagem inteira |
| três | 1/2 | Numeral (`tres1`, `tres2`) |
| quatro | 3/3 | Numeral |
| olá | 1/1 | Saudação; conferir relação com oi |
| bom-dia | 1/1 | Saudação composta, não igual a manhã por definição |
| porque | 2/2 | Uso causal/interrogativo e marcação não manual precisam de conferência |

**Se o atendimento for de saúde**, ampliar também a estes termos já medidos,
sem chamá-los erroneamente de ausentes:

| Termo | MALTA IDs/clipes | Distinção necessária |
|---|---:|---|
| febre | 2/2 | Sintoma, não aula sobre doença |
| receita | 1/2 | Prescrição médica, não culinária ou arrecadação |
| exame | 1/3 | Exame clínico/laboratorial, não prova escolar |

## 3. Presentes, mas com poucas execuções e diversidade limitada

Nenhum termo desta tabela está no MINDS ou no recorte V-res medidos. No V-pré,
cada linha tem **3 IDs / 3 clipes**. Não são três pessoas diferentes a cada palavra:
é predominantemente o mesmo trio do corpus.

| Termo | MALTA IDs/clipes | Soma nominal de IDs V-pré + MALTA |
|---|---:|---:|
| ajudar | 3/4 | 6 |
| esperar | 3/4 | 6 |
| por-favor | 2/2 | 5 |
| obrigado | 2/3 | 5 |
| documento | 2/2 | 5 |
| nome | 2/2 | 5 |
| sim | 2/3 | 5 |
| não | 2/5 | 5 |
| entrar | 2/5 | 5 |
| sair | 2/3 | 5 |
| levar | 3/4 | 6 |
| trazer | 2/2 | 5 |
| escrever | 3/4 | 6 |
| sentar | 2/2 | 5 |
| quando | 1/2 | 4 |
| onde | 2/3 | 5 |
| como | 2/3 | 5 |
| urgente | 2/3 | 5 |
| cansado | 2/2 | 5 |
| nervoso | 2/3 | 5 |
| oi | 3/3 | 6 |
| manhã | 3/4 | 6 |
| noite | 2/4 | 5 |
| número | 1/1 | 4 |
| com-licença | 2/2 | 5 |
| antes | 3/4 | 6 |
| agora | 2/2 | 5 |
| amanhã | 3/3 | 6 |
| poder | 2/3 | 5 |
| preencher | 1/1 | 4 |

As somas nominais não descontam identidade compartilhada entre corpora ou
republicação. Formas numeradas podem representar variantes. O objetivo da busca
é encontrar **execuções de pessoas efetivamente diferentes e sentido compatível**,
não atingir cinco URLs por palavra.

### “Ruim” e outros termos já no núcleo

| Termo | MINDS IDs/clipes | V-res IDs/clipes | MALTA IDs/clipes |
|---|---:|---:|---:|
| ruim | 8/40 | 3/3 | 2/3 |
| medo | 8/40 | 3/3 | 2/2 |
| banheiro | 8/40 | 3/3 | 3/3 |
| vacina | 8/40 | 0/0 | 2/2 |
| filho | 8/40 | 3/3 | 3/3 |
| cinco | 8/40 | 0/0 | 4/4 |
| esquina | 8/40 | 0/0 | 2/2 |

Todos com **zero no V-pré** para os rótulos pesquisados. V-res é reserva, não
estoque de treino. “Ruim” é prioridade de **diversidade/estado no atendimento**,
por solicitação do usuário, e não ausência lexical. “Estou passando mal” não é
automaticamente o sinal isolado ruim. Não deslocar as pessoas/clipes reservados
nem misturar resultados do vocabulário estendido às métricas do núcleo.

### Evitar falsas lacunas e falsos conectivos

- `ajuda`, `preciso` e `quero` não apareceram por nome, mas existem `ajudar`,
  `precisar` e `querer`. Tratar como **termos de busca auxiliares**; o consultor
  decide se são equivalentes no exemplo, em vez de criar três novas classes.
- `oi/olá`, `manhã/bom-dia`, `ano/anos/idade`, `marcar/agendar`, `dor/doer/doloroso`
  não são equivalências automáticas.
- `onde`, `quando`, `como` são úteis para perguntas; `por favor` e `obrigado`
  são fórmulas de interação, não simplesmente conectivos gramaticais.
- Não tentar compor português palavra a palavra acrescentando “e”, “de”, “para”
  como se houvesse correspondência obrigatória em LIBRAS. Expressões não manuais
  e sintaxe ficam sob revisão do consultor e limitações do modelo atual.

## 4. Ordem de execução: três frentes com orçamento limitado

### Lote A — maior retorno imediato, 12 alvos

**ruim, dor, precisar, mostrar, voltar, buscar, repetir, perguntar, ajudar,
esperar, documento, por-favor.**

Mistura deliberada de déficit alto com funções frequentes de balcão e palavras
já solicitadas. Na triagem original só `precisar` (2) e `esperar` (1) tinham
candidatos entre esses alvos; as demais precisam de nova prospecção ou recorte.
Não baixar de novo os candidatos/excluídos existentes.

### Lote B — institucional, seis alvos, prospecção com sentido explícito

**atendimento, consulta, protocolo, senha, agendar, marcar.**

Alto déficit, mas maior risco de sentido e variante. Fazer primeiro inventário
de links e descrições, com limite de tempo. `consulta` já tem um candidato na
triagem, “Consulta médica Libras”; não está aprovado nem resolve o rótulo genérico.
Se a busca só retornar outros sentidos, registrar lacuna e priorizar coleta local;
não substituí-los por avatares, frases longas ou sinais de informática.

### Lote C — depois da revisão de A/B

1. **Reparo/cadastro:** entender, assinar, preencher, ler, nome.
2. **Orientação e circulação:** entrar, sair, levar, trazer, sentar, levantar.
3. **Perguntas/tempo/necessidade:** porque, quando, onde, como, querer, poder,
   depois, hoje, agora, amanhã, antes.
4. **Interação:** obrigado, sim, não, com-licença, oi/olá, bom-dia.
5. **Cadastro numérico:** idade, anos, dois, três, quatro, número.
6. **Saúde, se confirmado o cenário:** febre, receita, exame, urgente, cansado,
   nervoso. Medo/vacina/banheiro já têm núcleo MINDS e são diversidade adicional.

Não executar todos esses itens de uma vez. O saldo da fila do consultor e a
decisão do cenário controlam a abertura do próximo lote.

## 5. Consultas e desambiguação

Para cada alvo, pesquisar até três formulações iniciais:

- `"<termo>" "Libras" "sinal"`
- `"<termo>" "Libras" "dicionário"`
- `"<termo>" "Libras" "sinalário"`

Aspas são ajuda de busca, não prova de rótulo. Uma quarta consulta contextual só
se as três primeiras não trouxerem candidatos distintos. A consulta regional
`"<termo>" "Libras" "Goiás"` / `"Goiânia"` é uma pista adicional, não requisito
que eliminaria vídeos sem região no título nem confirmação da variante.

| Alvo | Consulta contextual / alternativas de descoberta | Separar ou excluir do rótulo simples |
|---|---|---|
| ruim | `"ruim" "Libras" "sinal isolado"` | bem × ruim, ruim/mal; passar mal é outra expressão |
| dor | `"dor" "Libras" "dicionário"` | aulas de lombalgia, dor de cabeça, frase de intensidade; dor/doer ambíguo |
| precisar | `"precisar" "Libras"`; auxiliar “necessidade” | preciso/exato, eu preciso de você; sem fusão por título |
| mostrar | `"mostrar" "Libras" "documento"` | frase inteira não vira exemplo isolado; mostrar/apresentar ambíguo |
| voltar | `"voltar" "Libras"`; auxiliares “retornar”, “regressar” | sentidos emocionais, compilações de variantes |
| buscar | `"buscar" "Libras" "sinal"` | buscar-nós/direcional não fundir; pesquisa na internet |
| repetir / perguntar / entender | termo exato + `"Libras" "sinal"` | aulas inteiras, negação omitida, frases não segmentadas |
| ajudar | `"ajudar" "Libras"`; auxiliar “ajuda” | “como posso te ajudar?” como frase; 6 sinais em um vídeo |
| esperar | `"esperar" "Libras"`; auxiliar “aguardar” | compilação de sinônimos deve ser revisada |
| documento | `"documento" "Libras" "sinal"` | documento/conta, documento específico não equivalente |
| por-favor | `"por favor" "Libras"` | com licença/por favor, diferenças entre sinais |
| atendimento | `"atendimento ao público" "Libras" "sinal"` | atendimento ao paciente como tema de aula |
| consulta | `"consulta médica" "Libras" "sinal"` | consulta SQL, busca; composto médico precisa de decisão |
| protocolo | `"protocolo" "Libras" "atendimento"` | redes, protocolos clínicos; registro não é sinônimo garantido |
| senha | `"senha de atendimento" "Libras"`; `"senha" "Libras" "fila"` | password, código/segredo, música |
| agendar / marcar | `"agendar" "Libras"`; `"marcar consulta" "Libras" "sinal"` | marcar gol/objeto; frase exige recorte e revisão |
| nome / escrever / assinar | termo exato + `"Libras" "dicionário"` | nome completo, máquina de escrever; assinar ≠ sinalizar |
| receita / exame | `"receita médica" "Libras"`; `"exame médico" "Libras"` | receita culinária/arrecadação, prova escolar |
| idade / anos / numerais | termo exato + `"Libras" "sinal"` | contar de 1 a 100 não vale como um clipe por numeral |
| onde / como / quando / porque | termo + `"Libras" "interrogativo"` ou `"dicionário"` | manter sentido e marcas não manuais; não confundir aula com execução |

**Não usar blacklist cega:** “sinal-termo”, “glossário”, “consulta médica”,
aspas e barra não provam que o vídeo está errado. Os casos polissêmicos vão para
fila de ambiguidade, não recebem rótulo aprovado nem descarte definitivo por
string. “Nome completo” e “máquina de escrever” são exclusões do rótulo simples,
não necessariamente vídeos ruins para seus rótulos próprios.

## 6. Filtros, quotas e critério de parada propostos

1. **Descoberta sem download:** até 3 consultas × 10 resultados por alvo;
   deduplicar IDs antes de expandir metadados. Quarta consulta contextual no máximo.
   Não extrair streams de todos os resultados da busca.
2. **Elegibilidade técnica inicial:** vídeo individual público, humano a conferir,
   duração conhecida. Sem login, contorno de restrição, transmissão ao vivo ou playlist.
3. **Faixas de trabalho:** `0 < duração ≤ 6 s` = curto prioritário;
   `6 < duração ≤ 20 s` = possível recorte; `>20 s` = fora desta rodada.
   Limites operacionais, não definição linguística de sinal isolado. Duração
   arredondada do YouTube é preliminar; usar ffprobe para limites finais.
4. **No máximo 3 candidatos novos por palavra e 20 por lote**, preferindo
   curtos (até 2 curtos + 1 médio quando disponíveis). Para o lote B,
   teto total 10. Se só houver médios, até 2 por palavra, dentro do mesmo teto.
   Meta é revisão manejável, **não cinco downloads a qualquer custo**.
5. **Diversidade:** priorizar canal ainda não representado, mas confirmar pessoa
   e republicações depois. Comparar ID globalmente, hash e frames de referência
   quando disponíveis; hash diferente não prova gravação diferente. Os dois
   “trazer” originais são exemplo de URLs diferentes com bytes idênticos.
6. **Memória das exclusões:** guardar por ID e sentido-alvo, não só por palavra.
   Não recolher `nome/TotOUnrWBdI`, `escrever/j2RopRM-5Hs`,
   `levar ou trazer/jj_2JTzuw50`, nem os explicativos de dor já rejeitados.
   Revisão de um descarte por duração precisa ser explícita, sem sobrescrever
   a triagem original ou misturar silenciosamente com os 18 candidatos.
7. **Parar** ao atingir a quota, o limite de consultas ou se só houver material
   repetido/ambíguo. Registrar `sem_candidato_novo`, não concluir “sinal inexistente”.
8. **Quarentena:** intenção de busca, palavra proposta, título, canal/ID, URL,
   duração, licença declarada, alertas, hash e origem dos dados registrados.
   Linguística e permissão ficam pendentes. Ausência de licença não ganha valor
   `true` por conveniência; priorizar licença aberta ou contato com o autor.

## 7. Antes de buscar mais: recortes locais potencialmente econômicos

Há material local que pode reduzir downloads, mas hoje permanece `descartar`
na triagem. **Não foi reclassificado aqui.** Exemplos para eventual fila separada,
somente se o consultor aceitar revisar médios:

| Alvo | ID local | Duração na triagem | Motivo para considerar / pendência |
|---|---|---:|---|
| ruim | OiysZ7GJ2qU | 15 s | Título focado; localizar execução, sem presumir conteúdo |
| atendimento | eaWQDGzcP2I | 17 s | Sinalário; confirmar sentido e trecho |
| protocolo | xOypLUywmNU | 14 s | Sinalário; confirmar contexto administrativo |
| documento | 7FUK04h5_zw | 15 s | Conferir execução e recorte |
| voltar | PjrFp93O4Ew | 19 s | Conferir variante e recorte |
| por-favor | V3xEY9sykjY | 12 s | Não é o clipe ambíguo com “com licença” |
| ajudar | fFKvNicVieA | 10 s | Conferir execução completa e recorte |
| buscar | cVGe2ZtC5QU | 14 s | Conferir sentido e recorte |

Reutilizar esses arquivos evita baixar novamente; não reduz a obrigação de
validar linguagem e permissão. Não cortar simplesmente os primeiros seis segundos.

## 8. Critério de sucesso e integração

Relatar separadamente: resultados descobertos → URLs inéditas → candidatos
curtos/médios → clipes linguisticamente aprovados → permissões documentadas →
pessoas/execuções efetivamente novas. O tempo de revisão por exemplo aproveitado
importa mais que o total de vídeos baixados.

O consultor confere palavra, sentido, variante regional, execução completa,
enquadramento e eventuais marcas não manuais. A concordância lexical não aprova
automaticamente o uso dos dados. Recortes mantêm relação com o original e seus
tempos. Antes do treino, deduplicar também contra as bases existentes e preservar
splits e reservas. Não avaliar no mesmo material usado para selecionar/ajustar o modelo.

Manter **dois fluxos separados**: fontes documentadas no coletor atual e
prospecção YouTube em quarentena com curadoria. Esta estratégia não desativa os
critérios do coletor documentado nem promove links de busca a fontes credenciadas.
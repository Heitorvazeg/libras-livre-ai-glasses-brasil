# Resultado do fine-tuning — v1 (glosa → PT)

Gerado em 2026-09-11 12:15 · ptt5-small podado (45,1M, vocab 1.987) · 20 épocas · protocolo: split **por seq_id** (paráfrases da mesma sequência nunca cruzam treino/validação).

## ⚠️ O que este número NÃO é

Validação **sintética**: as referências saíram do mesmo gerador (`claude-opus-5`) que produziu o treino. Isto mede se o modelo aprendeu o mapeamento que nós inventamos — não se ele traduz Libras. O portão real é `avaliacao/conjunto_humano.jsonl` (§5.3), que ainda não existe. Nenhum número daqui deve aparecer em apresentação sem esta ressalva.

## Resultado

| Métrica | Template (portão) | Modelo | Alvo §10 |
|---|---|---|---|
| Acerto de negação | 1.000 | **1.000** | 1,000 inegociável |
| Content-word recall | 0.976 | **1.000** | ≥ 0,980 |
| Taxa de fallback | 0.103 | **0.000** | medir |
| Exact match (multi-ref) | 0.414 | **0.414** | > template |
| F1 de palavras | 0.884 | **0.856** | > template |

Corpus: 1528 pares / 191 sequências (272 com negação; 472 sem depender das glosas `camada: 3-proposta`).

## Curva de perda

| Época | Treino | Validação |
|---|---|---|
| 1 | 7.7672 | 4.5863 |
| 2 | 3.4586 | 2.2267 |
| 3 | 2.3082 | 1.8676 |
| 4 | 1.8939 | 1.7216 |
| 5 | 1.6526 | 1.6395 |
| 6 | 1.5476 | 1.5923 |
| 7 | 1.4421 | 1.5306 |
| 8 | 1.3726 | 1.4968 |
| 9 | 1.3172 | 1.4782 |
| 10 | 1.2974 | 1.4623 |
| 11 | 1.2448 | 1.4302 |
| 12 | 1.2118 | 1.4241 |
| 13 | 1.1863 | 1.4079 |
| 14 | 1.1609 | 1.3925 |
| 15 | 1.1419 | 1.3819 |
| 16 | 1.1400 | 1.3740 |
| 17 | 1.1374 | 1.3718 |
| 18 | 1.1092 | 1.3668 |
| 19 | 1.1167 | 1.3661 |
| 20 | 1.1054 | 1.3650 |

## Saídas lado a lado (validação)

| Glosas | Template | Modelo |
|---|---|---|
| `eu oi nome` | Olá, eu o nome. | eu agradeço o meu oi, eu agradeço nome |
| `obrigado` | Obrigado | obrigado, obrigado |
| `obrigado ajuda` | Obrigado pela ajuda | obrigado, por favor, me de ajuda |
| `eu precisar documento` | Eu preciso do documento. | eu preciso do documento |
| `documento onde` | Onde fica o documento? | onde está o documento? |
| `eu não documento voltar` | Eu não volto o documento. | eu não volto com o documento |
| `eu filho documento` | Eu o meu filho o documento. | eu tenho o documento do meu filho |
| `eu querer ajuda` | Eu quero ajuda. | eu quero uma ajuda |
| `eu medo vacina` | Eu estou com medo a vacina. | eu tenho receio da vacina |
| `eu precisar número` | Eu preciso da senha. | eu preciso do número |
| `eu precisar ajuda documento` | Eu preciso de ajuda o documento. | preciso de uma ajuda, o documento |
| `eu não conhecer você ajuda` | Eu não conheço ajuda. | eu não conheço você me ajuda |
| `eu dor ruim` | Eu estou com dor ruim. | eu sinto dor muito ruim |
| `eu dor noite` | Eu estou com dor à noite. | eu sinto dor à noite |
| `filho ruim` | O meu filho está mal. | o meu filho está mal |
| `banco esquina` | O banco a esquina. | o banco fica na esquina |
| `eu não conhecer banco` | Eu não conheço o banco. | eu não conheço o banco |
| `eu querer banco documento` | Eu quero o banco o documento. | eu queria o documento do banco |
| `eu não querer vacina` | Eu não quero a vacina. | eu não quero a vacina |
| `filho não ruim` | O meu filho não está mal. | o meu filho está mal, não |
| `eu não querer voltar` | Eu não quero. | eu não quero voltar |
| `eu não conhecer você` | Eu não conheço. | eu não conheço você |
| `eu filho` | Eu o meu filho. | eu tenho um filho que estuda |
| `filho aluno nome` | O meu filho aluno o nome. | o meu filho é estudante de nome |
| `barulho` | Barulho. | o barulho é muito forte |

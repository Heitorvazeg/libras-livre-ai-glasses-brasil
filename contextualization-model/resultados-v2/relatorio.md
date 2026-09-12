# Resultado do fine-tuning — v2 (glosa → PT)

Gerado em 2026-09-11 12:38 · ptt5-small podado (45,1M, vocab 1.987) · 30 épocas · protocolo: split **por seq_id** (paráfrases da mesma sequência nunca cruzam treino/validação).

## ⚠️ O que este número NÃO é

Validação **sintética**: as referências saíram do mesmo gerador (`claude-opus-5`) que produziu o treino. Isto mede se o modelo aprendeu o mapeamento que nós inventamos — não se ele traduz Libras. O portão real é `avaliacao/conjunto_humano.jsonl` (§5.3), que ainda não existe. Nenhum número daqui deve aparecer em apresentação sem esta ressalva.

## Resultado

| Métrica | Template (portão) | Modelo | Alvo §10 |
|---|---|---|---|
| Acerto de negação | 1.000 | **1.000** | 1,000 inegociável |
| Content-word recall | 0.953 | **1.000** | ≥ 0,980 |
| Taxa de fallback | 0.158 | **0.000** | medir |
| Exact match (multi-ref) | 0.395 | **0.395** | > template |
| F1 de palavras | 0.877 | **0.824** | > template |

Corpus: 2048 pares / 256 sequências (384 com negação; 608 sem depender das glosas `camada: 3-proposta`).

## Curva de perda

| Época | Treino | Validação |
|---|---|---|
| 1 | 7.8951 | 4.8620 |
| 2 | 3.5047 | 2.1592 |
| 3 | 2.2633 | 1.7159 |
| 4 | 1.8135 | 1.5118 |
| 5 | 1.5890 | 1.3965 |
| 6 | 1.4529 | 1.3109 |
| 7 | 1.3631 | 1.2645 |
| 8 | 1.2835 | 1.2244 |
| 9 | 1.2335 | 1.1837 |
| 10 | 1.1682 | 1.1617 |
| 11 | 1.1256 | 1.1496 |
| 12 | 1.0972 | 1.1311 |
| 13 | 1.0760 | 1.1124 |
| 14 | 1.0401 | 1.1016 |
| 15 | 1.0300 | 1.0859 |
| 16 | 1.0255 | 1.0825 |
| 17 | 0.9844 | 1.0775 |
| 18 | 0.9747 | 1.0694 |
| 19 | 0.9695 | 1.0741 |
| 20 | 0.9587 | 1.0659 |
| 21 | 0.9464 | 1.0658 |
| 22 | 0.9502 | 1.0589 |
| 23 | 0.9277 | 1.0587 |
| 24 | 0.9256 | 1.0543 |
| 25 | 0.9151 | 1.0547 |
| 26 | 0.9145 | 1.0513 |
| 27 | 0.9168 | 1.0494 |
| 28 | 0.9055 | 1.0485 |
| 29 | 0.9042 | 1.0474 |
| 30 | 0.9052 | 1.0472 |

## Saídas lado a lado (validação)

| Glosas | Template | Modelo |
|---|---|---|
| `oi manhã` | Bom dia | olá, de manhã |
| `eu oi nome` | Olá, eu o nome. | eu agradeço o nome dele, o meu nome é oi |
| `obrigado` | Obrigado | obrigado, obrigado |
| `obrigado ajuda` | Obrigado pela ajuda | obrigado, me ajude |
| `eu precisar documento` | Eu preciso do documento. | preciso do documento |
| `documento onde` | Onde fica o documento? | onde está o documento? |
| `eu não documento voltar` | Eu não volto o documento. | eu não tenho o documento aqui, não tenho o meu voltar |
| `eu filho documento` | Eu o meu filho o documento. | eu tenho o documento do meu filho |
| `eu querer ajuda` | Eu quero ajuda. | eu queria uma ajuda |
| `eu medo vacina` | Eu estou com medo a vacina. | eu tenho receio da vacina |
| `eu precisar número` | Eu preciso da senha. | eu preciso do número para a senha |
| `eu precisar ajuda documento` | Eu preciso de ajuda o documento. | preciso de ajuda com o documento |
| `eu não conhecer você ajuda` | Eu não conheço ajuda. | eu não conheço você me ajuda |
| `eu dor ruim` | Eu estou com dor ruim. | eu sinto dor e estou mal |
| `eu dor noite` | Eu estou com dor à noite. | eu sinto dor à noite |
| `filho ruim` | O meu filho está mal. | o meu filho está mal |
| `ruim acontecer` | Aconteceu. | o pior é que aconteceu |
| `banco esquina` | O banco a esquina. | o banco fica na esquina |
| `eu não conhecer banco` | Eu não conheço o banco. | eu não conheço o banco |
| `eu querer banco documento` | Eu quero o banco o documento. | eu queria o documento do banco |
| `eu não querer vacina` | Eu não quero a vacina. | eu não quero a vacina |
| `filho não ruim` | O meu filho não está mal. | o meu filho não está ruim |
| `eu não querer voltar` | Eu não quero. | eu não quero voltar |
| `eu não conhecer você` | Eu não conheço. | eu não conheço você |
| `eu filho` | Eu o meu filho. | eu tenho um filho que estuda aqui |

# Resultado do fine-tuning — v3 (glosa → PT)

Gerado em 2026-09-11 13:10 · ptt5-small podado (45,1M, vocab 1.987) · 30 épocas · protocolo: split **por seq_id** (paráfrases da mesma sequência nunca cruzam treino/validação).

## ⚠️ O que este número NÃO é

Validação **sintética**: as referências saíram do mesmo gerador (`claude-opus-5`) que produziu o treino. Isto mede se o modelo aprendeu o mapeamento que nós inventamos — não se ele traduz Libras. O portão real é `avaliacao/conjunto_humano.jsonl` (§5.3), que ainda não existe. Nenhum número daqui deve aparecer em apresentação sem esta ressalva.

## Resultado

| Métrica | Template (portão) | Modelo | Alvo §10 |
|---|---|---|---|
| Acerto de negação | 1.000 | **1.000** | 1,000 inegociável |
| Content-word recall | 0.971 | **1.000** | ≥ 0,980 |
| Conteúdo inventado (§8.1) | 0.048 | **0.119** | 0 — a guarda não pega |
| Taxa de fallback | 0.071 | **0.000** | medir |
| Exact match (multi-ref) | 0.429 | **0.429** | > template |
| F1 de palavras | 0.871 | **0.836** | > template |

Corpus: 2202 pares / 285 sequências (373 com negação; 765 sem depender das glosas `camada: 3-proposta`).

## Curva de perda

| Época | Treino | Validação |
|---|---|---|
| 1 | 7.6652 | 5.2314 |
| 2 | 3.2938 | 2.4226 |
| 3 | 2.1836 | 1.9631 |
| 4 | 1.7866 | 1.7318 |
| 5 | 1.5614 | 1.5779 |
| 6 | 1.4312 | 1.4935 |
| 7 | 1.3264 | 1.4378 |
| 8 | 1.2775 | 1.4082 |
| 9 | 1.2123 | 1.3720 |
| 10 | 1.1619 | 1.3436 |
| 11 | 1.1187 | 1.3282 |
| 12 | 1.0995 | 1.3191 |
| 13 | 1.0620 | 1.2983 |
| 14 | 1.0373 | 1.2805 |
| 15 | 1.0250 | 1.2758 |
| 16 | 0.9933 | 1.2675 |
| 17 | 0.9790 | 1.2551 |
| 18 | 0.9703 | 1.2509 |
| 19 | 0.9611 | 1.2458 |
| 20 | 0.9548 | 1.2446 |
| 21 | 0.9424 | 1.2394 |
| 22 | 0.9304 | 1.2400 |
| 23 | 0.9293 | 1.2397 |
| 24 | 0.9122 | 1.2348 |
| 25 | 0.9113 | 1.2306 |
| 26 | 0.9093 | 1.2325 |
| 27 | 0.9016 | 1.2316 |
| 28 | 0.9003 | 1.2309 |
| 29 | 0.8955 | 1.2297 |
| 30 | 0.9016 | 1.2293 |

## Saídas lado a lado (validação)

| Glosas | Template | Modelo |
|---|---|---|
| `oi eu precisar ajuda` | Olá, eu preciso de ajuda. | olá, eu preciso de ajuda |
| `não obrigado` | Obrigado, não. | não, obrigado |
| `obrigado eu voltar` | Obrigado, eu volto. | obrigado, eu volto |
| `obrigado noite` | Obrigado, à noite. | obrigada pela noite |
| `nome por-favor` | O nome, por favor. | por favor, o nome |
| `você nome` | Você o nome. | o nome do senhor |
| `documento onde` | Onde fica o documento? | onde está o documento? |
| `eu documento esperar` | Eu estou esperando o documento. | eu vou aguardar o documento |
| `eu não documento voltar` | Eu não volto o documento. | eu não tenho o documento aqui, eu não volto |
| `banheiro onde por-favor` | Onde fica o banheiro, por favor. | onde fica o banheiro? por favor |
| `filho precisar vacina` | O meu filho precisa da vacina. | o meu filho precisa da vacina |
| `vacina onde` | Onde fica a vacina? | onde eu tomo a vacina? |
| `eu vontade` | Eu quero. | eu aproveito muito, vontade |
| `eu precisar número` | Eu preciso da senha. | eu preciso do número |
| `eu não conhecer você ajuda` | Eu não conheço ajuda. | eu não conheço a senhora que me ajudou |
| `eu ruim` | Eu estou mal. | eu estou mal |
| `ruim acontecer` | Aconteceu. | o que aconteceu? É ruim acontecer |
| `eu esperar` | Eu estou esperando. | eu espero |
| `esperar quanto por-favor` | Quanto tempo estou esperando, por favor. | quanto tempo, por favor?????? esperar? |
| `você querer eu esperar` | Eu quero. | você quer que eu aguarde? |
| `filho esperar` | O meu filho está esperando. | o meu filho está esperando |
| `eu número cinco` | Eu a senha cinco. | eu tenho o número cinco |
| `cinco quando` | Quando cinco? | que dia é o cinco? |
| `quando eu voltar` | Quando eu volto? | quando eu volto? |
| `eu precisar banco` | Eu preciso do banco. | eu preciso do banco |

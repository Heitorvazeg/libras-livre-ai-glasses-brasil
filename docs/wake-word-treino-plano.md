# Treino dos classificadores de wake word pt-BR — plano e decisões

> Fecha a pendência registrada em `docs/orquestracao-dialogo-audio-plano.md` Fase 3
> e §8 item 1: o motor real de wake word (`OpenWakeWordDetector`, ONNX Runtime,
> offline) já está implementado no app e esperando só os dois classificadores
> treinados — `libras_livre_iniciar.onnx` e `libras_livre_encerrar.onnx`. Este
> documento é o "porquê" do que está em `../wake-word-model/`; o "como rodar" está
> no `README.md` daquela pasta.

## 0. Contexto em uma frase

Duas frases simétricas ("Libras Livre, iniciar" / "Libras Livre, encerrar")
precisam ser detectadas continuamente no mic do celular, offline, com baixo
consumo — o motor escolhido (`openWakeWord`, Apache 2.0) resolve a parte de
inferência, mas exige treinar um classificador raso por frase, e o pipeline oficial
de treino não tem suporte pronto pra português.

## 1. Por que não dá pra só baixar um modelo pronto

O `openWakeWord` distribui modelos prontos (`alexa`, `hey_jarvis`, `hey_mycroft`...)
só em inglês. Pra qualquer frase nova — em qualquer idioma — é preciso treinar. Não
existe atalho "sem retreino": o KWS sem retreino do `sherpa-onnx` foi pesquisado
como possível atalho (ver `orquestracao-dialogo-audio-plano.md` §8 item 1) e
descartado por só ter modelo pré-treinado zh/en, sem precedente de funcionar com
fonemas pt-BR nesse tokenizador.

## 2. A lacuna: `piper-sample-generator` não existe em pt-BR

O jeito recomendado de treinar (notebook oficial
`automatic_model_training.ipynb`) gera as amostras positivas/negativas com o
[`piper-sample-generator`](https://github.com/rhasspy/piper-sample-generator): um
checkpoint VITS **multi-falante**, com amostragem contínua de *speaker embedding*
(não é "uma voz", é uma família inteira de vozes sintéticas geradas por
amostragem), pensado especificamente pra dar diversidade de identidade vocal ao
dataset de treino sem gravar ninguém.

**Verificado nos releases oficiais do repositório** (`v1.0.0`–`v3.1.0`): os únicos
checkpoints publicados são `en_US-libritts_r-medium`, `de_DE-mls-medium`,
`fr_FR-mls-medium` e `nl_NL-mls-medium`. **Busca na API do HuggingFace** por
checkpoints equivalentes em português não encontrou nenhum — só vozes Piper de
inferência normal (um falante fixo cada), que é uma coisa diferente: essas servem
pra *falar* uma frase (é o que `PiperSherpaOnnxTtsEngine.kt` usa pro TTS do app),
não pra gerar um dataset de treino com a diversidade vocal que o
`piper-sample-generator` foi desenhado pra dar.

**Substituto adotado:** 6 vozes Piper pt-BR de comunidade publicadas pelo
`k2-fsa/sherpa-onnx` (`edresson`, `faber`, `cadu`, `jeff`, `miro`, `dii` — mesmo
formato que o app já baixa em `download-assets.sh`), cada uma instanciada com 4
combinações de `noise_scale`/`noise_scale_w` e velocidade variada por chamada
(`speed` de 0,85 a 1,15) — ver `wake-word-model/dados/sintetizar.py`. Isso dá 6
identidades vocais **discretas**, não a variação **contínua** de um gerador
multi-falante de verdade. É uma diferença real de diversidade, registrada aqui de
propósito: se o classificador final generalizar mal pra vozes/sotaques fora dessas
6, esta é a explicação mais provável, e a correção é treinar um checkpoint
multi-falante pt-BR do zero pro `piper-sample-generator` (trabalho de dias, não
cabia nesta sessão) ou gravar/coletar vozes humanas reais como reforço.

Se um checkpoint pt-BR compatível aparecer publicado no futuro, o pipeline oficial
(`--generate_clips`) volta a ser a opção — só trocar
`piper_sample_generator_path` no `config/*.yaml` pelo clone real; o stub em
`_vendor/piper-sample-generator-stub/` deixa de ser necessário (ver a docstring
dele pro motivo de existir).

## 3. O pool de negativos pré-computado (ACAV100M)

O treino oficial usa negativos de duas fontes complementares:

1. **Confusáveis sintéticos** — variações fonéticas da frase-alvo, gerados
   automaticamente em inglês (`generate_adversarial_texts`, baseado em
   `pronouncing`, uma biblioteca de fonética do inglês). Não serve pra português —
   por isso este projeto os substitui por uma lista **escrita à mão**
   (`wake-word-model/dados/frases.py`): frases truncadas, confusões fonéticas
   plausíveis em pt-BR, contexto de atendimento de balcão, e — o mais importante —
   **a frase irmã como negativo de cada classificador** ("encerrar" é o negativo
   mais perigoso de "iniciar", e vice-versa; ver Fase 3 do plano de orquestração,
   critério de sucesso "incluindo confundir uma frase pela outra").
2. **Features pré-computadas de ~2.000 horas de áudio genérico** (dataset
   `davidscripka/openwakeword_features`, ACAV100M) — é isto que dá ao classificador
   uma noção ampla de "como é o áudio do cotidiano que não tem nada a ver com a
   frase".

### Rodada 1 (2026-09-12): pulada por achar 17 GB inviável

**Decisão inicial: pular a fonte 2**, com compensação parcial — RIR real (MIT,
domínio público, ~8 MB, 270 respostas ao impulso) na augmentation, ruído ambiente
real (ESC-50, CC BY-NC 3.0, ~120 clipes de 5 s, resample pra 16 kHz) mixado como
fundo E usado como negativo extra, e o conjunto de validação de falso-positivo
(também do `openwakeword_features`, mas só 185 MB — esse sim baixado) recortado
de ~11 h pras primeiras ~2,7 h (`MAX_FRAMES_VALIDACAO` em `dados/baixar_ruido.py`
— `train.py` monta uma janela deslizante de passo 1 sobre o arquivo inteiro pra
checagem periódica de falso-positivo durante o treino, e as 11 h originais viravam
uma matriz de ~3,9 GB em RAM repetida a cada checagem, o que já tinha derrubado o
processo por OOM nesta sessão de 12 CPUs/15 GB compartilhados com o resto do
desktop).

**Resultado (confirmou o risco previsto):** os dois classificadores aprenderam a
distinguir a frase-alvo dos confusáveis (recall 0,84/0,56, precisão 0,91/0,77 no
split sintético — não um treino quebrado), mas com **103 a 280 falsos-positivos
por hora** em áudio genérico (alvo do config: 0,2/h) — 500 a 1.400× o alvo.
Exatamente o padrão de falha esperado quando o modelo nunca viu "áudio do
cotidiano" em volume.

### Rodada 2 (2026-09-12, mesma sessão): revisada — 17 GB não era inviável

O "inviável" da Rodada 1 vinha de uma suposição, não de uma medição. Com o
falso-positivo real medido e a banda desta sessão observada em ~14 MB/s (medida
baixando as vozes Piper, bem antes desta decisão), os 17,3 GB do ACAV100M dão
**~20 minutos de download** — nada perto de inviável. Revertido:
`dados/baixar_ruido.py` agora baixa o ACAV100M por padrão (`--pular-acav100m` pra
quem preferir não baixar), e os dois `config/*.yaml` apontam `feature_data_files`
pra ele (`batch_n_per_class.ACAV100M_sample: 1024`, valor do `custom_model.yml`
original). Carregado via `np.load(..., mmap_mode='r')` — memory-mapped, não aloca
17 GB em RAM, só as páginas efetivamente lidas pelo sampling aleatório de cada
batch, evitando repetir o OOM da Rodada 1.

**`max_negative_weight` mantido em 3** (não voltou pro default 1.500) de
propósito: mudar uma variável de cada vez — ver §6 pro colapso que 1.500 causou
sem o ACAV100M na Rodada 1. Resultado da Rodada 2, com ACAV100M mas peso ainda
baixo, no §4 abaixo.

**Licença do ESC-50 (CC BY-NC 3.0):** uso restrito a treino/augmentation nesta
pasta — os clipes não entram no git (`wake-word-model/.gitignore`) e nunca são
embarcados no app; só o `.onnx` resultante (que não contém áudio nenhum) sai desta
pasta.

## 4. O que os relatórios medem — e o que não medem

`wake-word-model/avaliar.py` produz `resultados/<modelo>/relatorio.md` com:

- Acurácia/recall/precisão no split de teste **sintético** (mesmo gerador do
  treino) — mede se o classificador aprendeu o que pedimos pra ele aprender, não
  se funciona com gente de verdade.
- Falso-positivo-por-hora no conjunto de validação genérico (~2,7h recortadas —
  ver §3 —, majoritariamente inglês/música/ruído, não pt-BR nem cenário de
  balcão) — um proxy grosseiro, não o ambiente de produção.

Isto é o **mesmo tipo de ressalva** que `contextualization-model/README.md` faz
sobre o corpus sintético dele: número medido no gerador que também gerou o treino
não é evidência de generalização. Não deve aparecer em apresentação sem esta
ressalva.

### Resultado medido — Rodada 1 (sem ACAV100M) vs. Rodada 2 (com ACAV100M)

| | Rodada 1 (`iniciar` / `encerrar`) | Rodada 2 (`iniciar` / `encerrar`) |
|---|---|---|
| Recall (split sintético) | 0,840 / 0,560 | 0,630 / 0,400 |
| Precisão (split sintético) | 0,913 / 0,767 | 0,955 / 1,000 |
| Acurácia (split sintético) | 0,886 / 0,710 | 0,810 / 0,714 |
| Falso-positivo/hora (áudio genérico) | 103,4 / 280,1 | **1,48 / 0,00** |
| Alvo de falso-positivo/hora (config) | 0,2 / 0,2 | 0,2 / 0,2 |

**O ACAV100M funcionou exatamente como o pipeline promete.** Falso-positivo caiu
70× (`iniciar`) e foi a zero no proxy medido (`encerrar`) — `encerrar` já bate o
alvo do config, `iniciar` está a 7,4× dele (ante 517× na Rodada 1). O preço foi
recall: caiu de 0,84→0,63 e de 0,56→0,40. **É a troca esperada, não um efeito
colateral estranho:** mais pressão contra falso-positivo — vindo de negativos de
verdade agora, não só do peso da loss — empurra o limiar de decisão pra cima, e
menos positivos passam. Nenhum dos dois classificadores colapsou (compare com
`max_negative_weight: 1.500` sem ACAV100M, §6 — aquele sim dava TP=0 em tudo).

**Ainda não wireado como motor padrão.** `SpeechRecognizerWakeWordDetector`
continua ativo — ver `docs/orquestracao-dialogo-audio-plano.md` Fase 3, critério
de sucesso.

### Curva de limiar (sem retreinar) — `resultados/*/relatorio.md`

`avaliar.py` recalcula os mesmos scores em 9 limiares (0,1 a 0,9) — trocar
`DEFAULT_THRESHOLD` em `OpenWakeWordDetector.kt` não exige exportar `.onnx` de
novo. Achado central: **os dois classificadores respondem de jeitos opostos ao
limiar**, o que muda o próximo passo de cada um.

- **`iniciar` tem uma folga real.** Descendo de 0,5 pra 0,3: recall 0,63→0,71,
  precisão só cai 0,955→0,947, FP/h sobe 1,48→1,97 — ainda ~50× melhor que antes
  do ACAV100M. É ganho de recall "de graça", sem retreinar.
- **`encerrar` não se move com o limiar.** Recall fica entre 0,40 e 0,47 em
  QUALQUER limiar de 0,1 a 0,9, com precisão 1,000 constante. Isso não é uma
  questão de calibração — é sinal de que os scores dos positivos são
  **bimodais**: uma parte cai bem alto (sempre detectada), outra bem baixo
  (nunca detectada, em nenhum limiar), sem meio-termo. Aponta pra falta de
  diversidade nos positivos ou nos confusáveis de `encerrar`
  ("terminar"/"finalizar" têm mais sobreposição com fala comum em pt-BR — ver
  hipótese já registrada acima), não pra `max_negative_weight` nem pro limiar.

Próximos passos, nesta ordem: (1) considerar `DEFAULT_THRESHOLD` mais baixo pra
`iniciar` especificamente (0,3-0,4); (2) pra `encerrar`, mais dado/diversidade
antes de mexer em limiar ou peso — o problema não está aí; (3) testar os dois em
hardware real, que é o único jeito de saber se isto dá uma experiência de uso
aceitável.

### Rodada 3 (2026-09-12, mesma sessão): mais dado pro `encerrar`

Seguindo o item (2) acima: `libras_livre_encerrar` precisava de mais
diversidade nos negativos, não de calibração. Duas adições:

1. **Fala real em português** (`dados/baixar_fala_pt.py`): 600 recortes de
   ~1,5 s do MLS Portuguese (`facebook/multilingual_librispeech`, CC-BY-4.0,
   audiolivros do LibriVox — majoritariamente português europeu, não pt-BR, mas
   é fonética/prosódia portuguesa de verdade, o que nem o ACAV100M
   (majoritariamente inglês) nem os confusáveis manuscritos ofereciam).
2. **`ENCERRAMENTO_SINONIMOS`** em `dados/frases.py`: 18 frases naturais de
   "terminar um atendimento" em pt-BR ("posso finalizar aqui", "vou fechar o
   atendimento"...) — mais profundidade na família de confusáveis que já
   incluía "terminar"/"finalizar".

**Primeiro teste: as duas fontes nos DOIS classificadores.** Resultado
inesperado — `encerrar` disparou pra recall 0,940 (precisão 0,969, FP/h 0,00),
resolvendo o problema bimodal por completo. Mas `iniciar` **piorou**: recall
caiu de 0,63→0,46 a limiar 0,5 (e o teto em qualquer limiar caiu de 0,74→0,53).
Reproduzido num segundo treino idêntico — não era variância de inicialização.

**Diagnóstico:** `ENCERRAMENTO_SINONIMOS` é semanticamente sobre "fechar" — um
confusável de peso pra `encerrar`, mas ruído sem função pra `iniciar` (que já
tinha cobertura suficiente via `CONTEXTO_ATENDIMENTO` e a frase irmã). Aplicado
aos dois, só teve custo pra `iniciar`, sem contrapartida.

**Correção:** `ENCERRAMENTO_SINONIMOS` restrito a `libras_livre_encerrar`
(`negativos_para()` em `dados/frases.py`), fala real em português mantida nos
dois (é genérica, não específica de um confusável). Retreinado:

| | Rodada 2 (`iniciar` / `encerrar`) | Rodada 3 (`iniciar` / `encerrar`) |
|---|---|---|
| Recall (split sintético, limiar 0,5) | 0,630 / 0,400 | 0,690 / 0,580 |
| Precisão (split sintético, limiar 0,5) | 0,955 / 1,000 | 0,932 / 0,967 |
| Falso-positivo/hora (limiar 0,5) | 1,48 / 0,00 | 0,52 / 0,49 |

**Os dois melhoraram** em relação à Rodada 2 — `iniciar` ganhou recall E reduziu
falso-positivo (0,63→0,69 recall, 1,48→0,52 FP/h); `encerrar` saiu do buraco
bimodal (0,40→0,58 recall) sem voltar ao catastrófico da Rodada 1. Não chegou
aos 0,94 de recall do teste "nos dois classificadores" — essa era uma
configuração pior pra `iniciar`, não uma opção descartada por engano.

**Limiar por classificador**, não mais um `DEFAULT_THRESHOLD` único
(`THRESHOLD_INICIAR`/`THRESHOLD_ENCERRAR` em `OpenWakeWordDetector.kt`): 0,3 pra
`iniciar` (recall 0,73, FP/h 0,52 — mesmo FP que 0,4, mais recall) e 0,4 pra
`encerrar` (recall 0,58, FP/h 1,48 — mesmo recall que 0,3, menos FP).

**Ressalva importante sobre variância:** com ~1.000-1.700 clipes por
classificador e 3.000 passos, duas rodadas com a MESMA configuração de dados
já produziram números visivelmente diferentes (ver `encerrar` entre a rodada
"nos dois" e a rodada final, com o MESMO `negativos_para` pra `encerrar` nas
duas). Regenerar `dados/sintetizar.py` sem mudar nenhum código muda os clipes
específicos sorteados (sem seed fixa) — os números desta pasta são úteis pra
comparar ORDENS DE GRANDEZA entre rodadas, não pra tratar a segunda casa
decimal como precisa. Isto reforça, mais do que qualquer número individual, por
que a Fase 3 exige validação em hardware real antes de qualquer decisão de
produção.

## 5. Critério de aceite real

Continua sendo o da Fase 3 em `orquestracao-dialogo-audio-plano.md`, inalterado por
este documento: testar em hardware real, com o app em foreground, medindo
falso-positivo/falso-negativo — **incluindo confundir uma frase pela outra** e
confundir com menções soltas ao nome do produto — comparando objetivamente contra o
`SpeechRecognizerWakeWordDetector` atual (que continua sendo o motor ativo) antes de
trocar o motor padrão no `DialogOrchestrator`.

## 6. Parâmetros de treino (resumo)

Ver `wake-word-model/config/*.yaml` pros valores exatos e comentários linha a linha.
Resumo das reduções de escala em relação ao `custom_model.yml` recomendado
(`n_samples` 20.000+, `steps` até 50.000) — dataset pequeno, mais que isso é só
overfitting sem ganho real, e `auto_train` já faz early-stopping/checkpoint-averaging
por conta própria:

| Parâmetro | Recomendado | Usado aqui | Por quê |
|---|---|---|---|
| Clipes positivos (treino+validação) | 20.000+ | 550 por frase | tempo de síntese numa sessão sem GPU (~0,3 s/clipe) |
| `steps` | 50.000 | 3.000 | dataset pequeno; mais passos não ajudam sem mais dado |
| Negativos pré-computados | ACAV100M (~17 GB) | ACAV100M completo | achado inviável na Rodada 1 (§3) por suposição, não medição — revertido na Rodada 2 depois de medir ~14 MB/s de banda real (~20 min) |
| `max_negative_weight` | 1.500 | 3 | **medido, não estimado:** 1.500 é calibrado pro ACAV100M assumindo volume MUITO maior de negativos por batch do que positivos; mesmo com o ACAV100M ativo (Rodada 2), mantido baixo de propósito — mudar uma variável de cada vez (ver §3, §4). Sem ACAV100M (Rodada 1), 1.500 colapsava os dois classificadores pra saída constante (~0,045 pra qualquer entrada, TP=0 em tudo — ver `wake-word-model/config/*.yaml` pro comentário completo) |

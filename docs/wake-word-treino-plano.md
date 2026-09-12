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

## 3. O corte: sem o pool de negativos pré-computado (ACAV100M)

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
   frase". **O arquivo tem 17,3 GB.** Verificado por HTTP HEAD ao escrever isto —
   inviável nesta sessão (sem GPU, sem esse volume de banda/tempo disponível).

**Decisão: pular a fonte 2 nesta rodada**, com compensação parcial:

- RIR real (MIT, domínio público, ~8 MB, 270 respostas ao impulso) — reverberação
  de ambiente na augmentation.
- Ruído ambiente real (ESC-50, CC BY-NC 3.0, ~120 clipes de 5 s, resample pra
  16 kHz) — mixado como fundo na augmentation E usado diretamente como negativo
  extra ("não é fala nenhuma").
- O conjunto de validação de falso-positivo (também do próprio
  `openwakeword_features`, mas só 185 MB — esse sim baixado) mede falso-positivo
  em áudio genérico durante o treino/avaliação, mesmo sem entrar como dado de
  treino. **Recortado de ~11 h pras primeiras ~2,7 h** (`dados/baixar_ruido.py`,
  `MAX_FRAMES_VALIDACAO`): `train.py` monta uma janela deslizante de passo 1 sobre
  o arquivo inteiro pra checagem periódica de falso-positivo durante o treino — pro
  tamanho de janela do nosso modelo, as 11 h originais viram uma matriz de ~3,9 GB
  em RAM, repetida a cada checagem, e isso derrubou o processo por OOM nesta sessão
  (12 CPUs, 15 GB de RAM, compartilhados com o resto do desktop). Mais uma
  compensação de recursos, não de metodologia — aumentar de volta é só mudar a
  constante numa máquina com mais RAM disponível.

**Isto é o maior fator de risco do classificador resultante.** Sem a fonte 2, o
modelo nunca vê a enorme variedade de "áudio do cotidiano" que não é a frase — só
os confusáveis que pensamos em escrever à mão. Falso-positivo em situações não
antecipadas é esperado até que a Fase 3 (validação em hardware real) meça o
contrário. Ver `wake-word-model/README.md` "O que fica de fora desta rodada" pra
como isso se conecta ao critério de aceite.

**Confirmado, não só previsto** (treino de 2026-09-12, ver §4 abaixo pros números
completos): os dois classificadores aprenderam a distinguir a frase-alvo dos
confusáveis (84%/56% de recall, 91%/77% de precisão no split sintético — não é um
classificador quebrado), mas erram entre **103 e 280 falsos positivos por hora**
no áudio genérico de validação — 500 a 1.400× o alvo de 0,2/h do config. Exatamente
o padrão de falha esperado quando o modelo nunca viu "áudio do cotidiano" em
volume: ele aprendeu bem a tarefa que ensinamos, mas essa tarefa não cobre o que o
mundo real vai jogar nele.

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

### Resultado medido (treino de 2026-09-12)

| | `libras_livre_iniciar` | `libras_livre_encerrar` |
|---|---|---|
| Recall (split sintético) | 0,840 | 0,560 |
| Precisão (split sintético) | 0,913 | 0,767 |
| Acurácia (split sintético) | 0,886 | 0,710 |
| Falso-positivo/hora (áudio genérico) | 103,4 | 280,1 |
| Alvo de falso-positivo/hora (config) | 0,2 | 0,2 |

Os dois classificadores **aprenderam a tarefa que ensinamos** — distinguir a frase-alvo
dos confusáveis que escrevemos e da frase irmã, com recall e precisão razoáveis pra
um v1 com ~550 clipes por classe. Não é um treino quebrado (compare com o
`max_negative_weight: 1500` original, §6: aquele sim colapsava pra saída
constante, TP=0 em tudo). O problema é o previsto no §3: **500 a 1.400× o alvo de
falso-positivo** em áudio genérico, porque o modelo nunca viu esse tipo de áudio em
volume — só os confusáveis manuscritos. `libras_livre_encerrar` saiu pior nos dois
eixos (menos recall, mais falso-positivo), consistente com seus confusáveis terem
mais sobreposição fonética com fala comum em pt-BR ("terminar", "finalizar") do que
os de `libras_livre_iniciar`.

**Não wireado como motor padrão.** `SpeechRecognizerWakeWordDetector` continua
ativo — ver `docs/orquestracao-dialogo-audio-plano.md` Fase 3, critério de sucesso.
Este resultado é o que a Fase 3 pede pra comparar contra ele, não um substituto
pronto: nesta forma, o motor `openWakeWord` dispararia constantemente em qualquer
ambiente com fala de fundo. Próximo passo mais direto pra reduzir o
falso-positivo, nesta ordem: (1) o pool ACAV100M (§3) — é literalmente pra isto que
ele existe; (2) mais confusáveis/contexto pt-BR manuscritos, principalmente pros
que `libras_livre_encerrar` erra; (3) só depois disso, testar em hardware real.

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
| Negativos pré-computados | ACAV100M (~17 GB) | nenhum — só sintético + ESC-50 | inviável nesta sessão, ver §3 |
| `max_negative_weight` | 1.500 | 3 | **medido, não estimado:** 1.500 é calibrado pro ACAV100M (milhões de negativos contra poucos positivos); com `feature_data_files` vazio (linha acima) e classes na mesma ordem de grandeza, 1.500 colapsou os dois classificadores pra saída constante (~0,045 pra qualquer entrada, TP=0 em tudo — ver `wake-word-model/config/*.yaml` pro comentário completo) |

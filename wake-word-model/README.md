# Wake Word Model — Libras Livre

Terceira trilha de **IA** do projeto. Treina os dois classificadores locais que
disparam a sessão de atendimento por voz: **"Libras Livre, iniciar"** e
**"Libras Livre, encerrar"**. Produz dois `.onnx`, um por frase, que o
[`../mobile-app-companion`](../mobile-app-companion) carrega via
`OpenWakeWordDetector.kt` (motor `openWakeWord`, já integrado — ver
`../mobile-app-companion/app/src/main/java/.../libras/audio/OpenWakeWordDetector.kt`).

Irmã de [`../computer-vision-model`](../computer-vision-model) (vídeo → glosa) e
[`../contextualization-model`](../contextualization-model) (glosa → português): esta
trilha é áudio → "é a wake word ou não", rodando continuamente no mic do celular.

```
  wake-word-model/                    mobile-app-companion/
  fala -> "é a wake word?"            OpenWakeWordDetector.kt
  libras_livre_{iniciar,encerrar}.onnx  -->  abre/fecha a sessão de atendimento
```

## Por que existe

O app já tem um motor de wake word funcionando —
`SpeechRecognizerWakeWordDetector` (escuta contínua via `android.speech.SpeechRecognizer`)
— mas ele depende da API de reconhecimento de fala do Android (historicamente
depende de rede em muitos aparelhos) e não é keyword-spotting de verdade. O motor
local de verdade (`OpenWakeWordDetector`, ONNX Runtime, offline) já está implementado
e esperava só os dois classificadores treinados. Ver
`docs/orquestracao-dialogo-audio-plano.md` Fase 3 e §8 item 1.

**Esta pasta treinou os dois classificadores em três rodadas (2026-09-12).** A
Rodada 1 pulou o pool de negativos ACAV100M (achou 17 GB inviável sem medir) e
saiu com falso-positivo de 103–280/h. A Rodada 2 mediu a banda real (~14 MB/s,
~20 min pro arquivo inteiro), baixou o ACAV100M e derrubou isso pra 1,48/0,00
por hora — mas `encerrar` ficou com recall preso em ~0,40 em qualquer limiar
(scores bimodais, não questão de calibração). A Rodada 3 acrescentou fala real
em português (MLS, 600 clipes) e mais confusáveis manuscritos específicos de
"encerrar" — resultado final: **recall 0,69/0,58, falso-positivo 0,52/0,49 por
hora** (limiar recalibrado por classificador). Ver `resultados/*/relatorio.md`
e `../docs/wake-word-treino-plano.md` §3/§4 pro histórico completo, inclusive
uma ressalva sobre variância entre rodadas que vale ler antes de comparar
números com a próxima rodada.
`SpeechRecognizerWakeWordDetector` continua sendo o motor ativo até isso ser
validado em hardware real.

## Estado

| Etapa | Estado |
|---|---|
| Motor Android (`OpenWakeWordDetector.kt`, `com.rementia.openwakeword.lib`) | pronto — não ativo |
| Modelos fixos do openWakeWord (mel-spectrogram, embedding) | baixados por `download-assets.sh` |
| Dataset sintético pt-BR (esta pasta) | pronto (`dados/sintetizar.py`) — ~550-850 clipes/classe/frase (varia por fonte, ver `dados/frases.py`) |
| Pool de negativos ACAV100M (~17 GB) | ✅ baixado e em uso (`dados/baixar_ruido.py`) |
| Fala real em português (MLS, negativo extra) | ✅ baixada e em uso (`dados/baixar_fala_pt.py`) — resolveu o recall bimodal de `encerrar` |
| Classificadores `.onnx` treinados | ✅ treinados (2026-09-12, Rodada 3) — recall 0,69/0,58, precisão 0,93/0,97 |
| Falso-positivo em áudio genérico | ✅ **0,52/h (`iniciar`) e 0,49/h (`encerrar`)** — ainda acima do alvo 0,2/h do config, mas ordens de grandeza melhor que a Rodada 1 |
| Limiar de decisão | ✅ por classificador — `THRESHOLD_INICIAR=0,3` / `THRESHOLD_ENCERRAR=0,4` em `OpenWakeWordDetector.kt` |
| Validação em ambiente ruidoso real | **pendente** — critério de sucesso da Fase 3 |
| Troca do motor padrão no `DialogOrchestrator` | **pendente**, depende da validação acima |

## Duas decisões que valem registrar antes de rodar

Ver `../docs/wake-word-treino-plano.md` pro detalhe de cada uma:

1. **O pipeline oficial de treino do openWakeWord não serve pt-BR "de fábrica".** O
   notebook `automatic_model_training.ipynb` usa o `piper-sample-generator`
   (checkpoint VITS multi-falante com amostragem de speaker embedding) — só existe
   pronto em inglês, alemão, francês e holandês. Não existe checkpoint pt-BR
   compatível publicado em lugar nenhum (verificado nos releases oficiais do
   `rhasspy/piper-sample-generator` e na busca do HuggingFace ao escrever isto).
   **Substituto usado aqui:** 6 vozes Piper pt-BR de comunidade (mesmas do TTS do
   app, motor sherpa-onnx) com timbre/velocidade variados por chamada
   (`dados/sintetizar.py`). Menos diverso que um gerador multi-falante de verdade
   (identidade vocal discreta, não contínua), mas é fala real em pt-BR — o que o
   pipeline oficial não oferece pronto.
2. **O pool de negativos pré-computado do openWakeWord (ACAV100M, ~17 GB) já está
   em uso** (`dados/baixar_ruido.py` baixa por padrão; `--pular-acav100m` pra quem
   preferir não). Foi pulado numa primeira tentativa por achar 17 GB inviável sem
   medir — a banda real da sessão (~14 MB/s) dá uns 20 min, nada perto de
   inviável. É o principal responsável por derrubar o falso-positivo de 103–280/h
   pra abaixo de 1/h. Continua rodando junto RIR real (MIT, ~8 MB) e ruído
   ambiente real (ESC-50, ~120 clipes).
3. **Fala real em português (MLS, `dados/baixar_fala_pt.py`) resolveu o recall
   bimodal de `libras_livre_encerrar`**, que o ACAV100M sozinho não tinha corrigido
   (recall preso em ~0,40 em QUALQUER limiar — sinal de falta de diversidade, não
   de calibração). **Só entra pra `encerrar`**, não pra `iniciar`: testado nos dois
   primeiro, e `iniciar` piorou (recall 0,63→0,46) sem ganho compensador — a
   sobreposição semântica entre os confusáveis novos ("fechar o atendimento"...) e
   `encerrar` é o que fazia diferença, e isso não existe pra `iniciar`. Ver
   `dados/frases.py` (`negativos_para`) e `docs/wake-word-treino-plano.md` §3
   Rodada 3.

## Como rodar

```bash
./treinar.sh
```

Faz tudo: cria `.venv`, clona o `openWakeWord` (fonte — o pacote do PyPI não traz
`train.py`), baixa as vozes/ruído, sintetiza os clipes, treina os dois
classificadores e escreve os relatórios. Idempotente por etapa — reexecutar pula o
que já existe. Cada etapa também roda isolada, se preferir:

```bash
source .venv/bin/activate       # depois da primeira vez que ./treinar.sh criar o venv
bash dados/baixar_vozes.sh      # 6 vozes Piper pt-BR, ~130 MB
python dados/baixar_ruido.py    # RIR + ruído ambiente + validação de FP + ACAV100M, ~17,5 GB
python dados/baixar_fala_pt.py  # fala real em português (MLS), ~157 MB
python dados/sintetizar.py --output-dir ./treino
python _vendor/openWakeWord-src/openwakeword/train.py \
    --training_config config/libras_livre_iniciar.yaml --augment_clips --train_model
python avaliar.py --modelo libras_livre_iniciar
```

Repita as três últimas linhas trocando `iniciar` por `encerrar`.

### Levar pro app

```bash
mkdir -p ../mobile-app-companion/app/src/main/assets/wakeword
cp treino/libras_livre_iniciar.onnx treino/libras_livre_encerrar.onnx \
   ../mobile-app-companion/app/src/main/assets/wakeword/
```

Os dois arquivos já são esperados nesses nomes exatos por `OpenWakeWordDetector.kt`
(`MODEL_INICIAR`/`MODEL_ENCERRAR`). Depois, trocar qual `WakeWordDetector` o
`CameraViewModel` instancia (ver `docs/orquestracao-dialogo-audio-plano.md` Fase 3,
último item) — só depois de validar em hardware real (próxima seção).

## O que fica de fora desta rodada

Lido junto com `resultados/*/relatorio.md` (números medidos, não estimados):

- **Vozes limitadas a 6 identidades vocais discretas**, não a variação contínua de
  um gerador multi-falante de verdade. Sotaques/timbres fora dessas 6 vozes são uma
  incógnita.
- **Recall ainda moderado** (0,69/0,58 no split sintético, limiar recalibrado por
  classificador) — bem melhor que a Rodada 2, mas pode significar que a pessoa
  precisa repetir a frase de vez em quando.
- **Variância entre rodadas é real e não pequena.** Sem seed fixa em
  `dados/sintetizar.py`/`train.py`, duas rodadas com a MESMA configuração de dados
  já produziram recalls visivelmente diferentes pro mesmo classificador (ver
  `docs/wake-word-treino-plano.md` §3 Rodada 3). Trate estes números como ordem de
  grandeza, não como precisos até a segunda casa decimal.
- **Nenhum teste com pessoa de verdade, mic real dos óculos/celular, ou ambiente de
  balcão.** Os relatórios desta pasta são só sobre o próprio dataset sintético — o
  mesmo aviso que `../contextualization-model/README.md` faz sobre o corpus dele
  vale aqui: número medido no gerador sintético não é evidência de campo.

**O portão de aceite real é o da Fase 3** em
`../docs/orquestracao-dialogo-audio-plano.md`: testar em hardware, comparando
objetivamente contra o `SpeechRecognizerWakeWordDetector` atual (falso-positivo,
falso-negativo, latência, funciona offline) antes de trocar o motor padrão. Se o
recall não for suficiente nesse teste, as alavancas mais óbvias, nesta ordem: (1)
gravar confusáveis/contexto reais (voz humana, não só sintética/MLS), (2) mais
`n_samples`/`steps`, (3) revisitar `max_negative_weight`/`batch_n_per_class.ACAV100M_sample`.

## Estrutura

```
wake-word-model/
├── config/
│   ├── libras_livre_iniciar.yaml    config de treino (formato custom_model.yml do openWakeWord)
│   └── libras_livre_encerrar.yaml
├── dados/
│   ├── frases.py                    frases-alvo, confusáveis e contexto pt-BR (só texto)
│   ├── baixar_vozes.sh              baixa as 6 vozes Piper pt-BR (sherpa-onnx)
│   ├── baixar_ruido.py              baixa RIR + ruído de fundo + validação de FP + ACAV100M
│   ├── baixar_fala_pt.py            baixa fala real em português (MLS) — negativo extra
│   └── sintetizar.py                gera os clipes .wav (substitui --generate_clips)
├── _vendor/
│   ├── openWakeWord-src/            clone do repositório oficial (train.py, data.py) — gitignored
│   └── piper-sample-generator-stub/ stub só pra satisfazer um import incondicional do train.py
├── avaliar.py                       mede o .onnx exportado, escreve o relatório
├── treinar.sh                       orquestra tudo, do zero ao .onnx
├── vozes/, ruido/, treino/          baixados/gerados — gitignored, exceto treino/.gitkeep
└── resultados/
    ├── libras_livre_iniciar/relatorio.md
    └── libras_livre_encerrar/relatorio.md
```

## Documentação

| Documento | O quê |
|---|---|
| [`../docs/wake-word-treino-plano.md`](../docs/wake-word-treino-plano.md) | **o porquê** — a lacuna do piper-sample-generator em pt-BR, o corte do ACAV100M, os limiares |
| [`../docs/orquestracao-dialogo-audio-plano.md`](../docs/orquestracao-dialogo-audio-plano.md) | Fase 3 (motor de wake word) e §8 item 1 — decisão original do motor |
| [`.../libras/audio/OpenWakeWordDetector.kt`](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/audio/OpenWakeWordDetector.kt) | como o app consome os `.onnx` |

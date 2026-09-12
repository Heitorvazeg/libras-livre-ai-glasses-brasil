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

**Esta pasta treinou os dois classificadores (2026-09-12), mas o resultado ainda
não fecha a pendência**: falso-positivo de 103–280/h em áudio genérico (alvo do
config: 0,2/h) — ver `resultados/*/relatorio.md` e
`../docs/wake-word-treino-plano.md` §4. `SpeechRecognizerWakeWordDetector`
continua sendo o motor ativo até isso ser resolvido (mais dados de negativo,
principalmente o pool ACAV100M — ver §3 do plano) e validado em hardware real.

## Estado

| Etapa | Estado |
|---|---|
| Motor Android (`OpenWakeWordDetector.kt`, `com.rementia.openwakeword.lib`) | pronto — não ativo |
| Modelos fixos do openWakeWord (mel-spectrogram, embedding) | baixados por `download-assets.sh` |
| Dataset sintético pt-BR (esta pasta) | pronto (`dados/sintetizar.py`) — ~550 clipes/classe/frase |
| Classificadores `.onnx` treinados | ✅ treinados (2026-09-12) — recall 0,84/0,56, precisão 0,91/0,77 |
| Falso-positivo em áudio genérico | ❌ **103–280/h medidos, alvo 0,2/h** — não aceitável assim (ver `resultados/*/relatorio.md`) |
| Mais dados de negativo (ACAV100M ou equivalente) | **pendente** — próximo passo mais direto pra reduzir o falso-positivo |
| Validação em ambiente ruidoso real | **pendente** — critério de sucesso da Fase 3 |
| Troca do motor padrão no `DialogOrchestrator` | **pendente**, depende das duas validações acima |

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
2. **O pool de negativos pré-computado do openWakeWord (ACAV100M, ~17 GB) foi
   pulado nesta rodada.** É o principal responsável pela robustez contra
   falso-positivo em áudio genérico no pipeline oficial, mas 17 GB é inviável numa
   sessão sem GPU e sem esse tipo de banda disponível. Compensação parcial: RIR real
   (MIT, ~8 MB) + ruído ambiente real (ESC-50, ~120 clipes) + confusáveis pt-BR
   escritos à mão, incluindo a frase irmã como negativo de cada classificador (ver
   `dados/frases.py`). **Isto é o maior fator de risco do modelo resultante** — ver
   "O que fica de fora desta rodada" abaixo.

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
python dados/baixar_ruido.py    # RIR + ruído ambiente + validação de FP, ~200 MB
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
- **Sem o pool de negativos do ACAV100M.** O classificador nunca viu "áudio do
  cotidiano" em volume — só os confusáveis que escrevemos à mão + um punhado de
  ruído ambiente (ESC-50) + a frase irmã. **Medido, não só previsto:** 103–280
  falsos-positivos por hora em áudio genérico (alvo do config: 0,2/h) — ver
  `resultados/*/relatorio.md`.
- **Nenhum teste com pessoa de verdade, mic real dos óculos/celular, ou ambiente de
  balcão.** Os relatórios desta pasta são só sobre o próprio dataset sintético — o
  mesmo aviso que `../contextualization-model/README.md` faz sobre o corpus dele
  vale aqui: número medido no gerador sintético não é evidência de campo.

**O portão de aceite real é o da Fase 3** em
`../docs/orquestracao-dialogo-audio-plano.md`: testar em hardware, comparando
objetivamente contra o `SpeechRecognizerWakeWordDetector` atual (falso-positivo,
falso-negativo, latência, funciona offline) antes de trocar o motor padrão. Se o
resultado não bater esse critério, os alavancas mais óbvias pra melhorar, nesta
ordem, são: (1) baixar o pool ACAV100M numa máquina com mais banda/disco e apontar
`feature_data_files` pra ele, (2) gravar confusáveis/contexto reais (voz humana, não
só sintética), (3) aumentar `n_samples`/`steps` no config.

## Estrutura

```
wake-word-model/
├── config/
│   ├── libras_livre_iniciar.yaml    config de treino (formato custom_model.yml do openWakeWord)
│   └── libras_livre_encerrar.yaml
├── dados/
│   ├── frases.py                    frases-alvo, confusáveis e contexto pt-BR (só texto)
│   ├── baixar_vozes.sh              baixa as 6 vozes Piper pt-BR (sherpa-onnx)
│   ├── baixar_ruido.py              baixa RIR + ruído de fundo + validação de FP
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

# Libras Livre

**Óculos inteligentes que traduzem Libras para fala, em tempo real.**

Uma pessoa surda sinaliza em Libras diante de óculos Ray-Ban Meta. O celular
pareado reconhece cada sinal, monta uma frase em português e a fala em voz alta,
para que quem não conhece a língua entenda. O atendente responde por voz, e a
resposta é transcrita de volta. Pensado para um ponto de atendimento fixo — um
posto de saúde, uma recepção, um balcão institucional.

Equipe 3G1B · Programa AI Glasses Brasil · Trilha Acessibilidade

---

## A premissa que molda todas as decisões

O dispositivo é **institucional, não pessoal**. Os óculos ficam com o atendente e
atendem dezenas de pessoas surdas diferentes por dia. Não existe calibração por
usuário: o modelo precisa funcionar com alguém que nunca viu, desde o primeiro
sinal.

Consequência técnica direta: toda avaliação de reconhecimento neste repositório
deixa uma **pessoa inteira** fora do treino (*leave-one-signer-out*). Um número
medido com a pessoa de teste presente no treino não significa nada aqui.

O raciocínio completo está em [`docs/libras-livre-arquitetura.md`](./docs/libras-livre-arquitetura.md).

---

## Como funciona

```
  pessoa sinaliza
        |
        v
  óculos (câmera)  --vídeo HEVC-->  celular  -->  "Estou com dor de cabeça" (voz)
                                       |
                    landmarks -> fronteiras -> sinal -> glosas -> frase
```

Os óculos são apenas a câmera e o microfone. Todo o processamento acontece no
celular, **on-device**, sem depender de internet — o balcão de atendimento é
justamente onde a conexão costuma faltar.

---

## As três trilhas

O sistema é montado por três trilhas independentes, que se encontram em dois
artefatos `.tflite` consumidos pelo app.

```
  computer-vision-model/        contextualization-model/       mobile-app-companion/
  vídeo -> glosa                glosa -> português             o app (Kotlin/Android)
  (Python: mediapipe, torch)    (Python: torch, transformers)  roda os dois modelos
          |                              |                              ^
          +-- sinal_classifier.tflite ---+-- modelo_contextualizacao ----+
                                              .tflite
```

| Trilha | O que entrega | Stack |
|---|---|---|
| [`computer-vision-model/`](./computer-vision-model) | classificador de sinal isolado (`.tflite`) | MediaPipe, PyTorch, ai-edge-torch |
| [`contextualization-model/`](./contextualization-model) | tradutor glosa → português (`.tflite`) | PyTorch, Transformers |
| [`mobile-app-companion/`](./mobile-app-companion) | o aplicativo Android | Kotlin, Jetpack Compose, LiteRT |

As duas trilhas de Python têm **ambientes virtuais separados** e dependências
incompatíveis entre si (MediaPipe fixa NumPy 1.x; Transformers quer 2.x). Nunca
as instale no mesmo `venv`.

### Divisão de responsabilidade

| Pergunta | Quem responde |
|---|---|
| *Que sinal é este trecho?* | `computer-vision-model` (Python), via `.tflite` |
| *Onde um sinal termina e outro começa?* | o app (Kotlin), por `SignBoundaryDetector` |
| *Como essas glosas viram uma frase em português?* | `contextualization-model` (Python), via `.tflite` |
| *Como a frase vira voz, e a resposta vira texto?* | o app (Kotlin), via Piper/sherpa-onnx e Vosk |
| *Como a resposta do atendente vira Libras?* | o app (Kotlin), via o player VLibras numa WebView |

---

## Começando do zero

### Pré-requisitos

| Para | Você precisa de |
|---|---|
| Qualquer trilha de Python | Python 3.11 (o MediaPipe 0.10.14 não suporta 3.12+) |
| Trilha de visão | ~25 GB de disco livre para os vídeos públicos |
| Treino de modelo | GPU (Colab/Kaggle servem; os notebooks estão prontos) |
| Trilha mobile | Android Studio Narwhal (2025.1.1)+, JDK 17, Android SDK 36 |
| Build do app | um *personal access token* do GitHub, para o Maven privado da Meta |
| Executar nos óculos | Ray-Ban Meta com *Developer Mode* ativo (opcional — há um simulador) |

### Caminho mais curto: rodar o app

Não exige treinar nada. Os modelos pesados são baixados por script; o app cai em
fallbacks documentados para o que ainda não existe.

```bash
git clone <este-repositório> && cd libras-livre-ai-glasses-brasil/mobile-app-companion
echo "github_token=SEU_TOKEN" >> local.properties
./download-assets.sh          # MediaPipe, TTS, STT, wake word e avatar — ~125 MB
# abra no Android Studio, sincronize o Gradle e execute
```

Sem óculos físicos, use o `MockDeviceKit` (menu de debug do app) para injetar um
vídeo de Libras como se viesse da câmera. Detalhes em
[`mobile-app-companion/README.md`](./mobile-app-companion/README.md).

### Caminho completo: reproduzir os modelos

```bash
# 1. Trilha de visão — baixar vídeos, extrair landmarks, treinar, exportar
cd computer-vision-model/PoC && python3.11 -m venv .venv311 && source .venv311/bin/activate
pip install -r requirements.txt
cd ../datasets && python ingest.py --reps 1     # clipes públicos, sem credencial do Kaggle
cd ../PoC && python src/extract.py              # vídeo -> landmarks (.npy), horas de CPU
cd ../treino && python selftest.py && python treinar.py

# 2. Trilha de contextualização — ambiente separado
cd ../../contextualization-model && python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python corpus/gerar.py && python modelo/treinar.py
python exportacao/para_tflite.py --experimento v2
```

Cada trilha tem um `selftest.py` que valida o encanamento em segundos, com dados
sintéticos, antes de qualquer execução cara. Rode-o primeiro.

---

## Estado atual

Números medidos, com as ressalvas que os tornam legíveis.

### Reconhecimento de sinal

| Modelo | LOSO signer-independent | Parâmetros |
|---|---|---|
| Chance aleatória (20 sinais) | 5,0% | — |
| Baseline DTW 1-NN (PoC) | 70,0% | — |
| **ST-GCN + ossos + z — modelo de entrega** | **94,6% / 94,9%** | **0,47M** |
| ResNet-18 + imputação | 95,1% | 11,25M |
| Literatura (mesma base, mesmo protocolo) | 93–94% | — |

O ST-GCN foi escolhido em 2026-09-11: empatou com a ResNet usando 24× menos
parâmetros ([`docs/decisao-arquitetura-modelo.md`](./docs/decisao-arquitetura-modelo.md)).

Duas ressalvas que precisam acompanhar qualquer citação desses números:

1. **A variância entre execuções é de ~1,7 ponto.** Diferenças menores que ~2
   pontos são ruído, não resultado.
2. **Tudo isso é vídeo de estúdio**, frontal e com enquadramento controlado. É um
   teto otimista. O número de balcão só sai com coleta própria.

### O que está pronto, e o que não está

| Componente | Estado |
|---|---|
| App base: conexão com os óculos, câmera, gravação | pronto (herdado do sample da Meta) |
| Extração de landmarks on-device (Pose + Hands, 57 pontos) | pronto |
| Normalização e imputação de lacunas, com paridade testada contra o Python | pronto |
| Detecção de fronteiras entre sinais | implementada, **parâmetros não calibrados** |
| Classificação de sinal no app | **placeholder** — o export TFLite do ST-GCN não existe |
| Contextualização glosa → português | pronta e integrada (`.tflite` sob guarda, template como piso) |
| Fala (TTS Piper/sherpa-onnx) e transcrição (Vosk pt-BR) | prontas |
| Avatar em Libras para a pessoa surda (VLibras em WebView) | implementado; **depende de rede** e não medido em aparelho ARM |
| Wake word "Libras Livre, iniciar/encerrar" | **fallback ativo** — os classificadores pt-BR precisam ser treinados |
| Coleta própria no cenário de balcão | pendente |
| Validação de vocabulário com consultor de Libras | pendente |

O maior bloqueio técnico é o **export do ST-GCN para TFLite**: `treino/exportar.py`
cobre apenas a ResNet-18, e portar o cálculo de ossos para dentro do grafo é
trabalho novo, não uma flag.

---

## Onde ler mais

| Se você quer | Leia |
|---|---|
| Entender o projeto inteiro rapidamente | [`docs/CONTEXTO.md`](./docs/CONTEXTO.md) |
| Navegar a documentação de decisões | [`docs/README.md`](./docs/README.md) |
| Rodar ou entender a trilha de visão | [`computer-vision-model/README.md`](./computer-vision-model/README.md) |
| Rodar ou entender a trilha de contextualização | [`contextualization-model/README.md`](./contextualization-model/README.md) |
| Buildar e rodar o app | [`mobile-app-companion/README.md`](./mobile-app-companion/README.md) |
| Ver como a IA está plugada no app | [`.../cameraaccess/libras/README.md`](./mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/README.md) |

---

## Dados e licenças

Os vídeos de treino vêm de bases públicas de Libras — MINDS-Libras, V-LIBRASIL,
MALTA-LIBRAS — e de ASL (WLASL). **Nada disso é versionado neste repositório:** o
que fica no git é a receita para reproduzir o dataset.

As licenças não são uniformes e não são todas permissivas. A V-LIBRASIL é
CC BY-NC-ND 4.0 — não comercial, sem derivações. O uso atual está enquadrado como
pesquisa, para o hackathon; **para produto, nada disso está resolvido**. O
enquadramento completo está em
[`docs/decisao-datasets-e-licencas.md`](./docs/decisao-datasets-e-licencas.md), e
precisa ser lido antes de qualquer publicação ou uso comercial.

## Licença do código

O app deriva do sample oficial de *Camera Access* do Meta Wearables Device Access
Toolkit, licenciado pela Meta Platforms nos termos do arquivo LICENSE do
repositório de origem.

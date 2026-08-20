# Computer Vision Model — Libras Livre

> Trilha de **IA** do Libras Livre. Transforma vídeos de sinais de Libras em um
> classificador `.tflite` leve, que roda **on-device** dentro do app de óculos
> inteligentes (`../mobile-app-companion`).

---

## 🧪 Comece pela PoC — [`PoC/`](./PoC)

**Antes de treinar o `.tflite`, uma PoC decide se vale a pena.** A pergunta que
ela responde, com o mínimo de esforço e critério de decisão definido de antemão:

> MediaPipe Holistic + um classificador simples reconhecem um vocabulário fechado
> de Libras **generalizando entre pessoas diferentes** (signer-independent), na
> distância e ângulo de um atendimento de balcão?

Isso é **requisito de produto**, não detalhe de avaliação: os óculos são
institucionais e atendem uma pessoa nova a cada sessão — o modelo nunca vê quem
está à frente da câmera (ver [`docs/libras-livre-arquitetura.md`](../docs/libras-livre-arquitetura.md), §4.3).

| | **PoC** (`PoC/`) | **Pipeline principal** (este diretório) |
|---|---|---|
| Objetivo | validar a hipótese signer-independent | produzir o `.tflite` de produção |
| Landmarks | MediaPipe **Holistic** (mãos + pose do tronco) | MediaPipe **Hands** (21 pontos) |
| Modelo | baseline **DTW** (1-NN), sem treino | classificador raso → temporal |
| Avaliação | **leave-one-signer-out** (obrigatório) | split treino/val |
| Saída | acurácia + decisão ir/não-ir | `sinal_classifier.tflite` |

➡️ **O pipeline abaixo só vale o investimento depois que a PoC der sinal verde
(≥ 80%).** Detalhes e passo a passo em [`PoC/README.md`](./PoC/README.md).

---

## 1. Contexto — o que este projeto resolve

**Libras Livre** é um sistema de acessibilidade para óculos inteligentes (Ray-Ban
Meta): a câmera dos óculos vê uma pessoa sinalizando em Libras, e o sistema
traduz esses sinais em fala/texto para quem não conhece a língua — pensado para
um cenário de atendimento (ex.: um posto de saúde).

O projeto tem **duas trilhas** que se encontram em um único arquivo:

```
   TRILHA IA (este projeto, Python)          TRILHA MOBILE (../mobile-app-companion, Kotlin)
   vídeos → landmarks → modelo  ──►  sinal_classifier.tflite  ──►  inferência em tempo real nos óculos
```

Este repositório é a **trilha de IA**. A entrega é o `sinal_classifier.tflite`,
copiado depois para `../mobile-app-companion/app/src/main/assets/`. Tudo aqui
existe para produzir esse arquivo com qualidade.

---

## 2. Conceitos essenciais da IA

Esta é a parte mais importante de entender. São 4 decisões que definem todo o resto.

### 2.1 Por que reconhecer *sinais isolados* (palavras), e não frases

O classificador reconhece **uma palavra por vez**, não a frase inteira como uma
classe única. Motivos:

- **Linguisticamente correto:** Libras é composta por sinais; uma frase é uma
  sequência deles, não um bloco atômico.
- **Escalável:** um sinal ("dor") serve em várias frases ("dor de cabeça", "dor
  de barriga"). Com N sinais você cobre muito mais que N frases.
- **Alinhado aos datasets reais:** V-LIBRASIL, MINDS-Libras, WLASL — todos são
  organizados por **palavra isolada**. Operar na mesma unidade que eles permite
  aproveitá-los como fonte de dados, não só como referência.

A montagem da frase final ("dor" + "cabeça" → "Estou com dor de cabeça") acontece
**depois, no app**, por uma tabela de combinações conhecidas — não por geração
gramatical (gloss-to-text), que segue sem solução pronta.

### 2.2 Por que *landmarks*, e não os pixels do vídeo

O modelo **não vê a imagem**. Antes de qualquer treino, cada frame passa pelo
**MediaPipe Hands**, que devolve as **coordenadas de 21 pontos da mão** (juntas
dos dedos, pulso), cada um com `(x, y, z)`:

```
21 pontos × 3 coordenadas = 63 números por frame, por mão
```

Isso é a "esqueletização" do gesto. Por que é uma ideia tão boa:

- **Invariante ao que não importa:** cor da pele, roupa, fundo, iluminação —
  nada disso chega ao modelo. Só a **geometria da mão**.
- **Modelo minúsculo:** classificar 63 números é ordens de grandeza mais barato
  que classificar uma imagem. Cabe num `.tflite` que roda em tempo real no celular.
- **Menos dados necessários:** o MediaPipe já resolveu a parte difícil (achar a
  mão); você só precisa ensinar a *forma* de cada sinal, não a visão inteira.

O código dessa etapa está em `src/data/landmark_extraction.py`.

### 2.3 Do simples ao robusto — Fase A e Fase B

O modelo evolui em duas fases (nunca reescrito, só trocado):

| | **Fase A — PoC** | **Fase B — vocabulário fechado** |
|---|---|---|
| Vocabulário | 1–2 sinais | ~10 sinais |
| Entrada | **média** dos landmarks na janela → vetor fixo de 63 | **sequência temporal** completa |
| Modelo | classificador raso (`Dense → softmax`) | temporal (1D-CNN / recorrente) |
| Ideia | "que *forma* a mão faz" | "que *movimento* a mão faz ao longo do tempo" |
| Arquivo | `src/models/shallow.py` | `src/models/temporal.py` |

A Fase A joga fora o tempo (tira a média) — funciona para sinais que são
essencialmente uma pose. A Fase B mantém o tempo — necessária para sinais que
**são um movimento** (a maioria). Você começa na A para provar o pipeline
inteiro ponta a ponta, depois liga a B trocando `fase: B` no `config.yaml`.

**Transfer learning (Fase B):** não existe modelo pronto de Libras, mas existem
modelos pré-treinados em outras línguas de sinais por esqueleto (ex.: **SAM-SLR**).
A estratégia é reaproveitar o *backbone* temporal e treinar só a cabeça de
classificação com seus sinais — reduz a quantidade de dados necessária por classe.
⚠️ *Licenciamento:* WLASL é C-UDA (uso acadêmico, sem comercial); MINDS-Libras e
V-LIBRASIL exigem autorização. Verifique antes de usar.

### 2.4 Por que `.tflite` (e quantização)

O modelo roda **dentro do app, no celular, sem internet** (on-device / edge). Para
isso, o modelo Keras é convertido para **TensorFlow Lite** — um formato compacto,
otimizado para inferência em ARM. A **quantização** (Fase B em diante) reduz ainda
mais o tamanho e acelera, convertendo os pesos de `float32` para `int8`, com custo
mínimo de acurácia. Código em `src/export/to_tflite.py`.

### 2.5 O problema das fronteiras (onde este projeto *não* mexe)

"Quando um sinal acaba e outro começa" é o problema de **segmentação temporal**.
Decisão do projeto: isso é resolvido **no app** (Kotlin), por detecção de pausa/
movimento sobre os landmarks em tempo real — **não aqui**. Este projeto só
responde *"que sinal é este trecho já recortado"*. A divisão:

- **Python (aqui):** *o quê* é o gesto → classificador de palavra isolada.
- **Kotlin (app):** *onde* um gesto começa e termina → lógica das duas pausas.

---

## 3. Pipeline

```
data/raw/*.mp4              vídeos brutos (1 sinal isolado por arquivo)
     │   MediaPipe Hands  ── src/data/landmark_extraction.py
     ▼
data/landmarks/*.npy        sequências de 63 valores/frame, rotuladas
     │   montar X, y      ── src/data/dataset.py   (média=Fase A | sequência=Fase B)
     ▼
X, y
     │   treino           ── src/models/ + src/training/train.py
     ▼
models/*.keras
     │   export + quantiz.── src/export/to_tflite.py
     ▼
models/sinal_classifier.tflite   ──►  copiar para ../mobile-app-companion/app/src/main/assets/
```

---

## 4. Estrutura do projeto

```
computer-vision-model/
├── config.yaml            ⭐ vocabulário, caminhos e hiperparâmetros num lugar só
├── requirements.txt       mediapipe, opencv, tensorflow, pyyaml, scikit-learn
│
├── data/
│   ├── raw/               vídeos brutos            (não versionado)
│   └── landmarks/         .npy extraídos           (não versionado)
│
├── src/                   biblioteca (a lógica, testável)
│   ├── config.py          carrega o config.yaml num objeto tipado
│   ├── data/
│   │   ├── landmark_extraction.py   §2.2 — MediaPipe Hands
│   │   └── dataset.py               §2.3 — monta X, y (Fase A e B)
│   ├── models/
│   │   ├── shallow.py               Fase A — classificador raso
│   │   └── temporal.py              Fase B — temporal / transfer learning
│   ├── training/train.py            orquestra o treino
│   └── export/to_tflite.py          §2.4 — export + quantização
│
├── scripts/               pontos de entrada (finos), na ordem de execução
│   ├── 01_extract_landmarks.py
│   ├── 02_train.py
│   └── 03_export_tflite.py
│
└── models/                saída: sinal_classifier.tflite   (não versionado)
```

Padrão de projeto de ML: **biblioteca (`src/`) separada de execução (`scripts/`)**.
Os scripts só carregam o config e chamam a função certa; toda a lógica vive em
`src/`, reutilizável e testável. O `config.yaml` é o centro de controle — nenhum
módulo tem vocabulário ou hiperparâmetro hardcoded.

---

## 5. Como rodar

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 1) grave vídeos em data/raw (1 sinal isolado por arquivo — ver data/README.md)
python scripts/01_extract_landmarks.py   # data/raw      -> data/landmarks
python scripts/02_train.py               # data/landmarks -> models/*.keras
python scripts/03_export_tflite.py       # models/*.keras -> models/sinal_classifier.tflite
```

**Coleta (§1.1 do guia):** câmera frontal, ~1 m de distância (ponto de vista de
quem atende), 15–20 repetições por sinal, variando pessoa, luz e velocidade.

> **Estado atual:** a extração de landmarks, o modelo raso (Fase A) e o export
> `.tflite` já estão implementados. `dataset.py`, `temporal.py` e `train.py`
> estão como *stubs* (`NotImplementedError` com o passo documentado), porque
> dependem das suas decisões de rótulo e dos seus dados reais.

---

## 6. Checklist da trilha (guia §1.5)

- [ ] Vídeos gravados (ângulo frontal, 1 sinal por vídeo)
- [ ] Landmarks extraídos e dataset montado
- [ ] Modelo treinado (Fase A raso → Fase B temporal + transfer learning)
- [ ] Exportado para `.tflite`
- [ ] Quantizado (a partir da Fase B)
- [ ] `.tflite` copiado para `../mobile-app-companion/app/src/main/assets/`

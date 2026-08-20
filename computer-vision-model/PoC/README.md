# PoC — Reconhecimento Signer-Independent de Libras

> **Equipe 3G1B · Programa AI Glasses Brasil**
>
> Esta PoC existe para responder **uma única pergunta técnica** antes de investir
> tempo em qualquer outra parte do sistema (treino do `.tflite`, app, nuvem):

> **MediaPipe Holistic + um classificador simples conseguem reconhecer um
> vocabulário fechado de Libras, generalizando entre pessoas diferentes, na
> distância e ângulo de câmera de um atendimento de balcão?**

Todo o resto desta pasta existe para responder isso com o **mínimo de esforço** e
com um **critério de decisão definido antes de começar** (ver §6).

> ⚠️ **Relação com o resto de `computer-vision-model/`:** o pipeline principal
> (`../src`, `../scripts`, `config.yaml`) monta o classificador de produção com
> MediaPipe **Hands** e export `.tflite`. Esta PoC é **anterior** a isso: ela
> valida a hipótese com MediaPipe **Holistic** (mãos + pose do tronco) e um
> baseline **DTW**, sem treino de rede neural. Só faz sentido investir no pipeline
> completo depois que a PoC der sinal verde (§6.3).

---

## 1. O que está (e o que não está) nesta PoC

**Está:** vocabulário fechado de 10–15 sinais isolados · coleta com 5–8 pessoas
diferentes · extração de landmarks com Holistic · baseline DTW (1-NN) · avaliação
**leave-one-signer-out** · matriz de confusão · critério de ir/não-ir.

**Não está** (fica para depois, ver [`docs/libras-livre-arquitetura.md`](../../docs/libras-livre-arquitetura.md)): nuvem, deploy,
API · retrain contínuo · tela para a pessoa surda · núcleo offline · consentimento
institucional formal · contextualização sinal→frase · hardware final dos óculos
(qualquer câmera de celular na posição certa serve).

---

## 2. Vocabulário

- **10 a 15 sinais.** Priorizar sinais já planejados para o MVP de atendimento
  (saudação, pedir ajuda, marcar consulta, sim/não, dor…) — assim a PoC gera dado
  reaproveitável.
- Incluir de propósito sinais com **configurações de mão e movimentos variados**
  entre si — o objetivo é estressar o modelo, não facilitar o resultado.
- Evitar sinais que dependem de **expressão facial** para ter sentido (fora de
  escopo).
- **Definir a lista final com apoio de um profissional de Libras / pessoa surda
  consultora antes de gravar** — vocabulário errado invalida o resultado.

A lista fica em `config.yaml` (`vocabulario`).

---

## 3. Protocolo de coleta

| Item | Alvo |
|---|---|
| Participantes | **mín. 5, ideal 8** pessoas diferentes que sinalizam Libras, o mais diverso possível |
| Repetições | **5 por sinal, por pessoa** (piso). Mais **pessoas** vale mais que mais repetições |
| Volume-exemplo | 12 sinais × 5 reps × 6 pessoas ≈ **360 clipes** |
| Câmera | altura/distância dos óculos num balcão (~altura dos olhos, ~1–1,5 m do sinalizante) |
| Luz | ambiente real (sala/escritório), **não** estúdio |

Uma pessoa repetindo o sinal muitas vezes **não substitui** ter várias pessoas —
mede consistência de um sinalizante, não generalização entre pessoas.

**Consentimento:** cada participante assina/confirma um consentimento simples de
participação em pesquisa (o vídeo é só para desenvolver o protótipo; informar se é
descartado após extração dos landmarks ou retido, e por quanto tempo). Se possível,
**descartar os vídeos brutos logo após extrair os landmarks**, mantendo só os
pontos.

---

## 4. Estrutura da pasta

```
PoC/
├── config.yaml            vocabulário, participantes, caminhos, parâmetros
├── requirements.txt       mediapipe, opencv, numpy, fastdtw, scikit-learn, matplotlib
├── data/
│   ├── raw/               vídeos brutos (descartáveis após extração)
│   └── landmarks/         landmarks extraídos, 1 .npy por clipe
├── src/
│   ├── record.py          §5.1 — captura de clipes (marcação início/fim)
│   ├── extract.py         §5.2 — extração via MediaPipe Holistic + normalização
│   ├── dtw_classifier.py  §5.3 — baseline 1-NN por DTW
│   ├── nn_classifier.py   §5.5 — opcional: GRU/MLP leve
│   └── evaluate.py        §5.4 — protocolo leave-one-signer-out + matriz de confusão
└── results/
    └── confusion_matrix.png
```

**Convenção de nome de clipe** (o nome já carrega os metadados — evita planilha
separada):

```
pessoa03_sinal-ajuda_rep02.mp4     →  pessoa=03, sinal=ajuda, repetição=02
```

`extract.py` gera o `.npy` de mesmo nome-base em `data/landmarks/`.

---

## 5. Pipeline técnico, passo a passo

### 5.1 Gravação — `record.py`
Abre a câmera (`cv2.VideoCapture`). Cada clipe é gravado sob demanda: uma tecla
começa, outra encerra. Salva como `data/raw/pessoaNN_sinal-XXX_repNN.mp4`.

### 5.2 Extração de landmarks — `extract.py`
Roda `mediapipe.solutions.holistic.Holistic` frame a frame. De cada frame extrai
**21 pontos de cada mão** + um **subconjunto de pose** (nariz, ombros, cotovelos,
pulsos) — contexto de tronco sem inflar a dimensionalidade.

> **A normalização é o passo que mais afeta o resultado e o mais fácil de
> esquecer.** Subtrai-se um ponto de referência estável (o **ponto médio entre os
> ombros**) de todos os pontos do frame, e divide-se pela **distância entre os
> ombros**. Isso torna os landmarks invariantes à distância da pessoa até a câmera
> e à sua posição no quadro — sem isso, o modelo aprende a distinguir "quem está
> mais perto da câmera" em vez de "qual sinal".

Empacota a sequência do clipe num array `num_frames × num_pontos × 3` e salva
`.npy` com o mesmo nome-base do vídeo.

### 5.3 Baseline DTW — `dtw_classifier.py`
Usa biblioteca pronta de DTW (`fastdtw`) — a lógica do algoritmo não é o gargalo,
a qualidade dos landmarks é. Achata cada frame num vetor único e classifica um
clipe novo pela **menor distância DTW** até **todos** os clipes de referência
(1-NN).

### 5.4 Avaliação leave-one-signer-out — `evaluate.py`
Para cada pessoa `p`: referência = todos os clipes **exceto** os de `p`; teste =
clipes de `p`. Classifica cada clipe de teste, registra acerto/erro. Ao final,
**acurácia média entre as rodadas** + **matriz de confusão agregada**.

### 5.5 Opcional — `nn_classifier.py`
Só depois do DTW medido: GRU pequeno (2 camadas, poucas dezenas de unidades) sobre
os mesmos landmarks normalizados, avaliado com o **mesmo** protocolo do §5.4. Só
vale adotar se superar o DTW de forma consistente.

---

## 6. Avaliação e critério de decisão

- **Leave-one-signer-out é obrigatório** — é o cenário real de produto (o sistema
  encontra alguém que nunca viu).
- **Métricas:** acurácia top-1 média (principal) + matriz de confusão agregada.

**Critério definido _antes_ de rodar o experimento:**

| Acurácia signer-independent | Decisão |
|---|---|
| **≥ 80%** | 🟢 Seguir para o MVP com essa abordagem |
| **60–80%** | 🟡 Revisar vocabulário (pares confundidos na matriz), mais repetições, ou testar o classificador treinado antes de decidir |
| **< 60%** | 🔴 Reconsiderar abordagem (vocabulário, distância de câmera, arquitetura) antes de investir mais |

---

## 7. Como rodar

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 1) grave clipes (uma pessoa/sinal por vez, veja a convenção de nome em §4)
python src/record.py --pessoa 03 --sinal ajuda

# 2) extraia landmarks de tudo em data/raw -> data/landmarks
python src/extract.py

# 3) rode a avaliação leave-one-signer-out (usa o baseline DTW)
python src/evaluate.py            # gera results/confusion_matrix.png + acurácia
```

> **Estado atual:** os scripts em `src/` são **implementações de referência** do
> protocolo acima, ainda **não validadas contra dados reais** (a coleta é o
> gargalo, não o código — ver §9 do plano). Trate-os como scaffold: leia, ajuste
> ao seu setup e valide com os primeiros clipes antes de confiar nos números.

---

## 8. Entregáveis

- Acurácia signer-independent média + matriz de confusão.
- Decisão de ir/não-ir para o MVP (critério §6).
- **Dataset de landmarks próprio** (reutilizável no MVP e no pipeline de produto).
- Lista de sinais problemáticos identificados, se houver.

---

*Plano completo: [`docs/libras-livre-poc-plano.md`](../../docs/libras-livre-poc-plano.md).
Arquitetura de longo prazo: [`docs/libras-livre-arquitetura.md`](../../docs/libras-livre-arquitetura.md).
Este scaffold é deliberadamente enxuto — qualquer
decisão de arquitetura mais ampla fica para depois que esta pergunta estiver
respondida.*

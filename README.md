# Libras Livre 🧏

**Óculos inteligentes que traduzem Libras para fala, em tempo real.**

Uma pessoa surda sinaliza em Libras. Os óculos (Ray-Ban Meta) veem os gestos, o
celular reconhece cada sinal e fala a frase em voz alta — para que quem não
conhece a língua entenda. Pensado para atendimento (ex.: um posto de saúde).

---

## Como funciona (a ideia em uma imagem)

```
  👋 pessoa sinaliza
        │
        ▼
  🕶️  óculos (câmera)  ──vídeo──►  📱 celular  ──►  🔊 "Estou com dor de cabeça"
                                      │
                          reconhece os sinais e monta a frase
```

Os óculos são só a **câmera**. Todo o "pensar" acontece no **celular**, sem
depender de internet.

---

## As duas partes do projeto

O sistema é montado por **duas trilhas** que se encontram em um único arquivo: o
modelo treinado (`sinal_classifier.tflite`).

```
  computer-vision-model/          mobile-app-companion/
  (a INTELIGÊNCIA, em Python)     (o APP, em Kotlin/Android)
  aprende a reconhecer sinais  ──►  usa o modelo nos óculos, em tempo real
           │                                    │
           └──────── sinal_classifier.tflite ───┘
```

### 📂 [`computer-vision-model/`](./computer-vision-model) — a inteligência
Treina o modelo que reconhece os sinais. Recebe **vídeos de sinais de Libras** e
produz um arquivo `.tflite` leve. O truque central: em vez de olhar a imagem, o
modelo olha o **esqueleto da mão** (pontos das juntas), o que o deixa pequeno,
rápido e robusto a luz/fundo/roupa.

### 📂 [`mobile-app-companion/`](./mobile-app-companion) — o app
App Android que conversa com os óculos Ray-Ban Meta, recebe o vídeo da câmera,
usa o modelo `.tflite` para reconhecer cada sinal e fala a frase. Nasceu do
exemplo oficial da Meta para acessar a câmera dos óculos.

---

## Como as partes se encaixam

1. A trilha de **IA** (`computer-vision-model`) grava vídeos, aprende os sinais e
   exporta o `sinal_classifier.tflite`.
2. Esse arquivo é copiado para o **app** (`mobile-app-companion/app/src/main/assets/`).
3. O **app** roda o modelo nos óculos: vê o gesto → reconhece a palavra → junta as
   palavras numa frase → fala.

Uma divisão importante de responsabilidade:

| Pergunta | Quem responde |
|---|---|
| *Que sinal é este?* | a IA (Python), com o modelo `.tflite` |
| *Onde um sinal termina e outro começa?* | o app (Kotlin), por detecção de pausa |
| *Como virar fala?* | o app (Kotlin), com síntese de voz |

---

## O passo zero: a PoC

Antes de treinar o modelo de produção, uma **PoC** responde a pergunta que decide
se a abordagem se sustenta:

> MediaPipe Holistic + um classificador simples reconhecem um vocabulário fechado
> de Libras **generalizando entre pessoas diferentes**, na distância de um
> atendimento de balcão?

Isso é um **requisito de produto**: os óculos são institucionais e atendem alguém
novo a cada atendimento — o modelo nunca viu quem está à frente da câmera. A PoC
mede exatamente isso (avaliação *leave-one-signer-out*) com um baseline DTW, sem
treinar rede nenhuma, e aplica um **critério de ir/não-ir** definido antes de rodar.

➡️ [`computer-vision-model/PoC/`](./computer-vision-model/PoC) — o pipeline
completo de treino só vale o investimento depois que a PoC der **sinal verde**.

### E os vídeos, de onde vêm?

De duas bases públicas de Libras — **MINDS-Libras** e **V-LIBRASIL** —, integradas
em [`computer-vision-model/datasets/`](./computer-vision-model/datasets). O
vocabulário do projeto (10 sinais) foi escolhido por um critério só: **os sinais
que existem nas duas bases**, e que por isso chegam com **11 pessoas diferentes
cada** — o insumo exato de que a PoC precisa.

```
acontecer   amarelo   banheiro   barulho   espelho
filho       maca      medo       ruim      sapo
```

Um script baixa só os clipes escolhidos (430 de ~5 mil, sem baixar os 58 GB dos
dois pacotes) e os renomeia para a convenção do pipeline. Os vídeos não entram no
git: o que fica versionado é a receita para reproduzi-los.

---

## Por onde começar

- **A PoC** (passo zero, decide o resto) → [`computer-vision-model/PoC/README.md`](./computer-vision-model/PoC/README.md)
- Entender **como o modelo de produção é treinado** → [`computer-vision-model/README.md`](./computer-vision-model/README.md)
- Entender **como o app funciona** → [`mobile-app-companion/README.md`](./mobile-app-companion/README.md)
- **Decisões de arquitetura e o plano da PoC** → [`docs/`](./docs)

Cada pasta tem seu próprio README com os detalhes técnicos, os conceitos e o passo
a passo de execução.

---

## Estado atual

- ✅ App base (conexão com os óculos, câmera, gravação) — herdado do sample da Meta.
- ✅ Estrutura da trilha de IA montada (extração de landmarks + modelo raso + export).
- ✅ Dataset integrado: 10 sinais × 11 pessoas vindos das bases públicas
  (`computer-vision-model/datasets/`), no lugar da espera pela coleta própria.
- ✅ **PoC executada:** 430 clipes → 70,0% (🟡) no dataset completo, **85,7%** no
  recorte sem o degrau entre as duas bases e sem os rótulos ainda não validados
  (`computer-vision-model/PoC/results/relatorio.md`).
- 🎯 **Foco atual: destravar o amarelo.** Nesta ordem: validar 3 rótulos com
  consultor de Libras (~10 pontos), e coleta própria no setup de balcão — as
  bases públicas são estúdio, e mesmo o número bom é teto otimista.
- 🚧 A integração (landmarks no app → classificador → fronteiras entre sinais → voz)
  fica para depois da PoC dar sinal verde.

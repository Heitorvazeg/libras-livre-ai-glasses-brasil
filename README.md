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

## Por onde começar

- Entender **como o modelo é treinado** → [`computer-vision-model/README.md`](./computer-vision-model/README.md)
- Entender **como o app funciona** → [`mobile-app-companion/README.md`](./mobile-app-companion/README.md)

Cada pasta tem seu próprio README com os detalhes técnicos, os conceitos e o passo
a passo de execução.

---

## Estado atual

- ✅ App base (conexão com os óculos, câmera, gravação) — herdado do sample da Meta.
- ✅ Estrutura da trilha de IA montada (extração de landmarks + modelo raso + export).
- 🚧 A integração (landmarks no app → classificador → fronteiras entre sinais → voz)
  é o trabalho em andamento.

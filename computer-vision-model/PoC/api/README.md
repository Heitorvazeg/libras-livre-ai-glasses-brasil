# API de validação — classificar sinal a partir de landmarks

> **Andaime de validação, não produção.** Esta API existe para fechar o loop
> `app → landmarks → sinal → voz` reusando, sem reimplementar nada, o
> classificador **DTW 1-NN** já medido pela PoC (§5.3). O objetivo do projeto é
> **on-device/offline** (ver README da raiz — o caso de uso é balcão de
> atendimento, onde internet pode faltar). Trate esta API como o caminho curto
> para validar a integração com o app; se validar, aí sim vale trazer o
> classificador para dentro do device.

---

## Por que o app manda landmarks **crus** e o servidor normaliza

O passo que mais afeta a acurácia é a **normalização** (`src/extract.py` §5.2:
origem no ponto médio dos ombros, escala pela distância entre eles, conversão para
pixel). Se o app normalizasse por conta própria, qualquer divergência derrubaria a
acurácia sem aviso — comparando o clipe do app contra referências normalizadas de
outro jeito.

Por isso: **o app só roda o MediaPipe e encaminha os pontos como saem do
detector.** Toda a normalização acontece no servidor, chamando o **mesmo**
`extract.frame_normalizado` que gerou os `.npy` de referência. Um único lugar com
a lógica sensível. `api/test_api.py` fixa esse invariante (paridade servidor ↔
`extract`).

Consequência prática: a máquina que roda a API **não precisa** de `mediapipe` nem
`opencv` — só `numpy`, `pyyaml`, um backend de DTW e o FastAPI. A extração pesada
fica no celular.

---

## O que o app precisa rodar

MediaPipe **Pose + Hands** (ou Holistic) por frame, sobre o vídeo que chega do
mock do DAT / dos óculos. Para cada frame do sinal (já segmentado pelo app —
"onde o sinal começa e termina" é responsabilidade do app, não da API), coletar:

- **pose:** os **33** pontos do MediaPipe Pose, cada um `[x, y, z, visibility]`,
  nas coordenadas normalizadas do MediaPipe (0..1). Mande **todos os 33** — o
  servidor indexa ombros (11,12), nariz (0), cotovelos (13,14) e pulsos (15,16);
  os índices precisam bater.
- **left_hand / right_hand:** os **21** pontos `[x, y, z]` de cada mão, ou `null`
  se a mão não foi detectada naquele frame.
- **width / height:** dimensões do frame em **pixels** (uma vez, no topo).

> A `visibility` dos ombros é usada para descartar frames sem pose confiável
> (`min_visibilidade` no `config.yaml`) — o mesmo critério da PoC. As mãos não
> usam `visibility`.

---

## Contrato

### `POST /classify`

```jsonc
{
  "width": 1280,
  "height": 720,
  "frames": [
    {
      "pose": [[0.51, 0.30, 0.0, 0.98], /* ... 33 pontos [x,y,z,visibility] ... */],
      "left_hand":  [[0.44, 0.55, 0.01], /* ... 21 pontos [x,y,z] ... */],
      "right_hand": null
    }
    // ... um objeto por frame do sinal
  ]
}
```

**Resposta `200`:**

```jsonc
{
  "sinal": "banheiro",              // classe do vizinho mais próximo (1-NN)
  "distancia": 12.34,               // distância DTW ao vizinho (menor = mais parecido)
  "clipe_vizinho": "pessoaM05_sinal-banheiro_rep03",
  "topk": [                         // as k classes mais próximas (útil pra ver confusão)
    {"sinal": "banheiro", "distancia": 12.34, "clipe": "pessoaM05_sinal-banheiro_rep03"},
    {"sinal": "acontecer", "distancia": 15.01, "clipe": "pessoaM02_sinal-acontecer_rep01"}
  ],
  "frames_usados": 120,             // frames com pose confiável
  "frames_descartados": 18          // frames sem ombro visível (não normalizáveis)
}
```

**Erros:**

| Código | Quando |
|---|---|
| `422` | payload malformado (pose ≠ 33 pontos, mão ≠ 21) — validado na entrada |
| `422` | nenhum frame com pose confiável (ombros fora do quadro/ocluídos) |
| `503` | sem clipes de referência em `data/landmarks` — rode a ingestão + `extract.py` |

### `GET /health`

```jsonc
{"status": "ok", "clipes_referencia": 430, "vocabulario": ["acontecer", ...],
 "backend_dtw": "dtaidistance", "dims": 2}
```

`status: "sem_referencias"` significa que o servidor subiu mas `data/landmarks`
está vazio — `/classify` responderá `503` até haver referências.

---

## Como rodar

```bash
# a partir de PoC/
pip install -r requirements.txt        # deps da PoC (numpy, dtaidistance, mediapipe*)
pip install -r api/requirements.txt    # fastapi + uvicorn

# 1) garanta que há referências (senão /classify dá 503):
(cd ../datasets && python ingest.py --reps 1)   # traz os clipes públicos
python src/extract.py                            # gera data/landmarks/*.npy

# 2) suba a API na rede local (0.0.0.0 = acessível por emulador e device físico)
python -m uvicorn api.server:app --host 0.0.0.0 --port 8000
```

\* `mediapipe`/`opencv` só são necessários se você for **extrair** landmarks nesta
máquina (`extract.py`). A API em si não os importa.

Docs interativas (Swagger, dá pra testar o `POST` pelo navegador): `/docs`.

### Acessar de um emulador Android (sem publicar servidor)

Não precisa deploy. Com a API rodando na sua máquina:

| De onde | URL base |
|---|---|
| **Emulador Android** | `http://10.0.2.2:8000` |
| **Celular físico** (mesma Wi-Fi) | `http://<IP-LAN-da-máquina>:8000` (ex.: `http://192.168.0.42:8000`) |
| Navegador da própria máquina | `http://127.0.0.1:8000/docs` |

`10.0.2.2` é o alias fixo que, **de dentro do emulador**, aponta para a máquina
host — `localhost` lá seria o próprio emulador. Descubra o IP LAN com `ip addr`
(Linux) / `ifconfig` (macOS).

> ⚠️ **Android bloqueia HTTP puro (cleartext) por padrão.** Como aqui é `http://`
> (sem TLS), libere o domínio de dev, senão a request falha. No app, adicione um
> `res/xml/network_security_config.xml` permitindo `10.0.2.2` (e o IP LAN) e
> referencie-o em `AndroidManifest.xml` (`android:networkSecurityConfig`).
> Alternativa rápida só para debug: `android:usesCleartextTraffic="true"` no
> `<application>`.

---

## Testes

```bash
# a partir de PoC/
python -m api.test_api          # sem pytest
python -m pytest api/test_api.py -v
```

Os testes **não** dependem de clipes de referência nem de mediapipe/opencv:
fixam a **paridade da normalização** com `extract.frame_normalizado` (o risco nº 1
do caminho por API), o descarte de frame sem pose, a mão ausente virando zeros, e
as respostas de erro do endpoint. A **acurácia** de verdade só a coleta real
valida (§9 do plano) — isto aqui valida o encanamento.

# Libras Livre — como atendemos cada checkpoint do hackathon

Referência: slides "Checkpoints obrigatórios" (Meta São Paulo, 18/09/2026). Este documento
mapeia **cada critério** ao que o Libras Livre faz, **qual número comprova**, e **de onde tirar
esse número** (arquivo/evento no CSV do gravador de sessão, log ou config). Onde há lacuna, está
declarada com honestidade.

O produto: os óculos captam o **surdo sinalizando**; o app reconhece os sinais (visão + classificador),
vira texto, o atendente ouve; a resposta falada do atendente vira texto (STT) e é apresentada ao
surdo por um **avatar 3D em Libras**. Tudo com **consentimento por atendimento** e **processamento
local**.

## Régua de evidência (como enunciar os números)

| Sinal | Conta | Não conta |
|---|---|---|
| Tecnologia nomeada | "classificador TFLite `final-s20260917-v1`, no celular (CPU)" | "modelo de visão" |
| Número com unidade e origem | "classificação mediana X ms, pior Y ms, n=Z, medido às HH:MM" | "baixa latência" |
| Decisão com o preterido | "MediaPipe em CPU (~5 fps) em vez de GPU (22 fps)…" | "otimizado" |
| Custo do trade-off | "…porque o init em GPU custa ~55 s a cada abertura" | ganho sem custo |

---

## Como gerar os números — o gravador de sessão (CSV)

Ligue **Configurações de demo → Gravador de sessão (CSV)**. Um arquivo por sessão em:

```
/sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes/<AAAAMMDD-HHMMSS>.csv
adb pull /sdcard/Android/data/.../files/sessoes
```

Três tipos de linha, mesmas colunas (`tipo,ts_ms,turno,estado,v_*,pose,mao_*,nome,detalhe,<57 pontos>`):
- **`frame`** — um por frame do MediaPipe: estado do detector, velocidades, presença de pose/mãos, 57 pontos.
- **`evento`** — `segmento`, `descartado`, `classificacao`, `falha_classificacao`, `enquadramento`,
  `decisao`, `latencia` (por etapa do turno), **`latencia_modelo`** (tts/stt), **`config_captura`**,
  `identidade_classificador`, `consentimento`, `aquecimento_*`, `bateria_baixa`.
- **`metrica`** — amostrado ~1×/s: `fps_recebido/decodificado/processado`, `pct_sem_pose`,
  `fila_cheia`, **`mp_inferencia_ms_media`/`mp_inferencia_ms_max`** (visão), `bateria_pct`,
  `folga_termica`, `estado_termico`, `ram_app_mb`.

O painel de métricas (mesmo menu) mostra fps, bateria e RAM ao vivo para conferência rápida.

---

## Medições reais — sessão 2026-09-18 12:57 (`20260918-125714.csv`)

Primeira sessão com a instrumentação completa. Config medida: `resolucao=MEDIUM, fps=24 (config),
saida_voz=OCULOS, mic_resposta=OCULOS`. Duração ~178 s, 567 frames, `pct_sem_pose` média 1,5%.

| Métrica | Valor real | Origem |
|---|---|---|
| **IA — classificador (TFLite)** | **< 1 ms** (n=20; arredonda a 0) | `latencia etapa=classificacao` |
| **Visão — MediaPipe (pose+mãos)** | **média 150 ms/frame · pior 496 ms** (n=85 janelas) | `mp_inferencia_ms_media/max` |
| **fps efetivo na captura** | **~7 fps** (dt mediano 143 ms; pico 21) | intervalos de `frame` / `fps_processado` |
| **TTS (Piper)** | 0–2 ms | `latencia_modelo modelo=tts_piper` — **frases cacheadas** (aviso "repita"/"desistir" pré-sintetizados); síntese real não exercida nesta sessão |
| **STT (Vosk)** | não exercido | sem resposta do atendente nesta sessão |
| **Segmentação (fechamento)** | **9 PAUSA · 5 DURAÇÃO_MÁX · 4 OCLUSÃO · 2 fim** (+4 espasmos descartados) | eventos `segmento`/`descartado` |
| **Bateria** | 100% → 100% (**inválido: celular no USB**) | `bateria_pct` |
| **Térmico** | nominal (`estado_termico=0`) | `estado_termico` |

**Leituras-chave:**
1. **O gargalo é a VISÃO, não a IA.** O classificador TFLite é <1 ms; o custo do pipeline é o
   MediaPipe a **150 ms/frame em CPU** (~7 fps). É o que justifica calibrar a segmentação para fps
   baixo e por que a GPU (22 fps medidos) está no roadmap — bloqueada pelos ~55 s de init.
2. **A calibração da segmentação funcionou:** de **1 PAUSA / 11 DURAÇÃO_MÁX** (antes) para
   **9 PAUSA / 5 DURAÇÃO_MÁX**. O detector voltou a achar o fim dos sinais.
3. **Confiança ainda separa certo/errado:** erros com 0,17–0,52; acertos com 0,78–1,00.
4. **Pendências de medição:** a bateria precisa ser medida **fora do USB**; TTS real (frase não
   cacheada) e STT precisam de um turno bidirecional completo para gerar número.

---

# CP1 · 15:00 — IA · câmera/microfone · áudio

## 1 · Uso de Inteligência Artificial

**Critério:** ≥ 1 componente de IA funcional e comprovável; alguém de fora escolhe a entrada na hora
e a saída muda; modelo nomeado; latência mediana e pior caso (n ≥ 20) e onde roda.

**O que fazemos:** o **classificador de sinais** (TensorFlow Lite, `REAL_EXPERIMENTAL`) recebe o
recorte de um sinal e devolve a glosa com confiança. A entrada é o surdo sinalizando ao vivo (entrada
escolhida na hora, dentro do vocabulário declarado); a saída muda com o sinal. Rodam ainda, todos
**locais**: MediaPipe (pose+mãos), contextualização de glosas, Vosk (STT), Piper (TTS).

**Evidência / número:**
- **Modelo nomeado + onde roda:** evento `identidade_classificador` (`modelo_sha256`,
  `experimento=final-s20260917-v1`, `calibracao=ausente_nao_calibrado`) — roda **no celular, CPU**.
- **Latência mediana e pior caso (n≥20):** eventos `latencia` com `etapa=classificacao` (um por sinal).
  Some ≥ 20 sinais numa sessão e calcule mediana/máx a partir do campo `ms`.
- **Latência da visão (MediaPipe):** métricas `mp_inferencia_ms_media` / `mp_inferencia_ms_max`.
- **Latência de TTS e STT (modelo):** eventos `latencia_modelo` (`modelo=tts_piper` /
  `modelo=stt_vosk`); e a nível de fluxo, `latencia` `etapa=frase_primeiro_audio` (voz) e
  `etapa=fim_fala_texto` (transcrição).

**Trade-off declarado:** MediaPipe roda em **CPU (~5 fps)** e não em **GPU (~22 fps, medido)** porque
a criação dos modelos em GPU custou **~55 s a cada abertura** no Galaxy A57 — inaceitável no warmup.
O código de GPU está pronto atrás de um flag (`usarGpu`), aguardando init em segundo plano.

## 2 · Câmera ou microfone

**Critério:** entrada efetiva pelos sensores dos óculos como canal principal; frame do stream DAT ou
mic dos óculos via HFP; fps × resolução configurados; tempo até o 1º frame; rota de áudio.

**O que fazemos:** consumimos o **stream de vídeo do DAT** (HEVC), decodificado e enviado ao MediaPipe —
é o canal de entrada principal, com o celular na mesa/mochila. O mic dos óculos (HFP) é usado para a
resposta do atendente quando configurado.

**Evidência / número:**
- **fps configurado × real:** `config_captura` (`fps`, `resolucao=MEDIUM`) + métricas
  `fps_recebido/decodificado/processado`.
- **Tempo até o 1º frame útil:** `latencia` `etapa=iniciar_pode_sinalizar` (do "iniciar" ao 1º frame
  com pose e os dois ombros — 1º frame *aproveitável*).
- **Rota de áudio:** `config_captura` (`saida_voz`, `mic_resposta`).

**Lacuna:** a **resolução** é fixa (`VideoQuality.MEDIUM`) — registrada em `config_captura`, mas não
variável. O "1º frame *bruto*" (qualquer frame) não é medido isoladamente; usamos o 1º frame útil.

## 3 · Output por áudio

**Critério:** resposta pelo alto-falante dos óculos, único canal de saída; latência até o 1º som;
perfil em uso (HFP/A2DP) e o que foi preterido.

**O que fazemos:** a frase reconhecida do surdo é **falada ao atendente** pela voz (Piper TTS), com
saída roteada para os óculos (`saidaVoz=OCULOS`). Para o surdo, a saída é o **avatar em Libras** (não
há display nos óculos; o avatar é a superfície visual no celular do atendente).

**Evidência / número:**
- **Latência até o 1º som:** `latencia` `etapa=frase_primeiro_audio` + `latencia_modelo`
  `modelo=tts_piper` (tempo do modelo até o 1º bloco de áudio).
- **Rota/saída:** `config_captura` (`saida_voz`).

**Lacuna:** o **perfil Bluetooth em uso (HFP × A2DP)** não é registrado no CSV — hoje é inferido pela
configuração de saída/mic. Registrar o perfil ativo é uma adição pequena pendente.

---

# CP2 · 16:00 — privacidade · bateria

## 4 · Privacidade e dados

**Critério:** justificativa explícita do tratamento; fluxo declarado (qual dado, onde, quanto tempo,
base legal) + mecanismo que roda e aparece no código; consentimento que **pode** ser recusado.

**O que fazemos:**
- **Processamento 100% local** para reconhecimento: classificador TFLite, MediaPipe, Vosk (STT) e
  Piper (TTS) rodam **no aparelho**, sem nuvem. Única dependência externa: o **dicionário/tradução do
  avatar VLibras** (`dicionario2.vlibras.gov.br`) — dado que trafega é o texto da resposta do
  atendente, não imagem do surdo.
- **Consentimento por atendimento** (estado `PEDINDO_CONSENTIMENTO`, `PoliticaCamera`): a câmera só
  liga após "Aceitar"; "Recusar" leva a atendimento por bilhete/intérprete, **sem travar** — o
  consentimento é obrigatório e recusável, nunca persistido entre atendimentos.
- **O que é retido:** o pipeline trabalha sobre **landmarks (57 pontos normalizados)**, não sobre o
  vídeo cru. O gravador de CSV (opcional, só com a demo ligada) grava esses pontos e métricas — não
  frames de vídeo. A gravação de **vídeo (MP4 passthrough) fica DESLIGADA por padrão** —
  `isRecording` inicia `false` e só liga por toque explícito (`toggleRecording`, botão na tela);
  não há gravação silenciosa.

**Evidência (arquivo e função do mecanismo):**
- Consentimento: `libras/dialogo/DialogOrchestrator.kt` (`pedirConsentimento`/`aceitar`/`recusar`) +
  `camera/PoliticaCamera.kt`; eventos `consentimento` (aceito/recusado/reaproveitado) no CSV.
- Processamento local: `libras/reconhecimento/` (MediaPipe/TFLite), `libras/audio/` (Vosk/Piper).
- Retenção: `libras/diagnostico/GravadorSessao.kt` (landmarks/métricas, opt-in).

**Verificado:** `stream/VideoRecorder.kt` (passthrough MP4, video-only, sem mic do celular) só grava
por toque explícito no botão de gravação; **desligado por padrão** na demo.

## 5 · Eficiência de bateria

**Critério:** estratégia clara de economia; medido hoje em uso contínuo — % a cada 10 min nos óculos
e no celular; fps × resolução; tempo de HFP ativo; estratégia com o que foi preterido.

**O que fazemos (estratégia):**
- **Modo economia** ao receber bateria baixa/crítica dos óculos (`BATTERY_LOW`/`BATTERY_CRITICAL` →
  `PoliticaCamera.ativarEconomia`): encerra captura e bloqueia religar a câmera.
- **Câmera desligada entre turnos** (só liga em captura/escuta), avatar **liberado sob pressão de
  memória**, e MediaPipe em **CPU** (evita o pico de energia/tempo do init em GPU).

**Evidência / número:**
- **Bateria do celular %/10 min:** métrica `bateria_pct` (amostrada ~1×/s, `BatteryManager` do
  celular) — deriva-se o consumo por 10 min da série.
- **fps × resolução:** métricas de fps + `config_captura`.
- **Térmico:** `folga_termica`, `estado_termico`.

**Lacunas:** **bateria dos óculos %/10 min** não é exposta pelo SDK de forma contínua (só o evento
crítico) — medir por observação externa; **tempo de HFP ativo** não é cronometrado no CSV.

---

## Resumo das lacunas (para fechar antes dos CPs)

| Item | Estado | Como fechar |
|---|---|---|
| Latência IA / visão / TTS / STT | ✅ no CSV | rodar gerador de relatório (mediana/pior/n) |
| fps, rota de áudio, resolução, 1º frame útil, bateria celular, térmico | ✅ no CSV | idem |
| Perfil Bluetooth HFP × A2DP | ❌ | registrar o perfil ativo num evento |
| Bateria dos óculos %/10 min | ❌ (SDK) | medição externa / observação |
| Tempo de HFP ativo | ❌ | cronometrar sessão de escuta |
| Retenção de vídeo (VideoRecorder) | ✅ off por padrão | opt-in manual; sem gravação silenciosa |

## Próximo passo sugerido

Um **gerador de relatório** que lê o CSV e emite, no formato da régua de evidência, os números de
CP1/CP2 — como os já medidos em 2026-09-18: "classificador TFLite <1 ms (n=20)"; "MediaPipe média
150 ms/frame, pior 496 ms"; "~7 fps efetivo"; "segmentação 9 PAUSA / 5 DURAÇÃO_MÁX". Assim cada
gravação vira evidência pronta para a banca. Para completar CP1/CP2 faltam três coletas: (a) bateria
**fora do USB**, (b) um turno bidirecional para TTS real (frase não cacheada) e STT (Vosk), e (c)
registrar o perfil Bluetooth ativo.

# Libras Livre — como atendemos cada checkpoint do hackathon

Referência: slides "Checkpoints obrigatórios" (Meta São Paulo, 18/09/2026). Para **cada critério**
este documento diz: **como resolvemos**, **onde está implementado** (arquivo/função), **como medir**
(campo no CSV + como derivar) e o **número real medido**. Lacunas estão declaradas com honestidade.

**O produto:** os óculos captam o **surdo sinalizando**; o app extrai landmarks (MediaPipe),
segmenta cada sinal e classifica a glosa (TFLite), vira texto e o atendente **ouve** (TTS Piper); a
resposta falada do atendente vira texto (STT Vosk) e é apresentada ao surdo por um **avatar 3D em
Libras** (VLibras). Tudo com **consentimento por atendimento** e **reconhecimento 100% local**.

## Régua de evidência (como enunciar os números)

| Sinal | Conta | Não conta |
|---|---|---|
| Tecnologia nomeada | "classificador ST-GCN em TFLite `final-s20260917-v1`, no celular (CPU)" | "modelo de visão" |
| Número com unidade e origem | "classificação mediana 76 ms, pior 119 ms, n=52, medido às 13:20" | "baixa latência" |
| Decisão com o preterido | "MediaPipe em CPU (~11 fps) em vez de GPU (~22 fps)…" | "otimizado" |
| Custo do trade-off | "…porque o init da GPU custa ~55 s a cada abertura" | ganho sem custo |

---

## Como gerar os números — o gravador de sessão (CSV)

Ligue **Configurações de demo → Gravador de sessão (CSV)**. Um arquivo por sessão em:

```
/sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes/<AAAAMMDD-HHMMSS>.csv
adb pull /sdcard/Android/data/com.meta.wearable.dat.externalsampleapps.cameraaccess/files/sessoes
```

**Implementação:** `libras/diagnostico/GravadorSessao.kt` (escrita) + `libras/diagnostico/Metricas.kt`
(coleta). Três tipos de linha, mesmas colunas (`tipo,ts_ms,turno,estado,v_*,pose,mao_*,nome,detalhe,<57 pontos>`):

- **`frame`** — um por frame do MediaPipe: estado do detector, velocidades, presença de pose/mãos, 57 pontos.
- **`evento`** — `segmento`, `descartado`, `classificacao`, `falha_classificacao`, `enquadramento`,
  `decisao`, `latencia` (por etapa do turno, formato `... etapa=X ms=Y`), `latencia_modelo`
  (`modelo=tts_piper|stt_vosk,ms=Y`), `config_captura`, `perfil_bluetooth` (`perfil=HFP|A2DP`),
  `identidade_classificador`, `consentimento`.
- **`metrica`** — amostrado ~1×/s: `fps_recebido/decodificado/processado`, `pct_sem_pose`,
  `fila_cheia`, `mp_inferencia_ms_media`/`mp_inferencia_ms_max` (visão), `bateria_pct`,
  `folga_termica`, `estado_termico`, `ram_app_mb`.

O **painel de métricas** (mesmo menu) mostra fps, bateria e RAM ao vivo — `ui/CameraScreen.kt`.

---

## Medições reais — sessões 2026-09-18 13:18–13:31 (8 arquivos, APK com downscale)

Config medida (`config_captura`): `resolucao=MEDIUM, fps=24 (config), saida_voz=OCULOS,
mic_resposta=OCULOS`. `pct_sem_pose` média 0,4%. n=52 sinais classificados no total.

| Métrica | Valor real medido | Como/onde medir |
|---|---|---|
| **IA — classificador (ST-GCN/TFLite)** | **mediana 76 ms · pior 119 ms · n=52** | `evento latencia etapa=classificacao` |
| **Visão — MediaPipe (pose+mãos)** | **média 97 ms/frame · pior 416 ms** | `metrica mp_inferencia_ms_media/max` |
| **fps efetivo (processado)** | **mediana ~11 · pico 22** | `metrica fps_processado` / intervalos de `frame` |
| **TTS Piper — modelo até 1º áudio** | **mediana 465 ms** (frase nova, n=4); ~0 ms cacheada | `evento latencia_modelo modelo=tts_piper` |
| **TTS — voz até 1º som (fluxo)** | **mediana 509 ms · pior 767 ms** | `evento latencia etapa=frase_primeiro_audio` |
| **STT Vosk — fechar texto final** | **mediana 268 ms · pior 490 ms · n=7** | `evento latencia_modelo modelo=stt_vosk` |
| **STT — fim de fala → texto (fluxo)** | **mediana 412 ms** | `evento latencia etapa=fim_fala_texto` |
| **Segmentação (fechamento)** | **37 PAUSA · 7 DURAÇÃO_MÁX · 9 fim de sessão** | `evento segmento` (campo `motivo`) |
| **Acerto do classificador** | **62% com confiança ≥0,6 (32/52)**; filho med. 0,99 · medo 0,90 | `evento classificacao` (`confianca`) |
| **Bateria** | inválida nesta coleta (**celular no USB**) | `metrica bateria_pct` |

**Leituras-chave:**
1. **A IA é rápida; o custo do pipeline é a VISÃO.** Classificador 76 ms; MediaPipe 97 ms/frame.
   Corrige a medição anterior ("<1 ms" foi erro de parse).
2. **Downscale + menos candidatos deram +55% de fps** (7→~11) e caíram os 150→97 ms do MediaPipe —
   ver trade-off no CP1.2.
3. **Segmentação sólida:** 37 fechamentos por PAUSA contra 7 por teto — o detector acha o fim dos sinais.
4. **Reconhecimento estabilizou nos sinais fortes:** filho (0,99) e medo (0,90) firmes; vacina/aluno/
   aproveitar ainda fracos (movimento rápido → poucos frames; modelo experimental, 8 pessoas do MINDS).
5. **Pendências de coleta:** bateria **fora do USB**; perfil Bluetooth HFP×A2DP; bateria dos óculos.

---

# CP1 · 15:00 — IA · câmera/microfone · áudio

## 1 · Uso de Inteligência Artificial

**Critério:** ≥1 componente de IA funcional e comprovável; alguém de fora escolhe a entrada na hora e
a saída muda; modelo nomeado; latência mediana e pior caso (n≥20) e onde roda.

**Como resolvemos:** o **classificador de sinais** (ST-GCN exportado para **TensorFlow Lite**,
`REAL_EXPERIMENTAL`) recebe o recorte temporal de um sinal (57 landmarks × N frames, reamostrado para
96) e devolve a glosa + confiança. A entrada é o surdo sinalizando ao vivo — quem sinaliza escolhe o
sinal na hora, dentro do vocabulário de 20 glosas declarado; a saída muda com o sinal. Rodam ainda,
**todos locais**: MediaPipe (pose+mãos), contextualização de glosas, Vosk (STT), Piper (TTS).

**Onde está implementado:**
- `libras/reconhecimento/TfliteSignClassifier.kt` — inferência TFLite (contrato `[1,96,57,3]`).
- `libras/reconhecimento/CarregadorClassificador.kt` — carrega/valida o modelo (SHA, identidade).
- Modelo/sidecar: `experimentos-privados/app-baseline-v1/` (opt-in do build, não versionado público).

**Como medir + número real:**
- **Modelo e onde roda:** `evento identidade_classificador` (`experimento=final-s20260917-v1`,
  `modelo_sha256=616d1e…`, `calibracao=ausente_nao_calibrado`) — **celular, CPU**.
- **Latência (n≥20):** `evento latencia etapa=classificacao` → **mediana 76 ms, pior 119 ms, n=52**.
- **Latências dos outros modelos:** MediaPipe `mp_inferencia_ms_*` (97/416 ms); TTS/STT
  `latencia_modelo` (465 ms / 268 ms).

**Trade-off declarado:** MediaPipe roda em **CPU (~11 fps)** e não em **GPU (~22 fps, medido)** porque
a criação dos modelos em GPU custou **~55 s a cada abertura** no Galaxy A57 — inaceitável no warmup.
Mitigamos com **downscale da imagem (½)** e **menos candidatos** (1 pose / 2 mãos): fps 7→~11,
MediaPipe 150→97 ms. Código de GPU pronto atrás do flag `usarGpu` (`LandmarkExtractor.kt`), aguardando
init em segundo plano.

## 2 · Câmera ou microfone

**Critério:** entrada efetiva pelos sensores dos óculos como canal principal; frame do stream DAT ou
mic dos óculos via HFP; fps × resolução configurados; tempo até o 1º frame; rota de áudio.

**Como resolvemos:** consumimos o **stream de vídeo HEVC do DAT**, decodificado e enviado ao MediaPipe
— é o canal de entrada principal, com o celular na mesa/mochila. O mic dos óculos (HFP) capta a
resposta do atendente quando `mic_resposta=OCULOS`.

**Onde está implementado:**
- `stream/HevcDecoder.kt`, `stream/VideoCaptureHandler.kt` — decodificação do stream do DAT.
- `libras/reconhecimento/LandmarkExtractor.kt` — MediaPipe pose+hands (downscale + 1 pose/2 mãos).
- `libras/reconhecimento/LandmarkPipeline.kt` — orquestra frames→landmarks→segmentação.
- `camera/CameraViewModel.kt` (`beginStream`) — `StreamConfiguration(VideoQuality.MEDIUM, 24 fps)`.

**Como medir + número real:**
- **fps × resolução:** `evento config_captura` (`resolucao=MEDIUM, fps=24`) + `metrica
  fps_recebido/processado` → **recebido ~24, processado mediana ~11**.
- **Tempo até o 1º frame útil:** `evento latencia etapa=iniciar_pode_sinalizar` (do "iniciar" ao 1º
  frame com pose e os dois ombros).
- **Rota de vídeo/áudio:** `config_captura` (`saida_voz`, `mic_resposta`).

**Lacunas/ressalvas:** resolução é fixa (`VideoQuality.MEDIUM`), registrada mas não variável. O
`iniciar_pode_sinalizar` **inclui o tempo de consentimento ①.5 + subida da câmera** (não é latência
pura de 1º frame) — para o CP, cronometrar o 1º frame bruto à parte ou declarar essa composição.

## 3 · Output por áudio

**Critério:** resposta pelo alto-falante dos óculos, único canal de saída; latência até o 1º som;
perfil em uso (HFP/A2DP) e o que foi preterido.

**Como resolvemos:** a frase reconhecida do surdo é **falada ao atendente** por TTS (Piper/sherpa-onnx,
local), com saída roteada para os óculos (`saidaVoz=OCULOS`). Para o surdo, a saída é o **avatar em
Libras** (não há display nos óculos; o avatar é a superfície visual no celular).

**Onde está implementado:**
- `libras/audio/PiperSherpaOnnxTtsEngine.kt` — síntese TTS + roteamento de saída.
- `libras/audio/TtsEmCadeia.kt`, `Speaker.kt` — cadeia principal→reserva.
- `libras/avatar/AvatarPlayer.kt` + `assets/vlibras/` — avatar VLibras (WebView/Unity).

**Como medir + número real:**
- **Latência até o 1º som:** `evento latencia etapa=frase_primeiro_audio` → **mediana 509 ms** (fluxo);
  modelo puro `latencia_modelo modelo=tts_piper` → **465 ms** (frase nova) / ~0 (cacheada).
- **Rota/saída:** `config_captura` (`saida_voz=OCULOS`).
- **Perfil Bluetooth ativo:** `evento perfil_bluetooth perfil=HFP|A2DP` — registrado a cada troca de
  estado (`AudioSessionManager`): **A2DP** no padrão (TTS/avatar), **HFP** durante a escuta do
  atendente. O que foi preterido: A2DP e HFP são exclusivos no mesmo par, então a escuta interrompe a
  reprodução por A2DP.

---

# CP2 · 16:00 — privacidade · bateria

## 4 · Privacidade e dados

**Critério:** justificativa explícita do tratamento; fluxo declarado (qual dado, onde, quanto tempo,
base legal) + mecanismo que roda e aparece no código; consentimento que **pode** ser recusado.

**Como resolvemos:**
- **Reconhecimento 100% local:** classificador TFLite, MediaPipe, Vosk (STT) e Piper (TTS) rodam **no
  aparelho**, sem nuvem. Única saída externa: o **dicionário/tradução do avatar VLibras**
  (`dicionario2.vlibras.gov.br`) — trafega o **texto** da resposta do atendente, nunca imagem do surdo.
- **Consentimento por atendimento:** a câmera só liga após "Aceitar" (estado `PEDINDO_CONSENTIMENTO`);
  "Recusar" leva a atendimento por bilhete/intérprete, **sem travar**; obrigatório, recusável e
  **nunca persistido** entre atendimentos.
- **Minimização/retenção:** o pipeline trabalha sobre **landmarks (57 pontos normalizados)**, não sobre
  o vídeo. O CSV (opcional, só com a demo ligada) grava landmarks/métricas — não frames. **Vídeo (MP4)
  fica DESLIGADO por padrão** (`isRecording=false`; só liga por toque em `toggleRecording`).

**Onde está implementado (arquivo e função do mecanismo):**
- Consentimento: `libras/dialogo/DialogOrchestrator.kt` (`pedirConsentimento`/`aceitarConsentimento`/
  `recusarConsentimento`) + `camera/PoliticaCamera.kt`; `evento consentimento` no CSV.
- Local: `libras/reconhecimento/` (MediaPipe/TFLite), `libras/audio/` (Vosk/Piper).
- Retenção: `libras/diagnostico/GravadorSessao.kt` (opt-in); `stream/VideoRecorder.kt` (MP4 off por padrão).

**Como medir:** `evento consentimento` (aceito/recusado/reaproveitado); inspeção de código dos arquivos
acima; ausência de chamadas de rede fora de VLibras (grep por `http`/SDKs de nuvem no `libras/`).

## 5 · Eficiência de bateria

**Critério:** estratégia clara de economia; medido em uso contínuo — % a cada 10 min nos óculos e no
celular; fps × resolução; tempo de HFP ativo; estratégia com o que foi preterido.

**Como resolvemos (estratégia, com o preterido):**
- **Modo economia** ao receber bateria baixa/crítica dos óculos (`BATTERY_LOW`/`BATTERY_CRITICAL`):
  encerra a captura e bloqueia religar a câmera — preterimos continuidade por autonomia.
- **Câmera desligada entre turnos** (só liga em captura/escuta); **avatar liberado sob pressão de
  memória**; **MediaPipe em CPU com downscale** — evita o pico de energia/tempo do init da GPU.

**Onde está implementado:**
- `camera/CameraViewModel.kt` (`onBateriaBaixa`) + `camera/PoliticaCamera.kt` (`ativarEconomia`).
- `libras/reconhecimento/LandmarkExtractor.kt` (downscale/menos candidatos, CPU).

**Como medir + número real:**
- **Bateria do celular %/10 min:** `metrica bateria_pct` (`BatteryManager`, `libras/diagnostico/
  LeitorSistema.kt`) — derivar o Δ da série. **Nesta coleta: inválida (celular no USB)**.
- **fps × resolução:** métricas de fps + `config_captura` (processado ~11, MEDIUM/24).
- **Térmico:** `metrica folga_termica`/`estado_termico` (nominal, `estado_termico=0`).
- **Tempo de HFP ativo:** derivado dos `evento perfil_bluetooth` — intervalo entre um `perfil=HFP` e o
  `perfil=A2DP` seguinte (`ts_ms`). HFP fica ativo só durante a escuta, minimizando o consumo do rádio.
- **Bateria dos óculos %/10 min (observação manual):** o SDK não expõe % contínua, então anotamos no
  início e no fim. **Medição 2026-09-18: 64% → 54% (Δ 10 pp)** durante a sessão de teste **fora do
  USB**. Janela do bloco de sessões (13:01→13:46, 44,5 min) = **~2,2 pp/10 min** (~13,5 pp/h,
  autonomia linear projetada ~7,4 h); considerando só a captura contínua (13:19→13:46, 27 min) =
  **~3,7 pp/10 min**. Método: leitura do indicador dos óculos, ancorada nos `ts_ms` do 1º e do último
  CSV da sessão.

**Lacuna:** a bateria dos óculos ainda depende de **observação manual** (não há telemetria contínua
no SDK); a leitura acima é empírica e deve ser refeita em uso representativo controlado.

---

## Resumo do estado (para os CPs)

| Item | Estado | Número / como fechar |
|---|---|---|
| IA — latência + modelo | ✅ medido | 76 ms med / 119 pior, n=52; ST-GCN TFLite no celular |
| Visão — MediaPipe | ✅ medido | 97 ms/frame, ~11 fps |
| Áudio — TTS até 1º som | ✅ medido | 509 ms (fluxo) / 465 ms (modelo) |
| STT — Vosk | ✅ medido | 268 ms fechar texto / 412 ms fim-fala→texto |
| Câmera — fps × resolução, rota | ✅ medido | recebido ~24 / processado ~11; MEDIUM; OCULOS |
| Privacidade — local, consentimento, retenção | ✅ implementado | arquivos acima; vídeo off por padrão |
| Bateria celular %/10 min | ⚠️ coletar fora do USB | `bateria_pct` |
| Perfil Bluetooth HFP × A2DP | ✅ registrado | `evento perfil_bluetooth` a cada troca |
| Tempo de HFP ativo | ✅ derivável | intervalo entre `perfil=HFP` e `perfil=A2DP` |
| Bateria dos óculos %/10 min | ✅ empírico | 64%→54%, ~2,2 pp/10 min (obs. manual, fora do USB, 2026-09-18) |
| 1º frame bruto (isolado) | ⚠️ | hoje medimos 1º frame útil (inclui consentimento) |

## Próximos passos

1. **Coleta fora do USB** para bateria do celular %/10 min (CP2.5) — e, na mesma sessão, conferir o
   `perfil_bluetooth` (HFP/A2DP) e o tempo de HFP, agora registrados.
2. **Fechar o vocabulário da demo** nos sinais fortes (filho, medo, banheiro, cinco, vontade) — o
   escopo permite fechar vocabulário/cenário.
3. Ganhar mais fps para sinais rápidos: **paralelizar pose+mãos na CPU** (~+30%) ou **GPU em segundo
   plano** (destrava os ~22 fps sem os 55 s de init).

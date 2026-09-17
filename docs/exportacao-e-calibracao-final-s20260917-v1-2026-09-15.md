# Exportação real e calibração externa — checkpoint final semente 20260917

**15/09/2026.** Primeira exportação real e primeira tentativa real de
calibração do `modelo_final.pt` produzido no Kaggle. Nenhuma alteração de
código nesta etapa — só execução dos módulos já existentes
(`exportar.py`, `calibracao_externa.py`) sobre o checkpoint e o conjunto de
calibração já documentados.

## 1. Checkpoint usado

`experimentos-privados/final-s20260917-v1/modelo_final/modelo_final.pt`
(privado, gitignorado), baixado do Kaggle pelo usuário. Conferido campo a
campo contra a política antes de qualquer uso:

| campo | valor | esperado |
|---|---|---|
| sha256 | `c7851d8ae9dd46b9aaf222ada2e02f158d69938cfd906aafda4d24779e2ef610` | bate com `checkpoint-final.json` |
| backbone | `7a6e997c5830139162b32bc9b37a48e6eb5b8d6222836da87cb95e94ecf6baf5` | o hash aprovado |
| commit | `837b30e019617f87812c36695dc0915e88fc7500` | commit mais recente na branch, correção da hierarquia MINDS no Kaggle |
| comando | `--arquitetura gcn --ossos --com-z --z-recentrado --kernel-temporal 9 --fontes minds --inventario-final ... --final --politica-final ultima --semente 20260917 --epocas 120` | bate com `politica-modelo-final-2026-09-14.md` |
| ambiente | Tesla T4, CUDA 12.8, torch 2.10.0+cu128 | — |
| época | 120/120, `aprovado_entrega: false` | — |
| selftest | 12/12 OK | — |

## 2. Exportação real (`exportar.py --backend ai-edge`)

Venv isolada (`~/.venv-libras-export`, fora do repo, `requirements-export.txt`):
torch 2.13.0, torchvision 0.28.0, litert-torch 0.9.4, ai-edge-litert 2.2.0.

```
[export] modo=landmarks entrada=(1, 96, 57, 3) classes=20 quantizacao=nenhuma
[export] gravado sinal_classifier (2.0 MB, backend=ai-edge)
[export] paridade PyTorch↔TFLite: max_dif=1.07e-04 top1_discordante=0/8
```

Saída em `experimentos-privados/export-final-s20260917-v1/` (privado):
`sinal_classifier` (`.tflite`, 2.0MB), `sinal_classifier.json` (sidecar,
sha256 `616d1e1c91d4d081f39e25143c9781c18ee19f460ef7023a6b03e3eb0393da4c`),
`sinal_classifier.labels.txt`. 20 rótulos, contrato `[1, 96, 57, 3]` float32,
`imputacao_embutida: true`. **Sem bloco `calibracao`** — não anexado (ver §3).

Esta é a primeira exportação real desta arquitetura vencedora com um
checkpoint `--final` de verdade — não é o smoke test nem o piloto M01.
Ainda não avaliada em Android nem com gravações reais dos óculos.

## 3. Calibração externa — protocolo NÃO atingido

Protocolo fixado **antes** da inferência, por decisão do usuário:
`acc_minima=0.90`, `cobertura_minima=0.50` (mesmo `acc_minima` já usado na
calibração LOSO; cobertura pensada para um conjunto pequeno de 50 clipes).

```
$ calibracao_externa.py protocolo --acc-minima 0.90 --cobertura-minima 0.50
$ calibracao_externa.py inferir --checkpoint ... --manifesto calibracao_naovista_manifesto.json ...
$ calibracao_externa.py ajustar --evidencias ... --saida calibracao.json
T=2.82478; limiar=0.7172944432815905; cobertura_ajuste=28.0%; meta_nao_atingida=True
```

**Resultado (50 clipes, 10 pessoas, V-LIBRASIL + MALTA):**

| métrica | valor |
|---|---|
| erros | 27/50 (54% de acerto bruto) |
| acc V-LIBRASIL | 46,7% (14/30) |
| acc MALTA | 45,0% (9/20) |
| ECE sem temperatura | 0,385 |
| ECE com temperatura (T=2,82) | 0,160 |
| acurácia dos aceitos (limiar 0,717) | 92,9% |
| cobertura obtida | 28,0% (meta: 50%) |
| **meta_nao_atingida** | **true** |

**O protocolo pré-registrado não foi atingido — `exportar.py` recusa esse
JSON em `--calibracao` (`validar_exportacao` checa `meta_nao_atingida is not
False` e recusa).** Não houve segunda tentativa com critérios diferentes —
mudar o protocolo depois de ver o resultado invalidaria a evidência por
definição. A exportação de §2 permanece sem bloco de calibração.

### Leitura honesta do resultado

Erro de ~54% num conjunto onde o checkpoint atinge ~96,6% em LOSO (MINDS)
é grande demais para ser só "confiança mal calibrada" — é sinal de
generalização fraca entre corpora, consistente com os limites já
documentados (câmera/enquadramento/população diferentes, exposição prévia
de 9 das 10 pessoas ao pré-treino sem gradiente supervisionado direto).
Acurácia é praticamente igual entre V-LIBRASIL (46,7%) e MALTA (45,0%) —
não é um problema de uma fonte só.

**Padrão observado, não investigado a fundo:** a classe `america` aparece
como previsão errada 6 vezes (verdadeiro era `ruim` ×3, `medo` ×2, `sapo`,
`conhecer` ×2) contra só 3 ocorrências reais no conjunto — um viés de
atração aparente, não confirmado como causa nem como padrão estável (mesmo
tipo de ressalva que o achado lateral do WLASL sobre `esquina↔sapo`: pode
não ser estável entre execuções). Não investigado nesta etapa — registrado
para quem for olhar mais a fundo.

**O que isso não prova:** que o checkpoint MINDS-domínio é ruim (LOSO
continua sendo a medida válida no domínio de treino) nem que o app vai ter
esse desempenho (câmera dos óculos é um domínio diferente de novo, não
V-LIBRASIL/MALTA). Prova que **este conjunto específico não serve para
aprovar um limiar de confiança de entrega** com a barra escolhida — é
exatamente o tipo de resultado que o protocolo a priori existe para
capturar honestamente, mesmo quando desagrada.

## 4. Estado depois desta etapa

- `.tflite` real existe, exportado e com paridade PyTorch↔TFLite conferida.
  Ainda não testado com vídeo real da câmera dos óculos.
- **Atualização 15/09 — executado no Android (emulador).** Fixture privado gerado
  com `scripts/fixture_paridade_classificador.py --checkpoint` em
  `experimentos-privados/paridade-real-final-s20260917-v1/`. O `.tflite` do fixture
  é byte a byte o da exportação (`616d1e1c…`, checkpoint `c7851d8a…`). Emulador
  Android 16 (API 36, x86_64, KVM), testes com
  `-PlibrasLivre.classificadorFixtures=<fixture>` e
  `-PlibrasLivre.permitirAssetsFaltando=true` (modelos de voz/wake word não
  baixados; não usados por estes testes):
  - `ClassificadorSmokeTest` (instrumentado): **4/4** — carrega pelo sidecar e
    classifica; recusa sidecar adulterado; o `.tflite` reproduz os logits do
    PyTorch sobre a entrada do app; o caminho inteiro do app reproduz o PyTorch e
    o top-1 do caminho de treino.
  - `ParidadeCaminhoAppTest` (JVM): **1/1** — normalização, imputação e
    reamostragem pelo tempo do app reproduzem o Python.
  Entradas sintéticas (3 sequências): prova paridade numérica do app com o
  modelo real, não acurácia nem o extrator Tasks rodando em vídeo.
- Calibração de entrega **continua sem existir** — nem a LOSO (nunca foi
  esse o objetivo) nem esta externa (não atingiu o protocolo). O sidecar
  exportado não carrega `calibracao`; o app, se usasse este `.tflite` hoje,
  usaria só o limiar manual de `ConfiguracoesDemo` (0,60 por padrão).
- Nenhum arquivo desta etapa foi commitado — todos os artefatos pesados
  (`.pt`, `.tflite`, evidências, logits) ficam em `experimentos-privados/`,
  gitignorado, como de costume.

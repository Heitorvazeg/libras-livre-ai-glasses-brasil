# Libras Livre — Plano de PoC: Reconhecimento Signer-Independent

> **Status (2026-09-12): concluído.** A PoC foi executada; o resultado e o
> veredito estão em `computer-vision-model/PoC/results/relatorio.md` e em
> `computer-vision-model/PoC/README.md` §6.4. Este documento fica como registro do
> método e do critério de decisão definido antes de medir.

**Equipe 3G1B · Programa AI Glasses Brasil**

---

## 1. Objetivo

Responder a uma única pergunta técnica antes de investir tempo em qualquer outra parte do sistema:

> **MediaPipe Holistic + um classificador simples conseguem reconhecer um vocabulário fechado de Libras, generalizando entre pessoas diferentes, na distância e ângulo de câmera de um atendimento de balcão?**

Todo o resto do plano existe para responder isso com o mínimo de esforço possível e com um critério de decisão definido antes de começar.

---

## 2. Fora de escopo desta PoC

Para manter o foco, os seguintes itens do documento de arquitetura **não** entram nesta fase:

- Infraestrutura de nuvem, deploy, API.
- Pipeline de coleta contínua e retrain em produção.
- Solução de tela para a pessoa surda (seção 5 do documento de arquitetura).
- Núcleo offline de emergência.
- Fluxo formal de consentimento institucional (aqui, um consentimento simples de participação em pesquisa é suficiente — ver seção 4.4).
- Contextualização de sinais em frases — a PoC classifica sinais isolados, não gera linguagem natural.
- Hardware final dos óculos — qualquer câmera (celular) na posição certa serve.

---

## 3. Vocabulário da PoC

**Tamanho:** 10 a 15 sinais.

**Critério de seleção:**
- Priorizar sinais que já fazem parte do vocabulário planejado para o MVP de atendimento (saudação, pedir ajuda, marcar consulta, sim/não, dor, etc.) — assim a PoC gera dado reaproveitável.
- Incluir propositalmente sinais com configurações de mão e movimentos variados entre si (não só os mais fáceis de diferenciar visualmente) — o objetivo é estressar o modelo, não facilitar o resultado.
- Evitar sinais que dependem de expressão facial ou marcação não-manual para terem sentido completo (fora de escopo, ver seção 10 do documento de arquitetura).

**Ação:** definir a lista final com apoio de um profissional de Libras ou pessoa surda consultora antes de gravar — mesmo numa PoC, vocabulário errado invalida o resultado.

---

## 4. Protocolo de coleta de dados

### 4.1 Participantes
- **Mínimo 5, ideal 8 pessoas diferentes** que sinalizam Libras, o mais diverso possível em idade/porte/estilo de sinalização dentro do que for viável recrutar no prazo.
- Uma única pessoa repetindo o sinal várias vezes **não substitui** isso — mede uma coisa diferente (consistência de um sinalizante, não generalização entre pessoas).

### 4.2 Repetições
- **5 repetições por sinal, por pessoa** é um piso razoável para a PoC (ex.: 12 sinais × 5 repetições × 6 pessoas ≈ 360 clipes). Mais repetições ajudam, mas o ganho marginal cai rápido — priorize mais pessoas antes de mais repetições por pessoa.

### 4.3 Setup físico
- Câmera na altura e distância que os óculos teriam num balcão de atendimento (aprox. altura dos olhos de quem está de pé ou sentado atrás do balcão, ~1 a 1,5m do sinalizante).
- Luz de ambiente real (sala, escritório) — não estúdio com iluminação controlada.
- Pode usar o celular na mão ou apoiado simulando a posição da câmera dos óculos; não é necessário esperar pelo hardware final.
- Registrar o setup (fotos, medidas) para poder reproduzir depois com o hardware real.

### 4.4 Consentimento dos participantes
- Mesmo sendo uma PoC interna, cada participante deve assinar (ou confirmar por escrito/áudio) um consentimento simples: explicando que o vídeo é usado só para desenvolver o protótipo, informando se será descartado após extração dos landmarks ou retido, e por quanto tempo.
- Se possível, os vídeos brutos são descartados logo após a extração de landmarks, mantendo só os pontos — coerente com o princípio de privacidade já adotado no restante do projeto.

---

## 5. Pipeline técnico

### 5.1 Extração de landmarks
- MediaPipe Holistic, extraindo mãos (21+21 pontos) e pose superior do corpo.
- Salvar os landmarks normalizados por clipe (ex.: um arquivo `.npy` ou `.json` por vídeo), não o vídeo em si além do necessário para depuração inicial.

### 5.2 Baseline: DTW
- Para cada sinal, guardar os exemplos de referência (das pessoas de treino) como sequências de landmarks.
- Classificar um clipe novo pela menor distância DTW até os exemplos de referência.
- Vantagem: não exige treino de rede neural, resultado em horas, serve de piso de comparação para qualquer coisa mais sofisticada depois.

### 5.3 Opcional, se sobrar tempo: classificador leve treinado
- Um GRU pequeno ou MLP sobre os mesmos landmarks normalizados, treinado com os dados disponíveis.
- Comparar diretamente com o baseline de DTW no mesmo protocolo de avaliação (seção 6) — só vale a pena adotar se superar o DTW de forma consistente.

### 5.4 Estrutura de repositório sugerida
```
poc-libras/
├── data/
│   ├── raw/              # vídeos brutos (descartáveis após extração)
│   └── landmarks/        # landmarks extraídos, por pessoa/sinal/repetição
├── src/
│   ├── record.py         # captura de vídeo com marcação de início/fim
│   ├── extract.py        # extração via MediaPipe Holistic
│   ├── dtw_classifier.py
│   ├── nn_classifier.py  # opcional
│   └── evaluate.py       # protocolo leave-one-signer-out
├── results/
│   └── confusion_matrix.png
└── README.md
```

### 5.5 Como implementar, passo a passo

Esta seção existe para que qualquer pessoa da equipe consiga sair do zero direto para código, sem precisar adivinhar detalhes.

**Passo 1 — Gravação (`record.py`)**
- Abrir a câmera com OpenCV (`cv2.VideoCapture`).
- Cada clipe é gravado sob demanda: pressiona uma tecla para começar a gravar um sinal, sinaliza, pressiona de novo para parar.
- Salvar o clipe como vídeo (`.mp4`) nomeado de forma sistemática, por exemplo `dados/raw/pessoa03_sinal-ajuda_rep02.mp4` — o nome do arquivo já carrega pessoa, sinal e repetição, o que evita precisar de uma planilha de metadados separada.

**Passo 2 — Extração de landmarks (`extract.py`)**
- Para cada vídeo salvo, rodar `mediapipe.solutions.holistic.Holistic` frame a frame.
- De cada frame, extrair: 21 pontos (x, y, z) da mão esquerda, 21 da mão direita, e um subconjunto de pontos de pose (ombros, cotovelos, pulsos, nariz — o suficiente para dar contexto de tronco sem inflar a dimensionalidade).
- **Normalização é o passo que mais afeta o resultado e o mais fácil de esquecer:** subtrair a posição de um ponto de referência estável (ex.: o ponto entre os ombros) de todos os outros pontos do frame, e dividir pela distância entre os ombros. Isso torna os landmarks invariantes à distância da pessoa até a câmera e à posição dela no quadro — sem isso, o modelo aprende a distinguir "pessoa mais perto da câmera" em vez de "sinal diferente".
- Empacotar a sequência de frames normalizados de um clipe em um único array (`num_frames × num_pontos × 3`) e salvar como `.npy`, com o mesmo nome-base do vídeo de origem.

**Passo 3 — Baseline DTW (`dtw_classifier.py`)**
- Usar uma biblioteca pronta de DTW (ex.: `dtaidistance` ou `fastdtw`) em vez de implementar do zero — a lógica do algoritmo não é o gargalo aqui, é a qualidade dos landmarks.
- Achatar cada frame (todos os pontos e coordenadas) em um vetor único por passo de tempo, gerando uma sequência 1D-por-frame que a biblioteca de DTW consegue comparar.
- Classificação de um clipe novo: calcular a distância DTW entre ele e **todos** os clipes de referência disponíveis (dos sinalizantes de treino), e atribuir a classe (sinal) do exemplo de referência com menor distância — é um classificador de vizinho mais próximo (1-NN) usando DTW como métrica de distância.

**Passo 4 — Avaliação leave-one-signer-out (`evaluate.py`)**
- Laço externo: para cada pessoa `p` na lista de participantes.
  - Conjunto de referência = todos os clipes de todas as pessoas, **exceto** `p`.
  - Conjunto de teste = todos os clipes de `p`.
  - Para cada clipe de teste, classificar contra o conjunto de referência (passo 3) e registrar se acertou.
- Ao final do laço externo, calcular a acurácia média entre todas as rodadas — essa é a métrica reportada na seção 6.
- Gerar a matriz de confusão agregando os erros de todas as rodadas (linhas = sinal verdadeiro, colunas = sinal previsto), para identificar visualmente quais pares de sinais o sistema mais confunde.

**Passo 5 — Opcional: classificador treinado**
- Só depois do baseline DTW estar rodando e medido: treinar um GRU pequeno (2 camadas, poucas dezenas de unidades) recebendo a mesma sequência de landmarks normalizados como entrada, com a última camada tendo tantas saídas quanto sinais no vocabulário.
- Avaliar com o mesmo protocolo leave-one-signer-out do passo 4, e comparar a acurácia diretamente com o DTW antes de decidir qual abordagem seguir para o MVP.

---

## 6. Protocolo de avaliação

### 6.1 Leave-one-signer-out (obrigatório)
- Para cada pessoa `p` no conjunto de participantes: treinar/montar referências com todas as outras pessoas, testar exclusivamente nos clipes de `p`.
- Repetir para cada pessoa, uma de cada vez.
- Reportar a acurácia média entre as rodadas — essa é a métrica que representa a realidade de produto (sistema encontrando alguém que nunca viu).

### 6.2 Métricas
- Acurácia top-1 média (métrica principal).
- Matriz de confusão agregada de todas as rodadas — identifica pares de sinais sistematicamente confundidos, útil para decidir se é problema de vocabulário (sinais parecidos demais) ou de modelo.

### 6.3 Critério de decisão, definido antes de rodar o experimento
| Resultado (acurácia signer-independent) | Decisão |
|---|---|
| ≥ 80% | Sinal verde — seguir para o MVP do hackathon com essa abordagem |
| 60–80% | Zona de atenção — revisar vocabulário (sinais confundidos na matriz de confusão), aumentar repetições, ou tentar o classificador treinado antes de decidir |
| < 60% | Sinal vermelho — reconsiderar abordagem (vocabulário, distância de câmera, ou arquitetura de modelo) antes de investir mais tempo |

---

## 7. Cronograma sugerido

| Dia | Atividade |
|---|---|
| 1 | Definir vocabulário final (com apoio de consultor de Libras), recrutar participantes, preparar script de consentimento |
| 2 | Gravação com os participantes no setup físico definido |
| 3 | Extração de landmarks, implementação do baseline DTW |
| 4 | Rodar avaliação leave-one-signer-out, gerar matriz de confusão |
| 5 | Analisar resultado, aplicar critério de decisão, documentar conclusão e próximos passos |

Ajustável conforme a disponibilidade real dos participantes, que costuma ser o gargalo, não o código.

---

## 8. Papéis sugeridos (equipe de 3)

- **Visão computacional / IA:** pipeline de extração, DTW, avaliação.
- **Mobile/Android:** captura de vídeo no setup físico (pode ser um app simples ou até gravação direta pelo celular nesta fase).
- **Produto:** recrutamento de participantes, script de consentimento, definição do vocabulário com apoio externo, documentação do resultado.

---

## 9. Riscos específicos da PoC

- **Recrutar 5–8 sinalizantes diferentes no prazo pode ser o maior gargalo**, não o código — comece essa frente no dia 1, em paralelo com tudo o resto.
- **Vocabulário mal escolhido invalida o resultado** — sinais fáceis demais de diferenciar geram acurácia artificialmente alta que não se sustenta depois com vocabulário maior.
- **Setup físico inconsistente entre participantes** (câmera em ângulos diferentes a cada gravação) introduz ruído que pode ser confundido com limitação do modelo — vale um checklist rápido de setup antes de cada gravação.

---

## 10. Entregáveis ao final da PoC

- Acurácia signer-independent média + matriz de confusão.
- Decisão de ir/não ir para o MVP com a abordagem testada, segundo o critério da seção 6.3.
- Dataset de landmarks próprio (reutilizável no MVP e, futuramente, no pipeline de produto).
- Lista de sinais problemáticos identificados (se houver) para revisão de vocabulário.

---

*Este plano é deliberadamente enxuto. Qualquer decisão de arquitetura mais ampla (nuvem, retrain, tela para o visitante) fica para depois que esta pergunta estiver respondida.*

# Modelo de visão: o que testar antes de entregar ao app

> **Atualização de 14/09:** consulte a [revalidação e ordem de execução vigente](validacao-visao-app-2026-09-14.md).
> O original abaixo é preservado como especificação histórica: receita M1,
> interpretação temporal, limiar/calibração e alcance dos testes têm ressalvas.

**Data:** 2026-09-13 · **Para:** quem vai mexer no ST-GCN (`computer-vision-model/treino/`)
**Contexto:** [plano de prontidão da demo](prontidao-demo/README.md), em especial o
[ponto 2](prontidao-demo/02-classificador.md)

O app foi desenhado **sem mudar o modelo**. Mas várias decisões do app fazem suposições sobre
o modelo que só o treino consegue confirmar, e algumas medições do treino mudam parâmetros do
app. Este documento lista esses pontos, por que cada um importa, como medir e o que entregar.

Regras do projeto que continuam valendo em todos os itens:

- **Avaliação leave-one-signer-out (LOSO).** Número medido com a pessoa de teste no treino não
  vale nada aqui.
- **Variância de ~1,7 ponto entre execuções.** Diferenças menores que ~2 pontos são ruído.
- **Vídeo de estúdio é teto otimista.** O número de uso real só sai com gravações do app.

---

## O que o app vai entregar ao `.tflite` (já decidido)

Isto é fixo do lado do app. Os testes abaixo verificam se o modelo aguenta.

| Etapa no app | Detalhe |
|---|---|
| Landmarks | MediaPipe **Tasks** (`pose_landmarker_lite` + `hand_landmarker`), **não** o Holistic do treino |
| Mão esquerda/direita | cada mão vai para o **pulso da pose mais próximo** (15/16), como o Holistic |
| Normalização | a de `PoC/src/extract.py`: `(x·w, y·h, z·w) − média dos ombros`, dividido pela distância entre ombros em x,y; mão ausente = zeros |
| Taxa de quadros | 24 fps dos óculos, **menos** quando o celular descarta frames, **sem espaçamento uniforme** |
| Segmento | do início do movimento ao fim, com margem de **250 ms antes e 150 ms depois**; repouso natural entre os sinais; duração máxima de 3,5 s |
| Imputação | lacunas de mão de até **5 frames**, na linha do tempo real, **antes** de reamostrar |
| Tempo | reamostragem linear **pelo timestamp** para **96 frames**; o grafo reamostra 96 → 64 |
| Saída | softmax dos logits, com `temperatura` do sidecar se existir; limiar inicial de 60% sobre a **menor** confiança da frase |

---

## Prioridade antes da demo

| # | Item | Por quê |
|---|---|---|
| M1 | Checkpoint de entrega e export | sem ele não há reconhecimento no app |
| M2 | Acurácia pelo caminho do app | valida as decisões de reamostragem e imputação |
| M3 | Taxa de quadros | 24 fps irregulares ≠ vídeo do dataset |
| M4 | Forma do segmento (repouso nas bordas) | define as margens do app |
| M5 | Mão esquerda/direita | sanidade rápida da convenção |
| M7 | Calibração da confiança | define o limiar do fluxo "repita" |
| M9 | Recall dos sinais do roteiro | a demo usa 6 sinais específicos |

**Depois, ou se sobrar tempo:** M6 (MediaPipe Tasks × Holistic), M8 (gestos fora do
vocabulário), M10 (ponto de vista em primeira pessoa), M11 (quantização).

---

## M1. Checkpoint de entrega e export

**Por quê.** O app implementa o contrato com o export `--smoke`; o modelo real ainda não
existe no repositório.

**Como.**
```bash
cd computer-vision-model/treino
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado            # LOSO
python treinar.py --arquitetura gcn --ossos --com-z --z-recentrado --final    # checkpoint de entrega
python exportar.py --checkpoint <modelo_final.pt> --saida ../models/sinal_classifier.tflite
```
- **Quantização `nenhuma`** (padrão): o GCN tem ~1,9 MB em float32, e o efeito do int8 na
  acurácia nunca foi medido.
- **`--frames` no padrão (96).** O app lê o valor do sidecar, mas trocar exige avisar.
- O checkpoint precisa declarar **todas** as flags de representação nos metadados (`com_z`,
  `z_recentrado`, `ossos`, `movimento`, `sem_imputacao`). O `exportar.py` recusa se faltar
  alguma.

**Entregar.**
- `sinal_classifier.tflite`, `sinal_classifier.json` (sidecar) e `sinal_classifier.labels.txt`;
- o relatório LOSO dessa configuração;
- o hash do commit que treinou.

Os arquivos vão para `mobile-app-companion/app/src/main/assets/`, versionados (modelo interno).
O teste do app confere o sha256 do sidecar.

## M2. Acurácia pelo caminho do app

**Por quê.** O treino faz N frames → 64 numa reamostragem só. O app faz N (a 24 fps, irregular)
→ imputação → 96 pelo tempo → 64 no grafo. Numa medição sintética, a divergência ficou abaixo
do tremor do MediaPipe (erro médio de 0,04–0,37% da amplitude), mas só a acurácia LOSO confirma.

**Como.** Uma função `caminho_do_app(clipe, fps_origem)` que, para cada clipe de teste da LOSO:
1. decima para 24 fps pelo tempo (M3);
2. recorta com as margens do app (M4);
3. imputa com limite de 5 frames;
4. reamostra pelo tempo para 96;
5. passa pelo grafo (ou pelo `para_sequencia` para 64).

Comparar a acurácia LOSO com o caminho do treino, **no mesmo checkpoint**.

**Entregar.** Acurácia dos dois caminhos por pessoa e a média. Diferença acima de ~2 pontos
volta para o app como problema.

## M3. Taxa de quadros

**Por quê.**
- Os óculos entregam 24 fps, e o celular descarta frames quando o MediaPipe atrasa.
- A taxa de quadros dos vídeos de treino (MINDS, V-LIBRASIL, MALTA) **não está documentada**.
- O limite de imputação de 5 frames significa ~167 ms a 30 fps, mas ~208 ms a 24 fps e ~417 ms
  a 12 fps.

**Como.**
1. Medir e registrar a taxa de quadros de cada dataset (`cv2.CAP_PROP_FPS` nos vídeos, ou o
   metadado da extração).
2. Pelo caminho do M2, LOSO com os clipes decimados para **24, 15 e 12 fps**, e a 24 fps com
   **20% e 40% de frames descartados** ao acaso (simula o celular atrasando).
3. Repetir com o limite de imputação expresso em **tempo** (ex.: 170 ms) em vez de frames.

**Entregar.**
- Tabela de acurácia por taxa de quadros e por descarte.
- Recomendação: o limite de imputação fica em frames ou vira milissegundos?
- Até que fps dá para baixar sem perder mais que ~2 pontos. Isso responde o seletor de fps do
  app (7.5).

## M4. Forma do segmento

**Por quê.** Os clipes começam e terminam em repouso; o app recorta do início do movimento ao
fim, com margens de 250/150 ms. Se o repouso das bordas do treino for bem maior, o segmento do
app fica "mais apertado" que o treino.

**Como.**
1. Com a **mesma métrica de velocidade do detector do app**, medir em cada clipe do MINDS
   quanto tempo há de repouso antes do primeiro movimento e depois do último. A métrica:
   média da velocidade por mão, máximo entre mãos e pulsos, janela de ~100 ms, em ombros/s,
   limiares de 0,7 para começar e 0,4 para continuar.
2. LOSO pelo caminho do M2 variando as margens (0, 150, 250, 400, 600 ms antes e depois),
   contra o clipe inteiro.

**Entregar.**
- A distribuição do repouso de borda (mediana e p90).
- As margens que mantêm a acurácia; elas substituem 250/150 ms no app (1.1).

## M5. Mão esquerda/direita

**Por quê.** O app atribui cada mão ao pulso da pose mais próximo. O treino usou o Holistic,
que faz algo equivalente, mas isso não foi conferido nos `.npy`.

**Como.**
1. Em uma amostra de clipes, medir a distância do ponto 0 de cada bloco de mão ao pulso
   correspondente da pose. O bloco "esquerda" deve estar consistentemente mais perto do pulso
   esquerdo. Contar as exceções.
2. **Sanidade do estrago:** LOSO com os blocos de mão **trocados** (sem espelhar o x) no teste.
   Mostra quanto uma troca custa.

**Entregar.** A taxa de clipes em que a convenção não vale, e a acurácia com as mãos trocadas.

## M6. MediaPipe Tasks × Holistic (depois)

**Por quê.** O app usa detectores separados (`pose_landmarker_lite` e `hand_landmarker`),
diferentes do Holistic do treino. As detecções, o z e as falhas de mão podem ter outra
distribuição.

**Como.**
1. Reextrair o conjunto de **uma** pessoa de teste com a API Tasks do MediaPipe em Python, com
   os mesmos modelos do app e a atribuição pelo pulso.
2. Avaliar o checkpoint (treinado com Holistic) nesse conjunto contra o conjunto Holistic da
   mesma pessoa.
3. Comparar a escala do z entre os dois.

**Entregar.** A queda de acurácia. Se passar de ~2 pontos, avaliar reextrair o treino com Tasks.

## M7. Calibração da confiança

**Por quê.** O fluxo "repita" do app usa a **menor** confiança da frase contra um limiar
(inicial de 60%). A confiança do softmax costuma ser exagerada, e o limiar certo depende do
modelo.

**Como.**
1. **Guardar os logits.** O `treinar.py` hoje salva só a classe prevista por clipe (`predicoes`).
   É preciso salvar também os logits (ou probabilidades) de cada clipe de teste da LOSO.
2. **Diagrama de confiabilidade e ECE** com essas saídas.
3. **Temperature scaling** ajustado nos clipes de **validação** de cada rodada (nunca no de
   teste) e avaliado no teste.
4. **Curva de cobertura × acurácia dos aceitos** por limiar. Escolher o limiar em que os sinais
   aceitos acertam ≥ 90%, e registrar quanto isso rejeita.

**Entregar.** No sidecar, um bloco novo que o app já sabe ler:
```json
"calibracao": {"temperatura": 1.8, "limiar_sugerido": 0.55, "cobertura_esperada": 0.87,
               "acuracia_dos_aceitos": 0.91, "metodo": "temperature scaling na validação LOSO"}
```
(valores ilustrativos). O `exportar.py` precisa aprender a gravar esse bloco.

## M8. Gestos fora do vocabulário (depois)

**Por quê.** O modelo sempre responde uma das 20 classes. Ajeitar o cabelo, um gesto de
conversa ou um sinal que não está entre os 20 viram algum sinal.

**Como.**
- **Conjunto fora do vocabulário:** sinais da V-LIBRASIL e do MALTA que **não** estão entre os
  20, mais trechos de repouso e de transição tirados das bordas dos clipes.
- Medir a distribuição da confiança máxima nesse conjunto contra os sinais verdadeiros, e quanto
  passa do limiar do M7.

**Entregar.** A taxa de falso aceite no limiar escolhido. Se for alta, fica registrado para o
roadmap (classe "nenhum/ruído" ou rejeição treinada).

## M9. Recall dos sinais do roteiro

**Por quê.** A demo usa **FILHO, VACINA, VONTADE, CINCO, MEDO, BANHEIRO**
([roteiro, 2.9](prontidao-demo/02-classificador.md#29-roteiro-da-demo)). Um sinal com recall
baixo ou confundido com outro do roteiro derruba a sequência inteira.

**Como.** Da matriz de confusão LOSO do checkpoint de entrega (`matriz_confusao.npy`): recall de
cada um dos 6 sinais e as confusões principais de cada um.

**Entregar.** A tabela e, se algum sinal ficar ruim, a sugestão de troca por outro dos 20 que
preserve a frase.

## M10. Ponto de vista em primeira pessoa (depois)

**Por quê.** A aumentação atual (`representacao.aumentar`) faz rotação **no plano** (σ = 12°),
zoom, translação e espelhamento. A câmera dos óculos fica na cabeça do atendente: vê a pessoa de
cima ou de lado, uma rotação **fora do plano** que nenhuma base de estúdio tem.

**Como.**
1. Com o z, girar o esqueleto em 3D (inclinação de −30° a +30°, giro lateral de ±30°) e
   reprojetar em x,y antes da normalização.
2. Medir a acurácia LOSO por ângulo.
3. Se cair, testar essa rotação 3D como aumentação no treino.
4. Validar com as gravações reais que o app vai produzir no teste com os óculos (CSV do
   gravador, [1.9](prontidao-demo/01-segmentacao.md#19-gravador-de-sessão-csv)).

**Entregar.** A curva de acurácia por ângulo e, se houver aumentação nova, o LOSO com ela.

## M11. Quantização (depois)

Manter float32 na demo. Se quiser int8 (0,47 MB), só com LOSO do modelo **quantizado**, porque o
efeito na acurácia nunca foi medido.

---

## Outros pontos para ter em mente

**Rótulos.** O rótulo `maca` não existe no léxico da contextualização (lá é `maçã`). O time
decidiu que o app **não fala** glosas fora do léxico, então esse sinal nunca será falado. Não
renomear rótulos sem avisar: o app tem testes que cruzam os rótulos do sidecar com o léxico.

**Documentação desatualizada no treino.** Em `computer-vision-model/treino/README.md`, a seção
"Exportação 3D não suportada", e o trecho que diz que não há reamostragem nem imputação
embutidas no grafo, são anteriores à PR #12. O `exportar.py` atual trata z, imputação, ossos e
reamostragem para o GCN. Vale corrigir junto com o M1.

## Checklist de entrega ao app

- [ ] `sinal_classifier.tflite` (float32) + `sinal_classifier.json` + `sinal_classifier.labels.txt`
- [ ] Relatório LOSO da configuração de entrega e hash do commit
- [ ] M2: acurácia pelo caminho do app × caminho do treino
- [ ] M3: tabela por fps e descarte; recomendação do limite de imputação
- [ ] M4: repouso de borda e margens recomendadas
- [ ] M5: taxa de exceções da convenção de mãos
- [ ] M7: bloco `calibracao` no sidecar
- [ ] M9: recall e confusões dos 6 sinais do roteiro

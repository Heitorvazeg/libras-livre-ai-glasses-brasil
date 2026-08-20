# Checklist de setup e sessão de gravação

Por que existe: **setup físico inconsistente entre participantes vira ruído que
depois é confundido com limitação do modelo** (§9 do plano). Dois minutos de
conferência antes de cada sessão protegem o resultado da PoC inteira.

Imprima ou deixe aberto no celular durante as gravações.

---

## 1. Uma vez, ao montar o setup (§4.3)

- [ ] **Altura da câmera:** na altura dos olhos de quem usaria os óculos — de pé
      ou sentado atrás do balcão. Anote a medida: **______ cm do chão**.
- [ ] **Distância câmera → sinalizante:** entre **1,0 e 1,5 m**. Anote: **______ m**.
- [ ] **Ângulo:** câmera de frente para o sinalizante, sem inclinação para cima ou
      para baixo (o celular apoiado, não segurado, evita a deriva ao longo da sessão).
- [ ] **Enquadramento:** cabeça até a cintura no quadro, com folga lateral para os
      braços abertos. Peça para a pessoa abrir os braços e confira se as mãos não
      saem do quadro.
- [ ] **Luz:** ambiente real (sala/escritório), **não** estúdio. Sem janela ou
      lâmpada forte atrás do sinalizante (contraluz apaga as mãos).
- [ ] **Fundo:** o que houver no local — evite só fundos com pessoas se movendo atrás.
- [ ] **Registro do setup:** tire **2 fotos** (uma da posição da câmera, uma do
      enquadramento visto pela câmera) e guarde junto das medidas. É isso que
      permite reproduzir o mesmo setup depois com o hardware real.

Marque no chão com fita a posição dos pés do sinalizante e a do tripé/apoio: as
sessões seguintes começam iguais sem precisar remedir.

## 2. Antes de cada participante

- [ ] Consentimento assinado/confirmado (`docs/consentimento.md`) e arquivado.
- [ ] Código do participante definido e anotado (`pessoa01`, `pessoa02`, …) — **o
      nome da pessoa não entra em nenhum arquivo**.
- [ ] Câmera na marcação, mesma altura e distância da sessão anterior.
- [ ] Celular/câmera com bateria e espaço em disco; notificações silenciadas.
- [ ] Lista de sinais da PoC à mão (`config.yaml`), com a ordem que será gravada.
- [ ] Combinar o gesto de partida: a pessoa começa com as **mãos em repouso**,
      faz o sinal, e volta ao repouso antes de encerrar o clipe.

## 3. Durante a gravação

Comando: `python src/record.py --pessoa 03 --sinal ajuda`

- [ ] ESPAÇO inicia, ESPAÇO encerra — **um sinal por clipe**.
- [ ] 5 repetições do mesmo sinal antes de passar ao próximo (o contador de
      repetição na tela avança sozinho).
- [ ] Repetição saiu errada (sinal trocado, mão fora do quadro, alguém passou na
      frente)? Aperte **D** para descartar o último clipe e refaça.
- [ ] Deixe a pessoa sinalizar no ritmo natural dela. **Não peça para desacelerar**
      — o DTW absorve variação de velocidade, e ritmo artificial vira dado que não
      representa o uso real.
- [ ] A cada troca de sinal, confirme em voz alta qual é o próximo (evita o erro
      mais comum: gravar o sinal certo com o rótulo errado).

## 4. Ao encerrar a sessão

- [ ] Conferir a contagem de arquivos:
      `ls data/raw | grep pessoa03 | wc -l` → deve dar **nº de sinais × 5**.
- [ ] Rodar a extração ainda no dia: `python src/extract.py`
      (avisos de "nenhum frame com pose detectada" ou "% de frames descartados"
      apontam problema de enquadramento — dá para regravar com a pessoa ainda ali).
- [ ] Aplicar a política de vídeo do termo assinado. Se for descarte imediato:
      `python src/extract.py --descartar-video`.
- [ ] Anotar qualquer coisa fora do padrão da sessão (pessoa canhota, sinal feito
      de forma regional diferente, interrupção) — isso explica outliers depois.

## 5. Sintomas comuns e o que fazer

| Sintoma | Causa provável | Ação |
|---|---|---|
| `nenhum frame com pose detectada` | pessoa muito perto/longe, contraluz, tronco cortado | reenquadrar cabeça-cintura, tirar a luz de trás |
| `% dos frames descartados (pose instável)` alto | ombro saindo do quadro ao sinalizar | afastar ~20 cm ou abrir o enquadramento |
| Clipes muito curtos (aviso do `record.py`) | ESPAÇO apertado duas vezes rápido demais | refazer com D e recomeçar o clipe |
| Acurácia baixa só de um participante | setup mudou naquela sessão | conferir as fotos do setup daquela sessão |

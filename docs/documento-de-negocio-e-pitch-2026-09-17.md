# Libras Livre — documento de negócio e pitch

> Gerado em 2026-09-17, complementar ao
> [`guia-tecnico-completo-hackathon-2026-09-17.md`](guia-tecnico-completo-hackathon-2026-09-17.md) (que cobre a estratégia
> tecnológica). Este documento é sobre **o negócio**: o argumento que sustenta o pitch, o
> problema, a evidência, o arranjo legal e o posicionamento — organizado em torno do que
> importa pra apresentar, não em torno de nenhum feedback específico. Toda afirmação foi
> conferida contra o código e os docs do repo; onde não deu pra confirmar, está marcado como
> **⚠️ A VERIFICAR**, nunca apresentado como fato.

---

## 1. O pitch em poucas frases

> Óculos Ray-Ban Meta que traduzem Libras para fala e voz para Libras, num balcão de
> atendimento institucional — posto de saúde, banco, órgão público. A pessoa surda sinaliza
> pros óculos; o celular do atendente reconhece, fala em português; o atendente responde por
> voz; a pessoa surda vê a resposta num avatar 3D em Libras. Tudo on-device, sem depender de
> internet, exceto o avatar.

Três coisas tornam isso um pitch, não só uma demo técnica: é **bidirecional** (a maioria das
soluções de acessibilidade em Libras traduz só num sentido), é **vestível** (a câmera fica na
cabeça de quem atende, não exige a pessoa surda carregar nada), e resolve um problema que **tem
obrigação legal por trás**, não só boa vontade institucional.

---

## 2. O problema e por que agora

O Decreto 5.626/2005 obriga serviços públicos — inclusive os de saúde — a garantir atendimento
em Libras. Na prática, a maioria não tem intérprete disponível o tempo todo: intérprete
qualificado é recurso escasso e caro de manter presencial num balcão que atende dezenas de
pessoas por dia. O resultado é o que já acontece hoje: pessoa surda depende de bilhete escrito,
de um familiar traduzindo, ou simplesmente não é bem atendida.

**O Libras Livre é uma ponte para quando não há intérprete — não um substituto.** Essa
distinção, dita assim no pitch, antecipa a objeção mais previsível da banca e da própria
comunidade surda ("vocês querem substituir intérpretes?"). E não é só retórica: o sistema já
tem essa limitação embutida na arquitetura, não é um ajuste de discurso — reconhecimentos de
baixa confiança são recusados em vez de ditos, e o desenho explicitamente não cobre
consentimento informado, diagnóstico, ato jurídico ou situação de risco de vida
(`docs/libras-livre-arquitetura.md` §7), que continuam exigindo intérprete humano.

---

## 3. A solução, em uma frase por peça

| Peça | O que faz | Por que importa pro pitch |
|---|---|---|
| Óculos (câmera + microfone) | capta o sinal e a voz do atendente | não pede que a pessoa surda tenha ou use qualquer aparelho |
| Reconhecimento de sinal (ST-GCN, on-device) | vídeo → glosa | roda no celular, sem internet, sem mandar vídeo pra nuvem |
| Contextualização (seq2seq + template) | glosa → frase em português | Libras tem gramática própria; uma lista de sinais não é uma frase |
| Fala (Piper/sherpa-onnx) e transcrição (Vosk) | fala a frase; ouve e transcreve a resposta | tudo on-device também, pt-BR |
| Avatar em Libras (VLibras) | mostra a resposta do atendente pra pessoa surda | fecha o ciclo bidirecional — é o que falta nas soluções que só traduzem Libras→voz |

---

## 4. Evidência para levar ao pitch — com a ressalva certa em cada número

O princípio que o próprio projeto já adota (`docs/CONTEXTO.md` §9): "quase todo erro caro veio
de aceitar um número sem perguntar o que ele mede." Os números abaixo são os mais fortes que
existem hoje, cada um com a ressalva que precisa acompanhá-lo:

| Número | O que é | Ressalva obrigatória |
|---|---|---|
| **94,6–94,9%** | reconhecimento de sinal (ST-GCN), *leave-one-signer-out* — pessoa inteira de fora do treino, os 20 sinais completos do MINDS-Libras | variância entre execuções ~1,7pp; vídeo de estúdio frontal, teto otimista pro balcão real |
| **93,3–94,6%** (224-227/240) | mesmo modelo, especificamente nos 6 sinais do roteiro da demo, duas rodadas de treino independentes | são clipes isolados, não frases inteiras; um sinal (`filho`) falha consistentemente com uma pessoa do dataset — saber disso e poder explicar é mais forte que esconder |
| **~35 sinais** | vocabulário fechado disponível sem gravar nada, mesclando bases públicas (§6) | parte dele tem confiança de generalização menor — ver §6 |
| **24× menos parâmetros** | ST-GCN (0,47M) contra a ResNet-18 alternativa (11,25M), empatados em acurácia | decisão de arquitetura registrada e justificada, não a única opção testada |

**Não levar ao pitch:** um número antigo de 85,7% (baseline DTW da PoC inicial) circulou em
versões anteriores do material — o próprio repositório já documenta que aquele recorte de
sinais foi **escolhido depois de ver o resultado** (`docs/investigacao-expansao-dataset.md`),
um viés conhecido. O número atual (94,6-94,9%, LOSO nos 20 sinais completos) é maior **e** mais
honesto — é estritamente melhor de citar.

*Leave-one-signer-out*, em uma frase pra banca: "avaliamos deixando uma pessoa inteira de fora
do treino, porque o produto real vai encontrar alguém que o modelo nunca viu — não existe
calibração por usuário num equipamento institucional."

---

## 5. Vocabulário: por que fechado, e o que isso significa pro roadmap

A demo apresenta um **vocabulário fechado**, não Libras livre e aberta — e isso é uma decisão
de produto correta pro estágio atual, não uma limitação a esconder. Reconhecimento contínuo de
Libras com vocabulário aberto é problema de pesquisa em aberto na literatura, não algo que se
resolve num ciclo de hackathon.

O vocabulário está desenhado em **3 camadas por confiança de generalização**
(`docs/vocabulario-mvp-proposta.md`), que é o argumento certo pra banca entender que "fechado"
não é "arbitrário":

- **Camada 1** — 20 sinais do MINDS-Libras, 8-12 pessoas por palavra: é onde dá pra afirmar
  acurácia *signer-independent* com confiança estatística real (a Seção 4 acima). Inclui
  `banheiro`, `medo`, `filho`, `vacina`, `cinco`, `esquina` — já direto ao cenário de
  atendimento.
- **Camada 2** — mais ~16 sinais via V-LIBRASIL, mas testados só com 3 intérpretes conhecidos
  (não pessoas novas) — apresentar como "vocabulário estendido", nunca misturado com a métrica
  de acurácia da Camada 1.
- **Camada 3** — ~9 termos institucionais (`marcar/agendar`, `atendimento`, `senha`,
  `protocolo`...) que **não existem em nenhuma base pública** — só coleta própria resolve. É
  literalmente o roadmap: "aqui está o que falta, e é exatamente onde entra a parceria com a
  comunidade surda."

**Revisão por consultor de Libras**: o time já está em contato com a Associação de Surdos de
Goiânia (mensagem de 2026-09-05). ⚠️ **A VERIFICAR**: confirmar se essa revisão do vocabulário
específico da demo já aconteceu antes de apresentar qualquer sinal como confirmado.

---

## 6. Arranjo legal e caminho comercial

Isto é o que separa "temos um protótipo que funciona" de "sabemos como isso vira produto" — e é
um ponto forte se apresentado com clareza, porque mostra que o time já pensou no que vem
depois do hackathon:

- **Papéis de LGPD, já desenhados**: a instituição que opera o equipamento (posto de saúde,
  órgão público) é a **controladora** dos dados durante o atendimento; o time é o **operador**.
  Frase pronta, direto de `docs/libras-livre-arquitetura.md` §7.
- **Base legal**: consentimento do titular (a pessoa surda), obtido por atendimento — não uma
  configuração feita uma vez pela instituição em nome de todos os visitantes. Recusar **não**
  bloqueia o atendimento: volta ao fluxo por bilhete/intérprete, sem prejuízo. Já implementado
  e testado (`docs/consentimento-por-atendimento-plano.md`).
- **Dados sensíveis**: landmarks de mão/pose não são anônimos por definição — se servem pra
  identificar alguém, são dado biométrico (portanto sensível). O sistema já reduz esse risco
  por desenho: não usa malha facial densa, só 7 pontos anatômicos esparsos (nariz, olhos,
  orelhas, boca) — necessários pra alguns sinais com âncora facial, não pra reconhecimento
  facial. Mesmo assim, a cautela do enquadramento como dado sensível deve continuar valendo.
- **Vídeo nunca sai do aparelho** — processado quadro a quadro e descartado, só os landmarks
  (pontos, não imagem) trafegam se algo trafegar.
- **Datasets usados hoje não autorizam uso comercial direto** — V-LIBRASIL é CC BY-NC-ND
  explicitamente; MINDS-Libras e MALTA são bases de pesquisa. O enquadramento atual é
  **pesquisa, para o hackathon**.
- **O caminho pra produto já está escrito pelo próprio time** — vale como resposta pronta se a
  banca perguntar "e depois, como isso vira negócio de verdade?": *"se o Libras Livre virar
  produto, será sob amparo institucional (Meta, CEIA-UFG), e nesse cenário obter licenças ou
  produzir base própria é tratável."* Coleta própria, com a Associação de Surdos de Goiânia,
  resolve licença, consentimento LGPD para finalidade de produto **e** ângulo de câmera dos
  óculos ao mesmo tempo — nenhuma base pública cobre os três.

---

## 7. Diferenciação e posicionamento

- **Gancho regulatório concreto**: Decreto 5.626/2005 obriga atendimento em Libras em serviços
  públicos, incluindo saúde — não é "seria bom ter", é obrigação que a maioria não cumpre por
  falta de intérprete disponível o tempo todo.
- **Bidirecional e vestível** é o que diferencia de soluções só de tradução Libras→voz ou de
  apps que exigem a pessoa surda segurar um celular. ⚠️ Se o material do pitch cita nomes de
  concorrentes específicos (ex. Lenovo, SignAll), vale conferir a frase exata usada — "nenhuma
  solução vestível e bidirecional em Libras" é mais defensável do que uma alegação de
  exclusividade total ("zero soluções mãos livres"), porque soluções mãos-livres de tradução
  unidirecional já existem e a banca pode conhecer.
- **Honestidade sobre o que ainda falta** é, paradoxalmente, um ponto de diferenciação: o
  projeto documenta as próprias limitações com disciplina incomum (variância entre execuções,
  vídeo de estúdio como teto otimista, vocabulário em camadas por confiança). Isso é
  credibilidade técnica real numa banca que já viu muito pitch inflado.

---

## 8. Riscos abertos que valem estar prontos pra falar

Não como confissão de fraqueza — como sinal de que o time sabe exatamente onde está o risco,
o que é mais forte do que fingir que não existe:

| Risco | Estado hoje |
|---|---|
| Nunca testado com pessoa surda real em condição de produto | verdadeiro — a evidência é LOSO com 8 pessoas de um dataset acadêmico público, que mede bem generalização, mas não substitui validação de produto |
| Checkpoint treinado ainda não validado com vídeo real no app | infraestrutura de carregamento pronta e testada; o vídeo real com sinais é o maior bloqueio técnico hoje |
| Licenças de dataset não autorizam produto | caminho já desenhado (§6), mas não resolvido |
| Texto de consentimento é placeholder | funcional, mas não revisado juridicamente nem pela comunidade surda |
| Ângulo de câmera (óculos na cabeça de alguém) não coberto por nenhuma base pública | risco real de produto — só coleta própria com os óculos resolve |
| Reconhecimento contínuo / vocabulário aberto | problema de pesquisa em aberto, não escondido — está no roadmap, não na entrega |

---

## 9. Perguntas prováveis da banca sobre o negócio

**"Isso substitui intérprete?"**
Não — é uma ponte para quando não há intérprete disponível, o que é a maioria do tempo na
maioria dos serviços. Intérprete continua padrão-ouro para consentimento informado, diagnóstico,
ato jurídico ou risco de vida; o sistema recusa falar reconhecimentos de baixa confiança em vez
de arriscar.

**"Quem é responsável se o sistema errar uma tradução importante?"**
A instituição que opera é a controladora dos dados; o desenho já inclui confirmação do
reconhecimento pela pessoa surda antes de falar pro atendente — reduz, não elimina, o risco de
erro silencioso.

**"Isso é viável comercialmente hoje?"**
Não com os dados atuais — os datasets públicos usados são não-comerciais. O caminho é coleta
própria sob amparo institucional, que também resolve o problema de generalização pro cenário
real (ângulo de câmera dos óculos, que nenhuma base pública cobre).

**"Por que Libras e não outro problema de acessibilidade?"**
Gancho regulatório concreto: Decreto 5.626/2005 obriga atendimento em Libras em serviços
públicos, incluindo saúde — e a maioria não tem intérprete disponível o tempo todo.

**"Vocês testaram com pessoas surdas reais?"**
Resposta honesta hoje (⚠️ confirmar antes se isso mudou): ainda não — a evidência é LOSO com 8
pessoas de um dataset acadêmico público (MINDS-Libras), que mede "funciona com quem o modelo
nunca viu", mas não substitui validação de produto com usuários reais e um consultor de Libras.
É o próximo passo declarado, não uma lacuna escondida.

---

## 10. Antes de subir ao palco: o que confirmar com o time

1. A revisão do vocabulário pela Associação de Surdos de Goiânia já aconteceu?
2. O checkpoint treinado já rodou com vídeo real de sinais no app, ou a demo vai usar o
   classificador `SIMULADO`? Isso muda o que pode ser dito sobre "reconhecimento ao vivo".
3. O texto de consentimento — mesmo placeholder — já foi ajustado pra mencionar que a fala do
   atendente também sai do aparelho e vai pro serviço do VLibras (não só os landmarks da pessoa
   surda)?
4. Se o material do pitch (vídeo/deck) cita concorrentes por nome, a frase de comparação está
   defensável (ver §7)?

---

## Anexo — resposta ponto a ponto ao feedback do CEIA/AI Glasses Brasil

Registro de referência: o CEIA mandou 10 pontos de feedback técnico-operacional. Fui conferir
cada um contra o estado real do repositório — em vários pontos o projeto já foi mais longe do
que o feedback supunha; em outros, a lacuna é real. Mantido aqui como material de apoio, não
como estrutura do pitch.

### 1. Escopo da demo: vocabulário fechado
Já coberto em detalhe na §5 acima — o número que o CEIA citou (85,7%, DTW, recorte enviesado)
está desatualizado; a evidência atual é mais forte (94,6-94,9% LOSO) e o vocabulário fechado em
3 camadas já está desenhado.

### 2. O modelo precisa chegar pronto
Infraestrutura de carregamento pronta e testada (3 modos, hash/identidade verificados,
`mobile-app-companion/README.md` §4); o checkpoint treinado ainda não rodou com vídeo real no
app — é o maior bloqueio técnico hoje. O plano B do CEIA (classificador DTW da PoC) **não está
integrado ao app** — existe só em Python; portar seria trabalho novo, não uma troca de flag, e
precisa ser decidido antes do dia 18, não durante. Licenças de dataset: caminho já respondido em
`docs/decisao-datasets-e-licencas.md` (ver §6 acima).

### 3. A câmera está na cabeça do atendente
Parcialmente endereçado por desenho: `SignBoundaryDetector` e a reamostragem temporal já são
baseados em tempo (ms), não em contagem de frames — aguenta fps variável por construção. ⚠️ Não
encontrados nos docs: simulação de atendimento real contando perdas de quadro por movimento de
cabeça, distância do balcão medida especificamente, e teste a 15fps — três testes rápidos, vale
rodar antes do dia 18.

### 4. Latência e troca de turno
Infraestrutura de medição pronta (painel + CSV + `scripts/latencia_por_etapa.py`), com metas
definidas — falta rodar e levar o número. O app já usa `PoseLandmarker`+`HandLandmarker`
separados (não `Holistic`), exatamente a alternativa que o CEIA sugere. Indicador visual de "de
quem é a vez" já implementado (faixa de estado + confirmação ②.5/③.5 pro surdo).

### 5. A pessoa surda precisa ver o que o sistema entendeu
Já implementado — estado ②.5 CONFIRMANDO_RECONHECIMENTO mostra a frase reconhecida pro surdo
(avatar + legenda) antes de falar pro atendente, com "Confirmar"/"Corrigir". É evidência de
capacidade de iterar rápido: esse exato feedback já veio de uma banca anterior (2026-09-15) e já
foi implementado.

### 6. Áudio: o que o SDK permite
No desenho atual, câmera e HFP não ficam ligados ao mesmo tempo (câmera desliga antes de abrir a
escuta) — diferente da premissa do CEIA de "HFP ligado durante todo o atendimento"; ⚠️ vale
confirmar se isso já resolve a preocupação deles. STT já é Vosk (não faster-whisper, que nem
rodaria no Android) — o risco específico do CEIA já não se aplica, mas testar Vosk com áudio a
8kHz (o que o HFP entrega) continua pendente. Wake word já é treinada em pt-BR (openWakeWord),
não Porcupine — ainda não é o motor padrão no app.

### 7. VLibras
Já implementado exatamente como sugerido: widget oficial numa WebView. O ajuste de privacidade
que o CEIA pede é real e pendente: o texto de consentimento atual não menciona que a fala do
atendente também sai do aparelho e vai pro serviço do VLibras.

### 8. LGPD e consentimento
A parte estrutural já está bem resolvida (ver §6 acima: controlador/operador, recusa sem
prejuízo, malha facial não usada). Em aberto: texto de consentimento ainda placeholder. Sobre
"ciclo de MLOps com retreino desligado por padrão": ⚠️ não encontrei nenhum ciclo de retreino a
partir de dado de campo implementado no app hoje — o risco parece não existir ainda no sistema,
o que é bom, mas vale confirmar antes de prometer qualquer coisa sobre isso na banca.

### 9. Posicionamento no pitch
Coberto na §7 acima (Decreto 5.626, "ponte não substituto", cuidado com a frase de comparação a
concorrentes). Sobre **"dados da validação com 10 pessoas surdas"**: ⚠️ **NÃO CONFIRMADO NESTE
REPOSITÓRIO.** O que encontrei é o oposto — o projeto registra repetidamente que "o sistema
ainda não foi testado com uma pessoa surda de verdade". A evidência mais próxima é o
MINDS-Libras: **8** pessoas (não 10), sinalizantes de um dataset acadêmico, avaliados por LOSO —
não uma validação de produto. Se essa validação aconteceu fora deste repositório, tragam os
dados; se for uma suposição do CEIA, não repitam pra banca sem certeza.

### 10. Bateria
Modo economia já implementado e testado, reagindo a eventos reais do SDK
(`BATTERY_CRITICAL`/`BATTERY_LOW`). Medição real de consumo (% a cada 10 min, óculos e celular)
ainda não foi feita — depende de hardware real. É o pedido mais simples de atender: medir no dia
e levar o número, mesmo que seja uma amostra só.

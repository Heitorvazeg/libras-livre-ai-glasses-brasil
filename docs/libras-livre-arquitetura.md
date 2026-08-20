# Libras Livre — Arquitetura e Decisões Técnicas

**Equipe 3G1B · Programa AI Glasses Brasil · Trilha Acessibilidade**

Este documento registra as decisões de arquitetura e as justificativas técnicas do sistema de reconhecimento e tradução de Libras do projeto Libras Livre, cobrindo tanto o recorte de MVP quanto a visão de produto de longo prazo.

**Premissa de base:** o dispositivo é usado pelo **atendente**, não pela pessoa surda, e serve múltiplos visitantes surdos diferentes ao longo do dia em um ponto de atendimento fixo. Essa premissa institucional — e não a de um dispositivo pessoal que acompanha a pessoa surda — orienta as decisões de todo o documento, com destaque para as seções 4.3 (generalização entre sinalizantes), 5 (saída para a pessoa surda) e 7 (privacidade e consentimento por atendimento).

---

## 1. Visão geral do produto

**Premissa central da arquitetura: o dispositivo é institucional, não pessoal.** Os óculos ficam com o **atendente** (recepção, agente de saúde, agente de resgate) e permanecem no ponto de atendimento — não acompanham a pessoa surda quando ela vai embora. Isso não é um detalhe de UX: é a premissa que molda praticamente todas as decisões deste documento.

Consequências diretas dessa premissa:

- **Um mesmo par de óculos atende dezenas de pessoas surdas diferentes por dia.** Não há sessão de uso prolongado nem relação contínua com um único sinalizante — cada atendimento é uma sessão nova, com uma pessoa que o sistema nunca viu antes.
- **Não há personalização nem calibração por usuário.** O modelo precisa funcionar bem desde o primeiro sinal de cada novo visitante — qualquer estratégia de adaptação incremental por pessoa (fine-tuning on-the-fly, calibração de gestos) está fora de cogitação. Isso eleva **generalização entre sinalizantes** de "boa prática de avaliação" para **requisito de produto obrigatório** (ver seções 4.3 e 9).
- **A pessoa surda não tem um dispositivo próprio pareado.** Isso obriga a repensar como o sentido ouvinte → surdo entrega a legenda (ver seção 5) — ela não pode simplesmente "aparecer no celular da pessoa surda", porque esse celular não existe no fluxo.
- **O consentimento é por atendimento, não uma configuração feita uma vez.** Cada pessoa surda que se senta no balcão é um novo titular de dados diferente, interagindo com um equipamento que não é dela (ver seção 7).

A câmera capta a pessoa surda **de frente**, corpo e rosto visíveis, no ângulo de uma conversa de balcão.

Dois sentidos de comunicação:

- **Surdo → atendente:** câmera capta os sinais → IA reconhece → alto-falante dos óculos fala em português para o atendente.
- **Atendente → surdo:** microfone capta a fala do atendente → IA transcreve → texto (ou avatar de Libras) é exibido em uma tela visível para a pessoa surda.

Este documento cobre principalmente o primeiro sentido, que é onde está a maior parte da complexidade de visão computacional.

---

## 2. Escopo e evolução

| | MVP (hackathon) | Produto |
|---|---|---|
| Vocabulário | Fechado, dezenas de enunciados de alta frequência | Aberto, expansível por domínio |
| Unidade reconhecida | Enunciado isolado (sinalizado por inteiro) | Sinalização contínua |
| Expressão facial | Fora de escopo | Incorporada à gramática |
| Segmentação | Sessão aberta/fechada manualmente pelo usuário | Detecção contínua de início/fim de sinalização |
| Modelo | DTW ou classificador leve treinado do zero | Pipeline com pré-treino multi-fonte + fine-tuning |
| Generalização entre sinalizantes | Validada informalmente com poucos testadores | Requisito obrigatório de produto, avaliado formalmente (seção 9) |
| Saída para a pessoa surda | Tela do celular do atendente, virada para o visitante | Tela dedicada no balcão, ou óculos com display |

O MVP não é uma versão "de brinquedo" da arquitetura de produto — é o mesmo pipeline com os componentes mais caros/arriscados simplificados. A migração de um para o outro deve ser incremental, não uma reescrita.

---

## 3. Arquitetura de alto nível

O sistema se divide em dois grandes blocos: **dispositivo** (óculos + celular, processamento local) e **nuvem** (processamento remoto).

```
┌─────────────────────────────┐        ┌─────────────────────────────┐
│         DISPOSITIVO          │        │            NUVEM             │
│    (óculos + celular)        │        │                               │
│                               │        │                               │
│  Captura + landmarks          │──────▶│  Reconhecimento               │
│  (MediaPipe Holistic)         │ pontos │  (modelo causal, streaming)   │
│                               │ (x,y,z)│         │                     │
│  Detector de sinalização      │        │         ▼                     │
│  (início/fim do enunciado)    │        │  Contextualização              │
│                               │        │  (sinais → frase completa)    │
│  Núcleo offline               │◀───────│         │                     │
│  (vocabulário de emergência)  │ frase  │         ▼                     │
│                               │reconhec│  Coleta e retrain              │
│  Saída (atendente)            │        │  (dados anônimos p/ retrain)  │
│  (voz sintetizada)            │        │                               │
│                               │        │                               │
│  Saída (visitante surdo)      │        │                               │
│  (tela compartilhada/legenda) │        │                               │
└─────────────────────────────┘        └─────────────────────────────┘
```

### Responsabilidade de cada bloco

**Dispositivo:** tudo que é sensível (imagem bruta) ou tem restrição de latência/bateria. O vídeo nunca deixa o aparelho — é processado quadro a quadro e descartado. Note que o dispositivo agora tem **duas saídas distintas para duas pessoas diferentes**: voz para quem está de óculos (atendente) e uma tela para quem não está (o visitante surdo) — ver seção 5.

**Nuvem:** tudo que é caro computacionalmente ou precisa ser atualizado com frequência sem depender de uma atualização de app no aparelho de cada usuário.

---

## 4. Pipeline de reconhecimento

### 4.1 Captura e extração de landmarks

- **Ferramenta:** MediaPipe Holistic, rodando no celular.
- **Componentes extraídos:** mãos (21 pontos cada) + pose superior do corpo. A malha facial (468 pontos) é capturada mas não usada pelo classificador no MVP — fica reservada para quando o vocabulário abrir e a gramática facial entrar em jogo.
- **Por que on-device:** processar localmente e enviar só os pontos (não o vídeo) resolve privacidade (nada de imagem trafega ou é retido) e reduz drasticamente o consumo de banda e bateria em relação a transmitir vídeo.
- **Contrato de dados dispositivo → nuvem:** um payload leve contendo, por frame, as coordenadas normalizadas dos pontos capturados, mais metadados de timestamp e id de sessão. Este contrato deve ser versionado desde o início (ex.: `landmark_schema_version`) para permitir evoluir o conjunto de pontos (ex.: adicionar face) sem quebrar modelos já treinados no formato antigo.

### 4.2 Detecção de segmento de sinalização

- **MVP:** heurístico simples baseado em velocidade/presença da mão no quadro, combinado com sessão aberta/fechada explicitamente pelo usuário.
- **Produto:** um classificador binário leve e contínuo ("sinalizando" / "parado"), rodando no dispositivo, substituindo a dependência de sessão manual — necessário para migrar de enunciados isolados para sinalização contínua.
- Este componente é o que decide **quando** vale a pena rodar o reconhecimento, evitando processar continuamente sem necessidade (custo de bateria e de banda).

### 4.3 Modelo de reconhecimento

**Arquitetura:** rede causal sobre a sequência de landmarks — GRU/LSTM (causais por natureza) ou uma variante de GCN temporal (ST-GCN/TGCN) com atenção, adaptada para operar em modo causal (nunca olhando frames futuros). A escolha causal é o que permite processar o sinal *enquanto* ele ainda está sendo feito, em vez de esperar o clipe inteiro terminar.

**Restrição de produto que muda a prioridade de engenharia:** como o dispositivo é institucional e atende um sinalizante diferente a cada sessão, **não existe a opção de personalizar o modelo por usuário**. Isso significa que a divisão de treino/validação deve ser **signer-independent desde a primeira versão do modelo** — nunca validar com o mesmo conjunto de pessoas usado no treino, mesmo informalmente durante o desenvolvimento — porque é exatamente esse cenário (modelo nunca viu a pessoa à frente da câmera) que o produto vai enfrentar em toda sessão real, não uma condição de borda.

**Estratégia de treino — pré-treino multi-fonte seguido de fine-tuning:**

1. **Pré-treino em corpora de língua de sinais de alto recurso** (ex.: WLASL/ASL). A literatura mostra evidência empírica de que transfer learning cross-lingual entre línguas de sinais diferentes traz ganhos reais de acurácia quando seguido de fine-tuning no idioma alvo — mesmo sem relação histórica entre as línguas — desde que a fonte de pré-treino também seja dado de língua de sinais (e não vídeo genérico). O ganho é maior quanto menor o dataset alvo disponível, que é exatamente o cenário inicial deste projeto.
   - **Ressalva importante:** transfer learning **zero-shot** (sem fine-tuning) entre línguas de sinais diferentes tende a falhar, por diferenças linguísticas e cinemáticas reais entre os idiomas. O valor do pré-treino cross-lingual só aparece quando combinado com fine-tuning no idioma alvo.
   - **Risco técnico concreto identificado:** o checkpoint público `sharonn18/tgcn-wlasl` (Hugging Face) documenta seu formato de entrada como "MediaPipe pose keypoints", mas o dataset WLASL original foi processado com **OpenPose**, não MediaPipe — são sistemas com convenções de indexação diferentes. Usar esse checkpoint específico exige descobrir e validar manualmente o mapeamento entre os 33 pontos de pose do MediaPipe e os 13 pontos de tronco/cabeça usados no treino original (os 21+21 pontos de mão têm convenção compatível entre os dois sistemas). Esse mapeamento não está claramente documentado nem no repositório do modelo, nem no repositório original do WLASL. **Alternativa mais segura:** reaproveitar apenas a arquitetura (não os pesos) e reprocessar os vídeos brutos do WLASL com MediaPipe Holistic, para gerar um pré-treino no formato de keypoints que o produto vai usar de fato.
2. **Pré-treino/fine-tuning intermediário em V-LIBRASIL** (UFPE — ~1.360 sinais, gravados por intérpretes, ângulo frontal compatível com a configuração do produto). Por já estar no idioma e no ângulo de câmera alvo, esta é provavelmente a fonte de pré-treino de maior valor por amostra.
3. **Fine-tuning final nas gravações próprias da equipe**, feitas exatamente na distância, ângulo e iluminação de uso real (balcão de atendimento). Nenhuma base pública cobre esse ponto de vista específico — essa etapa fecha a lacuna que nenhum dataset acadêmico resolve sozinho.
4. **MINDS-Libras** (UFMG — 20 sinais, 12 sinalizantes) é usado não como fonte de treino, mas como conjunto de validação de generalização entre pessoas diferentes (signer-independent), antes de considerar um modelo pronto para produção.

### 4.4 Contextualização (sinais → linguagem natural)

Estágio separado do reconhecimento de sinal individual, responsável por transformar a sequência de sinais reconhecidos em uma frase gramatical em português. No MVP de vocabulário fechado, isso pode ser tão simples quanto um mapeamento direto sinal→frase; a arquitetura deve prever, desde já, a evolução para um modelo sequência-a-sequência (ex.: encoder-decoder) capaz de lidar com vocabulário aberto e gramática espacial mais adiante — sem exigir reescrever a etapa de reconhecimento de sinais quando isso acontecer.

### 4.5 Núcleo offline (emergência)

- Versão reduzida do modelo de reconhecimento, com vocabulário restrito a enunciados de emergência, compilada para rodar localmente (ex.: TFLite/LiteRT) no celular.
- **Não recebe o retrain contínuo do modelo de produção** — é uma versão congelada, versionada e atualizada apenas por push explícito de uma nova build, já que precisa funcionar de forma previsível mesmo sem conectividade.

---

## 5. Sentido inverso: voz → texto/avatar

- Reconhecimento de fala em português, sem restrição de vocabulário, rodando no celular do atendente ou na nuvem conforme a conectividade disponível no momento.
- **Onde a legenda aparece é uma decisão de produto em aberto, não resolvida por padrão**, porque a pessoa surda não carrega nenhum dispositivo pareado com o sistema. Três caminhos possíveis, em ordem de menor para maior custo de implementação:
  1. **Tela do celular do atendente, virada para o visitante** nos momentos de resposta — sem hardware adicional, mas depende do atendente lembrar de virar a tela, o que é um ponto de falha de UX real e deve ser testado com usuários antes de assumir que funciona bem no fluxo.
  2. **Uma tela dedicada no balcão** (tablet ou monitor pequeno), conectada ao mesmo sistema — resolve o problema de UX acima, mas exige hardware extra por ponto de atendimento, o que pesa no custo de implantação institucional.
  3. **Óculos com display**, quando essa geração de hardware estiver disponível — a legenda apareceria no campo de visão do próprio atendente, e ele a repassaria oralmente ou apontando a tela; ainda não resolve a visibilidade direta para a pessoa surda a menos que o hardware de display também seja usado por ela, o que reabriria a pergunta de quem usa os óculos.
- Evolução planejada, independente de qual solução de tela for escolhida: avatar de Libras no lugar do texto puro, e futuramente identificação de quem está falando em ambientes com múltiplos interlocutores (ex.: mais de um atendente na mesa).

---

## 6. Dados

**Fontes de referência:**
- **V-LIBRASIL** (UFPE) — pré-treino principal, uso acadêmico, sujeito a autorização dos responsáveis.
- **MINDS-Libras** (UFMG) — validação de generalização entre sinalizantes.
- **Gravações próprias da equipe** — no ângulo e condições reais de uso; tornadas públicas para ajudar a mitigar a escassez geral de dados de Libras.

**Coleta contínua pós-lançamento:** toda sessão em que o reconhecimento teve confiança baixa e passou pela etapa de confirmação (ver seção 9) é um candidato natural a virar dado de treino rotulado, desde que o usuário consinta.

**Validação com a comunidade surda:** nenhum novo lote de vocabulário deve ser fechado sem envolvimento de pessoas surdas e profissionais de Libras na definição e revisão dos sinais — isso é tratado como etapa obrigatória do pipeline de expansão de vocabulário, não como validação opcional posterior.

---

## 7. Privacidade e conformidade (LGPD)

**O dispositivo é institucional, o titular dos dados muda a cada sessão.** Isso é diferente do caso de um dispositivo pessoal com consentimento configurado uma vez: aqui, a pessoa surda é uma visitante ocasional de um equipamento que pertence à instituição de atendimento, e cada uma delas é um titular de dados distinto exercendo esse papel só durante os minutos do atendimento.

- Vídeo processado quadro a quadro e descartado — sem gravação, sem banco de imagens, sem histórico.
- Apenas os pontos de landmarks necessários ao reconhecimento trafegam até a nuvem; a imagem nunca sai do celular.
- **Consentimento obtido no início de cada atendimento**, junto da própria pessoa surda — não uma configuração feita uma vez pelo atendente ou pela instituição em nome de todos os visitantes. O fluxo de abertura de sessão deve comunicar de forma clara e acessível (em Libras, não só em português) o que o sistema faz antes de captar qualquer sinal.
- **Responsabilidade institucional:** a instituição que opera o equipamento (posto de saúde, órgão público) é a controladora dos dados durante o atendimento, o que implica obrigações de transparência e de resposta a solicitações do titular que vão além do que um app pessoal precisaria prover.
- Base legal: consentimento do titular (a pessoa surda), sem retenção dos dados da conversa em qualquer configuração.
- A sessão é sempre aberta e encerrada explicitamente pelo atendente; o LED de captura do dispositivo sinaliza a terceiros — incluindo a pessoa surda atendida — que a câmera está ativa.
- **Reset de sessão obrigatório entre atendimentos:** nenhum dado (landmark, transcrição, estado de reconhecimento) de um atendimento pode vazar ou influenciar o atendimento seguinte com outra pessoa — isso deve ser garantido por arquitetura (estado limpo a cada nova sessão), não por confiança no atendente lembrar de reiniciar algo manualmente.
- O sistema não substitui intérprete humano em consentimento informado, diagnóstico, ato jurídico ou situação de risco de vida — reconhecimentos de baixa confiança são exibidos como incertos e não são falados automaticamente.

---

## 8. Eficiência energética

- Câmera ligada apenas durante uma sessão ativa, nunca em captura contínua de fundo.
- Tráfego de rede reduzido a pontos de landmark, não vídeo — menos rádio ligado tanto nos óculos quanto no celular.
- Modo de economia: com bateria baixa, a câmera é desligada e o sistema mantém apenas a legenda da fala (sentido ouvinte → surdo), que consome bem menos energia; o usuário é avisado da mudança de modo.

---

## 9. MLOps e ciclo de vida do modelo

- **Versionamento separado** para o modelo de nuvem (atualização contínua) e o núcleo offline (atualização por build).
- **Threshold de confiança + confirmação:** reconhecimentos abaixo de um limiar de confiança são exibidos para confirmação antes de virar fala, em vez de serem falados diretamente — isso já está no desenho de produto e também serve como filtro de qualidade para o dado que retroalimenta o retrain.
- **Avaliação signer-independent** obrigatória antes de promover uma nova versão de modelo — usando MINDS-Libras como benchmark de generalização entre pessoas, já que treinar e validar com o mesmo pequeno grupo de sinalizantes tende a superestimar a qualidade real do modelo.
- **Monitoramento por matriz de confusão** para identificar pares de sinais frequentemente confundidos, o que ajuda a priorizar onde coletar mais dados ou revisar o vocabulário.
- **Processo de expansão de vocabulário:** novo domínio (ex.: bancário, depois de saúde) → coleta e validação com a comunidade surda → fine-tuning incremental → avaliação signer-independent → promoção a produção.

---

## 10. Limitações conhecidas e riscos técnicos em aberto

- **Mismatch de formato de keypoints** entre pipelines pré-treinados publicamente em OpenPose (ex.: WLASL original) e a extração via MediaPipe usada em produção — precisa de validação cuidadosa ou re-treino do zero no formato correto antes de qualquer reaproveitamento de pesos.
- **Reconhecimento contínuo de Libras** (frases inteiras com gramática espacial, não apenas sinais isolados) segue sendo um problema de pesquisa em aberto na literatura — o recorte de vocabulário fechado e enunciados isolados do MVP é uma escolha deliberada para tornar o problema tratável no prazo do programa, não uma limitação técnica a ser escondida.
- **Expressão facial** fica fora do reconhecimento até o MVP fechado, mesmo com o rosto agora visível pela câmera frontal — decisão de produto que precisa ser revisitada quando o vocabulário abrir.
- **Escassez geral de dados de Libras** publicamente disponíveis é um limitador estrutural da área, não só deste projeto — parte da motivação para publicar as gravações próprias da equipe.
- **Saída para a pessoa surda ainda não tem solução definitiva** (seção 5) — a opção mais barata (celular do atendente virado para o visitante) depende de um comportamento humano consistente que não foi validado com usuários reais; isso é um risco de produto tão real quanto os riscos de modelo listados acima, e não deve ser tratado como detalhe de implementação trivial.
- **Nenhuma personalização por sinalizante é possível por desenho**, o que eleva a régua de qualidade exigida do modelo base — ele precisa acertar razoavelmente bem já no primeiro sinal de cada pessoa nova, sem janela de adaptação.

---

## 11. Referências

**Datasets:**
- V-LIBRASIL (UFPE)
- MINDS-Libras (UFMG)
- WLASL — Li, D., Rodriguez, C., Yu, X., Li, H. (2020). *Word-level Deep Sign Language Recognition from Video: A New Large-scale Dataset and Methods Comparison.* WACV 2020.

**Transfer learning e reconhecimento de língua de sinais:**
- Toengi, R. (2021). *Application of transfer learning to sign language recognition using an inflated 3D deep convolutional neural network.* (ASL → Língua de Sinais Alemã, ganhos de 8–21% de acurácia com fine-tuning).
- TransSLR (2026) — transferência zero-shot entre línguas de sinais de alto e baixo recurso.
- SIGNET (2026) — pré-treino multi-lingual (CSL, ASL, BSL) com backbones ST-GCN antes de fine-tuning cross-lingual.
- SignCLIP (2024) — transferência cross-lingual via aprendizado contrastivo texto-sinal.
- Transfer Learning for Cross-dataset Isolated Sign Language Recognition in Under-Resourced Datasets (2024) — comparação entre fine-tuning simples e técnicas especializadas de domain adaptation.

**Modelo consultado (com ressalvas documentadas na seção 4.3):**
- `sharonn18/tgcn-wlasl` — Hugging Face.

**Site do projeto:**
- https://aiglasses3g1b.netlify.app/

---

*Documento vivo — deve ser atualizado conforme decisões de arquitetura evoluem ao longo do programa.*

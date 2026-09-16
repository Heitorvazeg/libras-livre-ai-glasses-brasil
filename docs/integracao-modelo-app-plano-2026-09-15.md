# Integração privada do modelo de visão no app

**15/09/2026 — pacote, build, carregamento e diagnóstico implementados;
vídeo validado por componentes reais no emulador. Tela/ciclo completos e hardware pendentes.**

Resultados, identidade do APK e protocolo restante:
[relatório de validação](integracao-modelo-app-validacao-2026-09-15.md).

Avanço posterior do item 1: [vídeo e infraestrutura visual](integracao-video-infraestrutura-2026-09-15.md).

- Branch: `feat/integracao-modelo-app`.
- Base: `dev`, commit `d65a05d5aad3df6b5b2661816a7fc6ace2bc1d31`.
- Worktree separado: `/home/walisson/libras-livre-integracao-modelo-app`.
- Não alterar dados, notebooks, receita ou resultados dos testes de treino em andamento.

## Objetivo e limites

Produzir um APK **debug privado e experimental** que use o TFLite real pelo
caminho normal do aplicativo, com identidade do artefato visível e falhas
explícitas. Não aprovar o modelo para entrega nem prometer acurácia nos óculos.

Começar pelo baseline já exportado, preservado no worktree original. A candidata
da etapa 3 só poderá substituí-lo mediante decisão explícita, exportação própria
e paridade própria. O mecanismo de integração não depende de ela passar ou falhar.

Fora do escopo: novo treino, ajuste de limiar, mudanças no contextualizador ou
wake word, expansão do vocabulário, publicação de pesos/APKs, merge automático
e reabertura de FILHO.

## Estado verificado na base

- `CameraViewModel.criarClassificador()` carrega modelo/sidecar dos assets;
  ausência de modelo seleciona placeholder; modelo recusado gera erro.
- `TfliteSignClassifier` e `ValidacaoClassificador` já conferem hash e contrato.
- `librasLivre.classificadorFixtures` modifica apenas os assets de `androidTest`.
  Não configura o modelo no APK principal.
- Há evidência anterior de paridade com o baseline real em emulador usando
  entradas sintéticas. Não confundir com reconhecimento de vídeo real.

## 1. Contrato do pacote privado

Definir um diretório de entrada com apenas modelo TFLite, sidecar e registro
compacto de identidade: SHA-256 do modelo, do sidecar e do checkpoint de origem,
identificador do experimento e estado de calibração declarado.

- Conferir o par modelo/sidecar e sua correspondência com o artefato selecionado.
- Preservar bytes do export; adaptar o nome de destino se necessário, não os pesos.
- Não incluir checkpoint PyTorch, vídeos, landmarks ou fixtures no APK.
- Não transformar hash em certificado de autoria, licença ou qualidade linguística.
- O baseline começa sem calibração de entrega; temperatura 1 e limiar manual
  devem aparecer como configuração não calibrada.

**Aceite:** seleção inequívoca e rastreável do artefato, com recusa de pacote
incompleto ou adulterado, sem alteração dos originais.

## 2. Empacotamento debug por opção explícita

Adicionar opção de build distinta das fixtures, por exemplo
`librasLivre.classificadorPrivado`, apontando para diretório privado absoluto.

- Gerar assets exclusivamente para a variante debug; não copiar em fontes rastreadas.
- Validar caminhos canônicos e colisões; aceitar origem privada externa ao worktree.
- Copiar somente a lista permitida de arquivos.
- Declarar inputs/outputs da tarefa para evitar modelo antigo em builds incrementais.
- Remover a opção deve remover o modelo injetado no build seguinte.
- Recusar uso dessa opção para release e impedir publicação automática do APK.
- Preservar o build padrão e a opção existente de fixtures.

**Aceite:** inspecionar o APK e confirmar hashes dos arquivos empacotados;
nenhum peso/artefato privado passa a ser rastreado pelo Git.

## 3. Carregamento com modo explícito

Separar a política de seleção da construção do classificador para permitir testes:

- Simulado: placeholder identificado como tal.
- Real experimental solicitado: modelo obrigatório; ausência, contrato incompatível
  ou hash divergente bloqueiam reconhecimento, sem fallback silencioso para roteiro.
- Recusado: erro persistente e motivo legível; nunca continuar com outro modelo.

Manter as validações existentes e testar contratos malformados, rótulos vazios
ou repetidos, temperatura não finita e saídas não finitas. Fechar recursos do
Interpreter quando a inicialização falhar.

**Aceite:** o modo real não produz glosas de placeholder em nenhum caminho de falha.

## 4. Identificação e diagnóstico

Exibir na tela/painel o modo, estado de carregamento, experimento e hash curto.
Disponibilizar hashes completos nos registros de diagnóstico, junto com versão
do app, temperatura, limiar manual e estado de calibração.

Medir aquecimento e inferência usando a instrumentação existente; registrar
rejeições e falhas sem acrescentar captura de mídia ou dados pessoais por padrão.

**Aceite:** quem opera consegue distinguir simulação, modelo real experimental
e erro, inclusive depois de reiniciar o aplicativo.

## 5. Validação por camadas

1. **Build/contrato:** pacote ausente, hash errado, colisão de assets, release
   proibido, build sem opção após build com opção e preservação dos originais.
2. **JVM:** política de carregamento, estado visual e validação do contrato.
3. **Android:** carregar pelos assets do aplicativo alvo, não pelos assets do
   APK de testes; conferir hash, aquecer e comparar logits com fixtures conhecidas.
4. **Pipeline:** vídeo controlado passando por extração, normalização, segmentação,
   classificador, avaliação de frase e contextualização. Não exigir frases do
   roteiro do placeholder como se fossem previsões do modelo real.
5. **Celular/óculos:** sessões curtas com métricas de captura, mãos presentes,
   segmentação, latência, erros e rejeições. Explicitar diferenças frente ao emulador.

Os testes de integração comprovam transporte e execução corretos, não substituem
a avaliação de generalização e de rejeição de desconhecidos. Testes pesados e
uso de dispositivos serão coordenados para não disputar recursos com a frente de treino.

## Ordem de entrega

1. Pacote/identidade + build privado debug, com testes de empacotamento.
2. Modo real obrigatório + diagnóstico visual, com testes JVM.
3. APK e teste instrumentado pelo caminho normal do app com baseline real.
4. Execução controlada em vídeo e protocolo de validação no hardware.

**Pronto nesta frente** significa APK privado rastreável e integração validada,
com relatório que distingue testes executados dos pendentes. Não significa
modelo aprovado para entrega. Sem commit, push ou distribuição automática.
# Revisão do commit de calibração 3e81c3f

**14/09/2026 — revisão solicitada antes de commitar a investigação FILHO.**
Base examinada: `3e81c3fc68852e64c2a9a6649fd6f7f5fc3c8db5`.
Não houve correção do código de calibração nesta revisão, treino real, nova
calibração com dados reais, conversão TFLite real, Android ou push.

## Parecer

**Não aprovar a calibração para entrega no estado revisado.** É possível salvar
e compartilhar o trabalho como experimental, com estes achados explícitos.
A investigação FILHO é independente e pode ser commitada separadamente.
Os testes existentes passam, mas não cobrem as falhas reproduzidas abaixo.

## Achados confirmados, por prioridade

### Alta — export aceita temperatura inválida e JSON não finito

[exportar.py](../computer-vision-model/treino/exportar.py#L623-L628) confere apenas
schema e presença de temperatura; [escrever_sidecar](../computer-vision-model/treino/exportar.py#L527-L539)
confere rótulos, não os números, e serializa sem `allow_nan=False`.

**Reprodução executada:** usando o checkpoint sintético de
[test_export_contrato.py](../computer-vision-model/treino/test_export_contrato.py)
e seu conversor simulado, exportar com rótulos iguais e temperatura −1, NaN ou
infinito terminou com sucesso e gravou o valor inválido no sidecar.
NaN/Infinity também não são números válidos em JSON estrito.

O [consumidor Android](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/SidecarClassificador.kt)
verifica `temperatura <= 0`, insuficiente para não finitos. Não foi executado
Android; não se afirma aqui como seu parser tratará cada JSON inválido.

**Correção necessária:** validar tipo, finitude e faixa positiva compatível com
float32 antes de converter/publicar, serializar JSON estrito e adicionar
regressões. Não confiar apenas na recusa posterior do consumidor.

### Alta — “rejeita tudo” ainda aceita confiança igual a 1

[escolher_limiar](../computer-vision-model/treino/calibracao.py#L115-L140)
inicializa acima de 1, mas limita o retorno a 1. A regra de aceite usa `>=`.

**Reprodução executada:** logits `[[1000, -1000]]`, alvo `[1]` (predição errada),
meta 0,90. Softmax retorna `[[1, 0]]`. A função informa:

- `meta_nao_atingida=true`;
- `cobertura_esperada=0`;
- `limiar_sugerido=1`.

Aplicando `confianca >= limiar_sugerido`, **1/1 é aceito**, contradizendo cobertura
zero e a mensagem do CLI. Não é uma hipótese sobre refatoração futura: ocorre
no código atual, com saturação numérica legítima do softmax.

**Correção necessária:** representar explicitamente que não há política de
aceitação aprovada e impedir uso/export automático desse resultado. Apenas
manter `1+1e-9` não é solução para o app: float32 pode arredondar a 1 e a
configuração do limiar é limitada a 1. É preciso definir contrato com consumidor.

### Alta — calibração não vinculada ao modelo nem à representação

[carregar_pool/calibrar](../computer-vision-model/treino/calibracao.py) comparam
rótulos e registram caminhos, mas não preservam hashes de evidências/checkpoints
nem conferem representação/receita. O export também só compara os rótulos.

**Reprodução executada:** um JSON mínimo com T=2, mesmos rótulos e campo
`checkpoint_sha256="outro-modelo"` foi embutido sem recusa. O campo adicional é
ignorado; o próprio produtor atual nem exige identidade de modelo. O hash do
TFLite no sidecar protege os bytes desse TFLite, **não prova que T foi ajustado
para esse checkpoint**.

Pooling de modelos de folds diferentes não é automaticamente calibração do
checkpoint final. Quando se treina com todas as pessoas MINDS, as pessoas de
validação LOSO também fazem parte do ajuste desse novo checkpoint.

**Correção necessária:** decidir e registrar se o artefato é análise experimental
do procedimento LOSO ou calibração de um checkpoint específico. Para a segunda,
vincular identidade do modelo, representação, dados/partições e resultados
independentes. Não transferir automaticamente o pool ao modelo final só porque
o vocabulário coincide. A [política final](politica-modelo-final-2026-09-14.md)
continua exigindo dados novos para calibração/teste.

### Média — pool duplica evidências e trunca alvos inválidos

[carregar_pool](../computer-vision-model/treino/calibracao.py#L36-L60) aceita o
mesmo caminho repetido e converte alvos para inteiro antes de validar seu tipo.

**Reproduções executadas:**

- Arquivo com duas amostras passado duas vezes gera quatro amostras. Em
  `calibrar`, a contagem de folds usa o número de caminhos, portanto seria dois
  folds apesar de haver um único arquivo. Isso pode reponderar um fold e inflar
  o número de evidências reportado.
- Alvo `0.9` vira `0` silenciosamente, alterando a referência.

**Correção necessária:** rejeitar duplicatas por identidade e validar alvos
inteiros/faixa, arrays não vazios e IDs/partições compatíveis antes de concatenar.
Os testes atuais de pool usam evidências sintéticas sem identidade completa;
será preciso ampliá-los para o contrato realmente exigido.

## Limites e alegações que não procedem

- **Não foi encontrado uso automático do split teste:** o código lê
  explicitamente `d["validacao"]`. Uma reprodução com logits sentinela em `teste`
  confirmou que eles não entram no pool. A docstring dizendo que o módulo “não
  sabe qual arquivo é validação ou teste” é imprecisa para esse schema combinado.
- **O limiar sugerido não é aplicado automaticamente pelo app.** O sidecar lê T;
  o [avaliador](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/dialogo/AvaliadorDeFrase.kt#L66)
  usa a configuração do usuário, cujo padrão é 0,60. Isso é uma lacuna de
  integração, não prova de bug por ignorar uma recomendação explicitamente
  chamada “sugerida”. Não afirmar que exportar o JSON calibra a rejeição da demo.
- ECE, cobertura e acurácia calculadas nos mesmos dados usados para ajustar T e
  limiar são **medidas de ajuste**, não estimativas independentes garantidas.
  A validação LOSO já participa da escolha de época. Não declarar aprovação do
  futuro final nem de frases com base nessas medidas.
- A descrição “maior limiar” no cabeçalho não corresponde ao algoritmo de maior
  cobertura (menor limiar elegível entre os observados). E NLL é convexa na
  temperatura inversa, não em geral em `log(T)` como sugere a justificativa do
  LBFGS. São correções de documentação, não demonstrações de falha do ajuste
  nas amostras testadas.

## Validação realizada

**78 testes passaram em 36,884 s** no ambiente existente: Python 3.12.3,
PyTorch CPU, NumPy 1.26.4. Suites:

- calibração (13), contrato de exportação (10), investigação FILHO (7);
- auditoria M9 (4), política final (4), evidências LOSO (12);
- paridade TFLite piloto (4), avaliação pareada (8), extração (8), inventário (3),
  fixtures (5).

Os testes de exportação e as reproduções acima usam conversão **simulada**:
não constituem nova paridade TFLite real. Não foi repetido o selftest completo
nesta revisão. Nenhum resultado histórico foi reescrito.

As reproduções dos achados foram executadas separadamente, com dados sintéticos
e diretórios temporários, sem adicionar testes que legitimem o comportamento
incorreto. **Passagem dos 78 testes não significa ausência dos bugs relatados.**

## Encaminhamento

Registrar a investigação FILHO em commit próprio e este parecer em outro.
Manter a calibração como experimental, com correções pendentes antes de aprovar
seu uso no artefato de entrega. Não ampliar esta tarefa para mudar contratos
Android, recalibrar ou treinar novamente; nenhum push/merge foi solicitado aqui.
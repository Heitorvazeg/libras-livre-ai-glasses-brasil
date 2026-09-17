# Publicação pontual do modelo e das evidências — 17/09/2026

Após o checkpoint `17b67ae`, o responsável solicitou um commit separado na mesma
branch `feat/integracao-modelo-app` com o modelo e as evidências, **sem APKs**.
A visibilidade pública do repositório foi informada antes dessa autorização.

## Conteúdo publicado

122 arquivos originais, total de 7.098.991 bytes (aproximadamente 6,8 MiB), além
desta documentação e das regras Git:

- `experimentos-privados/app-baseline-v1`: modelo TFLite experimental, sidecar e
  identidade; os três arquivos do pacote, sem alterações de conteúdo.
- `experimentos-privados/etapa4`: logs e JSON das execuções REAL/RECUSADO, inclusive
  a primeira execução falha, e o pacote sintético inválido de recusa.
- `experimentos-privados/etapa5/evidencia-01`: relatório das oito fases, logs Gradle,
  inventários de fontes, fontes BuildConfig/init scripts e snapshots dos pacotes A/B.

**Nenhum APK**, nem principal nem de teste, é incluído. Também não são incluídos
vídeos, fotos, áudio, arquivos de landmarks ou checkpoints de treino. Os arquivos
com nomes terminados em `.apk-badging.log` são relatórios textuais, não APKs.
Os JSON preservam nomes, caminhos e hashes dos APKs ausentes; esses campos não
significam que os binários foram publicados. Assim, esta publicação permite
inspecionar os relatórios, mas não conferir os bytes dos APKs originais sem obtê-los
separadamente. Não são publicados novos resultados de testes neste commit.

O nome histórico `experimentos-privados` **não torna privados os arquivos rastreados**.
Esta é uma exceção pontual à política anterior, não uma liberação automática dos
demais experimentos. O diretório permanece ignorado para novos arquivos; a seleção
foi adicionada explicitamente. APKs continuam ignorados inclusive fora desse diretório.

## Identidade do baseline

Experimento `final-s20260917-v1`; o nome histórico não significa modelo final aprovado.
Contrato float32 `[1,96,57,3]`, 20 rótulos, sem calibração.

| Arquivo | SHA-256 |
|---|---|
| Modelo TFLite | `616d1e1c91d4d081f39e25143c9781c18ee19f460ef7023a6b03e3eb0393da4c` |
| Sidecar | `392228a9302712992f8d6b970305e7f3701b0c739924d9f6db1c2b107454b4ed` |
| Identidade | `8f6ce7ae2539bc1e123f85ba197e4f45b2b0721cae7964e32e997a4da4897b63` |

Os bytes originais e os UUIDs de execução são preservados para manter a
rastreabilidade. Atributos Git desativam conversão de quebras de linha nesses
snapshots. A cópia de A na etapa 5 é intencional; Git deduplica blobs idênticos.
Os pacotes negativos são fixtures inválidas, **não modelos utilizáveis**.

## Conteúdo dos metadados e limites da revisão

O sidecar contém código-fonte de proveniência, configurações, caminhos locais/Kaggle,
identificadores pseudônimos como `M01` e um inventário de 800 amostras com nomes,
rótulos e hashes. Não contém os arquivos dessas amostras nem seus arrays de landmarks.
Os logs também conservam caminhos locais, serial do emulador, PID e UUID de execução.
Não se declara anonimização desses metadados.

Foi feita triagem de tipos, links simbólicos, validade dos 22 JSON, hashes do pacote
e padrões comuns de credenciais, além de revisão independente somente leitura.
Não foram identificados segredos na triagem. Isso não é garantia de ausência de
todo dado sensível, auditoria jurídica ou análise de memorização dos pesos.

Publicar não altera `experimental=true`, `aprovado_entrega=false` ou o bloqueio de
release do carregamento experimental. Não constitui licença para redistribuir os
datasets de origem, não resolve licenças para produto nem valida qualidade linguística.
As questões de origem continuam descritas na
[decisão de datasets e licenças](decisao-datasets-e-licencas.md).

## Como retomar

O pacote baseline pode ser usado pelo mecanismo existente de seleção privada/debug,
apontando `librasLivre.classificadorPrivado` para o diretório absoluto do pacote.
O nome técnico dessa opção foi mantido; agora não implica confidencialidade do pacote
publicado. Assets adicionais, SDK e configurações locais continuam necessários.
Não executar builds com os init scripts arquivados como se fossem configuração
portátil: eles registram os caminhos da execução original.

As pendências de implementação permanecem no
[checkpoint de retomada](integracao-checkpoint-retomada-2026-09-17.md).
As evidências são da rodada descrita no
[fechamento da infraestrutura](integracao-infraestrutura-fechamento-2026-09-17.md),
não uma regressão completa do checkpoint posterior.
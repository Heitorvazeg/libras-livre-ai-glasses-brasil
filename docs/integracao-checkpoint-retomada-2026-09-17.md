# Checkpoint para retomada — 17/09/2026

> **Atualização posterior:** o responsável autorizou publicar modelo e evidências,
> sem APKs, em commit separado. Escopo e limites na
> [publicação pontual](integracao-publicacao-artefatos-2026-09-17.md).
> As referências abaixo a artefatos não enviados descrevem o checkpoint `17b67ae`.

Checkpoint solicitado pelo usuário para salvar e enviar o estado em andamento na
branch `feat/integracao-modelo-app`. Não representa conclusão das novas pendências
nem aprovação de modelo final. Nenhum artefato privado deve acompanhar o push.

## Evidência anterior

A rodada anterior está descrita no
[fechamento da infraestrutura](integracao-infraestrutura-fechamento-2026-09-17.md):
235 testes JVM aprovados/1 ignorado, 40 Python aprovados, 2 testes de atendimento
Android aprovados, persistência entre processos, recusa por segmento e oito fases
de empacotamento. Esses resultados não validam automaticamente alterações
posteriores, incluindo o trabalho parcial de interrupção/recuperação Android.
Não foi executada nova regressão para este checkpoint; foi verificado whitespace
e o isolamento dos artefatos privados no Git.

## Retomar — cobertura no emulador

- Revisar e completar o trabalho parcial de interrupção/recuperação das preferências,
  incluindo `InterrupcaoEtapa4`, os testes Android e a orquestração no host.
- Cobrir Confirmar, Corrigir e timeout com as novas decisões abaixo, distinguindo
  chamadas de áudio de comprovação acústica.
- Testar ciclos repetidos de abertura e encerramento.
- Executar a suíte instrumentada completa no estado resultante e investigar falhas.
- Solicitar revisão independente por etapa e registrar evidências novas.

## Decisões de produto aprovadas, conclusão ainda pendente

- Timeout não deve falar automaticamente: cancelar ou pedir nova confirmação.
- Falha ao Corrigir não deve falar a frase anterior: informar a falha e oferecer
  nova tentativa ou cancelamento.
- Revisar texto de consentimento e alternativa quando o avatar estiver indisponível.
  Validação de acessibilidade com pessoas usuárias de Libras depende de participação
  humana e não pode ser declarada concluída apenas por testes automatizados.

## Limites

Trabalhar exclusivamente no worktree de integração; preservar o worktree de treino.
Não retreinar, calibrar ou escolher o modelo final. Modelos, APKs e evidências
privadas continuam locais/ignorados; precisam ser preparados separadamente em outra
máquina. Testes físicos e áudio acústico permanecem fora da validação do emulador.
Este checkpoint não autoriza merge em `dev` nem novos envios após a retomada.
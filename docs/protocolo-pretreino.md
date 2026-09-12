# Pré-treino: isolamento e proveniência (Frente A)

## Contrato

- MINDS (`M*`) não pode entrar no pré-treino. Seu LOSO fica no fine-tuning.
- V-LIBRASIL (`V*`) é o corpus habilitado. WLASL tem ingestão com metadados, mas
  o carregador atual aceita apenas M/V: o pré-treino rejeita W explicitamente,
  em vez de contar um corpus que seria silenciosamente descartado.
- Os 30 clipes reservados da V-LIBRASIL são excluídos **por fonte + origem no ZIP**.
  Após pré-treino no restante da base, não são pessoas nem domínio inéditos.
- Compartilhar classes com o MINDS é permitido em transferência supervisionada;
  compartilhar clipes reservados não é. Isto não é avaliação zero-shot.
- Diretórios repetidos (inclusive symlinks), origens repetidas, hashes iguais de
  vídeos/landmarks, identidade incoerente e sidecars ausentes abortam o processo.
- O CSV de avaliação deve existir, ser válido e conter reservas V-LIBRASIL.
  Ingestões filtradas preservam reservas anteriores. Alterar/remover reservas é
  uma decisão explícita de protocolo, não uma limpeza automática.

## Manifestos por amostra

Ao lado de cada vídeo fica `<nome>.mp4.proveniencia.json`; ao lado do array,
`<nome>.npy.proveniencia.json`. Os JSON acompanham os dados nos pacotes privados.
O CSV existente continua sendo a lista de reservas/seleção, não o manifesto
completo do corpus de pré-treino.

O esquema 1 contém:

| Campo | Conteúdo |
|---|---|
| `fonte`, `pessoa`, `sinal`, `rep` | identidade, conferida contra o nome local |
| `bundle.id`, `bundle.indice_sha256` | dataset Kaggle e impressão do índice ordenado (origem, tamanho, CRC) |
| `origem` | caminho original do membro dentro do ZIP, sem normalizar/renomear |
| `video` | nome, bytes, SHA-256 e CRC32 |
| `landmarks` | nome, SHA-256, shape e dtype do array cru |
| `extracao` | número de pontos, dimensões efetivas e configuração de extração |

As três ingestões conferem tamanho/CRC e calculam SHA-256 também para vídeos
reaproveitados. Novas extrações herdam os metadados do vídeo. Os arrays não mudam.
Na auditoria, hashes de landmarks existentes no diretório de avaliação e hashes
de vídeo disponíveis em seus sidecars também são comparados.

**Limites:** hashes exatos não detectam recodificação/cortes e metadados não são
assinaturas de autenticidade. A origem deve vir de ingestão confiável. Um cache
identifica a versão observada do índice, não prova que ela é a versão mais recente.

## Dados antigos, sem reextração

**Estado em 10/09:** os 4.053 pares históricos já foram registrados localmente.
A auditoria encontrou 14 grupos duplicados (28 registros); o original foi
preservado e uma cópia `landmarks-pretreino-auditado` com **4.025 amostras** passou.
Usar essa cópia, não o corpus original reprovado. Ver
[relatório de regularização](auditoria-pretreino-2026-09-10.md), inclusive os limites
da declaração histórica e as instruções de empacotamento privado.

Não se deve atribuir automaticamente a configuração atual a arrays históricos.
O utilitário offline [registrar_legado.py](../computer-vision-model/datasets/registrar_legado.py)
confere o vídeo ainda disponível contra o índice local e cria os sidecars. Para
landmarks, exige a configuração usada naquela extração e confirmação explícita;
o registro conserva `config_declarada_para_legado: true`. Isso é uma declaração
histórica, não prova retrospectiva da execução do extrator.

Migração executada a partir da raiz com snapshot histórico verificado (retomável):

```bash
python computer-vision-model/datasets/registrar_legado.py \
  --fonte vlibrasil \
  --videos computer-vision-model/PoC/data/raw-pretreino \
  --indice computer-vision-model/datasets/.cache/davimedio01_v-librasil.json \
  --landmarks computer-vision-model/PoC/data/landmarks-pretreino \
  --config-extracao computer-vision-model/datasets/configs/extracao-vlibrasil-2026-09-08.yaml \
  --confirmar-config-legada
```

Sem vídeo/índice/configuração histórica confiável, o corpus fica bloqueado para
pré-treino. Não há flag para ignorar a auditoria. A migração pode ser retomada;
registros existentes incompatíveis nunca são sobrescritos.

Colisões de slug legado só são resolvidas quando tamanho/CRC identificam um
único membro do índice; empate continua bloqueado. Para reproduzir a preparação
conservadora **em um destino novo**, sem remover nada da entrada:

```bash
python computer-vision-model/datasets/preparar_corpus_auditado.py \
  --entrada computer-vision-model/PoC/data/landmarks-pretreino \
  --saida computer-vision-model/PoC/data/landmarks-pretreino-auditado --aplicar
```

Sem `--aplicar`, só calcula o plano. A cópia exclui **todos** os membros de grupos
duplicados, não escolhe rótulos/pessoas arbitrários. O plano fica em
`preparacao.json` no destino; a auditoria oficial abaixo continua obrigatória.

## Auditar e executar

No diretório de treino:

```bash
python pretreinar.py --corpus ../PoC/data/landmarks-pretreino-auditado --auditar
python pretreinar.py --corpus ../PoC/data/landmarks-pretreino-auditado \
  --objetivo contrastivo --pessoa-val V03 --saida resultados-contrastivo
python treinar.py --fontes minds --arquitetura resnet \
  --inicializar resultados-contrastivo/backbone_resnet.pt --saida resultados-transferencia
```

`--auditar` não constrói modelo, não baixa pesos e não treina. O treino normal
repete a auditoria: não confia num resultado antigo. O objetivo permanece
`classificacao` por compatibilidade; use `--objetivo contrastivo` explicitamente.
O [notebook GPU](../computer-vision-model/treino/notebook_gpu.ipynb) já faz isso.

## Checkpoints

O backbone guarda no `.pt` e no JSON:

- snapshot de todos os registros auditados e hash canônico do inventário;
- snapshot/hash do manifesto de avaliação;
- IDs das amostras de treino, validação, galeria, elegíveis para otimização e descartadas;
- configuração completa, argumentos, métrica e época selecionada;
- commit Git, indicação de árvore modificada, conteúdo/hash dos fontes Python e
  versões de Python, Torch, torchvision, NumPy, PyYAML e SciPy.

O modelo `--final` registra o inventário MINDS, o backbone pai e seu hash. Dados
MINDS legados sem sidecar são identificados como tal, não declarados verificados.
O modo final reutiliza pessoas de treino na validação: isso fica explícito e não
gera nova estimativa independente. A LOSO produz relatórios, não checkpoints por fold.
Chamadas genéricas ao saver sem inventário preservam compatibilidade, mas gravam
um aviso de proveniência incompleta; não são checkpoints de experimento auditado.

## Validação e compartilhamento

As regressões estão em [test_proveniencia.py](../computer-vision-model/treino/test_proveniencia.py)
e são chamadas pela suíte de treino; também há testes de ingestão e PoC.
Incluem subprocesso que rejeita MINDS antes do treino e pré-treino sintético que
salva um backbone com inventário e partição disjunta. Nenhum resultado sintético
prova melhora na acurácia do produto.

Os pacotes do notebook devem conter arrays e sidecars, não vídeos nem credenciais.
V-LIBRASIL declara CC BY-NC-ND: não publicar dados/derivados ou tratar os checkpoints
como automaticamente liberados para produto comercial. Verificar termos e permissões.
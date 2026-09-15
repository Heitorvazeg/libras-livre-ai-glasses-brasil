# Preparação final e calibração experimental — 14/09/2026

## Decisão e escopo

Manter o backbone/receita aprovados e os **50 clipes de calibração existentes**.
Não buscar novas pessoas, refazer pré-treino ou reabrir FILHO nesta frente.
Exposição prévia das pessoas ao SupCon/seleção é declarada; não há teste
independente nem aprovação de entrega. O conjunto não foi descartado ou alterado.

Implementação local concluída e validada com dados sintéticos. **Não houve
treino final, inferência/calibração nos 50 clipes, conversão TFLite real, alteração
de assets Android, commit ou push nesta etapa.**

## 1. Notebook e snapshot

O [notebook final](../computer-vision-model/treino/notebook_treino_final.ipynb)
exige `COMMIT_APROVADO`, um SHA completo de 40 caracteres; pode ser fornecido pela
variável de ambiente `LIBRAS_COMMIT_FINAL`. Não há fallback para HEAD ou branch.
**Após commitar e publicar estas correções**, preencher esse valor com o commit
aprovado. Não foi inventado um hash de commit futuro nem publicado código sem
autorização. O valor fica vazio deliberadamente até essa escolha operacional.
Localmente, preferir a variável de ambiente: editar o notebook rastreado para
inserir o SHA deixaria a árvore modificada, que a guarda recusa. No Kaggle, o
notebook enviado ao editor fica separado do clone executado.

No Kaggle, clone separado por commit e checkout detached. Clone existente não
recebe reset/merge automático. [codigo_final.py](../computer-vision-model/treino/codigo_final.py)
confere HEAD, arquivos rastreados modificados e código novo não rastreado nas
pastas executáveis. A guarda se repete antes dos testes e do treino longo.

GPU CUDA é obrigatória neste notebook. O ambiente, versões dos pacotes, CUDA,
cuDNN, GPU, threads/workers, comando e inventário são registrados em saídas
privadas. Registros divergentes nunca são sobrescritos. Fixar versões/seed não
promete determinismo entre dispositivos. O checkpoint também guarda proveniência.

## 2. MINDS sem mistura de extrações

[entrada_final.py](../computer-vision-model/treino/entrada_final.py) exige:

- **800 identidades exatas:** M01/M02/M05/M06/M08/M10/M11/M12 × 20 sinais × reps 01–05.
- NPY float32 finito, `(T>=3,57,3)`, sem pickle ou bytes extras.
- Somente os NPY esperados, sidecars correspondentes opcionais e marcador Git opcional.
- Sem symlinks, membros tar duplicados, caminhos externos ou diretórios aninhados.
- Staging temporário, validação antes da instalação e rename do diretório inteiro.
- Reuso somente quando origem, destino, registro e bytes continuam idênticos.

`preparar_minds(origem, destino)` retorna a pasta preparada. `validar_minds()`
produz inventário de todos os bytes (incluindo sidecars); um manifesto de referência
privado opcional permite conferir identidade com a preparação na origem.
Sem referência confiável, estrutura e hashes não certificam a extração histórica.

O notebook passa `--inventario-final` ao
[CLI do treino](../computer-vision-model/treino/treinar.py). Ele repete a validação
e confere as identidades **depois do loader**, antes de construir o modelo.
A flag só vale com `--final --fontes minds`; não altera o contrato LOSO nem
impõe 800 amostras aos testes sintéticos do modo final genérico.

**Entrada local:** a pasta histórica de landmarks contém 800 clipes M e 30 V.
Ela não é um pacote MINDS estrito e será recusada. Usar o pacote dedicado MINDS
de 800 clipes ou preparar uma cópia dedicada; **não apagar os 30 V originais**.
Em caso de múltiplos candidatos, informar `ORIGEM_MINDS` explicitamente.

O backbone segue conferido pelo SHA aprovado; extração em diretório temporário,
sem misturar tentativas anteriores. O final continua com 120 épocas, seed 20260917,
último estado, sem seleção/avaliação sobreposta. Não há retomada de otimização
interrompida; preservar tempo disponível e backup privado da sessão Kaggle.

## 3. Calibração externa, sem converter dados expostos em teste independente

[calibracao_externa.py](../computer-vision-model/treino/calibracao_externa.py)
tem três subcomandos separados. Consultar `--help` do módulo/subcomando; executar
no ambiente de treino com NumPy, PyTorch, torchvision, PyYAML e scipy disponíveis.

| Subcomando | Argumentos obrigatórios | Efeito |
|---|---|---|
| `protocolo` | `--acc-minima`, `--cobertura-minima`, `--saida` | Registra critérios explícitos antes da inferência; não escolhe defaults para o usuário. |
| `inferir` | `--checkpoint`, `--manifesto`, `--raiz-dados`, `--protocolo`, `--saida` | Inferência CPU por clipe, sem augmentação; registra logits e identidades. `--threads` padrão 2. |
| `ajustar` | `--evidencias`, `--saida` | Ajusta T e escolhe maior cobertura que atinge a acurácia empírica exigida; confere cobertura mínima. |

Para o [manifesto preservado](../computer-vision-model/treino/calibracao_naovista_manifesto.json),
`--raiz-dados` aponta para a pasta **computer-vision-model**, pois os caminhos
internos começam com `PoC/`. Usar caminhos de saída novos e privados em cada etapa.
Não adicionar arquivos de calibração ao MINDS do treinamento final.

Esta implementação aceita ST-GCN final com ossos/xyz recentrado, imputação,
kernel 9, sem movimento/adjacência adaptativa e com metadados explícitos.
Rótulos e inventário do checkpoint são obrigatórios. O produtor verifica
disjunção de pessoas/hashes contra o inventário de fine-tuning declarado;
**não certifica isolamento do pré-treino**. Somente carregar checkpoints próprios
e confiáveis: sua desserialização PyTorch pode executar pickle.

A sequência usa as mesmas funções de recentragem, imputação, ossos e reamostragem
de `DatasetSinais`, sem augmentação. Os arrays legados não permitem certificar
retroativamente seu layout/origem só pelo hash. A inferência sobre clipes completos
também não valida segmentação/reamostragem de janelas da câmera no app.

O protocolo é lido antes da inferência e vinculado por hash à evidência. Mudar
as metas posteriormente invalida essa evidência. Hash não é assinatura: não há
proteção contra alguém reescrever deliberadamente todos os arquivos/hashes.

### Saída e exportação

O bloco de calibração usa **schema 3**, `escopo=checkpoint_final_experimental`,
`avaliacao_independente=false`, `aprovado_entrega=false`, exposição prévia e
`metricas_medidas_em=mesmos_dados_do_ajuste`. Registra ECE antes/depois, acurácia
dos aceitos, cobertura, tamanho do ajuste, critérios e hashes das fontes.

Sem limiar viável, o limiar é `null`. Se a acurácia for atingida mas a cobertura
ficar abaixo da meta, a política também falha (`meta_nao_atingida=true`). O JSON
de diagnóstico pode ser salvo nesses casos, mas **não pode ser exportado**.

[exportar.py](../computer-vision-model/treino/exportar.py) aceita esse JSON em
`--calibracao` **somente com o mesmo checkpoint/rótulos e float32**. Antes de
converter e antes de escrever o sidecar, reabre evidência, protocolo, manifesto,
clipes e checkpoint, compara hashes e recalcula métricas/política no T fornecido.
Não desserializa checkpoints ao validar evidências e não reajusta T na exportação.
Conservar as fontes nos caminhos absolutos registrados. Não editar hashes para
contornar recusa. Quantização exige outro estudo de calibração, não transferência
automática deste ajuste.

O schema 2 LOSO é preservado; pools continuam não exportáveis. Sem `--calibracao`,
o export permanece sem bloco de calibração. O limiar sugerido **não é aplicado
automaticamente pelo app**; a integração Android continua separada.

## 4. Validação desta etapa

- **174 testes passaram**, incluindo entrada final, commit, notebook estático,
  política final, calibração externa, LOSO, entrada de pré-treino e ferramentas
  de avaliação/paridade existentes. Ambiente numérico: Python 3.12.3 já existente.
- **Selftest completo passou**, com dados sintéticos e conversores simulados.
- Os **50 hashes e arrays reais** foram conferidos pelo novo leitor, sem
  inferência ou ajuste real; nenhuma amostra/rótulo/hash do manifesto foi trocada.
- Notebook validado estaticamente, **nenhuma célula executada**. Kaggle/CUDA e
  publicação do snapshot ainda são passos operacionais, não resultados destes testes.
- Diagnóstico do editor ainda pode indicar PyTorch ausente no ambiente selecionado
  Python 3.14; não foram instalados pacotes para mascarar esse ambiente diferente.
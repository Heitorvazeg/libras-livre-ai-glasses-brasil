# P1 — política do checkpoint final

**14/09/2026 — decisão e implementação concluídas; treino real não executado.**
Resolve a escolha de seed e de estado a salvar, não aprova o modelo para produção.
Não depende de instalar Android nem de repetir o piloto M01.

**Atualização de preparação (14/09):** notebook exige commit explícito e inventário
MINDS completo. Por decisão do usuário, os 50 clipes disponíveis serão usados
somente em calibração **experimental com exposição prévia declarada**, sem novas
pessoas ou retreino do backbone agora. Isso não satisfaz o requisito de teste
independente abaixo nem altera a receita final. Ver
[implementação, uso e testes](preparacao-final-e-calibracao-experimental-2026-09-14.md).

## 1. Política v1 fixada antes do treino final

| Decisão | Valor e justificativa |
|---|---|
| Seed do fine-tuning final | **20260917**, já fixada no plano integrado antes do piloto; não escolhida pelo melhor recall/LOSO |
| Backbone | O já fixado para o piloto, SHA-256 **7a6e997c5830139162b32bc9b37a48e6eb5b8d6222836da87cb95e94ecf6baf5** |
| Pré-treino | V-LIBRASIL+MALTA contrastivo, V03, seed 0, 15 épocas, P32/K2; sem WLASL e sem negativos extras |
| Representação | ST-GCN, ossos+xyz recentrado, imputação, sem movimento/adjacência adaptativa, kernel 9 |
| Dados final | 800 clipes MINDS, vinte sinais, oito pessoas; conferir inventário antes de executar |
| Otimização | Adam, **120 épocas**, lr 1e-3, wd 1e-4, batch64, cosseno T_max=120 |
| Estado salvo | **Última época (120)**, não menor perda em pessoa que também foi treinada |
| Comparação de seeds finais | Não executar competição nem escolher por acurácia de treino |
| Calibração | Não calculada; não anunciar T=1/limiar0,60 como calibrados |
| Saída | Nova e privada; nenhum asset de produção substituído automaticamente |

As 120 épocas vêm do orçamento da receita já adotada, **não de otimização em M01,
M10 ou M11**. Não se afirma que a época 120 seja superior às melhores épocas LOSO.
Com todos os dados no ajuste, falta validação independente para escolhê-la;
um orçamento fixo torna a escolha explícita e reproduzível. O LOSO histórico
seleciona por validação e tem outro tamanho de treino: seus 96,625% avaliam aquele
procedimento, **não são acurácia medida deste futuro arquivo final**.

A seed não é um hiperparâmetro a escolher pelo teste. Os dois pacotes auditados
têm backbones diferentes apesar da mesma seed de pré-treino: não isolam o efeito
da seed do fine-tuning. Nenhuma garantia de melhor seed ou de determinismo entre
CPU/CUDA/versões é alegada. Registrar dispositivo, bibliotecas, threads e workers
antes da execução e preservar o ambiente do artefato final.

## 2. Implementação

[treinar.py](../computer-vision-model/treino/treinar.py) agora exige, para `--final`:

- **`--politica-final ultima`** e **`--semente`** explícitas;
- orçamento positivo e diretório novo/vazio;
- ausência de `--salvar-evidencias` (exclusivo do LOSO).

O final usa todos os clipes, sem loaders de validação/teste e sem chamadas a
`_avaliar`. Perda/acurácia mostradas durante otimização são diagnósticos de treino,
não critério de seleção. Não finitos impedem salvar o modelo. Metadados registram
`epoca_salva`, `politica_selecao=ultima`, `avaliacao_independente=false`, hash do
backbone e partição com validação/teste vazios. O contrato de exportação é mantido.

**Compatibilidade:** comandos antigos com apenas `--final` agora falham com uma
orientação explícita; não reproduzem silenciosamente a seleção sobreposta. Não
foi editado o notebook de outra PoC. No LOSO, a flag não aparece em `args` quando
ausente; seleção pela menor perda de validação e chamadas/RNG permanecem no caminho
anterior. A identidade estrita do código muda, como deve ocorrer após alteração.

O snapshot anterior ao ajuste P1 está preservado no commit **0b194a1**. Para
reproduzir o piloto M01, usar esse snapshot e seu ambiente original: não atualizar
os hashes das evidências antigas para aceitar código novo. A recusa por hash é
uma proteção, não motivo para treinar novamente ou reescrever relatórios.

## 3. Validação feita, sem treino real

Quatro testes em [test_politica_final.py](../computer-vision-model/treino/test_politica_final.py):

1. GCN sintético, duas épocas: estado salvo igual ao último passo de otimização;
   `_avaliar` proibido durante a execução; época, partição e contrato verificados.
2. Recusa de seed/política ausentes e uso fora do final, antes de carregar dados.
3. Recusa de saída ocupada, preservando os bytes existentes.
4. Perda NaN impede publicação do checkpoint.

Os doze testes de evidências LOSO também passaram, incluindo seleção, pesos/RNG,
retomada e adulteração. Nenhum modelo de entrega foi treinado nesta tarefa.

A regressão combinada terminou com **48 testes aprovados**, incluindo os quatro
da política final e os doze de evidências. O selftest completo também passou no
ambiente de treino existente, usando dados sintéticos.

## 4. O que falta para produzir e aprovar o artefato

1. Executar **um** ajuste final com a política acima, inventário completo e ambiente
   congelado. Conferir hashes, metadados e finitude antes de exportar. Não usar o
   checkpoint M01 como substituto: ele não treinou com todas as pessoas.
2. Converter o novo arquivo float32 e conferir sua própria paridade. O TFLite
   aprovado no piloto é outro artefato; aprovação não se transfere pelo nome.
3. Para confiança, reservar dados novos de pessoas não vistas em treino/pré-treino,
   separados em calibração e teste por pessoa. Fixar procedimento/critério antes
   de observar o teste. Os JSON legados sem logits não permitem essa calibração;
   M01 já examinado não deve ser promovido a validação para ajustar o limiar.
4. Avaliar os turnos do [M9](m9-diagnostico-roteiro-2026-09-14.md), incluindo
   rejeições e erros de alta confiança. Não impor correções semânticas para
   esconder `filho→medo`.

P1 deixa de estar bloqueada por escolha indefinida de seed. Produção do arquivo,
calibração e validação de demo são pendências distintas e explícitas, não tarefas
executadas por este documento. Android fica pausado nesta frente.
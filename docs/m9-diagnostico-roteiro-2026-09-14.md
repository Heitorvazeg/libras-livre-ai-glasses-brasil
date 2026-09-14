# M9 — diagnóstico dos sinais do roteiro

**14/09/2026. P2: análise dos resultados existentes concluída; aprovação da demo
real permanece pendente.** Não houve novo treino, alteração do roteiro ou de
limiares. O piloto M01 não substitui esta análise das oito pessoas.

**Continuação focada em FILHO:** a [inspeção dos 160 pares locais](investigacao-filho-2026-09-14.md)
separa ausência nas bordas/interior, controles M12/VACINA e a assimetria de
composição dos folds. Causa visual/linguística ainda não confirmada.

## 1. Evidências verificadas

[auditar_m9_loso.py](../scripts/auditar_m9_loso.py) leu diretamente dois pacotes
tar.gz, sem extrair arquivos nem desserializar pesos. Conferiu oito folds, vinte
rótulos, cem predições por pessoa, cinco por sinal, pessoa de validação distinta,
receita, acurácias e igualdade exata entre matriz salva e matriz reconstruída.
Também conferiu os hashes do backbone e de seus metadados contra o registro de
inicialização. Quatro testes de guardas passaram, além da execução nos dois pacotes.

| Execução | Seed fine-tuning | Acertos globais | Acertos nos seis sinais únicos |
|---|---:|---:|---:|
| Pacote semente20260917 | 20260917 | 773/800 | 224/240 (93,33%) |
| Pacote **v1-wlasl**, apesar do nome | 20260918 | 773/800 | 227/240 (94,58%) |

Ambos declaram **MALTA+V-LIBRASIL, sem WLASL nem negativos extras**. Inventário:
8.900 MALTA e 4.025 V-LIBRASIL, nenhuma amostra MINDS no pré-treino registrado.
Contrastivo, V03 reservada, seed 0, 15 épocas, P32/K2; fine-tuning ST-GCN
ossos+xyz recentrado, 120 épocas, batch 64, lr 1e-3, wd 1e-4, cosseno.

Código, configuração, entrada, manifesto, preparação e versões declaradas são
iguais entre esses dois pacotes. **Os backbones têm bytes diferentes**, embora
ambos declarem seed de pré-treino 0. Portanto não atribuir as diferenças observadas
somente à seed do fine-tuning. Não escolher uma seed pelo recall observado aqui.
O controle padrão seed 20260916 não foi localizado como pacote completo nesta
auditoria; variantes WLASL-v2/negativos-extras não foram misturadas ao candidato.

| Identidade | SHA-256 |
|---|---|
| Pacote seed17 | `cda704f05fe1a08316522dd3e2bb0fcfd82011a72cb50a81a4df9c8f8d3026ea` |
| Pacote seed18 (v1-wlasl) | `757316ad180f613b56f6ba4594acf84c12d8164c9ddab31196be8177d0e33575` |
| Backbone seed17 | `7a6e997c5830139162b32bc9b37a48e6eb5b8d6222836da87cb95e94ecf6baf5` |
| Backbone seed18 | `1f2a8d36bf6433bb3534b83a2bfa038d219795db7745cd31a7f03b8c8004e6d3` |
| Auditoria privada v1 | `dae588b58f6ccb93a10d2bdd8600577382f932f305a1840036bf15eaf12b3873` |

Relatório completo em `experimentos-privados/m9-20260914/auditoria-v1.json`.
Contém hashes dos membros, matrizes, args, fontes e contagens por pessoa.
Esses hashes vinculam a análise aos arquivos, não certificam rótulos linguísticos.
Os JSON legados **não têm IDs, logits ou pesos por fold**: não foi feita inferência
nova, atribuição de erro a repetição específica ou pareamento de erros por clipe.

## 2. Recall e confusões

| Sinal | Seed17 | Seed18 |
|---|---:|---:|
| filho | **30/40 (75%)** | **32/40 (80%)** |
| medo | 36/40 (90%) | 40/40 (100%) |
| banheiro | 38/40 (95%) | 36/40 (90%) |
| vontade | 40/40 (100%) | 39/40 (97,5%) |
| vacina | 40/40 (100%) | 40/40 (100%) |
| cinco | 40/40 (100%) | 40/40 (100%) |

`filho→medo` é a confusão dirigida mais frequente de **todo o vocabulário** nas
duas execuções: 6 e 5 ocorrências. Mas não é o único problema de `filho`:
também há `filho→aproveitar` (3 na seed17) e `filho→vacina` (1/3 nas seeds17/18).
Corrigir automaticamente `medo` para `filho` seria incorreto: `medo` é uma classe
válida, e a confusão inversa também aparece (4 na seed17).

### Concentração por pessoa (acertos de cinco)

| Pessoa | filho 17/18 | medo 17/18 | banheiro 17/18 | vontade 17/18 |
|---|---:|---:|---:|---:|
| M01 | 5 / 5 | 5 / 5 | 5 / 5 | 5 / 5 |
| M02 | 5 / 5 | 5 / 5 | 5 / 5 | 5 / 4 |
| M05 | 5 / 5 | 5 / 5 | 5 / 5 | 5 / 5 |
| M06 | 5 / 5 | 5 / 5 | 5 / 5 | 5 / 5 |
| M08 | 5 / 5 | 5 / 5 | 5 / 4 | 5 / 5 |
| **M10** | **0 / 0** | **1 / 5** | 5 / 5 | 5 / 5 |
| **M11** | **0 / 2** | 5 / 5 | 5 / 5 | 5 / 5 |
| **M12** | 5 / 5 | 5 / 5 | **3 / 2** | 5 / 5 |

`vacina` e `cinco`: 5/5 em todas as pessoas, nas duas execuções. Isso não garante
perfeição em novos sinalizantes. Todos os erros de `filho` se concentram em M10/M11;
M10 falha nas cinco tentativas em ambas as execuções. O problema não é bem
representado por uma média global de 96,6% ou pelo piloto favorável M01.
Não inferir causa (variante regional, extração, execução do sinal) só das contagens.

## 3. Conexão com o roteiro real

O [roteiro do placeholder](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/reconhecimento/SignClassifier.kt)
tem **quatro turnos e oito ocorrências, com seis sinais únicos**:

1. FILHO VACINA VONTADE;
2. CINCO;
3. FILHO MEDO;
4. BANHEIRO VONTADE.

Portanto `filho` afeta dois turnos. As 240 observações acima são clipes isolados
dos seis sinais, **não 240 frases nem ensaios do roteiro de oito ocorrências**.
Não multiplicar recalls para anunciar probabilidade de uma frase/atendimento correto:
há dependência por pessoa, repetição, segmentação e contexto ainda não medidos.

O [avaliador de frase](../mobile-app-companion/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/libras/dialogo/AvaliadorDeFrase.kt)
rejeita se qualquer confiança ficar abaixo do limiar, houver falha de classificação
ou não restar glosa conhecida. Há filtro de léxico; **não falta um filtro genérico
de confiança**. Porém `filho`, `medo`, `vacina` e `aproveitar` são conhecidos:
erro de alta confiança entre eles pode atravessar a guarda. Sem logits históricos
não é possível contar quantos desses erros seriam rejeitados em 0,60.

## 4. Decisão M9

- **Não trocar palavras automaticamente nem mapear uma classe para outra.** Não
  há sinônimo de `filho` demonstrado dentro das vinte classes; `aluno` muda o
  sentido, e `medo→ruim` também. Uma simplificação seria outro roteiro, não
  correção do reconhecimento. Exige decisão de produto e revisão de LIBRAS.
- **Manter o roteiro como cenário de teste, não aprová-lo como demo de visão.**
  Priorizar os dois turnos com FILHO e o turno BANHEIRO VONTADE na validação real.
  O modo placeholder pode demonstrar integração desde que identificado como tal.
- **Não reduzir limiar nem escolher seed olhando M10/M11/M12.** Essas pessoas
  foram testes das execuções auditadas. Reutilizar erros para uma nova intervenção
  transforma essa análise em desenvolvimento e exige nova avaliação independente.
- Para fechar a aprovação de demo: congelar checkpoint/configuração, usar
  gravações contínuas com pessoas não vistas, revisão humana dos sinais e registro
  de logits, rejeições, segmentação e frase pronunciada. Registrar todos os ensaios,
  não apenas tentativas bem-sucedidas. Definir critérios de aceitação antes da coleta.

**Encerrado nesta tarefa:** contagens históricas, localização do risco por pessoa,
conferência da receita e decisão de não mascarar o erro por roteiro/limiar.
**Não encerrado:** reconhecimento de frases nos óculos, diagnóstico visual da causa
e aprovação linguística/operacional da demo. Não dependem de mais testes Android
para reconhecer que o risco existe; dependem de dados novos para sua resolução.
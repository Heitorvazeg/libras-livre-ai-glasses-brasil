# FILHO — investigação descritiva dos vídeos e landmarks

**14/09/2026. Frente FILHO definitivamente encerrada por decisão do usuário;
causa inconclusiva, risco conhecido preservado.** Não há novas ações ou retomada
previstas nesta frente. O encerramento não significa correção dos erros nem
aprovação da demo. O usuário informou não ter acesso a profissional de Libras.
Preservar exemplos, rótulos e risco; não treinar, reextrair ou criar heurísticas
para FILHO a partir destas observações. O avanço posterior autorizado foi
corrigir os defeitos de engenharia da calibração, não retomar Android.

## 1. Evidência e método

O [diagnóstico M9](m9-diagnostico-roteiro-2026-09-14.md) mostrou FILHO com 0/5
em M10 nas duas execuções, 0/5 e 2/5 em M11 e 5/5 nas demais pessoas.
Os resultados legados não identificam os clipes por ID. Esta inspeção não
atribui a uma repetição local uma predição específica desses pacotes.

[investigar_filho.py](../scripts/investigar_filho.py) inspecionou **160 pares**:
oito pessoas × cinco repetições × FILHO/MEDO/APROVEITAR/VACINA. Os outros três
sinais foram incluídos porque são destinos das confusões históricas de FILHO.
Todos os clipes dessas combinações foram mantidos, sem seleção por aparência.

- **19.832 frames** decodificados; 160 vídeos de **1920×1080**.
- Cada NPY local tem a mesma contagem de frames que seu vídeo. Nenhum NPY vazio,
  não finito, de outro layout ou de outro dtype foi encontrado: `(T,57,3)`, float32.
- 160 hashes distintos de vídeo e 160 de NPY; sem duplicatas byte a byte nesse recorte.
- **Nenhum desses 320 arquivos tem sidecar de proveniência.** Não atribuir a eles
  retrospectivamente o extrator/configuração atual, nem afirmar identidade com
  os insumos dos treinos históricos apenas pelos nomes.
- Hashes foram conferidos antes/depois da leitura. Os originais não foram alterados.

O layout é interpretado conforme o pipeline atual: 15 pontos de pose e 21 por
mão; bloco XY inteiro em zero marca ausência. Compara-se bruto com **a imputação
da leitura existente**, limite 5, após recentrar z. Não se altera essa função,
nem se inclui aqui a segunda imputação do grafo ou se mede efeito nos logits.

Medidas de movimento usam apenas pares consecutivos com mão presente nos dois
frames. São deslocamentos XY por frame NPY, em unidades de distância entre
ombros, **não velocidade física**. Não há alinhamento NPY-vídeo: igualdade de
contagens não recupera índices/PTS nem certifica como ocorreu a extração antiga.

## 2. FILHO: cobertura não explica sozinha os erros

Percentuais ponderados por frames dos cinco clipes de cada pessoa, não por
tentativas de reconhecimento. “Sem mãos” significa os dois blocos ausentes.

| Pessoa | Frames | Sem mãos bruto | Sem mãos após imputação da leitura | Mão esquerda presente | Mão direita presente | Acertos históricos 17/18 |
|---|---:|---:|---:|---:|---:|---:|
| M01 | 702 | 47,6% | 47,6% | 0,0% | 52,4% | 5/5 e 5/5 |
| M02 | 621 | 57,3% | 56,7% | 0,0% | 42,7% | 5/5 e 5/5 |
| M05 | 653 | 53,1% | 52,8% | 0,0% | 46,9% | 5/5 e 5/5 |
| M06 | 577 | 48,0% | 44,2% | 1,6% | 51,6% | 5/5 e 5/5 |
| M08 | 503 | 48,3% | 45,3% | 2,8% | 49,9% | 5/5 e 5/5 |
| **M10** | **419** | **69,9%** | **67,8%** | **0,0%** | **30,1%** | **0/5 e 0/5** |
| **M11** | **722** | **0,0%** | **0,0%** | **100,0%** | **99,4%** | **0/5 e 2/5** |
| M12 | 851 | 0,0% | 0,0% | 100,0% | 95,9% | 5/5 e 5/5 |

As colunas locais e históricas são uma comparação descritiva por pessoa/sinal,
não um pareamento certificado por hash de clipe.

### M10: separar bordas de interrupções internas

Dos **293/419** frames sem mãos, **264 estão nas bordas**: antes da primeira ou
depois da última detecção da mão direita. Há **29 ausências internas**; a
imputação preenche 9 e mantém as 20 em lacunas maiores que cinco.

| Repetição local | Frames | Ausência no início/fim | Ausências internas | Maior lacuna interna | Preenchidos |
|---|---:|---:|---:|---:|---:|
| 01 | 89 | 23 / 42 | 0 | 0 | 0 |
| 02 | 96 | 28 / 34 | 8 | 7 | 1 |
| 03 | 77 | 6 / 38 | 7 | 6 | 1 |
| 04 | 80 | 14 / 35 | 8 | 7 | 1 |
| 05 | 77 | 7 / 37 | 6 | 5 | 6 |

“Borda” **não significa automaticamente repouso**: pode incluir a articulação
antes de o detector adquirir a mão, mão fora do quadro ou oclusão. Só a revisão
do vídeo completo pode separar essas possibilidades. Não converter 69,9% em
“taxa de falha do detector durante o sinal”. A repetição 01 merece revisão:
tem baixa cobertura sem nenhuma interrupção interna detectada.

**Contraprova à causa única por ausência:** M10/VACINA tem **66,2%** dos frames
sem mãos no bruto e **59,0%** após a imputação da leitura, mas 5/5 acertos em
ambas as execuções históricas. M06/FILHO tem **51** ausências internas da mão
direita, mais que M10 (29), e também 5/5 em ambas. Contagens não identificam se
as lacunas atingem a parte discriminativa do sinal.

### M11: alta presença não assegura landmarks corretos

M11 tem apenas quatro ausências da mão direita em 722 frames; todas preenchidas
pela imputação de leitura. A mão esquerda está presente em todos os frames.
Logo, a hipótese simples “todos os erros são falta de detecção” não acomoda
M11. Mas presença não é prova de localização correta, anatomia correta ou de
informação suficiente para distinguir FILHO dos outros sinais.

**Assimetria relevante dos folds:** a [rotação de validação](../computer-vision-model/treino/dados.py)
e os pacotes auditados colocam **M12 na validação quando M11 é teste**. Assim,
ambas ficam fora do ajuste dos pesos nesse fold. Nos dados locais de FILHO,
são justamente as duas pessoas com 100% de presença da mão esquerda; as seis
restantes têm entre 0% e 2,8%. Quando **M12 é teste**, a validação é M01 e
**M11 participa do treino**.

Isso é uma hipótese de falta de exemplos semelhantes no ajuste, não prova de
que o padrão de detecção causou o erro. M12 ainda influencia seleção de época
como validação; não foi completamente invisível ao procedimento. Outros sinais
e outros aspectos da realização podem explicar o contraste. Não trocar a
partição agora para obter um resultado melhor e chamá-lo de confirmação.

## 3. Pistas espaciais e temporais para orientar revisão

- Nos cinco FILHO de M10, a mediana de X do punho direito **nos frames presentes**
  fica entre **−1,177 e −1,006** unidades de ombro. Nas outras pessoas, as medianas
  por clipe vão de **−0,711 a −0,252**. Isso indica diferença espacial no vetor
  observado, mas não distingue realização do sinal, rotação corporal, escala
  projetada dos ombros ou localização incorreta da mão.
- Duração mediana **do clipe**: M10 **2,63 s**, M11 **4,67 s**, M12 **5,47 s**.
  Não concluir que M10 articula mais rápido: faltam início/fim humanos do sinal.
- Os quantis de deslocamento e de distância entre o punho da mão e o da pose
  foram preservados por clipe. A distância entre detectores não é erro contra
  uma anotação de referência, e não foi escolhido limiar para chamá-la de falha.

## 4. Material arquivado e roteiro histórico (não executar)

Gerados apenas na área privada:

- [Relatório completo](../experimentos-privados/investigacao-filho-20260914/v1/relatorio.json),
  com hashes, medidas por clipe, ambiente e limites.
- [Galeria local](../experimentos-privados/investigacao-filho-20260914/v1/galeria.html):
  160 contatos de seis frames uniformes e links para vídeos originais completos.
  Abrir no navegador local; mover só a página quebra os links. Sem servidor externo.
- [Planilha de revisão humana](../experimentos-privados/investigacao-filho-20260914/v1/revisao-humana.csv):
  inicialmente com julgamentos em branco; posteriormente recebeu **16 anotações
  de outro agente**, identificado como sem conhecimento de Libras (15 FILHO de
  M10/M11/M12 e uma VACINA de M10). Não são validação linguística profissional.

As anotações usam principalmente contatos de seis frames, com amostras adicionais
no primeiro FILHO de cada uma dessas pessoas. Os hashes dos vídeos anotados foram
conferidos, mas isso não confirma as interpretações visuais nem o pareamento com
os treinos históricos. A planilha original não foi alterada nesta revisão.

**Cuidado com os intervalos anotados:** `fim` no relatório é a **quantidade de
frames ausentes após a última detecção**, não o índice da última detecção. Por
exemplo, M10/FILHO rep02 não tem janela 28–34: a mão direita está presente nos
índices NPY (base zero, inclusivos) 28, 36–59 e 61. M10/VACINA rep01 não tem
janela 24–32: são 24, 34–47, 50–55, 60 e 64–66. Sem PTS/alinhamento certificado,
esses índices não viram limites humanos da articulação.

**Nenhuma revisão visual/linguística foi declarada concluída.** Contatos não
substituem assistir aos movimentos completos. **Roteiro anterior preservado
apenas como histórico**, cancelado como próxima ação pelo encerramento da frente:

1. FILHO M10, cinco repetições, começando pela 01 (sem lacuna interna): marcar
   início/fim real, observar se a mão está visível durante as bordas sem detecção.
2. Comparar com FILHO M01/M02/M06 e VACINA M10. Verificar se baixa cobertura
   representa repouso, enquadramento ou perda de articulação importante.
3. FILHO M11 versus M12, cinco repetições de cada: revisar realização, orientação,
   configuração/localização das mãos e se o rótulo é linguísticamente adequado.
4. Contrastar FILHO com MEDO/APROVEITAR/VACINA da mesma pessoa, sem informar uma
   suposta classe predita por repetição. Não existe esse pareamento histórico.

O plano anterior previa, após essa revisão, decidir entre hipóteses de extração,
segmentação ou representação/realização, eventualmente com pequena reextração
diagnóstica preservando PTS e índices originais. **Plano não executado e
cancelado como tarefa futura.** Não foi demonstrada uma solução causal.

Mudanças orientadas por estes testes históricos são desenvolvimento. Uma melhora
nos mesmos casos não substitui nova avaliação independente.

## 5. Reprodutibilidade e validação

- Execução: Python 3.12.3, NumPy 1.26.4, OpenCV 4.11.0, ambiente já existente.
- [Sete testes](../scripts/test_investigar_filho.py) passaram: lacunas/bordas,
  imputação sem mutação, exclusão de saltos para zero, dados inválidos, inventário,
  saída privada sem sobrescrita e contato com vídeo sintético.
- Regressão focada com auditoria M9: **11 testes aprovados**; `git diff --check`
  sem problemas de whitespace. Código de treino/modelo não foi alterado.
- Relatório SHA-256: `0fc9d7f96274cccbdef82275703419cf492c428d410bc24c9efa404f28578c05`.
- Script SHA-256: `1f3ab05e306fa646794b7bbb544df1b6420c3192f18bbcf13ac301f991158d61`.
- Editor mantém avisos de importação de `cv2`/`dados` no ambiente selecionado;
  execução no ambiente de análise comprovada, sem instalar dependências.

**Conclusão desta etapa:** há padrões distintos em M10 e M11. Aumentar imputação,
cortar bordas, trocar rótulos ou retreinar automaticamente não é justificado
pelas medidas atuais. **Não há próximo passo nesta frente: encerramento
definitivo, sem causa comprovada.** Relatórios, testes, exemplos e rótulos são
preservados. O risco permanece nos critérios gerais de avaliação da demo,
sem manter a investigação FILHO como pendência de execução.
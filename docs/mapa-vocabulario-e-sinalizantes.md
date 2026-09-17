# Mapa de vocabulário — quais palavras temos e com quantas pessoas cada uma

**Medido em:** 2026-09-11 · **Fonte:** os arquivos `.npy` em disco, não os índices das bases

Este documento responde uma pergunta só: **para cada palavra que temos, quantas pessoas
diferentes a sinalizaram?** É a pergunta que decide vocabulário, porque acurácia
signer-independent só é afirmável onde existe mais de uma pessoa por palavra.

Os números vêm de contar os nomes dos arquivos extraídos
(`pessoaXX_sinal-PALAVRA_repNN.npy`), que é a fonte de verdade do pipeline
([`CONTEXTO.md`](CONTEXTO.md) §4). Não vêm de tabela de dataset nem de estimativa — a
estimativa do MALTA já errou três vezes por isso
([`decisao-datasets-e-licencas.md`](decisao-datasets-e-licencas.md) §3).

---

## 1. Leia estas quatro ressalvas antes de usar qualquer número

**1. Rótulo igual não é sinal igual.** Somar pessoas entre corpora supõe que `filho` no
MINDS e `filho` no MALTA são o mesmo sinal. Libras tem variação regional, e este projeto
já tropeçou nisso: três sinais têm **rótulos diferentes** entre MINDS e V-LIBRASIL, e
comparar por nome deixou 9 de 30 clipes vazarem. Todo total da coluna "TOTAL" é
**limite superior**, pendente de conferência por consultor de Libras.

**2. As pessoas são sempre as mesmas.** Não são pessoas novas por palavra — são 19
sinalizantes ao todo, reaproveitados. `T002` (MALTA) aparece em 5.714 palavras; `V01`,
`V02` e `V03` em ~1.350 cada. Uma palavra "com 4 pessoas" é, em 494 dos 552 casos,
exatamente `V01,V02,V03,T002` — o mesmo quarteto. Isso **não** dá diversidade de
sinalizante; dá o mesmo trio de intérpretes mais o mesmo apresentador de dicionário.

**3. O MALTA numera variantes.** 1.422 dos 5.958 rótulos terminam em dígito (`chato1`,
`chato3`, `obrigado1`). São sinais distintos para a mesma palavra, ou sentidos distintos.
As contagens principais deste documento usam **correspondência estrita** de rótulo
(`chato1` ≠ `chato`). A §7 usa forma base (dígito final removido) e marca isso
explicitamente — ali o número é otimista de propósito, para responder "existe alguma
coisa?".

**4. Os 30 clipes V-LIBRASIL reservados estão contados à parte** (coluna `V-res`). Eles
ficam fora do pré-treino de propósito; contá-los como material de treino disponível
desfaz a reserva. Verificado agora: as 10 palavras reservadas têm **0 clipes** no corpus
de pré-treino auditado — o isolamento segurou.

---

## 2. O que está em disco

| Corpus | Diretório | Clipes | Rótulos distintos | Pessoas |
|---|---|---:|---:|---:|
| MINDS-Libras | `PoC/data/landmarks` (prefixo `M`) | 800 | 20 | 8 |
| V-LIBRASIL reservado | `PoC/data/landmarks` (prefixo `V`) | 30 | 10 | 3 |
| V-LIBRASIL auditada | `PoC/data/landmarks-pretreino-auditado` | 4.025 | 1.353 | 3 |
| MALTA-LIBRAS | `PoC/data/landmarks-malta` | 6.353 | 5.958 | 8 |
| WLASL100 (**ASL**) | `PoC/data/landmarks-wlasl` | 1.013 | 100 | 64 |
| **Total** | | **12.221** | | |

Em Libras (as quatro primeiras linhas): **6.629 rótulos distintos** e **19 sinalizantes**.
O WLASL fica fora de toda soma — é ASL, léxico diferente, e entra no projeto por
configuração de mão e primitivas de movimento, não por palavra
([`protocolo-treinamento.md`](protocolo-treinamento.md) §1).

---

## 3. A distribuição — a resposta curta

Quantas palavras de Libras temos, por número de pessoas que as sinalizaram:

| Pessoas por palavra | Palavras | Acumulado |
|---:|---:|---|
| 1 | 4.996 | 75,4% do vocabulário |
| 2 | 279 | |
| 3 | 661 | |
| 4 | 552 | |
| 5 | 121 | |
| 8 a 13 | 20 | os 20 sinais do MINDS |
| **≥4 pessoas** | **693** | 10,5% |
| **≥8 pessoas** | **20** | 0,3% |

**Três em cada quatro palavras que temos foram sinalizadas por uma pessoa só.** E as
únicas 20 palavras com 8 ou mais pessoas são exatamente as do MINDS — as mesmas que já
usamos para a avaliação LOSO. Nenhuma palavra nova alcançou esse patamar.

Por corpus isolado:

| Corpus | 1 pessoa | 2 | 3 | 8 |
|---|---:|---:|---:|---:|
| MINDS | — | — | — | 20 |
| V-LIBRASIL auditada | 4 | 26 | 1.323 | — |
| MALTA | 5.570 | 388 | — | — |

O MALTA **nunca** chega a 3 pessoas para uma mesma palavra. As 388 duplas são
quase todas `T002`+outro: `T002,TUFV` (255), `T002,T048` (109), o resto abaixo de 15.

---

## 4. Os 20 do MINDS — o núcleo mensurável

São as únicas palavras onde `leave-one-signer-out` tem significado estatístico.

| Palavra | MINDS | V-res | V-pré | MALTA | TOTAL | pessoas MALTA |
|---|---:|---:|---:|---:|---:|---|
| acontecer | 8 | 3 | 0 | 1 | 12 | TUFV |
| aluno | 8 | 0 | 0 | 0 | **8** | — |
| amarelo | 8 | 3 | 0 | 1 | 12 | T002 |
| america | 8 | 0 | 0 | 2 | 10 | T002, T048 |
| aproveitar | 8 | 0 | 0 | 0 | **8** | — |
| bala | 8 | 0 | 0 | 1 | 9 | T050 |
| banco | 8 | 0 | 0 | 0 | **8** | — |
| banheiro | 8 | 3 | 0 | 2 | **13** | T002, T048 |
| barulho | 8 | 3 | 0 | 1 | 12 | T002 |
| cinco | 8 | 0 | 0 | 2 | 10 | T002, T048 |
| conhecer | 8 | 0 | 0 | 2 | 10 | T002, T048 |
| espelho | 8 | 3 | 0 | 1 | 12 | T002 |
| esquina | 8 | 0 | 0 | 1 | 9 | T002 |
| filho | 8 | 3 | 0 | 2 | **13** | T002, TUFV |
| maca (maçã) | 8 | 3 | 0 | 1 | 12 | T002 |
| medo | 8 | 3 | 0 | 1 | 12 | T002 |
| ruim | 8 | 3 | 0 | 0 | 11 | — |
| sapo | 8 | 3 | 0 | 1 | 12 | T002 |
| vacina | 8 | 0 | 0 | 1 | 9 | T002 |
| vontade | 8 | 0 | 0 | 1 | 9 | T002 |

A coluna `V-pré` é zero nas 20 linhas por construção: nenhuma dessas palavras entra no
corpus de pré-treino. Para as 10 que têm `V-res`, os 3 clipes estão **reservados**.

⚠️ A tabela de camadas em [`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md)
diz "8-12 sinalizadores cada" para o MINDS. **Medido: são 8 no MINDS**, sempre. Os
12-13 aparecem só depois de somar V-LIBRASIL e MALTA — corpora diferentes, domínios
visuais diferentes, e sujeitos à ressalva 1.

---

## 5. Fora do MINDS: 673 palavras com 4 ou 5 pessoas

Essas são as candidatas a ampliar o vocabulário sem coleta própria. A composição é
sempre a mesma família:

| Pessoas | Palavras | Composição dominante |
|---:|---:|---|
| 5 | 121 | `V01,V02,V03` + 2 do MALTA (86× com `T002,TUFV`; 28× com `T002,T048`) |
| 4 | 552 | `V01,V02,V03` + 1 do MALTA (494× `T002`; 40× `TUFV`; 16× `T048`) |
| 3 | 661 | 650× o trio `V01,V02,V03` sozinho |
| 2 | 279 | 167× `T002,TUFV`; 77× `T002,T048` |

### As 121 palavras com 5 pessoas

```
abacaxi acrescentar acucar adiar adulto agarrar ainda ajoelhar amanha amigo ano aprender
armario azul banana batata bebe bolsa bombeiro bota brinco cabeca cabelo calca calculadora
cansado capacete carne casa cebola cerveja chave chicote chocolate chuva chuveiro coco comer
comparar computador consertar construir continuar decidir demitir dinossauro disputar doce
dolar dormir elefante elevador endereco escolher escurecer esquecer estrela evitar
experimentar familia feio flauta futebol futuro garagem garganta gostar gritar guerra
historia homem internet jovem laranja manha melancia memorizar moeda mulher namorar nove
ontem ouvir pais palestra parecer pascoa passar patins penis pente perfume politica ponte
porcentagem presidente primo psiquiatra pulseira reuniao revista sangue secretario seguro
sobrinho sol solteiro tatuagem tomada tomate torrada travesseiro trem vaca vassoura veado
vela vento verao violino ziper

```

### As 552 palavras com 4 pessoas

```
abanar abelha abencoar abobora aborto abraco aceitar acenar acima acompanhar aconselhar
afogar agora agressivo ajudar alarme alce alcool alfinete almofada alto amargo ambos
amigavel analisar angustiado animal anjo antes anunciar apagar aparelho-auditivo apito aqui
ar aranha arquivo aspirar assim assinatura assistir associacao atonito aumentar ausente
autoridade balao baleia banho barba barco bateria bebado bebida beijo beisebol bermuda
biblioteca bicicleta bigode bilhao biologia biquini bisbilhotar biscoito bloquear bode boi
bola bonito borboleta borracha brilhar brilho bufalo cafe caixa cama camera caminhada
campainha campeao cancelar canguru capital capitulo careca carpinteiro carro casaco
casamento catolico cavalo cego chance chao chave-de-fenda chefe chegar cheio choro cidade
ciencia cigarro cilios cintura circulo cirurgia classe cobertor cocegas coelho cola
colaborar colegio com-licenca comissao compaixao comprar compreender comunicar concordar
constituicao contato contra cor coracao coroa corpo creme-dental cruz cueca cuidado curioso
curva cuspir danca dar decolar dedo democracia depender derramar desafio descobrir
desenvolver desgosto desistir destruir deus devolver dia dialogo dicionario dificil dinheiro
diploma direto dirigir discordar disponivel distribuir divorcio documento dor-de-cabeca
educado elastico eletrico em-cima emocao encontrar encorajar engracado ensaiar ensinar
enterrar equipe escada escola escova-de-dente escravo escrever esfregar espaguete esperar
espinha espirrar esquerda estado estrangeiro estranho estupido exato excluir executar
expandir explicar explodir faca facada faculdade falhar fantasma fechar federal ferver festa
filha filmadora fim fino fisica flor fofinho fogo foguete fome forca fraco freio frio fruta
funeral galo garfo gato gay geracao giz gorila governo gramatica gravata grosseiro
guarda-chuva guitarra helicoptero honesto hora hospital humilde ideia idoso ignorar igreja
imaginar impossivel impressionar independente individual inferno inocente inseto insulto
interesse interpretar interruptor irma isolado jacare janela jardim-de-infancia jogar
jogar-futebol jogo judeu juiz labia lado lancar lapis lavar leao legenda leite leitura lento
lesbica leste levar licenca ligar lista livre livro lobo local lotado louco lua luz macaco
macio madeira mae maioria manteiga maquina marrom martelo mascara mastigar matar matematica
maximo medicina meia-noite meio-dia melhor menina menstruar metro microfone mil milhao milho
minuto misturar mochila molhado montanha morango motivo motocicleta movimento mudar mundo
museu nada nadar nao-poder nariz nascer necessario nervoso nome nomear nu numero obeso
obrigacao obrigado oculos ocupado oficina oi onde opcao opiniao orar organizacao orgulhoso
orientar outro ovelha pai paixao pao papel parabens parar parede passado passeio passo pato
pedir pele percurso perfeito perigo permitir perspectiva pertencer pesado pessego pessimo
pessoa piano piedade pintura pipoca pirata plastico pobre poderoso polegar policial
por-do-sol por-favor porco portao praia prata pratica preco predio preencher pressa pressao
prima princesa principal principe prioridade problema profissao profundo programa progresso
projeto pronto prosseguir protestante psicologia pulsar qualquer quantos quarta-feira queixo
quente quimica quinta-feira rainha raposa raspar rato razao recuperar recusar rei rejeitar
relampago relatorio religiao reprovado respeito respirar responsabilidade resposta
restaurante resultado resumir retorno revisao rigoroso roda rodovia rosa roxo sabado sabao
saber sacudir saia salada salsicha sanduiche sapato se secretaria segredo seguir selo
semaforo semestre sempre sentar sentir separado serio servico sexo sexta-feira sim simples
sino sobrancelha sobrinha sociedade sofrer soletrar sopa sorteio sortudo sorvete sozinho
suave substituir subtrair sucesso sujo sul sumir suor superior surgir surpresa sutia talvez
tambem tarde tecnico telescopio tempo teoria ter terminar termometro testa tigre timido
titulo todo-dia toque torax torneio total tradicao transbordar trazer tribunal trofeu tromba
tubo universidade universo urgente urso vagina vapor veia vender verde vergonha verificar
verme vermelho vestido vinganca vinho vinte virgem visita viver vocabulario volei vomitar
voto xampu xingar zero

```

---

## 6. O que "4 pessoas" não significa

A tabela acima é fácil de ler errado. Uma palavra com 4 pessoas **não** é uma palavra
pronta para LOSO. Três motivos, medidos:

**Não são 4 pessoas independentes.** Em 494 dos 552 casos são `V01,V02,V03,T002`. Ou
seja: em quase todo o vocabulário ampliado, a "quarta pessoa" é **a mesma pessoa** —
`T002`, o apresentador único que responde por 5.719 dos 6.353 clipes do MALTA (90%). Um
modelo que aprende `T002` não aprende diversidade de sinalizante; aprende `T002`.

**Não são 4 clipes por pessoa.** São 1 clipe por pessoa em quase todos os casos: o
V-LIBRASIL tem 1 execução por articulador e o MALTA é dicionário (1 vídeo por verbete).
Contra os 5 clipes por pessoa do MINDS (8 × 5 = 40 por sinal), é outro regime de dado.

**Não são 4 domínios visuais comparáveis.** V-LIBRASIL e MALTA são estúdios diferentes,
com enquadramentos diferentes. Deixar `T002` de fora numa avaliação mediria "generaliza
para outro estúdio", não "generaliza para outra pessoa" — as duas variáveis mudam juntas
e não dá para separar o efeito.

Quantas palavras a mais sobreviveriam a LOSO honesto? **Zero.** Nenhuma palavra fora do
MINDS tem pessoas suficientes e independentes o bastante para uma avaliação
signer-independent com significado. O `T002` sozinho em 5.714 palavras é a razão.

Isso é consistente com a decisão já registrada: **MALTA e V-LIBRASIL vão para o
pré-treino, não para a avaliação** ([`protocolo-treinamento.md`](protocolo-treinamento.md)
§1b). Este documento mede o porquê palavra a palavra.

### Sobreposição entre V-LIBRASIL e MALTA

686 rótulos batem exatamente entre os dois; 828 batem por forma base. É de onde vêm as
673 palavras com ≥4 pessoas. Os dois corpora **não** somam vocabulário tanto quanto
somam clipes: 1.353 + 5.958 = 7.311 rótulos brutos contra 6.625 distintos.

---

## 7. Cruzamento com o vocabulário proposto para o MVP

Conferência direta contra [`vocabulario-mvp-proposta.md`](vocabulario-mvp-proposta.md).
**Esta seção usa forma base** (dígito final removido), então os totais são otimistas: se
`esperar`, `esperar1` e `esperar2` forem sinais diferentes, a soma mistura sinais
diferentes. A coluna "variantes" mostra quando isso está acontecendo.

### Camada 2 — ampliação por V-LIBRASIL

| Termo | MINDS | V-res | V-pré | MALTA | TOTAL | variantes no MALTA |
|---|---:|---:|---:|---:|---:|---|
| obrigado | 0 | 0 | 3 | 2 | 5 | obrigado1, obrigado2 |
| esperar | 0 | 0 | 3 | 2 | 5 | esperar1, esperar2 |
| ajudar | 0 | 0 | 3 | 2 | 5 | ajudar1, ajudar2 |
| manha | 0 | 0 | 3 | 2 | 5 | — |
| por-favor | 0 | 0 | 3 | 1 | 4 | — |
| sim | 0 | 0 | 3 | 1 | 4 | — |
| nao | 0 | 0 | 3 | 1 | 4 | nao1, nao2 |
| documento | 0 | 0 | 3 | 1 | 4 | — |
| nome | 0 | 0 | 3 | 1 | 4 | — |
| oi | 0 | 0 | 3 | 1 | 4 | — |
| noite | 0 | 0 | 3 | 1 | 4 | noite1, noite2 |
| numero | 0 | 0 | 3 | 1 | 4 | — |
| doloroso | 0 | 0 | 3 | 0 | 3 | — |
| voltar | 0 | 0 | 0 | 1 | 1 | voltar1, voltar2, voltar3 |
| dor | 0 | 0 | 0 | 1 | 1 | — |
| ola | 0 | 0 | 0 | 1 | 1 | — |
| bom-dia | 0 | 0 | 0 | 1 | 1 | — |
| **ajuda** | — | — | — | — | **0** | **ausente** (existe `ajudar`) |

A Camada 2 se confirma, com duas correções: `ajuda` não existe com esse rótulo (use
`ajudar`), e `voltar`, `dor`, `ola`, `bom-dia` **não estão na V-LIBRASIL** — só no MALTA,
com 1 pessoa. O documento os lista como se viessem da V-LIBRASIL.

### Camada 3 — declarada como "só coleta própria"

| Termo | MINDS | V-pré | MALTA | TOTAL | variantes |
|---|---:|---:|---:|---:|---|
| atendimento | 0 | 0 | 0 | **0** | ausente |
| consulta | 0 | 0 | 0 | **0** | ausente |
| protocolo | 0 | 0 | 0 | **0** | ausente |
| marcar | 0 | 0 | 1 | 1 | marcar1, marcar2 |
| agendar | 0 | 0 | 1 | 1 | — |
| senha | 0 | 0 | 1 | 1 | — |
| idade | 0 | 0 | 1 | 1 | idade1, idade2, idade3 |
| anos | 0 | 0 | 1 | 1 | — |
| tres | 0 | 0 | 1 | 1 | tres1, tres2 |
| dois | 0 | 0 | 2 | **2** | dois1, dois2 |
| quatro | 0 | 0 | 2 | **2** | — |
| ano | 0 | 3 | 2 | **5** | ano2 |
| cinco | 8 | 0 | 2 | 10 | — |
| esquina | 8 | 0 | 1 | 9 | — |

**A Camada 3 muda com o MALTA, mas pouco.** A afirmação "não existem em nenhuma base
pública com mais de 1 pessoa" agora é falsa para `dois` e `quatro` (2 pessoas cada) e
para `ano` (5, se `ano` cobrir "idade"). `atendimento`, `consulta` e `protocolo`
continuam **ausentes de tudo** — nenhum rótulo, nem variante.

Na prática a conclusão da Camada 3 sobrevive: 1 ou 2 pessoas de dicionário não sustentam
nem fine-tuning decente nem qualquer alegação de generalização. **A coleta própria
continua sendo o único caminho para o vocabulário institucional.**

---

## 8. WLASL100 — o contraste (e é ASL)

É a única base que temos com diversidade real de sinalizante por palavra: **64 pessoas
para 100 palavras**, de 4 a 12 pessoas por palavra, mediana 8.

| Pessoas | Palavras |
|---:|---|
| 12 | thin |
| 11 | basketball, tall, what, who |
| 10 | apple, bed, bird, corn, dog, drink, family, mother, no, short, yes |
| 9 | accident, before, candy, change, city, fish, full, go, hat, help, hot, later, man, play, school, study, thanksgiving, walk, work |
| 8 | black, blue, bowling, brown, cool, deaf, decide, enjoy, fine, finish, give, hearing, letter, medicine, orange, pink, pizza, pull, purple, right, son, time, want, white, woman, year |
| 7 | africa, all, but, can, chair, color, computer, cousin, cow, dance, dark, doctor, eat, graduate, how, jacket, last, like, many, meet, now, paper, secretary, shirt, thursday, wrong |
| 6 | book, cheat, cook, forget, kiss, language, need, same |
| 5 | birthday, clothes, table, tell |
| 4 | paint |

**Nada disso é vocabulário de produto** — são sinais americanos. O valor é exatamente o
que o protocolo já registra: 64 sinalizantes em condições variadas (vídeo de YouTube,
ângulos e iluminação diferentes) é a única variação de enquadramento que temos. A
comparação com Libras é desconfortável e útil: em ASL temos 100 palavras com mediana de 8
pessoas; em Libras temos 20.

---

## 9. O que este mapa decide

1. **O vocabulário mensurável continua sendo 20 palavras.** Baixar MALTA e V-LIBRASIL
   ampliou o vocabulário de 20 para 6.629 rótulos, mas não moveu o número de palavras com
   pessoas suficientes para avaliação: continua 20, as do MINDS.
2. **A Camada 2 tem base real** — ~13 termos de atendimento com 3 a 5 pessoas. Continua
   valendo a regra do documento de vocabulário: apresentar como "vocabulário estendido" e
   nunca junto da acurácia signer-independent.
3. **`atendimento`, `consulta` e `protocolo` não existem em lugar nenhum.** Nem rótulo,
   nem variante, em 6.629 palavras de Libras.
4. **A coleta própria não ficou menos necessária depois do MALTA.** Ela continua sendo o
   único caminho para (a) o vocabulário institucional, (b) pessoas novas por palavra,
   (c) ângulo de câmera de óculos.
5. **Se o time gravar, a prioridade é pessoas, não palavras.** Temos 6.629 palavras e 19
   pessoas. O gargalo medido não é vocabulário.

---

## 10. Como refazer esta contagem

```bash
cd computer-vision-model/PoC/data
# pessoas distintas por palavra, num corpus
ls landmarks-malta/*.npy | xargs -n1 basename \
  | sed 's/^pessoa//; s/_rep[0-9]*\.npy$//' | tr '_' ' ' | sed 's/sinal-//' \
  | awk '{print $2, $1}' | sort -u | cut -d' ' -f1 | uniq -c | sort -rn | head
```

Os totais somados entre corpora deste documento saem de agrupar os quatro diretórios de
Libras por rótulo e unir os conjuntos de pessoa. O WLASL fica fora da união de propósito.

Se a contagem for repetida depois de novos downloads, **reconfira a ressalva 1**: quanto
mais fontes, mais chance de dois rótulos iguais serem sinais diferentes.

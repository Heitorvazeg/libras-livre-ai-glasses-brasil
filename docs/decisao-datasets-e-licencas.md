# Decisão: quais datasets usamos, sob qual enquadramento legal

**Decidido em:** 2026-09-11 · **Decide:** Walisson · **Escopo:** hackathon (16/09/2026)

Este documento existe porque a pergunta "podemos usar esse dataset?" foi respondida três
vezes de formas diferentes ao longo do projeto, e a resposta final depende de um
enquadramento que não estava escrito em lugar nenhum.

---

## 1. A decisão

**Para o hackathon, usamos as bases públicas sob enquadramento de pesquisa.** Isso inclui
MINDS-Libras, V-LIBRASIL, WLASL e as fontes do MALTA-LIBRAS que não proíbem uso
expressamente.

**Para produto, nada disso está resolvido** — e o problema não é hipotético nem futuro:
a V-LIBRASIL, que já usamos, é **CC BY-NC-ND** (não-comercial, sem derivações).

O raciocínio do time: se o Libras Livre virar produto, será sob amparo institucional
(Meta, CEIA-UFG), e nesse cenário obter licenças ou produzir base própria é tratável. O
que não seria tratável é travar o hackathon numa negociação de semanas.

---

## 2. Por que "licença não encontrada" não significa "liberado"

Esta seção existe porque a intuição contrária é razoável e estava errada.

> **Lei 9.610/98, Art. 18:** "A proteção aos direitos de que trata esta Lei **independe de
> registro**."

No Brasil a proteção autoral nasce com a criação. Não exige registro, aviso ou declaração
de licença. Uma obra sem nenhuma menção legal está tão protegida quanto uma com aviso
explícito. E o Art. 29, I exige **"prévia e expressa autorização"** para reproduzir —
baixar e copiar para um conjunto de treino é reprodução.

Logo, "não achei o termo de uso" não é permissão: é ausência de autorização, e
autorização precisa ser expressa.

**Não existe exceção de mineração de dados no Brasil.** O Art. 46 traz a lista de
limitações, lida pela doutrina majoritária como taxativa. A União Europeia tem exceção de
TDM (Diretiva 2019/790, arts. 3 e 4) e o Japão tem o art. 30-4; nós não temos. "É para
pesquisa" é atenuante prático, não base jurídica.

O [PL 2338/2023](https://www25.senado.leg.br/web/atividade/materias/-/materia/157233)
criaria uma exceção, mas foi aprovado no Senado em dez/2024 e **ainda está na Câmara**.
Mesmo o texto proposto limita a exceção a instituições científicas e educacionais, exige
acesso legítimo, prevê remuneração e permite o autor proibir o uso.

### O argumento mais forte não é o autoral — é a LGPD

> **LGPD, Art. 5º, II:** dado **biométrico** vinculado a pessoa natural é **dado pessoal
> sensível**.

Vídeo de alguém sinalizando é rosto e corpo. Uma foto não é automaticamente biométrico,
mas torna-se quando submetida a tratamento que extrai características da pessoa — que é o
que o MediaPipe faz.

E o que trava: para dado sensível, **a base legal do legítimo interesse não existe** (ela
vale para dados comuns, Art. 7º, IX). Dado sensível exige **consentimento específico,
informado e em destaque, para finalidade determinada** (Art. 11). "Específico" é a palavra
que impede reutilização: consentimento para corpus linguístico universitário não cobre
modelo embarcado em óculos num balcão de atendimento.

### O que o nosso pipeline já mitiga

Sem ter sido desenhado para isso, o pipeline reduz a exposição:

- **O vídeo bruto é apagado** após a extração (`extract.py --descartar-video`); só os
  landmarks ficam.
- **Não usamos face mesh.** Dos 57 pontos, apenas 7 são faciais grosseiros (nariz, olhos,
  orelhas, boca). O MediaPipe oferece 468 pontos de rosto e não os usamos. Sete âncoras
  normalizadas por distância entre ombros não são biometria de reconhecimento facial.
- **Nada derivado é publicado** — landmarks, sidecars, checkpoints e pacotes ficam
  privados.

O que **não** se resolve: os arquivos são `pessoaM01_...`, ou seja, pseudonimizados. Sob a
LGPD (Art. 13, §4º) dado pseudonimizado continua sendo dado pessoal.

⚠️ **Isto não é parecer jurídico.** É levantamento dos dispositivos aplicáveis. Decisão de
produto deve passar pelo apoio jurídico do CEIA-UFG.

---

## 3. O MALTA-LIBRAS: o que foi baixado e por quê

O MALTA não é uma base — é um **agregador de 8 dicionários** de Libras, cada um com seu
apresentador. Era a única fonte pública com potencial de trazer pessoas novas por sinal,
que é a métrica que falta ao projeto.

### Fontes incluídas e excluídas

| Fonte | Vídeos | Status | Motivo |
|---|---|---|---|
| Acessibilidade Brasil | 5.735 | ✅ baixado | — |
| UFV | 634 | ✅ baixado | — |
| UFSC SignBank | 3.082 | ❌ falhou | cadeia de certificado incompleta |
| USP | 432 | ❌ falhou | certificado expirado desde 29/09/2025 |
| **Spread the Sign** | 21.913 | ❌ **excluída por decisão** | proibição expressa |
| V-LIBRASIL | 4.089 | — | já em disco |

**Spread the Sign é o único caso em que a licença não é silêncio, e sim proibição**:
"only for personal use", redistribuição vedada. O enquadramento de pesquisa vale bem para
fontes universitárias que não publicaram termo; vale menos contra um "não" escrito.

### As duas falhas técnicas, para quem for repetir

**UFSC** (`videos.nals.cce.ufsc.br`): o servidor envia só o certificado folha, sem o
intermediário. O certificado é válido (Let's Encrypt YR1, 05/09 a 04/12/2026), mas a raiz
`ISRG Root YR` **não está no armazém do sistema nem no certifi** — ambos atualizados
(pacote de junho/2026, 121 raízes). Completar a cadeia exigiria adicionar uma âncora de
confiança que não conseguimos validar; não fizemos.

**USP** (`midia.atp.usp.br`): certificado expirado em 29/09/2025, e emitido para
`*.cursosextensao.usp.br` — o nome nem corresponde ao host. Não há conserto do nosso lado.

Nos dois casos a alternativa seria `verify=False`, que desliga a verificação justamente
onde ela falha. Não foi feito.

### O que o MALTA de fato rendeu — e o que não rendeu

**6.353 vídeos e 6.353 landmarks**, com proveniência registrada.

Mas a promessa de "5-7 pessoas novas por sinal" **não se confirmou**, e a estimativa foi
revista três vezes antes de ser medida nos arquivos:

| | Estimado | Real em disco |
|---|---|---|
| Pessoas | 19 | **8** |
| Distribuição | — | `T002` tem 5.719 dos 6.353 clipes (90%) |
| Rótulos | 7.716 | 5.958 |
| Ganho nos 20 sinais do MVP | 8 → 10-12 pessoas | **8 → 8, 9 ou 10** |

A queda da UFSC levou 10 das 19 pessoas. O que sobrou é essencialmente **um dicionário de
um apresentador só**, com vocabulário enorme e ~1 pessoa por rótulo.

**Consequência para o uso:** o MALTA serve para **pré-treino** (6.353 clipes de movimento
real de Libras, domínio visual novo), e **não** para avaliação signer-independent, porque
1 pessoa por rótulo não sustenta leave-one-signer-out.

### A promessa que eu errei três vezes

Registrado porque o modo de falha é instrutivo: o número "5-7 pessoas por sinal" veio de
uma tabela que contava **todas** as fontes, incluindo as que decidimos não baixar e as que
não têm CSV de links. Foi repetido depois do corte do Spread the Sign sem ser recalculado,
e depois da falha da UFSC sem ser recalculado de novo. Só virou número confiável quando
medido nos arquivos, depois do download.

---

## 4. O que continua sem resolução

| Item | Estado |
|---|---|
| Licença da V-LIBRASIL para produto | **CC BY-NC-ND** — bloqueia uso comercial |
| UFSC SignBank | cadastro de pesquisador em `corpuslibras.ufsc.br/entrar` resolveria licença **e** o problema de certificado |
| Consentimento LGPD para finalidade de produto | não existe em nenhuma base pública |
| Coleta própria | não iniciada — é o que resolve licença, LGPD e ângulo de câmera de uma vez |

---

## Fontes

- [Lei nº 9.610/1998](https://www.direitohd.com/lei9610) — Art. 18 (proteção independe de registro), Art. 29 I, Art. 46
- [Uso de obras protegidas no treinamento de IA generativa — GEDAI/UFPR](https://gedai.ufpr.br/uso-de-obras-protegidas-no-treinamento-de-sistemas-de-ia-generativa/)
- [Mapeamento global de exceções para mineração de dados — Reglab](https://reglab.com.br/mapeamento-global-de-leis-de-direitos-autorais-e-excecoes-para-mineracao-de-dados/)
- [PL 2338/2023 — Senado Federal](https://www25.senado.leg.br/web/atividade/materias/-/materia/157233)
- [Dados biométricos e LGPD — SFIEC](https://lgpd.sfiec.org.br/artigos/151363/artigo-dados-biometricos-e-lgpd)

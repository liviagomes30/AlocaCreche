# Cola do dia da apresentação: dados e cenários

As três seguem **esta cola**, cada uma na sua máquina e no seu banco, com **exatamente os mesmos dados**. Assim o
que a Larissa cadastra na Stack A é o que a Letícia e a Lívia encontram (ou recriam em segundos) nas suas partes.

Os resultados abaixo foram **conferidos executando o sistema** (posições, scores, convocação automática,
distâncias). Os dados também estão no código (`alocacreche-gateway/.../static/massa.js`), que a demonstração
guiada da Stack C e o botão "Carregar dados-base" usam.

Para a fala de cada uma, veja o roteiro da apresentação (`roteiro-apresentacao.md` e `falas-livia.md`). Esta cola é só **o que digitar,
clicar e o que deve aparecer**.

**Dois níveis de uso:**
- **Versão curta (parte 0B):** é o que **cabe na demonstração ao vivo**. O roteiro dá só **4 min 30 s para as três**
  (de 37:30 a 42:00), então cada uma faz **um destaque de cerca de 1 min 30 s**, com os dados já carregados.
- **Versão completa (seções 2, 3 e 4):** para **ensaiar**, para o **plano B** e para responder **perguntas**
  (por exemplo, "mostra de novo como a fila ordena" ou "e se o RabbitMQ cair?").

---

## 0. Regras do dia (valem para as três)

1. **Banco limpo antes de começar.** Cada uma, na sua máquina, no Git Bash, na pasta do projeto:
   ```bash
   git checkout main && git pull
   scripts/demo-subir.sh
   ```
   (sem `--manter`: apaga os dados e gera senha nova). O script imprime a URL e a senha. Leva uns 80 s.
2. **Entre como ADMIN, município 1** (`admin@demo.local`). O ADMIN faz tudo; GESTOR e ATENDENTE só são usados nos
   cenários de permissão.
3. **Job de manutenção desligado** (é o padrão do script). Se ligar sem querer, ele aloca vagas sozinho e
   estraga os cenários. Corrigir sem apagar dados: `scripts/demo-subir.sh --reiniciar-core`.
4. **Confira o ambiente:** aba **7. Roteiro** → "Antes de apresentar" deve dizer **pronto para apresentar**.
5. **Ctrl+F5** na página antes de começar (garante a versão atual da interface).
6. **CPFs são fixos** (tabela 1.2). Por isso o banco precisa estar limpo; repetir o cadastro dá 409 de CPF
   duplicado (e isso é um cenário, veja a seção 2, A6).
7. **Ensaio rápido:** na aba Roteiro, o botão **"Carregar dados-base da cola"** cadastra as 5 creches, as 7
   turmas e as 8 famílias na fila da Creche Sol, em uns 20 s. Use para ensaiar ou para montar o ponto de partida
   da sua parte (seções 3 e 4).
8. **Se algo fugir do esperado:** `scripts/demo-subir.sh` (zera tudo) e recomece pela sua parte.

### Ponto de partida de cada uma (versão completa)

| Quem | Parte | Estado de partida (banco limpo +) |
|---|---|---|
| Larissa | Stack A | nada: ela cadastra a creche, a turma e as famílias ao vivo (seção 2) |
| Letícia | Stack B | clica em **Carregar dados-base da cola**: fica igual ao estado em que a Larissa termina (seção 3) |
| Lívia | Stack C | clica em **Carregar dados-base da cola** (ou usa a demonstração guiada, que cadastra tudo sozinha) (seção 4) |

---

## 0B. Versão curta: a demonstração ao vivo (4 min 30 s para as três)

**Antes de a apresentação começar**, cada uma, na sua máquina: `scripts/demo-subir.sh` (banco limpo), login ADMIN,
aba **7. Roteiro** → **Carregar dados-base da cola**, e confira "pronto para apresentar". **Não mexa mais em nada**
até a sua vez: o estado carregado é o mesmo nas três máquinas (5 creches, 7 turmas, 8 famílias na fila da Creche Sol,
3 vagas, ainda **sem nenhuma matrícula**).

Cada uma compartilha a **sua** tela na sua vez. Quem não está falando deixa a máquina como está.

| Quem | Tempo | Aba | O que fazer (nesta ordem) | O que deve aparecer |
|---|---|---|---|---|
| **Larissa** (Stack A) | 1 min 30 s | 2. Stack A | 1. Card **Posição na fila** → **Atualizar todas as posições**. 2. Botão **Furar a fila** | fila da Creche Sol: **Ana 1, Bruno 2, Carla 3, Gisele 4, Diego 5, Hugo 6, Elisa 7, Fábio 8** (o Fábio chegou primeiro e é o 8º). No cenário: a inscrição com score **0,70** fica em 1º e a de renda alta (0,00) cai para 2º |
| **Letícia** (Stack B) | 1 min 30 s | 3. Stack B | 1. **Alocar vaga** (turma Creche Sol, MATERNAL). 2. **Confirmar matrícula**. 3. **Alocar vaga** de novo. 4. **Desistir da matrícula** (a segunda) | 1ª matrícula **PENDENTE da Ana**, **0,24 km**, turma com **2 vagas**; confirma. 2ª é do **Bruno** (**1,02 km**, **1 vaga**). Ao desistir: **CANCELADA**, a vaga volta e **a Carla é convocada sozinha** (nova matrícula PENDENTE, **2,18 km**) |
| **Lívia** (Stack C) | 1 min 30 s | 6. Eventos e Outbox | 1. **Executar cenário guiado** (cerca de 25 s no ritmo normal). 2. Bloco **Evento antigo ou duplicado** → turma **Creche Sol** → **Reenviar evento antigo**. 3. Aba **1. Visão geral**, mostrar o gráfico | cenário: **6 passos concluídos** (envio pausado, defasagem, retomada, relatório converge). Idempotência: **evento antigo ignorado** (vagas e sequência iguais). Gráfico: as linhas relatório × banco se separam e voltam a se encontrar |

Observações:
- A Lívia depende do estado **depois** da parte da Letícia **só na idempotência**: a turma precisa ter pelo menos 1 vaga
  alocada. Na máquina da Lívia, antes de começar, faça **uma** alocação na Creche Sol (aba Stack B → **Alocar vaga**)
  para o evento antigo diferir do atual. Se esquecer, o botão fica desativado e a tela explica por quê.
- Esta versão **substitui** o passo "pare o RabbitMQ com `docker compose`" do roteiro antigo: o botão pausa o publicador
  do core e mostra o mesmo efeito, sem terminal.
- A **demonstração guiada da Stack C** completa (cerca de 2 minutos) não cabe na janela. Deixe-a para as perguntas
  ("quer ver o fluxo inteiro?") ou como plano B.

---

## 1. Os dados da cola

### 1.1 Creches e turmas (5 creches, 7 turmas)

Em banco limpo, os ids saem na ordem abaixo. Na tela, escolha sempre pelo **nome**.

| # | Creche | Rua | Latitude | Longitude | Capacidade | Turmas (faixa, vagas) |
|---|---|---|---|---|---|---|
| 1 | Creche Sol (Centro) | Rua Direita | -23.550 | -46.633 | 50 | MATERNAL 3 · BERCARIO 2 |
| 2 | Creche Girassol (Bela Vista) | Rua Treze de Maio | -23.561 | -46.656 | 40 | MATERNAL 2 |
| 3 | Creche Aurora (Pinheiros) | Rua dos Pinheiros | -23.567 | -46.692 | 30 | MATERNAL 1 · PRE_ESCOLA 4 |
| 4 | Creche Esperança (Penha) | Rua da Penha | -23.526 | -46.543 | 35 | MATERNAL 2 |
| 5 | Creche Horizonte (Santo Amaro) | Avenida Santo Amaro | -23.655 | -46.709 | 45 | BERCARIO 3 |

O nome cadastrado é só **"Creche Sol"**, **"Creche Girassol"** etc. (o bairro entre parênteses é só referência).
A **turma da fila** é **Creche Sol, MATERNAL, 3 vagas**.

### 1.2 As 8 famílias (cadastro completo)

Telefone `11999990001` a `...08`; e-mail `nome.sobrenome@example.com`. **Ordem de chegada** é a ordem em que se
inscreve (da menor para a maior prioridade, de propósito).

| Chegada | Responsável | CPF | Telefone | E-mail | Rua, nº | Lat. | Lon. | Criança | Nascimento | Renda (R$) | Vulnerável |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1ª | Fábio Prado | 100.000.000-06 | 11999990006 | fabio.prado@example.com | Avenida Santo Amaro, 1200 | -23.650 | -46.705 | Pedro Prado | 2024-02-11 | 4500 | Não |
| 2ª | Elisa Ramos | 100.000.000-05 | 11999990005 | elisa.ramos@example.com | Rua da Penha, 52 | -23.528 | -46.548 | Sofia Ramos | 2024-07-08 | 1800 | Não |
| 3ª | Hugo Alves | 100.000.000-08 | 11999990008 | hugo.alves@example.com | Rua Boa Vista, 15 | -23.545 | -46.620 | Miguel Alves | 2024-04-22 | 2500 | **Sim** |
| 4ª | Gisele Nunes | 100.000.000-07 | 11999990007 | gisele.nunes@example.com | Rua São Joaquim, 77 | -23.554 | -46.640 | Laura Nunes | 2023-12-05 | 350 | Não |
| 5ª | Diego Lima | 100.000.000-04 | 11999990004 | diego.lima@example.com | Rua dos Pinheiros, 410 | -23.566 | -46.683 | Heitor Lima | 2024-01-15 | 450 | Não |
| 6ª | Carla Costa | 100.000.000-03 | 11999990003 | carla.costa@example.com | Rua Treze de Maio, 330 | -23.563 | -46.649 | Alice Costa | 2023-11-20 | 1200 | **Sim** |
| 7ª | Bruno Souza | 100.000.000-02 | 11999990002 | bruno.souza@example.com | Rua da Glória, 25 | -23.559 | -46.635 | Davi Souza | 2024-05-02 | 800 | **Sim** |
| 8ª | Ana Silva | 100.000.000-01 | 11999990001 | ana.silva@example.com | Rua Direita, 10 | -23.552 | -46.634 | Lia Silva | 2024-03-10 | 350 | **Sim** |

### 1.3 Score e posições esperadas

Score = fator da renda × 0,4 + vulnerabilidade × 0,3 (a distância vale 0 na inscrição; irmão matriculado soma 0,1).
Fator da renda: até 500 = 1 · até 1000 = 0,75 · até 1500 = 0,5 · até 2000 = 0,25 · acima = 0.

| Família | Score | Posição ao chegar | **Posição final** (8 na fila) |
|---|---|---|---|
| Fábio Prado | 0,00 | 1 | **8** |
| Elisa Ramos | 0,10 | 1 | **7** |
| Hugo Alves | 0,30 | 1 | **6** |
| Gisele Nunes | 0,40 | 1 | **4** |
| Diego Lima | 0,40 | **2** (empata com a Gisele; fica atrás quem chegou depois) | **5** |
| Carla Costa | 0,50 | 1 | **3** |
| Bruno Souza | 0,60 | 1 | **2** |
| Ana Silva | 0,70 | 1 | **1** |

Distância de cada família até a **Creche Sol** (aparece na matrícula quando a vaga é alocada):
Ana **0,24 km** · Bruno **1,02 km** · Carla **2,18 km** · Gisele 0,84 km · Hugo 1,44 km · Diego 5,40 km · Elisa 9,00 km · Fábio 13,32 km.

---

## 2. Cola da Larissa: Stack A (inscrição e fila)

Aba **2. Stack A**. Partida: banco limpo.

### A0. Criar a creche e a turma (1 min)
Aba **3. Stack B**:
1. Card **Creche**: Nome `Creche Sol`, Capacidade `50`, Rua `Rua Direita`, Latitude `-23.55`, Longitude `-46.633` → **Criar creche**.
2. Card **Turma**: Creche `Creche Sol`, Faixa `MATERNAL`, Vagas `3` → **Criar turma**.

Esperado: toast de sucesso, turma na tabela e o evento `vaga.liberada` na linha do tempo.

### A1. Primeira família, passo a passo, pelos 3 endpoints (2 min)
Fábio Prado (1ª a chegar). Volte para a aba Stack A:
1. Card **1. Responsável**: preencha com os dados do Fábio (tabela 1.2: nome, CPF `100.000.000-06`, telefone, e-mail, rua `Avenida Santo Amaro`, número `1200`, latitude `-23.65`, longitude `-46.705`) → **Cadastrar responsável**. Esperado: **201**.
2. Card **2. Criança**: Responsável `Fábio Prado`, nome `Pedro Prado`, nascimento `2024-02-11` → **Cadastrar criança**. Esperado: **201**.
3. Card **3. Inscrição**: Modo `Criança já cadastrada`, criança `Pedro Prado`, turma `Creche Sol, MATERNAL, 3 vagas`, renda `4500`, vulnerabilidade `Não` → **Inscrever**.
   Esperado: **201**, **posição 1**, **score 0,00**.

### A2. As outras 7 famílias, em modo inline (3 min)
Para cada família, na **ordem de chegada** da tabela 1.2 (Elisa, Hugo, Gisele, Diego, Carla, Bruno, Ana): preencha os
cards **1** e **2** com os dados dela, no card **3** escolha Modo `Cadastro inline`, turma `Creche Sol`, renda e
vulnerabilidade, e clique **Inscrever** (um clique só, sem cadastrar antes).

Esperado em cada inscrição (posição **no momento da chegada**):

| Inscrição | Posição na resposta | Score |
|---|---|---|
| Elisa Ramos | 1 | 0,10 |
| Hugo Alves | 1 | 0,30 |
| Gisele Nunes | 1 | 0,40 |
| Diego Lima | **2** (empate com a Gisele) | 0,40 |
| Carla Costa | 1 | 0,50 |
| Bruno Souza | 1 | 0,60 |
| Ana Silva | 1 | 0,70 |

> **O que dizer:** "A cada inscrição a fila inteira é reordenada por score. Chegou por último e tem mais
> prioridade? Passa na frente. Empate: quem chegou antes fica na frente."

### A3. Conferir a fila (1 min)
Card **Posição na fila**: **Atualizar todas as posições**. Esperado (tabela 1.3, coluna final):
Ana 1, Bruno 2, Carla 3, Gisele 4, Diego 5, Hugo 6, Elisa 7, Fábio 8. A posição vem do Redis, com fallback para o banco.

### A4. Cenários prontos (cada um cria a sua própria creche e turma)
| Botão | Esperado |
|---|---|
| **Furar a fila** (renda alta, depois baixa + vulnerável) | a 2ª inscrição (score **0,70**) fica na **posição 1** e a renda alta (0,00) cai para a **2** |
| **Irmão matriculado** | irmão A score **0,20**, irmão B **0,30** (diferença **0,10** = peso do irmão matriculado) |
| **Inscrições simultâneas (8 em paralelo)** | posições **1 a 8 sem repetição**, na ordem do score (lock por turma no servidor) |
| **Mesma criança duas vezes (409)** | **201** e depois **409** |
| **Mesma criança em paralelo** | **1 × 201** e **4 × 409** |

### A5. Erros e validações (aba Segurança, ou manualmente)
- **CPF duplicado:** cadastre de novo um responsável com o CPF `100.000.000-01` → **409**.
- **Criança, turma e matrícula inexistentes:** teste "Criança, turma e matrícula inexistentes" → **404** nos três.
- **Corpo inválido:** teste "Corpo inválido" → **400**.
- **Criança com inscrição ativa não se inscreve de novo:** teste correspondente → **409**.

### A6. Permissões (opcional, 1 min)
Entre como **GESTOR** (`gestor@demo.local`) e tente inscrever uma criança → **403** (GESTOR não tem `inscricao:inscricao:add`).
Entre como **ATENDENTE** (`atendente@demo.local`): inscrever funciona.

---

## 3. Cola da Letícia: Stack B (matrícula e vagas)

Aba **3. Stack B**. Partida: banco limpo + aba **7. Roteiro** → **Carregar dados-base da cola**.
Fica pronto: 5 creches, 7 turmas e as 8 famílias na fila da **Creche Sol, MATERNAL, 3 vagas**
(Ana 1, Bruno 2, Carla 3, Gisele 4, Diego 5, Hugo 6, Elisa 7, Fábio 8).

> Se ela preferir cadastrar ao vivo as creches e turmas, use a tabela 1.1 (Creche: nome, capacidade, rua,
> latitude, longitude; Turma: creche, faixa, vagas).

### B1. Alocar e confirmar
1. Card **Alocar e confirmar**: turma `Creche Sol, MATERNAL` → **Alocar vaga**.
   Esperado: **201**, matrícula **PENDENTE** da **Ana Silva**, distância **0,24 km**; a turma passa a **2 vagas**.
2. Matrícula da Ana → **Confirmar matrícula**. Esperado: **200** (CONFIRMADA).
3. **Confirmar 2 vezes (409)**: a segunda confirmação responde **409**.

### B2. Segunda vaga e desistência com alocação automática
1. **Alocar vaga** de novo. Esperado: matrícula **PENDENTE** do **Bruno Souza**, **1,02 km**; a turma fica com **1 vaga**.
2. Matrícula do Bruno → **Desistir da matrícula**. Esperado: **200**; a matrícula vira **CANCELADA**, a vaga volta
   (2), e **a Carla Costa é convocada sozinha** (nova matrícula **PENDENTE**, **2,18 km**), deixando a turma com **1 vaga**.
   A fila passa a ter 5: Gisele, Diego, Hugo, Elisa, Fábio. A consulta da posição da Carla passa a dar **404** (ela saiu da fila).
3. **Desistir 2 vezes (409)**: segunda desistência **409**. **Matrícula inexistente (404)**: **404**.

> **O que dizer:** "A vaga voltou e o próximo da fila foi convocado na hora, sem ninguém clicar. Cada passo
> também gerou eventos para o relatório."

### B3. Terceira vaga e turma sem vaga
**Alocar vaga** mais uma vez: a próxima é a **Gisele Nunes** (empatou com o Diego em 0,40 e chegou antes).
A turma fica com **0 vagas** e 4 na fila (Diego, Hugo, Elisa, Fábio). Alocar de novo → **409** ("Turma sem vaga disponível").

### B4. Sugerir creches próximas (diferentes localidades)
> **Faça este bloco ANTES da corrida e da fila vazia (B5 e B6):** esses cenários criam as próprias creches e turmas, que entrariam na lista e mudariam a ordem abaixo.

Card **Sugerir creches próximas** (distâncias em km, Haversine; em banco limpo, depois do carregamento):

| Origem (lat, lon) | Faixa | Limite | Resultado esperado (creche, km) |
|---|---|---|---|
| **Praça da Sé** (-23.55, -46.63): botão de exemplo | MATERNAL | 5 | Sol **0,31** · Girassol **2,92** · Aurora **6,60** · Esperança **9,26** |
| **Av. Paulista** (-23.56, -46.65): botão de exemplo | MATERNAL | 5 | Girassol **0,62** · Sol **2,06** · Aurora **4,35** · Esperança **11,54** |
| **Pinheiros** (-23.565, -46.69) | MATERNAL | 5 | Aurora **0,30** · Girassol **3,49** · Sol **6,04** · Esperança **15,60** |
| **Penha** (-23.527, -46.545) | MATERNAL | 5 | Esperança **0,23** · Sol **9,33** · Girassol **11,93** · Aurora **15,63** |
| Praça da Sé | BERCARIO | 5 | Sol **0,31** (2 vagas) · Horizonte **14,18** (3 vagas) |
| Pinheiros | PRE_ESCOLA | 5 | Aurora **0,30** (4 vagas) |
| Praça da Sé | MATERNAL | **2** | só Sol **0,31** e Girassol **2,92** |
| lat `-95`, limite `50` | qualquer | | **400** com mensagem amigável: botão **Parâmetros inválidos (400)** |

Depois do B3, a turma **Creche Sol, MATERNAL** está **sem vaga** e **continua na lista**, destacada
(a família ainda pode entrar na fila). Mostre isso partindo da Praça da Sé.

### B5. Concorrência (lock otimista)
Card **Concorrência**: **Preparar corrida (1 vaga, 3 inscritos)** e depois **Disparar alocações em paralelo**
(controle em 5). Esperado: **exatamente 1 × 201** e **4 × 409**, sem matrícula repetida. (Cria a própria creche e turma.)

### B6. Fila vazia
**Alocar com fila vazia (409)** → **409**.

### B7. Permissões (opcional)
Entre como **ATENDENTE** e tente **Criar creche** → **403** (só o GESTOR cria). Entre como **GESTOR**: funciona.

### B8. Expiração por prazo (opcional, exige o job ligado)
Só se sobrar tempo: `scripts/demo-subir.sh --reiniciar-core --com-job`, siga a aba **6. Eventos e Outbox**, bloco
"Job de expiração", e volte com `scripts/demo-subir.sh --reiniciar-core`.

---

## 4. Cola da Lívia: Stack C (Outbox, RabbitMQ, relatórios)

Partida: banco limpo.

### C0. Ambiente (sempre)
Aba **7. Roteiro**: "Antes de apresentar" → **pronto para apresentar**. Abra o painel público em outra aba:
`http://localhost:8090/painel.html?municipioId=1` (em janela anônima, para provar que não há sessão).

### C1. Demonstração guiada (o caminho recomendado)
Aba **4. Stack C** → **Executar demonstração da Stack C** (ritmo `lento` na apresentação, `rápido` no ensaio;
deixe marcada a opção do limite só se for o último passo). Ela **cadastra sozinha os mesmos dados da cola**
(5 creches, 7 turmas, 8 famílias na ordem de chegada) e narra cada passo. O que ela confere e o que a plateia vê:

| Passo | O que aparece / confere |
|---|---|
| Ambiente | core, MSRelatorios, painel público e RabbitMQ no ar; ADMIN; job desligado |
| Segurança | sem token **401** · token adulterado **401** · município de outra prefeitura **403** |
| Painel público | **200** sem token: totais, vagas por faixa, creches com vaga e fila agregada (oculta se < 5 famílias; sem dados pessoais) |
| Creches e turmas | **7 turmas** em 5 creches; um `vaga.liberada` por turma na Outbox |
| 8 famílias na fila | posições finais da tabela 1.3 (Ana 1 … Fábio 8; empate Gisele 4 × Diego 5) |
| Alocar e confirmar | matrícula da **Ana**; turma com **2 vagas**, fila com **7** |
| Publicador | eventos enviados, **0 pendentes**, 0 com erro |
| Projeções | relatório igual ao banco: **2 vagas**, fila **7** |
| Relatórios | ocupação, fila (**7**), taxa de confirmação e distância média coerentes |
| Mostrar o RabbitMQ | (pausa) painel do RabbitMQ: 3 filas do relatório com 1 consumidor, 3 DLQs vazias, exchange `alocacreche.dominio` |
| Falha | RabbitMQ parado; alocação do **Bruno**, inscrição do **Marcos** e confirmação do Bruno respondem OK; banco **1 vaga** × relatório **2 vagas**; Outbox com **5 pendentes** |
| Recuperação | relatório converge em poucos segundos: **1 vaga**, fila **6** |
| Desistência | Bruno cancela, **Carla é convocada sozinha**; relatório **1 vaga** e fila **6** |
| Job de manutenção | prazo da Carla vencido (confirmar dá **409**) + uma rodada do job: Carla **EXPIRADA**, o job convoca **Gisele e Diego**; relatório **0 vagas** e fila **4** |
| Outra creche lotada | Creche Esperança (2 vagas): Karina e Leandro lotam a turma, nova alocação **409**; relatório **0 vagas**, fila vazia |
| Turma lotada com fila | Creche Aurora (1 vaga): Júlio entra na fila sem vaga (**posição 1**), turma lotada segue na sugestão (**0 vagas**); a Irene desiste e o Júlio é convocado |
| Filas em várias turmas | Wagner e Vera entram na fila da Creche Esperança lotada (Vera passa para a posição 1) e a Olívia na da Creche Aurora; o painel mostra um bloco por turma (Sol 4, Esperança 2, Aurora 1) e o filtro por creche mostra só uma |
| Painel público × banco | os totais do painel (sem login) batem com a soma do core: vagas livres, creches com vaga e turmas |
| Idempotência | evento antigo reenviado; vagas e sequência **iguais** antes e depois |
| DLQ | a demo publica uma mensagem inválida: a DLQ vai de **0 para 1** (card "na DLQ" e fila `relatorios.ocupacao.dlq` no RabbitMQ) e depois é limpa; RabbitMQ no ar |
| Limite (opcional) | ~120 atendidas e o resto **429** com `Retry-After`; o painel público bloqueia ~1 min |

### C2. Números finais esperados (banco limpo, depois da demonstração guiada)
- Turma Creche Sol MATERNAL: **0 vagas** disponíveis, **4** famílias aguardando (Hugo, Elisa, Fábio, Marcos, que se inscreve durante a falha). Creche Esperança (maternal): **0 vagas**, **2** na fila (Vera e Wagner). Creche Aurora (maternal): **0 vagas**, **1** na fila (Olívia).
- Matrículas no período: **9** (Ana, Karina e Leandro CONFIRMADAS; Bruno e Irene CANCELADAS; Carla EXPIRADA; Gisele, Diego e Júlio PENDENTES), **3 confirmadas** → taxa **33%**.
- Distância média: **1,25 km** (média das 9 distâncias até a creche de cada uma).
- Ocupação: **7 turmas** no relatório. Painel público: **11 vagas, 4 creches com vaga, 7 turmas**.
- Em banco **não limpo** esses totais incluem o que já existia; confira só o que **mudou** (+3 matrículas, +1 confirmada).

### C3. Se fizer manualmente (em vez do botão), nesta ordem
1. **Carregar dados-base da cola** (aba Roteiro).
2. Aba **1. Visão geral**: mostre o gráfico e as colunas "banco × relatório".
3. Aba **3. Stack B**: **Alocar vaga** (Ana) e **Confirmar matrícula**.
4. Aba **6. Eventos e Outbox**: **Pausar envio**. Volte à Stack B, **Alocar vaga** de novo (Bruno, que responde 201).
   Na **Visão geral**, banco **1 vaga** × relatório **2 vagas** ("sincronizando...") e pendentes na Outbox. Depois
   **Retomar envio** e veja o relatório convergir para **1 vaga**.
   (O botão **Executar cenário guiado** faz isso sozinho, mas numa creche e turma novas, não na Creche Sol.)
5. **Desistir** da matrícula do Bruno: a Carla é convocada sozinha; veja o relatório convergir (1 vaga, fila 5).
6. Aba **6. Eventos e Outbox**, bloco "Evento antigo ou duplicado": escolha a turma **Creche Sol** e **Reenviar evento antigo**.
7. Aba **4. Stack C**: **Consultar relatórios**, botão **Outro município (403)** e, por último, **Simular excesso de consultas (429)**.
8. Aba **5. Segurança**: testes sem token e token adulterado; matriz de permissões.

### C4. Permissões nos relatórios (opcional)
- **GESTOR** consulta os relatórios (**200**).
- **ATENDENTE** consulta relatórios → **403** (o perfil dele não tem permissões de relatórios).
- O painel público responde **200** para todos, inclusive sem login.

---

## 5. Plano B e tempos

| Situação | O que fazer |
|---|---|
| Número de relatório diferente do esperado | o banco não está limpo: `scripts/demo-subir.sh` (zera) e refaça a sua parte |
| 409 de CPF ao cadastrar | o CPF já existe: banco não limpo (ou você repetiu a família) |
| Alocou "sozinho" antes do clique | job ligado: `scripts/demo-subir.sh --reiniciar-core` |
| Envio ficou pausado | aba Eventos e Outbox → **Retomar envio** |
| Painel público mostra "Aguardando liberação" | limite de consultas; espere cerca de 1 minuto |
| Sessão expirou (8 h) | faça login de novo |

Tempos da **versão completa** (ensaio e perguntas): Larissa cerca de 7 min (A0 a A3, mais um botão da A4), Letícia cerca de 5 min
(B1, B2, B4 e B5), Lívia cerca de 2 a 3 min (C1 no ritmo `normal`). **Na apresentação ao vivo use a versão curta (0B):
1 min 30 s para cada uma.**

## 6. Conferência antes de apresentar (cada uma)
- [ ] Banco limpo e `scripts/demo-subir.sh` sem erros.
- [ ] Login ADMIN; aba Roteiro: **pronto para apresentar**.
- [ ] Ctrl+F5 dado.
- [ ] Ensaiei a minha parte (versão curta **e** completa) uma vez com os dados da cola e os números bateram.
- [ ] Carreguei os dados-base e **não mexi mais** até a minha vez (versão curta).
- [ ] Depois do ensaio, voltei a um banco limpo (`scripts/demo-subir.sh`).

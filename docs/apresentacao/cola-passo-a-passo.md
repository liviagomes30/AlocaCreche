# Cola pela interface: passo a passo das três

Tudo aqui é feito **pela interface** (`http://localhost:8090`), campo por campo, com **os mesmos dados nas três**.
Cada uma na sua máquina e no seu banco. Os resultados foram conferidos executando o sistema.

Material de apoio (cenários extras, plano B): `cola-dados-e-cenarios.md`.

---

## PARTE 1. Antes de tudo (as três, 5 minutos)

1. No Git Bash, na pasta do projeto:
   ```bash
   git checkout main && git pull
   scripts/demo-subir.sh
   ```
   Sem `--manter`: o banco começa **limpo**. Anote a senha que o script imprime. Leva uns 80 s.
2. Abra `http://localhost:8090/login.html`. E-mail `admin@demo.local`, município `1`, a senha impressa.
3. Aba **7. Roteiro**: o quadro "Antes de apresentar" deve dizer **pronto para apresentar**.
4. **Ctrl+F5** na página.
5. **Job desligado** (é o padrão). Se alguma alocação acontecer "sozinha", rode `scripts/demo-subir.sh --reiniciar-core`.

> Regra de ouro: **CPFs fixos**. Se aparecer erro 409 de CPF, o banco não estava limpo: rode `scripts/demo-subir.sh` de novo.

### Quem monta o quê

| Quem | Como chega ao ponto de partida |
|---|---|
| **Larissa** | digita os dados à mão (ensaio) ou usa o botão (ao vivo) |
| **Letícia** | aba 7. Roteiro → botão **Carregar dados-base da cola** (leva uns 20 s) |
| **Lívia** | aba 7. Roteiro → botão **Carregar dados-base da cola** |

Depois do botão, o estado é este nas três máquinas: **5 creches, 7 turmas** e **8 famílias na fila** da
**Creche Sol, MATERNAL, 3 vagas**, com **nenhuma matrícula**.

---

## PARTE 2. Os dados (iguais nas três)

### 2.1 Creches e turmas

**Aba 3. Stack B**, cards "Creche" e "Turma". Campos da creche: Nome, Capacidade, Rua, Latitude, Longitude.

| Nome | Capacidade | Rua | Latitude | Longitude | Turmas (Faixa etária, Vagas) |
|---|---|---|---|---|---|
| Creche Sol | 50 | Rua Direita | -23.55 | -46.633 | MATERNAL 3 · BERCARIO 2 |
| Creche Girassol | 40 | Rua Treze de Maio | -23.561 | -46.656 | MATERNAL 2 |
| Creche Aurora | 30 | Rua dos Pinheiros | -23.567 | -46.692 | MATERNAL 1 · PRE_ESCOLA 4 |
| Creche Esperança | 35 | Rua da Penha | -23.526 | -46.543 | MATERNAL 2 |
| Creche Horizonte | 45 | Avenida Santo Amaro | -23.655 | -46.709 | BERCARIO 3 |

Para cada creche: preencha o card **Creche** → **Criar creche**; depois, no card **Turma**, escolha a **Creche**, a
**Faixa etária** e as **Vagas** → **Criar turma** (uma vez por turma).

### 2.2 As 8 famílias (uma ficha por família, na ordem de chegada)

**Aba 2. Stack A.** Cada ficha tem os 3 cartões da tela.

- **Cartão 1 Responsável:** Nome, CPF, Telefone, E-mail, Rua, Número, Latitude, Longitude.
- **Cartão 2 Criança:** Nome da criança, Nascimento (o campo de data usa **dia/mês/ano**).
- **Cartão 3 Inscrição:** Renda per capita, Vulnerabilidade.

**1ª. Fábio Prado**
- Responsável: `Fábio Prado` · CPF `100.000.000-06` · Tel `11999990006` · E-mail `fabio.prado@example.com` · Rua `Avenida Santo Amaro` · Nº `1200` · Lat `-23.65` · Lon `-46.705`
- Criança: `Pedro Prado` · Nascimento `11/02/2024`
- Inscrição: Renda `4500` · Vulnerabilidade `Não` → **posição 1**, score **0,00**

**2ª. Elisa Ramos**
- Responsável: `Elisa Ramos` · CPF `100.000.000-05` · Tel `11999990005` · E-mail `elisa.ramos@example.com` · Rua `Rua da Penha` · Nº `52` · Lat `-23.528` · Lon `-46.548`
- Criança: `Sofia Ramos` · Nascimento `08/07/2024`
- Inscrição: Renda `1800` · Vulnerabilidade `Não` → **posição 1**, score **0,10**

**3ª. Hugo Alves**
- Responsável: `Hugo Alves` · CPF `100.000.000-08` · Tel `11999990008` · E-mail `hugo.alves@example.com` · Rua `Rua Boa Vista` · Nº `15` · Lat `-23.545` · Lon `-46.62`
- Criança: `Miguel Alves` · Nascimento `22/04/2024`
- Inscrição: Renda `2500` · Vulnerabilidade `Sim` → **posição 1**, score **0,30**

**4ª. Gisele Nunes**
- Responsável: `Gisele Nunes` · CPF `100.000.000-07` · Tel `11999990007` · E-mail `gisele.nunes@example.com` · Rua `Rua São Joaquim` · Nº `77` · Lat `-23.554` · Lon `-46.64`
- Criança: `Laura Nunes` · Nascimento `05/12/2023`
- Inscrição: Renda `350` · Vulnerabilidade `Não` → **posição 1**, score **0,40**

**5ª. Diego Lima**
- Responsável: `Diego Lima` · CPF `100.000.000-04` · Tel `11999990004` · E-mail `diego.lima@example.com` · Rua `Rua dos Pinheiros` · Nº `410` · Lat `-23.566` · Lon `-46.683`
- Criança: `Heitor Lima` · Nascimento `15/01/2024`
- Inscrição: Renda `450` · Vulnerabilidade `Não` → **posição 2** (empata com a Gisele em 0,40; fica atrás quem chegou depois), score **0,40**

**6ª. Carla Costa**
- Responsável: `Carla Costa` · CPF `100.000.000-03` · Tel `11999990003` · E-mail `carla.costa@example.com` · Rua `Rua Treze de Maio` · Nº `330` · Lat `-23.563` · Lon `-46.649`
- Criança: `Alice Costa` · Nascimento `20/11/2023`
- Inscrição: Renda `1200` · Vulnerabilidade `Sim` → **posição 1**, score **0,50**

**7ª. Bruno Souza**
- Responsável: `Bruno Souza` · CPF `100.000.000-02` · Tel `11999990002` · E-mail `bruno.souza@example.com` · Rua `Rua da Glória` · Nº `25` · Lat `-23.559` · Lon `-46.635`
- Criança: `Davi Souza` · Nascimento `02/05/2024`
- Inscrição: Renda `800` · Vulnerabilidade `Sim` → **posição 1**, score **0,60**

**8ª. Ana Silva**
- Responsável: `Ana Silva` · CPF `100.000.000-01` · Tel `11999990001` · E-mail `ana.silva@example.com` · Rua `Rua Direita` · Nº `10` · Lat `-23.552` · Lon `-46.634`
- Criança: `Lia Silva` · Nascimento `10/03/2024`
- Inscrição: Renda `350` · Vulnerabilidade `Sim` → **posição 1**, score **0,70**

**Fila final** (com as 8): **Ana 1 · Bruno 2 · Carla 3 · Gisele 4 · Diego 5 · Hugo 6 · Elisa 7 · Fábio 8**.

Como o score é calculado: renda × 0,4 + vulnerabilidade × 0,3 (renda até 500 = 1; até 1000 = 0,75; até 1500 = 0,5;
até 2000 = 0,25; acima = 0).

### 2.3 Como isso aparece no banco (DBeaver)

Pela interface, estes campos **não existem** nos formulários e ficam assim no banco: **bairro = `Centro`**
(sempre), e **CEP, cidade e estado vazios**. É normal.

Para mostrar o que foi cadastrado, no DBeaver (conexão CORE: `localhost:5434`, banco `alocacreche`, usuário e senha
`alocacreche_app`), execute o bloco inteiro com **Alt+X**:

```sql
SELECT set_config('app.current_tenant', '1', false);

SELECT id, municipio_id, data_nascimento, nome, responsavel_id
FROM public.crianca
ORDER BY id;

SELECT id, municipio_id, cpf, email, bairro, cep, cidade, estado, latitude, longitude, numero, rua, nome, telefone
FROM public.responsavel
ORDER BY id;
```

A primeira linha define o município da sessão. **Sem ela, o Postgres recusa a consulta ou devolve vazio**: é a
Row-Level Security isolando as prefeituras (bom para mostrar). Depois do botão **Carregar dados-base**, esperado:
**8 responsáveis e 8 crianças** do município 1, com as latitudes e longitudes da tabela 2.2.

---

## PARTE 3. LARISSA: Stack A (inscrição e fila)

### Ao vivo (1 min 30 s) · ponto de partida: dados-base carregados
1. Aba **2. Stack A**. Role até o card **Posição na fila**.
2. Clique **Atualizar todas as posições**.
   **Aparece:** Ana 1, Bruno 2, Carla 3, Gisele 4, Diego 5, Hugo 6, Elisa 7, Fábio 8. *Diga:* "O Fábio chegou primeiro e é o último; a Ana chegou por último e é a primeira, pelo score."
3. Role até **Cenários** e clique **Furar a fila (renda alta, depois baixa + vulnerável)**.
   **Aparece:** a inscrição de score **0,70** fica na **posição 1** e a de renda alta (0,00) cai para a **2**.
4. (Se sobrar tempo) clique **Mesma criança duas vezes (409)**. **Aparece:** **201** e depois **409**.

### Ensaio completo (digitando tudo) · ponto de partida: banco limpo
1. Aba **3. Stack B**: crie a **Creche Sol** (card Creche) e a turma **MATERNAL, 3 vagas** (card Turma), pela tabela 2.1.
2. Aba **2. Stack A**, **Fábio Prado** em 3 passos:
   - Cartão 1 com a ficha do Fábio → **Cadastrar responsável** (**201**).
   - Cartão 2: Responsável `Fábio Prado`, criança `Pedro Prado`, `11/02/2024` → **Cadastrar criança** (**201**).
   - Cartão 3: Modo `Criança já cadastrada`, Criança `Pedro Prado`, Turma `Creche Sol`, Renda `4500`, Vulnerabilidade `Não` → **Inscrever** (**201**, posição **1**, score **0,00**).
3. As **outras 7 famílias**, na ordem da tabela 2.2 (Elisa, Hugo, Gisele, Diego, Carla, Bruno, Ana), em **modo inline**:
   preencha os cartões **1** e **2** com a ficha; no cartão **3** escolha Modo `Cadastro inline (usa os cartões 1 e 2)`,
   Turma `Creche Sol`, Renda e Vulnerabilidade da ficha → **Inscrever** (um clique só).
   **Confira cada posição** (ao chegar): Elisa 1, Hugo 1, Gisele 1, **Diego 2**, Carla 1, Bruno 1, Ana 1.
4. **Atualizar todas as posições:** fila final **Ana 1 … Fábio 8**.
5. Cenários: **Furar a fila**, **Irmão matriculado** (irmão A **0,20**, irmão B **0,30**), **Inscrições simultâneas (8 em paralelo)** (posições 1 a 8 sem repetir), **Mesma criança em paralelo** (1 × 201 e 4 × 409).
6. Erro de CPF: cadastre um responsável com CPF `100.000.000-01` → **409**.

---

## PARTE 4. LETÍCIA: Stack B (matrícula e vagas)

### Ao vivo (1 min 30 s) · ponto de partida: dados-base carregados
Aba **3. Stack B**, card **Alocar e confirmar**.
1. Em **Turma**, escolha **Creche Sol, MATERNAL** → **Alocar vaga**.
   **Aparece:** matrícula **PENDENTE** da **Ana Silva**, **0,24 km**; a turma passa a **2 vagas**.
2. Em **Matrícula**, escolha a da Ana → **Confirmar matrícula**. **Aparece:** **CONFIRMADA** (200).
3. **Alocar vaga** de novo. **Aparece:** matrícula **PENDENTE** do **Bruno Souza**, **1,02 km**; a turma fica com **1 vaga**.
4. No card **Desistência**, escolha a matrícula do **Bruno** → **Desistir da matrícula**.
   **Aparece:** matrícula **CANCELADA**, a vaga volta, e **a Carla Costa é convocada sozinha** (nova matrícula **PENDENTE**, **2,18 km**).
   A turma fica com **1 vaga**. *Diga:* "Ninguém clicou: o servidor convocou a próxima da fila."

### Ensaio completo · mesmo ponto de partida
5. **Confirmar 2 vezes (409)** na matrícula da Ana: a segunda dá **409**. **Desistir 2 vezes (409)** e **Matrícula inexistente (404)**: **409** e **404**.
6. **Alocar vaga** mais uma vez: vai para a **Gisele Nunes** (empatou com o Diego em 0,40 e chegou antes). A turma fica **sem vaga**. Alocar de novo → **409**.
7. **Sugerir creches próximas** (card à direita). **Faça esta etapa ANTES das etapas 8 e 9**, porque elas criam as próprias creches e mudam a lista.
   Escolha a faixa, digite a latitude e a longitude, **Limite 5** → **Buscar creches próximas**:

   | Origem (Latitude, Longitude) | Faixa | Resultado esperado (creche, km) |
   |---|---|---|
   | Praça da Sé (`-23.55`, `-46.63`), botão **Exemplo: Praça da Sé** | MATERNAL | Sol 0,31 · Girassol 2,92 · Aurora 6,60 · Esperança 9,26 |
   | Av. Paulista (`-23.56`, `-46.65`), botão **Exemplo: Av. Paulista** | MATERNAL | Girassol 0,62 · Sol 2,06 · Aurora 4,35 · Esperança 11,54 |
   | Pinheiros (`-23.565`, `-46.69`) | MATERNAL | Aurora 0,30 · Girassol 3,49 · Sol 6,04 · Esperança 15,60 |
   | Penha (`-23.527`, `-46.545`) | MATERNAL | Esperança 0,23 · Sol 9,33 · Girassol 11,93 · Aurora 15,63 |
   | Praça da Sé | BERCARIO | Sol 0,31 (2 vagas) · Horizonte 14,18 (3 vagas) |
   | Pinheiros | PRE_ESCOLA | Aurora 0,30 (4 vagas) |
   | Praça da Sé, **Limite 2** | MATERNAL | só Sol 0,31 e Girassol 2,92 |

   Depois da etapa 6, a **Creche Sol MATERNAL** está sem vaga e **continua na lista**, destacada. Botão **Parâmetros inválidos (400)** → **400**.
8. **Preparar corrida (1 vaga, 3 inscritos)**, ajuste o controle para **5** e **Disparar alocações em paralelo**: **1 × 201 e 4 × 409**.
9. **Alocar com fila vazia (409)** → **409**.

---

## PARTE 5. LÍVIA: Stack C (Outbox, RabbitMQ, relatórios)

### Ao vivo (1 min 30 s) · ponto de partida: dados-base carregados
**Antes de começar** (fora do ar): aba **3. Stack B**, **Alocar vaga** na Creche Sol uma vez (para o evento antigo diferir do atual). **Abra um terminal na pasta do projeto.**
1. Aba **6. Eventos e Outbox**, card **Falha e recuperação do envio**: mostra **RabbitMQ: no ar**. No terminal, rode `docker compose stop rabbitmq`: o card passa a **fora do ar** na hora.
2. Aba **3. Stack B** → **Alocar vaga** (responde **201**). Aba **1. Visão geral**: banco **1 vaga** × relatório **2 vagas** ("sincronizando...") e **pendentes** na Outbox.
3. No terminal, rode `docker compose start rabbitmq`. Em cerca de **15 s** o relatório converge (**1 vaga**) e os pendentes voltam a **0**.
   (Sem terminal: aba 6 → **Executar cenário guiado** faz a falha simulada, pausando o envio.)
2'. Card **Evento antigo ou duplicado**: turma **Creche Sol** → **Reenviar evento antigo**.
   **Aparece:** **evento antigo ignorado**; vagas e sequência aplicada **iguais antes e depois**.
3. Aba **1. Visão geral**: mostre o **gráfico** (as linhas relatório × banco se separam e voltam a se encontrar) e a coluna **Sincronia**.

### Ensaio completo · demonstração guiada
Aba **4. Stack C** → **Executar demonstração da Stack C** (ritmo `rápido` no ensaio, `lento` na apresentação). Ela cadastra
sozinha os mesmos dados da cola e narra tudo (segurança, painel público, Outbox, falha e recuperação, desistência,
idempotência, DLQ e limite) e inclui o **job de manutenção**: vence o prazo da matrícula da Carla e executa uma rodada do job sob demanda
(ela vira **EXPIRADA** e o job convoca **Gisele e Diego**). Em **Falha do RabbitMQ = parar de verdade** (o padrão), o quadro roxo mostra o comando com o botão
**Copiar comando**: rode `docker compose stop rabbitmq` no passo da falha e `docker compose start rabbitmq` no passo da recuperação;
a tela detecta sozinha quando o broker parou e quando voltou. **Simular em vez disso** usa a pausa simulada, sem terminal.
Se interromper a demo com o broker parado, o quadro deixa o comando de subida à vista.

Durante a falha, a demo gera **5 eventos** (alocação do Bruno = 3, inscrição do Marcos = 1, confirmação do Bruno = 1) e a Outbox mostra **5 pendentes**.

Com **pausar para mostrar o RabbitMQ** marcado (padrão), a demo **para em três momentos** com o endereço `http://localhost:15672` (usuário `guest`, senha `guest`) e o botão **Abrir no navegador** no quadro roxo; clique **Continuar** quando terminar: (1) **antes da falha**, aba Queues: as 3 filas do relatório com 1 consumidor e 0 mensagens, as 3 DLQs vazias, e o exchange `alocacreche.dominio`; (2) **depois de subir o broker**: as filas e os consumidores de volta (o painel do RabbitMQ some enquanto o broker está parado); (3) **na fila de rejeição**: a demo publica uma mensagem inválida e a fila `relatorios.ocupacao.dlq` mostra **1 mensagem** (o card "na DLQ" da tela também); depois a demo limpa essa mensagem. Cenários finais da demo, em outras creches, com 4 famílias extras (só na demo automática): **Creche Esperança** (maternal, 2 vagas):
Karina (0,70) e Leandro (0,20) lotam a turma e uma nova alocação dá **409**; **Creche Aurora** (maternal, 1 vaga): a Irene fica com a
vaga, o Júlio entra na fila **sem vaga** (posição 1), a turma lotada continua na sugestão de creches (0 vagas, 0,30 km de Pinheiros) e,
quando a Irene desiste, o Júlio é convocado sozinho. Passo **Filas em várias turmas e creches**: o Wagner (0,10) e a Vera (0,60) entram na fila da Creche Esperança já lotada (a Vera passa para a posição 1) e a Olívia (0,40) entra na da Creche Aurora; o painel "Crianças na fila de espera" mostra **um bloco por turma** (Creche Sol 4, Creche Esperança 2, Creche Aurora 1, em banco limpo), cada um com a contagem do core ao lado da do relatório, e a demo filtra por creche para mostrar só uma. O último passo confere o **painel público com o banco**.

Números finais esperados (banco limpo): Creche Sol **0 vagas** e **4 na fila** (Hugo, Elisa, Fábio e o Marcos, que se inscreve durante a falha); Esperança (2 na fila: Vera e Wagner) e Aurora (1 na fila: Olívia) com **0 vagas**;
**9 matrículas** no período, **3 confirmadas** (33%); distância média **1,25 km**; painel público **11 vagas, 4 creches com vaga, 7 turmas**.

Job de manutenção, sem a demo: aba **6. Eventos e Outbox**, bloco do job → escolha uma matrícula PENDENTE → **1. Vencer o prazo desta matrícula**
→ **2. Executar o job agora**. A tela conta o que o job fez (por exemplo, "Matrícula #6 de Carla Costa: PENDENTE → EXPIRADA" e as novas convocações).

Outros destaques, se houver tempo: aba **4. Stack C** → **Outro município (403)**; painel público em janela anônima
(`/painel.html?municipioId=1`); **Simular excesso de consultas (429)** (por último: bloqueia o painel por ~1 min).

---

## PARTE 6. Se algo der errado

| Situação | O que fazer |
|---|---|
| Número diferente do esperado ou erro 409 de CPF | banco não limpo: `scripts/demo-subir.sh` e refaça a sua parte |
| Alocou "sozinho" antes do clique | job ligado: `scripts/demo-subir.sh --reiniciar-core` |
| Envio da Outbox ficou pausado | aba 6. Eventos e Outbox → **Retomar envio** |
| Painel público: "Aguardando liberação" | limite de consultas: espere cerca de 1 minuto |
| Sessão expirou (8 h) | faça login de novo |
| DBeaver sem linhas ou com erro | falta o `set_config` da parte 2.3 no início do script |

## Conferência antes de apresentar (cada uma)
- [ ] `scripts/demo-subir.sh` sem erros, banco limpo, login ADMIN, aba Roteiro: **pronto para apresentar**.
- [ ] Ctrl+F5 dado; (Letícia e Lívia) **Carregar dados-base da cola** feito; (Lívia) uma alocação na Creche Sol feita.
- [ ] Ensaiei a minha parte pela interface e os números bateram.
- [ ] Depois do ensaio, voltei a um banco limpo e a um estado de partida (não mexo mais até a minha vez).

# Dados padronizados (iguais nas 3)

**Antes (todas):** `scripts/demo-subir.sh` → login `admin@demo.local`, município `1` → aba 7 Roteiro deve dizer "pronto para apresentar" → Ctrl+F5.
**Letícia e Lívia:** aba 7 Roteiro → **Carregar dados-base da cola**.

**Creches (aba 3 Stack B):** Nome · Capacidade · Rua · Lat · Lon → turmas (Faixa, Vagas)

| Nome | Cap. | Rua | Lat | Lon | Turmas |
|---|---|---|---|---|---|
| Creche Sol | 50 | Rua Direita | -23.55 | -46.633 | MATERNAL 3 · BERCARIO 2 |
| Creche Girassol | 40 | Rua Treze de Maio | -23.561 | -46.656 | MATERNAL 2 |
| Creche Aurora | 30 | Rua dos Pinheiros | -23.567 | -46.692 | MATERNAL 1 · PRE_ESCOLA 4 |
| Creche Esperança | 35 | Rua da Penha | -23.526 | -46.543 | MATERNAL 2 |
| Creche Horizonte | 45 | Avenida Santo Amaro | -23.655 | -46.709 | BERCARIO 3 |

**Famílias (aba 2 Stack A), na ordem de chegada**

| # | Responsável | CPF | Tel | E-mail | Rua | Nº | Lat | Lon | Criança | Nasc. | Renda | Vuln. | Posição ao chegar | Score |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Fábio Prado | 100.000.000-06 | 11999990006 | fabio.prado@example.com | Avenida Santo Amaro | 1200 | -23.65 | -46.705 | Pedro Prado | 11/02/2024 | 4500 | Não | 1 | 0,00 |
| 2 | Elisa Ramos | 100.000.000-05 | 11999990005 | elisa.ramos@example.com | Rua da Penha | 52 | -23.528 | -46.548 | Sofia Ramos | 08/07/2024 | 1800 | Não | 1 | 0,10 |
| 3 | Hugo Alves | 100.000.000-08 | 11999990008 | hugo.alves@example.com | Rua Boa Vista | 15 | -23.545 | -46.62 | Miguel Alves | 22/04/2024 | 2500 | Sim | 1 | 0,30 |
| 4 | Gisele Nunes | 100.000.000-07 | 11999990007 | gisele.nunes@example.com | Rua São Joaquim | 77 | -23.554 | -46.64 | Laura Nunes | 05/12/2023 | 350 | Não | 1 | 0,40 |
| 5 | Diego Lima | 100.000.000-04 | 11999990004 | diego.lima@example.com | Rua dos Pinheiros | 410 | -23.566 | -46.683 | Heitor Lima | 15/01/2024 | 450 | Não | **2** | 0,40 |
| 6 | Carla Costa | 100.000.000-03 | 11999990003 | carla.costa@example.com | Rua Treze de Maio | 330 | -23.563 | -46.649 | Alice Costa | 20/11/2023 | 1200 | Sim | 1 | 0,50 |
| 7 | Bruno Souza | 100.000.000-02 | 11999990002 | bruno.souza@example.com | Rua da Glória | 25 | -23.559 | -46.635 | Davi Souza | 02/05/2024 | 800 | Sim | 1 | 0,60 |
| 8 | Ana Silva | 100.000.000-01 | 11999990001 | ana.silva@example.com | Rua Direita | 10 | -23.552 | -46.634 | Lia Silva | 10/03/2024 | 350 | Sim | 1 | 0,70 |

**Fila final:** Ana 1 · Bruno 2 · Carla 3 · Gisele 4 · Diego 5 · Hugo 6 · Elisa 7 · Fábio 8

---

# Cola da LARISSA: Stack A

**Ao vivo (1:30)** · dados-base carregados
1. Aba **2 Stack A** → card **Posição na fila** → **Atualizar todas as posições** → Ana 1 … Fábio 8.
2. **Furar a fila** → score 0,70 na posição 1; renda alta cai para 2.
3. (Sobrou tempo) **Mesma criança duas vezes (409)** → 201, depois 409.

**Ensaio completo** · banco limpo
1. Aba **3 Stack B**: criar **Creche Sol** e turma **MATERNAL 3**.
2. Aba **2 Stack A**, Fábio em 3 passos: cartão 1 **Cadastrar responsável** (201) → cartão 2 **Cadastrar criança** (201) → cartão 3 modo `Criança já cadastrada`, turma Creche Sol, renda, vuln. → **Inscrever** (posição 1).
3. Demais 7, na ordem da tabela: cartões 1 e 2 preenchidos, cartão 3 modo `Cadastro inline`, turma Creche Sol → **Inscrever**. Conferir a coluna "Posição ao chegar".
4. **Atualizar todas as posições** → fila final.
5. Botões: **Irmão matriculado** (A 0,20 · B 0,30) · **Inscrições simultâneas** (1 a 8 sem repetir) · **Mesma criança em paralelo** (1×201, 4×409).
6. CPF `100.000.000-01` de novo → 409.

---

# Cola da LETÍCIA: Stack B

**Ao vivo (1:30)** · dados-base carregados · aba **3 Stack B**
1. Turma `Creche Sol, MATERNAL` → **Alocar vaga** → PENDENTE da **Ana**, 0,24 km, 2 vagas.
2. **Confirmar matrícula** (Ana) → CONFIRMADA.
3. **Alocar vaga** → PENDENTE do **Bruno**, 1,02 km, 1 vaga.
4. Card Desistência, matrícula do Bruno → **Desistir** → CANCELADA; **Carla** convocada sozinha (PENDENTE, 2,18 km); 1 vaga.

**Ensaio completo**
5. **Confirmar 2 vezes (409)** · **Desistir 2 vezes (409)** · **Matrícula inexistente (404)**.
6. **Alocar vaga** → vai para **Gisele**; turma sem vaga; alocar de novo → 409.
7. **Sugerir creches próximas** (limite 5) **antes** das etapas 8 e 9:

| Origem (Lat, Lon) | Faixa | Esperado (km) |
|---|---|---|
| Praça da Sé (-23.55, -46.63) | MATERNAL | Sol 0,31 · Girassol 2,92 · Aurora 6,60 · Esperança 9,26 |
| Av. Paulista (-23.56, -46.65) | MATERNAL | Girassol 0,62 · Sol 2,06 · Aurora 4,35 · Esperança 11,54 |
| Pinheiros (-23.565, -46.69) | MATERNAL | Aurora 0,30 · Girassol 3,49 · Sol 6,04 · Esperança 15,60 |
| Penha (-23.527, -46.545) | MATERNAL | Esperança 0,23 · Sol 9,33 · Girassol 11,93 · Aurora 15,63 |
| Praça da Sé | BERCARIO | Sol 0,31 · Horizonte 14,18 |
| Pinheiros | PRE_ESCOLA | Aurora 0,30 |
| Praça da Sé, limite 2 | MATERNAL | Sol 0,31 · Girassol 2,92 |

   **Parâmetros inválidos (400)** → 400.
8. **Preparar corrida**, controle em 5 → **Disparar alocações em paralelo** → 1×201, 4×409.
9. **Alocar com fila vazia (409)** → 409.

---

# Cola da LÍVIA: Stack C

**Antes do ao vivo:** dados-base carregados + aba 3 **Alocar vaga** uma vez na Creche Sol + **terminal aberto na pasta do projeto**.

**Ao vivo (1:30)** · falha do RabbitMQ de verdade
1. Aba **6 Eventos e Outbox**: o card mostra **RabbitMQ: no ar**. No terminal: `docker compose stop rabbitmq` → o card passa a **fora do ar** na hora.
2. Aba **3 Stack B** → **Alocar vaga** (a API responde 201) → aba **1 Visão geral**: banco **1 vaga** × relatório **2 vagas** ("sincronizando...") e **pendentes** na Outbox.
3. No terminal: `docker compose start rabbitmq` → em cerca de **15 s** o relatório converge (1 vaga) e os pendentes zeram.
4. Aba **6**, bloco **Evento antigo ou duplicado** → turma `Creche Sol` → **Reenviar evento antigo** → "evento antigo ignorado".
(Sem terminal: o botão **Executar cenário guiado** da aba 6 faz a falha simulada, pausando o envio.)

**Ensaio completo**
1. Aba **4 Stack C** → **Executar demonstração da Stack C** (ritmo `rápido`; `lento` na apresentação). Com **Falha do RabbitMQ = parar de verdade**, o quadro roxo mostra o comando, a demo **detecta sozinha** quando você para e quando sobe o broker: rode `docker compose stop rabbitmq` quando ele pedir (passo 10) e `docker compose start rabbitmq` no passo de recuperação (passo 12). **Simular em vez disso** no quadro usa a pausa simulada. Com **pausar para mostrar o RabbitMQ** marcado (padrão), a demo **para em três momentos** com o endereço `http://localhost:15672` (usuário `guest`, senha `guest`) e o botão **Abrir no navegador** no quadro roxo; clique **Continuar** quando terminar: (1) **antes da falha**, aba Queues: as 3 filas do relatório com 1 consumidor e 0 mensagens, as 3 DLQs vazias, e o exchange `alocacreche.dominio`; (2) **depois de subir o broker**: as filas e os consumidores de volta (o painel do RabbitMQ some enquanto o broker está parado); (3) **na fila de rejeição**: a demo publica uma mensagem inválida e a fila `relatorios.ocupacao.dlq` mostra **1 mensagem** (o card "na DLQ" da tela também); depois a demo limpa essa mensagem.
2. Final esperado (banco limpo): Creche Sol **0 vagas**, **4 na fila** (Hugo, Elisa, Fábio, Marcos); Creche Esperança (2 na fila: Vera e Wagner) e Creche Aurora (1 na fila: Olívia), maternais **lotadas, 0 vagas**; **9 matrículas**, **3 confirmadas (33%)**; distância média **1,25 km**; painel público **11 vagas, 4 creches com vaga, 7 turmas**. O passo do **job** vence o prazo da Carla (confirmar dá **409**) e executa uma rodada: ela vira **EXPIRADA** e o job convoca **Gisele e Diego**. Durante a falha do RabbitMQ a demo gera **5 eventos** (alocação do Bruno, inscrição do Marcos e confirmação do Bruno), e o Outbox mostra **5 pendentes**. Passo **Filas em várias turmas e creches**: o Wagner (0,10) e a Vera (0,60) entram na fila da Creche Esperança já lotada (a Vera passa para a posição 1) e a Olívia (0,40) entra na da Creche Aurora; o painel "Crianças na fila de espera" mostra **um bloco por turma** (Creche Sol 4, Creche Esperança 2, Creche Aurora 1, em banco limpo), cada um com a contagem do core ao lado da do relatório, e a demo filtra por creche para mostrar só uma. Os cenários finais usam famílias extras (só na demo automática): Karina e Leandro na Creche Esperança (2 vagas, lotam e dão 409), Irene e Júlio na Creche Aurora (1 vaga: o Júlio entra na fila sem vaga e é convocado quando a Irene desiste).
3. Job de manutenção (extra): aba **6 Eventos e Outbox**, bloco do job → escolha uma matrícula PENDENTE → **1. Vencer o prazo desta matrícula** → **2. Executar o job agora** → ela vira **EXPIRADA**, a vaga volta e o job convoca o próximo da fila.
4. Extras: **Outro município (403)** · painel público em janela anônima `/painel.html?municipioId=1` · **Simular excesso de consultas (429)** (por último, bloqueia ~1 min).

---

# Queries da LÍVIA (Stack C) · DBeaver · Alt+X

**Conexão CORE** (`localhost:5434`, banco `alocacreche`, usuário e senha `alocacreche_app`)

```sql
-- 1. Outbox: quantos eventos em cada status (durante a falha aparece PENDENTE; depois, tudo PROCESSADO)
SELECT status, count(*) FROM outbox GROUP BY status ORDER BY status;

-- 2. Últimos eventos gravados e enviados
SELECT id, tipo_evento, status, criado_em FROM outbox ORDER BY id DESC LIMIT 10;

-- 3. Vagas NO BANCO (compare com o relatório)
SELECT set_config('app.current_tenant', '1', false);
SELECT t.id AS turma_id, c.nome AS creche, t.faixa_etaria, t.vagas_totais, t.vagas_disponiveis
FROM turma t JOIN creche c ON c.id = t.creche_id ORDER BY t.id;

-- 3b. Matrículas e prazo (na demo do job: o prazo vira 2000-01-01 e o status muda para EXPIRADA)
SELECT id, inscricao_id, status, prazo_confirmacao FROM matricula ORDER BY id;
```

**Conexão RELATORIOS** (`localhost:5433`, banco `alocacreche_relatorios`, usuário e senha `alocacreche_app`)

```sql
-- 4. Vagas NO RELATÓRIO (projeção) e a sequência do último evento aplicado
SELECT set_config('app.current_tenant', '1', false);
SELECT turma_id, creche_id, vagas_disponiveis, ultima_sequencia_aplicada AS sequencia, atualizado_em
FROM ocupacao_turma ORDER BY turma_id;

-- 5. Fila de espera e matrículas na projeção
SELECT turma_id, count(*) FILTER (WHERE data_saida IS NULL) AS aguardando, count(*) AS total
FROM fila_espera_inscricao GROUP BY turma_id ORDER BY turma_id;
SELECT status, count(*) AS matriculas, round(avg(distancia_km)::numeric, 2) AS distancia_media_km
FROM matricula_relatorio GROUP BY status ORDER BY status;
```

**Como usar na demo:** rode a 1 e a 3 com o RabbitMQ parado (Outbox com PENDENTE, banco 1 vaga) e a 4 ao mesmo tempo
(relatório ainda com 2 vagas); depois de `docker compose start rabbitmq`, a 1 mostra tudo PROCESSADO e a 4 mostra 1 vaga
e uma sequência maior. A 5 confere as contagens do relatório de fila e de confirmação.

---

# DBeaver (CORE `localhost:5434`, banco `alocacreche`, usuário e senha `alocacreche_app`) · Alt+X

```sql
SELECT set_config('app.current_tenant', '1', false);
SELECT id, municipio_id, data_nascimento, nome, responsavel_id FROM public.crianca ORDER BY id;
SELECT id, municipio_id, cpf, email, bairro, cep, cidade, estado, latitude, longitude, numero, rua, nome, telefone FROM public.responsavel ORDER BY id;
```
Esperado após dados-base: 8 responsáveis e 8 crianças; `bairro = Centro`; CEP, cidade e estado vazios.

**Se der errado:** erro 409 de CPF ou número diferente → `scripts/demo-subir.sh` (banco limpo). Alocou sozinho → `scripts/demo-subir.sh --reiniciar-core`. Envio pausado → aba 6 **Retomar envio**.

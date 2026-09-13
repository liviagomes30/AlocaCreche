# Consumo do evento VagaLiberada e projeção de ocupação (MSRelatorios) Specification

## Problem Statement

`alocacreche-relatorios` existe hoje só como scaffold — sem nenhum dado, não há nada para painel
público ou relatório algum consultar. A primeira peça necessária é o consumidor assíncrono que
recebe o evento `VagaLiberada` (publicado pela Outbox do monólito via RabbitMQ) e mantém uma
projeção de ocupação por turma, de forma idempotente e resiliente a entrega duplicada/fora de
ordem — a base sobre a qual qualquer endpoint de leitura será construído depois.

## Goals

- [ ] Consumidor RabbitMQ processa o evento `VagaLiberada` e faz upsert da projeção de ocupação da
      turma correspondente.
- [ ] Entregas duplicadas ou fora de ordem (garantia at-least-once da Outbox) nunca corrompem a
      projeção — a maior sequência (`eventId`) sempre vence.
- [ ] Mensagens malformadas ou com falha persistente vão para uma dead-letter queue, nunca ficam em
      loop infinito nem se perdem silenciosamente.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Consumo do evento `MatriculaCriada` | Alimenta relatório de confirmação de matrícula, não ocupação — consumidor separado, tarefa futura |
| Endpoint HTTP de leitura (`consultarPainelPublico`, `relatorioOcupacao`) | Esta tarefa cobre só a ponta de escrita (consumidor + projeção); decisão confirmada com o usuário |
| Implementação do lado produtor (módulo Matrícula, `alocacreche-core`) | Fora do escopo deste módulo — o contrato de payload definido aqui é a especificação que o produtor deve seguir ao ser implementado |
| Cache Redis do painel público | Só relevante quando o endpoint de leitura existir |
| Autenticação/autorização (Sa-Token) | O RabbitMQ listener não é um endpoint HTTP — não há requisição autenticada aqui |
| Ferramenta de migration (Flyway/Liquibase) para a política de RLS | Fora de escopo desta tarefa (ver Assumptions); `ddl-auto: update` já cobre a tabela, RLS precisa de SQL manual documentado |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Contrato JSON do evento `VagaLiberada` | `{eventId, tipoEvento, municipioId, turmaId, crecheId, vagasDisponiveis, ocorridoEm}` | Nenhum produtor existe ainda; contrato precisa ser definido agora para o consumidor poder ser implementado, e vira a especificação que o time de Matrícula deve seguir | y |
| Topologia RabbitMQ | Exchange topic `alocacreche.dominio`, routing key `vaga.liberada`, fila `relatorios.ocupacao`, DLQ `relatorios.ocupacao.dlq` após 3 tentativas | Confirmado com o usuário; declarado pelo próprio `alocacreche-relatorios` (declaração idempotente, funciona mesmo sem o produtor existir ainda) | y |
| Row-Level Security no banco do MSRelatorios | Aplicar o mesmo padrão RLS + `SET LOCAL app.current_tenant` já usado no monólito (ver `docs/arquitetura/notas-tecnicas.md`), mesmo sendo um banco próprio e separado | O consumidor RabbitMQ processa eventos de todos os municípios num único processo, sem contexto de sessão — exatamente o cenário (job/consumidor sem JWT) para o qual o padrão RLS+SET LOCAL já existe no projeto. Estender o mesmo padrão evita reabrir, num banco novo, o risco que ele já foi desenhado para fechar no outro banco | y |
| Mecanismo de upsert concorrente (JPA otimista vs. `INSERT ... ON CONFLICT` nativo) | Decisão de Design, não de Specify | A spec só exige que a projeção nunca regrida para uma sequência mais antiga sob concorrência (OCUP-03); o mecanismo concreto é escolhido na fase Design | n/a — decisão adiada para Design |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Consumir VagaLiberada e manter a projeção de ocupação ⭐ MVP

**User Story**: Como MSRelatorios, quero consumir o evento `VagaLiberada` e manter uma projeção de
ocupação por turma, para que relatórios futuros (ainda não implementados) tenham um dado confiável
para consultar.

**Why P1**: Sem isso não existe nenhum dado no MSRelatorios — é o pré-requisito de tudo o resto do
módulo.

**Acceptance Criteria**:

1. **[OCUP-01]** WHEN uma mensagem com routing key `vaga.liberada` chega na fila
   `relatorios.ocupacao` THEN o sistema SHALL fazer upsert da linha de projeção de ocupação
   identificada por `turmaId`, gravando `vagasDisponiveis`, `crecheId`, `municipioId` e a sequência
   (`eventId`) recebida.
2. **[OCUP-02]** WHEN o `eventId` recebido for menor ou igual à última sequência já aplicada para
   aquele `turmaId` THEN o sistema SHALL descartar o upsert sem erro e sem alterar a projeção
   (idempotência e ordem).
3. **[OCUP-03]** WHEN duas mensagens para o mesmo `turmaId` forem processadas concorrentemente THEN
   o sistema SHALL garantir que a projeção final reflita só a maior sequência entre as duas,
   independente da ordem de chegada real.
4. **[OCUP-04]** The system SHALL aplicar a variável de tenant (`SET LOCAL app.current_tenant`)
   igual ao `municipioId` do payload antes de gravar ou ler a projeção, dentro da mesma transação de
   processamento da mensagem.
5. **[OCUP-05]** The system SHALL declarar o exchange `alocacreche.dominio` (topic), a fila
   `relatorios.ocupacao` e a dead-letter queue `relatorios.ocupacao.dlq` na inicialização, de forma
   idempotente (não falha se já existirem).
6. **[OCUP-10]** The system SHALL manter uma política de Row-Level Security na tabela de projeção
   (`municipio_id = current_setting('app.current_tenant')`), de forma que uma leitura sem
   `SET LOCAL` definido, ou com o tenant errado, não retorne linhas de outro município — mesmo que o
   código da aplicação omita um filtro explícito.

**Independent Test**: publicar manualmente uma mensagem JSON válida com routing key `vaga.liberada`
no RabbitMQ local (via management UI ou `rabbitmqadmin`) e verificar que a linha aparece/atualiza na
tabela de projeção do Postgres do `alocacreche-relatorios`, com `municipio_id` respeitando RLS.

---

### P2: Rejeitar mensagens malformadas para a dead-letter queue

**User Story**: Como MSRelatorios, quero que mensagens malformadas ou com falha persistente sejam
isoladas numa dead-letter queue, para que um payload inválido não fique reentregue para sempre nem
trave o processamento das mensagens válidas seguintes.

**Why P2**: Importante para resiliência em produção, mas não bloqueia a demonstração do caminho
feliz (P1).

**Acceptance Criteria**:

1. **[OCUP-06]** IF o payload não puder ser desserializado, ou faltar `municipioId`, `turmaId`,
   `eventId` ou `vagasDisponiveis` THEN o sistema SHALL rejeitar a mensagem diretamente para
   `relatorios.ocupacao.dlq`, sem tentativa de reprocessamento.
2. **[OCUP-07]** IF `vagasDisponiveis` vier negativo THEN o sistema SHALL tratar como payload
   inválido e rejeitar para a dead-letter queue (mesma regra do item 1).
3. **[OCUP-08]** IF o processamento falhar por uma causa transitória (ex.: banco indisponível) THEN
   o sistema SHALL fazer nack com requeue, permitindo nova tentativa.
4. **[OCUP-09]** WHEN uma mensagem falhar por 3 tentativas consecutivas por causa transitória THEN o
   sistema SHALL movê-la para `relatorios.ocupacao.dlq` em vez de continuar reentregando
   indefinidamente.

**Independent Test**: publicar uma mensagem com JSON inválido (ou faltando `turmaId`) e verificar
que ela aparece na fila `relatorios.ocupacao.dlq` imediatamente, sem reentrega.

---

## Edge Cases

- IF a fila/exchange ainda não existirem no broker THEN o sistema SHALL declará-los na
  inicialização (coberto por P1, AC5).
- IF `vagasDisponiveis` vier negativo THEN o sistema SHALL rejeitar para DLQ (coberto por P2, AC2).
- IF duas mensagens para turmas diferentes chegarem ao mesmo tempo THEN o sistema SHALL processá-las
  de forma independente (nenhuma trava global entre turmas distintas).

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status | Task(s) |
| --- | --- | --- | --- | --- |
| OCUP-01 | P1 | Tasks | Implementing | T1, T3, T5, T8 |
| OCUP-02 | P1 | Tasks | Implementing | T5 |
| OCUP-03 | P1 | Tasks | Implementing | T5 |
| OCUP-04 | P1 | Tasks | Implementing | T7 |
| OCUP-05 | P1 | Tasks | Implementing | T6, T8 |
| OCUP-06 | P2 | Tasks | Implementing | T2, T7, T8 |
| OCUP-07 | P2 | Tasks | Implementing | T2, T7 |
| OCUP-08 | P2 | Tasks | Implementing | T6, T8 |
| OCUP-09 | P2 | Tasks | Implementing | T6, T8 |
| OCUP-10 | P1 | Tasks | Implementing | T4, T5 |

**ID format:** `OCUP-NN` (Ocupação).

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 10 total, 10 mapped to tasks (ver tasks.md), 0 unmapped

---

## Success Criteria

- [ ] Publicar um evento `VagaLiberada` válido resulta numa linha de projeção correta no Postgres
      do `alocacreche-relatorios`, em menos de alguns segundos.
- [ ] Reenviar o mesmo evento (ou um com `eventId` menor) não altera a projeção.
- [ ] Um payload malformado aparece na DLQ, não na fila principal, e não trava mensagens seguintes.
- [ ] `mvn test` no módulo `alocacreche-relatorios` cobre os cenários acima e passa.

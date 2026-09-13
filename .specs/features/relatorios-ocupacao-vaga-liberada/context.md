# Consumo do evento VagaLiberada e projeção de ocupação — Context

**Gathered:** 2026-09-12
**Spec:** `.specs/features/relatorios-ocupacao-vaga-liberada/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Consumidor RabbitMQ que recebe o evento `VagaLiberada` e mantém uma projeção de ocupação por
turma no banco próprio do `alocacreche-relatorios`, de forma idempotente e resiliente a entrega
duplicada/fora de ordem. Só a ponta de escrita — nenhum endpoint HTTP de leitura faz parte desta
tarefa.

---

## Implementation Decisions

### Escopo do evento

- Consumir só `VagaLiberada` nesta tarefa. `MatriculaCriada` (relatório de confirmação de
  matrícula) fica para uma tarefa futura — consumidor e projeção separados.

### Fronteira escrita/leitura

- Só o consumidor + projeção. Nenhum endpoint HTTP (`consultarPainelPublico`,
  `relatorioOcupacao`) é criado nesta tarefa.

### Contrato do evento

- Payload JSON: `{eventId, tipoEvento, municipioId, turmaId, crecheId, vagasDisponiveis,
  ocorridoEm}`. `eventId` é o id auto-incremento da linha da Outbox no monólito — usado como
  sequência global para detectar entrega fora de ordem/duplicada.
- Este contrato ainda não tem um produtor implementado (o módulo Matrícula, no
  `alocacreche-core`, também não existe). Ele vale como a especificação que esse produtor deve
  seguir quando for implementado — não é uma decisão retroativa a um formato já existente.

### Topologia RabbitMQ

- Exchange topic `alocacreche.dominio`, routing key `vaga.liberada`, fila
  `relatorios.ocupacao`, dead-letter queue `relatorios.ocupacao.dlq` após 3 tentativas de
  reprocessamento por causa transitória.
- Declarados pelo próprio `alocacreche-relatorios` (declaração idempotente no broker — funciona
  mesmo que o produtor ainda não exista).

### Multi-tenancy

- Aplicar Row-Level Security no banco do MSRelatorios, mesmo sendo um banco separado do
  monólito — mesmo padrão de `docs/arquitetura/notas-tecnicas.md`. Motivo: o consumidor RabbitMQ
  processa eventos de todos os municípios num único processo, sem contexto de sessão — o mesmo
  cenário para o qual o padrão RLS + `SET LOCAL` já existe no projeto.

### Agent's Discretion

- Mecanismo exato de upsert concorrente (lock otimista JPA `@Version` vs. `INSERT ... ON
  CONFLICT` nativo) — decisão de Design, a spec só define o comportamento observável (nunca
  regredir para uma sequência mais antiga).
- Nome de classes/pacotes dentro de `alocacreche-relatorios` (ex.: `OcupacaoTurma`,
  `VagaLiberadaListener`) — segue as convenções já estabelecidas no restante do projeto
  (`CLAUDE.md`, se disponível localmente) e nos demais módulos.

### Declined / Undiscussed Gray Areas → Assumptions

- Nenhuma — todas as áreas cinzentas identificadas (escopo do evento, fronteira leitura/escrita,
  contrato do payload, topologia RabbitMQ, RLS) foram discutidas e decididas explicitamente com o
  usuário (ver spec.md, seção Assumptions & Open Questions, todas com `Confirmado? y`).

---

## Specific References

Nenhuma referência externa/produto específica trazida pelo usuário — decisões seguiram os
padrões já estabelecidos no projeto (ADR 0001, ADR 0002, `docs/arquitetura/notas-tecnicas.md`).

---

## Deferred Ideas

- Consumo do evento `MatriculaCriada` (relatório de confirmação de matrícula) — tarefa futura.
- Endpoint HTTP de leitura da projeção (`consultarPainelPublico` / `relatorioOcupacao`) — tarefa
  futura, depende desta.
- Cache Redis do painel público — só relevante quando o endpoint de leitura existir.

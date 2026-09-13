# STATE

## Decisions

### AD-001
- **Decision**: Consumidores RabbitMQ neste projeto usam o interceptor de retry do Spring Retry (stateless, 3 tentativas) com `RejectAndDontRequeueRecoverer`, mais uma dead-letter queue nativa do RabbitMQ (DLX declarado na fila principal). Exceções de payload inválido (ex.: `EventoInvalidoException`) ficam fora da política de retry — rejeição imediata, sem reentrega.
- **Reason**: Reusa `spring-retry`, já dependência do projeto (para `@Retryable` no módulo Matrícula); é o padrão documentado do Spring AMQP para "N tentativas, depois dead-letter", sem precisar de lógica de contagem manual.
- **Trade-off**: Acopla a política de retry à configuração do listener container (`RabbitMqConfig`), não ao código de negócio — quem adicionar um novo consumidor precisa replicar essa config, não só o `@RabbitListener`.
- **Scope**: Qualquer consumidor RabbitMQ futuro em `alocacreche-relatorios` ou `alocacreche-core` (ex.: consumidor de `MatriculaCriada`, consumidor de notificações no módulo Notificação).
- **Date**: 2026-09-12
- **Status**: active

### AD-002
- **Decision**: Políticas de Row-Level Security são provisionadas via `schema.sql` idempotente (`ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + `DROP POLICY IF EXISTS` + `CREATE POLICY`), com **`spring.sql.init.mode=always`** (não só `defer-datasource-initialization=true`) para rodar depois do `ddl-auto=update` criar a tabela.
- **Reason**: Não há ferramenta de migration (Flyway/Liquibase) adotada ainda (ver ADR 0002) — esta é a menor adição possível para ter RLS funcionando sem introduzir uma ferramenta nova fora do escopo da tarefa atual.
- **Trade-off**: `schema.sql` roda a cada restart da aplicação; overhead pequeno agora, mas não escala bem e precisa ser substituído quando uma ferramenta de migration for adotada.
- **Scope**: Qualquer entidade multi-tenant futura em `alocacreche-core` ou `alocacreche-relatorios` que precise de RLS, até que Flyway/Liquibase seja adotado (nesse ponto, `AD-002` deve ser superseded).
- **Date**: 2026-09-12
- **Status**: active
- **Gotchas descobertos na implementação (T4 de `relatorios-ocupacao-vaga-liberada`)**:
  1. `spring.jpa.defer-datasource-initialization=true` sozinho **não** é suficiente: `schema.sql` só roda automaticamente por padrão para banco embutido (`spring.sql.init.mode` default = `embedded`). Contra um Postgres real, é preciso `spring.sql.init.mode: always` explícito, ou `schema.sql` é silenciosamente ignorado — sem erro, sem log de aviso.
  2. Sem `FORCE ROW LEVEL SECURITY`, a policy **não tem efeito nenhum** nas queries da própria aplicação: por padrão o Postgres isenta o dono da tabela da RLS, e a aplicação conecta com a mesma role que o Hibernate usa para criar a tabela (logo, é a dona). Confirmado só via consulta direta a `pg_class.relforcerowsecurity` e `pg_policies` — o teste de contexto sobe verde mesmo com a RLS completamente inativa.

## Handoff

- **Feature**: relatorios-ocupacao-vaga-liberada
- **Phase / Task**: Tasks aprovado pelo gate (0 erros) — Execute ainda não começou
- **Completed**: none
- **In-progress**: nenhum arquivo de código ainda — só `.specs/features/relatorios-ocupacao-vaga-liberada/{spec,context,design,tasks}.md`
- **Next step**: Confirmar ferramentas por task (MCP/Skill) com o usuário, depois começar Execute por T1
- **Blockers**: none
- **Uncommitted files**: none (specs ainda não commitados)
- **Branch**: main

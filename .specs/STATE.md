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
- **Decision**: Políticas de Row-Level Security são provisionadas via `schema.sql` idempotente (`DROP POLICY IF EXISTS` + `CREATE POLICY`) com `spring.jpa.defer-datasource-initialization=true`, para rodar depois do `ddl-auto=update` criar a tabela.
- **Reason**: Não há ferramenta de migration (Flyway/Liquibase) adotada ainda (ver ADR 0002) — esta é a menor adição possível para ter RLS funcionando sem introduzir uma ferramenta nova fora do escopo da tarefa atual.
- **Trade-off**: `schema.sql` roda a cada restart da aplicação; overhead pequeno agora, mas não escala bem e precisa ser substituído quando uma ferramenta de migration for adotada.
- **Scope**: Qualquer entidade multi-tenant futura em `alocacreche-core` ou `alocacreche-relatorios` que precise de RLS, até que Flyway/Liquibase seja adotado (nesse ponto, `AD-002` deve ser superseded).
- **Date**: 2026-09-12
- **Status**: active

## Handoff

- **Feature**: relatorios-ocupacao-vaga-liberada
- **Phase / Task**: Tasks aprovado pelo gate (0 erros) — Execute ainda não começou
- **Completed**: none
- **In-progress**: nenhum arquivo de código ainda — só `.specs/features/relatorios-ocupacao-vaga-liberada/{spec,context,design,tasks}.md`
- **Next step**: Confirmar ferramentas por task (MCP/Skill) com o usuário, depois começar Execute por T1
- **Blockers**: none
- **Uncommitted files**: none (specs ainda não commitados)
- **Branch**: main

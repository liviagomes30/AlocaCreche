# STATE

## Decisions

### AD-001
- **Decision**: Consumidores RabbitMQ neste projeto usam o interceptor de retry do Spring Retry (stateless, 3 tentativas) com `RejectAndDontRequeueRecoverer`, mais uma dead-letter queue nativa do RabbitMQ (DLX declarado na fila principal). Exceções de payload inválido (ex.: `EventoInvalidoException`) ficam fora da política de retry — rejeição imediata, sem reentrega.
- **Reason**: Reusa `spring-retry`, já dependência do projeto (para `@Retryable` no módulo Matrícula); é o padrão documentado do Spring AMQP para "N tentativas, depois dead-letter", sem precisar de lógica de contagem manual.
- **Trade-off**: Acopla a política de retry à configuração do listener container (`RabbitMqConfig`), não ao código de negócio — quem adicionar um novo consumidor precisa replicar essa config, não só o `@RabbitListener`.
- **Scope**: Qualquer consumidor RabbitMQ futuro em `alocacreche-relatorios` ou `alocacreche-core` (ex.: consumidor de `MatriculaCriada`, consumidor de notificações no módulo Notificação).
- **Date**: 2026-09-12
- **Status**: active
- **Gotcha descoberto na implementação (T6 de `relatorios-ocupacao-vaga-liberada`)**: usar
  `org.springframework.amqp.rabbit.config.RetryInterceptorBuilder` (especifico do Spring AMQP),
  **não** `org.springframework.retry.interceptor.RetryInterceptorBuilder` (generico do Spring
  Retry) — o generico nao aceita `MessageRecoverer` (ex.: `RejectAndDontRequeueRecoverer`), so
  `MethodInvocationRecoverer`, e nao compila. Alem disso, `RabbitAdmin` **não** declara
  exchanges/filas/bindings no `ContextRefreshedEvent` como a documentação sugere — a declaração só
  acontece quando o `ConnectionFactory` cria sua primeira conexão real (via `ConnectionListener`),
  o que só acontece quando algo de fato usa o broker (um `@RabbitListener` ativo, ou uso do
  `RabbitTemplate`). Um `@SpringBootTest` que só sobe o contexto sem nenhum listener **não prova
  nada** sobre a topologia estar declarada no broker — só o teste de integração ponta a ponta (T8,
  com um listener real) verifica isso de fato.

### AD-002
- **Decision**: Row-Level Security é provisionada via `schema.sql` idempotente (`ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + `DROP POLICY IF EXISTS` + `CREATE POLICY`), com **`spring.sql.init.mode=always`** (não só `defer-datasource-initialization=true`), **e a aplicação conecta como uma role dedicada não-superuser** (`alocacreche_app`), nunca como o usuário bootstrap do container Postgres.
- **Reason**: Não há ferramenta de migration (Flyway/Liquibase) adotada ainda (ver ADR 0002) — esta é a menor adição possível para ter RLS funcionando sem introduzir uma ferramenta nova fora do escopo da tarefa atual.
- **Trade-off**: `schema.sql` roda a cada restart da aplicação; overhead pequeno agora, mas não escala bem e precisa ser substituído quando uma ferramenta de migration for adotada. A role dedicada exige um script de init no `docker-compose.yml` (e um `withInitScript` equivalente em qualquer teste com Testcontainers).
- **Scope**: Qualquer entidade multi-tenant futura em `alocacreche-core` ou `alocacreche-relatorios` que precise de RLS, até que Flyway/Liquibase seja adotado (nesse ponto, `AD-002` deve ser superseded).
- **Date**: 2026-09-12
- **Status**: active
- **Gotchas descobertos na implementação (T4/T5 de `relatorios-ocupacao-vaga-liberada`)** — os três juntos, na ordem em que se manifestam:
  1. `spring.jpa.defer-datasource-initialization=true` sozinho **não** é suficiente: `schema.sql` só roda automaticamente por padrão para banco embutido (`spring.sql.init.mode` default = `embedded`). Contra um Postgres real, é preciso `spring.sql.init.mode: always` explícito, ou `schema.sql` é silenciosamente ignorado — sem erro, sem log de aviso.
  2. Sem `FORCE ROW LEVEL SECURITY`, a policy não tem efeito nas queries da própria aplicação, porque por padrão o Postgres isenta o dono da tabela da RLS, e a aplicação conecta com a mesma role que o Hibernate usa para criar a tabela.
  3. **Mesmo com `FORCE ROW LEVEL SECURITY`, a policy continua sem efeito nenhum se a role de conexão for superuser** — superusers ignoram RLS incondicionalmente, e `FORCE` não consegue mudar isso. O usuário bootstrap de um container Postgres oficial (`POSTGRES_USER`) **é sempre criado como superuser** pela imagem, então conectar como ele (mesmo com `FORCE` no lugar) deixa a RLS completamente inerte. A correção real é conectar como uma role dedicada `NOSUPERUSER NOBYPASSRLS` (criada via script de init do banco/Testcontainers) — nunca como o usuário bootstrap. Sem essa role dedicada, os gotchas 1 e 2 podem estar 100% corrigidos e a RLS ainda não protege nada.
  4. Nenhum destes três é detectado pelo teste de contexto padrão (`@SpringBootTest` só verifica que a aplicação sobe) — só um teste que efetivamente tenta ler/escrever como dois tenants diferentes e verifica o resultado prova que a RLS está ativa. Verificação de schema (`pg_policies`, `relforcerowsecurity`) sozinha também não basta — prova que a policy existe, não que ela é aplicada à role de conexão real.

## Handoff

- **Feature**: relatorios-ocupacao-vaga-liberada
- **Phase / Task**: Tasks aprovado pelo gate (0 erros) — Execute ainda não começou
- **Completed**: none
- **In-progress**: nenhum arquivo de código ainda — só `.specs/features/relatorios-ocupacao-vaga-liberada/{spec,context,design,tasks}.md`
- **Next step**: Confirmar ferramentas por task (MCP/Skill) com o usuário, depois começar Execute por T1
- **Blockers**: none
- **Uncommitted files**: none (specs ainda não commitados)
- **Branch**: main

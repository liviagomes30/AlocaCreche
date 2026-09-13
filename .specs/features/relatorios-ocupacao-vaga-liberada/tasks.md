# Consumo do evento VagaLiberada e projeção de ocupação — Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its
Execute flow and Critical Rules.** Do not search for skill files by filesystem path. The skill is
the source of truth for the full flow (per-task cycle, sub-agent delegation, adequacy review,
Verifier, discrimination sensor).

**If the skill cannot be activated, STOP and tell the user - do not proceed without it.**

---

**Design**: `.specs/features/relatorios-ocupacao-vaga-liberada/design.md`
**Status**: Draft

---

## Test Coverage Matrix

> Gerado a partir do código existente (`AlocaCrecheRelatoriosApplicationTests.java`: JUnit 5 +
> Spring Boot Test; `pom.xml`: Testcontainers `junit-jupiter`/`postgresql`/`rabbitmq` já em escopo
> de teste) e do princípio de TDD do `CLAUDE.md` local (Red-Green-Refactor, teste antes do código).
> Sem AGENTS.md/CONTRIBUTING.md formal — aplicado o padrão forte (1:1 com AC do spec).

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| `VagaLiberadaListener` (validação de payload) | unit | Todos os ramos de validação (payload válido, campo faltando, `vagasDisponiveis` negativo) — 1:1 com OCUP-06/07 | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/VagaLiberadaListenerTest.java` | `mvn -pl alocacreche-relatorios test -Dtest=VagaLiberadaListenerTest` |
| `OcupacaoTurmaRepository` (upsert nativo) + RLS | integration (Testcontainers Postgres) | Upsert idempotente, ordem/duplicata, concorrência (OCUP-01/02/03) e isolamento por município via RLS (OCUP-10) | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepositoryIT.java` | `mvn -pl alocacreche-relatorios test -Dtest=OcupacaoTurmaRepositoryIT` |
| Fluxo ponta a ponta (RabbitMQ + Postgres reais) | integration (Testcontainers) | Caminho feliz completo (OCUP-01/04/05), DLQ imediato para payload malformado (OCUP-06), DLQ após 3 tentativas por falha transitória (OCUP-08/09) | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/VagaLiberadaConsumerIT.java` | `mvn -pl alocacreche-relatorios test -Dtest=VagaLiberadaConsumerIT` |
| `VagaLiberadaEvent`, `EventoInvalidoException`, `OcupacaoTurma`, `RabbitMqConfig`, `schema.sql` | none | Cobertos indiretamente pelas camadas acima | — | build gate only |

## Gate Check Commands

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Depois de T7 (teste unitário do listener) | `mvn -pl alocacreche-relatorios test -Dtest=VagaLiberadaListenerTest` |
| Full | Depois de T5 e T8 (Testcontainers — requer Docker rodando) | `mvn -pl alocacreche-relatorios test` |
| Build | Depois de completar a feature | `mvn clean install` (raiz do reactor) |

---

## Execution Plan

### Phase 1: Contratos e Fundação

```
T1 → T2
```

### Phase 2: Persistência e RLS

```
T3 → T4 → T5
```

### Phase 3: Mensageria

```
T6 → T7
```

### Phase 4: Integração ponta a ponta

```
T8
```

---

## Task Breakdown

### T1: Criar `VagaLiberadaEvent` (DTO) — ✅ Complete

**What**: Record Java com os campos do payload (`eventId`, `tipoEvento`, `municipioId`, `turmaId`,
`crecheId`, `vagasDisponiveis`, `ocorridoEm`).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/VagaLiberadaEvent.java`
**Depends on**: None
**Reuses**: —
**Requirement**: OCUP-01

**Tools**:
- MCP: NONE
- Skill: `java-development` (baseline Java/records)

**Done when**:
- [x] Record com todos os campos do contrato definido em `context.md`
- [x] Compila sem erro (`mvn -pl alocacreche-relatorios compile`)

**Tests**: none
**Gate**: build

---

### T2: Criar `EventoInvalidoException` — ✅ Complete

**What**: Exceção unchecked que sinaliza payload inválido (exclui da política de retry).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/EventoInvalidoException.java`
**Depends on**: T1
**Reuses**: `RuntimeException`
**Requirement**: OCUP-06, OCUP-07

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Classe estende `RuntimeException`, construtor `(String message)`
- [x] Compila sem erro

**Tests**: none
**Gate**: build

---

### T3: Criar entidade `OcupacaoTurma` — ✅ Complete

**What**: Entidade JPA da projeção — `turmaId` como `@Id` (chave de negócio), `municipioId`,
`crecheId`, `vagasDisponiveis`, `ultimaSequenciaAplicada`, `atualizadoEm`.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurma.java`
**Depends on**: None
**Reuses**: convenção Lombok de `OutboxEvent` (`alocacreche-core`)
**Requirement**: OCUP-01

**Tools**:
- MCP: NONE
- Skill: `jpa-patterns`

**Done when**:
- [x] Entidade mapeada para tabela `ocupacao_turma` com todos os campos do design
- [x] `mvn -pl alocacreche-relatorios test` sobe o contexto e cria a tabela via `ddl-auto=update` (reusa o smoke test já existente) — confirmado via `\d ocupacao_turma` no Postgres real

**Tests**: none
**Gate**: build

---

### T4: Provisionar RLS via `schema.sql` — ✅ Complete

**What**: `schema.sql` com `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` +
`DROP POLICY IF EXISTS` + `CREATE POLICY` (idempotente), e `spring.sql.init.mode=always` +
`spring.jpa.defer-datasource-initialization=true` no `application.yml` para rodar depois do
Hibernate criar a tabela.
**Where**: `alocacreche-relatorios/src/main/resources/schema.sql` (novo),
`alocacreche-relatorios/src/main/resources/application.yml` (modificar)
**Depends on**: T3
**Reuses**: padrão de `docs/arquitetura/notas-tecnicas.md` (`AD-002` em `.specs/STATE.md`)
**Requirement**: OCUP-10

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] `schema.sql` contém a policy exata do design.md (corrigido: `FORCE ROW LEVEL SECURITY` adicionado — ver AD-002)
- [x] `application.yml` tem `spring.jpa.defer-datasource-initialization: true` e `spring.sql.init.mode: always` (o segundo não estava no design original — sem ele, `schema.sql` é ignorado silenciosamente contra Postgres real; ver AD-002)
- [x] `mvn -pl alocacreche-relatorios test` sobe o contexto sem erro (reusa o smoke test) — schema/policy confirmados via `pg_class`/`pg_policies` nesta task. **Correção feita em T5**: essa verificação sozinha não provava que a RLS funcionava — a aplicação ainda conectava como o usuário bootstrap (superuser, ignora RLS incondicionalmente). Ver AD-002, gotcha 3, e a role dedicada `alocacreche_app` criada em T5.

**Tests**: none
**Gate**: build

---

### T5: Implementar `OcupacaoTurmaRepository.upsert` + teste de integração — ✅ Complete

**What**: Método `@Modifying @Query(nativeQuery = true)` com o `INSERT ... ON CONFLICT ... WHERE`
do design, e teste de integração (Testcontainers Postgres) cobrindo upsert idempotente, ordem/
duplicata, concorrência e isolamento por RLS.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepository.java`,
`alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepositoryIT.java`
**Depends on**: T4
**Reuses**: `org.testcontainers:postgresql` (já dependência de teste do módulo)
**Requirement**: OCUP-01, OCUP-02, OCUP-03, OCUP-10

**Tools**:
- MCP: NONE
- Skill: `jpa-patterns`, `test-quality`, `concurrency-review` (para o teste de upsert concorrente)

**Done when**:
- [x] Upsert cria linha nova quando `turmaId` não existe
- [x] Upsert atualiza quando `eventId` novo é maior que o aplicado
- [x] Upsert é no-op quando `eventId` recebido é menor ou igual ao aplicado (idempotência/ordem)
- [x] Teste de concorrência: duas chamadas simultâneas para o mesmo `turmaId` com sequências
      diferentes — a projeção final reflete só a maior sequência
- [x] Teste de RLS: gravar com `SET LOCAL app.current_tenant = X`, ler com `SET LOCAL` = Y — zero
      linhas retornadas para o município errado. **Achado real durante a implementação**: a
      primeira versão falhou porque a aplicação conectava como o usuário bootstrap do Postgres
      (superuser, ignora RLS incondicionalmente mesmo com `FORCE`). Corrigido criando a role
      dedicada `alocacreche_app` (`docker/postgres-relatorios-init/01-app-role.sql` +
      `db/relatorios-app-role.sql` para o Testcontainers) — ver AD-002, gotcha 3. Reverificado
      também contra o Postgres real do `docker-compose` (não só o Testcontainers efêmero).
- [x] Gate check passa: `mvn -pl alocacreche-relatorios test -Dtest=OcupacaoTurmaRepositoryIT`
- [x] Contagem de testes: 5 testes passam

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): upsert idempotente e RLS na projecao de ocupacao`

---

### T6: Declarar topologia RabbitMQ (`RabbitMqConfig`) — ✅ Complete

**What**: Beans do exchange topic `alocacreche.dominio`, fila `relatorios.ocupacao` (com DLX),
fila `relatorios.ocupacao.dlq`, bindings, `Jackson2JsonMessageConverter`, e o
`SimpleRabbitListenerContainerFactory` com o interceptor de retry (3 tentativas,
`EventoInvalidoException` excluída, `RejectAndDontRequeueRecoverer`).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java`
**Depends on**: None
**Reuses**: `spring-retry` (já dependência via parent), padrão `AD-001` em `.specs/STATE.md`
**Requirement**: OCUP-05, OCUP-08, OCUP-09

**Tools**:
- MCP: NONE
- Skill: `spring-boot-patterns`

**Done when**:
- [x] Todos os beans do design declarados (exchange, 2 filas, 2 bindings, converter, container factory)
- [x] `mvn -pl alocacreche-relatorios test` sobe o contexto sem erro (reusa o smoke test) — bean
      wiring confirmado sem exceção via `rabbitAdmin.initialize()` manual num teste descartável
      (removido depois). **Achado**: `RabbitAdmin` só declara no broker quando o `ConnectionFactory`
      cria a primeira conexão real (via `ConnectionListener`), não no `ContextRefreshedEvent` como a
      doc sugere — sem um `@RabbitListener` ativo (T7), nada força essa conexão. Ver AD-001. A
      declaração real no broker só é verificada de fato em T8.

**Tests**: none
**Gate**: build

---

### T7: Implementar `VagaLiberadaListener` + teste unitário — ✅ Complete

**What**: `@RabbitListener` + `@Transactional` que valida o payload (lança
`EventoInvalidoException` se inválido), aplica `SET LOCAL app.current_tenant` via `EntityManager`,
e delega o upsert ao repositório. Teste unitário mockando o repositório, cobrindo os ramos de
validação.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/VagaLiberadaListener.java`,
`alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/VagaLiberadaListenerTest.java`
**Depends on**: T1, T2, T5, T6
**Reuses**: `EntityManager` nativo (padrão já usado em `sb-multi-tenancy-rls.md`)
**Requirement**: OCUP-04, OCUP-06, OCUP-07

**Tools**:
- MCP: NONE
- Skill: `test-quality`

**Done when**:
- [x] Payload válido: chama `SET LOCAL` com o `municipioId` correto e delega ao repositório
- [x] Payload com campo obrigatório faltando: lança `EventoInvalidoException`, repositório NUNCA é chamado
- [x] `vagasDisponiveis` negativo: lança `EventoInvalidoException`, repositório NUNCA é chamado
- [x] Gate check passa: `mvn -pl alocacreche-relatorios test -Dtest=VagaLiberadaListenerTest`
- [x] Contagem de testes: 3 testes passam

**Tests**: unit
**Gate**: quick

**Commit**: `feat(relatorios): listener do evento VagaLiberada com validacao e RLS`

---

### T8: Teste de integração ponta a ponta (RabbitMQ + Postgres reais)

**What**: Teste com Testcontainers (RabbitMQ + Postgres) publicando mensagens reais na fila
`relatorios.ocupacao` e verificando o comportamento fim a fim: upsert bem-sucedido, payload
malformado indo direto para a DLQ, e falha transitória simulada indo para a DLQ após 3 tentativas.
**Where**: `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/VagaLiberadaConsumerIT.java`
**Depends on**: T7
**Reuses**: `org.testcontainers:rabbitmq` e `org.testcontainers:postgresql` (já dependências de
teste do módulo)
**Requirement**: OCUP-01, OCUP-04, OCUP-05, OCUP-06, OCUP-08, OCUP-09

**Tools**:
- MCP: NONE
- Skill: `test-quality`

**Done when**:
- [ ] Publicar `VagaLiberada` válido → linha aparece na projeção (verificado via repositório)
- [ ] Publicar payload malformado (JSON inválido ou campo faltando) → mensagem aparece em
      `relatorios.ocupacao.dlq` imediatamente, sem reentrega
- [ ] Simular falha transitória (ex.: repositório lança exceção não-`EventoInvalidoException` nas 3
      primeiras tentativas) → mensagem aparece em `relatorios.ocupacao.dlq` só após a 3ª tentativa
- [ ] Gate check passa: `mvn -pl alocacreche-relatorios test -Dtest=VagaLiberadaConsumerIT`
- [ ] Contagem de testes: 3 testes passam

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): teste ponta a ponta do consumidor VagaLiberada`

---

## Phase Execution Map

```
Phase 1 → Phase 2 → Phase 3 → Phase 4

Phase 1:  T1 ------→ T2
Phase 2:  T3 ------→ T4 ------→ T5
Phase 3:  T6 ------→ T7
Phase 4:  T8

Dependências entre fases (backward, nunca forward):
T1 ------------------------------→ T7
T2 ------------------------------→ T7
T5 ------------------------------→ T7
T7 ------------------------------→ T8
```

Execução é estritamente sequencial — sem paralelismo intra-fase. 8 tarefas totais, cabe num único
batch (≤ ~8) — sem necessidade de sub-agentes.

---

## Task Granularity Check

| Task | Scope | Status |
| --- | --- | --- |
| T1: Criar `VagaLiberadaEvent` | 1 arquivo (record) | ✅ Granular |
| T2: Criar `EventoInvalidoException` | 1 arquivo | ✅ Granular |
| T3: Criar entidade `OcupacaoTurma` | 1 arquivo | ✅ Granular |
| T4: RLS via `schema.sql` | 2 arquivos, uma decisão coesa (provisionar RLS) | ✅ Granular (coeso) |
| T5: `OcupacaoTurmaRepository.upsert` + teste | 1 componente + seu teste co-localizado | ✅ Granular |
| T6: `RabbitMqConfig` | 1 arquivo (config) | ✅ Granular |
| T7: `VagaLiberadaListener` + teste | 1 componente + seu teste co-localizado | ✅ Granular |
| T8: Teste de integração ponta a ponta | 1 arquivo de teste | ✅ Granular |

---

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| --- | --- | --- | --- |
| T1 | None | (início da Phase 1) | ✅ Match |
| T2 | T1 | T1 → T2 | ✅ Match |
| T3 | None | (início da Phase 2) | ✅ Match |
| T4 | T3 | T3 → T4 | ✅ Match |
| T5 | T4 | T4 → T5 | ✅ Match |
| T6 | None | (início da Phase 3) | ✅ Match |
| T7 | T1, T2, T5, T6 | T6 → T7 (intra-fase) + arrows T1→T7, T2→T7, T5→T7 (backward, seção "Dependências entre fases") | ✅ Match |
| T8 | T7 | T7 → T8 (seção "Dependências entre fases") | ✅ Match |

Nenhuma dependência aponta para uma fase posterior.

---

## Test Co-location Validation

| Task | Code Layer Created/Modified | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1: `VagaLiberadaEvent` | DTO/record | none | none | ✅ OK |
| T2: `EventoInvalidoException` | Exception | none | none | ✅ OK |
| T3: `OcupacaoTurma` | Entity | none | none | ✅ OK |
| T4: `schema.sql` + config | Config/schema | none | none | ✅ OK |
| T5: `OcupacaoTurmaRepository` | Repository/data-access | integration | integration | ✅ OK |
| T6: `RabbitMqConfig` | Config | none | none | ✅ OK |
| T7: `VagaLiberadaListener` | Domain/orquestração (mais alto: listener é lógica de negócio) | unit | unit | ✅ OK |
| T8: teste ponta a ponta | Integração/e2e do fluxo completo | integration | integration | ✅ OK |

Nenhuma violação — todas as camadas com teste obrigatório têm o teste co-localizado na mesma task
que cria o código.

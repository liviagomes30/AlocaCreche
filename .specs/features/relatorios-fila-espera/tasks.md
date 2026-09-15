# Consumo do evento FilaEsperaAtualizada e relatorioFilaEspera — Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its
Execute flow and Critical Rules.** Do not search for skill files by filesystem path. The skill is
the source of truth for the full flow (per-task cycle, sub-agent delegation, adequacy review,
Verifier, discrimination sensor).

**If the skill cannot be activated, STOP and tell the user - do not proceed without it.**

---

**Design**: `.specs/features/relatorios-fila-espera/design.md`
**Status**: Draft

---

## Test Coverage Matrix

> Gerado a partir do código existente (mesmo padrão de `relatorios-ocupacao-vaga-liberada/tasks.md`:
> JUnit 5 + Spring Boot Test; Testcontainers Postgres/RabbitMQ já em escopo de teste do módulo) e do
> princípio de TDD do `CLAUDE.md` local. Sem AGENTS.md/CONTRIBUTING.md formal — aplicado o padrão
> forte (1:1 com AC do spec).

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| `FilaEsperaAtualizadaListener` (validação de payload) | unit | Todos os ramos de validação (payload válido, cada campo obrigatório faltando, `dataSaida` anterior a `dataEntrada`) — 1:1 com FILA-07/08 | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/FilaEsperaAtualizadaListenerTest.java` | `mvn -pl alocacreche-relatorios test -Dtest=FilaEsperaAtualizadaListenerTest` |
| `FilaEsperaInscricaoRepository` (upsert nativo + agregação) + RLS | integration (Testcontainers Postgres) | Upsert idempotente, ordem/duplicata (incluindo fronteira `eventId` igual — lição L-001), concorrência e isolamento por RLS (FILA-01/02/03/06); agregação por turma — contagem e média, turma sem evento ausente da lista (FILA-10/14/15) | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/FilaEsperaInscricaoRepositoryIT.java` | `mvn -pl alocacreche-relatorios verify -Dit.test=FilaEsperaInscricaoRepositoryIT` |
| Fluxo ponta a ponta do consumidor (RabbitMQ + Postgres reais) | integration (Testcontainers) | Caminho feliz completo (FILA-01/04/05), DLQ imediato para payload malformado e para `dataSaida` < `dataEntrada` (FILA-07/08), DLQ após 3 tentativas por falha transitória (FILA-09) — rejeição imediata provada por contagem de invocação do listener, não só chegada na DLQ (lições L-002/L-005) | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/FilaEsperaAtualizadaConsumerIT.java` | `mvn -pl alocacreche-relatorios verify -Dit.test=FilaEsperaAtualizadaConsumerIT` |
| `RelatorioFilaEsperaController` (endpoint de leitura) | integration (Testcontainers Postgres, `@SpringBootTest` + `MockMvc`/`WebTestClient`) | `municipioId` obrigatório → 400 sem tocar repositório (FILA-11), filtros opcionais `crecheId`/`turmaId` (FILA-10), RLS aplicada antes da consulta (FILA-12), resposta como DTO (FILA-13) | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/RelatorioFilaEsperaControllerIT.java` | `mvn -pl alocacreche-relatorios verify -Dit.test=RelatorioFilaEsperaControllerIT` |
| `FilaEsperaAtualizadaEvent`, `FilaEsperaEventoInvalidoException`, `FilaEsperaInscricao`, `RabbitMqConfig` (alteração), `RelatorioFilaEsperaDTO`, `RelatorioFilaEsperaProjection`, `schema.sql` (alteração) | none | Cobertos indiretamente pelas camadas acima | — | build gate only |

## Gate Check Commands

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Depois de T7 (teste unitário do listener) | `mvn -pl alocacreche-relatorios test -Dtest=FilaEsperaAtualizadaListenerTest` |
| Full | Depois de T5, T8 e T9 (Testcontainers — requer Docker rodando) | `mvn -pl alocacreche-relatorios verify` (**não** `test` — `*IT.java` só roda via `maven-failsafe-plugin`, já ligado às fases `integration-test`/`verify` desde `relatorios-ocupacao-vaga-liberada`; ver AD-003) |
| Build | Depois de completar a feature | `mvn clean verify` (raiz do reactor) |

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

### Phase 4: Integração ponta a ponta (consumidor)

```
T8
```

### Phase 5: Endpoint de leitura

```
T9
```

---

## Task Breakdown

### T1: Criar `FilaEsperaAtualizadaEvent` (DTO)

**What**: Record Java com os campos do payload (`eventId`, `tipoEvento`, `municipioId`, `turmaId`,
`crecheId`, `inscricaoId`, `dataEntrada`, `dataSaida`, `ocorridoEm`) — `dataEntrada`/`dataSaida`
como `LocalDate`.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/FilaEsperaAtualizadaEvent.java`
**Depends on**: None
**Reuses**: —
**Requirement**: FILA-01

**Tools**:
- MCP: NONE
- Skill: `java-development` (baseline Java/records)

**Done when**:
- [x] Record com todos os campos do contrato definido no spec/design
- [x] Compila sem erro (`mvn -pl alocacreche-relatorios compile`)

**Tests**: none
**Gate**: build

---

### T2: Criar `FilaEsperaEventoInvalidoException`

**What**: Exceção unchecked que sinaliza payload inválido (campo faltando ou `dataSaida` anterior a
`dataEntrada`) — exclui a mensagem da política de retry ao estender a classe-base compartilhada.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/FilaEsperaEventoInvalidoException.java`
**Depends on**: T1
**Reuses**: `org.alocacreche.relatorios.evento.EventoDominioInvalidoException`
**Requirement**: FILA-07, FILA-08

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Classe estende `EventoDominioInvalidoException`, construtor `(String message)`
- [x] Compila sem erro
- [x] Nenhuma edição em `RabbitMqConfig.retryableExceptions()` necessária — herda a classificação
      não-retryable pela classe-base (confirmar lendo o código, não assumir)

**Tests**: none
**Gate**: build

---

### T3: Criar entidade `FilaEsperaInscricao`

**What**: Entidade JPA da projeção — `inscricaoId` como `@Id` (chave de negócio), `turmaId`,
`crecheId`, `municipioId`, `dataEntrada`, `dataSaida` (nullable), `ultimaSequenciaAplicada`,
`atualizadoEm`.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/FilaEsperaInscricao.java`
**Depends on**: None
**Reuses**: convenção de `OcupacaoTurma` (sem setters, só mutada via upsert nativo)
**Requirement**: FILA-01

**Tools**:
- MCP: NONE
- Skill: `jpa-patterns`

**Done when**:
- [x] Entidade mapeada para tabela `fila_espera_inscricao` com todos os campos do design
- [x] `mvn -pl alocacreche-relatorios test` sobe o contexto e cria a tabela via `ddl-auto=update`
      (reusa o smoke test já existente) — confirmar via `\d fila_espera_inscricao` no Postgres real

**Tests**: none
**Gate**: build

---

### T4: Provisionar RLS da nova tabela via `schema.sql`

**What**: Acrescentar ao `schema.sql` existente o mesmo bloco idempotente (`ENABLE`/`FORCE ROW
LEVEL SECURITY` + `DROP POLICY IF EXISTS` + `CREATE POLICY`) para `fila_espera_inscricao`.
**Where**: `alocacreche-relatorios/src/main/resources/schema.sql` (modificar — mesmo arquivo de
`ocupacao_turma`, não um arquivo novo)
**Depends on**: T3
**Reuses**: padrão já provisionado para `ocupacao_turma` (`AD-002` em `.specs/STATE.md`);
`spring.sql.init.mode=always` e `spring.jpa.defer-datasource-initialization=true` já configurados
em `application.yml` — nenhuma mudança de configuração necessária, só o SQL
**Requirement**: FILA-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] `schema.sql` contém a policy exata do `design.md` para `fila_espera_inscricao`, adicionada ao
      final do arquivo existente (bloco de `ocupacao_turma` intacto)
- [x] `mvn -pl alocacreche-relatorios test` sobe o contexto sem erro — schema/policy confirmados via
      `pg_class`/`pg_policies`
- [x] Confirmar que a aplicação já conecta como a role dedicada `alocacreche_app` (criada em
      `relatorios-ocupacao-vaga-liberada`, T5) — nenhuma role nova necessária, mas a policy só tem
      efeito se essa role já estiver em uso (AD-002, gotcha 3)

**Tests**: none
**Gate**: build

---

### T5: Implementar `FilaEsperaInscricaoRepository` (upsert + agregação) + teste de integração

**What**: Método `upsert` (`@Modifying @Query(nativeQuery = true)`, `INSERT ... ON CONFLICT
(inscricao_id) DO UPDATE ... WHERE`) e método `buscarRelatorio` (`GROUP BY turma_id, creche_id,
municipio_id` retornando `RelatorioFilaEsperaProjection`), e teste de integração (Testcontainers
Postgres) cobrindo upsert idempotente, ordem/duplicata (incluindo a fronteira de igualdade — lição
candidata L-001), concorrência, RLS, e a agregação (contagem/média, turma sem evento ausente).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/FilaEsperaInscricaoRepository.java`,
`alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/RelatorioFilaEsperaProjection.java`,
`alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/FilaEsperaInscricaoRepositoryIT.java`
**Depends on**: T4
**Reuses**: `org.testcontainers:postgresql` (já dependência de teste do módulo), padrão de query
nativa de `OcupacaoTurmaRepository`
**Requirement**: FILA-01, FILA-02, FILA-03, FILA-06, FILA-10, FILA-14, FILA-15

**Tools**:
- MCP: NONE
- Skill: `jpa-patterns`, `test-quality`, `concurrency-review` (para o teste de upsert concorrente)

**Done when**:
- [x] Upsert cria linha nova quando `inscricaoId` não existe
- [x] Upsert atualiza quando `eventId` novo é maior que o aplicado
- [x] Upsert é no-op quando `eventId` recebido é igual ou menor ao aplicado (idempotência/ordem —
      testar explicitamente a igualdade, não só o menor, ver lição candidata L-001)
- [x] Teste de concorrência: duas chamadas simultâneas para a mesma `inscricaoId` com sequências
      diferentes — a projeção final reflete só a maior sequência
- [x] Teste de RLS: gravar com `SET LOCAL app.current_tenant = X`, ler com `SET LOCAL` = Y — zero
      linhas retornadas para o município errado
- [x] `buscarRelatorio`: turma com 1 inscrição aguardando (`dataSaida` nula) retorna
      `quantidadeAguardando=1` e `tempoMedioEsperaDias` maior que zero
- [x] `buscarRelatorio`: turma sem nenhuma linha não aparece no resultado (FILA-15)
- [x] `buscarRelatorio`: filtro por `crecheId`/`turmaId` opcional restringe corretamente
- [x] Gate check passa: `mvn -pl alocacreche-relatorios verify -Dit.test=FilaEsperaInscricaoRepositoryIT`

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): upsert idempotente e agregacao da projecao de fila de espera`

---

### T6: Declarar topologia RabbitMQ da fila de espera (`RabbitMqConfig`)

**What**: Adicionar constantes e beans para a fila `relatorios.filaespera` (com DLX) e
`relatorios.filaespera.dlq`, e os bindings correspondentes ao exchange `alocacreche.dominio`/DLX já
declarados — reusando `messageConverter()` e `rabbitListenerContainerFactory(...)` existentes sem
alteração.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java`
(modificar)
**Depends on**: None
**Reuses**: `dominioExchange()`, `dominioDlx()`, `messageConverter()`,
`rabbitListenerContainerFactory(...)`, `retryInterceptor()` já existentes; padrão `AD-001`
**Requirement**: FILA-05, FILA-09

**Tools**:
- MCP: NONE
- Skill: `spring-boot-patterns`

**Done when**:
- [x] Novos beans do design declarados (2 filas, 2 bindings) — exchange/DLX/converter/container
      factory reaproveitados, não duplicados
- [x] `mvn -pl alocacreche-relatorios test` sobe o contexto sem erro (reusa o smoke test)
- [x] Confirmar (lendo o código, não assumindo) que `retryableExceptions()` já cobre
      `FilaEsperaEventoInvalidoException` pela classe-base — nenhuma edição necessária nesse método

**Tests**: none
**Gate**: build

---

### T7: Implementar `FilaEsperaAtualizadaListener` + teste unitário

**What**: `@RabbitListener` + `@Transactional` que valida o payload (campos obrigatórios e
consistência `dataSaida`/`dataEntrada`), aplica `SET LOCAL app.current_tenant` via `EntityManager`,
e delega o upsert ao repositório. Teste unitário mockando o repositório, cobrindo todos os ramos de
validação.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/FilaEsperaAtualizadaListener.java`,
`alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/FilaEsperaAtualizadaListenerTest.java`
**Depends on**: T1, T2, T5, T6
**Reuses**: `EntityManager` nativo via `TenantContext.aplicar` (mesmo padrão de `VagaLiberadaListener`)
**Requirement**: FILA-01, FILA-04, FILA-07, FILA-08

**Tools**:
- MCP: NONE
- Skill: `test-quality`

**Done when**:
- [x] Payload válido (com e sem `dataSaida`): chama `SET LOCAL` com o `municipioId` correto e
      delega ao repositório
- [x] Cada campo obrigatório faltando (`eventId`, `municipioId`, `turmaId`, `crecheId`,
      `inscricaoId`, `dataEntrada`) — um teste por campo, lança `FilaEsperaEventoInvalidoException`,
      repositório NUNCA é chamado
- [x] `dataSaida` anterior a `dataEntrada`: lança `FilaEsperaEventoInvalidoException`, repositório
      NUNCA é chamado
- [x] Gate check passa: `mvn -pl alocacreche-relatorios test -Dtest=FilaEsperaAtualizadaListenerTest`

**Tests**: unit
**Gate**: quick

**Commit**: `feat(relatorios): listener do evento FilaEsperaAtualizada com validacao e RLS`

---

### T8: Teste de integração ponta a ponta do consumidor (RabbitMQ + Postgres reais)

**What**: Teste com Testcontainers (RabbitMQ + Postgres) publicando mensagens reais na fila
`relatorios.filaespera` e verificando o comportamento fim a fim: upsert bem-sucedido, payload
malformado e `dataSaida` < `dataEntrada` indo direto para a DLQ (rejeição imediata provada por
contagem de invocação do listener, não só chegada na DLQ), e falha transitória simulada indo para a
DLQ após 3 tentativas.
**Where**: `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/FilaEsperaAtualizadaConsumerIT.java`
**Depends on**: T7
**Reuses**: `org.testcontainers:rabbitmq` e `org.testcontainers:postgresql` (já dependências de
teste do módulo), padrão `@MockitoSpyBean` no listener já usado em `VagaLiberadaConsumerIT`
**Requirement**: FILA-01, FILA-04, FILA-05, FILA-07, FILA-08, FILA-09

**Tools**:
- MCP: NONE
- Skill: `test-quality`

**Done when**:
- [x] Publicar `FilaEsperaAtualizada` válido → linha aparece na projeção (verificado via repositório)
- [x] Publicar payload malformado (JSON inválido ou campo faltando) → mensagem aparece em
      `relatorios.filaespera.dlq` imediatamente, sem reentrega — confirmado via
      `@MockitoSpyBean` no listener, `verify(times(0))` ou equivalente que distingue rejeição
      imediata de retry (ver lições candidatas L-002/L-005; para JSON malformado, a exceção
      acontece na conversão antes do listener ser invocado — 0 invocações é o sinal correto aqui)
- [x] Publicar payload com `dataSaida` anterior a `dataEntrada` → mensagem aparece em
      `relatorios.filaespera.dlq` imediatamente, sem reentrega — mesma verificação de invocação
      única do listener, mas neste caso a exceção acontece DENTRO do `receber()`, então o sinal
      correto é `verify(times(1))`, não `times(0))` (distinguir os dois casos explicitamente)
- [x] Simular falha transitória (repositório lança exceção não-`FilaEsperaEventoInvalidoException`
      nas 3 primeiras tentativas) → mensagem aparece em `relatorios.filaespera.dlq` só após a 3ª
      tentativa — confirmado via `@MockitoSpyBean` + `verify(repository, times(3))`
- [x] Gate check passa: `mvn -pl alocacreche-relatorios verify -Dit.test=FilaEsperaAtualizadaConsumerIT`

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): teste ponta a ponta do consumidor FilaEsperaAtualizada`

---

### T9: Implementar `RelatorioFilaEsperaController` (+ DTO) + teste de integração

**What**: `RelatorioFilaEsperaDTO` (record de resposta), e `@RestController` com
`GET /api/v1/relatorios/fila-espera` — `municipioId` obrigatório (HTTP 400 automático quando
ausente, sem tocar `TenantContext` nem o repositório), `crecheId`/`turmaId` opcionais, aplica `SET
LOCAL` via `TenantContext.aplicar`, mapeia `RelatorioFilaEsperaProjection` para o DTO. Teste de
integração cobrindo os cenários da spec, incluindo duas chamadas consecutivas confirmando que
`tempoMedioEsperaDias` não diminui (FILA-14).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/RelatorioFilaEsperaDTO.java`,
`alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/filaespera/RelatorioFilaEsperaController.java`,
`alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/filaespera/RelatorioFilaEsperaControllerIT.java`
**Depends on**: T5
**Reuses**: estrutura de `OcupacaoConsultaController`, `TenantContext.aplicar`
**Requirement**: FILA-10, FILA-11, FILA-12, FILA-13, FILA-14, FILA-15

**Tools**:
- MCP: NONE
- Skill: `spring-boot-patterns`, `test-quality`

**Done when**:
- [ ] Com uma linha de projeção já criada (inscrição aguardando, sem `dataSaida`), chamar o
      endpoint com `municipioId` retorna `quantidadeAguardando=1` e `tempoMedioEsperaDias` maior
      que zero
- [ ] Chamar sem `municipioId` retorna HTTP 400 — confirmar (via spy/mock no repositório) que o
      repositório NUNCA é consultado nesse caso (FILA-11 exige "sem consultar o banco", não só o
      código de status)
- [ ] Filtro por `crecheId`/`turmaId` restringe corretamente a lista retornada
- [ ] Turma sem nenhum evento não aparece na lista (FILA-15)
- [ ] Chamar duas vezes seguidas sem nenhum evento novo processado entre elas: `tempoMedioEsperaDias`
      não diminui entre as duas chamadas (FILA-14) — teste com um pequeno `Thread.sleep`/tempo
      controlado se necessário para tornar a diferença observável
- [ ] Resposta é `RelatorioFilaEsperaDTO`, nunca a entidade/projeção diretamente (inspecionar o
      corpo da resposta HTTP, não só o tipo de retorno do método Java)
- [ ] RLS aplicada antes da consulta — reusa a mesma prova de isolamento por tenant de T5 (linha de
      outro município não aparece na resposta)
- [ ] Gate check passa: `mvn -pl alocacreche-relatorios verify -Dit.test=RelatorioFilaEsperaControllerIT`

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): endpoint GET /api/v1/relatorios/fila-espera`

---

## Phase Execution Map

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5

Phase 1:  T1 ------→ T2
Phase 2:  T3 ------→ T4 ------→ T5
Phase 3:  T6 ------→ T7
Phase 4:  T8
Phase 5:  T9

Dependências entre fases (backward, nunca forward):
T1 ------------------------------→ T7
T2 ------------------------------→ T7
T5 ------------------------------→ T7
T5 ------------------------------→ T9
T6 ------------------------------→ T7
T7 ------------------------------→ T8
```

Execução é estritamente sequencial — sem paralelismo intra-fase. 9 tarefas totais, excede o
orçamento de um único batch (≤ ~8) — oferecer sub-agentes ao usuário antes de iniciar o Execute
(um worker por batch de fases consecutivas somando ~7 tarefas: ex. Phases 1-3, ~7 tarefas, e
Phases 4-5, ~2 tarefas).

---

## Task Granularity Check

| Task | Scope | Status |
| --- | --- | --- |
| T1: Criar `FilaEsperaAtualizadaEvent` | 1 arquivo (record) | ✅ Granular |
| T2: Criar `FilaEsperaEventoInvalidoException` | 1 arquivo | ✅ Granular |
| T3: Criar entidade `FilaEsperaInscricao` | 1 arquivo | ✅ Granular |
| T4: RLS via `schema.sql` | 1 arquivo modificado, uma decisão coesa (provisionar RLS) | ✅ Granular (coeso) |
| T5: `FilaEsperaInscricaoRepository` (upsert + agregação) + teste | 2 arquivos de produção (repositório + projeção) + seu teste co-localizado, uma decisão coesa (acesso a dados da mesma entidade) | ✅ Granular (coeso) |
| T6: `RabbitMqConfig` (alteração) | 1 arquivo | ✅ Granular |
| T7: `FilaEsperaAtualizadaListener` + teste | 1 componente + seu teste co-localizado | ✅ Granular |
| T8: Teste de integração ponta a ponta do consumidor | 1 arquivo de teste | ✅ Granular |
| T9: `RelatorioFilaEsperaController` (+ DTO) + teste | 2 arquivos de produção (DTO + controller) + seu teste co-localizado, uma decisão coesa (endpoint de leitura) | ✅ Granular (coeso) |

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
| T9 | T5 | T5 → T9 (seção "Dependências entre fases") | ✅ Match |

Nenhuma dependência aponta para uma fase posterior.

---

## Test Co-location Validation

| Task | Code Layer Created/Modified | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1: `FilaEsperaAtualizadaEvent` | DTO/record | none | none | ✅ OK |
| T2: `FilaEsperaEventoInvalidoException` | Exception | none | none | ✅ OK |
| T3: `FilaEsperaInscricao` | Entity | none | none | ✅ OK |
| T4: `schema.sql` (alteração) | Config/schema | none | none | ✅ OK |
| T5: `FilaEsperaInscricaoRepository` + `RelatorioFilaEsperaProjection` | Repository/data-access | integration | integration | ✅ OK |
| T6: `RabbitMqConfig` (alteração) | Config | none | none | ✅ OK |
| T7: `FilaEsperaAtualizadaListener` | Domain/orquestração (listener é lógica de negócio) | unit | unit | ✅ OK |
| T8: teste ponta a ponta do consumidor | Integração/e2e do fluxo de mensageria | integration | integration | ✅ OK |
| T9: `RelatorioFilaEsperaController` + `RelatorioFilaEsperaDTO` | Web/controller | integration | integration | ✅ OK |

Nenhuma violação — todas as camadas com teste obrigatório têm o teste co-localizado na mesma task
que cria o código.

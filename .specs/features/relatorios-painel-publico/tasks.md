# Painel público de ocupação (MSRelatorios) Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its Execute
flow and Critical Rules.** Do not search for skill files by filesystem path. The skill is the source of
truth for the full flow (per-task cycle, sub-agent delegation, adequacy review, Verifier, discrimination
sensor).

**If the skill cannot be activated, STOP and tell the user - do not proceed without it.**

---

**Design**: `.specs/features/relatorios-painel-publico/design.md`
**Status**: Draft

---

## Test Coverage Matrix

> Gerado a partir do código existente (mesmo padrão de `OcupacaoTurmaRepositoryIT`/
> `VagaLiberadaConsumerIT`: testes de integração reais via Testcontainers, sem mocks de infraestrutura).
> Redis é infraestrutura nova neste módulo — dependência de teste nova: `com.redis:testcontainers-redis`
> (`RedisContainer`, confirmado via testcontainers.com/modules/redis).

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| Config (`CacheConfig`, `PainelPublicoDTO`) | none | - (build gate only) — comportamento real exercitado indiretamente pelos testes de `PainelPublicoService` (T4) | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/{config/CacheConfig,ocupacao/PainelPublicoDTO}.java` | build gate only |
| Service (`PainelPublicoService`) | integration | Agregação correta (soma/contagem/máximo) + cache hit real (repositório não chamado 2x) + fail-open com Redis derrubado no meio do teste — os 3 riscos documentados em design.md | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/PainelPublicoServiceIT.java` (novo) | `mvn -pl alocacreche-relatorios verify -Dit.test=PainelPublicoServiceIT` |
| Controller (`PainelPublicoController`) | integration | Rota HTTP: caminho feliz + `municipioId` ausente/inválido (400) + município vazio (200 com totais zerados) | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/PainelPublicoControllerIT.java` (novo) | `mvn -pl alocacreche-relatorios verify -Dit.test=PainelPublicoControllerIT` |

## Gate Check Commands

> Gerado a partir de `alocacreche-relatorios/pom.xml` (Surefire para `*Test.java`, Failsafe para
> `*IT.java` — ver AD-003).

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Após tasks só com testes unitários (nenhuma nesta feature) | `mvn -pl alocacreche-relatorios test` |
| Full | Após tasks com testes de integração (T4, T5) | `mvn -pl alocacreche-relatorios verify -Dit.test=<Classe>` |
| Build | Após conclusão da feature | `mvn -pl alocacreche-relatorios verify` |

---

## Execution Plan

### Phase 1: Fundação (dependência + DTO)

```
T1
T2
```

### Phase 2: Configuração de cache

```
T1 ------→ T3
```

### Phase 3: Serviço

```
T2 ------→ T4
T3 ------→ T4
```

### Phase 4: Endpoint HTTP

```
T4 ------→ T5
```

---

## Task Breakdown

### T1: Adicionar dependências e configuração de cache — ✅ Complete

**What**: `spring-boot-starter-cache` no `pom.xml` (produção) + `com.redis:testcontainers-redis` (teste)
+ `spring.cache.redis.time-to-live: 60000` no `application.yml`.
**Where**: `alocacreche-relatorios/pom.xml`, `alocacreche-relatorios/src/main/resources/application.yml`
**Depends on**: None
**Reuses**: `spring-boot-starter-data-redis` já presente
**Requirement**: PAINEL-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] `spring-boot-starter-cache` adicionado
- [x] `com.redis:testcontainers-redis` adicionado com `<scope>test</scope>` (versão 2.2.2, fixada
      explicitamente — sem gerenciamento pelo BOM do Spring Boot)
- [x] `spring.cache.redis.time-to-live: 60000` em `application.yml`
- [x] Gate check passa: `mvn -pl alocacreche-relatorios compile` e `test-compile` (confirma resolução
      da dependência nova)

**Tests**: none
**Gate**: build

**Commit**: `build(relatorios): adiciona dependencias de cache Redis`

---

### T2: Criar `PainelPublicoDTO` — ✅ Complete

**What**: Record de resposta/valor de cache — `municipioId`, `totalVagasDisponiveis`,
`totalCrechesComVaga`, `totalTurmas`, `atualizadoEm`. **Precisa `implements Serializable`** (ver
design.md, Risks & Concerns — sem isso o `RedisCacheManager` padrão falha ao serializar).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/PainelPublicoDTO.java`
**Depends on**: None
**Reuses**: Nenhum
**Requirement**: PAINEL-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Record com os 5 campos, `implements Serializable`
- [x] Gate check passa: `mvn -pl alocacreche-relatorios compile`

**Tests**: none
**Gate**: build

**Commit**: `feat(relatorios): DTO do painel publico de ocupacao`

---

### T3: Criar `CacheConfig` — ✅ Complete

**What**: `@EnableCaching` + `CacheErrorHandler` que engole erros de Redis (fail-open) em vez de
propagar — implementação dos 4 métodos da interface (`handleCacheGetError`,
`handleCachePutError`, `handleCacheEvictError`, `handleCacheClearError`), logando em `WARN`.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/CacheConfig.java`
**Depends on**: T1
**Reuses**: Nenhum — primeiro uso de `@EnableCaching` no projeto
**Requirement**: PAINEL-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] `@EnableCaching` presente
- [x] `CacheErrorHandler` implementado, não relança nenhuma das 4 exceções
- [x] Gate check passa: `mvn -pl alocacreche-relatorios compile`
- [x] **Correção pós-T4**: a versão original (`@Bean CacheErrorHandler` solto, sem `CachingConfigurer`)
      compilava e passava no gate de build, mas o teste de fail-open de T4
      (`PainelPublicoFailOpenIT`) provou em runtime que o bean nunca era conectado ao
      `CacheInterceptor` — `ProxyCachingConfiguration` só busca o `errorHandler` através de um bean
      `CachingConfigurer`. Corrigido implementando essa interface em `CacheConfig`. Gate de compilação
      sozinho não detecta esse tipo de erro de wiring — só um teste de integração real com Redis
      derrubado de fato prova.

**Tests**: none (comportamento real provado pelos testes de `PainelPublicoService` em T4 — não é
testável de forma decisiva isoladamente sem um `CacheManager` real já wireado)
**Gate**: build

**Commit**: `feat(relatorios): habilita cache com fail-open no Redis`

---

### T4: Criar `PainelPublicoService` — ✅ Complete

**What**: `obterPainel(Long municipioId)` — `@Cacheable(cacheNames = "painel-publico", key =
"#municipioId")` (sem `sync=true`, ver design.md) + `@Transactional(readOnly = true)`. Aplica
`TenantContext.aplicar`, chama `repository.buscarPorFiltro(municipioId, null, null)` (reusa Epic 1),
agrega em memória (soma de `vagasDisponiveis`, contagem de `crecheId` distintos com vaga > 0, contagem
de turmas, máximo de `atualizadoEm`).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/PainelPublicoService.java` (new), `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/PainelPublicoServiceIT.java` (new), `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/PainelPublicoFailOpenIT.java` (new — **desvio do plano original**: o teste de fail-open precisa derrubar o container Redis, o que contaminaria os outros testes se compartilhassem o mesmo container; isolado numa classe própria com seu próprio container)
**Depends on**: T2, T3
**Reuses**: `OcupacaoTurmaRepository.buscarPorFiltro`, `TenantContext.aplicar`
**Requirement**: PAINEL-01, PAINEL-03, PAINEL-04, PAINEL-05, PAINEL-06

**Tools**:
- MCP: NONE
- Skill: `spring-boot-patterns`, `test-quality`

**Done when**:
- [x] Agregação correta: soma de vagas, contagem de creches com vaga > 0, contagem de turmas, máximo
      de `atualizadoEm`, comparado com cálculo manual do teste
- [x] Município sem nenhuma linha retorna totais zerados e `atualizadoEm=null`
- [x] **Cache hit real**: 2ª chamada com o mesmo `municipioId` dentro do TTL não chama o repositório de
      novo — `@MockitoSpyBean` em `OcupacaoTurmaRepository`, `verify(repository, times(1))`, não
      `times(2)`, provando que o valor voltou do Redis e não foi recalculado
- [x] **Fail-open real**: container Redis do Testcontainers parado (`redis.stop()`) no meio do teste —
      chamada seguinte ainda retorna o painel correto (200, dado certo), não erro. **Achado real
      durante a implementação**: a primeira versão de `CacheConfig` (um `@Bean CacheErrorHandler`
      solto) NÃO funcionava — o teste falhou de verdade com `RedisConnectionFailureException`
      propagada, provando que `ProxyCachingConfiguration` só conecta o `errorHandler` através de um
      bean `CachingConfigurer`, não por tipo direto. Corrigido implementando `CachingConfigurer` em
      `CacheConfig` (ver T3, atualizado retroativamente).
- [x] `PainelPublicoDTO` de fato serializa/deserializa via Redis sem perda de campo (verificado pelo
      próprio teste de cache hit — o valor lido de volta bate com o original)
- [x] Gate check passa: `mvn -pl alocacreche-relatorios verify -Dit.test=PainelPublicoServiceIT,PainelPublicoFailOpenIT`
- [x] Contagem de testes: 4 testes novos passam (3 em `PainelPublicoServiceIT` + 1 em `PainelPublicoFailOpenIT`)

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): servico do painel publico com cache e fail-open`

---

### T5: Criar `PainelPublicoController`

**What**: `GET /api/v1/relatorios/painel-publico?municipioId=X` — delega a `PainelPublicoService`,
sem lógica própria. Cobre PAINEL-02 (validação HTTP de `municipioId`).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/PainelPublicoController.java` (new), `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/PainelPublicoControllerIT.java` (new)
**Depends on**: T4
**Reuses**: `PainelPublicoService`, mesmo padrão de validação HTTP 400 do `OcupacaoConsultaController` (Epic 1)
**Requirement**: PAINEL-01, PAINEL-02, PAINEL-03

**Tools**:
- MCP: NONE
- Skill: `spring-boot-patterns`, `test-quality`

**Done when**:
- [ ] `GET .../painel-publico?municipioId=X` retorna 200 com o painel correto via HTTP real
- [ ] `municipioId` ausente ou não numérico retorna 400
- [ ] Município sem dado retorna 200 com totais zerados
- [ ] Gate check passa: `mvn -pl alocacreche-relatorios verify -Dit.test=PainelPublicoControllerIT`
- [ ] Contagem de testes: N testes novos passam

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): endpoint GET /api/v1/relatorios/painel-publico`

---

## Phase Execution Map

```
Phase 1 → Phase 2 → Phase 3 → Phase 4

Phase 1:  T1
          T2
Phase 2:  T1 ------→ T3
Phase 3:  T2, T3 ------→ T4
Phase 4:  T4 ------→ T5
```

Execution is strictly sequential - there is no intra-phase parallelism. Todas as 5 tasks cabem num
único batch (≤ ~8 tasks) — execução inline, sem sub-agentes.

---

## Task Granularity Check

| Task | Scope | Status |
| --- | --- | --- |
| T1: dependências + config de cache | 2 arquivos de config, 1 conceito | ✅ Granular |
| T2: `PainelPublicoDTO` | 1 arquivo, 1 conceito | ✅ Granular |
| T3: `CacheConfig` | 1 arquivo, 1 conceito | ✅ Granular |
| T4: `PainelPublicoService` | 1 serviço + testes do mesmo serviço | ✅ Granular |
| T5: `PainelPublicoController` | 1 endpoint + testes do mesmo endpoint | ✅ Granular |

---

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| --- | --- | --- | --- |
| T1 | None | None | ✅ Match |
| T2 | None | None | ✅ Match |
| T3 | T1 | T1→T3 (Phase 2) | ✅ Match |
| T4 | T2, T3 | T2→T4, T3→T4 (Phase 3) | ✅ Match |
| T5 | T4 | T4→T5 (Phase 4) | ✅ Match |

---

## Test Co-location Validation

| Task | Code Layer Created/Modified | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1: dependências/config | Config | none | none | ✅ OK |
| T2: DTO | Config | none | none | ✅ OK |
| T3: CacheConfig | Config | none | none | ✅ OK |
| T4: Service | Service | integration | integration | ✅ OK |
| T5: Controller | Controller | integration | integration | ✅ OK |

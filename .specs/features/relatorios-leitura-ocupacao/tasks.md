# Leitura do relatório de ocupação (MSRelatorios) Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its Execute
flow and Critical Rules.** Do not search for skill files by filesystem path. The skill is the source of
truth for the full flow (per-task cycle, sub-agent delegation, adequacy review, Verifier, discrimination
sensor).

**If the skill cannot be activated, STOP and tell the user - do not proceed without it.**

---

**Design**: `.specs/features/relatorios-leitura-ocupacao/design.md`
**Status**: Draft

---

## Test Coverage Matrix

> Gerado a partir do código existente (nenhum `AGENTS.md`/guideline de teste dedicado encontrado — o
> padrão observado nos testes já existentes do módulo é o guideline de fato). Amostrado de
> `OcupacaoTurmaRepositoryIT.java` e `VagaLiberadaConsumerIT.java`: testes de integração reais
> (Testcontainers, Postgres real, sem mocks de banco) para qualquer coisa que toque RLS/multi-tenancy.

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| Repository (`OcupacaoTurmaRepository.buscarPorFiltro`) | integration | Cada combinação de filtro (só município, +creche, +turma) + isolamento cross-tenant — mesma profundidade já usada para `upsert` | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepositoryIT.java` (métodos novos no arquivo existente) | `mvn -pl alocacreche-relatorios verify -Dit.test=OcupacaoTurmaRepositoryIT` |
| Controller (`OcupacaoConsultaController`) | integration | Todas as rotas do endpoint: caminho feliz + cada filtro opcional + `municipioId` ausente/inválido (400) + lista vazia (200) + isolamento cross-tenant + não vazamento de tenant entre chamadas sequenciais | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/OcupacaoConsultaControllerIT.java` (novo) | `mvn -pl alocacreche-relatorios verify -Dit.test=OcupacaoConsultaControllerIT` |
| DTO (`OcupacaoTurmaDTO`) | none | - (build gate only — record simples, sem lógica) | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaDTO.java` | build gate only |
| ADR (`docs/adr/0003-*.md`) | none | - (documentação, não código) | `docs/adr/0003-prefixo-rest-api.md` | build gate only |

## Gate Check Commands

> Gerado a partir de `alocacreche-relatorios/pom.xml` (Surefire para `*Test.java`, Failsafe para
> `*IT.java` — ver AD-003).

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Após tasks só com testes unitários (nenhuma nesta feature — tudo aqui toca RLS/Postgres real) | `mvn -pl alocacreche-relatorios test` |
| Full | Após tasks com testes de integração (T2, T3) | `mvn -pl alocacreche-relatorios verify -Dit.test=<Classe>` |
| Build | Após conclusão da feature | `mvn -pl alocacreche-relatorios verify` |

---

## Execution Plan

Phases are ordered and run sequentially - each phase completes before the next begins, and tasks
within a phase execute in order.

### Phase 1: Fundação (DTO + consulta no repositório)

Sem dependência entre si — executadas em ordem só por convenção (mesma fase), não por necessidade.

```
T1
T2
```

### Phase 2: Endpoint HTTP

```
T1 ------→ T3
T2 ------→ T3
```

### Phase 3: Documentação

```
T3 ------→ T4
```

---

## Task Breakdown

### T1: Criar `OcupacaoTurmaDTO` — ✅ Complete

**What**: Record de resposta da API — espelha os campos de `OcupacaoTurma`, nunca expõe a entidade JPA
diretamente.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaDTO.java`
**Depends on**: None
**Reuses**: Mesmos nomes de campo de `OcupacaoTurma` (design.md)
**Requirement**: RELOC-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Record com `turmaId`, `crecheId`, `municipioId`, `vagasDisponiveis`, `ultimaSequenciaAplicada`, `atualizadoEm`
- [x] Gate check passa: `mvn -pl alocacreche-relatorios compile`

**Tests**: none
**Gate**: build

**Commit**: `feat(relatorios): DTO de resposta da consulta de ocupacao`

---

### T2: Adicionar `buscarPorFiltro` ao `OcupacaoTurmaRepository`

**What**: Novo método JPQL com `municipioId` obrigatório e `crecheId`/`turmaId` opcionais
(`(:param IS NULL OR campo = :param)`), cobrindo RELOC-01/02/03/05/06.
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepository.java` (modify), `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepositoryIT.java` (modify — novos métodos de teste)
**Depends on**: None
**Reuses**: Container Testcontainers/role `alocacreche_app` já existentes no arquivo de teste; padrão `@Query` já usado em `upsert`
**Requirement**: RELOC-01, RELOC-02, RELOC-03, RELOC-05, RELOC-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `buscarPorFiltro(municipioId, crecheId, turmaId)` retorna todas as turmas do município quando `crecheId`/`turmaId` são `null`
- [ ] Retorna só as turmas da creche quando `crecheId` é informado
- [ ] Retorna só a turma informada quando `turmaId` é informado
- [ ] Retorna lista vazia quando `crecheId`/`turmaId` pertencem a outro município (RLS fail-closed)
- [ ] Retorna lista vazia quando o município não tem nenhuma linha
- [ ] Gate check passa: `mvn -pl alocacreche-relatorios verify -Dit.test=OcupacaoTurmaRepositoryIT`
- [ ] Contagem de testes: 8 + N novos testes passam (N = testes desta task)

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): consulta de ocupacao por municipio/creche/turma`

---

### T3: Criar `OcupacaoConsultaController`

**What**: `GET /api/v1/relatorios/ocupacao` — aplica `TenantContext.aplicar`, delega a
`buscarPorFiltro`, mapeia para `OcupacaoTurmaDTO`. Cobre RELOC-01 a RELOC-07 (todos — é o ponto de
entrada de todos os critérios).
**Where**: `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoConsultaController.java` (new), `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/OcupacaoConsultaControllerIT.java` (new)
**Depends on**: T1, T2
**Reuses**: `TenantContext.aplicar`, padrão `@Transactional` de `VagaLiberadaListener`, `docker/postgres-relatorios-init`/`db/relatorios-app-role.sql` (Testcontainers)
**Requirement**: RELOC-01, RELOC-02, RELOC-03, RELOC-04, RELOC-05, RELOC-06, RELOC-07

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `GET .../ocupacao?municipioId=X` retorna 200 com a lista do município
- [ ] Filtro por `crecheId` e por `turmaId` funcionam via HTTP real
- [ ] `municipioId` ausente ou não numérico retorna 400 (sem tocar o banco)
- [ ] Filtro sem match retorna 200 com lista vazia
- [ ] `crecheId`/`turmaId` de outro município retornam lista vazia (nunca dado de outro tenant)
- [ ] `@Transactional(readOnly = true)` presente no método — teste dedicado chama o endpoint 2x
      seguidas com `municipioId` diferentes (mesmo pool de conexão) e confirma que a 2ª chamada nunca
      vê dado do 1º município (mitigação do risco de vazamento via HikariCP, ver design.md)
- [ ] Gate check passa: `mvn -pl alocacreche-relatorios verify -Dit.test=OcupacaoConsultaControllerIT`
- [ ] Contagem de testes: N testes novos passam (ver Done-when acima, um teste por bullet no mínimo)

**Tests**: integration
**Gate**: full

**Commit**: `feat(relatorios): endpoint GET /api/v1/relatorios/ocupacao`

---

### T4: Registrar ADR do prefixo de rota REST

**What**: `docs/adr/0003-prefixo-rest-api.md` documentando `/api/v1` como prefixo padrão do projeto —
primeira decisão desse tipo, tomada implicitamente por esta feature (ver spec.md, Assumptions).
**Where**: `docs/adr/0003-prefixo-rest-api.md` (new)
**Depends on**: T3
**Reuses**: Formato de `docs/adr/0001-fundacao-arquitetural.md` e `docs/adr/0002-versoes-stack-e-scaffold-inicial.md`
**Requirement**: N/A (decisão arquitetural, não requisito funcional)

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] ADR registra a decisão, o porquê (primeiro Controller do projeto, sem prefixo definido antes),
      e o escopo (vale para todo módulo futuro que expuser Controller)
- [ ] Gate check passa: `mvn -pl alocacreche-relatorios verify` (build completo, garante que nada
      quebrou)

**Tests**: none
**Gate**: build

**Commit**: `docs(adr): registra prefixo de rota REST do projeto (/api/v1)`

---

## Phase Execution Map

```
Phase 1 → Phase 2 → Phase 3

Phase 1:  T1
          T2
Phase 2:  T1, T2 ------→ T3
Phase 3:  T3 ------→ T4
```

Execution is strictly sequential - there is no intra-phase parallelism. A single agent works one task
at a time, in order. Todas as 4 tasks cabem num único batch (≤ ~8 tasks) — execução inline, sem
sub-agentes.

---

## Task Granularity Check

| Task | Scope | Status |
| --- | --- | --- |
| T1: Criar `OcupacaoTurmaDTO` | 1 arquivo, 1 conceito | ✅ Granular |
| T2: `buscarPorFiltro` no repositório | 1 método + testes do mesmo método | ✅ Granular |
| T3: `OcupacaoConsultaController` | 1 endpoint + testes do mesmo endpoint | ✅ Granular |
| T4: ADR de prefixo de rota | 1 documento | ✅ Granular |

---

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| --- | --- | --- | --- |
| T1 | None | None | ✅ Match |
| T2 | None | None | ✅ Match |
| T3 | T1, T2 | T1→T3, T2→T3 (Phase 2) | ✅ Match |
| T4 | T3 | T3→T4 (Phase 3) | ✅ Match |

---

## Test Co-location Validation

| Task | Code Layer Created/Modified | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1: DTO | DTO (`OcupacaoTurmaDTO`) | none | none | ✅ OK |
| T2: Repository | Repository (`buscarPorFiltro`) | integration | integration | ✅ OK |
| T3: Controller | Controller (`OcupacaoConsultaController`) | integration | integration | ✅ OK |
| T4: ADR | ADR (doc) | none | none | ✅ OK |

# Leitura do relatório de ocupação (MSRelatorios) Validation

**Date**: 2026-09-14
**Spec**: `.specs/features/relatorios-leitura-ocupacao/spec.md`
**Diff range**: `a598694..c1c9681` (4 commits: `27b0ddd` T1, `a8e6268` T2, `a13d4f9` T3, `c1c9681` T4)
**Verifier**: independent sub-agent (author ≠ verifier)

---

## Task Completion

| Task | Status  | Notes |
| ---- | ------- | ----- |
| T1   | ✅ Done | `OcupacaoTurmaDTO` record, matches design.md field list exactly |
| T2   | ✅ Done | `buscarPorFiltro` added to `OcupacaoTurmaRepository`; 5 new tests in `OcupacaoTurmaRepositoryIT` |
| T3   | ✅ Done | `OcupacaoConsultaController` new; 8 new tests in `OcupacaoConsultaControllerIT` |
| T4   | ✅ Done | `docs/adr/0003-prefixo-rest-api.md` registers `/api/v1` prefix |

---

## Spec-Anchored Acceptance Criteria

| Criterion (spec.md) | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| RELOC-01 (AC1): `GET ?municipioId=X` | HTTP 200, list restricted to that município | `OcupacaoConsultaControllerIT.java:70-74` - `assertThat(resposta.getStatusCode().value()).isEqualTo(200)`; `assertThat(resposta.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactlyInAnyOrder(1000L, 1001L)`. Also `OcupacaoTurmaRepositoryIT.java:210-212` (`buscarPorFiltroComoTenant(1L,1L,null,null)` → `containsExactlyInAnyOrder(900L,901L)`) | ✅ PASS |
| RELOC-02 (AC2): filter `crecheId` | Only turmas of that creche, within município | `OcupacaoConsultaControllerIT.java:82-86` - `containsExactly(1010L)`; `OcupacaoTurmaRepositoryIT.java:220-222` - `containsExactly(910L)` | ✅ PASS |
| RELOC-03 (AC3): filter `turmaId` | Only that turma (0 or 1 row), within município | `OcupacaoConsultaControllerIT.java:94-98` - `containsExactly(1020L)`; `OcupacaoTurmaRepositoryIT.java:230-232` - `containsExactly(920L)` | ⚠️ Spec-precision gap — only the "1 row" branch is exercised for the `turmaId` filter specifically; the "0 rows" branch is only proven generically (RELOC-05, different `municipioId`), not with a `turmaId` that legitimately doesn't exist inside an otherwise-populated município |
| RELOC-04 (AC4): `municipioId` ausente/inválido | HTTP 400, no DB call, error message in body | `OcupacaoConsultaControllerIT.java:102-107` (ausente) and `:110-116` (não numérico) - both assert `isEqualTo(400)` | ⚠️ Spec-precision gap — status code asserted precisely; "mensagem de erro" in the body is not asserted (relies on Spring Boot's default error body, not verified by the test) |
| RELOC-05 (AC5): sem match | HTTP 200, empty list (incl. município that never received an event) | `OcupacaoConsultaControllerIT.java:120-124` - `isEqualTo(200)` + `isEmpty()`; `OcupacaoTurmaRepositoryIT.java:236-239` - `isEmpty()` | ✅ PASS |
| RELOC-06 (AC6): `crecheId`/`turmaId` de outro município | Empty list, never leak cross-tenant data | `OcupacaoConsultaControllerIT.java:128-136` (`crecheId` de outro município → `isEmpty()`); `OcupacaoTurmaRepositoryIT.java:243-250` (sessão no tenant 2, filtro pede `municipioId=1` → `isEmpty()`, prova que a RLS barra mesmo com filtro "correto") | ⚠️ Spec-precision gap — the `turmaId`-from-another-município variant is not separately exercised at the Controller layer (only `crecheId` is); functionally implied by the identical query shape (`buscarPorFiltro`) and by the RLS-session test, but not directly asserted for `turmaId` |
| RELOC-07 (AC7): `TenantContext.aplicar` antes de qualquer leitura, mesma transação | Applied inside the same `@Transactional` method, before the repository call | `OcupacaoConsultaController.java:26` (`@Transactional(readOnly = true)`), `:31` (`TenantContext.aplicar(...)`), `:33` (repository call) - structural code fact, empirically proven by Discrimination Sensor mutation #3 below (removing the call breaks 6/8 tests) and mutation #1 (removing `@Transactional` breaks 6/8 tests); additionally `OcupacaoConsultaControllerIT.java:139-151` (`consultarEmSequenciaComMunicipiosDiferentesNaoMisturaDados`) proves no cross-request bleed | ✅ PASS |

**Status**: ✅ All 7 ACs have real evidence with outcomes matching the spec-defined result; 3 minor spec-precision gaps noted above (RELOC-03, RELOC-04, RELOC-06) — none of them indicate incorrect behavior, only an untested sub-variant of an already-covered code path.

---

## Discrimination Sensor

Isolated scratch used: `git worktree add` at a temp path (`/c/repos-pessoal/creche-scratch/mutation-sensor`, detached at `c1c9681`), never `git stash`. Baseline `git status --porcelain` on the real tree before the sensor run: `?? docs/mock-data/` (pre-existing untracked mock data, unrelated to this feature). After the sensor run and `git worktree remove --force` + directory deletion, `git status --porcelain` on the real tree was re-checked and matched the baseline exactly — isolation confirmed, real tree never touched.

| # | File:line (real file, mutation applied to scratch copy) | Description | Killed? |
| - | --- | --- | --- |
| 1 | `OcupacaoConsultaController.java:26` | Removed `@Transactional(readOnly = true)` from `consultar` | ✅ Killed — 6/8 `OcupacaoConsultaControllerIT` tests errored (RLS fails closed because `set_config(..., is_local=true)` never survives to the `SELECT`, which now runs as its own implicit autocommit transaction; response body becomes a Spring error object, which fails JSON-array deserialization in the test client) |
| 2 | `OcupacaoTurmaRepository.java:50` | Changed `(:crecheId IS NULL OR o.crecheId = :crecheId)` → `o.crecheId = :crecheId` (removed optionality) | ✅ Killed — 2/13 `OcupacaoTurmaRepositoryIT` tests failed: `buscarPorFiltroRetornaTodasAsTurmasDoMunicipioQuandoSemFiltroAdicional` (RELOC-01, no additional filter) and `buscarPorFiltroComTurmaIdRetornaSoAquelaTurma` (RELOC-03, `turmaId`-only filter) both expected non-empty results but got `[]`, because `crecheId=null` no longer matches |
| 3 | `OcupacaoConsultaController.java:31` | Removed the `TenantContext.aplicar(entityManager, municipioId)` call | ✅ Killed — 6/8 `OcupacaoConsultaControllerIT` tests errored (RLS fails closed identically to mutation #1 — `current_setting('app.current_tenant')` is never set for the session, so every query blows up) |

**Sensor depth**: lightweight (3 mutations, proportional to a non-P0 read-only endpoint)
**Result**: 3/3 killed - ✅ PASS

---

## Technical nuance check (design.md risk correction)

Verified independently, not accepted on faith:

- `TenantContext.java:13`: `set_config('app.current_tenant', ?1, true)` — the third argument `true` is Postgres's `is_local` flag. Per Postgres semantics, `is_local=true` ties the setting to the *current transaction*, reverting at transaction end — it is never a session-level `SET`, so it structurally cannot leak between different HTTP requests sharing a pooled HikariCP connection.
- `schema.sql:14-15`: the RLS policy reads `current_setting('app.current_tenant')::bigint` with no fallback — an unset GUC throws (`unrecognized configuration parameter` / `invalid input syntax for type bigint: ""` depending on whether the pooled connection ever referenced the GUC before), which is exactly what `OcupacaoTurmaRepositoryIT.java:169-188` (`semSetLocalDefinidoFalhaFechadoEmVezDeVazarDados`) demonstrates and what mutations #1 and #3 above reproduced live.
- Conclusion: design.md's corrected explanation ("fails closed within the same request, not a cross-request leak") is technically sound and matches the actual code and the RLS policy definition. The dedicated sequential test (`consultarEmSequenciaComMunicipiosDiferentesNaoMisturaDados`) still has value: it is a cheap regression guard for the *outcome* (no data mixing across `municipioId` values in sequential calls), even though the underlying structural guarantee against cross-request leakage comes from `is_local=true` itself, not from this specific test or from `@Transactional`. `@Transactional`'s role is narrower and different: it is what makes the `TenantContext.aplicar` call and the subsequent `SELECT` share the same transaction (and therefore the same `is_local` scope) in the first place — without it, the endpoint fails closed (empty-looking behavior surfaces as a 500 in the test harness because the query throws), as proven by mutation #1.

---

## Code Quality

| Principle | Status |
| --- | --- |
| Minimum code | ✅ — DTO, one repository method, one controller, one ADR; no service layer added, consistent with design.md's explicit decision |
| Surgical changes | ✅ — only files listed in tasks.md touched |
| No scope creep | ✅ — auth/permission explicitly and correctly left out per spec.md "Out of Scope", not silently dropped |
| Matches patterns | ✅ — mirrors `VagaLiberadaListener`'s `TenantContext.aplicar` + `@Transactional` pattern; reuses Testcontainers/`alocacreche_app` role pattern from `OcupacaoTurmaRepositoryIT` |
| Spec-anchored outcome check | ✅ (with 3 noted spec-precision gaps, see above) |
| Per-layer Coverage Expectation met | ✅ — Repository: all filter combinations + cross-tenant; Controller: happy path + every optional filter + 400 (missing/non-numeric) + empty-list + cross-tenant + sequential isolation |
| Every test maps to a spec requirement | ✅ — no unclaimed tests found in either new/modified test file |
| Documented guidelines followed | tasks.md Test Coverage Matrix (project-local, derived from existing test patterns — no external testing guideline file exists yet) |

---

## Edge Cases (spec.md)

- [x] `municipioId` de município que nunca recebeu evento → 200 vazio: `OcupacaoTurmaRepositoryIT.java:236-239`, `OcupacaoConsultaControllerIT.java:119-125`
- [x] `crecheId` sem `turmaId` (e vice-versa) aplicam só o filtro informado: `OcupacaoTurmaRepositoryIT.java:216-233` (cada filtro isolado)
- [x] `crecheId`/`turmaId` não numéricos → 400: `OcupacaoConsultaControllerIT.java:109-116` covers `municipioId` non-numeric; no dedicated test for non-numeric `crecheId`/`turmaId` specifically, but the mechanism (`MethodArgumentTypeMismatchException` from `@RequestParam Long`) is identical to the tested `municipioId` case and applies uniformly to all three `Long` parameters — low-risk, structurally guaranteed by Spring's parameter binding rather than custom code, consistent with design.md's Error Handling Strategy (no custom handler)

---

## Gate Check

- **Gate command**: `mvn -pl alocacreche-relatorios verify`
- **Result**: exit code 0 (BUILD SUCCESS). This-run test reports (filtered by 2026-09-14 timestamp; older `DebugAmqpIT`/`DebugRlsIT` surefire report files are stale leftovers from a prior session for classes that no longer exist in source, and were not re-executed):
  - Surefire: `AlocaCrecheRelatoriosApplicationTests` (1), `RabbitMqConfigRetryClassificationTest` (2), `VagaLiberadaListenerTest` (4)
  - Failsafe: `OcupacaoConsultaControllerIT` (8), `OcupacaoTurmaRepositoryIT` (13), `VagaLiberadaConsumerIT` (4)
  - **Total this run: 32 passed, 0 failed, 0 skipped**
- **Test count before feature** (per `.specs/STATE.md` handoff for `relatorios-ocupacao-vaga-liberada`): 19
- **Test count after feature**: 32
- **Delta**: +13 new tests (5 in `OcupacaoTurmaRepositoryIT` per T2, 8 in new `OcupacaoConsultaControllerIT` per T3) — matches tasks.md's claimed counts exactly (8 originais + 5 novos = 13 in repository IT; 8 novos in controller IT)
- **Skipped tests**: none
- **Failures**: none

---

## Fix Plans

None required — no blocking gaps found. The 3 spec-precision gaps noted above (RELOC-03 zero-row-for-turmaId variant, RELOC-04 error-message-body assertion, RELOC-06 turmaId-cross-tenant variant at Controller layer) are optional coverage improvements, not correctness defects; the underlying query logic is shared and structurally identical across the untested variants and the tested ones, and was itself killed correctly by the discrimination sensor (mutation #2) for the exact code path in question.

---

## Requirement Traceability Update

| Requirement | Previous Status | New Status |
| --- | --- | --- |
| RELOC-01 | Implementing | ✅ Verified |
| RELOC-02 | Implementing | ✅ Verified |
| RELOC-03 | Implementing | ✅ Verified (spec-precision gap noted, not blocking) |
| RELOC-04 | Implementing | ✅ Verified (spec-precision gap noted, not blocking) |
| RELOC-05 | Implementing | ✅ Verified |
| RELOC-06 | Implementing | ✅ Verified (spec-precision gap noted, not blocking) |
| RELOC-07 | Implementing | ✅ Verified |

---

## Summary

**Overall**: ✅ Ready

**Spec-anchored check**: 7/7 ACs matched spec outcome, 3 spec-precision gaps flagged (non-blocking)
**Sensor**: 3/3 mutations killed
**Gate**: 32 passed, 0 failed (`mvn -pl alocacreche-relatorios verify`, exit 0)

**What works**: All 7 RELOC ACs are implemented and covered by real Testcontainers-backed integration tests (repository layer + HTTP layer). Multi-tenancy discipline (`TenantContext.aplicar` + RLS) is applied correctly and its necessity is empirically proven by the discrimination sensor, not just asserted in comments. The design.md risk-correction (fail-closed within a request, not a cross-request leak via `is_local=true`) was independently re-derived from `TenantContext.java` and `schema.sql` and found technically sound. `RELOC-07`'s "auth removed from scope" is documented as a deliberate, traceable TODO in spec.md, design.md, and the controller's own Javadoc — correctly not treated as a gap. ADR 0003 registers the `/api/v1` prefix decision as required by T4.

**Issues found**: None blocking. Three optional test-coverage refinements identified (see Spec-Anchored table) — could be added in a future small PR but do not represent incorrect behavior in the shipped code.

**Next steps**: None required to close this feature. Optionally, a follow-up could add: (1) a `turmaId` that doesn't exist within a populated município (0-row case for RELOC-03), (2) an assertion on the 400 response body content for RELOC-04, (3) a `turmaId`-from-another-município variant at the Controller layer for RELOC-06.

---

## Update (2026-09-15): 3 spec-precision gaps closed

User asked to fix all 3 non-blocking gaps. Closed directly (test-only additions, no production code
change — the underlying code paths were already correct and already mutation-killed):

1. **RELOC-03**: added `buscarPorFiltroComTurmaIdInexistenteDentroDeMunicipioPopuladoRetornaVazio`
   (`OcupacaoTurmaRepositoryIT.java`) and `consultarComTurmaIdInexistenteDentroDeMunicipioPopuladoRetornaListaVazia`
   (`OcupacaoConsultaControllerIT.java`) — both seed a turma under the município, then filter by a
   `turmaId` that doesn't exist, asserting an empty result (not just a differently-scoped empty case).
2. **RELOC-04**: strengthened the two existing 400 tests
   (`consultarSemMunicipioIdRetorna400`, `consultarComMunicipioIdNaoNumericoRetorna400`) to assert the
   response body, not just the status code. Checked the actual body first (ran the test with a debug
   print before writing the assertion) rather than guessing: Spring Boot's default error body is
   `{"timestamp":...,"status":400,"error":"Bad Request","path":...}` — no `message` field by default
   (suppressed for security unless `server.error.include-message` is configured, which this project
   doesn't set). Assertion targets `"status":400` and `"error":"Bad Request"` — the actual error
   message this endpoint returns today, not a fabricated one.
3. **RELOC-06**: added `consultarComTurmaIdDeOutroMunicipioRetornaListaVazia`
   (`OcupacaoConsultaControllerIT.java`), mirroring the existing `crecheId`-from-another-município test
   for the `turmaId` variant at the Controller layer.

Gate re-run: `mvn -pl alocacreche-relatorios verify` → 35 tests (7 unit + 28 integration,
+3 vs. the 32 at PASS time), `BUILD SUCCESS`. No re-verification round dispatched — these were
explicitly non-blocking coverage refinements at PASS, not a FAIL requiring fix→re-verify.

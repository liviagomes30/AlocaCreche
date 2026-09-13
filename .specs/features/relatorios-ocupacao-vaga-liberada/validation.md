# Consumo do evento VagaLiberada e projeção de ocupação Validation

**Date**: 2026-09-12
**Spec**: `.specs/features/relatorios-ocupacao-vaga-liberada/spec.md`
**Diff range**: `240bd4f..HEAD` (HEAD = `33aabc5`)
**Verifier**: independent sub-agent (author ≠ verifier)

---

## Task Completion

| Task | Status  | Notes |
| ---- | ------- | ----- |
| T1   | ✅ Done | `VagaLiberadaEvent` record, all 7 fields present |
| T2   | ✅ Done | `EventoInvalidoException extends RuntimeException` |
| T3   | ✅ Done | `OcupacaoTurma` entity, `turmaId` as `@Id` |
| T4   | ✅ Done | `schema.sql` + `spring.sql.init.mode: always` present |
| T5   | ✅ Done | Upsert query + `OcupacaoTurmaRepositoryIT` (5 tests, all pass in isolation) |
| T6   | ✅ Done | `RabbitMqConfig` declares exchange/queues/bindings/converter/retry |
| T7   | ✅ Done | `VagaLiberadaListener` + `VagaLiberadaListenerTest` (3 tests, all pass) |
| T8   | ✅ Done | `VagaLiberadaConsumerIT` (3 tests, all pass in isolation) |

All 8 tasks are functionally complete and each task's own "Done when" checklist is satisfied. However, see **Gate Check** below: the task-level claims of "N testes passam" were verified by running each IT class individually (`-Dtest=ClassName`), not by the literal Gate Check Command the tasks declare (`mvn -pl alocacreche-relatorios test`) — that command does not execute either IT class at all (see Gate Check section).

---

## Spec-Anchored Acceptance Criteria

| Criterion (WHEN X THEN Y) | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| **OCUP-01**: upsert de `vagasDisponiveis`, `crecheId`, `municipioId`, sequência (`eventId`) por `turmaId` | Linha gravada com todos os 4 campos + chave `turmaId` | `alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepositoryIT.java:78-82` - `assertThat(turma.getVagasDisponiveis()).isEqualTo(5); assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(1L); assertThat(turma.getCrecheId()).isEqualTo(10L); assertThat(turma.getMunicipioId()).isEqualTo(1L)`; e2e em `VagaLiberadaConsumerIT.java:88-91` via routing key real `vaga.liberada` | ✅ PASS |
| **OCUP-02**: `eventId` ≤ última sequência aplicada → descarta sem erro, sem alterar | Zero alteração para `eventId` menor **ou igual** | `OcupacaoTurmaRepositoryIT.java:96-103` - `upsertComoTenant(..., 5L)` depois `upsertComoTenant(..., 3L)`; asserta que `vagasDisponiveis`/`ultimaSequenciaAplicada` permanecem os do 1º upsert | ⚠️ **GAP parcial** - o teste só cobre `eventId` estritamente **menor** (3 < 5); nenhum teste chama upsert com `eventId` **igual** ao já aplicado. Sensor de discriminação confirmou: mutar o `WHERE` de `<` para `<=` **sobrevive** aos 5 testes (ver Discrimination Sensor #1) |
| **OCUP-03**: duas mensagens concorrentes para o mesmo `turmaId` → só a maior sequência prevalece | Resultado final = maior `eventId` das duas | `OcupacaoTurmaRepositoryIT.java:106-126` - `upsertConcorrenteAplicaSoAMaiorSequencia`: dois `ExecutorService` submits com `eventId` 5 e 10; `assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(10L); assertThat(turma.getVagasDisponiveis()).isEqualTo(7)` | ✅ PASS |
| **OCUP-04**: `SET LOCAL app.current_tenant` = `municipioId` antes de gravar/ler, mesma transação | `SET LOCAL` chamado com o valor exato do `municipioId` do payload, antes do upsert | `VagaLiberadaListenerTest.java:39-49` - `verify(entityManager).createNativeQuery("SET LOCAL app.current_tenant = '7'"); verify(query).executeUpdate(); verify(repository).upsert(100L, 7L, 10L, 5, 1L)`; confirmado fim-a-fim em `VagaLiberadaConsumerIT.java:80-91` (busca só encontra a linha lendo com o mesmo tenant) | ✅ PASS |
| **OCUP-05**: declarar exchange `alocacreche.dominio`, fila `relatorios.ocupacao`, DLQ `relatorios.ocupacao.dlq` na inicialização, idempotente | Beans declarados; reinício não falha se já existirem | `RabbitMqConfig.java:42-76` (beans); uso real comprovado em `VagaLiberadaConsumerIT.java` (mensagens fluem pela fila/DLQ nomeados) | ⚠️ **Spec-precision gap** - a declaração em si é comprovada (beans existem, broker aceita mensagens), mas a propriedade explícita "idempotente / não falha se já existir" nunca é exercitada por um teste que reinicia o contexto contra um broker já provisionado - depende de comportamento padrão do `RabbitAdmin`/AMQP, não de lógica própria testada |
| **OCUP-06**: payload não desserializável ou campo obrigatório faltando → DLQ direto, sem retry | Mensagem aparece na DLQ imediatamente (0 tentativas extras) | JSON malformado: `VagaLiberadaConsumerIT.java:95-113` - `payloadMalformadoVaiDiretoParaDlqSemReentrega`: `assertThat(naDlq).isNotNull(); assertThat(naFilaPrincipal).isNull()`. Campo faltando: só unitário, `VagaLiberadaListenerTest.java:52-60` - `assertThatThrownBy(...).isInstanceOf(EventoInvalidoException.class); verifyNoInteractions(repository)` | ⚠️ **GAP parcial** - o caminho "JSON malformado" é provado fim-a-fim (via broker real). O caminho "campo obrigatório faltando" só é provado no nível de unidade (lança a exceção, não chama o repositório) - **nunca fim-a-fim contra o broker real**, então a classificação de retry (`EventoInvalidoException` não-retryable) para esse subcaminho especificamente não é comprovada. Sensor de discriminação confirmou: tornar `EventoInvalidoException` retryable **sobrevive** a todos os 6 testes relevantes (ver Discrimination Sensor #3) |
| **OCUP-07**: `vagasDisponiveis` negativo → mesma regra (DLQ) | Mesma regra do item acima | `VagaLiberadaListenerTest.java:62-71` - `vagasDisponiveisNegativoLancaExcecaoSemChamarRepositorio`: `assertThatThrownBy(...).isInstanceOf(EventoInvalidoException.class); verifyNoInteractions(repository)` | ⚠️ **GAP parcial** - mesmo problema do OCUP-06: só unitário, nunca fim-a-fim contra o broker real confirmando ausência de retry para este payload especificamente |
| **OCUP-08**: falha transitória → nack com requeue, nova tentativa | Repositório é chamado novamente após falha | `VagaLiberadaConsumerIT.java:116-129` - `falhaTransitoriaVaiParaDlqApenasAposTresTentativas`: `doThrow(new RuntimeException(...))...; verify(repository, times(3)).upsert(...)` | ✅ PASS |
| **OCUP-09**: 3 tentativas consecutivas por causa transitória → DLQ | DLQ recebe a mensagem só após a 3ª tentativa | `VagaLiberadaConsumerIT.java:127-129` - `Message naDlq = rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_DLQ, 5_000); assertThat(naDlq).isNotNull(); verify(repository, times(3))...` | ✅ PASS |
| **OCUP-10**: RLS na tabela de projeção - leitura sem `SET LOCAL`/tenant errado não retorna linhas de outro município | Zero linhas para tenant incorreto | `OcupacaoTurmaRepositoryIT.java:128-140` - `rlsEscondeLinhaDeOutroMunicipio`: grava como tenant `1`, lê como tenant `2`, `assertThat(resultadoComoTenantErrado).isEmpty()` | ⚠️ **Spec-precision gap** - o subcaso "tenant errado definido" está coberto; o subcaso literal do texto do AC "**sem** `SET LOCAL` definido" (nenhum valor setado) não tem teste próprio - `current_setting()` de uma GUC não definida lança erro do Postgres em vez de devolver silenciosamente zero linhas, então o comportamento observável desse subcaso específico (erro vs. lista vazia) não é comprovado |

**Status**: ❌ Gaps present (3 partial-coverage gaps: OCUP-02, OCUP-06, OCUP-07 — each traced to a surviving mutant or an untested sub-branch; 2 spec-precision gaps: OCUP-05, OCUP-10)

---

## Discrimination Sensor

Isolated via `git worktree add <scratch> HEAD` at a temp path outside the repo tree; mutations applied only to the scratch copy; worktree removed with `git worktree remove --force` after each cleanup; `git status --porcelain` on the real tree confirmed empty both before and after the entire sensor run.

| Mutation | File:line | Description | Killed? |
| --- | --- | --- | --- |
| 1 | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepository.java:25` | Flipped upsert guard `WHERE ocupacao_turma.ultima_sequencia_aplicada < EXCLUDED.ultima_sequencia_aplicada` → `<=` (boundary: makes an "equal `eventId`" delivery apply the update, violating OCUP-02's "menor ou igual" idempotency guarantee) | ❌ **Survived** - `OcupacaoTurmaRepositoryIT` still reports `Tests run: 5, Failures: 0` because no test exercises `eventId` exactly equal to the already-applied sequence |
| 2 | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/VagaLiberadaListener.java:53` | Off-by-one on the negative-quota guard: `evento.vagasDisponiveis() < 0` → `< -1` (makes `-1` a valid value) | ✅ **Killed** - `VagaLiberadaListenerTest.vagasDisponiveisNegativoLancaExcecaoSemChamarRepositorio` fails: expects `EventoInvalidoException`, gets a `NullPointerException` because the exception path (and its guard) never fires |
| 3 | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:96` | Flipped retry classification: `retryableExceptions.put(EventoInvalidoException.class, false)` → `true` (makes validation failures retry 3× like a transient failure, instead of rejecting immediately per OCUP-06/07) | ❌ **Survived** - `VagaLiberadaConsumerIT` (3 tests) + `VagaLiberadaListenerTest` (3 tests) all still pass; no test publishes a well-formed-but-invalid `VagaLiberadaEvent` through the real broker to observe retry count/DLQ timing for this exception type specifically |

**Sensor depth**: lightweight (3 targeted mutations, default tier)
**Result**: 1/3 killed - ❌ **FAIL** (2 surviving mutants → fix tasks required)

---

## Code Quality

| Principle | Status |
| --- | --- |
| Minimum code | ✅ |
| Surgical changes | ✅ - diff scoped to `alocacreche-relatorios/`, `.specs/`, 2 DB init scripts, `docker-compose.yml` (3-line addition); no unrelated files touched |
| No scope creep | ✅ - HTTP read endpoint, `MatriculaCriada` consumer, Redis cache, Sa-Token all correctly left out per spec's Out of Scope table |
| Matches patterns | ✅ - `@RequiredArgsConstructor` constructor injection used in `VagaLiberadaListener` (repo-wide convention); Lombok `@Getter/@Setter` on the entity matches `OutboxEvent` convention cited in design.md |
| Spec-anchored outcome check (asserted values match spec) | ⚠️ - matches where covered; see 3 partial-coverage gaps above |
| Per-layer Coverage Expectation met (domain 1:1 ACs; routes happy+edge+error) | ❌ - the "campo obrigatório faltando" / "vagasDisponiveis negativo" error paths are proven only at unit level, not end-to-end against the real broker + retry interceptor (the layer where OCUP-06/07's "no retry" guarantee actually lives) |
| Every test maps to a spec requirement - no unclaimed tests | ✅ - no stray/unclaimed tests found |
| Documented guidelines followed: CLAUDE.md (constructor injection, Portuguese comments, no end-of-line comments) | ✅ - spot-checked all 6 new main-source files; no end-of-line comments found, all comments in Portuguese, header-style Javadoc used throughout |

---

## Edge Cases

- [x] Fila/exchange não existirem no broker → declarados na inicialização: covered by `RabbitMqConfig` beans + `VagaLiberadaConsumerIT` exercising them against a real Testcontainers broker
- [x] `vagasDisponiveis` negativo → rejeita para DLQ: unit-covered (`VagaLiberadaListenerTest.java:62-71`); see gap note above re: no e2e proof of the "no retry" property for this specific case
- [ ] Duas mensagens para turmas diferentes chegam ao mesmo tempo → processadas de forma independente (nenhuma trava global): **NOT explicitly tested** - the only concurrency test (`upsertConcorrenteAplicaSoAMaiorSequencia`) targets the *same* `turmaId`; no test exercises two distinct `turmaId`s concurrently to confirm the absence of cross-row locking/blocking. Low risk (native `INSERT ... ON CONFLICT` is inherently row-scoped in Postgres) but unverified by this test suite.

---

## Gate Check

- **Gate command** (from tasks.md, "Full"): `mvn -pl alocacreche-relatorios test`
- **Result**: 4 passed, 0 failed, 0 skipped — **but this is not the full picture, see finding below**
- **Test count before feature**: 1 (`AlocaCrecheRelatoriosApplicationTests` smoke test only, per design.md/STATE.md)
- **Test count after feature (literal gate command)**: 4
- **Test count after feature (all test classes in module, run explicitly)**: 12 (1 smoke + 3 `VagaLiberadaListenerTest` + 5 `OcupacaoTurmaRepositoryIT` + 3 `VagaLiberadaConsumerIT`)
- **Delta**: +3 (literal gate) / +11 (full module, all classes individually confirmed green)
- **Skipped tests**: none reported by any run
- **Failures**: none — every test that runs, passes

**Critical finding — the documented "Full" gate silently skips 8 of 12 tests, with exit code 0:**

`alocacreche-relatorios/pom.xml` declares no `maven-failsafe-plugin`, and neither does the parent (`pom.xml` at repo root, inherited from `spring-boot-starter-parent`). Maven Surefire's default `test` goal only matches `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`. `OcupacaoTurmaRepositoryIT.java` and `VagaLiberadaConsumerIT.java` use the `*IT` suffix, which is Failsafe's default convention, not Surefire's. Running the exact command tasks.md prescribes as the "Full" gate (`mvn -pl alocacreche-relatorios test`) produces:

```
[INFO] Running org.alocacreche.relatorios.AlocaCrecheRelatoriosApplicationTests
[INFO] Tests run: 1, ...
[INFO] Running org.alocacreche.relatorios.ocupacao.VagaLiberadaListenerTest
[INFO] Tests run: 3, ...
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Neither `OcupacaoTurmaRepositoryIT` nor `VagaLiberadaConsumerIT` is even mentioned — no error, no "0 tests found" warning, nothing. Both classes only run when targeted explicitly (`-Dtest=ClassName`), which is how T5's "5 testes passam" and T8's "3 testes passam" claims in tasks.md were actually verified during implementation — not via the gate command tasks.md itself names. Verified independently in this session: `-Dtest=OcupacaoTurmaRepositoryIT` → 5/5 pass; `-Dtest=VagaLiberadaConsumerIT` → 3/3 pass; combined `-Dtest=VagaLiberadaConsumerIT,VagaLiberadaListenerTest` → 6/6 pass. So functionally nothing is broken - but as written, `mvn -pl alocacreche-relatorios test` (and by extension `mvn clean install` at the reactor root, since it invokes the same `test` phase) gives a false-green signal for 8 of the 10 acceptance criteria in this feature (everything OCUP-01/02/03/10 and part of OCUP-01/04/05/06/08/09 depend on those two IT classes). Any future regression in the upsert query, the RLS policy, or the end-to-end retry/DLQ wiring would pass CI silently.

---

## Fix Plans (if issues found)

### Fix 1: Build gate silently excludes both integration-test classes

- **Root cause**: No `maven-failsafe-plugin` bound to the `integration-test`/`verify` phases in `alocacreche-relatorios/pom.xml` (or the parent); Surefire's default include patterns don't match the `*IT.java` naming convention used for `OcupacaoTurmaRepositoryIT` and `VagaLiberadaConsumerIT`.
- **Fix task**: Either (a) add `maven-failsafe-plugin` with its default `integration-test`/`verify` executions bound in `alocacreche-relatorios/pom.xml`, and update the "Full" Gate Check Command in tasks.md to `mvn -pl alocacreche-relatorios verify`; or (b) if Failsafe is intentionally out of scope for this module, add an explicit Surefire `<includes>` pattern covering `**/*IT.java` so `mvn test` actually runs them. Update tasks.md's Gate Check Commands table to match whichever is chosen.
- **Priority**: Blocker (the stated regression gate for 8 of 10 ACs does not run them)

### Fix 2: OCUP-02 equal-`eventId` boundary untested (surviving mutant #1)

- **Root cause**: `OcupacaoTurmaRepositoryIT.upsertNaoAplicaQuandoSequenciaRecebidaEMenorOuIgual` only exercises `eventId` strictly less than the applied sequence (3 < 5); it never calls upsert with `eventId` exactly equal to the applied sequence, despite its name implying both cases.
- **Fix task**: Add an assertion (or a 3rd case in the existing test) that upserts with `eventId` equal to the already-applied sequence and confirms zero change, matching OCUP-02's literal "menor ou igual" wording.
- **Priority**: Major

### Fix 3: OCUP-06/07 "no retry" guarantee unverified end-to-end for `EventoInvalidoException` (surviving mutant #3)

- **Root cause**: `VagaLiberadaConsumerIT` only proves immediate-DLQ-without-retry for `MessageConversionException` (malformed JSON); it never publishes a well-formed JSON message with a missing required field or negative `vagasDisponiveis` through the real broker to prove `EventoInvalidoException`'s non-retryable classification actually holds end-to-end.
- **Fix task**: Add a test to `VagaLiberadaConsumerIT` that publishes a valid-JSON-but-semantically-invalid `VagaLiberadaEvent` (e.g., `turmaId=null` or `vagasDisponiveis=-1`) and asserts it lands in `relatorios.ocupacao.dlq` with `verify(repository, never()).upsert(...)` (0 retries, not 3).
- **Priority**: Major

### Fix 4 (minor): OCUP-10 "sem SET LOCAL definido" sub-case and independent-turma concurrency edge case untested

- **Root cause**: `rlsEscondeLinhaDeOutroMunicipio` only covers "wrong tenant set", not "no tenant set at all"; no test covers two different `turmaId`s processed concurrently.
- **Fix task**: Optional — add a test reading `OcupacaoTurma` inside a transaction with no `SET LOCAL app.current_tenant` call at all, and document/assert the actual observed behavior (error vs. empty result). Add a concurrency test with two distinct `turmaId`s if this needs stronger proof.
- **Priority**: Minor

---

## Requirement Traceability Update

| Requirement | Previous Status | New Status |
| --- | --- | --- |
| OCUP-01 | Implementing | ✅ Verified |
| OCUP-02 | Implementing | ⚠️ Needs Fix (equal-boundary untested, mutant survived) |
| OCUP-03 | Implementing | ✅ Verified |
| OCUP-04 | Implementing | ✅ Verified |
| OCUP-05 | Implementing | ⚠️ Spec-precision gap (idempotent re-declaration not tested) |
| OCUP-06 | Implementing | ⚠️ Needs Fix (missing-field sub-case not proven end-to-end) |
| OCUP-07 | Implementing | ⚠️ Needs Fix (negative-quota sub-case not proven end-to-end) |
| OCUP-08 | Implementing | ✅ Verified |
| OCUP-09 | Implementing | ✅ Verified |
| OCUP-10 | Implementing | ⚠️ Spec-precision gap ("sem SET LOCAL" sub-case not proven) |

---

## Summary

**Overall**: ❌ Not Ready

**Spec-anchored check**: 5/10 ACs cleanly matched spec outcome (OCUP-01, 03, 04, 08, 09); 3 partial-coverage gaps traced to surviving mutants or untested sub-branches (OCUP-02, 06, 07); 2 spec-precision gaps (OCUP-05, 10)
**Sensor**: 1/3 mutations killed - 2 survived
**Gate**: 4 passed via the literal documented command, 0 failed — but 8 additional tests (both IT classes, covering the majority of the ACs) never execute under that command at all

**What works**: The core upsert/idempotency/concurrency/RLS mechanics (`OcupacaoTurmaRepository`, `schema.sql`, the `alocacreche_app` non-superuser role) are correctly implemented and, when run explicitly, all 12 tests in the module pass. The listener's validation and tenant-setting logic, the RabbitMQ topology, and the transient-failure retry/DLQ path (OCUP-08/09) are solid with real evidence.

**Issues found**:
1. `mvn -pl alocacreche-relatorios test` (the tasks.md "Full" gate) does not execute `OcupacaoTurmaRepositoryIT` or `VagaLiberadaConsumerIT` at all — add `maven-failsafe-plugin` or adjust Surefire includes, and correct the Gate Check Commands table.
2. OCUP-02's "menor ou igual" equal-sequence boundary is untested — add the missing assertion.
3. OCUP-06/07's "no retry" guarantee is unverified end-to-end for `EventoInvalidoException` specifically — add an e2e test publishing a valid-JSON/invalid-payload message.
4. (Minor) OCUP-10's "no SET LOCAL at all" sub-case and the independent-turma concurrency edge case are untested.

**Next steps**: Route Fix 1-3 back to an implementer as fix tasks (Fix 4 optional/minor), then re-run this Verifier. Bounded to 3 fix→re-verify iterations per the skill's process before escalating to the user.

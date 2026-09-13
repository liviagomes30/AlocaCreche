# Consumo do evento VagaLiberada e projeção de ocupação Validation

**Date**: 2026-09-13
**Spec**: `.specs/features/relatorios-ocupacao-vaga-liberada/spec.md`
**Diff range**: `7ad51ef..fa99510` (this round's fix commit; cumulative feature range `240bd4f..fa99510`)
**Verifier**: independent sub-agent (author ≠ verifier)
**Round**: 3 of max 3 (fix→re-verify) — final allowed round. Re-derives everything from scratch
against the code as it stands after commit `fa99510` ("fix(relatorios): corrige retry indevido de
EventoInvalidoException"). Does **not** trust the Round 1/2 reports' conclusions or this agent's own
non-existent memory of them; every finding below was independently re-run this session.

---

## What changed since Round 2 (commit `7ad51ef` → `fa99510`)

- `RabbitMqConfig.java`: removed the catch-all `retryableExceptions.put(Exception.class, true)`
  entry; switched `SimpleRetryPolicy` to the 4-argument constructor
  `SimpleRetryPolicy(3, retryableExceptions, true, true)` (`traverseCauses=true`,
  `defaultValue=true`) so unlisted exceptions still retry by default without a catch-all shadowing
  the specific `EventoInvalidoException`/`MessageConversionException` entries.
- `VagaLiberadaConsumerIT.java`: added `@MockitoSpyBean private VagaLiberadaListener listener;` and
  `verify(listener, times(1)).receber(argThat(...))` to
  `payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio` — the decisive signal
  Round 2 said was missing.
- `.specs/STATE.md`: new AD-001 gotcha documenting the catch-all root cause and the "count
  invocations, not side effects" lesson.

---

## Task Completion

| Task | Status | Notes |
| --- | --- | --- |
| T1-T8 | ✅ Done | Unchanged from Rounds 1-2 |
| Round-2 Fix (OCUP-06/07 decisive signal for `EventoInvalidoException`) | ✅ **Closed** | Independently re-verified this round — see Discrimination Sensor Mutations 1-2 |

---

## Spec-Anchored Acceptance Criteria

| Criterion (WHEN X THEN Y) | Spec-defined outcome | `file:line` + assertion expression | Result |
| --- | --- | --- | --- |
| **OCUP-01** upsert de `vagasDisponiveis`, `crecheId`, `municipioId`, `eventId` por `turmaId` | Linha gravada com os 4 campos + chave `turmaId` | `OcupacaoTurmaRepositoryIT.java:74-84` `upsertCriaLinhaQuandoTurmaNaoExiste` - `assertThat(turma.getVagasDisponiveis()).isEqualTo(5); ...getCrecheId()...isEqualTo(10L); ...getMunicipioId()...isEqualTo(1L)`; e2e `VagaLiberadaConsumerIT.java:84-96` `eventoValidoAtualizaProjecao` | ✅ PASS |
| **OCUP-02** `eventId` ≤ última sequência aplicada → descarta sem erro, sem alterar | Zero alteração para `eventId` menor **ou igual** | `OcupacaoTurmaRepositoryIT.java:96-104` (`<`) e `:106-114` (`=`) | ✅ PASS |
| **OCUP-03** duas mensagens concorrentes, mesmo `turmaId` → só a maior sequência prevalece | Resultado final = maior `eventId` | `OcupacaoTurmaRepositoryIT.java:116-137` `upsertConcorrenteAplicaSoAMaiorSequencia` - `assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(10L); ...getVagasDisponiveis()...isEqualTo(7)` | ✅ PASS |
| **OCUP-04** `SET LOCAL app.current_tenant` = `municipioId` antes de gravar/ler, mesma transação | `SET LOCAL` chamado com o valor exato do `municipioId`, antes do upsert | `VagaLiberadaListenerTest.java:38-49` `payloadValidoAplicaTenantEDelegaAoRepositorio` - `verify(entityManager).createNativeQuery("SET LOCAL app.current_tenant = '7'"); verify(repository).upsert(100L, 7L, 10L, 5, 1L)`; e2e `VagaLiberadaConsumerIT.java:84-96` | ✅ PASS — minor note: the unit test verifies both calls happened with the right values but does not use Mockito `InOrder` to assert `SET LOCAL` strictly precedes `upsert`; ordering is only guaranteed by reading `VagaLiberadaListener.java:26-37` (sequential statements). Not blocking — same as Rounds 1-2. |
| **OCUP-05** declarar exchange/fila/DLQ na inicialização, idempotente | Beans declarados; reinício não falha se já existirem | `RabbitMqConfig.java:41-76` (beans); uso real em `VagaLiberadaConsumerIT` | ⚠️ **Spec-precision gap (unchanged from Rounds 1-2)** — declaration itself is proven by the e2e test successfully consuming from the declared queue; "does not fail on re-declare against an already-provisioned broker" is never exercised (no test restarts the context against a live broker) |
| **OCUP-06** payload não desserializável OU campo faltando → DLQ direto, sem retry | Mensagem na DLQ imediatamente (0 tentativas extras) | Malformed JSON: `VagaLiberadaConsumerIT.java:99-117` `payloadMalformadoVaiDiretoParaDlqSemReentrega` - `assertThat(naDlq).isNotNull(); assertThat(naFilaPrincipal).isNull()`. Missing field: unit only, `VagaLiberadaListenerTest.java:52-60` | ❌ **GAP (NEW finding this round)** — see Discrimination Sensor Mutation 3. The malformed-JSON sub-case's assertions are invariant to retry count: the sensor proves this test still passes when the `MessageConversionException` non-retryable classification is deleted (i.e. when malformed JSON is silently retried 3× before landing in the DLQ instead of being rejected immediately). This is the exact same class of gap Round 2 found and fixed for `EventoInvalidoException` — but the fix in `fa99510` only added the decisive signal for the `EventoInvalidoException` path (`payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio`), not for the `MessageConversionException` path. The `@MockitoSpyBean` on `VagaLiberadaListener` cannot detect this because malformed JSON fails during message **conversion**, before `listener.receber()` is ever invoked — spying on the listener yields 0 invocations regardless of retry count for this specific sub-case. |
| **OCUP-07** `vagasDisponiveis` negativo → mesma regra (DLQ, sem retry) | Mesma regra do item acima | Unit: `VagaLiberadaListenerTest.java:62-71`. E2E (decisive signal): `VagaLiberadaConsumerIT.java:119-136` `payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio` - `verify(listener, times(1)).receber(argThat(e -> turmaId.equals(e.turmaId())))` | ✅ **PASS — Round 2 gap genuinely closed.** Confirmed by Discrimination Sensor Mutations 1 and 2 below: both the reintroduced catch-all bug and a flipped `defaultValue` are killed by this exact assertion. |
| **OCUP-08** falha transitória → nack com requeue, nova tentativa | Repositório chamado de novo após falha | `VagaLiberadaConsumerIT.java:139-153` `falhaTransitoriaVaiParaDlqApenasAposTresTentativas` - `verify(repository, times(3)).upsert(...)` | ✅ PASS |
| **OCUP-09** 3 tentativas consecutivas por causa transitória → DLQ | DLQ recebe só após a 3ª tentativa | Same test - `assertThat(naDlq).isNotNull(); verify(repository, times(3))...` | ✅ PASS — confirmed by Discrimination Sensor Mutation 2 (flipping `defaultValue` to `false` breaks the "3 attempts" guarantee and this exact test catches it) |
| **OCUP-10** RLS - leitura sem `SET LOCAL`/tenant errado não retorna linhas de outro município | Zero linhas / fail-closed para tenant incorreto ou ausente | Wrong tenant: `OcupacaoTurmaRepositoryIT.java:181-194` `rlsEscondeLinhaDeOutroMunicipio`. No `SET LOCAL`: `OcupacaoTurmaRepositoryIT.java:159-179` `semSetLocalDefinidoFalhaFechadoEmVezDeVazarDados` | ✅ PASS |

**Status**: ❌ Gaps present — 8/10 criteria PASS cleanly; OCUP-06 has a newly-discovered surviving-mutant
gap for its "não puder ser desserializado" sub-case; OCUP-05 carries an unchanged, non-blocking
spec-precision gap.

---

## Discrimination Sensor

Isolated via `git worktree add C:/repos-pessoal/creche-verify-scratch HEAD` (outside the real tree,
`HEAD` = `fa99510`). `git status --porcelain` on the real tree confirmed empty immediately before
creating the worktree and again immediately after `git worktree remove --force`. `git stash` was
never used. All mutations were applied only to files inside the scratch worktree; each was tested
individually (reverted before the next).

| # | File:line | Description | Killed? |
| --- | --- | --- | --- |
| 1 | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:100` | Re-injected the exact Round-2 bug: added back `retryableExceptions.put(Exception.class, true)` catch-all | ✅ **Killed** — `mvn -pl alocacreche-relatorios verify -Dit.test=VagaLiberadaConsumerIT` in the scratch worktree: `Tests run: 4, Failures: 1`. `payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio` fails with `TooManyActualInvocations: Wanted 1 time... But was 3 times` on `verify(listener, times(1)).receber(...)`. Proves the Round-2 fix's decisive signal genuinely works. |
| 2 | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:112` | Flipped `SimpleRetryPolicy`'s `defaultValue` argument from `true` to `false` | ✅ **Killed** — same command: `Tests run: 4, Failures: 1`. `falhaTransitoriaVaiParaDlqApenasAposTresTentativas` fails with `TooFewActualInvocations: Wanted 3 times... But was 1 time` on `verify(repository, times(3)).upsert(...)`. Proves OCUP-08/09's "3 attempts before DLQ" guarantee is genuinely enforced by a real test. |
| 3 | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:100` | Removed the `retryableExceptions.put(MessageConversionException.class, false)` entry entirely | ❌ **Survived** — same command: `Tests run: 4, Failures: 0`, `BUILD SUCCESS`. Under this mutation, malformed JSON is now classified retryable-by-default (`defaultValue=true`) and is retried 3× before `RejectAndDontRequeueRecoverer` sends it to the DLQ — a real behavior change (violates OCUP-06's "sem tentativa de reprocessamento" for the deserialization sub-case) — but `payloadMalformadoVaiDiretoParaDlqSemReentrega`'s only assertions (`naDlq` not null, `naFilaPrincipal` null) are true in both the correct and the mutated configuration, so the mutant is invisible to the suite. |

**Sensor depth**: lightweight (3 targeted mutations — 2 requested in the task brief plus the exact
Round-2 regression re-check, all directly relevant to OCUP-06/07/08/09)
**Result**: 2/3 killed — ❌ **FAIL** (1 new surviving mutant found this round)

---

## Code Quality

| Principle | Status |
| --- | --- |
| Minimum code | ✅ — fix commit `fa99510` only touches `RabbitMqConfig.java`, `VagaLiberadaConsumerIT.java`, and spec/state/lessons bookkeeping |
| Surgical changes | ✅ |
| No scope creep | ✅ |
| Matches patterns | ✅ — new spy follows the same `@MockitoSpyBean` pattern already used for the repository spy |
| Spec-anchored outcome check (asserted values match spec) | ⚠️ — matches for the `EventoInvalidoException` path (Round-2 gap genuinely fixed); does NOT match for the `MessageConversionException`/malformed-JSON path, which needed the identical treatment but didn't get it |
| Per-layer Coverage Expectation met (domain 1:1 ACs; routes happy+edge+error) | ❌ — OCUP-06's deserialization sub-case still has no test that discriminates immediate-rejection from retry-then-reject |
| Every test maps to a spec requirement - no unclaimed tests | ✅ |
| Documented guidelines followed: CLAUDE.md (constructor injection, Portuguese comments, no end-of-line comments) | ✅ — spot-checked `RabbitMqConfig.java` and `VagaLiberadaConsumerIT.java` diff; comments in Portuguese, header-style, no end-of-line comments |

---

## Edge Cases

- [x] Fila/exchange não existirem no broker → declarados na inicialização: covered
- [ ] `vagasDisponiveis` negativo → rejeita para DLQ **sem retry**: now genuinely covered (decisive signal)
- [ ] Payload não desserializável → rejeita para DLQ **sem retry**: **NOT covered** for the "without retry" part specifically (see OCUP-06 gap)
- [x] Duas mensagens para turmas diferentes chegam ao mesmo tempo → processadas de forma independente: covered

---

## Gate Check

- **Gate command** (from tasks.md, "Full"): `mvn -pl alocacreche-relatorios verify`
- **Result**: 16 passed, 0 failed, 0 skipped (4 via Surefire: 1 smoke + 3 `VagaLiberadaListenerTest`; 12 via Failsafe: 8 `OcupacaoTurmaRepositoryIT` + 4 `VagaLiberadaConsumerIT`)
- **Test count before feature**: 1 (smoke test only)
- **Test count after feature (this round)**: 16 — unchanged from Round 2 (`fa99510` did not add or remove any test, only added one assertion line to an existing test)
- **Delta**: +15 vs. pre-feature baseline
- **Skipped tests**: none
- **Failures**: none on the real tree

Run verbatim on the real tree (`C:\repos-pessoal\creche`), JDK 21 + Maven 3.9.16, Docker available:
`EXIT_CODE=0`, `BUILD SUCCESS`.

---

## Fix Plans (if issues found)

### Fix 1 (NEW, Blocker): OCUP-06's "no retry" guarantee for malformed/undeserializable JSON is unproven end-to-end

- **Root cause**: `payloadMalformadoVaiDiretoParaDlqSemReentrega` (`VagaLiberadaConsumerIT.java:99-117`)
  only asserts final message location (DLQ vs. main queue), which is identical whether the message
  is rejected immediately or retried 3× first. The Round-2/Round-3 fix added a listener-invocation-count
  spy, but that signal is structurally blind to this sub-case: `MessageConversionException` is thrown
  during message **conversion**, before `VagaLiberadaListener.receber()` is ever invoked, so
  `verify(listener, ...)` always reports 0 invocations regardless of retry count.
- **Fix task**: Add a decisive signal for this sub-case specifically. Options, in order of directness:
  1. Spy on the `MessageConverter` bean (`@MockitoSpyBean private MessageConverter messageConverter;`)
     and assert `verify(messageConverter, times(1)).fromMessage(any())` for the malformed message —
     mirrors the listener-spy pattern already established for the `EventoInvalidoException` path.
  2. Alternatively, spy on/wrap the `SimpleMessageListenerContainer`'s error handler or read the
     RabbitMQ `x-death` header count off the DLQ message (note: internal Spring Retry attempts happen
     within a single broker delivery, so `x-death.count` will likely be `1` either way — verify this
     assumption empirically before relying on it; option 1 is more likely to be decisive).
  3. Apply the identical fix pattern to any future non-retryable-exception classification added to
     `RabbitMqConfig` — this is now the second time this exact shape of gap has appeared for two
     different exception types in the same map.
- **Priority**: Blocker — this is the final allowed automated round (3 of 3); this gap must either be
  fixed now or explicitly escalated to the user, per the skill's 3-iteration bound.

### Fix 2 (carried over, unchanged priority): OCUP-05 idempotent re-declaration not tested

- **Root cause**: No test restarts the Spring context against an already-provisioned broker to confirm
  `RabbitAdmin`'s declaration is idempotent; relies on unverified default AMQP behavior.
- **Fix task**: Optional — add a test that runs declaration twice against the same broker/queues and
  asserts no exception is thrown.
- **Priority**: Minor / spec-precision gap (not blocking, unchanged since Round 1)

---

## Requirement Traceability Update

| Requirement | Previous Status (Round 2) | New Status (Round 3) |
| --- | --- | --- |
| OCUP-01 | ✅ Verified | ✅ Verified |
| OCUP-02 | ✅ Verified | ✅ Verified |
| OCUP-03 | ✅ Verified | ✅ Verified |
| OCUP-04 | ✅ Verified | ✅ Verified |
| OCUP-05 | ⚠️ Spec-precision gap | ⚠️ Spec-precision gap (unchanged) |
| OCUP-06 | ❌ Needs Fix | ❌ **Needs Fix (new specific gap: deserialization sub-case, not the field-missing sub-case)** |
| OCUP-07 | ❌ Needs Fix | ✅ **Verified — Round 2/3 fix confirmed effective by sensor** |
| OCUP-08 | ✅ Verified | ✅ Verified (sensor-confirmed this round) |
| OCUP-09 | ✅ Verified | ✅ Verified (sensor-confirmed this round) |
| OCUP-10 | ✅ Verified | ✅ Verified |

---

## Summary

**Overall**: ❌ Not Ready

**Spec-anchored check**: 9/10 ACs cleanly matched spec outcome (up from 8/10 in Round 2 — OCUP-07 is
now genuinely closed); 1 surviving-mutant gap remains, but it has shifted: OCUP-07 (the exact bug this
round's commit targeted) is now solid, while OCUP-06's deserialization sub-case — which shares the
same code shape (a non-retryable classification entry in the same map) — was never given the
equivalent fix and is a newly-identified gap. 1 unchanged spec-precision gap (OCUP-05).

**Sensor**: 2/3 mutations killed — 1 survived (a new mutation this round, not a repeat of the Round-2 mutant)

**Gate**: 16 passed, 0 failed — `mvn -pl alocacreche-relatorios verify` on the real tree, confirmed green

**What works**: The Round-2 blocker is genuinely fixed — `fa99510`'s catch-all removal plus the
listener-invocation-count spy is proven, by independent re-injection of the exact same bug, to
actually kill the mutant now (Mutations 1 and 2 above). OCUP-01 through OCUP-05 and OCUP-08 through
OCUP-10 all hold up under spec-anchored review and/or sensor testing.

**Issues found**:
1. (Blocker, NEW) OCUP-06's "no retry" guarantee for malformed/undeserializable JSON is not proven
   end-to-end — same class of gap as the one just fixed for OCUP-07, but for the
   `MessageConversionException` entry instead of `EventoInvalidoException`. See Fix 1 above.
2. (Minor, unchanged) OCUP-05's idempotent-redeclaration-on-restart property remains untested.

**Next steps**: This is round 3 of the bounded 3-iteration fix→re-verify loop — per
`references/sub-agents.md`, if gaps remain after 3 rounds, escalate to the user rather than
auto-dispatching a 4th fix. Recommend presenting Fix 1 to the user as a manual follow-up task (it is
narrowly scoped and low-risk: add a `MessageConverter` spy assertion mirroring the existing listener
spy) rather than continuing the automated loop.

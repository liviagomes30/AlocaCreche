# Consumo do evento VagaLiberada e projeção de ocupação Validation

**Date**: 2026-09-13
**Spec**: `.specs/features/relatorios-ocupacao-vaga-liberada/spec.md`
**Diff range**: `240bd4f..HEAD` (HEAD = `7ad51ef`)
**Verifier**: independent sub-agent (author ≠ verifier)
**Round**: 2 of max 3 (fix→re-verify) — re-derives coverage from scratch against the code as it
stands after commit `7ad51ef` ("fix(relatorios): close gaps found by the independent Verifier").
Does **not** trust the Round 1 report's conclusions; every finding below was independently
re-run this session.

---

## What changed since Round 1 (commit `7ad51ef`)

- `alocacreche-relatorios/pom.xml`: added `maven-failsafe-plugin` bound to `integration-test`/`verify`.
- `OcupacaoTurmaRepositoryIT.java`: split the menor-ou-igual test into two (`upsertNaoAplicaQuandoSequenciaRecebidaEMenor` / `upsertNaoAplicaQuandoSequenciaRecebidaEIgual`), added `upsertConcorrenteParaTurmasDiferentesNaoInterfereEntreSi` and `semSetLocalDefinidoFalhaFechadoEmVezDeVazarDados` (8 tests total, was 5).
- `VagaLiberadaConsumerIT.java`: added `payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio` (4 tests total, was 3).
- `.specs/STATE.md`: new `AD-003` (Failsafe required for `*IT` classes).
- `tasks.md`: "Full" gate command corrected to `mvn -pl alocacreche-relatorios verify`.

---

## Task Completion

| Task | Status  | Notes |
| ---- | ------- | ----- |
| T1-T8 | ✅ Done | Unchanged from Round 1 — all 8 tasks' own "Done when" checklists satisfied |
| Fix 1 (gate) | ✅ Done | `maven-failsafe-plugin` added, gate corrected to `verify` |
| Fix 2 (OCUP-02 equal boundary) | ✅ Done | New test added, mutant re-run confirms it now discriminates |
| Fix 3 (OCUP-06/07 e2e non-retry proof) | ❌ **Not actually closed** | New test added, but re-running the same mutation independently shows it still survives — see Discrimination Sensor |
| Fix 4 (SET LOCAL / independent-turma) | ✅ Done | Both new tests added and pass |

---

## Spec-Anchored Acceptance Criteria

| Criterion (WHEN X THEN Y) | Spec-defined outcome | `file:line` + assertion expression | Result |
| --- | --- | --- | --- |
| **OCUP-01** upsert de `vagasDisponiveis`, `crecheId`, `municipioId`, `eventId` por `turmaId` | Linha gravada com os 4 campos + chave `turmaId` | `OcupacaoTurmaRepositoryIT.java:75-84` `upsertCriaLinhaQuandoTurmaNaoExiste` - `assertThat(turma.getVagasDisponiveis()).isEqualTo(5); ...getCrecheId()...isEqualTo(10L); ...getMunicipioId()...isEqualTo(1L)`; e2e `VagaLiberadaConsumerIT.java:82-93` `eventoValidoAtualizaProjecao` via routing key real `vaga.liberada` | ✅ PASS |
| **OCUP-02** `eventId` ≤ última sequência aplicada → descarta sem erro, sem alterar | Zero alteração para `eventId` menor **ou igual** | `OcupacaoTurmaRepositoryIT.java:96-104` `upsertNaoAplicaQuandoSequenciaRecebidaEMenor` (caso `<`) **e** `OcupacaoTurmaRepositoryIT.java:106-114` `upsertNaoAplicaQuandoSequenciaRecebidaEIgual` (caso `=`, NOVO) - `assertThat(turma.getVagasDisponiveis()).isEqualTo(5); assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(5L)` | ✅ PASS - fronteira de igualdade agora coberta; mutante #1 (`<` → `<=` em `OcupacaoTurmaRepository.java:25`) re-executado nesta rodada e **morto** (`upsertNaoAplicaQuandoSequenciaRecebidaEIgual` falha com o mutante) |
| **OCUP-03** duas mensagens concorrentes, mesmo `turmaId` → só a maior sequência prevalece | Resultado final = maior `eventId` | `OcupacaoTurmaRepositoryIT.java:116-137` `upsertConcorrenteAplicaSoAMaiorSequencia` - `assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(10L); assertThat(turma.getVagasDisponiveis()).isEqualTo(7)` | ✅ PASS |
| **OCUP-04** `SET LOCAL app.current_tenant` = `municipioId` antes de gravar/ler, mesma transação | `SET LOCAL` chamado com o valor exato do `municipioId` do payload | `VagaLiberadaListenerTest.java:39-49` `payloadValidoAplicaTenantEDelegaAoRepositorio` - `verify(entityManager).createNativeQuery("SET LOCAL app.current_tenant = '7'"); verify(repository).upsert(100L, 7L, 10L, 5, 1L)`; e2e `VagaLiberadaConsumerIT.java:82-93` (só encontra a linha lendo com o mesmo tenant) | ✅ PASS |
| **OCUP-05** declarar exchange/fila/DLQ na inicialização, idempotente | Beans declarados; reinício não falha se já existirem | `RabbitMqConfig.java:41-76` (beans); uso real em `VagaLiberadaConsumerIT` | ⚠️ **Spec-precision gap (carregado do Round 1, sem mudança)** - a declaração em si é comprovada; a propriedade "não falha se já existir" ao reiniciar contra um broker já provisionado nunca é exercitada por teste - depende de comportamento padrão do `RabbitAdmin`, não testado |
| **OCUP-06** payload não desserializável OU campo faltando → DLQ direto, sem retry | Mensagem na DLQ imediatamente (0 tentativas extras) | JSON malformado: `VagaLiberadaConsumerIT.java:96-114` `payloadMalformadoVaiDiretoParaDlqSemReentrega` - `assertThat(naDlq).isNotNull(); assertThat(naFilaPrincipal).isNull()` (PASS, subcaso não afetado pelas correções desta rodada). Campo faltando: só unitário, `VagaLiberadaListenerTest.java:52-60` `campoObrigatorioFaltandoLancaExcecaoSemChamarRepositorio` | ❌ **GAP (não fechado)** - subcaso "campo obrigatório faltando" continua só unitário, nunca fim-a-fim contra o broker real; o novo teste e2e adicionado (Fix 3) cobre um payload diferente (`vagasDisponiveis` negativo, ver OCUP-07), não este |
| **OCUP-07** `vagasDisponiveis` negativo → mesma regra (DLQ, sem retry) | Mesma regra do item acima | Unit: `VagaLiberadaListenerTest.java:62-71`. E2E (NOVO): `VagaLiberadaConsumerIT.java:117-128` `payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio` - `assertThat(naDlq).isNotNull(); verify(repository, never()).upsert(...)` | ❌ **GAP (Fix 3 não resolveu)** - re-executei o mutante #3 (`EventoInvalidoException` classificado `true`/retryable em `RabbitMqConfig.java:96`) de forma independente nesta rodada: **sobrevive de novo**. O teste novo só afirma "DLQ não-nulo" e "repositório nunca chamado" - ambas as afirmações são invariantes à mutação, porque a exceção é lançada **antes** do repositório ser tocado (então `never()` é verdade em ambos os casos) e a mensagem eventualmente chega na DLQ de qualquer forma, só que após esgotar as 3 tentativas de retry em vez de rejeição imediata. Confirmei isso lendo o log da execução mutada: aparecem 3 mensagens `"Execution of Rabbit message listener failed"` + `"Retry Policy Exhausted"` antes da rejeição - ou seja, o comportamento observável realmente mudou (3 tentativas em vez de 0), mas nenhuma asserção do teste captura essa diferença |
| **OCUP-08** falha transitória → nack com requeue, nova tentativa | Repositório chamado de novo após falha | `VagaLiberadaConsumerIT.java:131-145` `falhaTransitoriaVaiParaDlqApenasAposTresTentativas` - `verify(repository, times(3)).upsert(...)` | ✅ PASS |
| **OCUP-09** 3 tentativas consecutivas por causa transitória → DLQ | DLQ recebe só após a 3ª tentativa | `VagaLiberadaConsumerIT.java:131-145` - `Message naDlq = rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_DLQ, 5_000); assertThat(naDlq).isNotNull(); verify(repository, times(3))...` | ✅ PASS |
| **OCUP-10** RLS - leitura sem `SET LOCAL`/tenant errado não retorna linhas de outro município | Zero linhas / comportamento fail-closed para tenant incorreto ou ausente | Tenant errado: `OcupacaoTurmaRepositoryIT.java:182-194` `rlsEscondeLinhaDeOutroMunicipio` - `assertThat(resultadoComoTenantErrado).isEmpty()`. Sem `SET LOCAL` (NOVO): `OcupacaoTurmaRepositoryIT.java:160-179` `semSetLocalDefinidoFalhaFechadoEmVezDeVazarDados` - `assertThatThrownBy(...).isInstanceOf(RuntimeException.class)` | ✅ PASS - gap fechado. Nota: o comportamento real observado é uma **exceção** do Postgres (GUC não definida), não uma lista vazia silenciosa; isso ainda satisfaz a garantia da spec ("não retorna linhas de outro município" - falhar fechado é uma implementação válida, e mais estrita, dessa garantia), mas diverge da leitura mais literal do texto ("não retorne linhas" sugere execução bem-sucedida com resultado vazio). Comportamento agora documentado e testado, o que é a melhoria pedida |

**Status**: ❌ Gaps present (1 unresolved surviving-mutant gap carried from Round 1: OCUP-06/07's "no retry" guarantee for `EventoInvalidoException` remains unproven end-to-end despite the Round-1 fix attempt; 1 unchanged spec-precision gap: OCUP-05). 8/10 criteria now cleanly PASS (up from 5/10 in Round 1); OCUP-02 and OCUP-10 gaps from Round 1 are genuinely closed.

---

## Discrimination Sensor

Isolated via `git worktree add <scratch-path> HEAD` at `C:/repos-pessoal/creche-scratch-verify2` (outside the real tree); mutations applied only to files inside the scratch worktree; `git status --porcelain` on the real tree (`C:/repos-pessoal/creche`) confirmed empty immediately before creating the worktree and again immediately after `git worktree remove --force`. `git stash` was never used.

Scope: this round re-runs only the 2 mutations that survived in Round 1 (per the task), applied one at a time (the other reverted) so each result is unambiguous.

| Mutation | File:line | Description | Killed? |
| --- | --- | --- | --- |
| 1 (re-run) | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepository.java:25` | Flipped upsert guard `WHERE ... ultima_sequencia_aplicada < EXCLUDED.ultima_sequencia_aplicada` → `<=` | ✅ **Killed** - `mvn -pl alocacreche-relatorios verify -Dit.test=OcupacaoTurmaRepositoryIT` in the scratch worktree: `Tests run: 8, Failures: 1` — `OcupacaoTurmaRepositoryIT.upsertNaoAplicaQuandoSequenciaRecebidaEIgual:112` fails (`invalid input syntax for type bigint: ""`-adjacent assertion mismatch: `vagasDisponiveis` changed from 5 to the mutated update value). Round-1 gap confirmed closed. |
| 3 (re-run) | `alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:96` | Flipped retry classification `retryableExceptions.put(EventoInvalidoException.class, false)` → `true` | ❌ **Survived again** - `mvn -pl alocacreche-relatorios verify -Dit.test=VagaLiberadaConsumerIT` in the scratch worktree: `Tests run: 4, Failures: 0` — `payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio` (added in the Round-1 fix specifically to catch this) still passes under the mutation. Log evidence from the mutated run shows 3 distinct `"Execution of Rabbit message listener failed"` / `"Retry Policy Exhausted"` entries before DLQ delivery (vs. immediate rejection on the real code), proving the behavior genuinely changed - but no assertion in the test observes attempt count, so it doesn't fail. |

**Sensor depth**: lightweight (2 targeted re-runs, default tier; scope bounded to the 2 previously-surviving mutations per this round's task)
**Result**: 1/2 killed - ❌ **FAIL** (1 mutant still survives; Round-1 Fix 3 did not actually close this gap)

---

## Code Quality

| Principle | Status |
| --- | --- |
| Minimum code | ✅ - fix commit `7ad51ef` only touches `pom.xml`, the 2 IT test files, and spec/state bookkeeping |
| Surgical changes | ✅ - no unrelated files touched |
| No scope creep | ✅ |
| Matches patterns | ✅ - new tests follow the same `TransactionTemplate`/`@MockitoSpyBean` patterns already in the file |
| Spec-anchored outcome check (asserted values match spec) | ⚠️ - matches where covered; OCUP-06/07's e2e "no retry" assertion targets the wrong signal (repository-call absence / DLQ presence, both invariant to the mutation) instead of attempt count |
| Per-layer Coverage Expectation met (domain 1:1 ACs; routes happy+edge+error) | ❌ - the messaging layer (where the "no retry" guarantee for `EventoInvalidoException` actually lives) still has no test that discriminates retryable vs non-retryable classification for this exception type |
| Every test maps to a spec requirement - no unclaimed tests | ✅ |
| Documented guidelines followed: CLAUDE.md (constructor injection, Portuguese comments, no end-of-line comments) | ✅ - spot-checked the 4 new/modified test methods and the `pom.xml` comment block; all comments in Portuguese, header-style, no end-of-line comments |

---

## Edge Cases

- [x] Fila/exchange não existirem no broker → declarados na inicialização: covered (unchanged from Round 1)
- [ ] `vagasDisponiveis` negativo → rejeita para DLQ **sem retry**: unit-covered for "throws"; e2e-covered for "DLQ presence" but NOT for "without retry" specifically (see OCUP-07 gap above)
- [x] Duas mensagens para turmas diferentes chegam ao mesmo tempo → processadas de forma independente: **NOW covered** - `OcupacaoTurmaRepositoryIT.java:140-157` `upsertConcorrenteParaTurmasDiferentesNaoInterfereEntreSi` (Round-1 gap closed)

---

## Gate Check

- **Gate command** (from tasks.md, "Full", corrected this round): `mvn -pl alocacreche-relatorios verify`
- **Result**: 16 passed, 0 failed, 0 skipped (4 via Surefire: 1 smoke + 3 `VagaLiberadaListenerTest`; 12 via Failsafe: 8 `OcupacaoTurmaRepositoryIT` + 4 `VagaLiberadaConsumerIT`)
- **Test count before feature**: 1 (`AlocaCrecheRelatoriosApplicationTests` smoke test only)
- **Test count after feature (Round 1, literal gate)**: 4 (both IT classes silently skipped)
- **Test count after feature (Round 2, corrected gate)**: 16 - **all test classes in the module now execute under the single documented gate command**, confirmed by running it verbatim (not `-Dtest=`/`-Dit.test=` targeted) and observing `Running org.alocacreche.relatorios.ocupacao.OcupacaoTurmaRepositoryIT` and `Running org.alocacreche.relatorios.ocupacao.VagaLiberadaConsumerIT` in the output, each followed by a non-zero "Tests run" line
- **Delta**: +15 vs. pre-feature baseline
- **Skipped tests**: none
- **Failures**: none - the build-gate finding from Round 1 (Failsafe missing, `*IT` classes silently skipped) is confirmed fixed

---

## Fix Plans (if issues found)

### Fix carried over: OCUP-06/07 "no retry" guarantee still unverified end-to-end for `EventoInvalidoException` (mutant #3 survives again)

- **Root cause**: The Round-1 fix added `payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio`, but its assertions (`naDlq` not null, `verify(repository, never())`) are both true regardless of the retry classification for this exception, because `EventoInvalidoException` is thrown by the listener's validation logic **before** the repository is ever invoked - so `never()` holds under both the correct (non-retryable) and mutated (retryable) configurations, and the message still eventually reaches the DLQ either way (immediately vs. after 3 exhausted attempts).
- **Fix task**: Add an assertion that actually observes attempt count for this specific exception, not repository interaction. Options, in order of directness:
  1. Spy on the listener bean itself (`@MockitoSpyBean private VagaLiberadaListener listener;`) and assert `verify(listener, times(1)).receber(any())` (immediate rejection, one invocation) vs. what a retryable classification would produce (3 invocations) - mirrors the pattern already used for the repository spy in `falhaTransitoriaVaiParaDlqApenasAposTresTentativas`.
  2. Alternatively, read the RabbitMQ `x-death` header (`count`) off the DLQ message and assert it reflects zero requeues/retries.
  3. Do the same for the "missing required field" sub-case of OCUP-06, which currently has no e2e coverage at all (only the unit test).
- **Priority**: Blocker (this is the second consecutive round this specific mutant has survived - the underlying behavior guarantee for OCUP-06/07 is still not proven by any test that would fail if it regressed)

### Fix (carried over, unchanged priority): OCUP-05 idempotent re-declaration not tested

- **Root cause**: No test restarts the Spring context against an already-provisioned broker to confirm `RabbitAdmin`'s declaration is idempotent; this currently relies on unverified default AMQP behavior.
- **Fix task**: Optional - add a test that runs `RabbitAdmin.initialize()` (or restarts the context) a second time against the same broker/queues and asserts no exception is thrown.
- **Priority**: Minor / spec-precision gap (not blocking, same as Round 1)

---

## Requirement Traceability Update

| Requirement | Previous Status (Round 1) | New Status (Round 2) |
| --- | --- | --- |
| OCUP-01 | ✅ Verified | ✅ Verified |
| OCUP-02 | ⚠️ Needs Fix | ✅ Verified (mutant killed) |
| OCUP-03 | ✅ Verified | ✅ Verified |
| OCUP-04 | ✅ Verified | ✅ Verified |
| OCUP-05 | ⚠️ Spec-precision gap | ⚠️ Spec-precision gap (unchanged) |
| OCUP-06 | ⚠️ Needs Fix | ❌ Needs Fix (missing-field subcase still not e2e; malformed-JSON subcase unaffected/fine) |
| OCUP-07 | ⚠️ Needs Fix | ❌ Needs Fix (mutant #3 survived again - Round-1 fix ineffective) |
| OCUP-08 | ✅ Verified | ✅ Verified |
| OCUP-09 | ✅ Verified | ✅ Verified |
| OCUP-10 | ⚠️ Spec-precision gap | ✅ Verified (sub-case now tested, fail-closed behavior confirmed) |

---

## Summary

**Overall**: ❌ Not Ready

**Spec-anchored check**: 8/10 ACs cleanly matched spec outcome (up from 5/10 in Round 1); 1 surviving-mutant gap (OCUP-06/07, carried over and confirmed still open); 1 unchanged spec-precision gap (OCUP-05)
**Sensor**: 1/2 re-run mutations killed - 1 survived (same mutant as Round 1, despite an intervening fix attempt)
**Gate**: 16 passed, 0 failed - the Round-1 build-gate blocker (Failsafe never wired, both `*IT` classes silently skipped) is confirmed fixed; `mvn -pl alocacreche-relatorios verify` now genuinely executes all 16 tests in the module

**What works**: The build gate is now trustworthy (Fix 1 fully resolved and independently re-verified). The OCUP-02 equal-`eventId` boundary is now genuinely discriminated by a test (Fix 2 fully resolved - mutant killed). The OCUP-10 "no SET LOCAL" sub-case and the independent-turma concurrency edge case are both now covered (Fix 4 fully resolved).

**Issues found**:
1. (Blocker, carried over) OCUP-06/07's "no retry" guarantee for `EventoInvalidoException` is still not proven end-to-end - the Round-1 fix added a test but its assertions don't discriminate retry count; the same mutation that survived in Round 1 survives again in Round 2. Needs an assertion on attempt count (listener spy or DLQ `x-death` header), not repository-call absence.
2. (Minor, unchanged) OCUP-05's idempotent-redeclaration-on-restart property remains untested.

**Next steps**: Route the carried-over Fix (OCUP-06/07 attempt-count assertion) back to an implementer as a fix task - this is iteration 2 of the bounded 3-iteration fix→re-verify loop, so one more round is available before escalating to the user. Fix for OCUP-05 remains optional/minor.

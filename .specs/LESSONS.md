# LESSONS - auto-maintained by scripts/lessons.py

> Machine-owned. Do NOT hand-edit. Changes are overwritten on the next `lessons.py` write.
> Canonical state lives in `.specs/lessons.json`. Edit lessons only via the script.
> promote_threshold=2 distinct features · window_days=45 · quarantine_threshold=2

## Confirmed (load these at Specify/Design)

Corroborated across multiple features. Safe to apply as guidance.

_none_

## Candidates (under observation - do NOT load as guidance yet)

Seen once or not yet corroborated. Tracked, not trusted.

### L-001 - When an idempotency guard uses a strict inequality to reject duplicate/out-of-order events (e.g. WHERE applied < incoming), add a test case for the exact-equality boundary, not just the strictly-less-than case, or a </<= mutation survives silently.
- signal: `surviving_mutant` · recurrence: 1 feature(s) · scope: `repo-layer` · harmful: 0
- features: relatorios-ocupacao-vaga-liberada
- evidence: alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/ocupacao/OcupacaoTurmaRepository.java:25 (repo-layer)
- last seen: 2026-09-13T02:10:09Z

### L-002 - When classifying a domain exception as non-retryable for a message broker retry interceptor, add an end-to-end test that triggers that exact exception type through the real listener and broker, not only a unit test of the exception being thrown, or a retry-policy misclassification for that type survives undetected.
- signal: `surviving_mutant` · recurrence: 1 feature(s) · scope: `messaging` · harmful: 0
- features: relatorios-ocupacao-vaga-liberada
- evidence: alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:96 (messaging)
- last seen: 2026-09-13T02:10:09Z

### L-003 - When naming integration test classes with the *IT suffix, bind maven-failsafe-plugin to the integration-test/verify phases, because Maven Surefire's default test goal does not match *IT.java and mvn test silently reports success while skipping all IT coverage.
- signal: `gate_fail` · recurrence: 1 feature(s) · scope: `build` · harmful: 0
- features: relatorios-ocupacao-vaga-liberada
- evidence: alocacreche-relatorios/pom.xml (no maven-failsafe-plugin bound) (build)
- last seen: 2026-09-13T02:10:10Z

### L-004 - When an AC's WHEN-clause lists multiple distinct triggering sub-cases (e.g. 'sem SET LOCAL definido, ou com o tenant errado'), write one test per sub-case, not just the easiest one to simulate, since the untested sub-case can behave differently (error vs empty result) than assumed.
- signal: `spec_precision_gap` · recurrence: 1 feature(s) · scope: `multi-tenancy` · harmful: 0
- features: relatorios-ocupacao-vaga-liberada
- evidence: OCUP-10 (multi-tenancy)
- last seen: 2026-09-13T02:10:10Z

### L-005 - When proving a non-retryable exception classification end-to-end, assert on retry attempt count (a spy on the listener invocation count, or the broker's delivery/death header), not on downstream side-effect absence or final dead-letter-queue arrival, because both are identical whether the exception retries first or is rejected immediately.
- signal: `surviving_mutant` · recurrence: 1 feature(s) · scope: `messaging` · harmful: 0
- features: relatorios-ocupacao-vaga-liberada
- evidence: alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:96 (messaging) (+1 more)
- last seen: 2026-09-13T17:20:28Z

### L-006 - When a fix adds a decisive test signal for one exception type in a shared retry-classification map, add the equivalent decisive signal for every other entry in that same map, not just the one that was reported as broken, since sibling entries carry the identical unproven risk.
- signal: `surviving_mutant` · recurrence: 1 feature(s) · scope: `messaging` · harmful: 0
- features: relatorios-ocupacao-vaga-liberada
- evidence: alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java:100 (messaging)
- last seen: 2026-09-13T17:20:39Z

### L-007 - When an AC names both a match and a no-match branch for the same optional filter, add a dedicated test for the no-match branch instead of relying on a differently-scoped empty-list test to imply it.
- signal: `spec_precision_gap` · recurrence: 1 feature(s) · scope: `read-endpoints` · harmful: 0
- features: relatorios-leitura-ocupacao
- evidence: RELOC-03 - OcupacaoTurmaRepositoryIT.java:225-233, OcupacaoConsultaControllerIT.java:89-99 (read-endpoints)
- last seen: 2026-09-15T00:08:06Z

### L-008 - When a spec requires an error response to include an error message, assert on the response body content, not only the HTTP status code.
- signal: `spec_precision_gap` · recurrence: 1 feature(s) · scope: `read-endpoints` · harmful: 0
- features: relatorios-leitura-ocupacao
- evidence: RELOC-04 - OcupacaoConsultaControllerIT.java:101-116 (read-endpoints)
- last seen: 2026-09-15T00:08:06Z

### L-009 - When an AC lists multiple independent filter parameters as cross-tenant risks, add one cross-tenant test per parameter instead of testing only one and generalizing to the rest.
- signal: `spec_precision_gap` · recurrence: 1 feature(s) · scope: `multi-tenancy` · harmful: 0
- features: relatorios-leitura-ocupacao
- evidence: RELOC-06 - OcupacaoConsultaControllerIT.java:127-137 (multi-tenancy)
- last seen: 2026-09-15T00:08:06Z

### L-010 - When a spec requires an error path to skip a downstream call (DB/cache), assert that no call happened (spy/verify), not just the resulting HTTP status.
- signal: `spec_precision_gap` · recurrence: 1 feature(s) · scope: `relatorios` · harmful: 0
- features: relatorios-painel-publico
- evidence: PAINEL-02 / alocacreche-relatorios/src/test/java/org/alocacreche/relatorios/ocupacao/PainelPublicoControllerIT.java:69-84 (relatorios)
- last seen: 2026-09-15T14:57:34Z

### L-011 - Before writing a design.md rationale about a framework's internal error-handling behavior (e.g. Spring @Cacheable sync=true), verify it empirically against the actual pinned framework version's bytecode/source, not general web-search knowledge that may target an older version.
- signal: `surviving_mutant` · recurrence: 1 feature(s) · scope: `design-docs` · harmful: 0
- features: relatorios-painel-publico
- evidence: mutation 3 (sync=true on @Cacheable) / .specs/features/relatorios-painel-publico/design.md:124 (design-docs)
- last seen: 2026-09-15T14:57:34Z

## Quarantined (failed when applied - ignore)

A confirmed lesson that recurred alongside failure. Kept for the maintainer to review.

_none_

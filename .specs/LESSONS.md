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

## Quarantined (failed when applied - ignore)

A confirmed lesson that recurred alongside failure. Kept for the maintainer to review.

_none_

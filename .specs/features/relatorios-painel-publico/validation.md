# relatorios-painel-publico Validation

## Validation: relatorios-painel-publico - PASS ✅

**Date**: 2026-09-15
**Spec**: `.specs/features/relatorios-painel-publico/spec.md`
**Diff range**: `a49c1fe..20799b5` (5 commits: `bad6928` T1, `fbb8be7` T2, `683e4e8` T3, `df9dbb4` T4, `20799b5` T5)
**Verifier**: independent sub-agent (author ≠ verifier)

---

## Task Completion

| Task | Status  | Notes |
| ---- | ------- | ----- |
| T1   | ✅ Done | `spring-boot-starter-cache` + `com.redis:testcontainers-redis` + `spring.cache.redis.time-to-live: 60000` (`application.yml:42`) |
| T2   | ✅ Done | `PainelPublicoDTO.java` record, `implements Serializable` |
| T3   | ✅ Done | `CacheConfig implements CachingConfigurer` - confirmed by reading current code (not the tasks.md claim alone) and by sensor mutation 1 below |
| T4   | ✅ Done | `PainelPublicoService.java` + `PainelPublicoServiceIT.java` (3 tests) + `PainelPublicoFailOpenIT.java` (1 test) |
| T5   | ✅ Done | `PainelPublicoController.java` + `PainelPublicoControllerIT.java` (4 tests) |

---

## Spec-Anchored Acceptance Criteria

| Criterion (WHEN X THEN Y) | Spec-defined outcome | `file:line` + assertion | Result |
| -------------------------- | --------------------- | ------------------------ | ------ |
| PAINEL-01 (AC1): GET com `municipioId=X` retorna 200 com totais agregados a partir de `OcupacaoTurma` do município | `municipioId`, `totalVagasDisponiveis`, `totalCrechesComVaga`, `totalTurmas`, `atualizadoEm` batendo com soma manual | `PainelPublicoServiceIT.java:74-87` (`obterPainelAgregaCorretamenteVagasCrechesETurmas`, soma=10, creches=2, turmas=3) + `PainelPublicoControllerIT.java:55-67` (`consultarRetornaPainelAgregadoDoMunicipio`, HTTP 200, soma=10, creches=2, turmas=2) | ✅ PASS |
| PAINEL-02 (AC2): `municipioId` ausente/inválido → 400, sem consultar banco/cache | HTTP 400 | `PainelPublicoControllerIT.java:69-75` (`consultarSemMunicipioIdRetorna400`, status 400) + `:77-84` (`consultarComMunicipioIdNaoNumericoRetorna400`, status 400) | ⚠️ Spec-precision gap - status 400 é verificado; "sem consultar o banco nem o cache" **não** tem asserção própria (nenhum spy/verify prova zero chamadas ao repositório neste caminho). A garantia vem da arquitetura do Spring MVC (bind falha antes do método do controller rodar - mesmo padrão do Epic 1, precedente aceito em RELOC-03), não de um teste decisivo. Não-bloqueante, mesmo padrão já registrado como gap aceito na feature anterior. |
| PAINEL-03 (AC3): município sem linha → 200, totais zerados, `atualizadoEm=null`, nunca 404/erro | `totalVagasDisponiveis=0`, `totalCrechesComVaga=0`, `totalTurmas=0`, `atualizadoEm=null`, status 200 | `PainelPublicoServiceIT.java:89-97` (`obterPainelDeMunicipioSemDadosRetornaTotaisZerados`) + `PainelPublicoControllerIT.java:86-97` (`consultarMunicipioSemDadosRetornaTotaisZerados`, status 200 explícito) | ✅ PASS |
| PAINEL-04: cachear resultado por `municipioId` via Redis, TTL 60s | Config: `time-to-live: 60000`; comportamento: 2ª chamada não recalcula | `application.yml:42` (`time-to-live: 60000`) + `PainelPublicoServiceIT.java:100-111` (`obterPainelSegundaChamadaVemDoCacheSemChamarRepositorioDeNovo`, `verify(repository, times(1))` - não `times(2)` - e `assertThat(segunda).isEqualTo(primeira)`, provando deserialização correta) | ✅ PASS (expiração real após 60s não é testada - impraticável em teste automatizado; escopo do Test Coverage Matrix já limitava isso a "build gate only" para a config) |
| PAINEL-05 (AC5): Redis indisponível → consulta banco direto, retorna normalmente, fail-open | HTTP 200 com dado correto mesmo com Redis fora do ar | `PainelPublicoFailOpenIT.java:52-67` (`obterPainelContinuaFuncionandoComRedisIndisponivel` - `redis.stop()` seguido de `service.obterPainel(900L)`, assert totais corretos, sem exceção) | ✅ PASS - causalidade confirmada empiricamente pela mutação 1 do sensor (abaixo): revertendo `CacheConfig` para não implementar `CachingConfigurer` faz este teste falhar de verdade com `RedisConnectionFailureException` propagada, reproduzindo o bug histórico documentado em tasks.md |
| PAINEL-06 (AC6): `TenantContext.aplicar` antes de qualquer leitura de `OcupacaoTurma` | Chamada obrigatória antes do `repository.buscarPorFiltro` | `PainelPublicoService.java:32` (`TenantContext.aplicar(entityManager, municipioId);`, antes da linha 34) | ✅ PASS - não havia teste que provasse isso causalmente antes desta verificação; adicionada a mutação 4 do sensor (abaixo), que remove a chamada e mata 5/7 testes com erro real de RLS do Postgres (`unrecognized configuration parameter "app.current_tenant"`), confirmando que a proteção é real, não incidental |

**Status**: ⚠️ 1 spec-precision gap não-bloqueante (PAINEL-02), demais 5/6 critérios com evidência direta e outcome batendo com o valor definido na spec.

---

## Discrimination Sensor

Executado em dois `git worktree` isolados (`../creche-verify-scratch`, `../creche-verify-scratch2`), nunca `git stash`. Baseline pré-sensor da árvore real: `git status --porcelain` → só `?? docs/mock-data/` (não relacionado a esta feature). Confirmado idêntico após cada limpeza.

| Mutation | File:line | Description | Killed? |
| -------- | --------- | ------------ | ------- |
| 1 (obrigatória) | `CacheConfig.java` (reescrito para `@Bean CacheErrorHandler` solto, sem `implements CachingConfigurer`) | Reproduz exatamente o bug histórico documentado em tasks.md T3 "Correção pós-T4" | ✅ Killed - `PainelPublicoFailOpenIT` falha com `RedisConnectionFailureException: Unable to connect to Redis` propagada (não engolida) |
| 2 | `PainelPublicoService.java:40` | `turma.getVagasDisponiveis() > 0` → `>= 0` (quebra a contagem de `totalCrechesComVaga`) | ✅ Killed - `PainelPublicoServiceIT.obterPainelAgregaCorretamenteVagasCrechesETurmas:84` falha (espera 2, mutante conta 3) |
| 3 | `PainelPublicoService.java:29` | Adicionado `sync = true` ao `@Cacheable` | ❌ Survived - nenhum teste falhou. Ver "Achado adicional" abaixo. |
| 4 | `PainelPublicoService.java:32` | Removida a chamada `TenantContext.aplicar(entityManager, municipioId)` | ✅ Killed - 5/7 testes de `PainelPublicoServiceIT`+`PainelPublicoControllerIT` falham com erro real de RLS do Postgres (`unrecognized configuration parameter "app.current_tenant"` / `invalid input syntax for type bigint`) |

**Sensor depth**: lightweight (padrão, 4 mutações - acima do mínimo de 3 pedido)
**Result**: 3/4 killed - mutação 3 sobreviveu (ver achado abaixo)

### Achado adicional (mutação 3 - não é um mutante fraco no sentido usual)

O design.md afirma (Tech Decisions, linha 124): *"Pesquisa confirmou que `sync=true` faz o Spring pular o `CacheErrorHandler` em falhas de conexão - quebraria o requisito fail-open."* Testei essa afirmação empiricamente adicionando `sync=true` e rodando `PainelPublicoFailOpenIT` com o Redis derrubado: **o teste passou** - o fail-open continuou funcionando mesmo com `sync=true`.

Investiguei a causa lendo o bytecode de `CacheAspectSupport` (`spring-context-6.2.19.jar`, a versão realmente pinada por este projeto via Spring Boot 3.5.16): o método `executeSynchronized` (caminho usado quando `sync=true`) chama `doGet(cache, key, callable)` - o **mesmo** método de `AbstractCacheInvoker` usado pelo caminho não-síncrono, que invoca `errorHandler.handleCacheGetError(...)` em caso de falha. Ou seja, nesta versão do Spring Framework, `sync=true` **não** pula o `CacheErrorHandler` - a afirmação em design.md está desatualizada ou incorreta para a versão realmente usada (pode valer para uma versão mais antiga do Spring, que é possivelmente o que a "pesquisa" original encontrou).

Isso não é um bug de produção (o código real não usa `sync=true` - a decisão de não usar continua válida por outro motivo já documentado: evitar thundering herd), mas é:
1. Uma imprecisão factual em `design.md` que deveria ser corrigida (a razão técnica citada para a decisão está errada, mesmo que a decisão em si seja defensável por outro motivo).
2. Uma lacuna de proteção: a diretiva "NUNCA usar `sync=true`" (comentário em `PainelPublicoService.java:26-27` e javadoc de `CacheConfig.java:23-24`) não tem nenhum teste que a proteja - se alguém adicionar `sync=true` no futuro, nada no gate atual detecta a mudança (e, evidenciado acima, isso não quebraria fail-open nesta versão do Spring de qualquer forma, então a alegação de risco no próprio design.md pode levar a um `sync=true` incorretamente vetado por um motivo errado, mascarando o motivo real - thundering herd).

Isso foi registrado como lição (`sync-true-cache-error-handler`, ver `.specs/LESSONS.md`).

---

## Code Quality

| Principle | Status |
| --------- | ------ |
| Minimum code | ✅ |
| Surgical changes | ✅ |
| No scope creep | ✅ - filtro por `bairro` e invalidação ativa de cache corretamente descopados (Out of Scope documentado, não são gaps) |
| Matches patterns | ✅ - reusa `TenantContext.aplicar`, `buscarPorFiltro`, padrão de validação 400 do Epic 1 |
| Spec-anchored outcome check (asserted values match spec) | ✅ (com 1 gap de precisão não-bloqueante, ver PAINEL-02 acima) |
| Per-layer Coverage Expectation met | ✅ - Service: agregação + cache hit + fail-open; Controller: happy + 2 erros + vazio |
| Every test maps to a spec requirement - no unclaimed tests | ✅ - todos os 8 testes novos mapeiam a PAINEL-01/03/04/05/06 |
| Documented guidelines followed | `docs/context/relatorios.md`, `design.md` (padrão de camada de serviço nova para permitir proxy `@Cacheable`) |
| Autenticação ausente | ✅ Decisão do usuário, não é gap (painel público) |
| Permissão `@SaCheckPermission` | N/A - `PainelPublicoController` não tem, por decisão deliberada de escopo (painel público); nenhum outro controller de `alocacreche-relatorios` usa Sa-Token hoje (módulo não-autenticado por escolha registrada no Epic 1) |

---

## Edge Cases

- [x] TTL expira entre chamadas → recalcula e recacheia: comportamento padrão do Spring Cache/Redis TTL, não testado à parte (impraticável esperar 60s em teste automatizado); coberto indiretamente pelo teste de cache-hit provando que o mecanismo de cache/deserialização funciona.
- [x] Município que nunca recebeu evento `VagaLiberada` → 200 com totais zerados: `PainelPublicoServiceIT.java:89-97`, `PainelPublicoControllerIT.java:86-97`.

---

## Gate Check

- **Gate command**: `mvn -pl alocacreche-relatorios verify` (PowerShell com `JAVA_HOME`/`PATH` apontando para `C:\dev-tools\jdk\jdk-21.0.12.1+1` e `C:\dev-tools\maven\apache-maven-3.9.16\bin`)
- **Result**: `BUILD SUCCESS` - 43 testes totais (7 unitários via Surefire + 36 de integração via Failsafe), 0 falhas, 0 erros, 0 skipped
- **Testes novos desta feature**: 8 (`PainelPublicoServiceIT` = 3, `PainelPublicoFailOpenIT` = 1, `PainelPublicoControllerIT` = 4) - bate exatamente com a contagem declarada em tasks.md (T4: 4 testes, T5: 4 testes)
- **Delta**: +8 novos testes, nenhum removido
- **Skipped tests**: nenhum
- **Failures**: nenhuma

---

## Fix Plans

Nenhum fix bloqueante. Dois itens não-bloqueantes registrados como lições (ver abaixo), nenhum precisa de fix task antes de fechar a feature:

1. **PAINEL-02 spec-precision gap** - "sem consultar o banco nem o cache" não tem asserção decisiva própria. Mesmo padrão já aceito como não-bloqueante na feature anterior (`relatorios-leitura-ocupacao`, RELOC-03/04/06). Não corrigido aqui por consistência com o precedente e por não ser um requisito de corretude visível ao usuário (o efeito observável - 400 - está corretamente provado).
2. **design.md - afirmação incorreta sobre `sync=true`** - a razão técnica citada para não usar `sync=true` está desatualizada/incorreta para Spring Framework 6.2.19 (a versão real do projeto). A decisão de não usar `sync=true` continua válida (thundering herd), mas a citação errada deveria ser corrigida para não induzir decisões futuras a erro.

---

## Requirement Traceability Update

| Requirement | Previous Status | New Status |
| ----------- | ---------------- | ----------- |
| PAINEL-01 | Implementing | ✅ Verified |
| PAINEL-02 | Implementing | ✅ Verified (com spec-precision gap não-bloqueante) |
| PAINEL-03 | Implementing | ✅ Verified |
| PAINEL-04 | Implementing | ✅ Verified |
| PAINEL-05 | Implementing | ✅ Verified |
| PAINEL-06 | Implementing | ✅ Verified |

---

## Summary

**Overall**: ✅ Ready

**Spec-anchored check**: 6/6 ACs com evidência `file:line`; 5/6 com outcome exato batendo a spec; 1/6 (PAINEL-02) com gap de precisão não-bloqueante (mesmo padrão já aceito na feature anterior)

**Sensor**: 3/4 mutações mortas (mutação 3 sobreviveu por um motivo genuíno e documentado - não é fraqueza dos testes, é uma imprecisão factual em design.md sobre o comportamento de `sync=true` na versão real do Spring usada pelo projeto)

**Gate**: 43 passed, 0 failed (`mvn -pl alocacreche-relatorios verify` → `BUILD SUCCESS`)

**What works**: Agregação correta (soma/contagem/máximo) provada por teste + cálculo manual; cache hit real provado por spy (`times(1)`, não `times(2)`); fail-open real provado derrubando o container Redis via Testcontainers, com causalidade confirmada pela mutação 1 (reproduz o bug histórico real); RLS/multi-tenancy (PAINEL-06) agora com causalidade confirmada pela mutação 4 (não tinha antes desta verificação); validação HTTP 400; município vazio retorna zeros, nunca 404/erro.

**Issues found**:
1. PAINEL-02: gap de precisão de spec, não-bloqueante, consistente com precedente da feature anterior.
2. design.md contém uma afirmação tecnicamente incorreta sobre `sync=true` para a versão real do Spring Framework do projeto (6.2.19) - a decisão em si (não usar `sync=true`) continua correta por outro motivo (thundering herd), mas a citação de risco deveria ser corrigida.

**Next steps**: Nenhum bloqueante. Sugestão opcional (não obrigatória): corrigir a linha de design.md sobre `sync=true` para citar o motivo real (thundering herd) em vez do motivo empiricamente incorreto (pular o `CacheErrorHandler`).

---

## Update (2026-09-15): 2 gaps não-bloqueantes fechados

User pediu para corrigir os 2 gaps. Fechados:

1. **`sync=true` — correção de documentação**: `design.md:124`, javadoc de `CacheConfig.java` e
   comentário de `PainelPublicoService.java` corrigidos para citar o motivo real (thundering herd),
   removendo a afirmação empiricamente incorreta sobre `sync=true` pular o `CacheErrorHandler` nesta
   versão do Spring (6.2.19). Nenhuma mudança de comportamento — a decisão em si (não usar
   `sync=true`) já estava correta, só a justificativa citada mudou.
2. **PAINEL-02 — sinal decisivo de "zero chamadas ao banco/cache"**: `OcupacaoTurmaRepository` virou
   `@MockitoSpyBean` em `PainelPublicoControllerIT.java`; os dois testes de 400 agora chamam
   `verify(repository, never()).buscarPorFiltro(any(), any(), any())`. Diferente do gap similar já
   corrigido na feature `relatorios-ocupacao-vaga-liberada`, aqui `never()` é um sinal genuinamente
   decisivo (não ambíguo) — não há lógica de retry no caminho 400: o binding do Spring MVC falha
   *antes* do método do Controller ser invocado, então não existe um cenário "tentou e falhou depois"
   que `never()` deixaria de distinguir.

Gate re-run: `mvn -pl alocacreche-relatorios verify` → 43 testes (sem novos testes — as duas mudanças
foram doc-only e uma asserção adicionada em testes já existentes), `BUILD SUCCESS`. Nenhuma rodada de
verificação nova disparada — gaps não-bloqueantes fechados diretamente, mesmo padrão da feature
anterior.

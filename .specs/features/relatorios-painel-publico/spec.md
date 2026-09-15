# Painel público de ocupação (MSRelatorios) Specification

## Problem Statement

`alocacreche-relatorios` já expõe `GET /api/v1/relatorios/ocupacao` (relatório gerencial, detalhado
por turma/creche — feature `relatorios-leitura-ocupacao`), mas não há um resumo agregado e cacheado
para consulta pública de baixo custo, como a arquitetura prevê (`consultarPainelPublico`).

## Goals

- [ ] Expor `GET /api/v1/relatorios/painel-publico?municipioId=X` retornando um resumo agregado da
      ocupação do município (não o detalhe por turma, que já existe no Epic 1).
- [ ] Cachear o resultado via Redis (primeira vez que a dependência já presente no `pom.xml` é
      efetivamente usada).
- [ ] Manter a mesma disciplina de multi-tenancy (`TenantContext` + RLS) e a ausência de autenticação
      já decididas no Epic 1, por consistência.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Filtro por `bairro` | A assinatura sugerida em `docs/arquitetura/visao-geral.md` inclui `bairro`, mas a projeção `OcupacaoTurma` não tem nenhum campo de bairro/nome/endereço de creche — só `crecheId` como FK opaca. Não existe entidade `Creche` implementada em nenhum módulo, nem evento que carregue esse metadado para o MSRelatorios. **Decisão do usuário**: descopar por agora, filtrar só por `municipioId`. Retomar quando existir uma fonte de metadado de creche. |
| Invalidação ativa de cache | Decisão do usuário: TTL simples em vez de o consumidor de eventos invalidar o cache a cada upsert — evita acoplar o lado de escrita ao mecanismo de cache do lado de leitura. |
| Autenticação/autorização | Decisão do usuário (consistente com o Epic 1): painel é público por definição, sem `@SaCheckPermission`. |
| Detalhe por turma/creche | Já coberto por `GET /api/v1/relatorios/ocupacao` (Epic 1) — este endpoint é o resumo agregado, não substitui o detalhado. |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Filtro por bairro | Fora de escopo, só `municipioId` | Ver Out of Scope — decisão do usuário após identificação de que o dado não existe hoje | y |
| Estratégia de cache | TTL simples (Redis, `@Cacheable`) | Decisão do usuário — mais simples, aceita um pequeno atraso, consistente com a arquitetura já ser eventual consistency | y |
| Autenticação | Sem `@SaCheckPermission` | Decisão do usuário — painel público, mesma lógica do Epic 1 | y |
| Duração do TTL | 60 segundos | Valor de partida razoável para um painel público (atraso perceptível mas pequeno); ajustável sem mudança de contrato se se mostrar errado em uso real | y — proposta técnica, aprovada pelo usuário |
| Forma do `PainelDTO` | Agregado por município: `totalVagasDisponiveis` (soma), `totalCrechesComVaga` (contagem de creches distintas com `vagasDisponiveis > 0`), `totalTurmas` (contagem), `atualizadoEm` (máximo `atualizadoEm` da projeção) | Só há esses campos agregáveis na projeção `OcupacaoTurma` hoje (sem nome/bairro de creche, sem `vagasTotais` para calcular percentual — mesma limitação já documentada no Epic 1) | y — proposta técnica, aprovada pelo usuário |
| Comportamento com Redis indisponível | Fail-open — consulta o banco direto, sem erro | Cache aqui é otimização de custo, não requisito de corretude; um painel público não deveria cair só porque o Redis caiu | y — proposta técnica, aprovada pelo usuário |

**Open questions:** none — todas resolvidas ou registradas acima.

---

## User Stories

### P1: Consultar painel público de ocupação por município ⭐ MVP

**User Story**: Como cidadão, quero consultar um resumo rápido da disponibilidade de vagas do meu
município, sem precisar de login nem entender o detalhe por creche/turma.

**Why P1**: Núcleo do Epic 2 do roadmap — primeiro uso real do Redis no módulo, e o painel público que
a arquitetura já previa desde o início.

**Acceptance Criteria**:

1. WHEN o cliente chama `GET /api/v1/relatorios/painel-publico?municipioId=X` THEN o sistema SHALL
   retornar HTTP 200 com `municipioId`, `totalVagasDisponiveis`, `totalCrechesComVaga`, `totalTurmas`,
   `atualizadoEm`, agregados a partir de `OcupacaoTurma` restrita àquele município.
2. IF `municipioId` estiver ausente ou não for um número válido THEN o sistema SHALL retornar HTTP 400
   com uma mensagem de erro, sem consultar o banco nem o cache — mesma regra do Epic 1.
3. WHEN o município não tem nenhuma linha na projeção THEN o sistema SHALL retornar HTTP 200 com
   `totalVagasDisponiveis=0`, `totalCrechesComVaga=0`, `totalTurmas=0`, `atualizadoEm=null` — nunca 404,
   nunca erro.
4. The system SHALL cachear o resultado por `municipioId` via Redis com TTL de 60 segundos.
5. IF o Redis estiver indisponível THEN o sistema SHALL consultar o banco diretamente e retornar o
   resultado normalmente — a indisponibilidade do cache nunca deve quebrar o endpoint.
6. The system SHALL aplicar `TenantContext.aplicar(entityManager, municipioId)` antes de qualquer
   leitura de `OcupacaoTurma`, mesma disciplina do Epic 1 (AD-002).

**Independent Test**: com dados de mock inseridos, chamar o endpoint com `municipioId=1` e conferir os
totais agregados batem com a soma manual; chamar de novo dentro de 60s e confirmar que o Redis foi
consultado (cache hit); chamar sem `municipioId` e ver 400; derrubar o Redis e confirmar que o
endpoint continua respondendo (fail-open).

---

## Edge Cases

- WHEN o TTL do cache expira entre duas chamadas consecutivas THEN a segunda chamada recalcula do
  banco e recacheia — comportamento padrão de TTL, não um caso especial.
- IF `municipioId` for de um município que nunca recebeu nenhum evento `VagaLiberada` THEN o sistema
  SHALL retornar 200 com os totais zerados (mesmo comportamento do AC3).

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| PAINEL-01 | P1 | Design | Pending |
| PAINEL-02 | P1 | Design | Pending |
| PAINEL-03 | P1 | Design | Pending |
| PAINEL-04 | P1 | Design | Pending |
| PAINEL-05 | P1 | Design | Pending |
| PAINEL-06 | P1 | Design | Pending |

**ID format:** `PAINEL-NN`.

**Coverage:** 6 total, 0 mapped to tasks, 6 unmapped ⚠️ (esperado antes da fase Tasks/Execute).

---

## Success Criteria

- [ ] `GET /api/v1/relatorios/painel-publico?municipioId=X` retorna os totais agregados corretos a
      partir dos dados reais da projeção.
- [ ] Segunda chamada dentro do TTL não bate no banco (cache hit real, não só declarado).
- [ ] Queda do Redis não derruba o endpoint (fail-open comprovado, não só assumido).

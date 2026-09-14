# Leitura do relatório de ocupação (MSRelatorios) Specification

## Problem Statement

`alocacreche-relatorios` mantém a projeção `OcupacaoTurma` (turma/creche/vagas disponíveis por
município) desde a feature `relatorios-ocupacao-vaga-liberada`, mas o dado só existe no banco — não há
nenhum jeito de consultá-lo pela aplicação. É o primeiro endpoint HTTP de todo o projeto (nenhum outro
módulo tem Controller ainda).

## Goals

- [ ] Expor `GET /api/v1/relatorios/ocupacao` retornando a ocupação de vagas por município, com filtro
      opcional por creche e por turma.
- [ ] Aplicar a mesma disciplina de multi-tenancy (`TenantContext` + RLS) já usada no consumidor de
      eventos, para a leitura.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Painel público cacheado (`consultarPainelPublico`) | Feature separada (Epic 2 do roadmap) — depende de estratégia de cache com Redis ainda não decidida. |
| Relatórios de fila de espera, distância média de alocação, taxa de confirmação de matrícula | Dependem de eventos de domínio (`MatriculaCriada` etc.) que o monólito ainda não publica — bloqueado por implementação em `alocacreche-core`. |
| Cálculo de percentual/taxa de ocupação (vagas ocupadas ÷ total) | A projeção `OcupacaoTurma` nunca recebeu `vagasTotais` — o contrato do evento `VagaLiberada` só carrega `vagasDisponiveis`. Calcular percentual exigiria estender esse contrato ou consumir um evento adicional de `Turma`, fora do escopo desta feature. |
| Paginação/ordenação | Volume de dados hoje é pequeno (dezenas de creches por município) — lista completa sem paginação é aceitável para o MVP. |
| Endpoint de escrita/correção manual da projeção | Só leitura — a única forma de escrever em `OcupacaoTurma` continua sendo o consumidor de eventos. |
| Autenticação/autorização real (`@SaCheckPermission`) | **TODO explícito, adiamento estratégico (ver CLAUDE.md, "Estratégia de Resolução de Problemas").** `@SaCheckPermission` depende de um `StpInterface` que resolve permissões a partir de um login — e não existe nenhum modelo de usuário/permissão em nenhum módulo do projeto ainda (nenhum lugar chama `StpUtil.login(...)`). Implementar isso aqui exigiria ou (a) construir um modelo de permissão real dentro do MSRelatorios, que não é dono desse domínio (`Notificação + plataforma` é, ver `docs/context/notificacao-plataforma.md`), ou (b) um `StpInterface` que concede a permissão a qualquer chamador logado — o que na prática contornaria a checagem, proibido pelo `CLAUDE.md`. Decisão do usuário: endpoint fica sem autenticação por enquanto; retomar quando o módulo de segurança definir o mecanismo real. |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Prefixo de rota REST do projeto | `/api/v1/...` | `CLAUDE.md` deixa em aberto ("a definir no primeiro PR que criar o Gateway/controllers", com uma nova entrada em `docs/adr/`) — esta é a primeira vez que qualquer módulo expõe um Controller, então esta feature decide o prefixo por padrão de mercado. Precisa virar uma entrada `docs/adr/0003-*.md` durante Design/Execute, não só ficar implícito no código. | n — default de mercado, não uma preferência do usuário; registrar como ADR ao implementar |
| Nome da permissão Sa-Token | `relatorios:ocupacao:list` | Segue o formato `modulo:funcionalidade:operacao` já exigido pelo `CLAUDE.md`; `list` é uma das operações genéricas já catalogadas. | y |
| Autenticação neste endpoint | Sem `@SaCheckPermission` por agora — TODO documentado | Pesquisa confirmou que `@SaCheckPermission` exige um `StpInterface` que resolve permissões a partir de um login real, e nenhum módulo do projeto tem modelo de usuário/permissão ainda. Construir isso aqui expandiria o escopo para um domínio que não pertence a `alocacreche-relatorios` (segurança é de `Notificação + plataforma`), ou exigiria um stub que contornaria a checagem (proibido pelo CLAUDE.md). Decisão do usuário, revista após essa descoberta. | y |
| Forma da resposta | Sempre lista (0..N), nunca objeto único nem 404 | Decisão do usuário — um único endpoint cobre "tudo do município", "de uma creche" ou "de uma turma" com o mesmo formato de resposta. | y |
| Campos do DTO de resposta | Espelha `OcupacaoTurma`: `turmaId`, `crecheId`, `municipioId`, `vagasDisponiveis`, `ultimaSequenciaAplicada`, `atualizadoEm` | Não expõe a entidade JPA diretamente; não inclui campos que a projeção não tem (`vagasTotais`, percentual — ver Out of Scope). | n/a — decorre do dado existente, não é uma preferência a confirmar |

**Open questions:** none — todas resolvidas ou registradas acima.

---

## User Stories

### P1: Consultar ocupação de vagas por município ⭐ MVP

**User Story**: Como gestor municipal, quero consultar a ocupação de vagas das creches do meu
município — de todas, de uma creche específica, ou de uma turma específica — para saber quantas vagas
estão disponíveis agora.

**Why P1**: É o núcleo do Epic 1 do roadmap ("expor o que já existe") — sem isso, a projeção mantida
pelo consumidor de eventos não tem nenhum uso prático.

**Acceptance Criteria**:

1. WHEN o cliente chama `GET /api/v1/relatorios/ocupacao?municipioId=X` THEN o sistema SHALL retornar
   HTTP 200 com uma lista de 0 a N registros de ocupação (`turmaId`, `crecheId`, `municipioId`,
   `vagasDisponiveis`, `ultimaSequenciaAplicada`, `atualizadoEm`) restrita ao `municipioId` informado.
2. WHEN o filtro incluir `crecheId` THEN o sistema SHALL retornar somente as turmas daquela creche,
   dentro do `municipioId` informado.
3. WHEN o filtro incluir `turmaId` THEN o sistema SHALL retornar somente a linha daquela turma (0 ou 1
   registro), ainda restrita ao `municipioId` informado.
4. IF `municipioId` estiver ausente ou não for um número válido THEN o sistema SHALL retornar HTTP 400
   com uma mensagem de erro, sem consultar o banco.
5. WHEN nenhuma linha corresponde ao filtro informado THEN o sistema SHALL retornar HTTP 200 com lista
   vazia — nunca 404, nunca erro (inclui o caso de um município que nunca recebeu nenhum evento
   `VagaLiberada`).
6. IF `crecheId` ou `turmaId` informados pertencerem a um município diferente do `municipioId`
   informado THEN o sistema SHALL retornar lista vazia — nunca dado de outro município (fail-closed via
   RLS, ver AD-002; nunca um erro que revele que o recurso existe em outro tenant).
7. The system SHALL aplicar `TenantContext.aplicar(entityManager, municipioId)` antes de qualquer
   leitura de `OcupacaoTurma`, na mesma transação da consulta — reaproveita a disciplina já usada pelo
   consumidor de eventos (AD-002).

**Independent Test**: com dados de mock já inseridos (`docs/mock-data/seed-ocupacao-turma.sql`), chamar
o endpoint com `municipioId=1` e ver a lista de turmas daquele município; repetir com `crecheId`/
`turmaId` para confirmar o filtro; chamar sem `municipioId` e ver 400; chamar com `crecheId` de outro
município e ver lista vazia.

---

## Edge Cases

- IF `municipioId` for de um município que nunca recebeu nenhum evento `VagaLiberada` THEN o sistema
  SHALL retornar 200 com lista vazia (mesmo comportamento do AC5, não um caso especial).
- WHEN `crecheId` for informado sem `turmaId` (ou vice-versa) THEN o sistema SHALL aplicar apenas o
  filtro informado — ambos são independentes e opcionais.
- IF `crecheId` ou `turmaId` vierem com valor não numérico THEN o sistema SHALL retornar HTTP 400, mesma
  regra do AC4 para `municipioId`.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| RELOC-01 | P1 | Design | Pending |
| RELOC-02 | P1 | Design | Pending |
| RELOC-03 | P1 | Design | Pending |
| RELOC-04 | P1 | Design | Pending |
| RELOC-05 | P1 | Design | Pending |
| RELOC-06 | P1 | Design | Pending |
| RELOC-07 | P1 | Design | Pending |

**ID format:** `RELOC-NN` (REL­atório de OCupação).

**Coverage:** 7 total, 0 mapped to tasks, 7 unmapped ⚠️ (esperado antes da fase Tasks/Execute).

---

## Success Criteria

- [ ] `GET /api/v1/relatorios/ocupacao?municipioId=X` retorna os dados reais já inseridos por
      `seed-ocupacao-turma.sql`, filtráveis por creche/turma.
- [ ] Chamada sem `municipioId` retorna 400.
- [ ] Nenhuma chamada retorna dado de um município diferente do informado, mesmo com `crecheId`/
      `turmaId` de outro tenant.
- [ ] TODO de autenticação documentado de forma rastreável (não silenciosamente esquecido).

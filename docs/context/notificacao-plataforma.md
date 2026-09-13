# Contexto — Módulo Notificação + Plataforma

> Carregue este arquivo antes de implementar ou revisar código em `alocacreche-core/.../notificacao/`,
> no Gateway, ou em segurança/observabilidade transversal. Contexto geral: `CLAUDE.md`. Decisões
> arquiteturais: `docs/adr/0001-fundacao-arquitetural.md`.

## Dono exclusivo do pacote

`Notificacao`, `Municipio`.

## Responsabilidades de domínio

- Envio de notificações multicanal (`enviarNotificacao`: e-mail, SMS, push).
- Consumo idempotente de eventos do RabbitMQ (`aoReceberMatriculaCriada`).

## Responsabilidades de plataforma (cross-cutting, mas mantidas por este integrante)

- **Publicador da Outbox** (`publicarEventosPendentes`, `@Scheduled`): lê a tabela de outbox — que os
  módulos Inscrição/Matrícula gravam — e publica no RabbitMQ com retry. Ver
  `.claude/skills/java-development/spring-boot/sb-outbox-pattern.md` para a mecânica completa
  (at-least-once, nunca marcar `PROCESSADO` antes de confirmar o publish).
- **Purga da Outbox**: job diário separado remove linhas `PROCESSADO` com mais de 7 dias — janela
  suficiente para reprocessar em caso de incidente, sem deixar a tabela crescer sem limite.
- **API Gateway** (Spring Cloud Gateway): configuração de roteamento para o monólito e para o
  MSRelatorios. Deploy próprio (`alocacreche-gateway/`), não faz parte do código-fonte de nenhum dos
  dois backends.
- **Autenticação/autorização**: Sa-Token em modo stateless + extensão `sa-token-jwt`. Um único mecanismo
  de autenticação — não trate "Sa-Token" e "JWT" como dois mecanismos complementares.
- **Isolamento multi-tenant** (RLS + `@TenantId`): mesma responsabilidade transversal. Ver
  `.claude/skills/java-development/spring-boot/sb-multi-tenancy-rls.md`.
- **Observabilidade**: Micrometer Tracing/OpenTelemetry, escopo restrito à chamada externa de
  geocodificação (feita pelo módulo Matrícula) e ao fluxo assíncrono até o MSRelatorios — o núcleo
  transacional não precisa de correlação distribuída porque roda numa única transação.
- **Onboarding de novo município**: cadastro do município, configuração inicial de
  `ConfiguracaoPriorizacao`, cadastro em lote das creches daquele município.

## Regras específicas deste módulo

- **Idempotência por dedup de ID de evento** (não upsert) — enviar a mesma notificação duas vezes é o
  erro real a evitar aqui, diferente do MSRelatorios (que usa upsert por chave de negócio; ver
  `docs/context/relatorios.md`).
- **Mascarar CPF, renda e critério de vulnerabilidade social** em qualquer log ou payload que não
  precise do dado bruto (LGPD) — isto vale especialmente aqui, ponto de saída para provedores externos
  de e-mail/SMS/push.
- **Outbox fica fora do escopo de Row-Level Security, por decisão explícita** (ADR 0001, decisão 11): o
  publicador só repassa payload já decidido dentro de uma transação de negócio já tenant-scoped; o
  schema da Outbox não tem `municipio_id` nem política RLS. Não trate isso como uma lacuna a corrigir —
  é uma exceção documentada.

## Aspecto técnico de destaque

Outbox + fila (RabbitMQ) fire-and-forget + segurança transversal (Sa-Token/JWT) + tracing distribuído.

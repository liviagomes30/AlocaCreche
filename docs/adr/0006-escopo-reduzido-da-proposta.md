# ADR 0006 — Escopo reduzido da proposta (3 funcionalidades por stack)

> Status: aceito
> Revisa a decisão 3 e parte da decisão 4 de `docs/adr/0004-divisao-equipe-tres-integrantes.md`
> (`GeolocalizacaoService` na Stack C e itens de Stack C adiados). Não muda decisões de arquitetura de
> sistema da ADR 0001.

## Contexto

Ao longo do projeto a proposta foi simplificada para três funcionalidades por stack, escolhidas para
sustentar a demonstração de ponta a ponta.

## Decisão

- **Stack A — Inscrição & Fila:** cadastro de inscrição (`Responsavel`, `Crianca`, `Inscricao`),
  score de prioridade com pesos fixos (sem Strategy configurável e sem versionamento dinâmico) e
  consulta de posição na fila lida do Redis.
- **Stack B — Matrícula & Vagas/Creches:** alocação automática de vaga ao liberar uma (lock otimista
  com `@Version` e Spring Retry), confirmação de matrícula e sugestão de creche próxima por distância
  de Haversine sobre coordenadas já cadastradas.
- **Stack C — Relatórios + integração:** Publicador da Outbox para o RabbitMQ, painel público de
  ocupação e relatórios de fila de espera e confirmação de matrícula.
- **Geocodificação externa ao vivo está fora do escopo.** `GeolocalizacaoService` deixa de ser entrega
  da Stack C; a distância usa coordenadas cadastradas. O contrato continua em `core/comum/geolocalizacao`
  caso a geocodificação seja retomada.
- Ficam como limitações conhecidas, e não como requisitos: Flyway (o esquema usa `ddl-auto`),
  observabilidade com Micrometer e tracing, backoff do Outbox e notificação multicanal.
- Permanecem obrigatórios: o lock otimista da alocação, o isolamento multi-tenant (RLS) e a
  autenticação por token (ADR 0005).

## Consequências

- O score usa o fator de distância 0 enquanto não houver cálculo de distância na inscrição.
- O painel é atualizado em segundos pelo fluxo Outbox → RabbitMQ → projeção, por consulta com cache
  invalidado; não há push em tempo real (SSE ou WebSocket).
- Documentos de contexto que citam Strategy configurável ou geocodificação devem ser lidos à luz
  desta ADR.

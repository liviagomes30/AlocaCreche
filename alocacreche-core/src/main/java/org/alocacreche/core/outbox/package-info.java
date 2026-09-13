/**
 * Outbox — infraestrutura compartilhada, nao propriedade de um modulo (ver ADR 0001,
 * decisao 10). O modulo Matricula &amp; Vagas/Creches grava o evento de negocio aqui na
 * mesma transacao local da escrita; o modulo Notificacao + plataforma opera o
 * publicador assincrono que le esta tabela e publica no RabbitMQ.
 *
 * <p>Deliberadamente fora do escopo de Row-Level Security (ADR 0001, decisao 11): so
 * repassa payload ja decidido dentro de uma transacao de negocio ja tenant-scoped.
 *
 * <p>Mecanica completa, incluindo idempotencia do consumidor (dedup vs. upsert por
 * chave de negocio) em
 * {@code .claude/skills/java-development/spring-boot/sb-outbox-pattern.md}.
 */
package org.alocacreche.core.outbox;

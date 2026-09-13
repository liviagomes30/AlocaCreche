/**
 * MSRelatorios — unico servico com deploy e banco realmente proprios. Consome, via
 * RabbitMQ, os eventos de dominio publicados pela Outbox do monolito, mantendo
 * projecoes de leitura por upsert idempotente (nunca contador incremental).
 *
 * <p>Antes de implementar aqui, leia {@code docs/context/relatorios.md}.
 */
package org.alocacreche.relatorios;

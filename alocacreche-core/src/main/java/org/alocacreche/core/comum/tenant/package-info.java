/**
 * Isolamento multi-tenant (ADR 0001, decisao 14) - aplica o municipio corrente na sessao Postgres
 * para a Row-Level Security definida em {@code src/main/resources/schema.sql}. Infraestrutura
 * compartilhada entre todos os modulos de dominio (mesmo tratamento de {@code outbox} e do
 * Gateway - ver docs/adr/0004-divisao-equipe-tres-integrantes.md para quem opera isto).
 */
package org.alocacreche.core.comum.tenant;

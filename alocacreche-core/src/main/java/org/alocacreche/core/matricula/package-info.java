/**
 * Modulo Matricula &amp; Vagas/Creches. Dono exclusivo: Matricula, Turma, Creche,
 * vagasDisponiveis. Unico modulo autorizado a decrementar vagasDisponiveis ou
 * criar/atualizar Matricula.
 *
 * <p>Antes de implementar aqui, leia {@code docs/context/matricula-vagas.md} — a garantia
 * central de consistencia (transacao local + lock otimista), os gotchas de self-invocation
 * com {@code @Transactional}/{@code @Retryable}/{@code @Scheduled}, e a regra de
 * multi-tenancy para jobs agendados.
 */
package org.alocacreche.core.matricula;

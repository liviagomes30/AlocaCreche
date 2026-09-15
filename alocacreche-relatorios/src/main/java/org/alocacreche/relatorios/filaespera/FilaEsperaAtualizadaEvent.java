package org.alocacreche.relatorios.filaespera;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Payload do evento FilaEsperaAtualizada, publicado pela Outbox do modulo Inscricao & Fila (ainda
 * nao implementado em alocacreche-core) e consumido via RabbitMQ. Granularidade por inscricao, nao
 * por turma - cada evento carrega o estado completo conhecido da inscricao, nunca um delta.
 * Contrato definido em .specs/features/relatorios-fila-espera/spec.md e design.md.
 *
 * <p>dataEntrada/dataSaida como LocalDate (nao LocalDateTime): tempoMedioEsperaDias e a unidade de
 * saida do relatorio, e date - date no Postgres ja retorna dias inteiros diretamente. dataSaida
 * nula enquanto a inscricao ainda aguarda na fila.
 */
public record FilaEsperaAtualizadaEvent(
        Long eventId,
        String tipoEvento,
        Long municipioId,
        Long turmaId,
        Long crecheId,
        Long inscricaoId,
        LocalDate dataEntrada,
        LocalDate dataSaida,
        LocalDateTime ocorridoEm) {
}

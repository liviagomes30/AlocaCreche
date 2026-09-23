package org.alocacreche.relatorios.matricula;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Payload provisorio do evento MatriculaAtualizada, publicado pela Outbox do modulo Matricula &amp;
 * Vagas/Creches (ainda nao implementado em alocacreche-core). Mesmo raciocinio de
 * FilaEsperaAtualizadaEvent: granularidade por matricula, cada evento carrega o estado completo
 * conhecido da matricula, nunca um delta - permite reconstruir tanto o relatorio de distancia media
 * de alocacao quanto o de taxa de confirmacao a partir da mesma projecao.
 *
 * <p>Contrato definido do lado do consumidor (MSRelatorios), antecipando o que o dono do modulo
 * Matricula vai publicar - o unico campo sem correspondencia direta no diagrama de classes
 * (docs/arquitetura/visao-geral.md) e {@code distanciaKm}: a distancia calculada em
 * {@code alocarProximoElegivel} (usada no calculo de score via {@code pesoDistancia}) nao e
 * persistida em {@code Matricula} hoje, mas precisa estar no payload para este relatorio existir sem
 * recalcular geocodificacao aqui. Alinhar com o dono de Matricula antes de considerar este contrato
 * definitivo.
 *
 * <p>{@code dataConvocacao} espelha o campo homonimo de {@code Matricula} no diagrama de classes;
 * {@code dataConfirmacao} e nulo enquanto o status nao for CONFIRMADA.
 */
public record MatriculaAtualizadaEvent(
        Long eventId,
        String tipoEvento,
        Long municipioId,
        Long turmaId,
        Long crecheId,
        Long inscricaoId,
        Long matriculaId,
        StatusMatricula status,
        Double distanciaKm,
        LocalDate dataConvocacao,
        LocalDate dataConfirmacao,
        LocalDateTime ocorridoEm) {
}

package org.alocacreche.core.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre so a orquestracao (chama o service via bean externo, nunca this) e o isolamento de falha -
 * o agendamento por cron e a purga contra o banco real ficam com OutboxPublicadorConsumerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPurgaScheduler")
class OutboxPurgaSchedulerTest {

    @Mock private OutboxPurgaService service;

    private OutboxPurgaScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPurgaScheduler(service);
    }

    @Test
    @DisplayName("executar delega a purga ao service")
    void executarDelegaAPurgaAoService() {
        when(service.purgarProcessadosAntigos()).thenReturn(3);

        scheduler.executar();

        verify(service).purgarProcessadosAntigos();
    }

    @Test
    @DisplayName("falha na purga nao propaga excecao para o agendador")
    void falhaNaPurgaNaoPropagaExcecao() {
        when(service.purgarProcessadosAntigos()).thenThrow(new RuntimeException("banco fora"));

        assertThatCode(() -> scheduler.executar()).doesNotThrowAnyException();
    }
}

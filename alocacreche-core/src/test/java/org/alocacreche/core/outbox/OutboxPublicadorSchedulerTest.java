package org.alocacreche.core.outbox;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre so a orquestracao do loop (chama o service uma vez por evento pendente, via bean externo -
 * nunca this.publicarEvento) - a prova de que o @Transactional de fato funciona por evento e do
 * @Scheduled disparar sozinho fica com OutboxPublicadorConsumerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublicadorScheduler")
class OutboxPublicadorSchedulerTest {

    @Mock private OutboxPublicadorService service;

    private OutboxPublicadorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublicadorScheduler(service);
    }

    @Test
    @DisplayName("sem eventos pendentes, nao chama publicarEvento")
    void semEventosPendentesNaoChamaPublicarEvento() {
        when(service.buscarPendentes()).thenReturn(Collections.emptyList());

        scheduler.executar();

        verify(service, never()).publicarEvento(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("chama publicarEvento uma vez por evento pendente")
    void chamaPublicarEventoUmaVezPorEventoPendente() {
        OutboxEvent evento1 = new OutboxEvent("vaga.liberada", "{}");
        OutboxEvent evento2 = new OutboxEvent("fila.atualizada", "{}");
        when(service.buscarPendentes()).thenReturn(List.of(evento1, evento2));

        scheduler.executar();

        verify(service, times(1)).publicarEvento(evento1);
        verify(service, times(1)).publicarEvento(evento2);
    }
}

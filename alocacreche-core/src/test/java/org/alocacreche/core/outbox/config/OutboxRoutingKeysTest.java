package org.alocacreche.core.outbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.alocacreche.core.outbox.service.OutboxEventWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fixa a definicao unica das routing keys conhecidas, compartilhada pelo gravador e pelo publicador. */
@DisplayName("Routing keys conhecidas da Outbox")
class OutboxRoutingKeysTest {

    @Test
    @DisplayName("contem exatamente vaga.liberada, fila.atualizada e matricula.atualizada")
    void contemExatamenteAsTresRoutingKeys() {
        assertThat(OutboxRabbitConfig.ROUTING_KEYS_CONHECIDAS)
                .containsExactlyInAnyOrder(
                        "vaga.liberada", "fila.atualizada", "matricula.atualizada");
    }

    @Test
    @DisplayName("o gravador continua rejeitando routing key desconhecida")
    void gravadorRejeitaRoutingKeyDesconhecida() {
        OutboxEventWriter writer =
                new OutboxEventWriter(mock(OutboxEventRepository.class), new ObjectMapper());

        assertThatThrownBy(() -> writer.registrar("evento.inexistente", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

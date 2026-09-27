package org.alocacreche.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Cobre a logica de decisao de OutboxPublicadorService (sucesso, tipoEvento desconhecido, falha de
 * broker) - o fluxo fim a fim real (RabbitMQ/Postgres reais, @Scheduled disparando sozinho) e
 * coberto por OutboxPublicadorConsumerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublicadorService")
class OutboxPublicadorServiceTest {

    @Mock private OutboxEventRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;

    private OutboxPublicadorService service;

    @BeforeEach
    void setUp() {
        service = new OutboxPublicadorService(repository, rabbitTemplate);
    }

    @Test
    @DisplayName("buscarPendentes delega ao repositorio, ordenado por id")
    void buscarPendentesDelegaAoRepositorio() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        when(repository.findByStatusOrderByIdAsc("PENDENTE")).thenReturn(List.of(evento));

        List<OutboxEvent> pendentes = service.buscarPendentes();

        assertThat(pendentes).containsExactly(evento);
    }

    @Test
    @DisplayName("publica com sucesso e marca PROCESSADO")
    void publicaComSucessoEMarcaProcessado() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{\"turmaId\":1}");

        service.publicarEvento(evento);

        verify(rabbitTemplate)
                .convertAndSend(
                        OutboxRabbitConfig.DOMINIO_EXCHANGE, "vaga.liberada", "{\"turmaId\":1}");
        assertThat(evento.getStatus()).isEqualTo("PROCESSADO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("tipoEvento desconhecido marca ERRO sem tentar publicar")
    void tipoEventoDesconhecidoMarcaErroSemPublicar() {
        OutboxEvent evento = new OutboxEvent("evento.inexistente", "{}");

        service.publicarEvento(evento);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("falha de publish mantem PENDENTE e nao propaga a excecao")
    void falhaDePublishMantemPendenteSemPropagarExcecao() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        doThrow(new AmqpException("broker indisponivel"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), anyString());

        service.publicarEvento(evento);

        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }
}
